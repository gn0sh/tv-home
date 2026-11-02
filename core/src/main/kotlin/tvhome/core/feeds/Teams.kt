package tvhome.core.feeds

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.time.Instant
import java.time.ZoneId

/** Which public schedule feed a team is read from. */
enum class League { NHL, ESPN_FOOTBALL }

/**
 * One followed team. The list [Teams.ALL] is data: add a row to follow another team.
 * [feedId] is the NHL club abbreviation or the ESPN team id. [primary] and [secondary] are the team's brand colours
 * (documentation); the three theme colours are the shades the hero uses, adjusted so text keeps 4.5:1 contrast.
 * All colours are 0xRRGGBB.
 */
data class TeamConfig(
    val key: String,
    val league: League,
    val feedId: String,
    val name: String,
    val primary: Int,
    val secondary: Int,
    val backdropTop: Int,
    val backdropBottom: Int,
    val accent: Int,
    /** The club's home time zone: the NHL dated URL uses the club's local date. */
    val zone: ZoneId,
) {
    /**
     * NHL: `week/YYYY-MM-DD` for today in [zone]. The `week/now` form answers HTTP 307 to the dated URL, and
     * [HttpFeedFetcher] refuses redirects (ADR 0005), so `now` never worked (CI capture, 2026-11-02).
     */
    fun scheduleUrl(now: Instant): HttpUrl = when (league) {
        League.NHL -> "https://api-web.nhle.com/v1/club-schedule/$feedId/week/${now.atZone(zone).toLocalDate()}"
        League.ESPN_FOOTBALL -> espnScheduleUrl()
    }.toHttpUrl()

    /**
     * The whole-season schedule, the fallback when the live document changes shape (spec M7-4.4). NHL: the dated season
     * form `club-schedule-season/NSH/20262027` (its `now` form answers 307 like the week one). ESPN: the team schedule
     * is already the season document, so it is the same URL.
     */
    fun seasonUrl(now: Instant): HttpUrl = when (league) {
        League.NHL -> "https://api-web.nhle.com/v1/club-schedule-season/$feedId/${nhlSeason(now.atZone(zone).toLocalDate())}"
        League.ESPN_FOOTBALL -> espnScheduleUrl()
    }.toHttpUrl()

    private fun espnScheduleUrl() = "https://site.api.espn.com/apis/site/v2/sports/football/college-football/teams/$feedId/schedule"
}

/** NHL season id for a local date: July starts the next season (2026-27 is 20262027). */
internal fun nhlSeason(date: java.time.LocalDate): String {
    val start = if (date.monthValue >= 7) date.year else date.year - 1
    return "$start${start + 1}"
}

object Teams {
    /** Gold #FFB81C on navy #041E42. */
    val PREDATORS = TeamConfig(
        key = "predators", league = League.NHL, feedId = "NSH", name = "Predators",
        primary = 0xFFB81C, secondary = 0x041E42,
        backdropTop = 0x041E42, backdropBottom = 0x0B3169, accent = 0xFFB81C,
        zone = ZoneId.of("America/Chicago"),
    )

    /** Scarlet #BB0000 and gray #666666. Gray is too dark for text on the dark backdrop, so the accent is a lighter gray. */
    val OHIO_STATE = TeamConfig(
        key = "ohio-state", league = League.ESPN_FOOTBALL, feedId = "194", name = "Ohio State",
        primary = 0xBB0000, secondary = 0x666666,
        backdropTop = 0x2A0707, backdropBottom = 0x8A0000, accent = 0xC4C4C4,
        zone = ZoneId.of("America/New_York"),
    )

    val ALL: List<TeamConfig> = listOf(PREDATORS, OHIO_STATE)
}

enum class GamePhase { UPCOMING, LIVE, FINAL }

/**
 * One game of a followed team. Scores are the team's own and the opponent's; [period] is a short label such as "2nd",
 * "Q3 8:12", "OT" (live) or "OT"/"SO" (final), already capped.
 */
data class GameInfo(
    val start: Instant,
    val opponent: String,
    val home: Boolean,
    val phase: GamePhase,
    val ownScore: Int?,
    val oppScore: Int?,
    val period: String?,
    /** Broadcast network names the feed lists for this game (cleaned, at most [MAX_NETWORKS]); empty when it lists none. */
    val networks: List<String> = emptyList(),
) {
    companion object { const val MAX_NETWORKS = 8 }
}

/** A game together with the team it belongs to. */
data class TeamGame(val team: TeamConfig, val game: GameInfo)
