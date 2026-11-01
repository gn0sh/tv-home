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
    private val MAX_SKEW = java.time.Duration.ofDays(366)

    private fun plausible(start: Instant, now: Instant): Boolean =
        java.time.Duration.between(now, start).abs() <= MAX_SKEW

    /** [now] bounds plausible start times to within [MAX_SKEW] (feed-date-range: hostile dates are skipped). */
    fun parse(team: TeamConfig, raw: String, now: Instant = Instant.now()): List<GameInfo>? = runCatching {
        val root = json.parseToJsonElement(raw) as? JsonObject ?: return@runCatching null
        when (team.league) {
            League.NHL -> nhl(team, root, now)
            League.ESPN_FOOTBALL -> espn(team, root, now)
        }
    }.getOrNull()

    // --- NHL: api-web.nhle.com/v1/club-schedule/{abbrev}/week/now -> {"games":[{...}]} -----------------------------

    private fun nhl(team: TeamConfig, root: JsonObject, now: Instant): List<GameInfo>? {
        val games = root["games"] as? JsonArray ?: return null
        return games.take(MAX_GAMES).mapNotNull { runCatching { nhlGame(team, it as? JsonObject, now) }.getOrNull() }
    }

    private fun nhlGame(team: TeamConfig, g: JsonObject?, now: Instant): GameInfo? {
        g ?: return null
        val start = Instant.parse(g["startTimeUTC"].str() ?: return null).takeIf { plausible(it, now) } ?: return null
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
        val scored = phase != GamePhase.UPCOMING
        return GameInfo(start, opponent, isHome, phase, if (scored) own["score"].int()?.takeIf { it in 0..999 } else null, if (scored) opp["score"].int()?.takeIf { it in 0..999 } else null, period)
    }

    private fun name(e: JsonElement?): String? = (e as? JsonObject)?.get("default").str()

    private fun ordinal(n: Int): String? = when (n) { 1 -> "1st"; 2 -> "2nd"; 3 -> "3rd"; else -> null }

    // --- ESPN: .../college-football/teams/{id}/schedule (or the scoreboard) -> {"events":[{...}]} ------------------

    private fun espn(team: TeamConfig, root: JsonObject, now: Instant): List<GameInfo>? {
        val events = root["events"] as? JsonArray ?: return null
        return events.take(MAX_GAMES).mapNotNull { runCatching { espnGame(team, it as? JsonObject, now) }.getOrNull() }
    }

    private fun espnGame(team: TeamConfig, e: JsonObject?, now: Instant): GameInfo? {
        e ?: return null
        val comp = (e["competitions"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return null
        // ESPN dates have no seconds ("2026-11-01T17:00Z"); OffsetDateTime accepts that, Instant.parse may not.
        val start = OffsetDateTime.parse((comp["date"].str() ?: e["date"].str()) ?: return null).toInstant().takeIf { plausible(it, now) } ?: return null
        val competitors = (comp["competitors"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: return null
        val own = competitors.firstOrNull { teamId(it) == team.feedId } ?: return null
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
        val scored = phase != GamePhase.UPCOMING
        val period = when (phase) {
            GamePhase.UPCOMING -> null
            GamePhase.LIVE -> espnLive(type, status)
            GamePhase.FINAL -> status["period"].int()?.takeIf { it > 4 }?.let { "OT" }
        }
        return GameInfo(
            start, opponent, own["homeAway"].str() == "home", phase,
            if (scored) score(own["score"]) else null, if (scored) score(opp["score"]) else null, period,
        )
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
