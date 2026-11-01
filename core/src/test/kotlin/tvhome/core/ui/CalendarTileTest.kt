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

class CalendarTileTest {
    private val zone = ZoneId.of("America/New_York")
    private val now = Instant.parse("2026-10-31T14:00:00Z") // Sat 10:00 local
    private val sat = LocalDate.of(2026, 10, 31)
    private val sun = LocalDate.of(2026, 11, 1)

    private fun item(title: String, h: Int, m: Int) = AgendaItem(title, TimeLabel.Starts(LocalTime.of(h, m)))
    private val allDay = AgendaItem("Mum's birthday", TimeLabel.AllDay)
    private val continues = AgendaItem("Conference", TimeLabel.Continues)
    private val today = AgendaDay(sat, listOf(allDay, continues, item("Dentist", 9, 0), item("Lunch with Sam", 11, 30), item("Film", 19, 0)))
    private val tomorrow = AgendaDay(sun, listOf(item("Run club", 8, 0)))
    private val emptyToday = AgendaDay(sat, emptyList())
    private val emptyTomorrow = AgendaDay(sun, emptyList())

    private fun ok(t: AgendaDay = today, m: AgendaDay = tomorrow) = AgendaState.Ok(t, m, now, "Updated just now")
    private fun tile(s: AgendaState, at: Instant = now, is24h: Boolean = false) = calendarTile(s, at, zone, is24h)

    @Test fun nextEventIsTheFirstTimedItemAtOrAfterNow() {
        val t = tile(ok())
        assertEquals("Sat 31 Oct", t.date)
        assertEquals("11:30 AM", t.time)
        assertEquals("Lunch with Sam", t.text)
        assertFalse(t.warn)
        assertNull(t.note)
        assertEquals("Calendar, Saturday 31 October. Next: 11:30 AM, Lunch with Sam.", t.description)
    }

    @Test fun anEventStartingThisMinuteIsStillNext() {
        val at = Instant.parse("2026-10-31T15:30:45Z") // 11:30:45 local
        assertEquals("Lunch with Sam", tile(ok(), at).text)
        assertEquals("Film", tile(ok(), Instant.parse("2026-10-31T15:31:00Z")).text)
    }

    @Test fun allDayAndContinuingItemsAreNeverNext() {
        val t = tile(ok(AgendaDay(sat, listOf(allDay, continues))))
        assertNull(t.time)
        assertEquals("No more events today", t.text)
        assertEquals("Calendar, Saturday 31 October. No more events today.", t.description)
    }

    @Test fun afterTheLastEventTomorrowIsNotShownAsNext() {
        val t = tile(ok(), Instant.parse("2026-10-31T23:30:00Z")) // 19:30 local
        assertNull(t.time)
        assertEquals(NO_MORE_TODAY, t.text)
    }

    @Test fun twentyFourHourClock() {
        assertEquals("11:30", tile(ok(), is24h = true).time)
    }

    @Test fun blankTitleUsesNoTitle() {
        assertEquals("(No title)", tile(ok(AgendaDay(sat, listOf(item("  ", 12, 0))))).text)
    }

    @Test fun nothingScheduled() {
        val t = tile(AgendaState.NothingScheduled(emptyToday, emptyTomorrow, now, "Updated just now"))
        assertEquals(NO_MORE_TODAY, t.text)
        assertNull(t.time)
    }

    @Test fun afterMidnightTheCachedTomorrowIsToday() {
        val sunMorning = Instant.parse("2026-11-01T04:30:00Z") // Sun 00:30 local, cache still says today = Sat
        val t = tile(ok(), sunMorning)
        assertEquals("Sun 1 Nov", t.date)
        assertEquals("8:00 AM", t.time)
        assertEquals("Run club", t.text)
    }

    @Test fun dayThatIsNotTodayHasNoNextEvent() {
        assertNull(nextEvent(tomorrow, now, zone))
    }

    @Test fun messageStatesUseScreenModelTitles() {
        assertEquals(("Calendar not set up" to false), tile(AgendaState.NotSetUp).let { it.text to it.warn })
        assertEquals(("Loading calendar" to false), tile(AgendaState.Loading).let { it.text to it.warn })
        assertEquals(("Can't reach calendar" to true), tile(AgendaState.CantReach).let { it.text to it.warn })
        assertEquals(("Calendar data unreadable" to true), tile(AgendaState.Unreadable).let { it.text to it.warn })
        val t = tile(AgendaState.NotSetUp)
        assertNull(t.time)
        assertEquals("Sat 31 Oct", t.date)
        assertEquals("Calendar, Saturday 31 October. Calendar not set up.", t.description)
        // The tile's words match the full-screen message title for the same state.
        for (s in listOf(AgendaState.NotSetUp, AgendaState.Loading, AgendaState.CantReach, AgendaState.Unreadable)) {
            assertEquals((screenModel(s, now, zone, false) as ScreenModel.Message).title, tile(s).text)
        }
    }

    @Test fun staleShowsEventsAndOnlyAnEmphasisedCopyAddsTheAgeNote() {
        fun stale(emphasised: Boolean) = AgendaState.Stale(
            today, tomorrow, now.minusSeconds(if (emphasised) 10_800 else 1_800), if (emphasised) 180 else 30,
            if (emphasised) "Updated 3 hours ago" else "Updated 30 min ago", emphasised, StaleCause.CANT_REACH, false,
        )
        val quiet = tile(stale(false))
        assertEquals("Lunch with Sam", quiet.text)
        assertNull(quiet.note)
        val loud = tile(stale(true))
        assertEquals("Lunch with Sam", loud.text)
        assertEquals("Updated 3 hours ago", loud.note)
        assertFalse(loud.warn)
        assertEquals("Calendar, Saturday 31 October. Next: 11:30 AM, Lunch with Sam. Updated 3 hours ago.", loud.description)
    }

    @Test fun toStringHasNoTitle() {
        val s = tile(ok()).toString()
        assertFalse("Lunch" in s, s)
    }
}
