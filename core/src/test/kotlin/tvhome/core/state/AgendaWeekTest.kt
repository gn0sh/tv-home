package tvhome.core.state

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import tvhome.core.agenda.WEEK_DAYS
import tvhome.core.agenda.agendaWindow
import tvhome.core.agenda.buildDays
import tvhome.core.agenda.fetchWindow
import tvhome.core.calendar.EventInstance
import tvhome.core.calendar.EventTime
import tvhome.core.calendar.FetchResult
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** The Board's week (seven days from today) carried on the agenda states. */
class AgendaWeekTest {
    private val zone = ZoneId.of("America/Chicago")
    private val now = Instant.parse("2026-11-03T15:00:00Z") // Tue 09:00 local
    private val today = LocalDate.of(2026, 11, 3)

    private fun at(day: Int, hour: Int, title: String) =
        EventInstance("u$title", title, EventTime.At(today.plusDays(day.toLong()).atTime(hour, 0).atZone(zone).toInstant()), null)

    @Test fun fetchWindowCoversTheWeekWithADayOfSlack() {
        val w = fetchWindow(now, zone)
        assertEquals(today.minusDays(1).atStartOfDay(zone).toInstant(), w.start)
        assertEquals(today.plusDays(WEEK_DAYS + 1L).atStartOfDay(zone).toInstant(), w.end)
        assertEquals(agendaWindow(now, zone).start, w.start)
    }

    @Test fun freshFetchCarriesSevenDaysInOrderWithAllDayFirst() {
        val allDay = EventInstance("a", "Holiday", EventTime.AllDay(today.plusDays(5)), null)
        val evs = listOf(at(0, 10, "Today"), at(6, 9, "Last"), at(7, 9, "Beyond"), at(5, 8, "Timed"), allDay)
        val s = reduce(true, null, FetchResult.Ok(evs, now), now, zone) as AgendaState.Ok
        assertEquals((0 until 7).map { today.plusDays(it.toLong()) }, s.week.map { it.date })
        assertEquals(listOf("Holiday", "Timed"), s.week[5].items.map { it.title })
        assertEquals(listOf("Last"), s.week[6].items.map { it.title })
        assertTrue(s.week.none { d -> d.items.any { it.title == "Beyond" } })
    }

    /** An old-format cache (narrow window) gives only the days it covers: later days are left out, never shown empty. */
    @Test fun daysTheCacheDoesNotCoverAreLeftOut() {
        val old = CachedAgenda(now.minus(Duration.ofMinutes(5)), agendaWindow(now.minus(Duration.ofMinutes(5)), zone), listOf(at(0, 10, "A")))
        val s = reduce(true, old, null, now, zone) as AgendaState.Ok
        assertEquals(listOf(today, today.plusDays(1), today.plusDays(2)), s.week.map { it.date })
    }

    /** The wider window does not keep a cache usable longer: a fetch from two days ago is still no data. */
    @Test fun freshnessStillUsesTheNarrowWindow() {
        val twoDaysAgo = now.minus(Duration.ofDays(2))
        val c = CachedAgenda(twoDaysAgo, fetchWindow(twoDaysAgo, zone), listOf(at(0, 10, "A")))
        assertEquals(AgendaState.CantReach, reduce(true, c, FetchResult.Failed(tvhome.core.calendar.SourceError.NETWORK), now, zone))
    }

    @Test fun buildDaysStopsAtTheCoveredEnd() {
        val end = today.plusDays(4).atStartOfDay(zone).toInstant()
        assertEquals(4, buildDays(emptyList(), now, zone, 7, end).size)
        assertEquals(0, buildDays(emptyList(), now, zone, 7, today.atStartOfDay(zone).toInstant()).size)
    }
}

/** board-truncated-week: after a cut at the parser's cap, days past the last kept instance are not loaded, not empty. */
class TruncatedWeekTest {
    private val zone = ZoneId.of("America/Chicago")
    private val now = Instant.parse("2026-11-03T15:00:00Z")
    private val today = LocalDate.of(2026, 11, 3)
    private fun at(d: Int, h: Int) = EventInstance("u$d$h", "E", EventTime.At(today.plusDays(d.toLong()).atTime(h, 0).atZone(zone).toInstant()), null)

    @Test fun daysFromTheLastKeptStartAreNotLoaded() {
        val s = reduce(true, null, FetchResult.Ok(listOf(at(0, 9), at(3, 10)), now, truncated = true), now, zone) as AgendaState.Ok
        assertEquals(listOf(today, today.plusDays(1), today.plusDays(2)), s.week.map { it.date }, "day 3 may be partial: not shown as complete")
    }

    @Test fun neverLessThanTodayAndTomorrow() {
        val s = reduce(true, null, FetchResult.Ok(listOf(at(0, 9)), now, truncated = true), now, zone) as AgendaState.Ok
        assertEquals(listOf(today, today.plusDays(1)), s.week.map { it.date })
    }

    @Test fun notTruncatedKeepsTheWholeWeek() {
        val s = reduce(true, null, FetchResult.Ok(listOf(at(0, 9)), now), now, zone) as AgendaState.Ok
        assertEquals(7, s.week.size)
    }
}
