package tvhome.core.agenda

import tvhome.core.calendar.EventInstance
import tvhome.core.calendar.EventTime
import tvhome.core.calendar.InstantRange
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

data class AgendaDay(val date: LocalDate, val items: List<AgendaItem>) {
    /** Counts only: titles are personal data and must not reach logs or crash reports. */
    override fun toString(): String = "AgendaDay($date, ${items.size} items)"
}

/**
 * One row of a day. [startsAt] and [endsAt] are set for timed events (M7 Now/Next countdown and "now, until 5:30");
 * [endsAt] is null when the event has no usable end. Both are null for all-day events. Derived from the cached
 * [EventInstance]s, so the cache format is unchanged.
 */
data class AgendaItem(
    val title: String,
    val label: TimeLabel,
    val startsAt: Instant? = null,
    val endsAt: Instant? = null,
) {
    override fun toString(): String = "AgendaItem(title=${title.length} chars, $label)"
}

sealed interface TimeLabel {
    object AllDay : TimeLabel
    data class Starts(val time: LocalTime) : TimeLabel
    object Continues : TimeLabel
}

/** Days the Board page lists: today and the six after it. */
const val WEEK_DAYS = 7

/** [start of today - 1 day, start of today + 3 days) in [zone]: the today/tomorrow range the agenda's freshness rules use. */
fun agendaWindow(now: Instant, zone: ZoneId): InstantRange {
    val today = now.atZone(zone).toLocalDate()
    return InstantRange(
        today.minusDays(1).atStartOfDay(zone).toInstant(),
        today.plusDays(3).atStartOfDay(zone).toInstant(),
    )
}

/**
 * [start of today - 1 day, start of today + 8 days) in [zone]: the range events are fetched, expanded over and cached
 * for. It is [agendaWindow] widened to cover the Board's seven days ([WEEK_DAYS]) with a day of slack. The parser's cap
 * ([tvhome.core.calendar.IcsParser.MAX_INSTANCES]) still keeps today and tomorrow first ([agendaFocus]), and whether a
 * cache is still usable is decided on the narrow [agendaWindow] of its fetch time, so the wider range changes no
 * freshness rule.
 */
fun fetchWindow(now: Instant, zone: ZoneId): InstantRange {
    val today = now.atZone(zone).toLocalDate()
    return InstantRange(
        today.minusDays(1).atStartOfDay(zone).toInstant(),
        today.plusDays(WEEK_DAYS + 1L).atStartOfDay(zone).toInstant(),
    )
}

/**
 * Today and tomorrow inside an [agendaWindow]: [window start + 1 day, window start + 3 days) in [zone]. The parser
 * keeps these instances first when it has to cut to its cap.
 */
fun agendaFocus(window: InstantRange, zone: ZoneId): InstantRange {
    val today = window.start.atZone(zone).toLocalDate().plusDays(1)
    val start = today.atStartOfDay(zone).toInstant()
    val end = today.plusDays(2).atStartOfDay(zone).toInstant()
    return InstantRange(maxOf(start, window.start).coerceAtMost(window.end), minOf(end, window.end).coerceAtLeast(window.start))
}

/**
 * Today and tomorrow in [zone]. A timed instance appears on every local day it overlaps; it is labelled with its
 * local start time on the day it starts, else Continues. All-day events appear on each date in [start, end).
 * Order: all-day first, then by start, then title.
 */
fun buildAgenda(events: List<EventInstance>, now: Instant, zone: ZoneId): Pair<AgendaDay, AgendaDay> {
    val today = now.atZone(zone).toLocalDate()
    return dayOf(events, today, zone) to dayOf(events, today.plusDays(1), zone)
}

/**
 * Up to [count] days from today in [zone], same rules as [buildAgenda], stopping at the first day that does not end by
 * [coveredUntil] (the cached window's end): a day the cache does not cover is left out, never shown as empty.
 */
fun buildDays(events: List<EventInstance>, now: Instant, zone: ZoneId, count: Int, coveredUntil: Instant): List<AgendaDay> {
    val today = now.atZone(zone).toLocalDate()
    return (0 until count).map { today.plusDays(it.toLong()) }
        .takeWhile { !it.plusDays(1).atStartOfDay(zone).toInstant().isAfter(coveredUntil) }
        .map { dayOf(events, it, zone) }
}

private class Entry(
    val title: String,
    val label: TimeLabel,
    val allDay: Boolean,
    val sortStart: Instant,
    val startsAt: Instant? = null,
    val endsAt: Instant? = null,
)

private fun dayOf(events: List<EventInstance>, date: LocalDate, zone: ZoneId): AgendaDay {
    val dayStart = date.atStartOfDay(zone).toInstant()
    val dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant()
    val entries = ArrayList<Entry>()
    for (e in events) {
        when (val s = e.start) {
            is EventTime.AllDay -> {
                val endDate = (e.end as? EventTime.AllDay)?.date?.takeIf { it.isAfter(s.date) } ?: s.date.plusDays(1)
                if (!date.isBefore(s.date) && date.isBefore(endDate)) {
                    entries += Entry(e.title, TimeLabel.AllDay, true, s.date.atStartOfDay(zone).toInstant())
                }
            }
            is EventTime.At -> {
                val end = (e.end as? EventTime.At)?.instant?.takeIf { it.isAfter(s.instant) }
                val overlaps = if (end == null) {
                    !s.instant.isBefore(dayStart) && s.instant.isBefore(dayEnd)
                } else {
                    s.instant.isBefore(dayEnd) && end.isAfter(dayStart)
                }
                if (overlaps) {
                    val startsToday = !s.instant.isBefore(dayStart)
                    val label = if (startsToday) {
                        TimeLabel.Starts(s.instant.atZone(zone).toLocalTime())
                    } else {
                        TimeLabel.Continues
                    }
                    entries += Entry(e.title, label, false, s.instant, s.instant, end)
                }
            }
        }
    }
    val sorted = entries.sortedWith(
        compareByDescending<Entry> { it.allDay }.thenBy { it.sortStart }.thenBy { it.title },
    )
    return AgendaDay(date, sorted.map { AgendaItem(it.title, it.label, it.startsAt, it.endsAt) })
}
