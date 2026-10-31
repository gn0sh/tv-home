package tvhome.core.calendar

import java.time.Instant

enum class SourceError { NOT_CONFIGURED, OFFLINE, NETWORK, HTTP_STATUS, TOO_LARGE, PARSE, INSECURE_URL }

sealed interface FetchResult {
    data class Ok(val events: List<EventInstance>, val fetchedAt: Instant) : FetchResult {
        /** Counts only (event titles are personal data). */
        override fun toString(): String = "Ok(${events.size} events, fetchedAt=$fetchedAt)"
    }
    data class Failed(val error: SourceError) : FetchResult
}

interface CalendarSource {
    suspend fun fetch(window: InstantRange): FetchResult
}
