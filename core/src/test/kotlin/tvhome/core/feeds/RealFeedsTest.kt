package tvhome.core.feeds

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import tvhome.core.Fixtures
import java.time.Instant
import java.time.ZoneId

/** Parsers against real responses captured by capture-feeds (M7-D1) on 2026-11-02. */
class RealFeedsTest {
    private val captured = Instant.parse("2026-11-02T21:36:00Z")

    @Test fun nhlWeek() {
        val games = Schedules.parse(Teams.PREDATORS, Fixtures.text("nhl-week.json"), captured)!!
        assertEquals(2, games.size)
        val (a, b) = games
        assertEquals(GameInfo(Instant.parse("2026-11-04T01:00:00Z"), "Hurricanes", true, GamePhase.UPCOMING, null, null, null), a)
        assertEquals(Instant.parse("2026-11-06T02:30:00Z"), b.start)
        assertEquals(GamePhase.UPCOMING, b.phase)
        assertFalse(b.home, "away game")
        // The game line the hero draws, on game day.
        val line = GameDay.line(TeamGame(Teams.PREDATORS, a), Instant.parse("2026-11-03T22:00:00Z"), ZoneId.of("America/Chicago"))
        assertEquals("Predators vs Hurricanes · 7:00 PM · in 3h 0m", line)
    }

    @Test fun nhlSeasonParsesFinals() {
        val games = Schedules.parse(Teams.PREDATORS, Fixtures.text("nhl-season.json"), captured)!!
        assertEquals(2, games.size)
        assertTrue(games.all { it.phase == GamePhase.FINAL })
        assertTrue(games.all { it.ownScore != null && it.oppScore != null })
    }

    @Test fun espnTeam194() {
        val games = Schedules.parse(Teams.OHIO_STATE, Fixtures.text("espn-team-194.json"), captured)!!
        assertEquals(2, games.size)
        val (a, b) = games
        assertEquals(GameInfo(Instant.parse("2026-09-05T16:30:00Z"), "Ball State", true, GamePhase.FINAL, 56, 3, null), a)
        assertEquals("Texas", b.opponent); assertFalse(b.home)
        assertEquals(23, b.ownScore); assertEquals(24, b.oppScore)
    }

    @Test fun espnScoreboardWithoutOhioStateIsNoGamesNotAnError() {
        val games = Schedules.parse(Teams.OHIO_STATE, Fixtures.text("espn-cfb-scoreboard.json"), captured)
        assertNotNull(games)
    }
}
