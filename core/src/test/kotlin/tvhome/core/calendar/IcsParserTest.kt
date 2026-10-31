package tvhome.core.calendar

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
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

    private fun fixture(name: String): String =
        checkNotNull(javaClass.getResourceAsStream("/ics/$name")) { "missing fixture $name" }
            .readBytes().toString(Charsets.UTF_8)

    private fun parse(name: String, at: Instant = now, zone: ZoneId = ny) =
        IcsParser.parse(fixture(name), agendaWindow(at, zone), zone)

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
        val r = IcsParser.parse(ics, agendaWindow(now, ny), ny)
        assertEquals(4, r.size) // Mar 9, 10, 11, 12
    }

    @Test
    fun runawayRuleIsBoundedByTheInstanceCap() {
        val ics = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:x\r\nBEGIN:VEVENT\r\nUID:run@t\r\nDTSTAMP:20260101T000000Z\r\n" +
            "DTSTART:20260310T000000Z\r\nDTEND:20260310T000001Z\r\nRRULE:FREQ=SECONDLY\r\nSUMMARY:Runaway\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n"
        val r = IcsParser.parse(ics, agendaWindow(now, ny), ny)
        assertEquals(IcsParser.MAX_INSTANCES, r.size)
    }

    @Test
    fun garbageIsParseErrorWithFixedMessage() {
        val secret = "https://calendar.example/private/abc123.ics"
        val e = assertThrows(IcsParseException::class.java) {
            IcsParser.parse("this is not a calendar $secret", agendaWindow(now, ny), ny)
        }
        assertTrue(!e.message!!.contains(secret))
        assertThrows(IcsParseException::class.java) { IcsParser.parse("", agendaWindow(now, ny), ny) }
    }

    @Test
    fun manyEventsAreCappedAtMaxInstancesAcrossTheWholeParse() {
        val sb = StringBuilder("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:x\r\n")
        repeat(3_000) { i ->
            sb.append("BEGIN:VEVENT\r\nUID:e$i@t\r\nDTSTAMP:20260101T000000Z\r\n")
                .append("DTSTART:20260310T150000Z\r\nDTEND:20260310T160000Z\r\nSUMMARY:Event $i\r\nEND:VEVENT\r\n")
        }
        sb.append("END:VCALENDAR\r\n")
        val r = IcsParser.parse(sb.toString(), agendaWindow(now, ny), ny)
        assertEquals(IcsParser.MAX_INSTANCES, r.size)
        assertEquals(500, IcsParser.MAX_INSTANCES)
    }

    @Test
    fun manyRecurringEventsTogetherAreCapped() {
        val sb = StringBuilder("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:x\r\n")
        repeat(100) { i ->
            sb.append("BEGIN:VEVENT\r\nUID:r$i@t\r\nDTSTAMP:20260101T000000Z\r\n")
                .append("DTSTART:20260310T000000Z\r\nDTEND:20260310T000100Z\r\nRRULE:FREQ=HOURLY\r\nSUMMARY:R $i\r\nEND:VEVENT\r\n")
        }
        sb.append("END:VCALENDAR\r\n")
        val r = IcsParser.parse(sb.toString(), agendaWindow(now, ny), ny)
        assertEquals(IcsParser.MAX_INSTANCES, r.size)
    }
}
