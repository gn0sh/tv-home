package tvhome.core.ui

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import tvhome.core.agenda.AgendaDay
import tvhome.core.agenda.AgendaItem
import tvhome.core.agenda.TimeLabel
import tvhome.core.feeds.AlertKind
import tvhome.core.feeds.AlertSet
import tvhome.core.feeds.GameDay
import tvhome.core.feeds.GameInfo
import tvhome.core.feeds.GamePhase
import tvhome.core.feeds.NwsAlert
import tvhome.core.feeds.TeamGame
import tvhome.core.feeds.Teams
import tvhome.core.feeds.WatchMap
import tvhome.core.feeds.WeatherNow
import tvhome.core.state.AgendaState
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** M7-B9: the card priority (alert > live > upcoming today > time of day) and the time-of-day card. */
class ContextCardTest {
    private val zone = ZoneId.of("America/Chicago")
    private val mon = LocalDate.of(2026, 11, 2)
    private fun local(h: Int, m: Int = 0, day: LocalDate = mon) = day.atTime(h, m).atZone(zone).toInstant()
    private val now = local(16, 50)

    private fun gameAt(phase: GamePhase, start: Instant = local(19), nets: List<String> = listOf("ESPN+")) =
        TeamGame(Teams.PREDATORS, GameInfo(start, "Blues", true, phase, if (phase == GamePhase.UPCOMING) null else 2, if (phase == GamePhase.UPCOMING) null else 1, null, nets))
    private val alerts = AlertSet(listOf(NwsAlert("Tornado Warning", "Extreme", AlertKind.OVERRIDE, now.plus(Duration.ofMinutes(55)))))
    private val tod = ContextCard.TimeOfDay("Today", "High 63°")
    private val map = WatchMap.parse("ESPN+=com.espn.gtv", null)

    private fun card(alert: Boolean, games: List<TeamGame>, timeOfDay: ContextCard.TimeOfDay? = tod, at: Instant = now) =
        contextCard(if (alert) alerts else null, GameDay.select(games, at, zone), at, zone, false, timeOfDay, map)

    @Test fun priorityTable() {
        val live = gameAt(GamePhase.LIVE, local(16, 0))
        val upcoming = gameAt(GamePhase.UPCOMING)
        val final = gameAt(GamePhase.FINAL, local(10, 0))
        // alert, live, upcoming, final, timeOfDay -> expected
        class Row(val alert: Boolean, val games: List<TeamGame>, val tod: Boolean, val expect: String)
        val rows = listOf(
            Row(true, listOf(live, upcoming), true, "Alert"),
            Row(true, emptyList(), true, "Alert"),
            Row(false, listOf(live, upcoming), true, "Game:LIVE"),
            Row(false, listOf(upcoming, live), true, "Game:LIVE"),
            Row(false, listOf(upcoming), true, "Game:UPCOMING"),
            Row(false, listOf(upcoming, final), true, "Game:UPCOMING"),
            Row(false, listOf(final), true, "Game:FINAL"),
            Row(false, emptyList(), true, "TimeOfDay"),
            Row(false, emptyList(), false, "none"),
            Row(false, listOf(gameAt(GamePhase.UPCOMING, local(19).plus(Duration.ofDays(1)))), true, "TimeOfDay"), // tomorrow's game is not game day
        )
        for ((i, r) in rows.withIndex()) {
            val c = card(r.alert, r.games, if (r.tod) tod else null)
            val got = when (c) {
                null -> "none"
                is ContextCard.Alert -> "Alert"
                is ContextCard.Game -> "Game:${c.phase}"
                is ContextCard.TimeOfDay -> "TimeOfDay"
            }
            assertEquals(r.expect, got, "row $i")
        }
    }

    @Test fun gameLineCarriesTheMappedNetworkUnlessTheGameIsOver() {
        val up = card(false, listOf(gameAt(GamePhase.UPCOMING))) as ContextCard.Game
        assertEquals("ESPN+", up.network)
        assertTrue(up.line.endsWith(" · ESPN+"), up.line)
        val unmapped = card(false, listOf(gameAt(GamePhase.UPCOMING, nets = listOf("SCRIPPS")))) as ContextCard.Game
        assertNull(unmapped.network); assertFalse(unmapped.line.contains("SCRIPPS"), "an unmapped name is not drawn")
        val final = card(false, listOf(gameAt(GamePhase.FINAL, local(10)))) as ContextCard.Game
        assertNull(final.network)
    }

    // --- time of day -----------------------------------------------------------------------------------------------

    private val weather = WeatherNow(55, "Overcast", null, "rain after 3 PM", highF = 63, lowF = 48, highHour = 15, overnightLowF = 41)
    private fun item(title: String, h: Int, m: Int, day: LocalDate) =
        AgendaItem(title, TimeLabel.Starts(LocalTime.of(h, m)), local(h, m, day), local(h, m, day).plusSeconds(1800))
    private fun agenda(at: Instant, vararg tomorrow: AgendaItem): AgendaState =
        AgendaState.Ok(AgendaDay(mon, emptyList()), AgendaDay(mon.plusDays(1), tomorrow.toList()), at, "Updated just now")

    @Test fun morningShowsTheWeatherArc() {
        val c = timeOfDayCard(weather, AgendaState.NotSetUp, local(7, 30), zone, false, false)!!
        assertEquals("Today", c.heading)
        assertEquals("High 63° around 3:00 PM", c.text)
        assertEquals("Low 48° · rain after 3 PM", c.detail)
        assertEquals("High 63° around 15:00", timeOfDayCard(weather, AgendaState.NotSetUp, local(7, 30), zone, false, true)!!.text)
        assertNull(timeOfDayCard(weather.copy(highF = null), AgendaState.NotSetUp, local(7, 30), zone, false, false), "an old cache without the new fields shows nothing")
        assertNull(timeOfDayCard(null, AgendaState.NotSetUp, local(7, 30), zone, false, false))
    }

    @Test fun eveningShowsTomorrowsFirstEventAndTheOvernightLow() {
        val at = local(19, 30)
        val a = agenda(at, item("Dentist", 9, 0, mon.plusDays(1)), item("Lunch", 12, 0, mon.plusDays(1)))
        val c = timeOfDayCard(weather, a, at, zone, false, false)!!
        assertEquals("Tomorrow", c.heading)
        assertEquals("Tomorrow: Dentist at 9:00 AM", c.text)
        assertEquals("Low tonight 41°", c.detail)
    }

    @Test fun eveningWithNothingTomorrowOrNoCalendarOrNoLow() {
        val at = local(19, 30)
        assertEquals("Nothing on tomorrow", timeOfDayCard(weather, agenda(at), at, zone, false, false)!!.text)
        // Calendar 3 h old: not trusted, so only the low.
        val old = agenda(at.minus(Duration.ofHours(3)), item("Dentist", 9, 0, mon.plusDays(1)))
        val c = timeOfDayCard(weather, old, at, zone, false, false)!!
        assertEquals("Low tonight 41°", c.text); assertNull(c.detail)
        assertNull(timeOfDayCard(weather.copy(overnightLowF = null), AgendaState.CantReach, at, zone, false, false))
        assertEquals("Tomorrow: Dentist at 9:00 AM", timeOfDayCard(null, agenda(at, item("Dentist", 9, 0, mon.plusDays(1))), at, zone, false, false)!!.text)
    }

    @Test fun quietHidesTheEventTitleButKeepsTheTimeAndTheLow() {
        val at = local(19, 30)
        val a = agenda(at, item("Therapy with Dr Rao", 9, 0, mon.plusDays(1)))
        val c = timeOfDayCard(weather, a, at, zone, true, false)!!
        assertEquals("Tomorrow: Busy at 9:00 AM", c.text)
        assertEquals("Low tonight 41°", c.detail)
        assertFalse(c.toString().contains("Therapy"))
    }

    @Test fun middayAndSmallHoursShowNothing() {
        for (h in listOf(0, 3, 4, 12, 14, 16)) assertNull(timeOfDayCard(weather, AgendaState.NotSetUp, local(h, 10), zone, false, false), "hour $h")
        for (h in listOf(5, 11)) assertNotNull(timeOfDayCard(weather, AgendaState.NotSetUp, local(h, 10), zone, false, false), "hour $h")
        for (h in listOf(17, 23)) assertNotNull(timeOfDayCard(weather, AgendaState.NotSetUp, local(h, 10), zone, false, false), "hour $h")
    }
}
