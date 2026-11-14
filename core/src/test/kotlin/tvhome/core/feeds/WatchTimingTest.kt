package tvhome.core.feeds

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Instant

/** Brian 2026-11-13: with no pre-game feed, the Watch pill appears an hour before the game. */
class WatchTimingTest {
    private fun g(team: TeamConfig, start: String, phase: GamePhase = GamePhase.UPCOMING) =
        TeamGame(team, GameInfo(Instant.parse(start), "X", true, phase, null, null, null))

    @Test fun pillAppearsAnHourBeforeTheStart() {
        val preds = g(Teams.PREDATORS, "2026-11-14T03:00:00Z") // 9:00 PM CT
        assertFalse(WatchTiming.shown(preds, Instant.parse("2026-11-14T01:59:59Z")))
        assertTrue(WatchTiming.shown(preds, Instant.parse("2026-11-14T02:00:00Z")))
        val buckeyes = g(Teams.OHIO_STATE, "2026-11-14T17:00:00Z") // 11 AM CT
        assertEquals(Instant.parse("2026-11-14T16:00:00Z"), WatchTiming.showFrom(buckeyes))
    }

    @Test fun liveAlwaysFinalNever() {
        val t = Instant.parse("2026-11-14T03:30:00Z")
        assertTrue(WatchTiming.shown(g(Teams.PREDATORS, "2026-11-14T03:00:00Z", GamePhase.LIVE), t.minusSeconds(86_000)))
        assertFalse(WatchTiming.shown(g(Teams.PREDATORS, "2026-11-14T03:00:00Z", GamePhase.FINAL), t))
    }
}
