package tvhome.core.agenda

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tvhome.core.calendar.EventInstance
import tvhome.core.calendar.EventTime
import tvhome.core.calendar.IcsParser
import tvhome.core.calendar.ParseOutcome
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

class AgendaTest {
    private val ny = ZoneId.of("America/New_York")
    private val clock = Clock.fixed(Instant.parse("2026-03-10T14:00:00Z"), ZoneOffset.UTC) // Tue 10:00 EDT

    private fun agendaOf(name: String, zone: ZoneId = ny, c: Clock = clock): Pair<AgendaDay, AgendaDay> {
        val bytes = checkNotNull(javaClass.getResourceAsStream("/ics/$name")).readBytes()
        val ev = (IcsParser.parse(bytes, agendaWindow(c.instant(), zone), zone) as ParseOutcome.Complete).events
        return buildAgenda(ev, c.instant(), zone)
    }

    private fun titles(d: AgendaDay) = d.items.map { it.title }

    @Test
    fun daysAreTodayAndTomorrowInZone() {
        val (t, tm) = agendaOf("empty-day.ics")
        assertEquals(LocalDate.of(2026, 3, 10), t.date)
        assertEquals(LocalDate.of(2026, 3, 11), tm.date)
        assertEquals(emptyList<AgendaItem>(), t.items)
        assertEquals(emptyList<AgendaItem>(), tm.items)
    }

    @Test
    fun emptyCalendar() {
        val (t, tm) = agendaOf("empty-calendar.ics")
        assertEquals(0, t.items.size + tm.items.size)
    }

    @Test
    fun weeklyShowsTodayWithLocalStart() {
        val (t, tm) = agendaOf("weekly-rrule.ics")
        assertEquals(listOf(AgendaItem("Weekly standup", TimeLabel.Starts(LocalTime.of(11, 0)))), t.items)
        assertEquals(emptyList<AgendaItem>(), tm.items)
    }

    @Test
    fun exdateSkipsTodayKeepsTomorrow() {
        val (t, tm) = agendaOf("exdate.ics")
        assertEquals(emptyList<AgendaItem>(), t.items)
        assertEquals(listOf(AgendaItem("Daily sync", TimeLabel.Starts(LocalTime.of(10, 0)))), tm.items)
    }

    @Test
    fun movedInstanceShowsOnceOnNewDay() {
        val (t, tm) = agendaOf("moved-instance.ics")
        assertEquals(emptyList<AgendaItem>(), t.items)
        assertEquals(listOf(AgendaItem("Planning (moved)", TimeLabel.Starts(LocalTime.of(14, 0)))), tm.items)
    }

    @Test
    fun cancelledNotShown() {
        val (t, tm) = agendaOf("cancelled-instance.ics")
        assertEquals(0, t.items.size + tm.items.size)
    }

    @Test
    fun utcAndTzidAndFloatingLabelsAreLocal() {
        assertEquals(LocalTime.of(9, 0), (agendaOf("utc.ics").first.items.single().label as TimeLabel.Starts).time)
        assertEquals(LocalTime.of(19, 0), (agendaOf("tzid-other-zone.ics").first.items.single().label as TimeLabel.Starts).time)
        assertEquals(LocalTime.of(9, 30), (agendaOf("floating.ics").first.items.single().label as TimeLabel.Starts).time)
    }

    @Test
    fun allDayFirstThenByStartThenTitle() {
        val (t, tm) = agendaOf("allday-single.ics")
        assertEquals(
            listOf(
                AgendaItem("Birthday", TimeLabel.AllDay),
                AgendaItem("No end", TimeLabel.AllDay),
                AgendaItem("Timed same day", TimeLabel.Starts(LocalTime.of(9, 0))),
            ),
            t.items,
        )
        assertEquals(emptyList<AgendaItem>(), tm.items)
    }

    @Test
    fun multiDayAllDayAppearsOnEachDate() {
        val (t, tm) = agendaOf("allday-multiday.ics")
        assertEquals(listOf(AgendaItem("Conference", TimeLabel.AllDay)), t.items)
        assertEquals(listOf(AgendaItem("Conference", TimeLabel.AllDay)), tm.items)
        // Exclusive end: not on Mar 12.
        val later = Clock.fixed(Instant.parse("2026-03-11T14:00:00Z"), ZoneOffset.UTC)
        val (t2, tm2) = agendaOf("allday-multiday.ics", c = later)
        assertEquals(1, t2.items.size)
        assertEquals(0, tm2.items.size)
    }

    @Test
    fun dstWeekKeepsNineAmEitherSideOfSwitch() {
        val sat = Clock.fixed(Instant.parse("2026-03-07T14:00:00Z"), ZoneOffset.UTC)
        val (t, tm) = agendaOf("dst-change-week.ics", c = sat)
        assertEquals(LocalDate.of(2026, 3, 7), t.date)
        assertEquals(listOf(AgendaItem("Morning run", TimeLabel.Starts(LocalTime.of(9, 0)))), t.items)
        assertEquals(LocalDate.of(2026, 3, 8), tm.date)
        assertEquals(listOf(AgendaItem("Morning run", TimeLabel.Starts(LocalTime.of(9, 0)))), tm.items)
    }

    @Test
    fun eventAcrossMidnightStartsTodayContinuesTomorrow() {
        val (t, tm) = agendaOf("across-midnight.ics")
        assertEquals(listOf(AgendaItem("Late movie", TimeLabel.Starts(LocalTime.of(22, 0)))), t.items)
        assertEquals(listOf(AgendaItem("Late movie", TimeLabel.Continues)), tm.items)
    }

    @Test
    fun dayBoundariesFollowTheInjectedZone() {
        // 2026-03-10T23:30Z is Mar 10 19:30 in New York but Mar 11 08:30 in Tokyo.
        val c = Clock.fixed(Instant.parse("2026-03-10T23:30:00Z"), ZoneOffset.UTC)
        val tokyo = ZoneId.of("Asia/Tokyo")
        assertEquals(LocalDate.of(2026, 3, 10), agendaOf("empty-day.ics", ny, c).first.date)
        assertEquals(LocalDate.of(2026, 3, 11), agendaOf("empty-day.ics", tokyo, c).first.date)
    }

    @Test
    fun eventEndingExactlyAtMidnightDoesNotSpillOver() {
        val ev = EventInstance(
            "u", "Ends at midnight",
            EventTime.At(Instant.parse("2026-03-10T20:00:00Z")), // 16:00 EDT
            EventTime.At(Instant.parse("2026-03-11T04:00:00Z")), // 00:00 EDT Mar 11
        )
        val (t, tm) = buildAgenda(listOf(ev), clock.instant(), ny)
        assertEquals(1, t.items.size)
        assertEquals(0, tm.items.size)
    }

    @Test
    fun eventWithoutEndAppearsOnlyOnStartDay() {
        val ev = EventInstance("u", "Point", EventTime.At(Instant.parse("2026-03-11T15:00:00Z")), null)
        val (t, tm) = buildAgenda(listOf(ev), clock.instant(), ny)
        assertEquals(0, t.items.size)
        assertEquals(listOf(AgendaItem("Point", TimeLabel.Starts(LocalTime.of(11, 0)))), tm.items)
    }

    @Test
    fun multiDayTimedEventContinuesOnMiddleDays() {
        val ev = EventInstance(
            "u", "Retreat",
            EventTime.At(Instant.parse("2026-03-09T15:00:00Z")),
            EventTime.At(Instant.parse("2026-03-12T15:00:00Z")),
        )
        val (t, tm) = buildAgenda(listOf(ev), clock.instant(), ny)
        assertEquals(TimeLabel.Continues, t.items.single().label)
        assertEquals(TimeLabel.Continues, tm.items.single().label)
    }
}
