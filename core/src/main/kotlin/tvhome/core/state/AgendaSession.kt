package tvhome.core.state

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import tvhome.core.calendar.FetchResult
import tvhome.core.calendar.SourceError
import java.time.Instant
import java.time.ZoneId

/**
 * What the foreground screen runs (architecture 3a consumer 14). Plain Kotlin so the lifecycle behaviour is tested on
 * the JVM; the activity only collects [state] and calls [runWhileStarted] inside `repeatOnLifecycle(STARTED)`.
 *
 * - Refresh goes through [AgendaRefresher] only. This class never writes the cache; it reads it (on [io]).
 * - Start: read the cache and the secret on [io], publish a state from the cache at once (AC8: no network wait), then
 *   refresh. Then every [TICK_MS] it re-publishes (so "Updated N min ago" stays true) and refreshes whenever
 *   [RefreshPolicy] says the interval has passed. Cancelling the caller (ON_STOP) cancels the fetch and parse.
 * - Offline (policy `SKIP_OFFLINE`): no fetch; the state is computed as if the fetch failed with OFFLINE, so an old
 *   cache shows "Can't reach calendar. Showing the last copy." and no cache shows the CantReach screen. The next tick
 *   after the network returns fetches (an offline skip does not count as an attempt).
 * - No secret: NotSetUp, no fetch. The secret is re-read on [io] at start and on every tick, so a link provisioned
 *   (or removed) while the screen is up shows within a minute, without a relaunch.
 *
 * One collector at a time: [runWhileStarted] is not re-entrant, which `repeatOnLifecycle` guarantees.
 */
class AgendaSession(
    private val refresher: AgendaRefresher,
    private val cache: CacheFile,
    private val hasSecret: () -> Boolean,
    private val online: () -> Boolean,
    private val zone: () -> ZoneId,
    private val clock: () -> Instant,
    private val policy: RefreshPolicy = RefreshPolicy(),
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val _state = MutableStateFlow<AgendaState>(AgendaState.Loading)
    val state: StateFlow<AgendaState> = _state.asStateFlow()

    private var cached: CachedAgenda? = null
    private var secret = false
    private var lastFetch: FetchResult? = null
    private var lastAttempt: Instant? = null

    /** Returns only by throwing the caller's cancellation. [pause] is injectable for tests. */
    suspend fun runWhileStarted(pause: suspend (millis: Long) -> Unit = { delay(it) }) {
        val (loaded, hasIt) = withContext(io) { CachedAgenda.load(cache) to hasSecret() }
        cached = loaded
        secret = hasIt
        lastFetch = null // "no fetch yet this foreground session"
        publish()
        step(RefreshPolicy.Trigger.START)
        while (true) {
            pause(TICK_MS)
            step(RefreshPolicy.Trigger.TICK)
        }
    }

    private suspend fun step(trigger: RefreshPolicy.Trigger) {
        if (trigger == RefreshPolicy.Trigger.TICK) {
            // Provisioning (content write / am start) can happen while this screen is up: re-read on IO every tick.
            val now = withContext(io) { hasSecret() }
            if (now != secret) {
                secret = now
                lastFetch = null
                lastAttempt = null // a new or removed secret restarts the cycle: the next step fetches at once
            }
        }
        val now = clock()
        if (secret) {
            when (policy.decide(trigger, online(), lastAttempt, now)) {
                RefreshPolicy.Decision.FETCH -> {
                    lastAttempt = now
                    val r = refresher.refresh() // null: one is already running
                    if (r != null) {
                        lastFetch = r
                        if (r is FetchResult.Ok) cached = cacheOf(r, zone())
                    }
                }
                RefreshPolicy.Decision.SKIP_OFFLINE -> lastFetch = FetchResult.Failed(SourceError.OFFLINE)
                RefreshPolicy.Decision.SKIP_TOO_SOON -> Unit
            }
        }
        publish()
    }

    private fun publish() {
        _state.value = reduce(secret, cached, lastFetch, clock(), zone())
    }

    companion object {
        /** State re-publish period; fetches still happen only when [RefreshPolicy] allows (every 15 min). */
        const val TICK_MS = 60_000L
    }
}
