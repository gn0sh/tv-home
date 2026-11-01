package tvhome.core.feeds

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant

/** The last good results, in memory only. */
data class FeedsSnapshot(
    val weather: WeatherNow? = null,
    val games: List<TeamGame> = emptyList(),
)

/** Refresh timing: pure, so the back-off is testable. */
object FeedTiming {
    const val WEATHER_MS = 30L * 60_000
    const val SCHEDULE_MS = 60L * 60_000
    const val LIVE_MS = 60_000L
    const val NO_LOCATION_RECHECK_MS = 60_000L
    const val MAX_BACKOFF_MS = 2L * 60 * 60_000
    const val MAX_LIVE_BACKOFF_MS = 15L * 60_000

    /** [base] after a success; doubled per consecutive failure, up to [max]. */
    fun delayMs(base: Long, failures: Int, max: Long = MAX_BACKOFF_MS): Long =
        if (failures <= 0) base else minOf(max, base shl minOf(failures, 10)).coerceAtLeast(base)

    /** 60 s while a game is live or about to start (within 10 minutes, or just past its start); otherwise 60 min. */
    fun scheduleBase(games: List<TeamGame>, now: Instant): Long {
        val hot = games.any { (_, g) ->
            (g.phase == GamePhase.LIVE && Duration.between(g.start, now) < GameDay.STALE_LIVE) ||
                (g.phase == GamePhase.UPCOMING && Duration.between(now, g.start) < Duration.ofMinutes(10) &&
                    Duration.between(g.start, now) < Duration.ofHours(1))
        }
        return if (hot) LIVE_MS else SCHEDULE_MS
    }
}

/**
 * Weather and game schedules for the hero. Network work runs on [io]; nothing here blocks the caller's thread, and
 * nothing is persisted. A failed refresh keeps the last good value, except that weather older than 2 hours is dropped.
 * With no [location] the weather is off and no request is made.
 */
class LiveFeeds(
    private val fetcher: FeedFetcher,
    private val location: () -> Location?,
    private val teams: List<TeamConfig> = Teams.ALL,
    private val clock: () -> Instant = Instant::now,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val state = MutableStateFlow(FeedsSnapshot())
    val snapshot: StateFlow<FeedsSnapshot> get() = state

    private var weatherOkAt: Instant? = null
    private val perTeam = HashMap<String, List<GameInfo>>()

    /** True when the weather is off (no location) or the fetch and parse worked. */
    suspend fun refreshWeather(): Boolean {
        val loc = withContext(io) { runCatching(location).getOrNull() }
        if (loc == null) {
            state.update { it.copy(weather = null) }
            weatherOkAt = null
            return true
        }
        val w = fetcher.fetch(WeatherFeed.url(loc))?.let { raw -> withContext(io) { WeatherFeed.parse(raw) } }
        val now = clock()
        if (w != null) {
            weatherOkAt = now
            state.update { it.copy(weather = w) }
        } else if (weatherOkAt?.let { Duration.between(it, now) > STALE_WEATHER } != false) {
            state.update { it.copy(weather = null) }
        }
        return w != null
    }

    /** True when every team's schedule was fetched and parsed. */
    suspend fun refreshSchedules(): Boolean {
        var allOk = true
        for (team in teams) {
            val games = fetcher.fetch(team.scheduleUrl())?.let { raw -> withContext(io) { Schedules.parse(team, raw, clock()) } }
            if (games != null) perTeam[team.key] = games else allOk = false
        }
        val all = teams.flatMap { t -> perTeam[t.key].orEmpty().map { TeamGame(t, it) } }
        state.update { it.copy(games = all) }
        return allOk
    }

    /**
     * Runs until cancelled. A loop's first refresh waits out the time left since its last attempt, so leaving and
     * re-entering the home screen does not refetch. With no location the weather loop only re-reads the setting.
     */
    suspend fun run() = coroutineScope {
        launch { weatherLoop() }
        launch { scheduleLoop() }
    }

    private var weatherLast: Instant? = null
    private var scheduleLast: Instant? = null
    private var weatherFailures = 0
    private var scheduleFailures = 0

    private suspend fun weatherLoop() {
        while (true) {
            if (withContext(io) { runCatching(location).getOrNull() } == null) {
                refreshWeather() // clears any weather; makes no request
                delay(FeedTiming.NO_LOCATION_RECHECK_MS)
                continue
            }
            val wait = waitLeft(weatherLast, FeedTiming.delayMs(FeedTiming.WEATHER_MS, weatherFailures))
            if (wait > 0) { delay(wait); continue }
            weatherLast = clock()
            weatherFailures = if (refreshWeather()) 0 else weatherFailures + 1
        }
    }

    private suspend fun scheduleLoop() {
        while (true) {
            val base = FeedTiming.scheduleBase(state.value.games, clock())
            val max = if (base == FeedTiming.LIVE_MS) FeedTiming.MAX_LIVE_BACKOFF_MS else FeedTiming.MAX_BACKOFF_MS
            val wait = waitLeft(scheduleLast, FeedTiming.delayMs(base, scheduleFailures, max))
            if (wait > 0) { delay(wait); continue }
            scheduleLast = clock()
            scheduleFailures = if (refreshSchedules()) 0 else scheduleFailures + 1
        }
    }

    private fun waitLeft(last: Instant?, every: Long): Long =
        if (last == null) 0 else (every - Duration.between(last, clock()).toMillis()).coerceAtLeast(0)

    private companion object {
        val STALE_WEATHER: Duration = Duration.ofHours(2)
    }
}
