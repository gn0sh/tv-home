package tvhome.core.ui

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import tvhome.core.agenda.AgendaDay
import tvhome.core.agenda.AgendaItem
import tvhome.core.agenda.TimeLabel
import tvhome.core.feeds.AlertKind
import tvhome.core.feeds.AlertSet
import tvhome.core.feeds.FeedsSnapshot
import tvhome.core.feeds.GameInfo
import tvhome.core.feeds.GamePhase
import tvhome.core.feeds.NwsAlert
import tvhome.core.feeds.PanelSwitches
import tvhome.core.feeds.Stamped
import tvhome.core.feeds.TeamGame
import tvhome.core.feeds.Teams
import tvhome.core.feeds.WeatherNow
import tvhome.core.state.AgendaState
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class HomeGlanceTest {
    private val zone = ZoneId.of("America/Chicago")
    private val now = Instant.parse("2026-11-02T22:50:00Z") // Mon 4:50 PM CST
    private val mon = LocalDate.of(2026, 11, 2)
    private fun at(h: Int, m: Int) = mon.atTime(h, m).atZone(zone).toInstant()
    private fun item(t: String, h: Int, m: Int) = AgendaItem(t, TimeLabel.Starts(LocalTime.of(h, m)), at(h, m), at(h, m).plusSeconds(1800))

    private val weather = Stamped(WeatherNow(72, "Cloudy", null, "rain after 6 PM"), now)
    private val tornado = NwsAlert("Tornado Warning", "Extreme", AlertKind.OVERRIDE, now.plus(Duration.ofMinutes(55)))
    private val flood = NwsAlert("Flood Watch", "Severe", AlertKind.SUFFIX, now.plus(Duration.ofHours(8)))
    private val game = TeamGame(Teams.PREDATORS, GameInfo(at(19, 0), "Blues", true, GamePhase.UPCOMING, null, null, null))

    private fun agenda(vararg items: AgendaItem): AgendaState =
        AgendaState.Ok(AgendaDay(mon, items.toList()), AgendaDay(mon.plusDays(1), emptyList()), now, "Updated just now")
    private val noCalendar: AgendaState = AgendaState.NotSetUp

    private fun glance(
        feeds: FeedsSnapshot = FeedsSnapshot(),
        agenda: AgendaState = noCalendar,
        brief: String? = null,
        switches: PanelSwitches = PanelSwitches(),
        hasLocation: Boolean = true,
        quiet: Boolean = false,
        at: Instant = now,
    ) = homeGlance(GlanceInputs(at, zone, false, feeds, switches, hasLocation, agenda, emptyList(), brief, quiet))

    @Test fun every64PanelSubsetFitsTheBudgetAndKeepsTheClock() {
        for (mask in 0 until 64) {
            fun on(b: Int) = mask and (1 shl b) != 0
            val alerts = if (on(1)) Stamped(AlertSet(listOf(tornado, flood)), now) else null
            val feeds = FeedsSnapshot(
                weather = if (on(0)) weather else null,
                alerts = alerts,
                games = if (on(2)) Stamped(listOf(game), now) else null,
                alertsChecked = alerts != null,
            )
            val events = listOfNotNull(if (on(3)) item("Dentist", 17, 30) else null, if (on(4)) item("Film", 20, 0) else null)
            val g = glance(feeds, if (events.isEmpty()) noCalendar else agenda(*events.toTypedArray()), if (on(5)) "Rain after 6 · Preds 7" else null)
            assertTrue(g.items.size <= GlanceBudget.MAX, "mask $mask: ${g.items}")
            assertTrue(GlanceItem.CLOCK in g.items, "mask $mask")
            assertEquals("4:50 PM", g.clock)
            assertEquals(g.chip != null, GlanceItem.CHIP in g.items, "mask $mask")
            assertEquals(g.card != null, GlanceItem.ALERT_CARD in g.items || GlanceItem.CARD in g.items, "mask $mask")
            assertEquals(g.nowNext?.line2 != null, GlanceItem.NOW_2 in g.items, "mask $mask")
            assertEquals(g.brief != null, GlanceItem.BRIEF in g.items, "mask $mask")
            if (on(1)) assertTrue(g.card is ContextCard.Alert, "alert beats the game, mask $mask")
        }
    }

    @Test fun aSynthetic7thDisplacesTheLowestAndAnAlertDisplacesNothingAboveNowLine1() {
        val all = GlanceItem.entries.toSet()
        assertEquals(7, all.size)
        val kept = GlanceBudget.fit(all)
        assertEquals(6, kept.size)
        assertFalse(GlanceItem.BRIEF in kept)
        val noAlert = all - GlanceItem.ALERT_CARD
        assertEquals(noAlert, GlanceBudget.fit(noAlert))
        // Adding the alert to a full set drops only the brief: clock and Now line 1 stay.
        assertTrue(GlanceBudget.fit(all).containsAll(listOf(GlanceItem.CLOCK, GlanceItem.ALERT_CARD, GlanceItem.NOW_1, GlanceItem.CHIP)))
        assertEquals(setOf(GlanceItem.CLOCK), GlanceBudget.fit(emptySet()), "clock always kept")
    }

    @Test fun goalLineExample() {
        val g = glance(FeedsSnapshot(weather = weather, games = Stamped(listOf(game), now)), agenda(item("Dentist", 17, 30), item("Film", 20, 0)))
        assertEquals("72°F Cloudy · rain after 6 PM", g.chip)
        assertEquals("Dentist in 40 min", g.nowNext!!.line1)
        assertEquals("Then Film at 8:00 PM", g.nowNext!!.line2)
        val card = g.card as ContextCard.Game
        assertEquals("predators", card.teamKey)
        assertTrue(card.line.startsWith("Predators vs Blues · 7:00\u00A0PM"), card.line)
        assertEquals("Mon 2 Nov", g.date)
    }

    @Test fun overrideAlertTakesTheCardAndWatchesGoToTheChip() {
        val g = glance(FeedsSnapshot(weather = weather, alerts = Stamped(AlertSet(listOf(tornado, flood)), now), alertsChecked = true,
            games = Stamped(listOf(game), now)))
        assertEquals(ContextCard.Alert("Tornado Warning", "until 5:45 PM"), g.card)
        assertEquals("Tornado Warning until 5:45 PM", (g.card as ContextCard.Alert).text)
        assertEquals("72°F Cloudy · rain after 6 PM · Flood Watch", g.chip)
        assertEquals(GlanceItem.ALERT_CARD in g.items, true)
    }

    @Test fun anAlertThatHasEndedDisappearsWithoutAFetch() {
        // Last good fetch 10 min before the alert's end; nothing newer since.
        val f = FeedsSnapshot(alerts = Stamped(AlertSet(listOf(tornado)), now.plus(Duration.ofMinutes(45))), alertsChecked = true)
        assertTrue(glance(f, at = now.plus(Duration.ofMinutes(54))).card is ContextCard.Alert)
        assertNull(glance(f, at = now.plus(Duration.ofMinutes(55))).card)
        assertNull(glance(f, at = now.plus(Duration.ofMinutes(55))).chip, "checked, nothing active: no suffix")
    }

    @Test fun alertsUnknownAfter30MinutesOrAFailedFirstCheck() {
        val f = FeedsSnapshot(weather = Stamped(weather.value, now.plus(Duration.ofMinutes(29))), alerts = Stamped(AlertSet(listOf(flood)), now), alertsChecked = true)
        assertEquals("72°F Cloudy · rain after 6 PM · Flood Watch", glance(f, at = now.plus(Duration.ofMinutes(29))).chip)
        assertEquals("72°F Cloudy · rain after 6 PM · alerts unknown", glance(f, at = now.plus(Duration.ofMinutes(30))).chip)
        assertEquals("alerts unknown", glance(FeedsSnapshot(alertsChecked = true)).chip, "no weather: suffix alone")
        assertNull(glance(FeedsSnapshot(alertsChecked = false)).chip, "before the first attempt: nothing")
        assertNull(glance(FeedsSnapshot(alertsChecked = true), hasLocation = false).chip, "no location: alerts are off")
        assertNull(glance(FeedsSnapshot(alertsChecked = true), switches = PanelSwitches(alerts = false)).chip, "switched off")
    }

    @Test fun switchesHideTheirPanels() {
        val f = FeedsSnapshot(weather = weather, games = Stamped(listOf(game), now))
        assertNull(glance(f, switches = PanelSwitches(weather = false, alerts = false)).chip)
        assertNull(glance(f, switches = PanelSwitches(games = false)).card)
    }

    @Test fun staleWeatherIsHidden() {
        val f = FeedsSnapshot(weather = Stamped(weather.value, now.minus(Duration.ofHours(2))))
        assertNull(glance(f, switches = PanelSwitches(alerts = false)).chip)
    }

    @Test fun quietHidesTitlesInEveryString() {
        val g = glance(FeedsSnapshot(weather = weather, games = Stamped(listOf(game), now)),
            agenda(item("Secret dentist", 17, 30), item("Surprise party", 20, 0)), quiet = true)
        val strings = listOfNotNull(g.clock, g.date, g.chip, g.nowNext?.line1, g.nowNext?.line2, g.nowNext?.ageText,
            (g.card as? ContextCard.Game)?.line, g.brief)
        for (s in strings) for (t in listOf("Secret", "dentist", "Surprise", "party")) assertFalse(s.contains(t, true), "$t in $s")
        assertTrue(g.quiet)
        assertFalse(g.toString().contains("Secret"))
    }

    @Test fun zonesAreDisjointAndInsideTheSafeMargins() {
        val z = HeroZones.ALL
        for (a in z) {
            assertTrue(a.x >= Tokens.MARGIN_H_DP && a.right <= HeroZones.SCREEN_W - Tokens.MARGIN_H_DP, "$a")
            assertTrue(a.y >= Tokens.MARGIN_V_DP && a.bottom <= HeroZones.SCREEN_H - Tokens.MARGIN_V_DP, "$a")
            assertTrue(a.w > 0 && a.h > 0)
        }
        for (i in z.indices) for (j in z.indices) if (i != j) assertFalse(z[i].overlaps(z[j]), "${z[i]} ${z[j]}")
        assertTrue(HeroZones.Zone(0, 0, 10, 10).overlaps(HeroZones.Zone(5, 5, 10, 10)))
    }

    @Test fun untilOnAnotherDayNamesTheDay() {
        assertEquals("until Tue 7:00 AM", untilText(mon.plusDays(1).atTime(7, 0).atZone(zone).toInstant(), now, zone, false))
    }
}
