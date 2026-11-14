package tvhome.core.feeds

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Instant

/** Brian 2026-11-13: the Watch pill appears 15 minutes before the pre-game show on the same channel. */
class WatchTimingTest {
    private fun g(team: TeamConfig, start: String, phase: GamePhase = GamePhase.UPCOMING) =
        TeamGame(team, GameInfo(Instant.parse(start), "X", true, phase, null, null, null))

    @Test fun predsPillAppears45MinutesBeforePuckDrop() {
        val game = g(Teams.PREDATORS, "2026-11-14T03:00:00Z") // 9:00 PM CT
        assertEquals(Instant.parse("2026-11-14T02:15:00Z"), WatchTiming.showFrom(game, "SCRIPPS"))
        assertFalse(WatchTiming.shown(game, "SCRIPPS", Instant.parse("2026-11-14T02:14:59Z")))
        assertTrue(WatchTiming.shown(game, "SCRIPPS", Instant.parse("2026-11-14T02:15:00Z")))
    }

    @Test fun foxNoonGameWaitsForBigNoonKickoff() {
        val buckeyes = g(Teams.OHIO_STATE, "2026-11-14T17:00:00Z") // noon ET, 11 AM CT
        assertEquals(Instant.parse("2026-11-14T13:45:00Z"), WatchTiming.showFrom(buckeyes, "FOX")) // 7:45 AM CT
        assertEquals(Instant.parse("2026-11-14T16:15:00Z"), WatchTiming.showFrom(buckeyes, "BTN"))
        val night = g(Teams.OHIO_STATE, "2026-11-22T00:30:00Z") // 7:30 PM ET on FOX: the usual half hour
        assertEquals(Instant.parse("2026-11-21T23:45:00Z"), WatchTiming.showFrom(night, "FOX"))
    }

    @Test fun liveAlwaysFinalNever() {
        val t = Instant.parse("2026-11-14T03:30:00Z")
        assertTrue(WatchTiming.shown(g(Teams.PREDATORS, "2026-11-14T03:00:00Z", GamePhase.LIVE), "SCRIPPS", t.minusSeconds(86_000)))
        assertFalse(WatchTiming.shown(g(Teams.PREDATORS, "2026-11-14T03:00:00Z", GamePhase.FINAL), "SCRIPPS", t))
    }
}
