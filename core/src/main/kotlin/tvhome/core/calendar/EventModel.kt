package tvhome.core.calendar

import java.time.Instant
import java.time.LocalDate

/** Half-open range [start, end). */
data class InstantRange(val start: Instant, val end: Instant) {
    init {
        require(!end.isBefore(start)) { "end before start" }
    }

    operator fun contains(i: Instant): Boolean = !i.isBefore(start) && i.isBefore(end)
}

data class EventInstance(val uid: String, val title: String, val start: EventTime, val end: EventTime?)

sealed interface EventTime {
    /** UTC, TZID and floating times resolved to an instant. */
    data class At(val instant: Instant) : EventTime

    /** VALUE=DATE; never zone-converted. For an end time, the date is exclusive (RFC 5545 DTEND). */
    data class AllDay(val date: LocalDate) : EventTime
}
