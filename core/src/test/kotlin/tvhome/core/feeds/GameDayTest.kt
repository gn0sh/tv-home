package tvhome.core.feeds

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

class GameDayTest {
    private val zone = ZoneId.of("America/Chicago")
    private fun at(h: Int, m: Int = 0, day: Int = 1) = ZonedDateTime.of(2026, 11, day, h, m, 0, 0, zone).toInstant()
    private fun game(team: TeamConfig, start: Instant, phase: GamePhase = GamePhase.UPCOMING, own: Int? = null, opp: Int? = null,
                     period: String? = null, home: Boolean = true, opponent: String = "Blues") =
        TeamGame(team, GameInfo(start, opponent, home, phase, own, opp, period))
    private val nsh = Teams.PREDATORS
    private val osu = Teams.OHIO_STATE

    @Test fun noGameTodayMeansNoSelection() {
        assertNull(GameDay.select(emptyList(), at(12), zone))
        assertNull(GameDay.select(listOf(game(nsh, at(19, day = 2))), at(12), zone))
        assertNull(GameDay.select(listOf(game(nsh, at(19, day = 1), GamePhase.FINAL)), at(12, day = 2), zone))
    }

    @Test fun localDayNotUtcDay() {
        // 8 PM Chicago on Nov 1 is already Nov 2 in UTC.
        val g = game(nsh, at(20))
        assertSame(g, GameDay.select(listOf(g), at(9), zone))
    }

    @Test fun soonestUpcomingWins() {
        val late = game(nsh, at(19)); val early = game(osu, at(15), opponent = "Michigan")
        assertSame(early, GameDay.select(listOf(late, early), at(9), zone))
    }

    @Test fun liveBeatsEverythingEvenALaterStartedOne() {
        val up = game(nsh, at(13)); val live = game(osu, at(12), GamePhase.LIVE, 7, 3, "Q2 4:00")
        assertSame(live, GameDay.select(listOf(up, live), at(12, 30), zone))
    }

    @Test fun upcomingBeatsFinal() {
        val fin = game(osu, at(9), GamePhase.FINAL, 3, 1); val up = game(nsh, at(19))
        assertSame(up, GameDay.select(listOf(fin, up), at(15), zone))
        assertSame(fin, GameDay.select(listOf(fin), at(23, 59), zone))
        assertNull(GameDay.select(listOf(fin), at(0, 0, day = 2), zone))
    }

    @Test fun staleFeedDataIsIgnored() {
        assertNull(GameDay.select(listOf(game(nsh, at(10))), at(15), zone)) // "upcoming" 5 h after start
        assertNull(GameDay.select(listOf(game(nsh, at(8), GamePhase.LIVE, 1, 1)), at(15), zone)) // "live" 7 h after start
        assertNotNull(GameDay.select(listOf(game(nsh, at(14), GamePhase.LIVE, 1, 1)), at(15), zone))
    }

    @Test fun lines() {
        val now = at(16, 45)
        assertEquals("Predators vs Blues · 7:00 PM · in 2h 15m", GameDay.line(game(nsh, at(19)), now, zone))
        assertEquals("Predators at Blues · 5:30 PM · in 45m", GameDay.line(game(nsh, at(17, 30), home = false), at(16, 45), zone))
        assertEquals("Predators vs Blues · 7:00 PM · starting now", GameDay.line(game(nsh, at(19)), at(19, 0), zone))
        assertEquals("Ohio State 21, Michigan 14 · Q3 8:12",
            GameDay.line(game(osu, at(12), GamePhase.LIVE, 21, 14, "Q3 8:12", opponent = "Michigan"), now, zone))
        assertEquals("Predators vs Blues · live", GameDay.line(game(nsh, at(16), GamePhase.LIVE), now, zone))
        assertEquals("Final (OT) · Predators 4, Blues 3", GameDay.line(game(nsh, at(14), GamePhase.FINAL, 4, 3, "OT"), now, zone))
        assertEquals("Final · Ohio State 24, Michigan 27", GameDay.line(game(osu, at(12), GamePhase.FINAL, 24, 27, opponent = "Michigan"), now, zone))
    }

    @Test fun badges() {
        assertEquals("GAME DAY", GameDay.badge(game(nsh, at(19))))
        assertEquals("GAME DAY", GameDay.badge(game(nsh, at(19), GamePhase.FINAL)))
        assertEquals("LIVE", GameDay.badge(game(nsh, at(19), GamePhase.LIVE)))
    }

    @Test fun countdownBoundaries() {
        assertEquals("starting now", GameDay.countdown(Duration.ofSeconds(59)))
        assertEquals("starting now", GameDay.countdown(Duration.ofMinutes(-3)))
        assertEquals("in 1m", GameDay.countdown(Duration.ofSeconds(60)))
        assertEquals("in 1h 0m", GameDay.countdown(Duration.ofMinutes(60)))
    }
}
