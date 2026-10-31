package tvhome.core.calendar

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tvhome.core.agenda.agendaWindow
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class IcsParserTest {
    private val ny = ZoneId.of("America/New_York")

    /** Tue 2026-03-10 10:00 EDT. Window: Mar 9 00:00 EDT to Mar 13 00:00 EDT. */
    private val now = Instant.parse("2026-03-10T14:00:00Z")

    private fun fixture(name: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/ics/$name")) { "missing fixture $name" }.readBytes()

    private fun parse(name: String, at: Instant = now, zone: ZoneId = ny) =
        IcsParser.parse(fixture(name), agendaWindow(at, zone), zone).complete()

    private fun at(s: String) = EventTime.At(Instant.parse(s))

    @Test
    fun weeklyRruleYieldsOnlyInstancesInWindow() {
        val r = parse("weekly-rrule.ics")
        assertEquals(listOf(at("2026-03-10T15:00:00Z")), r.map { it.start })
        assertEquals(at("2026-03-10T16:00:00Z"), r.single().end)
        assertEquals("Weekly standup", r.single().title)
    }

    @Test
    fun exdateRemovesInstance() {
        val r = parse("exdate.ics")
        assertEquals(
            listOf(at("2026-03-09T14:00:00Z"), at("2026-03-11T14:00:00Z"), at("2026-03-12T14:00:00Z")),
            r.map { it.start },
        )
    }

    @Test
    fun recurrenceIdOverrideReplacesOriginal() {
        val r = parse("moved-instance.ics")
        assertEquals(1, r.size)
        assertEquals("Planning (moved)", r.single().title)
        assertEquals(at("2026-03-11T18:00:00Z"), r.single().start)
        assertEquals(at("2026-03-11T19:00:00Z"), r.single().end)
    }

    @Test
    fun overrideOutsideWindowDoesNotAppearAndOriginalStillDropped() {
        // Looking at the week of Mar 3: original is Mar 3 15:00Z (not replaced), the override lives on Mar 11.
        val r = parse("moved-instance.ics", at = Instant.parse("2026-03-03T14:00:00Z"))
        assertEquals(listOf(at("2026-03-03T15:00:00Z")), r.map { it.start })
        assertEquals("Planning", r.single().title)
    }

    @Test
    fun cancelledInstanceAndCancelledEventAreDropped() {
        val r = parse("cancelled-instance.ics")
        assertEquals(emptyList<EventInstance>(), r)
    }

    @Test
    fun utcTime() {
        assertEquals(listOf(at("2026-03-10T13:00:00Z")), parse("utc.ics").map { it.start })
    }

    @Test
    fun tzidInAnotherZoneResolvesToInstant() {
        val r = parse("tzid-other-zone.ics")
        assertEquals(at("2026-03-10T23:00:00Z"), r.single().start) // 08:00 JST on Mar 11
        assertEquals(at("2026-03-11T00:00:00Z"), r.single().end)
    }

    @Test
    fun floatingTimeIsReadInTheDeviceZone() {
        assertEquals(at("2026-03-10T13:30:00Z"), parse("floating.ics").single().start) // 09:30 EDT
        val tokyo = ZoneId.of("Asia/Tokyo")
        val r = parse("floating.ics", zone = tokyo)
        assertEquals(at("2026-03-10T00:30:00Z"), r.single().start) // 09:30 JST
    }

    @Test
    fun allDaySingleDayNotZoneConverted() {
        val r = parse("allday-single.ics")
        val birthday = r.single { it.title == "Birthday" }
        assertEquals(EventTime.AllDay(LocalDate.of(2026, 3, 10)), birthday.start)
        assertEquals(EventTime.AllDay(LocalDate.of(2026, 3, 11)), birthday.end)
        // Same dates in a far-east zone: still Mar 10.
        val tokyo = parse("allday-single.ics", zone = ZoneId.of("Pacific/Kiritimati"))
        assertEquals(EventTime.AllDay(LocalDate.of(2026, 3, 10)), tokyo.single { it.title == "Birthday" }.start)
        assertEquals(3, r.size)
    }

    @Test
    fun allDayMultiDayKeepsExclusiveEnd() {
        val r = parse("allday-multiday.ics").single()
        assertEquals(EventTime.AllDay(LocalDate.of(2026, 3, 9)), r.start)
        assertEquals(EventTime.AllDay(LocalDate.of(2026, 3, 12)), r.end)
    }

    @Test
    fun dstChangeKeepsLocalWallClockTime() {
        val r = parse("dst-change-week.ics")
        // Mar 9 and Mar 10 are after the Mar 8 switch: 09:00 EDT = 13:00Z. COUNT=5 ends on Mar 10.
        assertEquals(listOf(at("2026-03-09T13:00:00Z"), at("2026-03-10T13:00:00Z")), r.map { it.start })
        val before = parse("dst-change-week.ics", at = Instant.parse("2026-03-07T14:00:00Z"))
        // Window Mar 6..Mar 9 inclusive, local: Mar 6 and 7 at EST (14:00Z), Mar 8 at EDT (13:00Z).
        assertEquals(
            listOf(at("2026-03-06T14:00:00Z"), at("2026-03-07T14:00:00Z"), at("2026-03-08T13:00:00Z"), at("2026-03-09T13:00:00Z")),
            before.map { it.start },
        )
    }

    @Test
    fun eventAcrossMidnightKeepsBothEnds() {
        val r = parse("across-midnight.ics").single()
        assertEquals(at("2026-03-11T02:00:00Z"), r.start)
        assertEquals(at("2026-03-11T06:00:00Z"), r.end)
    }

    @Test
    fun farAwayEventIsOutsideWindow() {
        assertEquals(emptyList<EventInstance>(), parse("empty-day.ics"))
    }

    @Test
    fun calendarWithNoEventsParsesToEmpty() {
        assertEquals(emptyList<EventInstance>(), parse("empty-calendar.ics"))
    }

    @Test
    fun oldRecurringSeriesIsReachedWithoutHittingTheCap() {
        val ics = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:x\r\nBEGIN:VEVENT\r\nUID:old@t\r\nDTSTAMP:20200101T000000Z\r\n" +
            "DTSTART:20100101T140000Z\r\nDTEND:20100101T150000Z\r\nRRULE:FREQ=DAILY\r\nSUMMARY:Old daily\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n"
        val r = parseComplete(ics, agendaWindow(now, ny), ny)
        assertEquals(4, r.size) // Mar 9, 10, 11, 12
    }

    @Test
    fun secondlyRuleIsNotExpandedAndShowsOnlyItsDtstart() {
        val ics = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:x\r\nBEGIN:VEVENT\r\nUID:run@t\r\nDTSTAMP:20260101T000000Z\r\n" +
            "DTSTART:20260310T000000Z\r\nDTEND:20260310T000001Z\r\nRRULE:FREQ=SECONDLY\r\nSUMMARY:Runaway\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n"
        val r = parseComplete(ics, agendaWindow(now, ny), ny)
        assertEquals(listOf(at("2026-03-10T00:00:00Z")), r.map { it.start })
        assertEquals(at("2026-03-10T00:00:01Z"), r.single().end)
    }

    @Test
    fun garbageIsRejectedWithNoText() {
        val secret = "https://calendar.example/private/abc123.ics"
        val r = parseOutcome("this is not a calendar $secret", agendaWindow(now, ny), ny)
        assertEquals(RejectReason.NOT_VCALENDAR, (r as ParseOutcome.Rejected).reason)
        assertTrue(!r.toString().contains(secret))
        assertEquals(RejectReason.NOT_VCALENDAR, (parseOutcome("", agendaWindow(now, ny), ny) as ParseOutcome.Rejected).reason)
    }

    @Test
    fun manyEventsAreCappedAtMaxInstancesKeepingAStableChoice() {
        val sb = StringBuilder("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:x\r\n")
        repeat(3_000) { i ->
            sb.append("BEGIN:VEVENT\r\nUID:e$i@t\r\nDTSTAMP:20260101T000000Z\r\n")
                .append("DTSTART:20260310T150000Z\r\nDTEND:20260310T160000Z\r\nSUMMARY:Event $i\r\nEND:VEVENT\r\n")
        }
        sb.append("END:VCALENDAR\r\n")
        val r = parseComplete(sb.toString(), agendaWindow(now, ny), ny)
        assertEquals(500, IcsParser.MAX_INSTANCES)
        assertEquals(IcsParser.MAX_INSTANCES, r.size)
        // Same start for all: ties break by title, so the kept ones are the first 500 titles in sort order.
        assertEquals((0 until 3_000).map { "Event $it" }.sorted().take(500), r.map { it.title })
    }

    /** instance-cap-starves-today: yesterday's events listed first in the feed must not push out today's. */
    @Test
    fun capKeepsTodayAndTomorrowBeforeYesterday() {
        val sb = StringBuilder("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:x\r\n")
        fun ev(uid: String, start: String, title: String) = sb.append("BEGIN:VEVENT\r\nUID:$uid\r\nDTSTAMP:20260101T000000Z\r\n")
            .append("DTSTART:${start}\r\nSUMMARY:$title\r\nEND:VEVENT\r\n")
        repeat(600) { ev("y$it@t", "20260309T120000Z", "Y%03d".format(it)) }   // yesterday (Mar 9), first in the feed
        repeat(10) { ev("t$it@t", "20260310T150000Z", "T$it") }                // today
        repeat(5) { ev("m$it@t", "20260311T150000Z", "M$it") }                 // tomorrow
        repeat(5) { ev("d$it@t", "20260312T150000Z", "D$it") }                 // day after tomorrow (window only)
        sb.append("END:VCALENDAR\r\n")
        val w = agendaWindow(now, ny)
        val r = parseComplete(sb.toString(), w, ny, focus = tvhome.core.agenda.agendaFocus(w, ny))
        assertEquals(500, r.size)
        val titles = r.map { it.title }
        assertEquals((0 until 10).map { "T$it" } + (0 until 5).map { "M$it" }, titles.filter { it[0] == 'T' || it[0] == 'M' })
        // the rest of the cap goes to the earliest of the others: yesterday's Y000..Y484; day-after-tomorrow loses
        assertEquals((0 until 485).map { "Y%03d".format(it) }, titles.filter { it[0] == 'Y' })
        assertTrue(titles.none { it[0] == 'D' })
        assertEquals(r.sortedBy { (it.start as EventTime.At).instant }, r) // returned in start order
        // the real adapter passes the focus too
        val viaAdapter = IcsParse.ics { ny }.parse(sb.toString().toByteArray(), w).complete()
        assertEquals(r, viaAdapter)
    }

    @Test
    fun manyRecurringSeriesTogetherAreCappedKeepingTheEarliestOfToday() {
        val sb = StringBuilder("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:x\r\n")
        repeat(100) { i ->
            sb.append("BEGIN:VEVENT\r\nUID:r$i@t\r\nDTSTAMP:20260101T000000Z\r\n")
                .append("DTSTART:20260301T000000Z\r\nDTEND:20260301T000100Z\r\nRRULE:FREQ=DAILY;BYHOUR=${(0..23).joinToString(",")}\r\nSUMMARY:R $i\r\nEND:VEVENT\r\n")
        }
        sb.append("END:VCALENDAR\r\n")
        val r = IcsParse.ics { ny }.parse(sb.toString().toByteArray(), agendaWindow(now, ny)).complete()
        assertEquals(IcsParser.MAX_INSTANCES, r.size)
        // Today starts 04:00Z (midnight EDT). 100 series x 5 hours = 500: today's 04:00Z..08:00Z, nothing else.
        val hours = r.map { (it.start as EventTime.At).instant }.groupingBy { it }.eachCount()
        assertEquals((4..8).map { Instant.parse("2026-03-10T%02d:00:00Z".format(it)) to 100 }.toMap(), hours)
    }
}
