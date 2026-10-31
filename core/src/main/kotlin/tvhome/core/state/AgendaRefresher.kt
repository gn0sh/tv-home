package tvhome.core.state

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import tvhome.core.agenda.agendaWindow
import tvhome.core.calendar.CalendarSource
import tvhome.core.calendar.FetchResult
import java.time.Instant
import java.time.ZoneId

/**
 * The one place that refreshes the agenda and the only writer of the cache (architecture 3a, N2/N3; ADR 0004).
 * The app calls [refresh] on ON_START and each 15-minute tick and never calls `save` itself.
 *
 * - Single-flight: while a refresh runs, another call returns null without fetching.
 * - The cache is written only for [FetchResult.Ok], which is only ever a complete parse. Failed results (including
 *   `PARSE_LIMIT`) leave the last good copy untouched.
 * - Cancellation propagates and changes nothing: no save, and the next [refresh] works.
 */
class AgendaRefresher(
    private val source: CalendarSource,
    private val cache: CacheFile,
    private val zone: () -> ZoneId,
    private val clock: () -> Instant,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val running = Mutex()

    /** Null (no fetch) if a refresh is already running; otherwise the fetch result, for `reduce()`. */
    suspend fun refresh(): FetchResult? {
        if (!running.tryLock()) return null
        try {
            val z = zone()
            val result = source.fetch(agendaWindow(clock(), z)) // throws CancellationException on cancel
            if (result is FetchResult.Ok) {
                // withContext checks for cancellation on entry, so a cancel between fetch and save saves nothing.
                // The write is atomic; a failed save keeps the old file and the result is still returned.
                withContext(io) { cache.save(cacheOf(result, z)) }
            }
            return result
        } finally {
            running.unlock()
        }
    }
}
