package tvhome.core.feeds

import java.time.Duration
import java.time.Instant

/**
 * When the Watch pill appears. Brian 2026-11-13: "have the button appear 15 minutes before any pre-game coverage on the
 * same channel", then "if you dont have pre-game feeds, just make it available an hour before the game." The schedule
 * feeds give the start only, never the pre-game show, so the pill shows from [LEAD] before the start, and always while
 * the game is live.
 */
object WatchTiming {
    val LEAD: Duration = Duration.ofHours(1)

    /** The moment the pill may appear for [game]. */
    fun showFrom(game: TeamGame): Instant = game.game.start.minus(LEAD)

    /** Live games always; upcoming ones from [showFrom]; never an ended game. */
    fun shown(game: TeamGame, now: Instant): Boolean = when (game.game.phase) {
        GamePhase.LIVE -> true
        GamePhase.FINAL -> false
        GamePhase.UPCOMING -> !now.isBefore(showFrom(game))
    }
}
