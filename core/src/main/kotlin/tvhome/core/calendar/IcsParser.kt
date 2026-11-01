package tvhome.core.calendar

import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.PriorityQueue

/**
 * Turns any byte string into exactly one [ParseOutcome] with bounded time and memory (architecture section 3a,
 * ADR 0004). [IcsReader] (ours) reads the bytes; this object interprets the whitelisted VEVENT fields as they arrive:
 *
 * - TZIDs go through [ZoneResolver] (Windows names, IANA ids, else the device zone); VTIMEZONE is never read.
 * - Recurrence is expanded by [RecurrenceExpander] in the event's local frame: TZID events in their zone, floating
 *   ones in the device zone, so wall-clock times hold across DST. Unsupported rules show DTSTART only.
 * - RECURRENCE-ID overrides: the original instance with the same UID and RECURRENCE-ID is dropped and the override
 *   added. RANGE=THISANDFUTURE is not handled: such an override replaces only its own instance. A first, keys-only
 *   pass of the reader collects every removal key (one step each, under the same deadline and VEVENT cap), so the
 *   keys are applied as instances are added and the result never depends on file order (`instance-cap-removal-
 *   order`). A VEVENT with a valid UID and RECURRENCE-ID removes its original even if the override is then skipped.
 * - STATUS:CANCELLED events and overrides are dropped. One malformed VEVENT is skipped and counted.
 * - Work is bounded by [Limits]: parse-wide limits (deadline from entry, global steps, VEVENTs, kept series) give
 *   [ParseOutcome.Exhausted]; a series over its own budget is skipped and counted. Steps are charged before work.
 * - At most [MAX_INSTANCES] are returned: instances overlapping `focus` (today and tomorrow) are kept first, then the
 *   earliest; the result is in start order. One UID gets at most [FOCUS_PER_UID_PER_DAY] focus instances on each
 *   local day of the focus range, so one dense series cannot push real events out of today.
 * - Per-VEVENT work is charged before it is done: an RRULE's size before its rule is built (and only a rule that can
 *   reach the window is built), its times of day before they are built, and each EXDATE before its key is made.
 */
object IcsParser {
    /** Hard cap on instances returned by one parse, across all events. */
    const val MAX_INSTANCES = 500

    /** Focus instances one UID may hold per day of the focus range (every 30 minutes, all day). */
    const val FOCUS_PER_UID_PER_DAY = 48

    data class Limits(
        val maxVevents: Int = 50_000,
        val maxSteps: Long = 5_000_000,
        val maxStepsPerSeries: Long = 20_000,
        val maxSeries: Int = 5_000,
        val deadline: Duration = Duration.ofSeconds(5),
    )

    /**
     * @param window instances overlapping this range are returned (timed: [start,end) overlap; all-day: date range
     *   overlap with the window's local dates in [zone]).
     * @param zone the device zone: floating times, unknown TZIDs and all-day placement.
     * @param focus instances overlapping it win when the cap cuts; null means the whole window.
     * @param nanoTime the clock for the deadline; it is read first at entry, so the deadline covers the whole parse.
     * @throws InterruptedException if the thread is interrupted (cancellation via `runInterruptible`). Nothing else.
     */
    fun parse(
        body: ByteArray,
        window: InstantRange,
        zone: ZoneId,
        focus: InstantRange? = null,
        limits: Limits = Limits(),
        nanoTime: () -> Long = System::nanoTime,
    ): ParseOutcome {
        val deadline = nanoTime() + limits.deadline.toNanos()
        val budget = Budget(limits.maxSteps, limits.maxStepsPerSeries, deadline, nanoTime)
        val run = Run(window, zone, focus ?: window, budget, limits)
        return try {
            // Pass 1: removal keys only. Its framing verdict is the same as pass 2's, so a reject ends here.
            IcsReader(body, budget, limits.maxVevents, keysOnly = true, sink = run::removalKey).read()
                ?.let { return ParseOutcome.Rejected(it) }
            val reader = IcsReader(body, budget, limits.maxVevents, sink = run::vevent)
            val rejected = reader.read()
            if (rejected != null) ParseOutcome.Rejected(rejected) else run.complete(reader.vevents)
        } catch (s: Stop) {
            ParseOutcome.Exhausted(s.limit)
        }
    }

    // ---------------------------------------------------------------- internals

    private class Raw(val uid: String, val title: String, val start: EventTime, val end: EventTime?)

    /** A resolved DTSTART-like value: wall clock plus the zone it is read in (null for all-day). */
    private class Point(val local: LocalDateTime, val zone: ZoneId?) {
        val allDay get() = zone == null
        fun toEventTime(): EventTime =
            if (zone == null) EventTime.AllDay(local.toLocalDate()) else EventTime.At(local.atZone(zone).toInstant())
    }

    /** 128-bit digest of a UID, so removal keys stay small whatever the UID length. */
    private data class UidKey(val hi: Long, val lo: Long) {
        companion object {
            fun of(uid: String, md: MessageDigest): UidKey {
                val d = md.digest(uid.toByteArray(Charsets.UTF_8))
                var hi = 0L
                var lo = 0L
                for (i in 0 until 8) hi = (hi shl 8) or (d[i].toLong() and 0xFF) // bound: (ii) 8 bytes
                for (i in 8 until 16) lo = (lo shl 8) or (d[i].toLong() and 0xFF) // bound: (ii) 8 bytes
                return UidKey(hi, lo)
            }
        }
    }

    private data class Key(val hi: Long, val lo: Long, val isDate: Boolean, val v: Long)

    /** Match keys for EXDATE / RECURRENCE-ID: exact instants for timed values, local dates for DATE values. */
    private class Keys {
        private val set = HashSet<Key>()
        val isEmpty get() = set.isEmpty()

        fun add(u: UidKey, p: Point) {
            when (val t = p.toEventTime()) {
                is EventTime.At -> set += Key(u.hi, u.lo, false, t.instant.epochSecond)
                is EventTime.AllDay -> set += Key(u.hi, u.lo, true, t.date.toEpochDay())
            }
        }

        /** [frame] is the series' zone, used to compare a timed instance with a DATE key. */
        fun matches(u: UidKey, t: EventTime, frame: ZoneId?): Boolean {
            if (set.isEmpty()) return false
            return when (t) {
                is EventTime.At -> Key(u.hi, u.lo, false, t.instant.epochSecond) in set ||
                    (frame != null && Key(u.hi, u.lo, true, t.instant.atZone(frame).toLocalDate().toEpochDay()) in set)
                is EventTime.AllDay -> Key(u.hi, u.lo, true, t.date.toEpochDay()) in set
            }
        }
    }

    /** The state of one parse; [vevent] is the reader's sink. */
    private class Run(
        val window: InstantRange,
        val zone: ZoneId,
        focus: InstantRange,
        val budget: Budget,
        val limits: Limits,
    ) {
        val zones = ZoneResolver.Memo(zone)
        val sha256: MessageDigest = MessageDigest.getInstance("SHA-256")
        val out = Collector(focus, zone, window)
        val replaced = Keys() // RECURRENCE-ID removal keys from pass 1, one per override (at most maxVevents)
        var series = 0
        var skippedEvents = 0
        var unsupportedRules = 0
        var skippedSeries = 0
        var ignoredValues = 0

        /** Pass 1 sink: one removal key per override with a valid UID line and RECURRENCE-ID, one step each. */
        fun removalKey(r: VEventRecord) {
            val rid = r.recurrenceId ?: return
            if (r.bad) return
            budget.startSeries()
            budget.take() // bound: (iii) at most maxVevents keys, each charged before it is made
            replaced.add(UidKey.of(r.uid ?: "", sha256), point(rid))
        }

        fun complete(vevents: Int): ParseOutcome {
            val events = out.result()
            val stats = ParseStats(vevents, skippedEvents, unsupportedRules, skippedSeries, ignoredValues, out.truncated, budget.steps)
            return ParseOutcome.Complete(events, stats)
        }

        fun vevent(r: VEventRecord) {
            budget.startSeries()
            if (r.bad) {
                skippedEvents++
                return
            }
            try {
                handle(r)
            } catch (e: InterruptedException) {
                throw e
            } catch (e: Exception) {
                // one bad VEVENT (an instant out of range, a bug) is skipped; nothing from it escapes
                skippedEvents++
            }
        }

        private fun point(d: DateValue): Point = when {
            d.isDate -> Point(d.local, null)
            d.utc -> Point(d.local, ZoneOffset.UTC)
            else -> Point(d.local, d.tzid?.let { zoneOf(it) } ?: zone) // no TZID: floating
        }

        // The reader shares one TZID string among the values of a line: resolve it once per line, not per value.
        private var lastTzid: String? = null
        private var lastZone: ZoneId = zone

        private fun zoneOf(tzid: String): ZoneId {
            if (tzid !== lastTzid) {
                lastZone = zones.resolve(tzid)
                lastTzid = tzid
            }
            return lastZone
        }

        private fun handle(r: VEventRecord) {
            val dts = r.dtStart ?: run {
                skippedEvents++
                return
            }
            val uid = r.uid ?: ""
            val title = r.summary ?: ""
            val cancelled = r.status == "CANCELLED"
            val start = point(dts)
            val end = endOf(r, start)
            val key = UidKey.of(uid, sha256)
            ignoredValues += r.ignoredValues

            val rid = r.recurrenceId
            if (rid != null) {
                // its removal key came from pass 1
                if (!cancelled) add(listOf(Raw(uid, title, start.toEventTime(), end)), key, null, removable = false)
                return
            }
            if (cancelled) return

            val reach = Reach(start, end)
            val spec = r.rrule as? RuleSpec.Spec
            var expander: RecurrenceExpander? = null
            var unreachable = false
            if (spec != null) {
                // bound: (iii) the rule's size (its BYxxx lists, each domain-bounded) is charged before the Rule and
                // expander are built; the reader's parse of the same line was bound (i), at most 1 KiB
                if (!budget.take(ruleCost(spec))) return skipSeries()
                val until = untilOf(spec, start)
                // Decided from the spec: a rule that ends before the window or starts after it is never built.
                if (start.local.toLocalDate().isAfter(reach.to) || until?.toLocalDate()?.isBefore(reach.from) == true) {
                    unreachable = true
                } else {
                    expander = RecurrenceExpander(toRule(spec, until), start.local, start.allDay).takeIf { it.supported }
                }
            }
            if (r.rruleSeen && !unreachable && expander == null) unsupportedRules++
            if (unreachable && r.rdates.isEmpty()) return // no instance of it can overlap the window

            val excluded = Keys()
            // bound: (iii) one step per EXDATE value charged before its key is made (at most 2,000 per VEVENT)
            if (r.exdates.isNotEmpty() && !budget.take(r.exdates.size.toLong())) return skipSeries()
            for (ex in r.exdates) excluded.add(key, point(ex))

            if (expander == null && !unreachable && r.rdates.isEmpty()) {
                // one-off, or a rule we do not expand: DTSTART only
                val t = start.toEventTime()
                if (excluded.matches(key, t, start.zone)) return
                add(listOf(Raw(uid, title, t, end)), key, start.zone, removable = true)
                return
            }
            expandSeries(r, uid, title, key, start, end, expander, unreachable, reach, excluded)
        }

        /** The local dates (in the series' frame) whose instances can overlap the window, with a day of slack. */
        private inner class Reach(start: Point, end: EventTime?) {
            private val first = start.toEventTime()

            /** One instance's length: exact millis for timed events, whole days for all-day ones. */
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
            private val frame = start.zone ?: zone
            val from: LocalDate = window.start.minusMillis(spanMs).atZone(frame).toLocalDate().minusDays(spanDays + 1)
            val to: LocalDate = window.end.atZone(frame).toLocalDate().plusDays(1)
        }

        /** Construction cost of a rule: one step plus one per BYxxx value (each list is domain-bounded). */
        private fun ruleCost(s: RuleSpec.Spec): Long = 1L + s.byMonth.size + s.byMonthDay.size + s.byDay.size +
            s.bySetPos.size + s.byHour.size + s.byMinute.size + s.bySecond.size

        /**
         * A recurring master: RRULE and/or RDATE. All of its instances are added, or none (series over budget).
         * [unreachable]: the rule cannot reach the window and was never built; only the RDATEs can.
         */
        private fun expandSeries(
            r: VEventRecord, uid: String, title: String, key: UidKey, start: Point, end: EventTime?,
            expander: RecurrenceExpander?, unreachable: Boolean, reach: Reach, excluded: Keys,
        ) {
            val first = start.toEventTime()
            val spanMs = reach.spanMs
            val spanDays = reach.spanDays
            fun make(p: Point): Raw = if (p.allDay) {
                val d = p.local.toLocalDate()
                Raw(uid, title, EventTime.AllDay(d), EventTime.AllDay(d.plusDays(spanDays)))
            } else {
                val s = p.local.atZone(p.zone).toInstant()
                Raw(uid, title, EventTime.At(s), if (end != null) EventTime.At(s.plusMillis(spanMs)) else null)
            }

            val frame = start.zone ?: zone
            // A kept series can still yield an instance near the window (its rule was built only if it can reach
            // it); only those count toward maxSeries.
            if (expander != null || r.rdates.isNotEmpty()) {
                if (++series > limits.maxSeries) throw Stop(ParseLimit.SERIES)
            }

            val instances = LinkedHashMap<EventTime, Raw>()
            if (expander != null) {
                val starts = expander.between(reach.from, reach.to, budget) ?: return skipSeries()
                // bound: (iii) the starts were paid for, period by period, inside between()
                for (s in starts) {
                    val x = make(Point(s, start.zone))
                    instances[x.start] = x
                }
            } else if (!unreachable) {
                // a rule we do not expand, plus RDATEs: DTSTART plus the RDATEs
                instances[first] = Raw(uid, title, first, end)
            }
            // bound: (iii) one step charged per RDATE value before its work (at most 2,000 per VEVENT)
            for (rd in r.rdates) {
                if (!budget.take()) return skipSeries()
                val x = when (rd) {
                    is DateValue -> point(rd).let { make(Point(it.local, if (start.allDay) null else it.zone ?: frame)) }
                    is PeriodValue -> {
                        val s = point(rd.start)
                        val pe = rd.end?.let { point(it).toEventTime() }
                            ?: rd.duration?.let { d -> (s.toEventTime() as? EventTime.At)?.let { EventTime.At(it.instant.plusMillis(d.toMillis())) } }
                        Raw(uid, title, s.toEventTime(), pe)
                    }
                    else -> continue
                }
                instances.putIfAbsent(x.start, x)
            }
            val kept = ArrayList<Raw>(instances.size)
            // bound: (iii) the instances above, each already paid for
            for (x in instances.values) {
                if (excluded.matches(key, x.start, start.zone)) continue
                kept += x
            }
            add(kept, key, start.zone, removable = true)
        }

        private fun skipSeries() {
            skippedSeries++
        }

        /** Charges one step per instance before adding it; a series over its budget adds nothing. */
        private fun add(xs: List<Raw>, key: UidKey, frame: ZoneId?, removable: Boolean) {
            if (xs.isEmpty()) return
            if (!budget.take(xs.size.toLong())) {
                skippedSeries++
                return
            }
            // bound: (iii) paid for by the charge just above
            for (x in xs) {
                if (removable && replaced.matches(key, x.start, frame)) continue
                out.add(x, key)
            }
        }

        /** End from DTEND or DURATION; null if neither is present. */
        private fun endOf(r: VEventRecord, start: Point): EventTime? {
            r.dtEnd?.let { return point(it).toEventTime() }
            val dur = r.duration ?: return null
            return when (val s = start.toEventTime()) {
                is EventTime.At -> EventTime.At(s.instant.plusMillis(dur.toMillis()))
                is EventTime.AllDay -> EventTime.AllDay(s.date.plusDays(maxOf(1L, dur.toMillis() / 86_400_000L)))
            }
        }

        /**
         * Our [Rule] in the series' local frame. UNTIL: a DATE counts through that day; a UTC date-time is converted
         * to the series' zone, and for an all-day series to its date in the device zone (`allday-until-utc-frame`).
         */
        private fun untilOf(s: RuleSpec.Spec, start: Point): LocalDateTime? = s.until?.let { u ->
            when {
                u.isDate -> u.local.toLocalDate().atTime(LocalTime.MAX)
                start.allDay -> (if (u.utc) u.local.atOffset(ZoneOffset.UTC).atZoneSameInstant(zone).toLocalDate() else u.local.toLocalDate())
                    .atTime(LocalTime.MAX)
                u.utc -> u.local.atOffset(ZoneOffset.UTC).atZoneSameInstant(start.zone).toLocalDateTime()
                else -> u.local
            }
        }

        private fun toRule(s: RuleSpec.Spec, until: LocalDateTime?): Rule {
            return Rule(
                freq = s.freq, interval = s.interval, count = s.count, until = until,
                byMonth = s.byMonth, byMonthDay = s.byMonthDay, byDay = s.byDay, bySetPos = s.bySetPos,
                byHour = s.byHour, byMinute = s.byMinute, bySecond = s.bySecond, wkst = s.wkst,
            )
        }
    }

    /**
     * Keeps the best [MAX_INSTANCES] overlapping instances in a bounded heap: overlapping [focus] first, then earliest.
     * Each add is O(log 500). Removal keys were applied before [add], so nothing kept is ever removed later. A UID's
     * focus instances beyond [FOCUS_PER_UID_PER_DAY] on one local day are cut (they would only crowd out other
     * events); the day is the local date of the instance's start, or of the focus start if it began earlier.
     */
    private class Collector(private val focus: InstantRange, private val zone: ZoneId, private val window: InstantRange) {
        private class Entry(val e: EventInstance, val focus: Boolean, val sortKey: Instant)
        private data class DayKey(val uid: UidKey, val day: Long)

        /** Focus instances offered per (UID, local day): at most one entry per added instance, each charged. */
        private val perUidDay = HashMap<DayKey, Int>()
        private val focusStartDay = focus.start.atZone(zone).toLocalDate()

        /** Start order: start, title, uid, end. */
        private val inOrder = Comparator<Entry> { a, b ->
            var c = a.sortKey.compareTo(b.sortKey)
            if (c == 0) c = a.e.title.compareTo(b.e.title)
            if (c == 0) c = a.e.uid.compareTo(b.e.uid)
            if (c == 0) c = endKey(a.e.end).compareTo(endKey(b.e.end))
            c
        }

        /** Which instance wins a slot: overlapping [focus] first, then start order. */
        private val better = Comparator<Entry> { a, b -> if (a.focus != b.focus) (if (a.focus) -1 else 1) else inOrder.compare(a, b) }
        private val heap = PriorityQueue(MAX_INSTANCES + 1, better.reversed()) // worst on top
        var truncated = false
            private set

        fun add(r: Raw, key: UidKey) {
            val e = EventInstance(r.uid, r.title, r.start, r.end)
            if (!overlaps(e, window, zone)) return
            val inFocus = overlaps(e, focus, zone)
            if (inFocus) {
                val k = DayKey(key, focusDay(e).toEpochDay())
                val n = (perUidDay[k] ?: 0) + 1
                perUidDay[k] = n
                if (n > FOCUS_PER_UID_PER_DAY) {
                    truncated = true
                    return
                }
            }
            val entry = Entry(e, inFocus, sortKey(e))
            if (heap.size >= MAX_INSTANCES) {
                truncated = true
                if (better.compare(entry, heap.peek()) >= 0) return // no better than the worst kept one
                heap.poll()
            }
            heap.add(entry)
        }

        /** The kept instances in start order. */
        fun result(): List<EventInstance> = heap.sortedWith(inOrder).map { it.e } // bound: (ii) at most MAX_INSTANCES

        private fun focusDay(e: EventInstance): LocalDate {
            val d = when (val s = e.start) {
                is EventTime.At -> s.instant.atZone(zone).toLocalDate()
                is EventTime.AllDay -> s.date
            }
            return if (d.isBefore(focusStartDay)) focusStartDay else d
        }

        private fun sortKey(e: EventInstance): Instant = when (val s = e.start) {
            is EventTime.At -> s.instant
            is EventTime.AllDay -> s.date.atStartOfDay(zone).toInstant()
        }

        private fun endKey(t: EventTime?): Long = when (t) {
            null -> Long.MIN_VALUE
            is EventTime.At -> t.instant.epochSecond
            is EventTime.AllDay -> t.date.toEpochDay()
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
