package tvhome.core.feeds

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Which followed game, if any, owns the hero right now, and how to say it. Pure: the caller passes the clock and zone.
 *
 * Game day means a game whose local start date is today, or one that is live. Priority: a live game, then the upcoming
 * game that starts soonest, then a final (shown until local midnight). Feed data that is too old to trust is ignored:
 * an "upcoming" game more than 4 hours past its start, or a "live" one more than 6 hours past it, was missed by the
 * refresh and would only mislead.
 */
object GameDay {
    private val TIME = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
    private val STALE_UPCOMING = Duration.ofHours(4)
    internal val STALE_LIVE: Duration = Duration.ofHours(6)

    /** Never throws: any failure on odd feed data is "no game" (feed-date-range). */
    fun select(games: List<TeamGame>, now: Instant, zone: ZoneId): TeamGame? =
        runCatching { selectUnsafe(games, now, zone) }.getOrNull()

    private fun selectUnsafe(games: List<TeamGame>, now: Instant, zone: ZoneId): TeamGame? {
        val today = now.atZone(zone).toLocalDate()
        val candidates = games.filter { (_, g) ->
            when (g.phase) {
                GamePhase.LIVE -> Duration.between(g.start, now).let { it < STALE_LIVE && it > Duration.ofDays(-1) }
                GamePhase.UPCOMING -> Duration.between(g.start, now) < STALE_UPCOMING && g.start.atZone(zone).toLocalDate() == today
                GamePhase.FINAL -> g.start.atZone(zone).toLocalDate() == today
            }
        }
        return candidates.filter { it.game.phase == GamePhase.LIVE }.minByOrNull { it.game.start }
            ?: candidates.filter { it.game.phase == GamePhase.UPCOMING }.minByOrNull { it.game.start }
            ?: candidates.filter { it.game.phase == GamePhase.FINAL }.maxByOrNull { it.game.start }
    }

    /** The badge beside the clock: "LIVE" during a game, otherwise "GAME DAY". */
    fun badge(g: TeamGame): String = if (g.game.phase == GamePhase.LIVE) "LIVE" else "GAME DAY"

    /** One line for the top bar, e.g. "Predators vs Blues, 7:00 PM, in 2h 15m". */
    fun line(tg: TeamGame, now: Instant, zone: ZoneId): String =
        runCatching { lineUnsafe(tg, now, zone) }.getOrDefault(tg.team.name)

    private fun lineUnsafe(tg: TeamGame, now: Instant, zone: ZoneId): String {
        val (team, g) = tg
        val vs = if (g.home) "vs" else "at"
        return when (g.phase) {
            GamePhase.UPCOMING ->
                "${team.name} $vs ${g.opponent} · ${TIME.format(g.start.atZone(zone))} · ${countdown(Duration.between(now, g.start))}"
            GamePhase.LIVE -> {
                val score = scoreText(team, g)
                (if (score != null) "$score · ${g.period ?: "live"}" else "${team.name} $vs ${g.opponent} · ${g.period ?: "live"}")
            }
            GamePhase.FINAL -> {
                val score = scoreText(team, g)
                val ot = g.period?.let { " ($it)" } ?: ""
                if (score != null) "Final$ot · $score" else "Final$ot · ${team.name} $vs ${g.opponent}"
            }
        }
    }

    private fun scoreText(team: TeamConfig, g: GameInfo): String? {
        val a = g.ownScore ?: return null
        val b = g.oppScore ?: return null
        return "${team.name} $a, ${g.opponent} $b"
    }

    internal fun countdown(d: Duration): String {
        val minutes = d.toMinutes()
        return when {
            d.seconds <= -60 -> "started"
            minutes < 1 -> "starting now"
            minutes < 60 -> "in ${minutes}m"
            else -> "in ${minutes / 60}h ${minutes % 60}m"
        }
    }
}
