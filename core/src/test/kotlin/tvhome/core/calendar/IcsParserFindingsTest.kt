package tvhome.core.calendar

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.function.ThrowingSupplier
import tvhome.core.agenda.agendaWindow
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicReference

/** One test (or more) per finding in docs/reviews/m1-core-t2-t3-reviews.md, named by its problem key. */
class IcsParserFindingsTest {
    private val ny = ZoneId.of("America/New_York")
    private val now = Instant.parse("2026-03-10T14:00:00Z") // Tue 10:00 EDT; window Mar 9 04:00Z .. Mar 13 04:00Z
    private val window = agendaWindow(now, ny)
    private val fast = Duration.ofSeconds(5)

    private fun cal(vararg events: String, head: String = ""): String =
        "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:x\r\n$head" +
            events.joinToString("") { "BEGIN:VEVENT\r\n" + it.trimIndent().replace("\n", "\r\n") + "\r\nEND:VEVENT\r\n" } +
            "END:VCALENDAR\r\n"

    private fun outcome(text: String, at: Instant = now, zone: ZoneId = ny, limits: IcsParser.Limits = IcsParser.Limits()) =
        parseOutcome(text, agendaWindow(at, zone), zone, limits = limits)

    private fun parse(text: String, at: Instant = now, zone: ZoneId = ny, limits: IcsParser.Limits = IcsParser.Limits()) =
        outcome(text, at, zone, limits).complete()

    private fun limit(o: ParseOutcome) = (o as? ParseOutcome.Exhausted)?.limit
    private fun reason(o: ParseOutcome) = (o as? ParseOutcome.Rejected)?.reason

    /** Runs [block] on another thread and fails the test if it takes longer than 5 s (hostile-input probes). */
    private fun <T> within(block: () -> T): T = assertTimeoutPreemptively(fast, ThrowingSupplier { block() })

    private fun at(s: String) = EventTime.At(Instant.parse(s))
    private fun day(s: String) = EventTime.AllDay(LocalDate.parse(s))

    // ------------------------------------------------------------------ floating-recurring-dst

    @Test
    fun floatingRecurringKeepsWallClockAcrossDst() {
        val ics = cal("UID:f@t\nDTSTART:20260301T090000\nDTEND:20260301T093000\nRRULE:FREQ=DAILY\nSUMMARY:Walk")
        // After the Mar 8 switch: 09:00 EDT = 13:00Z every day (biweekly gave 14:00Z here, an hour off).
        assertEquals((9..12).map { at("2026-03-%02dT13:00:00Z".format(it)) }, parse(ics).map { it.start })
        assertEquals(at("2026-03-09T13:30:00Z"), parse(ics).first().end)
        // A window across the switch: EST before, EDT after.
        val across = parse(ics, at = Instant.parse("2026-03-07T15:00:00Z"))
        assertEquals(
            listOf(at("2026-03-06T14:00:00Z"), at("2026-03-07T14:00:00Z"), at("2026-03-08T13:00:00Z"), at("2026-03-09T13:00:00Z")),
            across.map { it.start },
        )
        // Same feed on a TV in Tokyo: 09:00 JST.
        val tokyo = ZoneId.of("Asia/Tokyo")
        assertEquals(at("2026-03-10T00:00:00Z"), parse(ics, zone = tokyo).first { it.start == at("2026-03-10T00:00:00Z") }.start)
    }

    // ------------------------------------------------------------------ one-bad-event-fails-feed, unknown-tzid-as-utc

    @Test
    fun oneBadEventIsSkippedAndTheRestOfTheFeedStands() {
        val ics = cal(
            "UID:a@t\nDTSTART:20260310T150000Z\nSUMMARY:Good A",
            "UID:bad@t\nDTSTART:20260230T100000Z\nSUMMARY:Feb 30",            // impossible date: this VEVENT throws
            "UID:b@t\nDTSTART;TZID=Eastern Standard Time:20260310T090000\nSUMMARY:Outlook",
            "UID:c@t\nDTSTART;TZID=Bogus/Nowhere:20260311T090000\nSUMMARY:Unknown zone",
            "UID:d@t\nDTSTART;TZID=/mozilla.org/20070129_1/Asia/Tokyo:20260311T080000\nSUMMARY:Mozilla",
            "UID:e@t\nDTSTART:20260311T160000Z\nRRULE:FREQ=DAILY;COUNT=2;BYDAY=XX\nSUMMARY:Odd rule",
        )
        val r = parse(ics).associate { it.title to it.start }
        assertEquals(at("2026-03-10T15:00:00Z"), r["Good A"])
        assertEquals(at("2026-03-10T13:00:00Z"), r["Outlook"])       // Windows name -> America/New_York (EDT)
        assertEquals(at("2026-03-11T13:00:00Z"), r["Unknown zone"])  // device zone, not UTC (was 09:00Z)
        assertEquals(at("2026-03-10T23:00:00Z"), r["Mozilla"])       // 08:00 JST
        assertTrue("Feb 30" !in r)
        assertTrue("Odd rule" in r)
    }

    @Test
    fun everyWindowsZoneNameResolvesAndUnknownFallsBack() {
        for ((win, iana) in ZoneResolver.WINDOWS_IDS) {
            assertEquals(ZoneId.of(iana), ZoneResolver.resolve(win, ZoneId.of("Pacific/Kiritimati")), win)
        }
        assertEquals(ZoneId.of("America/Chicago"), ZoneResolver.resolve("central standard time", ny))
        assertEquals(ny, ZoneResolver.resolve("Mars/Olympus_Mons", ny))
        assertEquals(ny, ZoneResolver.resolve("", ny))
        assertEquals(ny, ZoneResolver.resolve("EST", ny)) // short ids are ambiguous: not used
        assertEquals(ZoneId.of("Europe/London"), ZoneResolver.resolve("\"/Europe/London\"", ny))
    }

    // ------------------------------------------------------------------ old-dtstart-recurrence-missing

    @Test
    fun seriesFrom1900AppearIn2026() {
        val ics = cal(
            "UID:d1@t\nDTSTART:19000101T140000Z\nDTEND:19000101T150000Z\nRRULE:FREQ=DAILY\nSUMMARY:Daily 1900",
            "UID:d2@t\nDTSTART:19000101T160000Z\nRRULE:FREQ=DAILY;COUNT=100000\nSUMMARY:Daily count",   // #46,xxx is in 2026
            "UID:d3@t\nDTSTART:19000101T170000Z\nRRULE:FREQ=DAILY;COUNT=10\nSUMMARY:Daily ended 1900",
            "UID:b1@t\nDTSTART;VALUE=DATE:19000310\nDTEND;VALUE=DATE:19000311\nRRULE:FREQ=YEARLY\nSUMMARY:Birthday",
            "UID:b2@t\nDTSTART;TZID=America/New_York:19000311T090000\nRRULE:FREQ=YEARLY\nSUMMARY:Anniversary",
        )
        val r = parse(ics)
        val byTitle = r.groupBy({ it.title }, { it.start })
        assertEquals((9..12).map { at("2026-03-%02dT14:00:00Z".format(it)) }, byTitle["Daily 1900"])
        assertEquals((9..12).map { at("2026-03-%02dT16:00:00Z".format(it)) }, byTitle["Daily count"])
        assertTrue("Daily ended 1900" !in byTitle)
        assertEquals(listOf(day("2026-03-10")), byTitle["Birthday"])
        assertEquals(day("2026-03-11"), r.single { it.title == "Birthday" }.end)
        assertEquals(listOf(at("2026-03-11T13:00:00Z")), byTitle["Anniversary"]) // EDT in 2026, not 1900's LMT
    }

    @Test
    fun countEndingInsideTheWindowIsExact() {
        // 1900-01-01 + 46,090 days = 2026-03-10, so COUNT=46,091 ends on Mar 10 (yesterday Mar 9 and today only).
        val count = java.time.temporal.ChronoUnit.DAYS.between(LocalDate.of(1900, 1, 1), LocalDate.of(2026, 3, 10)) + 1
        val ics = cal("UID:c@t\nDTSTART:19000101T140000Z\nRRULE:FREQ=DAILY;COUNT=$count\nSUMMARY:Ends today")
        assertEquals(listOf(at("2026-03-09T14:00:00Z"), at("2026-03-10T14:00:00Z")), parse(ics).map { it.start })
    }

    // ------------------------------------------------------------------ recurrence-id-thisandfuture (documented)

    @Test
    fun thisAndFutureOverrideReplacesOnlyItsOwnInstance() {
        val ics = cal(
            "UID:w@t\nDTSTART:20260303T100000Z\nDTEND:20260303T110000Z\nRRULE:FREQ=WEEKLY\nSUMMARY:Class",
            "UID:w@t\nRECURRENCE-ID;RANGE=THISANDFUTURE:20260310T100000Z\nDTSTART:20260310T120000Z\nDTEND:20260310T130000Z\nSUMMARY:Class moved",
        )
        assertEquals(listOf("Class moved" to at("2026-03-10T12:00:00Z")), parse(ics).map { it.title to it.start })
        // Not handled (docs/architecture/tv-home.md): the next week keeps the master's time and title.
        val next = parse(ics, at = Instant.parse("2026-03-17T14:00:00Z"))
        assertEquals(listOf("Class" to at("2026-03-17T10:00:00Z")), next.map { it.title to it.start })
    }

    @Test
    fun rdateAddsAndDateExdateRemovesADay() {
        val ics = cal(
            "UID:r@t\nDTSTART:20260301T150000Z\nDTEND:20260301T160000Z\nRRULE:FREQ=WEEKLY;BYDAY=MO\n" +
                "RDATE:20260311T150000Z\nEXDATE;VALUE=DATE:20260309\nSUMMARY:Gym",
        )
        val r = parse(ics)
        assertEquals(listOf(at("2026-03-11T15:00:00Z")), r.map { it.start })
        assertEquals(at("2026-03-11T16:00:00Z"), r.single().end)
    }

    // ------------------------------------------------------------------ ics-parse-cpu-memory-dos + rrule-advance-cpu

    /** Reviewer probe 1: a 335-byte VTIMEZONE with a SECONDLY rule from 1900 ran biweekly out of memory. */
    @Test
    fun probeVtimezoneSecondlyFrom1900() {
        val ics = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nBEGIN:VTIMEZONE\r\nTZID:Evil\r\nBEGIN:STANDARD\r\nDTSTART:19000101T000000\r\n" +
            "RRULE:FREQ=SECONDLY\r\nTZOFFSETFROM:+0000\r\nTZOFFSETTO:+0100\r\nEND:STANDARD\r\nEND:VTIMEZONE\r\nBEGIN:VEVENT\r\nUID:a\r\n" +
            "DTSTART;TZID=Evil:20260310T090000\r\nSUMMARY:x\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n"
        val r = within { parse(ics) }
        assertEquals(listOf(at("2026-03-10T13:00:00Z")), r.map { it.start }) // unknown TZID "Evil": device zone
    }

    /** P22 (expectation changed by ADR 0004): case and folding are read as VTIMEZONE and skipped; a group prefix or a
     *  parameter on BEGIN rejects the feed. Never interpreted either way. */
    @Test
    fun probeVtimezoneHiddenByFoldingOrCaseIsSkippedOrRejected() {
        val tz = "TZID:Evil\r\nBEGIN:STANDARD\r\nDTSTART:19000101T000000\r\nRRULE:FREQ=SECONDLY\r\nTZOFFSETFROM:+0000\r\n" +
            "TZOFFSETTO:+0100\r\nEND:STANDARD\r\n"
        fun ics(begin: String) = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\n$begin\r\n${tz}END:VTIMEZONE\r\nBEGIN:VEVENT\r\nUID:a\r\n" +
            "DTSTART;TZID=Evil:20260310T090000\r\nSUMMARY:x\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n"
        for (begin in listOf("begin:vtimezone", "BEGIN:VTIME\r\n ZONE")) {
            assertEquals(listOf(at("2026-03-10T13:00:00Z")), within { parse(ics(begin)) }.map { it.start }, begin)
        }
        for (begin in listOf("x.BEGIN:VTIMEZONE", "BEGIN;X=1:VTimeZone")) {
            assertEquals(RejectReason.STRUCTURE, reason(within { outcome(ics(begin)) }), begin)
        }
    }

    /** Reviewer probe 2: SECONDLY;COUNT=2e9 from 1970 ran over 60 s. */
    @Test
    fun probeSecondlyHugeCountFrom1970() {
        // COUNT=2e9 is also out of range (1..1e9): unsupported either way, DTSTART only (1970, outside the window)
        val ics = cal("UID:s@t\nDTSTART:19700101T000000Z\nRRULE:FREQ=SECONDLY;COUNT=2000000000\nSUMMARY:x")
        val o = within { outcome(ics) }.completeOutcome()
        assertEquals(emptyList<EventInstance>(), o.events)
        assertEquals(1, o.stats.unsupportedRules)
    }

    /** Reviewer probe 3: 20k SECONDLY events ending just before the window ran over 60 s. */
    @Test
    fun probeTwentyThousandSecondlyEventsEndingBeforeTheWindow() {
        val events = Array(20_000) { "UID:s$it@t\nDTSTART:20260308T000000Z\nRRULE:FREQ=SECONDLY;UNTIL=20260309T035959Z\nSUMMARY:x" }
        val ics = cal(*events)
        assertEquals(emptyList<EventInstance>(), within { parse(ics) })
    }

    @Test
    fun minutelyAndHourlyShowOnlyDtstart() {
        val ics = cal(
            "UID:m@t\nDTSTART:20260310T150000Z\nRRULE:FREQ=MINUTELY\nSUMMARY:Minutely",
            "UID:h@t\nDTSTART:20260310T160000Z\nRRULE:FREQ=HOURLY;BYSETPOS=1\nSUMMARY:Hourly",
        )
        assertEquals(listOf(at("2026-03-10T15:00:00Z"), at("2026-03-10T16:00:00Z")), within { parse(ics) }.map { it.start })
    }

    /**
     * P23 (expectation changed by ADR 0004, `deadline-drops-recurring-silently`): COUNT rules that need a walk from
     * year 1 run out the parse-wide steps, so the answer is unknown: Exhausted, never a shorter Complete.
     */
    @Test
    fun probeWalkingCountRulesFromYearOneExhaustTheSteps() {
        val events = Array(3_000) { "UID:w$it@t\nDTSTART:00010101T090000Z\nRRULE:FREQ=DAILY;BYMONTHDAY=13;COUNT=1000000000\nSUMMARY:w" } +
            "UID:real@t\nDTSTART:20260310T170000Z\nSUMMARY:Real"
        assertEquals(ParseLimit.STEPS, limit(within { outcome(cal(*events)) }))
    }

    @Test
    fun wallClockDeadlineEndsTheParse() {
        val events = Array(3_000) { "UID:w$it@t\nDTSTART:00010101T090000Z\nRRULE:FREQ=DAILY;BYMONTHDAY=13;COUNT=1000000000\nSUMMARY:w" } +
            "UID:real@t\nDTSTART:20260310T170000Z\nSUMMARY:Real"
        val unbounded = IcsParser.Limits(maxSteps = Long.MAX_VALUE, maxStepsPerSeries = Long.MAX_VALUE, deadline = Duration.ofMillis(300))
        val t0 = System.nanoTime()
        val r = within { outcome(cal(*events), limits = unbounded) }
        assertTrue(System.nanoTime() - t0 < 3_000_000_000L)
        assertEquals(ParseLimit.DEADLINE, limit(r)) // deadline-drops-recurring-silently: never a Complete without them
    }

    @Test
    fun interruptStopsTheParse() {
        val events = Array(3_000) { "UID:w$it@t\nDTSTART:00010101T090000Z\nRRULE:FREQ=DAILY;BYMONTHDAY=13;COUNT=1000000000\nSUMMARY:w" }
        val text = cal(*events)
        val unbounded = IcsParser.Limits(maxSteps = Long.MAX_VALUE, maxStepsPerSeries = Long.MAX_VALUE, deadline = Duration.ofMinutes(10))
        val thrown = AtomicReference<Throwable?>()
        val t = Thread { thrown.set(runCatching { parse(text, limits = unbounded) }.exceptionOrNull()) }
        within {
            t.start()
            Thread.sleep(300)
            t.interrupt()
            t.join()
        }
        assertTrue(thrown.get() is InterruptedException, thrown.get().toString())
    }

    @Test
    fun veventCountCapExhaustsTheParse() {
        val small = IcsParser.Limits(maxVevents = 30_000)
        val events = Array(30_001) { "UID:v$it@t\nDTSTART:20200101T000000Z" }
        assertEquals(ParseLimit.VEVENTS, limit(within { outcome(cal(*events), limits = small) }))
        assertEquals(0, parse(cal(*events.copyOf(30_000).requireNoNulls()), limits = small).size) // at the cap (all in 2020)
    }

    /** P24 (expectation changed by ADR 0004): ~36,500 earlier instances would overlap; the series budget skips it. */
    @Test
    fun longSpanDailySeriesIsSkippedByItsSeriesBudget() {
        val ics = cal("UID:l@t\nDTSTART:19260101T000000Z\nDTEND:20260101T000000Z\nRRULE:FREQ=DAILY\nSUMMARY:Long")
        val o = within { outcome(ics) }.completeOutcome()
        assertEquals(0, o.events.size)
        assertEquals(1, o.stats.skippedSeries)
    }

    @Test
    fun denseByRulesAreNotExpanded() {
        val ics = cal("UID:x@t\nDTSTART:20260101T000000Z\nRRULE:FREQ=YEARLY;BYMONTH=3;BYMONTHDAY=10;BYHOUR=${(0..23).joinToString(",")};" +
            "BYMINUTE=${(0..59).joinToString(",")};BYSECOND=${(0..59).joinToString(",")}\nSUMMARY:Dense")
        assertEquals(emptyList<EventInstance>(), within { parse(ics) }) // DTSTART only: Jan 1
    }
}
