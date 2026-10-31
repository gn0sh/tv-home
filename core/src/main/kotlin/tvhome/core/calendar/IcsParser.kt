package tvhome.core.calendar

import biweekly.Biweekly
import biweekly.ICalendar
import biweekly.component.VEvent
import biweekly.property.ICalProperty
import biweekly.util.Frequency
import biweekly.util.ICalDate
import biweekly.util.Recurrence
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/** Thrown for unparseable input. The message is fixed text: it never contains calendar content or URLs. */
class IcsParseException : Exception("ICS parse failed")

/**
 * Parses ICS text into concrete [EventInstance]s overlapping a window, with bounded work.
 *
 * - biweekly is used for the text syntax only (lines, escaping, property values, the RRULE parts). VTIMEZONE
 *   components are removed before biweekly sees the text, and TZIDs go through [ZoneResolver] (IANA, Windows names,
 *   else the device zone).
 * - Recurrence is expanded by [RecurrenceExpander] (our own, bounded) in the event's local frame: TZID events in their
 *   zone, floating ones in the device zone, so wall-clock times hold across DST.
 * - RECURRENCE-ID overrides: VEVENTs are grouped by UID, the original instance matching each RECURRENCE-ID is dropped
 *   and the override added. RANGE=THISANDFUTURE is not handled: such an override replaces only its own instance.
 * - STATUS:CANCELLED events and overrides are dropped. One VEVENT that fails is skipped; the rest of the feed stands.
 * - Work is capped by [Limits]: VEVENT count (more fails the parse), a parse-wide step budget, a per-series budget and
 *   a wall-clock deadline. When a budget runs out, the series still unexpanded are skipped and what was found is
 *   returned (one-off events are processed first, so only recurring series can be lost).
 * - At most [MAX_INSTANCES] are returned: instances overlapping `focus` (today and tomorrow) are kept first, then the
 *   earliest; the result is in start order.
 */
object IcsParser {
    /** Hard cap on instances returned by one parse, across all events. */
    const val MAX_INSTANCES = 500

    data class Limits(
        val maxVevents: Int = 30_000,
        val maxSteps: Long = 2_000_000,
        val maxStepsPerSeries: Long = 200_000,
        val deadline: Duration = Duration.ofSeconds(2),
    )

    /**
     * @param window instances overlapping this range are returned (timed: [start,end) overlap; all-day: date range
     *   overlap with the window's local dates in [zone]).
     * @param zone the device zone: floating times, unknown TZIDs and all-day placement.
     * @param focus instances overlapping it win when the cap cuts; null means the whole window.
     * @throws IcsParseException unparseable text or more than [Limits.maxVevents] VEVENTs.
     * @throws InterruptedException if the thread is interrupted (cancellation via `runInterruptible`).
     */
    fun parse(
        text: String,
        window: InstantRange,
        zone: ZoneId,
        focus: InstantRange? = null,
        limits: Limits = Limits(),
        nanoTime: () -> Long = System::nanoTime,
    ): List<EventInstance> {
        val budget = Budget(limits.maxSteps, limits.maxStepsPerSeries, nanoTime() + limits.deadline.toNanos(), nanoTime)
        val clean = IcsPrepass.prepare(text, limits.maxVevents) ?: throw IcsParseException()
        val calendars: List<ICalendar> = try {
            Biweekly.parse(clean).all()
        } catch (e: Exception) {
            throw IcsParseException()
        }
        if (calendars.isEmpty()) throw IcsParseException()

        val out = Collector(focus ?: window, zone)
        val ctx = Ctx(window, zone, budget, out)
        // Phase 1: one-off events (cheap). Phase 2: recurring series, which a budget can cut short.
        val groups = calendars.flatMap { cal -> cal.events.groupBy { it.uid?.value ?: "" }.map { cal to it } }
        val (recurring, single) = groups.partition { (_, g) -> g.value.any { isRecurringMaster(it) } }
        for ((cal, g) in single + recurring) {
            if (budget.exhausted) break
            budget.startSeries()
            try {
                collect(cal, g.key, g.value, ctx)
            } catch (e: InterruptedException) {
                throw e
            } catch (e: Exception) {
                // one bad VEVENT (odd TZID, broken value) is skipped; nothing from it escapes
            }
        }
        return out.result()
    }

    // ---------------------------------------------------------------- internals

    private class Ctx(val window: InstantRange, val zone: ZoneId, val budget: Budget, val out: Collector)

    private class Raw(val uid: String, val title: String, val start: EventTime, val end: EventTime?)

    /** A resolved DTSTART-like value: wall clock plus the zone it is read in (null for all-day). */
    private class Point(val local: LocalDateTime, val zone: ZoneId?) {
        val allDay get() = zone == null
        fun toEventTime(): EventTime =
            if (zone == null) EventTime.AllDay(local.toLocalDate()) else EventTime.At(local.atZone(zone).toInstant())
    }

    private fun isRecurringMaster(e: VEvent) =
        e.recurrenceId == null && (e.recurrenceRule != null || e.recurrenceDates.isNotEmpty())

    private fun isCancelled(e: VEvent) = e.status?.isCancelled == true

    private fun title(e: VEvent): String = e.summary?.value?.trim().orEmpty()

    private fun tzidOf(cal: ICalendar, prop: ICalProperty): String? =
        cal.timezoneInfo.getTimezone(prop)?.let { it.globalId ?: it.component?.timezoneId?.value }
            ?: prop.parameters.timezoneId

    private fun point(cal: ICalendar, prop: ICalProperty, d: ICalDate, zone: ZoneId): Point {
        val c = d.rawComponents
        if (!d.hasTime()) return Point(LocalDate.of(c.year, c.month, c.date).atStartOfDay(), null)
        val local = LocalDateTime.of(c.year, c.month, c.date, c.hour, c.minute, c.second.coerceIn(0, 59))
        val z: ZoneId = when {
            c.isUtc -> ZoneOffset.UTC
            else -> tzidOf(cal, prop)?.let { ZoneResolver.resolve(it, zone) } ?: zone // no TZID: floating
        }
        return Point(local, z)
    }

    /** Match keys for EXDATE / RECURRENCE-ID: exact instants for timed values, local dates for DATE values. */
    private class Keys {
        val instants = HashSet<Instant>()
        val dates = HashSet<LocalDate>()
        fun add(p: Point) {
            when (val t = p.toEventTime()) {
                is EventTime.At -> instants += t.instant
                is EventTime.AllDay -> dates += t.date
            }
        }
        /** [frame] is the series' zone, used to compare a timed instance with a DATE key. */
        fun matches(t: EventTime, frame: ZoneId?): Boolean = when (t) {
            is EventTime.At -> t.instant in instants || (dates.isNotEmpty() && frame != null &&
                t.instant.atZone(frame).toLocalDate() in dates)
            is EventTime.AllDay -> t.date in dates
        }
    }

    private fun collect(cal: ICalendar, uid: String, events: List<VEvent>, ctx: Ctx) {
        val masters = events.filter { it.recurrenceId == null }
        val overrides = events.filter { it.recurrenceId != null }

        val replaced = Keys()
        for (ov in overrides) {
            val rid = ov.recurrenceId.value ?: continue
            replaced.add(point(cal, ov.recurrenceId, rid, ctx.zone))
        }
        for (m in masters) {
            if (isCancelled(m)) continue
            val ds = m.dateStart?.value ?: continue
            val start = point(cal, m.dateStart, ds, ctx.zone)
            val excluded = Keys()
            for (ex in m.exceptionDates) for (v in ex.values) {
                if (!ctx.budget.take()) break
                excluded.add(point(cal, ex, v, ctx.zone))
            }
            for (inst in expand(cal, m, uid, start, ctx)) {
                if (replaced.matches(inst.start, start.zone) || excluded.matches(inst.start, start.zone)) continue
                ctx.out.add(inst, ctx.window)
            }
        }
        for (ov in overrides) {
            if (isCancelled(ov)) continue
            val ds = ov.dateStart?.value ?: continue
            val start = point(cal, ov.dateStart, ds, ctx.zone)
            ctx.out.add(Raw(uid, title(ov), start.toEventTime(), endOf(cal, ov, start, ctx.zone)), ctx.window)
        }
    }

    /** End from DTEND or DURATION; null if neither is present. */
    private fun endOf(cal: ICalendar, e: VEvent, start: Point, zone: ZoneId): EventTime? {
        val de = e.dateEnd
        if (de != null && de.value != null) return point(cal, de, de.value, zone).toEventTime()
        val dur = e.duration?.value ?: return null
        return when (val s = start.toEventTime()) {
            is EventTime.At -> EventTime.At(s.instant.plusMillis(dur.toMillis()))
            is EventTime.AllDay -> EventTime.AllDay(s.date.plusDays(maxOf(1L, dur.toMillis() / 86_400_000L)))
        }
    }

    /** All instances of master [e] (DTSTART, RRULE, RDATE) whose local date is near the window; not yet filtered. */
    private fun expand(cal: ICalendar, e: VEvent, uid: String, start: Point, ctx: Ctx): List<Raw> {
        val title = title(e)
        val end = endOf(cal, e, start, ctx.zone)
        val first = start.toEventTime()
        // One instance's length: exact millis for timed events, whole days for all-day ones.
        val spanMs: Long = if (first is EventTime.At && end is EventTime.At) {
            (end.instant.toEpochMilli() - first.instant.toEpochMilli()).coerceAtLeast(0)
        } else {
            0L
        }
        val spanDays: Long = if (first is EventTime.AllDay) {
            ((end as? EventTime.AllDay)?.date?.let { ChronoUnit.DAYS.between(first.date, it) } ?: 1L).coerceAtLeast(1)
        } else {
            0L
        }
        fun make(p: Point): Raw = if (p.allDay) {
            val d = p.local.toLocalDate()
            Raw(uid, title, EventTime.AllDay(d), EventTime.AllDay(d.plusDays(spanDays)))
        } else {
            val s = p.local.atZone(p.zone).toInstant()
            Raw(uid, title, EventTime.At(s), if (end != null) EventTime.At(s.plusMillis(spanMs)) else null)
        }

        val rrule = e.recurrenceRule?.value
        if (rrule == null && e.recurrenceDates.isEmpty()) {
            return listOf(Raw(uid, title, first, end))
        }
        // The local dates (in the series' frame) whose instances can overlap the window, with a day of slack.
        val frame = start.zone ?: ctx.zone
        val from = ctx.window.start.minusMillis(spanMs).atZone(frame).toLocalDate().minusDays(spanDays + 1)
        val to = ctx.window.end.atZone(frame).toLocalDate().plusDays(1)

        val out = LinkedHashMap<EventTime, Raw>()
        val rule = rrule?.let { toRule(it, start, cal) }
        val starts: List<LocalDateTime> = if (rule != null) {
            RecurrenceExpander(rule, start.local, start.allDay).between(from, to, ctx.budget) ?: emptyList()
        } else {
            listOf(start.local) // no RRULE, or one we do not expand (e.g. SECONDLY): DTSTART only
        }
        for (s in starts) {
            val r = make(Point(s, start.zone))
            out[r.start] = r
        }
        for (rd in e.recurrenceDates) {
            for (d in rd.dates) {
                if (!ctx.budget.take()) break
                val r = make(point(cal, rd, d, ctx.zone).let { Point(it.local, if (start.allDay) null else it.zone ?: frame) })
                out.putIfAbsent(r.start, r)
            }
            for (p in rd.periods) {
                if (!ctx.budget.take()) break
                val ps = p.startDate ?: continue
                val s = point(cal, rd, ps, ctx.zone)
                val pe = p.endDate?.let { point(cal, rd, it, ctx.zone).toEventTime() }
                    ?: p.duration?.let { d -> (s.toEventTime() as? EventTime.At)?.let { EventTime.At(it.instant.plusMillis(d.toMillis())) } }
                val r = Raw(uid, title, s.toEventTime(), pe)
                out.putIfAbsent(r.start, r)
            }
        }
        return out.values.toList()
    }

    /** Maps biweekly's RRULE to our [Rule]; null for rules we do not expand (the series shows DTSTART only). */
    private fun toRule(r: Recurrence, start: Point, cal: ICalendar): Rule? {
        val freq = when (r.frequency) {
            Frequency.DAILY -> Freq.DAILY
            Frequency.WEEKLY -> Freq.WEEKLY
            Frequency.MONTHLY -> Freq.MONTHLY
            Frequency.YEARLY -> Freq.YEARLY
            // SECONDLY, MINUTELY, HOURLY: no family calendar needs them and they were the CPU/memory DoS vector.
            else -> return null
        }
        if (r.byYearDay.isNotEmpty() || r.byWeekNo.isNotEmpty()) return null
        val count = r.count?.toLong()
        if (count != null && count <= 0) return null
        val until: LocalDateTime? = r.until?.let { u ->
            val c = u.rawComponents
            when {
                !u.hasTime() -> LocalDate.of(c.year, c.month, c.date).atTime(java.time.LocalTime.MAX)
                c.isUtc -> LocalDateTime.of(c.year, c.month, c.date, c.hour, c.minute, c.second.coerceIn(0, 59))
                    .atOffset(ZoneOffset.UTC).atZoneSameInstant(start.zone ?: ZoneOffset.UTC).toLocalDateTime()
                else -> LocalDateTime.of(c.year, c.month, c.date, c.hour, c.minute, c.second.coerceIn(0, 59))
            }
        }
        return Rule(
            freq = freq,
            interval = r.interval ?: 1,
            count = count,
            until = until,
            byMonth = r.byMonth.filterNotNull().filter { it in 1..12 },
            byMonthDay = r.byMonthDay.filterNotNull().filter { it != 0 && it in -31..31 },
            byDay = r.byDay.mapNotNull { bd -> bd?.day?.let { WeekdayNum(bd.num ?: 0, DayOfWeek.valueOf(it.name)) } },
            bySetPos = r.bySetPos.filterNotNull().filter { it != 0 && it in -1000..1000 },
            byHour = r.byHour.filterNotNull(),
            byMinute = r.byMinute.filterNotNull(),
            bySecond = r.bySecond.filterNotNull(),
            wkst = r.workweekStarts?.let { DayOfWeek.valueOf(it.name) } ?: DayOfWeek.MONDAY,
        )
    }

    /**
     * Keeps the best [MAX_INSTANCES] overlapping instances in bounded memory: overlapping [focus] first, then earliest.
     */
    private class Collector(private val focus: InstantRange, private val zone: ZoneId) {
        private var items = ArrayList<EventInstance>()
        private val order = compareBy<EventInstance>({ if (overlaps(it, focus, zone)) 0 else 1 }, { sortKey(it) }, { it.title }, { it.uid })

        fun add(r: Raw, window: InstantRange) {
            val e = EventInstance(r.uid, r.title, r.start, r.end)
            if (!overlaps(e, window, zone)) return
            items += e
            if (items.size >= 4 * MAX_INSTANCES) prune()
        }

        private fun prune() {
            items.sortWith(order)
            items = ArrayList(items.subList(0, minOf(items.size, MAX_INSTANCES)))
        }

        fun result(): List<EventInstance> {
            prune()
            return items.sortedWith(compareBy<EventInstance>({ sortKey(it) }, { it.title }, { it.uid }))
        }

        private fun sortKey(e: EventInstance): Instant = when (val s = e.start) {
            is EventTime.At -> s.instant
            is EventTime.AllDay -> s.date.atStartOfDay(zone).toInstant()
        }
    }

    private fun overlaps(r: EventInstance, w: InstantRange, zone: ZoneId): Boolean = when (val s = r.start) {
        is EventTime.At -> {
            val e = (r.end as? EventTime.At)?.instant
            if (e == null || !e.isAfter(s.instant)) w.contains(s.instant)
            else s.instant.isBefore(w.end) && e.isAfter(w.start)
        }
        is EventTime.AllDay -> {
            val endDate = (r.end as? EventTime.AllDay)?.date?.takeIf { it.isAfter(s.date) } ?: s.date.plusDays(1)
            val ws = w.start.atZone(zone).toLocalDate()
            // window end is exclusive; the last local date touched is the date of (end - 1ns)
            val we = w.end.minusNanos(1).atZone(zone).toLocalDate()
            s.date <= we && endDate > ws
        }
    }
}

/**
 * Text pass before biweekly: unfolds lines, drops every VTIMEZONE component (biweekly evaluates VTIMEZONE RRULEs while
 * parsing, and a hostile one ran it out of memory) and counts VEVENTs. Returns null if there are more than
 * [maxVevents]. Linear in the input.
 */
internal object IcsPrepass {
    fun prepare(text: String, maxVevents: Int): String? {
        val out = StringBuilder(text.length)
        var depth = 0 // >0 while inside a VTIMEZONE (nested STANDARD/DAYLIGHT go with it)
        var vevents = 0
        for (line in unfold(text)) {
            val name = line.substringBefore(':').substringBefore(';').substringAfterLast('.').trim()
            val isBegin = name.equals("BEGIN", true)
            val isEnd = name.equals("END", true)
            val value = if (isBegin || isEnd) line.substringAfterLast(':').trim() else ""
            if (isBegin && value.equals("VTIMEZONE", true)) {
                depth++
                continue
            }
            if (depth > 0) {
                if (isEnd && value.equals("VTIMEZONE", true)) depth--
                continue
            }
            if (isBegin && value.equals("VEVENT", true) && ++vevents > maxVevents) return null
            out.append(line).append("\r\n")
        }
        return out.toString()
    }

    /** RFC 5545 3.1: a line break followed by a space or tab continues the previous line. */
    private fun unfold(text: String): Sequence<String> = sequence {
        val cur = StringBuilder()
        var hasCur = false
        var i = 0
        val n = text.length
        while (i <= n) {
            var j = text.indexOf('\n', i)
            if (j < 0) j = n
            var line = text.substring(i, j)
            if (line.endsWith('\r')) line = line.dropLast(1)
            if (hasCur && line.isNotEmpty() && (line[0] == ' ' || line[0] == '\t')) {
                cur.append(line, 1, line.length)
            } else {
                if (hasCur && cur.isNotEmpty()) yield(cur.toString())
                cur.setLength(0)
                cur.append(line)
                hasCur = true
            }
            i = j + 1
        }
        if (hasCur && cur.isNotEmpty()) yield(cur.toString())
    }
}
