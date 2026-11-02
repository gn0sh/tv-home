package tvhome.core.feeds

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tvhome.core.state.CacheFile
import java.time.Duration
import java.time.Instant

/**
 * One followed team's games from its league's unofficial feed, behind an adapter (spec M7-4.4, ADR 0005). Null means
 * nothing is known (no live answer and no usable season cache): the caller counts a failed refresh. Never throws.
 */
fun interface ScheduleSource {
    suspend fun games(now: Instant): ScheduleResult?
}

/** [fromFallback] is true when the live document gave nothing usable and [games] came from the season cache. */
data class ScheduleResult(val games: List<GameInfo>, val fromFallback: Boolean = false)

/**
 * The adapter for one team. The live document is read first (NHL: the week with scores; ESPN: the team schedule, which
 * is also the season). When it cannot be fetched or is no longer the expected shape, the games come from the cached
 * season schedule instead: a shape change gives games from the fallback, never a crash (M7-B8).
 *
 * The season is fetched at most once a day (NHL: `club-schedule-season`, in addition to the live week; ESPN: the live
 * document itself is cached) and kept on disk in [cache] (one file; null means memory only). Only the games within a
 * window around now are kept ([Schedules.parseSeason]). A cache older than [SEASON_MAX_AGE] is not used.
 */
class LeagueSchedule(
    private val team: TeamConfig,
    private val fetcher: FeedFetcher,
    private val cache: CacheFile? = null,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ScheduleSource {
    private val serializer = PanelCaches.games(listOf(team))
    private var season: Stamped<List<GameInfo>>? = null
    private var seasonLoaded = false

    override suspend fun games(now: Instant): ScheduleResult? {
        val liveUrl = team.scheduleUrl(now)
        val seasonUrl = team.seasonUrl(now)
        val sameDocument = liveUrl == seasonUrl
        val raw = fetcher.fetch(liveUrl)
        val live = raw?.let { withContext(io) { Schedules.parse(team, it, now) } }
        if (live != null) {
            // ESPN: the live document is the season, so keep it. NHL: top the season up once a day.
            if (sameDocument) keep(withContext(io) { Schedules.parseSeason(team, raw, now) }, now)
            else refreshSeasonIfDue(seasonUrl, now)
            return ScheduleResult(live)
        }
        if (!sameDocument) refreshSeasonIfDue(seasonUrl, now)
        return fallback(now)?.let { ScheduleResult(it, fromFallback = true) }
    }

    private suspend fun refreshSeasonIfDue(url: okhttp3.HttpUrl, now: Instant) {
        val held = loadSeason(now)
        if (held != null && Duration.between(held.at, now) < SEASON_REFRESH && !held.at.isAfter(now)) return
        val parsed = fetcher.fetch(url)?.let { raw -> withContext(io) { Schedules.parseSeason(team, raw, now) } }
        keep(parsed, now)
    }

    /** The cached season, windowed to [now], or null when there is none or it is too old to trust. */
    private suspend fun fallback(now: Instant): List<GameInfo>? {
        val held = loadSeason(now) ?: return null
        if (held.at.isAfter(now.plus(PanelPolicies.MAX_FUTURE_SKEW)) || Duration.between(held.at, now) > SEASON_MAX_AGE) return null
        return held.value.filter { it.start >= now.minus(Schedules.SEASON_BEFORE) && it.start <= now.plus(Schedules.SEASON_AFTER) }
    }

    private suspend fun loadSeason(now: Instant): Stamped<List<GameInfo>>? {
        if (!seasonLoaded) {
            seasonLoaded = true
            val file = cache
            if (season == null && file != null) {
                season = withContext(io) { PanelCacheCodec.load(file, serializer, now) }?.let { s ->
                    Stamped(s.value.map { it.game }, s.at)
                }
            }
        }
        return season
    }

    /** A season that parsed replaces the held one in memory and on disk; null (a failed fetch) keeps what is held. */
    private suspend fun keep(parsed: List<GameInfo>?, now: Instant) {
        parsed ?: return
        val stamped = Stamped(parsed, now)
        season = stamped
        seasonLoaded = true
        val file = cache ?: return
        withContext(io) { PanelCacheCodec.save(file, Stamped(parsed.map { TeamGame(team, it) }, now), serializer) }
    }

    companion object {
        /** NHL season fetched at most this often. */
        val SEASON_REFRESH: Duration = Duration.ofHours(24)

        /** A cached season older than this is not used as a fallback (a missed week of games is better hidden). */
        val SEASON_MAX_AGE: Duration = Duration.ofDays(7)
    }
}

/** Builds the adapter for a team; one place to swap a league's source. */
object GameSources {
    fun forTeam(team: TeamConfig, fetcher: FeedFetcher, cache: CacheFile?, io: CoroutineDispatcher = Dispatchers.IO): ScheduleSource =
        LeagueSchedule(team, fetcher, cache, io)

    /** Cache panel id for a team's season file (`noBackupFilesDir/panels/<id>.json` in the app). */
    fun cacheId(team: TeamConfig): String = "season-${team.key}"
}
