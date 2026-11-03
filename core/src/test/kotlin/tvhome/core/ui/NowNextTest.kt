package tvhome.core.ui

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import tvhome.core.agenda.AgendaDay
import tvhome.core.agenda.AgendaItem
import tvhome.core.agenda.TimeLabel
import tvhome.core.state.AgendaState
import tvhome.core.state.StaleCause
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class NowNextTest {
    private val zone = ZoneId.of("America/Chicago")
    private val mon = LocalDate.of(2026, 11, 2)
    private val tue = mon.plusDays(1)
    private fun at(d: LocalDate, h: Int, m: Int): Instant = ZonedDateTime.of(d, java.time.LocalTime.of(h, m), zone).toInstant()
    private fun timed(title: String, d: LocalDate, h: Int, m: Int, endH: Int? = null, endM: Int = 0) =
        AgendaItem(title, TimeLabel.Starts(java.time.LocalTime.of(h, m)), at(d, h, m), endH?.let { at(d, it, endM) })
    private fun allDay(title: String) = AgendaItem(title, TimeLabel.AllDay)

    private val dentist = timed("Dentist", mon, 17, 30, 18)
    private val film = timed("Film night", mon, 20, 0, 22)
    private val run = timed("Run club", tue, 8, 0, 9)

    private fun ok(today: List<AgendaItem>, tomorrow: List<AgendaItem> = listOf(run), updated: Instant = at(mon, 16, 0)) =
        AgendaState.Ok(AgendaDay(mon, today), AgendaDay(tue, tomorrow), updated, "Updated just now")

    private fun nn(s: AgendaState, now: Instant, travel: List<TravelRule> = emptyList(), quiet: Boolean = false, is24h: Boolean = false) =
        nowNext(s, now, zone, travel, quiet, is24h)

    private val start = at(mon, 17, 30)
    private fun before(min: Long) = start.minus(Duration.ofMinutes(min))

    @Test fun countdownAt61_60_1_0MinutesAndAfterStart() {
        val s = ok(listOf(dentist, film), updated = before(70))
        assertEquals("Dentist at 5:30 PM", nn(s, before(61))!!.line1)
        assertEquals("Dentist in 60 min", nn(s, before(60))!!.line1)
        assertEquals("Dentist in 1 min", nn(s, before(1))!!.line1)
        assertEquals("Dentist starting now", nn(s, start)!!.line1)
        assertEquals("Dentist starting now", nn(s, start.plusSeconds(59))!!.line1, "next until a minute after start")
        assertEquals("Dentist now, until 6:00 PM", nn(s, start.plusSeconds(60))!!.line1)
        assertEquals("Then Film night at 8:00 PM", nn(s, before(60))!!.line2)
        assertEquals("Film night at 8:00 PM", nn(s, at(mon, 18, 0))!!.line1, "after the end")
    }

    @Test fun leaveByOnlyWithAMatchingRuleAndOnlyWithinTheWindow() {
        val rules = listOf(TravelRule.of("dentist", 20)!!)
        val s = ok(listOf(dentist), updated = before(70))
        assertEquals("Dentist at 5:30 PM", nn(s, before(61), rules)!!.line1)
        assertEquals("Dentist · Leave by 5:10 PM", nn(s, before(60), rules)!!.line1)
        assertEquals("Dentist · Leave by 5:10 PM", nn(s, before(21), rules)!!.line1)
        assertEquals("Dentist · Leave now", nn(s, before(20), rules)!!.line1)
        assertEquals("Dentist · Leave now", nn(s, before(1), rules)!!.line1)
        assertEquals("Dentist · Leave now", nn(s, start, rules)!!.line1)
        assertEquals("Dentist now, until 6:00 PM", nn(s, start.plusSeconds(60), rules)!!.line1)
        // No rule, no "Leave by"; a rule for another keyword does nothing.
        assertEquals("Dentist in 42 min", nn(s, before(42), listOf(TravelRule.of("gym", 15)!!))!!.line1)
        assertEquals("Dentist in 42 min", nn(s, before(42))!!.line1)
    }

    @Test fun inProgressWithASoonEventPutsTheSoonOneFirst() {
        val work = timed("Workshop", mon, 13, 0, 19)
        val s = ok(listOf(work, dentist), updated = at(mon, 14, 0).plusSeconds(1800))
        val r = nn(s, before(30))!!
        assertEquals("Dentist in 30 min", r.line1)
        assertEquals("Workshop now, until 7:00 PM", r.line2)
        val far = nn(s, at(mon, 14, 30))!!
        assertEquals("Workshop now, until 7:00 PM", far.line1)
        assertEquals("Then Dentist at 5:30 PM", far.line2)
    }

    @Test fun allDayNeverCountsDown() {
        val s = ok(listOf(allDay("Holiday")), updated = at(mon, 9, 0))
        val r = nn(s, at(mon, 9, 0))!!
        assertEquals("Today: Holiday", r.line1)
        assertEquals("Tomorrow: Run club at 8:00 AM", r.line2)
        val withTimed = nn(ok(listOf(allDay("Holiday"), dentist), updated = before(30)), before(30))!!
        assertEquals("Dentist in 30 min", withTimed.line1)
        assertEquals("Today: Holiday", withTimed.line2)
    }

    @Test fun nothingLeftShowsTomorrowsFirstOrNothingOnToday() {
        val late = at(mon, 23, 0)
        assertEquals(NowNext("Tomorrow: Run club at 8:00 AM", null, null), nn(ok(listOf(dentist), updated = late), late))
        assertEquals(NowNext("Tomorrow: Fair", null, null), nn(ok(emptyList(), listOf(allDay("Fair")), updated = late), late))
        val empty = AgendaState.NothingScheduled(AgendaDay(mon, emptyList()), AgendaDay(tue, emptyList()), late, "Updated just now")
        assertEquals(NowNext(NowNextCopy.NOTHING_TODAY, null, null), nn(empty, late))
    }

    @Test fun ageLineFrom15MinAndHiddenAt3Hours() {
        val now = at(mon, 16, 0)
        fun stale(min: Long) = AgendaState.Stale(AgendaDay(mon, listOf(dentist)), AgendaDay(tue, listOf(run)),
            now.minus(Duration.ofMinutes(min)), min, "x", min >= 60, StaleCause.CANT_REACH, false)
        assertNull(nn(stale(14), now)!!.ageText)
        assertEquals("Updated 15 min ago", nn(stale(15), now)!!.ageText)
        assertEquals("Dentist at 5:30 PM", nn(stale(179), now)!!.line1)
        val hidden = nn(stale(180), now)!!
        assertEquals(NowNext("Calendar not updated since 1:00 PM", null, null), hidden)
        val s = AgendaState.Stale(AgendaDay(mon, listOf(dentist)), AgendaDay(tue, listOf(run)), at(mon, 16, 0).minus(Duration.ofDays(1)),
            1440, "x", true, StaleCause.CANT_REACH, false)
        assertEquals("Calendar not updated since Sun 1 Nov", nn(s, now)!!.line1)
    }

    @Test fun unavailableStatesAreHidden() {
        for (s in listOf(AgendaState.NotSetUp, AgendaState.Loading, AgendaState.CantReach, AgendaState.Unreadable)) {
            assertNull(nn(s, at(mon, 9, 0)), s.toString())
        }
    }

    @Test fun quietHidesEveryTitleButKeepsTimesAndRules() {
        fun s(now: Instant) = ok(listOf(allDay("Secret party"), timed("Dentist Dr Who", mon, 17, 30, 18), film), listOf(run), updated = now)
        val all = listOf(before(42), at(mon, 21, 0), at(mon, 23, 0)).map {
            nn(s(it), it, listOf(TravelRule.of("dentist", 20)!!), quiet = true)!!
        }
        val text = all.flatMap { listOfNotNull(it.line1, it.line2, it.ageText) }
        for (title in listOf("Secret", "party", "Dentist", "Film", "Run club", "Who")) {
            assertTrue(text.none { it.contains(title) }, "$title in $text")
        }
        assertEquals("Busy · Leave by 5:10 PM", all[0].line1)
        assertEquals("Busy now, until 10:00 PM", all[1].line1)
    }

    @Test fun twentyFourHourClock() {
        assertEquals("Dentist at 17:30", nn(ok(listOf(dentist), updated = before(90)), before(90), is24h = true)!!.line1)
    }

    @Test fun travelRuleLimits() {
        assertNotNull(TravelRule.of("x", 1)); assertNotNull(TravelRule.of("k".repeat(40), 180))
        assertNull(TravelRule.of("x", 0)); assertNull(TravelRule.of("x", 181)); assertNull(TravelRule.of("x", null))
        assertNull(TravelRule.of(" ", 10)); assertNull(TravelRule.of(null, 10)); assertNull(TravelRule.of("k".repeat(41), 10))
        assertNull(TravelRule.of("a\nb", 10))
        // Only the first 10 rules are used.
        val many = (1..10).map { TravelRule.of("r$it", 5)!! } + TravelRule.of("dentist", 20)!!
        assertEquals("Dentist in 30 min", nn(ok(listOf(dentist), updated = before(30)), before(30), many)!!.line1)
    }

    @Test fun toStringHoldsNoTitles() {
        assertFalse(nn(ok(listOf(dentist)), before(30)).toString().contains("Dentist"))
    }
}
