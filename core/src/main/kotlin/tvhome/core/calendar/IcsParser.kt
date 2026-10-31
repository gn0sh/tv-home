package tvhome.core.calendar

import biweekly.Biweekly
import biweekly.ICalendar
import biweekly.component.VEvent
import biweekly.parameter.Range
import biweekly.property.ICalProperty
import biweekly.util.ICalDate
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import java.util.TimeZone

/** Thrown for unparseable input. The message is fixed text: it never contains calendar content or URLs. */
class IcsParseException : Exception("ICS parse failed")

/**
 * Parses ICS text into concrete [EventInstance]s overlapping a window.
 *
 * biweekly expands RRULE/RDATE/EXDATE but does not apply RECURRENCE-ID overrides, so those are applied here:
 * VEVENTs are grouped by UID, the original instance matching each RECURRENCE-ID is dropped, and the override
 * (with its own DTSTART/DTEND) is added. STATUS:CANCELLED events and overrides are dropped.
 */
object IcsParser {
    const val MAX_ITERATIONS_PER_EVENT = 5_000

    /**
     * @param window instances overlapping this range are returned (timed: [start,end) overlap; all-day: date range
     *   overlap with the window's local dates in [zone]).
     * @param zone used to read floating times and to place all-day events against the window.
     */
    fun parse(text: String, window: InstantRange, zone: ZoneId): List<EventInstance> {
        val calendars: List<ICalendar> = try {
            Biweekly.parse(text).all()
        } catch (e: Exception) {
            throw IcsParseException()
        }
        if (calendars.isEmpty()) throw IcsParseException()

        val out = ArrayList<EventInstance>()
        for (cal in calendars) {
            try {
                collect(cal, window, zone, out)
            } catch (e: Exception) {
                throw IcsParseException()
            }
        }
        return out
    }

    private class Raw(val uid: String, val title: String, val start: EventTime, val end: EventTime?)

    private fun collect(cal: ICalendar, window: InstantRange, zone: ZoneId, out: MutableList<EventInstance>) {
        val byUid = cal.events.groupBy { it.uid?.value ?: "" }
        for ((uid, events) in byUid) {
            val masters = events.filter { it.recurrenceId == null }
            val overrides = events.filter { it.recurrenceId != null }

            // Keys of originals replaced (or cancelled) by an override.
            val replaced = HashSet<Any>()
            for (ov in overrides) {
                val rid = ov.recurrenceId.value ?: continue
                replaced += timeKey(cal, ov, ov.recurrenceId, rid, zone)
            }

            for (m in masters) {
                if (isCancelled(m)) continue
                for (inst in expand(cal, m, uid, window, zone)) {
                    if (keyOf(inst.start) in replaced) continue
                    out += inst
                }
            }
            for (ov in overrides) {
                if (isCancelled(ov)) continue
                val inst = single(cal, ov, uid, zone) ?: continue
                if (overlaps(inst, window, zone)) out += inst.toEvent()
            }
        }
    }

    private fun isCancelled(e: VEvent) = e.status?.isCancelled == true

    private fun keyOf(t: EventTime): Any = when (t) {
        is EventTime.At -> t.instant
        is EventTime.AllDay -> t.date
    }

    private fun timeKey(cal: ICalendar, ev: VEvent, prop: ICalProperty, d: ICalDate, zone: ZoneId): Any =
        keyOf(toEventTime(cal, prop, d, zone))

    private fun Raw.toEvent() = EventInstance(uid, title, start, end)

    private fun title(e: VEvent): String = e.summary?.value?.trim().orEmpty()

    private fun toEventTime(cal: ICalendar, prop: ICalProperty, d: ICalDate, zone: ZoneId): EventTime {
        if (!d.hasTime()) {
            val c = d.rawComponents
            return EventTime.AllDay(LocalDate.of(c.year, c.month, c.date))
        }
        val c = d.rawComponents
        val tzInfo = cal.timezoneInfo
        val assigned = if (tzInfo.isFloating(prop)) null else tzInfo.getTimezone(prop)
        val local = java.time.LocalDateTime.of(c.year, c.month, c.date, c.hour, c.minute, c.second)
        return when {
            c.isUtc -> EventTime.At(local.toInstant(java.time.ZoneOffset.UTC))
            assigned != null -> EventTime.At(local.atZone(assigned.timeZone.toZoneId()).toInstant())
            else -> EventTime.At(local.atZone(zone).toInstant()) // floating
        }
    }

    /** Duration of the event as given by DTEND or DURATION; null if neither is present. */
    private fun endOf(cal: ICalendar, e: VEvent, start: EventTime, zone: ZoneId): EventTime? {
        val de = e.dateEnd
        if (de != null && de.value != null) return toEventTime(cal, de, de.value, zone)
        val dur = e.duration?.value ?: return null
        return when (start) {
            is EventTime.At -> EventTime.At(Instant.ofEpochMilli(start.instant.toEpochMilli() + dur.toMillis()))
            is EventTime.AllDay -> {
                val days = maxOf(1L, dur.toMillis() / 86_400_000L)
                EventTime.AllDay(start.date.plusDays(days))
            }
        }
    }

    private fun single(cal: ICalendar, e: VEvent, uid: String, zone: ZoneId): Raw? {
        val ds = e.dateStart ?: return null
        val dv = ds.value ?: return null
        val start = toEventTime(cal, ds, dv, zone)
        return Raw(uid, title(e), start, endOf(cal, e, start, zone))
    }

    private fun expand(cal: ICalendar, e: VEvent, uid: String, window: InstantRange, zone: ZoneId): List<EventInstance> {
        val base = single(cal, e, uid, zone) ?: return emptyList()
        val ds = e.dateStart
        val tz: TimeZone = when {
            !ds.value.hasTime() -> TimeZone.getTimeZone("UTC")
            cal.timezoneInfo.isFloating(ds) -> TimeZone.getTimeZone(zone)
            else -> cal.timezoneInfo.getTimezone(ds)?.timeZone
                ?: if (ds.value.rawComponents.isUtc) TimeZone.getTimeZone("UTC") else TimeZone.getTimeZone(zone)
        }
        val allDay = base.start is EventTime.AllDay
        // biweekly returns floating times as wall-clock-in-UTC, so they are re-read in the device zone below.
        val floating = !allDay && cal.timezoneInfo.isFloating(ds)

        // Length of one instance, to keep earlier long-running instances that overlap the window start.
        val spanMs: Long = when {
            allDay -> {
                val s = (base.start as EventTime.AllDay).date
                val en = (base.end as? EventTime.AllDay)?.date ?: s.plusDays(1)
                (java.time.temporal.ChronoUnit.DAYS.between(s, en).coerceAtLeast(1) + 1) * 86_400_000L
            }
            base.end is EventTime.At -> maxOf(
                0L,
                (base.end as EventTime.At).instant.toEpochMilli() - (base.start as EventTime.At).instant.toEpochMilli(),
            )
            else -> 0L
        }

        val it = e.getDateIterator(tz)
        val result = ArrayList<EventInstance>()
        // Skip ahead without counting instances that end before the window; a day of slack covers zone offsets.
        it.advanceTo(Date(window.start.toEpochMilli() - spanMs - 86_400_000L))
        var n = 0
        while (it.hasNext() && n < MAX_ITERATIONS_PER_EVENT) {
            val d = it.next()
            n++
            val inst: Raw = if (allDay) {
                val day = d.toInstant().atZone(java.time.ZoneOffset.UTC).toLocalDate()
                val s = (base.start as EventTime.AllDay).date
                val bEnd = (base.end as? EventTime.AllDay)?.date
                val len = if (bEnd != null) java.time.temporal.ChronoUnit.DAYS.between(s, bEnd) else 1L
                Raw(uid, base.title, EventTime.AllDay(day), EventTime.AllDay(day.plusDays(len.coerceAtLeast(1))))
            } else {
                val startI = if (floating) {
                    java.time.LocalDateTime.ofInstant(d.toInstant(), java.time.ZoneOffset.UTC).atZone(zone).toInstant()
                } else {
                    d.toInstant()
                }
                val bs = (base.start as EventTime.At).instant
                val be = (base.end as? EventTime.At)?.instant
                val endI = if (be != null) startI.plusMillis(be.toEpochMilli() - bs.toEpochMilli()) else null
                Raw(uid, base.title, EventTime.At(startI), endI?.let { EventTime.At(it) })
            }
            val startKey = when (val s = inst.start) {
                is EventTime.At -> s.instant
                is EventTime.AllDay -> s.date.atStartOfDay(zone).toInstant()
            }
            if (startKey >= window.end.plusSeconds(86_400)) break // past the window; slack for all-day zone edges
            if (overlaps(inst, window, zone)) result += inst.toEvent()
        }
        return result
    }

    private fun overlaps(r: Raw, w: InstantRange, zone: ZoneId): Boolean = when (val s = r.start) {
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
