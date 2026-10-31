package tvhome.core.calendar

import java.time.Instant
import java.time.LocalDate

/** Half-open [start, end). */
data class InstantRange(val start: Instant, val end: Instant)

// NOTE (merge seam with T2): EventInstance/EventTime are the architecture's contract types. T3 only needs them to
// type FetchResult.Ok. If T2 defines them too, keep T2's copy and delete this one (same package, same shape).
data class EventInstance(val uid: String, val title: String, val start: EventTime, val end: EventTime?)

sealed interface EventTime {
    data class At(val instant: Instant) : EventTime
    data class AllDay(val date: LocalDate) : EventTime
}

enum class SourceError { NOT_CONFIGURED, OFFLINE, NETWORK, HTTP_STATUS, TOO_LARGE, PARSE, INSECURE_URL }

sealed interface FetchResult {
    data class Ok(val events: List<EventInstance>, val fetchedAt: Instant) : FetchResult
    data class Failed(val error: SourceError) : FetchResult
}

interface CalendarSource {
    suspend fun fetch(window: InstantRange): FetchResult
}
