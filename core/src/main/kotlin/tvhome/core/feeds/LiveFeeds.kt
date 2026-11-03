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
import tvhome.core.state.CacheFile
import java.time.Duration
import java.time.Instant

/**
 * The last good results, each stamped with its fetch time (ADR 0008 section 1). Staleness is decided by [view] at draw
 * time, never here. [alertsChecked] is true once an alerts fetch has finished (good or not) or a cache was loaded, so the
 * chip can say "alerts unknown" without flashing it before the first attempt.
 */
data class FeedsSnapshot(
    val weather: Stamped<WeatherNow>? = null,
    val alerts: Stamped<AlertSet>? = null,
    val games: Stamped<List<TeamGame>>? = null,
    val alertsChecked: Boolean = false,
)

/**
 * Per-panel off switches, set over adb. Off means no request and the panel's view is Off. The brief is on by default
 * (approved by Brian), but it only ever calls Claude when an API key is provisioned (`BriefEngine`'s hasKey); off, the
 * free template line still fills its slot.
 */
data class PanelSwitches(
    val weather: Boolean = true,
    val alerts: Boolean = true,
    val games: Boolean = true,
    val brief: Boolean = true,
)

/** Refresh timing: pure, so the back-off is testable. */
object FeedTiming {
    const val WEATHER_MS = 30L * 60_000
    const val SCHEDULE_MS = 60L * 60_000
    const val ALERTS_MS = 15L * 60_000
    const val ALERTS_ACTIVE_MS = 5L * 60_000
    /** Alerts are stale at 30 min, so back-off never waits longer than that. */
    const val MAX_ALERTS_BACKOFF_MS = 30L * 60_000
    const val LIVE_MS = 60_000L
    const val NO_LOCATION_RECHECK_MS = 60_000L
    const val SLICE_MS = 60_000L
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

    /** 5 min while any alert is active, else 15 min. */
    fun alertsBase(alerts: AlertSet?): Long = if (alerts?.alerts?.isNotEmpty() == true) ALERTS_ACTIVE_MS else ALERTS_MS
}

/**
 * Weather, NWS alerts and game schedules for the hero: the one fetch path for panels (spec M7-1.6). Network and disk
 * work run on [io]; nothing here blocks the caller's thread. A failed refresh keeps the last good value (stamped), and
 * [view] decides at draw time whether it is too old to show. With no [location] weather and alerts are cleared and no
 * request is made. [switches] is read every slice; a panel switched off makes no request.
 *
 * [caches] gives the last-good file per panel id (`weather`, `alerts`, `games`); null means memory only. Caches are read
 * once, on [io], as the first step of [run], and written only after a good fetch and parse.
 */
class LiveFeeds(
    private val fetcher: FeedFetcher,
    private val location: () -> Location?,
    private val teams: List<TeamConfig> = Teams.ALL,
    private val clock: () -> Instant = Instant::now,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /** Injectable for tests (a fake clock advances inside it). */
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val switches: () -> PanelSwitches = { PanelSwitches() },
    private val caches: ((String) -> CacheFile)? = null,
    /** The schedule adapter per team; by default one [LeagueSchedule] per team with its season file in [caches]. */
    private val sources: ((TeamConfig) -> ScheduleSource)? = null,
) {
    private val scheduleSources = HashMap<String, ScheduleSource>()
    private fun sourceFor(team: TeamConfig): ScheduleSource = scheduleSources.getOrPut(team.key) {
        sources?.invoke(team) ?: GameSources.forTeam(team, fetcher, caches?.let { c -> runCatching { c(GameSources.cacheId(team)) }.getOrNull() }, io)
    }

    private val state = MutableStateFlow(FeedsSnapshot())
    val snapshot: StateFlow<FeedsSnapshot> get() = state

    private val perTeam = HashMap<String, Stamped<List<GameInfo>>>()
    private val gamesSerializer = PanelCaches.games(teams)
    private var cachesLoaded = false

    private fun switchesNow(): PanelSwitches = runCatching(switches).getOrDefault(PanelSwitches())

    /** Publishes each cached panel that memory does not already hold. Never throws. */
    internal suspend fun loadCaches() {
        val files = caches ?: return
        val now = clock()
        val loaded = withContext(io) {
            fun <T> one(id: String, ser: kotlinx.serialization.KSerializer<T>): Stamped<T>? =
                runCatching { PanelCacheCodec.load(files(id), ser, now) }.getOrNull()
            Triple(one(PanelCaches.WEATHER, PanelCaches.weather), one(PanelCaches.ALERTS, PanelCaches.alerts),
                one(PanelCaches.GAMES, gamesSerializer))
        }
        val (w, a, g) = loaded
        if (g != null && perTeam.isEmpty()) {
            for ((team, games) in g.value.groupBy { it.team }) perTeam[team.key] = Stamped(games.map { it.game }, g.at)
        }
        state.update {
            it.copy(
                weather = it.weather ?: w,
                alerts = it.alerts ?: a,
                alertsChecked = it.alertsChecked || a != null,
                games = it.games ?: g,
            )
        }
    }

    private suspend fun <T> writeThrough(id: String, value: Stamped<T>, ser: kotlinx.serialization.KSerializer<T>) {
        val files = caches ?: return
        withContext(io) { runCatching { PanelCacheCodec.save(files(id), value, ser) } }
    }

    /** True when the weather is off (no location) or the fetch and parse worked. */
    suspend fun refreshWeather(): Boolean {
        val loc = withContext(io) { runCatching(location).getOrNull() }
        if (loc == null) {
            state.update { it.copy(weather = null) }
            return true
        }
        val w = fetcher.fetch(WeatherFeed.url(loc))?.let { raw -> withContext(io) { WeatherFeed.parse(raw) } } ?: return false
        val stamped = Stamped(w, clock())
        state.update { it.copy(weather = stamped) }
        writeThrough(PanelCaches.WEATHER, stamped, PanelCaches.weather)
        return true
    }

    /** True when alerts are off for want of a location, or the fetch and parse worked. */
    suspend fun refreshAlerts(): Boolean {
        val loc = withContext(io) { runCatching(location).getOrNull() }
        if (loc == null) {
            state.update { it.copy(alerts = null, alertsChecked = false) }
            return true
        }
        val raw = fetcher.fetch(NwsAlerts.url(loc))
        val now = clock()
        val set = raw?.let { withContext(io) { NwsAlerts.parse(it, now) } }
        if (set == null) {
            state.update { it.copy(alertsChecked = true) }
            return false
        }
        val stamped = Stamped(set, now)
        state.update { it.copy(alerts = stamped, alertsChecked = true) }
        writeThrough(PanelCaches.ALERTS, stamped, PanelCaches.alerts)
        return true
    }

    /** True when every team's schedule was fetched and parsed. */
    suspend fun refreshSchedules(): Boolean {
        var allOk = true
        var anyOk = false
        for (team in teams) {
            val result = try { sourceFor(team).games(clock()) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Throwable) { null }
            // Games from the season fallback are shown, but the refresh counts as failed so the back-off applies.
            if (result != null) { perTeam[team.key] = Stamped(result.games, result.at ?: clock()); anyOk = true; if (result.fromFallback) allOk = false } else allOk = false
        }
        val held = teams.mapNotNull { t -> perTeam[t.key]?.let { t to it } }
        if (held.isEmpty()) return allOk
        // Stamped with the oldest team fetch that fed it (ADR 0008).
        val combined = Stamped(held.flatMap { (t, s) -> s.value.map { TeamGame(t, it) } }, held.minOf { it.second.at })
        state.update { it.copy(games = combined) }
        if (anyOk) writeThrough(PanelCaches.GAMES, combined, gamesSerializer)
        return allOk
    }

    /**
     * Runs until cancelled. A loop's first refresh waits out the time left since its last attempt, so leaving and
     * re-entering the home screen does not refetch. With no location the weather loop only re-reads the setting.
     */
    suspend fun run() = coroutineScope {
        if (!cachesLoaded) {
            loadCaches() // on io; published before any fetch
            cachesLoaded = true
        }
        launch { weatherLoop() }
        launch { alertsLoop() }
        launch { scheduleLoop() }
    }

    private var weatherLast: Instant? = null
    private var scheduleLast: Instant? = null
    private var weatherLoc: Location? = null
    private var weatherFailures = 0
    private var scheduleFailures = 0
    private var alertsLast: Instant? = null
    private var alertsLoc: Location? = null
    private var alertsFailures = 0

    /**
     * Both loops wake every [FeedTiming.SLICE_MS] at most and re-evaluate everything (the live/about-to-start rate, the
     * location), so a puck drop switches to 60 s polling within a minute, not at the next hourly fetch. "Last attempt"
     * is stamped after the attempt completes, so a cancelled fetch (leaving the home screen) does not count as one.
     */
    private suspend fun weatherLoop() {
        while (true) {
            if (!switchesNow().weather) { sleep(FeedTiming.SLICE_MS); continue }
            val loc = withContext(io) { runCatching(location).getOrNull() }
            if (loc == null) {
                refreshWeather() // clears any weather; makes no request
                weatherLoc = null
                sleep(FeedTiming.NO_LOCATION_RECHECK_MS)
                continue
            }
            val due = loc != weatherLoc || waitLeft(weatherLast, FeedTiming.delayMs(FeedTiming.WEATHER_MS, weatherFailures)) <= 0
            if (!due) { sleep(FeedTiming.SLICE_MS); continue }
            val ok = refreshWeather()
            weatherLoc = loc
            weatherFailures = if (ok) 0 else weatherFailures + 1
            weatherLast = clock()
        }
    }

    private suspend fun alertsLoop() {
        while (true) {
            if (!switchesNow().alerts) { sleep(FeedTiming.SLICE_MS); continue }
            val loc = withContext(io) { runCatching(location).getOrNull() }
            if (loc == null) {
                refreshAlerts() // clears any alerts; makes no request
                alertsLoc = null
                sleep(FeedTiming.NO_LOCATION_RECHECK_MS)
                continue
            }
            val base = FeedTiming.alertsBase(state.value.alerts?.value)
            val every = FeedTiming.delayMs(base, alertsFailures, FeedTiming.MAX_ALERTS_BACKOFF_MS)
            val due = loc != alertsLoc || waitLeft(alertsLast, every) <= 0
            if (!due) { sleep(minOf(waitLeft(alertsLast, every), FeedTiming.SLICE_MS)); continue }
            val ok = refreshAlerts()
            alertsLoc = loc
            alertsFailures = if (ok) 0 else alertsFailures + 1
            alertsLast = clock()
        }
    }

    private suspend fun scheduleLoop() {
        while (true) {
            if (!switchesNow().games) { sleep(FeedTiming.SLICE_MS); continue }
            val base = FeedTiming.scheduleBase(state.value.games?.value.orEmpty(), clock())
            val max = if (base == FeedTiming.LIVE_MS) FeedTiming.MAX_LIVE_BACKOFF_MS else FeedTiming.MAX_BACKOFF_MS
            val wait = waitLeft(scheduleLast, FeedTiming.delayMs(base, scheduleFailures, max))
            if (wait > 0) { sleep(minOf(wait, FeedTiming.SLICE_MS)); continue }
            val ok = refreshSchedules()
            scheduleFailures = if (ok) 0 else scheduleFailures + 1
            scheduleLast = clock()
        }
    }

    private fun waitLeft(last: Instant?, every: Long): Long =
        if (last == null) 0 else (every - Duration.between(last, clock()).toMillis()).coerceAtLeast(0)
}
