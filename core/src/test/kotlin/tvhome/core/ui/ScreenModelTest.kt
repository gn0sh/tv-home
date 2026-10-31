package tvhome.core.ui

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import tvhome.core.agenda.AgendaDay
import tvhome.core.agenda.AgendaItem
import tvhome.core.agenda.TimeLabel
import tvhome.core.state.AgendaState
import tvhome.core.state.StaleCause
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class ScreenModelTest {
    private val zone = ZoneId.of("America/New_York")
    private val now = Instant.parse("2026-10-31T14:00:00Z") // 10:00 local
    private val sat = LocalDate.of(2026, 10, 31)
    private val sun = LocalDate.of(2026, 11, 1)

    private fun item(title: String, h: Int, m: Int) = AgendaItem(title, TimeLabel.Starts(LocalTime.of(h, m)))
    private val allDay = AgendaItem("Mum's birthday", TimeLabel.AllDay)
    private val today = AgendaDay(sat, listOf(allDay, item("Dentist", 9, 0), item("Lunch with Sam", 11, 30)))
    private val tomorrow = AgendaDay(sun, listOf(item("Run club", 10, 0)))
    private val empty = AgendaDay(sat, emptyList())
    private val emptyTomorrow = AgendaDay(sun, emptyList())

    private fun model(s: AgendaState, is24h: Boolean = false) = screenModel(s, now, zone, is24h)

    private fun stale(cause: StaleCause, emphasised: Boolean, minutes: Long = if (emphasised) 180 else 30, nothing: Boolean = false) =
        AgendaState.Stale(
            if (nothing) empty else today, if (nothing) emptyTomorrow else tomorrow, now.minusSeconds(minutes * 60),
            minutes, "Updated $minutes min ago", emphasised, cause, nothing,
        )

    // ---- time and date formats ----

    @Test fun twelveHourClock() {
        assertEquals("9:00 AM", formatTime(LocalTime.of(9, 0), false))
        assertEquals("11:30 AM", formatTime(LocalTime.of(11, 30), false))
        assertEquals("12:00 PM", formatTime(LocalTime.NOON, false))
        assertEquals("12:05 AM", formatTime(LocalTime.of(0, 5), false))
        assertEquals("1:45 PM", formatTime(LocalTime.of(13, 45), false))
        assertEquals("11:59 PM", formatTime(LocalTime.of(23, 59), false))
    }

    @Test fun twentyFourHourClock() {
        assertEquals("09:00", formatTime(LocalTime.of(9, 0), true))
        assertEquals("00:05", formatTime(LocalTime.of(0, 5), true))
        assertEquals("23:59", formatTime(LocalTime.of(23, 59), true))
    }

    @Test fun dates() {
        assertEquals("Sat 31 Oct", formatDate(sat))
        assertEquals("Sun 1 Nov", formatDate(sun))
        assertEquals("Saturday 31 October", formatDateLong(sat))
    }

    // ---- unavailable states: exact copy ----

    @Test fun messageStatesUseTheDesignCopy() {
        assertEquals(ScreenModel.Message("Calendar not set up", "Set it up from your PC: see the setup steps in the README."), model(AgendaState.NotSetUp))
        assertEquals(ScreenModel.Message("Can't reach calendar", "Check the TV's Wi-Fi. It will try again soon."), model(AgendaState.CantReach))
        assertEquals(ScreenModel.Message("Calendar data unreadable", "It will try again soon."), model(AgendaState.Unreadable))
        assertEquals(ScreenModel.Message("Loading calendar", null), model(AgendaState.Loading))
    }

    // ---- list states ----

    @Test fun okShowsRowsAndGreyAgeLineWithoutFooter() {
        val m = model(AgendaState.Ok(today, tomorrow, now, "Updated just now")) as ScreenModel.Agenda
        assertEquals("Today", m.today.heading)
        assertEquals("Sat 31 Oct", m.today.date)
        assertEquals("Today, Saturday 31 October", m.today.headingDescription)
        assertEquals("Tomorrow, Sunday 1 November", m.tomorrow.headingDescription)
        assertEquals(listOf("All day", "9:00 AM", "11:30 AM"), m.today.rows.map { it.time })
        assertEquals(listOf("Mum's birthday", "Dentist", "Lunch with Sam"), m.today.rows.map { it.title })
        assertEquals("All day, Mum's birthday", m.today.rows[0].description)
        assertEquals("9:00 AM, Dentist", m.today.rows[1].description)
        assertEquals(AgeLine("Updated just now", false, "Calendar up to date"), m.age)
        assertNull(m.footer)
    }

    @Test fun twentyFourHourSettingFormatsRows() {
        val m = model(AgendaState.Ok(today, tomorrow, now, "x"), is24h = true) as ScreenModel.Agenda
        assertEquals(listOf("All day", "09:00", "11:30"), m.today.rows.map { it.time })
    }

    @Test fun continuesLabelAndEmptyTitle() {
        val d = AgendaDay(sat, listOf(AgendaItem("   ", TimeLabel.Continues)))
        val m = model(AgendaState.Ok(d, emptyTomorrow, now, "x")) as ScreenModel.Agenda
        assertEquals(RowModel("Continues", "(No title)", "Continues, (No title)"), m.today.rows[0])
    }

    @Test fun nothingScheduledKeepsBothHeadingsWithNoRows() {
        val m = model(AgendaState.NothingScheduled(empty, emptyTomorrow, now, "Updated 4 min ago")) as ScreenModel.Agenda
        assertTrue(m.today.rows.isEmpty() && m.tomorrow.rows.isEmpty())
        assertEquals("Today", m.today.heading)
        assertEquals("Tomorrow", m.tomorrow.heading)
        assertNull(m.initialFocus)
        assertEquals("Updated 4 min ago", m.age.text)
    }

    // ---- stale, per cause ----

    @Test fun staleUnderAnHourIsSilentForEveryCause() {
        for (c in StaleCause.entries) {
            val m = model(stale(c, emphasised = false)) as ScreenModel.Agenda
            assertEquals(AgeLine("Updated 30 min ago", false, "Calendar up to date"), m.age, "$c")
            assertNull(m.footer, "$c")
        }
    }

    @Test fun emphasisedCantReachShowsAmberAgeLineAndFooter() {
        val m = model(stale(StaleCause.CANT_REACH, true)) as ScreenModel.Agenda
        assertEquals(AgeLine("Updated 180 min ago", true, "Calendar out of date"), m.age)
        assertEquals("Can't reach calendar. Showing the last copy.", m.footer)
    }

    @Test fun emphasisedUnreadableShowsItsFooter() {
        val m = model(stale(StaleCause.UNREADABLE, true)) as ScreenModel.Agenda
        assertTrue(m.age.emphasised)
        assertEquals("Calendar data unreadable. Showing the last copy.", m.footer)
    }

    /** OVERDUE also covers "old cache, fetch not finished yet": no false "Can't reach" claim, just the amber age. */
    @Test fun emphasisedOverdueHasAmberAgeLineButNoFooter() {
        val m = model(stale(StaleCause.OVERDUE, true)) as ScreenModel.Agenda
        assertTrue(m.age.emphasised)
        assertNull(m.footer)
    }

    @Test fun staleAndEmptyKeepsNothingScheduledBodyAndFooter() {
        val m = model(stale(StaleCause.CANT_REACH, true, nothing = true)) as ScreenModel.Agenda
        assertTrue(m.today.rows.isEmpty() && m.tomorrow.rows.isEmpty())
        assertEquals("Can't reach calendar. Showing the last copy.", m.footer)
    }

    @Test fun liveDescriptionOnlyChangesWithFreshness() {
        val a = (model(stale(StaleCause.CANT_REACH, true, minutes = 120)) as ScreenModel.Agenda).age
        val b = (model(stale(StaleCause.CANT_REACH, true, minutes = 121)) as ScreenModel.Agenda).age
        assertNotEquals(a.text, b.text)
        assertEquals(a.liveDescription, b.liveDescription)
    }

    // ---- initial focus ----

    @Test fun focusStartsOnTheNextEventToday() {
        // 10:00 local: Dentist (9:00) is past, Lunch (11:30) is next; flat index 2 (all-day, dentist, lunch).
        assertEquals(2, initialFocusIndex(today, tomorrow, LocalTime.of(10, 0)))
        assertEquals(1, initialFocusIndex(today, tomorrow, LocalTime.of(9, 0))) // starting right now counts
    }

    @Test fun focusFallsBackToTheFirstRow() {
        assertEquals(0, initialFocusIndex(today, tomorrow, LocalTime.of(23, 0)))
        assertEquals(0, initialFocusIndex(AgendaDay(sat, listOf(allDay)), tomorrow, LocalTime.of(8, 0)))
        assertEquals(0, initialFocusIndex(empty, tomorrow, LocalTime.of(8, 0)))
    }

    @Test fun noRowsMeansNoFocusTarget() {
        assertNull(initialFocusIndex(empty, emptyTomorrow, LocalTime.of(8, 0)))
    }

    @Test fun modelUsesTheDeviceZoneForNow() {
        val m = model(AgendaState.Ok(today, tomorrow, now, "x")) as ScreenModel.Agenda
        assertEquals(2, m.initialFocus)
    }

    // ---- privacy ----

    @Test fun toStringShowsCountsNotTitles() {
        val m = model(AgendaState.Ok(today, tomorrow, now, "x")) as ScreenModel.Agenda
        assertFalse(m.toString().contains("Dentist"))
        assertFalse(m.today.rows[1].toString().contains("Dentist"))
    }

    // ---- design tokens: contrast (design: every text pair 4.5:1, ring and chevrons 3:1) ----

    @Test fun textContrastMeetsWcagAa() {
        val bg = Tokens.BACKGROUND
        assertTrue(Tokens.contrast(Tokens.TEXT, bg) >= 7.0, "text on bg")
        assertTrue(Tokens.contrast(Tokens.SECONDARY, bg) >= 4.5, "secondary on bg")
        assertTrue(Tokens.contrast(Tokens.AMBER, bg) >= 4.5, "amber on bg")
        assertTrue(Tokens.contrast(Tokens.TEXT, Tokens.FOCUS_FILL) >= 4.5, "text on focus fill")
        assertTrue(Tokens.contrast(Tokens.FOCUS_RING, bg) >= 3.0, "ring on bg")
        assertTrue(Tokens.contrast(Tokens.FOCUS_RING, Tokens.FOCUS_FILL) >= 3.0, "ring on fill")
        assertTrue(Tokens.contrast(Tokens.SECONDARY, bg) >= 3.0, "chevrons on bg")
    }

    @Test fun noTextBelowTheFloor() {
        assertTrue(listOf(Tokens.HEADING_SP, Tokens.DAY_HEADING_SP, Tokens.ROW_SP, Tokens.SECONDARY_SP, Tokens.AGE_SP).all { it >= 22 })
    }

    @Test fun contrastMathMatchesKnownValues() {
        assertEquals(21.0, Tokens.contrast(0x000000, 0xFFFFFF), 0.01)
        assertEquals(1.0, Tokens.contrast(0x123456, 0x123456), 0.0001)
    }
}
