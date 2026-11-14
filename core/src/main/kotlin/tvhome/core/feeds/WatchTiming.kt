package tvhome.core.feeds

import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/**
 * When the Watch pill appears (Brian 2026-11-13: "have the button appear 15 minutes before any pre-game coverage on
 * the same channel"). The schedule feeds give the start only, never the pre-game show, so this is a small table of how
 * early each broadcast's pre-game starts, from the networks' usual line-ups (estimates; change a row here when one is
 * wrong). The pill shows from [EARLY] before that, and always while the game is live.
 */
object WatchTiming {
    /** Brian's 15 minutes ahead of the pre-game show. */
    val EARLY: Duration = Duration.ofMinutes(15)

    /** NHL broadcasts (Scripps, FanDuel Sports Network, ESPN, TNT, streaming) open about half an hour before puck drop. */
    val NHL_PREGAME: Duration = Duration.ofMinutes(30)

    /** College football: a half-hour lead-in is the usual. */
    val FOOTBALL_PREGAME: Duration = Duration.ofMinutes(30)

    /** FOX's noon (Eastern) game follows Big Noon Kickoff, which starts three hours before kickoff. */
    val FOX_NOON_PREGAME: Duration = Duration.ofHours(3)
    private val EASTERN: ZoneId = ZoneId.of("America/New_York")

    /** How long before the start the pre-game coverage on [network] begins. */
    fun pregame(game: TeamGame, network: String?): Duration = when (game.team.league) {
        League.NHL -> NHL_PREGAME
        League.ESPN_FOOTBALL -> {
            val et = game.game.start.atZone(EASTERN)
            val fox = network?.trim()?.equals("FOX", ignoreCase = true) == true
            if (fox && et.hour == 12 && et.minute == 0) FOX_NOON_PREGAME else FOOTBALL_PREGAME
        }
    }

    /** The moment the pill may appear for [game] on [network]. */
    fun showFrom(game: TeamGame, network: String?): Instant = game.game.start.minus(pregame(game, network)).minus(EARLY)

    /** Live games always; upcoming ones from [showFrom]; never an ended game. */
    fun shown(game: TeamGame, network: String?, now: Instant): Boolean = when (game.game.phase) {
        GamePhase.LIVE -> true
        GamePhase.FINAL -> false
        GamePhase.UPCOMING -> !now.isBefore(showFrom(game, network))
    }
}
