package tvhome.core.board

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import tvhome.core.agenda.AgendaDay
import tvhome.core.feeds.AlertKind
import tvhome.core.feeds.AlertSet
import tvhome.core.feeds.DayForecast
import tvhome.core.feeds.FeedsSnapshot
import tvhome.core.feeds.GameInfo
import tvhome.core.feeds.GamePhase
import tvhome.core.feeds.NwsAlert
import tvhome.core.feeds.PanelSwitches
import tvhome.core.feeds.Precip
import tvhome.core.feeds.Stamped
import tvhome.core.feeds.TeamGame
import tvhome.core.feeds.Teams
import tvhome.core.feeds.WeatherNow
import tvhome.core.state.AgendaState
import tvhome.core.state.StaleCause
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class BoardTest {
    private val zone = ZoneId.of("America/Chicago")
    private val today = LocalDate.of(2026, 11, 3)
    private val now = today.atTime(9, 0).atZone(zone).toInstant()
    private val week = (0 until 7).map { AgendaDay(today.plusDays(it.toLong()), emptyList()) }
    private val ok = AgendaState.Ok(week[0], week[1], now, "Updated just now", week)

    private fun inputs(
        agenda: AgendaState = ok,
        feeds: FeedsSnapshot = FeedsSnapshot(),
        switches: PanelSwitches = PanelSwitches(),
        hasLocation: Boolean = true,
    ) = BoardInputs(now, zone, false, agenda, feeds, switches, hasLocation, quiet = false)

    private fun game(team: tvhome.core.feeds.TeamConfig, start: String, nets: List<String>) =
        TeamGame(team, GameInfo(Instant.parse(start), "Stars", true, GamePhase.UPCOMING, null, null, null, nets))

    @Test fun bothTeamsAlwaysListedWithWhereItIsOn() {
        val games = listOf(game(Teams.PREDATORS, "2026-11-06T01:00:00Z", listOf("ESPN+")), game(Teams.OHIO_STATE, "2026-11-07T17:00:00Z", emptyList()))
        val b = board(inputs(feeds = FeedsSnapshot(games = Stamped(games, now))))
        assertEquals(listOf("Predators", "Ohio State"), b.games.map { it.heading })
        assertEquals(listOf("vs Stars", "Thu 7:00 PM · TV: ESPN+"), b.games[0].lines)
        assertEquals(listOf("vs Stars", "Sat 11:00 AM · TV: not announced yet"), b.games[1].lines)
        assertFalse(b.games.any { it.warn })
    }

    @Test fun gamesUnknownOffOrNoneAreSaidNotHidden() {
        assertEquals(listOf(BoardCopy.SCHEDULE_UNKNOWN), board(inputs()).games[0].lines)
        assertTrue(board(inputs()).games[0].warn)
        assertEquals(listOf(BoardCopy.GAMES_OFF), board(inputs(switches = PanelSwitches(games = false))).games[1].lines)
        val none = board(inputs(feeds = FeedsSnapshot(games = Stamped(emptyList(), now))))
        assertEquals(listOf(BoardCopy.NO_GAME), none.games[0].lines)
        val old = board(inputs(feeds = FeedsSnapshot(games = Stamped(listOf(game(Teams.PREDATORS, "2026-11-06T01:00:00Z", listOf("ESPN+"))), now.minus(Duration.ofHours(13))))))
        assertTrue(old.games[0].warn)
        assertEquals("Updated 13 hours ago", old.games[0].lines.last())
    }

    private val weather = WeatherNow(55, "Partly cloudy", null, "rain after 9 PM", 63, 41,
        days = listOf(DayForecast(today, 63, 41, "Overcast", 10), DayForecast(today.plusDays(1), 58, 40, "Rain", 80)))

    @Test fun weatherTodayAndForecastUnderEachDay() {
        val b = board(inputs(feeds = FeedsSnapshot(weather = Stamped(weather, now))))
        assertEquals(listOf("Now 55°F Partly cloudy", "High 63° · Low 41° · Rain after 9 PM"), b.weather.lines)
        assertEquals(BoardForecast("63° / 41°", null), b.forecast[0])
        assertEquals(BoardForecast("58° / 40°", "Rain 80%"), b.forecast[1])
        assertNull(b.forecast[2], "no forecast for that day: nothing drawn, never invented")
        val raining = board(inputs(feeds = FeedsSnapshot(weather = Stamped(weather.copy(precipNow = Precip.RAIN, nextPrecip = null), now))))
        assertTrue(raining.weather.lines[1].endsWith("Rain now"))
    }

    @Test fun weatherUnknownStaleOffOrNoLocation() {
        val stale = board(inputs(feeds = FeedsSnapshot(weather = Stamped(weather, now.minus(Duration.ofHours(3))))))
        assertEquals(BoardCopy.WEATHER_UNKNOWN, stale.weather.lines[0]); assertTrue(stale.weather.warn)
        assertTrue(stale.forecast.all { it == null })
        assertEquals(listOf(BoardCopy.NO_LOCATION), board(inputs(hasLocation = false)).weather.lines)
        assertEquals(BoardCopy.WEATHER_OFF, board(inputs(switches = PanelSwitches(weather = false))).weather.lines[0])
    }

    @Test fun alertsOnTopAndUnknownSaidInText() {
        val set = AlertSet(listOf(
            NwsAlert("Tornado Warning", "Extreme", AlertKind.OVERRIDE, now.plus(Duration.ofMinutes(45))),
            NwsAlert("Flood Watch", "Moderate", AlertKind.SUFFIX, null),
        ))
        val b = board(inputs(feeds = FeedsSnapshot(alerts = Stamped(set, now), alertsChecked = true)))
        assertEquals(listOf(BoardAlert("Tornado Warning until 9:45 AM", true), BoardAlert("Flood Watch", false)), b.alerts)
        val unknown = board(inputs(feeds = FeedsSnapshot(weather = Stamped(weather, now), alerts = Stamped(set, now.minus(Duration.ofHours(1))), alertsChecked = true)))
        assertTrue(unknown.alerts.isEmpty())
        assertEquals(BoardCopy.ALERTS_UNKNOWN, unknown.weather.lines.last())
    }

    @Test fun calendarMessageOrAge() {
        val notSetUp = board(inputs(agenda = AgendaState.NotSetUp))
        assertEquals("Calendar not set up. Set it up from your PC: see the setup steps in the README.", notSetUp.calendarMessage)
        assertTrue(notSetUp.grid.columns.none { it.known })
        assertNull(board(inputs()).calendarAge)
        val stale = AgendaState.Stale(week[0], week[1], now.minus(Duration.ofHours(2)), 120, "Updated 2 hours ago", true, StaleCause.CANT_REACH, true, week)
        val age = board(inputs(agenda = stale)).calendarAge!!
        assertTrue(age.emphasised)
        assertEquals("Updated 2 hours ago · Can't reach calendar. Showing the last copy.", age.text)
    }
}
