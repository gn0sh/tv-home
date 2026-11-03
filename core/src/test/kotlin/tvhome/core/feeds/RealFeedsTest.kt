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
        assertEquals(GameInfo(Instant.parse("2026-11-04T01:00:00Z"), "Hurricanes", true, GamePhase.UPCOMING, null, null, null, listOf("SCRIPPS")), a)
        assertEquals(Instant.parse("2026-11-06T02:30:00Z"), b.start)
        assertEquals(GamePhase.UPCOMING, b.phase)
        assertFalse(b.home, "away game")
        // Broadcasts (real capture): national US feeds for the away game; Canadian feeds left out.
        assertEquals(listOf("ESPN+", "HULU", "Disney+"), b.networks)
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
        assertEquals(GameInfo(Instant.parse("2026-09-05T16:30:00Z"), "Ball State", true, GamePhase.FINAL, 56, 3, null, listOf("BTN")), a)
        assertEquals("Texas", b.opponent); assertFalse(b.home)
        assertEquals(23, b.ownScore); assertEquals(24, b.oppScore)
    }

    /** Home game: our own (H) feed is kept; the visitor's local feeds (A: CARNHL, HHN) and Canada's (SN+) are not. */
    @Test fun nhlBroadcastsKeepNationalAndOwnMarketOnly() {
        val (home, away) = Schedules.parse(Teams.PREDATORS, Fixtures.text("nhl-week.json"), captured)!!
        assertEquals(listOf("SCRIPPS"), home.networks)
        assertFalse(home.networks.any { it == "CARNHL" || it == "HHN" || it == "SN+" })
        assertFalse(away.networks.contains("TVAS"))
    }

    @Test fun espnTeamScheduleBroadcasts() {
        val games = Schedules.parse(Teams.OHIO_STATE, Fixtures.text("espn-team-194.json"), captured)!!
        assertEquals(listOf(listOf("BTN"), listOf("ABC")), games.map { it.networks })
    }

    /** The scoreboard shape: `broadcasts[].names` and `geoBroadcasts[].media.shortName` (same network, listed once). */
    @Test fun espnScoreboardBroadcasts() {
        val team = Teams.OHIO_STATE.copy(key = "t2440", feedId = "2440")
        val games = Schedules.parse(team, Fixtures.text("espn-cfb-scoreboard.json"), captured)!!
        assertEquals(1, games.size)
        assertEquals(listOf("CBSSN"), games.single().networks)
        assertEquals(GamePhase.UPCOMING, games.single().phase)
    }

    /** espn-geo-filter: radio rows and the other side's local market are not where we watch. */
    @Test fun espnGeoBroadcastsKeepTvAndStreamingForNationalOrOurSide() {
        fun geo(name: String, type: String, market: String) =
            """{"type":{"id":"1","shortName":"$type"},"market":{"id":"1","type":"$market"},"media":{"shortName":"$name"}}"""
        val doc = """{"events":[{"date":"2026-11-07T17:00Z","competitions":[{"date":"2026-11-07T17:00Z",
            "status":{"type":{"state":"pre"}},
            "competitors":[{"homeAway":"home","team":{"id":"194","shortDisplayName":"Ohio State"}},
                           {"homeAway":"away","team":{"id":"99","shortDisplayName":"Purdue"}}],
            "geoBroadcasts":[${geo("FOX", "TV", "National")},${geo("WBNS Radio", "Radio", "National")},
                             ${geo("Peacock", "Streaming", "National")},${geo("WOSU", "TV", "Home")},${geo("WLFI", "TV", "Away")}]}]}]}"""
        val g = Schedules.parse(Teams.OHIO_STATE, doc, captured)!!.single()
        assertEquals(listOf("FOX", "Peacock", "WOSU"), g.networks)
    }

    @Test fun espnScoreboardWithoutOhioStateIsNoGamesNotAnError() {
        val games = Schedules.parse(Teams.OHIO_STATE, Fixtures.text("espn-cfb-scoreboard.json"), captured)
        assertNotNull(games)
    }
}
