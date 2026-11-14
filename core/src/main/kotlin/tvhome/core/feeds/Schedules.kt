package tvhome.core.feeds

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import java.time.Instant
import java.time.OffsetDateTime

/**
 * Parsers for the two unofficial schedule feeds. A document that is not the expected shape is null ("no data"); one
 * game with a malformed field is skipped without losing the others. Strings are cleaned and capped at 40 characters.
 */
object Schedules {
    private val json = Json { ignoreUnknownKeys = true }
    private const val MAX_GAMES = 64
    private const val MAX_DEPTH = 32

    /** A season document lists 80+ games; a window filter runs before the [MAX_GAMES] cap, over at most this many raw rows. */
    private const val MAX_SEASON_ROWS = 256

    /** The part of a season the cache and the hero care about: a couple of days back to three weeks ahead. */
    val SEASON_BEFORE: java.time.Duration = java.time.Duration.ofDays(2)
    val SEASON_AFTER: java.time.Duration = java.time.Duration.ofDays(21)
    private val MAX_SKEW = java.time.Duration.ofDays(366)

    /** One feed row: a game, or a row that is fine but not ours or not plausible ([Skip]). A row of the wrong shape is null. */
    private sealed interface Row {
        class Game(val g: GameInfo) : Row
        object Skip : Row
    }

    private fun plausible(start: Instant, now: Instant): Boolean =
        java.time.Duration.between(now, start).abs() <= MAX_SKEW

    /** [now] bounds plausible start times to within [MAX_SKEW] (feed-date-range: hostile dates are skipped). */
    fun parse(team: TeamConfig, raw: String, now: Instant = Instant.now()): List<GameInfo>? = runCatching {
        if (NwsAlerts.depthOver(raw, MAX_DEPTH)) return@runCatching null
        val root = json.parseToJsonElement(raw) as? JsonObject ?: return@runCatching null
        when (team.league) {
            League.NHL -> nhl(team, root, now, null)
            League.ESPN_FOOTBALL -> espn(team, root, now, null)
        }
    }.getOrNull()

    /**
     * A whole-season document (NHL `club-schedule-season`, ESPN team schedule): only the games starting within
     * [SEASON_BEFORE] before and [SEASON_AFTER] after [now] are kept, chosen before the [MAX_GAMES] cap so a full 82-game
     * season is not cut off at its start. Same null rules as [parse].
     */
    fun parseSeason(team: TeamConfig, raw: String, now: Instant): List<GameInfo>? = runCatching {
        if (NwsAlerts.depthOver(raw, MAX_DEPTH)) return@runCatching null
        val root = json.parseToJsonElement(raw) as? JsonObject ?: return@runCatching null
        val window: (GameInfo) -> Boolean = { it.start >= now.minus(SEASON_BEFORE) && it.start <= now.plus(SEASON_AFTER) }
        when (team.league) {
            League.NHL -> nhl(team, root, now, window)
            League.ESPN_FOOTBALL -> espn(team, root, now, window)
        }
    }.getOrNull()

    /**
     * Rows are counted before the plausibility and window filters: a non-empty array in which no row has the expected
     * shape (a renamed field in every row) is null, so the caller falls back, and is never an empty list that would
     * overwrite a good cached season. A valid season with no game in the window is an empty list.
     */
    private fun rows(arr: JsonArray, window: ((GameInfo) -> Boolean)?, one: (JsonObject?) -> Row?): List<GameInfo>? {
        val parsed = arr.take(if (window == null) MAX_GAMES else MAX_SEASON_ROWS).map { runCatching { one(it as? JsonObject) }.getOrNull() }
        if (parsed.isNotEmpty() && parsed.all { it == null }) return null
        val games = parsed.mapNotNull { (it as? Row.Game)?.g }
        return if (window == null) games else games.filter(window).take(MAX_GAMES)
    }

    // --- NHL: api-web.nhle.com/v1/club-schedule/{abbrev}/week/{YYYY-MM-DD} -> {"games":[{...}]} -----------------------------

    private fun nhl(team: TeamConfig, root: JsonObject, now: Instant, window: ((GameInfo) -> Boolean)?): List<GameInfo>? {
        val games = root["games"] as? JsonArray ?: return null
        return rows(games, window) { nhlGame(team, it, now) }
    }

    /**
     * Where the game is on for a viewer in our team's market: `tvBroadcasts[].network` where `countryCode` is US and
     * `market` is national (`N`) or our own side (`H` home, `A` away); the other club's local feed (e.g. `CARNHL` when
     * Carolina visits) is left out. A row with no market is kept. Order as the feed gives it.
     */
    private fun nhlNetworks(g: JsonObject, isHome: Boolean): List<String> =
        (g["tvBroadcasts"] as? JsonArray).orEmpty().asSequence()
            .mapNotNull { it as? JsonObject }
            .filter { it["countryCode"].str() == "US" }
            .filter { when (it["market"].str()) { null, "N" -> true; "H" -> isHome; "A" -> !isHome; else -> false } }
            .mapNotNull { Feeds.clean(it["network"].str()) }
            .distinct().take(GameInfo.MAX_NETWORKS).toList()

    private fun nhlGame(team: TeamConfig, g: JsonObject?, now: Instant): Row? {
        g ?: return null
        val start = Instant.parse(g["startTimeUTC"].str() ?: return null)
        val home = g["homeTeam"] as? JsonObject ?: return null
        val away = g["awayTeam"] as? JsonObject ?: return null
        val isHome = when (team.feedId) {
            home["abbrev"].str() -> true
            away["abbrev"].str() -> false
            else -> return null
        }
        val own = if (isHome) home else away
        val opp = if (isHome) away else home
        val opponent = Feeds.clean(name(opp["commonName"]) ?: name(opp["placeName"]) ?: opp["abbrev"].str()) ?: return null
        val phase = when (g["gameState"].str()) {
            "FUT", "PRE" -> GamePhase.UPCOMING
            "LIVE", "CRIT" -> GamePhase.LIVE
            "FINAL", "OFF" -> GamePhase.FINAL
            else -> return null
        }
        val pd = g["periodDescriptor"] as? JsonObject
        val number = pd?.get("number").int()
        val type = pd?.get("periodType").str()
        val period = when (phase) {
            GamePhase.UPCOMING -> null
            GamePhase.LIVE -> when (type) {
                "OT" -> "OT"
                "SO" -> "SO"
                else -> number?.let(::ordinal)
            }
            GamePhase.FINAL -> when (type) { "OT" -> "OT"; "SO" -> "SO"; else -> null }
        }
        if (!plausible(start, now)) return Row.Skip
        val scored = phase != GamePhase.UPCOMING
        return Row.Game(GameInfo(start, opponent, isHome, phase, if (scored) own["score"].int()?.takeIf { it in 0..999 } else null, if (scored) opp["score"].int()?.takeIf { it in 0..999 } else null, period, nhlNetworks(g, isHome), GameInfo.abbr(opp["abbrev"].str())))
    }

    private fun name(e: JsonElement?): String? = (e as? JsonObject)?.get("default").str()

    private fun ordinal(n: Int): String? = when (n) { 1 -> "1st"; 2 -> "2nd"; 3 -> "3rd"; else -> null }

    // --- ESPN: .../college-football/teams/{id}/schedule (or the scoreboard) -> {"events":[{...}]} ------------------

    private fun espn(team: TeamConfig, root: JsonObject, now: Instant, window: ((GameInfo) -> Boolean)?): List<GameInfo>? {
        val events = root["events"] as? JsonArray ?: return null
        return rows(events, window) { espnGame(team, it, now) }
    }

    /**
     * The team schedule gives `broadcasts[].media.shortName` ("BTN", "ABC"); the scoreboard gives `broadcasts[].names[]`
     * ("CBSSN") and `geoBroadcasts[]`. A `geoBroadcasts` row counts only when its `type.shortName` is TV or Streaming
     * (not radio) and its `market.type` is National or our own side (Home when [isHome], else Away): another market's
     * local channel is not where we can watch it (espn-geo-filter). Order as the feed gives it, duplicates once.
     */
    private fun espnNetworks(comp: JsonObject, isHome: Boolean): List<String> {
        val plain = (comp["broadcasts"] as? JsonArray).orEmpty().asSequence()
            .mapNotNull { it as? JsonObject }
            .flatMap { b ->
                val names = (b["names"] as? JsonArray).orEmpty().map { n -> n.str() }
                (listOf((b["media"] as? JsonObject)?.get("shortName").str()) + names).asSequence()
            }
        val ourSide = if (isHome) "home" else "away"
        val geo = (comp["geoBroadcasts"] as? JsonArray).orEmpty().asSequence()
            .mapNotNull { it as? JsonObject }
            .filter { g -> (g["type"] as? JsonObject)?.get("shortName").str()?.lowercase() in GEO_TYPES }
            .filter { g -> (g["market"] as? JsonObject)?.get("type").str()?.lowercase().let { it == "national" || it == ourSide } }
            .map { g -> (g["media"] as? JsonObject)?.get("shortName").str() }
        return (plain + geo)
            .mapNotNull { Feeds.clean(it) }
            .distinct().take(GameInfo.MAX_NETWORKS).toList()
    }

    private val GEO_TYPES = setOf("tv", "streaming")

    private fun espnGame(team: TeamConfig, e: JsonObject?, now: Instant): Row? {
        e ?: return null
        val comp = (e["competitions"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return null
        // ESPN dates have no seconds ("2026-11-01T17:00Z"); OffsetDateTime accepts that, Instant.parse may not.
        val start = OffsetDateTime.parse((comp["date"].str() ?: e["date"].str()) ?: return null).toInstant()
        val competitors = (comp["competitors"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: return null
        val own = competitors.firstOrNull { teamId(it) == team.feedId } ?: return Row.Skip // another team's game on a scoreboard
        val opp = competitors.firstOrNull { it !== own } ?: return null
        val oppTeam = opp["team"] as? JsonObject
        val opponent = Feeds.clean(oppTeam?.get("shortDisplayName").str() ?: oppTeam?.get("displayName").str()) ?: return null

        val status = (comp["status"] as? JsonObject) ?: (e["status"] as? JsonObject) ?: return null
        val type = status["type"] as? JsonObject ?: return null
        val phase = when (type["state"].str()) {
            "pre" -> GamePhase.UPCOMING
            "in" -> GamePhase.LIVE
            "post" -> GamePhase.FINAL
            else -> return null
        }
        if (!plausible(start, now)) return Row.Skip
        val scored = phase != GamePhase.UPCOMING
        val period = when (phase) {
            GamePhase.UPCOMING -> null
            GamePhase.LIVE -> espnLive(type, status)
            GamePhase.FINAL -> status["period"].int()?.takeIf { it > 4 }?.let { "OT" }
        }
        val isHome = own["homeAway"].str() == "home"
        return Row.Game(GameInfo(
            start, opponent, isHome, phase,
            if (scored) score(own["score"]) else null, if (scored) score(opp["score"]) else null, period, espnNetworks(comp, isHome),
            GameInfo.abbr(oppTeam?.get("abbreviation").str()),
        ))
    }

    private fun teamId(c: JsonObject): String? = ((c["team"] as? JsonObject)?.get("id") ?: c["id"]).str()

    /** The scoreboard gives "14"; the team schedule gives {"value":14.0,"displayValue":"14"}. */
    private fun score(e: JsonElement?): Int? = when (e) {
        is JsonObject -> e["displayValue"].str()?.toIntOrNull()
        is JsonPrimitive -> e.content.let { it.toIntOrNull() ?: it.toDoubleOrNull()?.toInt() }
        else -> null
    }?.takeIf { it in 0..999 }

    private val CLOCK = Regex("""\d{1,2}:\d{2}""")

    private fun espnLive(type: JsonObject, status: JsonObject): String? {
        if (type["name"].str() == "STATUS_HALFTIME") return "Half"
        val p = status["period"].int() ?: return null
        val label = if (p in 1..4) "Q$p" else "OT"
        val clock = status["displayClock"].str()?.takeIf { CLOCK.matches(it) }
        return if (clock != null) "$label $clock" else label
    }

    private fun JsonElement?.int(): Int? = (this as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
}
