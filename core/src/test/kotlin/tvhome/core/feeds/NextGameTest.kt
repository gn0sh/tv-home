package tvhome.core.feeds

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import tvhome.core.Fixtures
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/** The next game per team (Board, home card), on any day, with where it is on. */
class NextGameTest {
    private val zone = ZoneId.of("America/Chicago")
    private val now = Instant.parse("2026-11-03T15:00:00Z") // Tue 09:00 local
    private fun g(team: TeamConfig, start: String, phase: GamePhase = GamePhase.UPCOMING, nets: List<String> = emptyList()) =
        TeamGame(team, GameInfo(Instant.parse(start), "Stars", false, phase, null, null, null, nets))

    @Test fun soonestUpcomingPerTeamOrOverall() {
        val preds = g(Teams.PREDATORS, "2026-11-06T01:00:00Z")
        val predsLater = g(Teams.PREDATORS, "2026-11-08T01:00:00Z")
        val osu = g(Teams.OHIO_STATE, "2026-11-07T17:00:00Z")
        val all = listOf(predsLater, osu, preds)
        assertEquals(preds, GameDay.next(all, "predators", now))
        assertEquals(osu, GameDay.next(all, "ohio-state", now))
        assertEquals(preds, GameDay.next(all, null, now))
        assertNull(GameDay.next(listOf(osu), "predators", now))
    }

    @Test fun liveBeatsUpcomingAndMissedOrFinishedGamesAreSkipped() {
        val live = g(Teams.PREDATORS, "2026-11-03T14:00:00Z", GamePhase.LIVE)
        val up = g(Teams.PREDATORS, "2026-11-04T01:00:00Z")
        assertEquals(live, GameDay.next(listOf(up, live), "predators", now))
        val missed = g(Teams.PREDATORS, "2026-11-03T10:00:00Z") // still "upcoming" 5 h after its start
        val final = g(Teams.PREDATORS, "2026-11-02T01:00:00Z", GamePhase.FINAL)
        assertEquals(up, GameDay.next(listOf(missed, final, up), "predators", now))
        val staleLive = g(Teams.PREDATORS, "2026-11-03T08:00:00Z", GamePhase.LIVE)
        assertEquals(up, GameDay.next(listOf(staleLive, up), "predators", now))
    }

    @Test fun tvTextIsNeverHidden() {
        assertEquals("TV: not announced yet", GameDay.tvText(g(Teams.PREDATORS, "2026-11-06T01:00:00Z").game))
        assertEquals("TV: ESPN+, HULU, Disney+", GameDay.tvText(g(Teams.PREDATORS, "2026-11-06T01:00:00Z", nets = listOf("ESPN+", "HULU", "Disney+", "X")).game))
    }

    @Test fun dayWords() {
        fun at(s: String) = GameDay.dayText(Instant.parse(s), now, zone)
        assertEquals("Today", at("2026-11-04T01:00:00Z")) // Tue 7 PM local
        assertEquals("Tomorrow", at("2026-11-04T18:00:00Z"))
        assertEquals("Mon", at("2026-11-09T18:00:00Z"))
        assertEquals("Tue 10 Nov", at("2026-11-10T18:00:00Z"))
    }

    /** Real captures: the next Predators game and its broadcasters, as the Board and the home card say them. */
    @Test fun realNhlWeekNextGameLine() {
        val captured = Instant.parse("2026-11-02T21:36:00Z")
        val games = Schedules.parse(Teams.PREDATORS, Fixtures.text("nhl-week.json"), captured)!!.map { TeamGame(Teams.PREDATORS, it) }
        val n = GameDay.next(games, "predators", captured)!!
        assertEquals("Predators vs Hurricanes · Tomorrow 7:00 PM · TV: SCRIPPS", GameDay.nextLine(n, captured, zone))
        val after = GameDay.next(games, "predators", n.game.start.plus(Duration.ofHours(5)))!!
        assertEquals("Predators at Avalanche · Tomorrow 8:30 PM · TV: ESPN+, HULU, Disney+", GameDay.nextLine(after, n.game.start.plus(Duration.ofHours(5)), zone))
    }
}

class NextGame24hTest {
    @Test fun whenTextFollowsTheClockSetting() {
        val zone = ZoneId.of("America/Chicago")
        val now = Instant.parse("2026-11-03T15:00:00Z")
        val start = Instant.parse("2026-11-06T01:00:00Z") // Thu 7:00 PM local
        assertEquals("Thu 7:00 PM", GameDay.whenText(start, now, zone))
        assertEquals("Thu 19:00", GameDay.whenText(start, now, zone, is24h = true))
    }
}
