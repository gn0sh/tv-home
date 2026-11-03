package tvhome.core.calendar

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tvhome.core.agenda.agendaWindow
import tvhome.core.agenda.dedupeEvents
import tvhome.core.agenda.normalizeTitle
import tvhome.core.agenda.titlesMatch
import tvhome.core.state.AgendaState
import tvhome.core.state.CachedAgenda
import tvhome.core.state.reduce
import tvhome.core.ui.nowNext
import java.time.Instant
import java.time.ZoneId

/** calendar-dedupe: one appointment in the feed twice shows once; real different events at one time stay apart. */
class CalendarDedupeTest {
    private val zone = ZoneId.of("America/Chicago")
    private val now = Instant.parse("2026-11-03T18:35:00Z") // Tue 12:35 PM CST, 55 min before the 1:30 PM trim

    private fun fixture(name: String) = javaClass.getResource("/ics/$name")!!.readText()
    private fun state(name: String): AgendaState {
        val events = parseOutcome(fixture(name), agendaWindow(now, zone), zone).complete()
        return reduce(true, CachedAgenda(now, agendaWindow(now, zone), events), null, now, zone)
    }
    private fun today(name: String) = (state(name) as AgendaState.Ok).today.items.map { it.title }
    private fun hero(name: String) = nowNext(state(name), now, zone, emptyList(), quiet = false, is24h = false)!!

    @Test fun sameUidTwiceShowsOnce() {
        assertEquals(listOf("VIP Beard Trim", "School pickup"), today("duplicate-same-uid.ics"))
        val h = hero("duplicate-same-uid.ics")
        assertEquals("VIP Beard Trim in 55 min", h.line1)
        assertEquals("Then School pickup at 3:15 PM", h.line2)
    }

    @Test fun titleFormsOfOneBookingShowOnceWithTheFullerTitle() {
        assertEquals(listOf("Barber: VIP Beard Trim – Holiday Barber Shop", "School pickup"), today("duplicate-title-forms.ics"))
        val h = hero("duplicate-title-forms.ics")
        assertEquals("Barber: VIP Beard Trim – Holiday Barber Shop in 55 min", h.line1)
        assertEquals("Then School pickup at 3:15 PM", h.line2, "the subtitle never repeats the headline")
    }

    @Test fun differentEventsAtOneTimeStaySeparate() {
        // Dentist and Standup share a time; the two trims share a title but not their times.
        assertEquals(listOf("Dentist", "Standup", "VIP Beard Trim", "VIP Beard Trim"), today("same-time-different-events.ics").sorted())
        assertEquals("Then Standup at 1:30 PM", hero("same-time-different-events.ics").line2)
    }

    @Test fun titleMatching() {
        assertEquals("barber vip beard trim holiday barber shop", normalizeTitle("Barber: VIP  Beard Trim – Holiday Barber Shop!"))
        assertTrue(titlesMatch("VIP Beard-Trim", "vip beard trim", allDay = false))
        assertTrue(titlesMatch("Barber: VIP Beard Trim – Holiday Barber Shop", "VIP Beard Trim", allDay = false))
        assertFalse(titlesMatch("Pickup", "School pickup", allDay = false), "a one-word title never matches by containment")
        assertTrue(titlesMatch("Pickup", "pickup", allDay = false), "equal titles still do")
        assertFalse(titlesMatch("Gym class", "Gym classes today", allDay = false), "whole words only")
        assertFalse(titlesMatch("Spirit week", "Spirit week: pajama day", allDay = true), "all-day: equal titles only")
        assertTrue(titlesMatch("Election Day", "election day", allDay = true))
        assertFalse(titlesMatch("", "Gym", allDay = false)); assertFalse(titlesMatch("!!", "??", allDay = false))
        assertFalse(titlesMatch("Dentist", "Standup", allDay = false))
    }

    /** dedupe-allday-containment and dedupe-generic-timed, through the parser and the agenda. */
    @Test fun allDayAndGenericTitlesStayApart() {
        val items = (state("allday-and-generic-titles.ics") as AgendaState.Ok).today.items.map { it.title }.sorted()
        assertEquals(listOf("Birthday", "Election Day", "Jack's birthday", "No school", "Pickup", "School", "School pickup",
            "Spirit week: pajama day").sorted(), items, "only the equal Election Day pair and the shared Spirit week UID merge")
    }

    /** dedupe-order-dependent: the same events in any order give the same events (grouping is transitive). */
    @Test fun resultDoesNotDependOnFeedOrder() {
        val events = parseOutcome(fixture("duplicate-title-forms.ics"), agendaWindow(now, zone), zone).complete() +
            parseOutcome(fixture("allday-and-generic-titles.ics"), agendaWindow(now, zone), zone).complete() +
            // A chain: A~B (same uid), B~C (title), A!~C. Every order must collapse all three.
            listOf(
                EventInstance("x", "Trim", EventTime.At(now), EventTime.At(now.plusSeconds(600))),
                EventInstance("x", "Beard trim", EventTime.At(now), EventTime.At(now.plusSeconds(600))),
                EventInstance("y", "VIP beard trim", EventTime.At(now), EventTime.At(now.plusSeconds(600))),
            )
        val expected = dedupeEvents(events).toSet()
        val rnd = java.util.Random(7)
        repeat(50) { assertEquals(expected, dedupeEvents(events.shuffled(rnd)).toSet()) }
        assertEquals(1, expected.count { it.start == EventTime.At(now) })
        assertTrue(expected.any { it.uid == "y" && it.title == "VIP beard trim" }, "the kept event's uid and title are one member's")
    }

    @Test fun neverMergesDifferentTimes() {
        val a = EventInstance("u", "Trim", EventTime.At(now), EventTime.At(now.plusSeconds(3600)))
        val b = EventInstance("u", "Trim", EventTime.At(now), EventTime.At(now.plusSeconds(1800)))
        val c = EventInstance("u", "Trim", EventTime.At(now.plusSeconds(60)), EventTime.At(now.plusSeconds(3600)))
        assertEquals(3, dedupeEvents(listOf(a, b, c)).size)
        assertEquals(1, dedupeEvents(listOf(a, a.copy(title = "Other"))).size, "same UID and times: one event")
    }

    /** The hero's own guard, for days built without the merge step: "Then" never names the headline's event. */
    @Test fun thenNeverRepeatsTheHeadline() {
        val start = Instant.parse("2026-11-03T19:30:00Z"); val end = Instant.parse("2026-11-03T20:15:00Z")
        fun item(t: String, s: Instant = start, e: Instant = end) =
            tvhome.core.agenda.AgendaItem(t, tvhome.core.agenda.TimeLabel.Starts(s.atZone(zone).toLocalTime()), s, e)
        val day = tvhome.core.agenda.AgendaDay(now.atZone(zone).toLocalDate(), listOf(
            item("Barber: VIP Beard Trim – Holiday Barber Shop"), item("VIP Beard Trim"),
            item("School pickup", Instant.parse("2026-11-03T21:15:00Z"), Instant.parse("2026-11-03T21:45:00Z")),
        ))
        val tomorrow = tvhome.core.agenda.AgendaDay(day.date.plusDays(1), emptyList())
        val h = nowNext(AgendaState.Ok(day, tomorrow, now, "Updated just now"), now, zone, emptyList(), quiet = false, is24h = false)!!
        assertEquals("Then School pickup at 3:15 PM", h.line2)
    }
}
