package tvhome.core.calendar

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.function.ThrowingSupplier
import tvhome.core.agenda.agendaFocus
import tvhome.core.agenda.agendaWindow
import tvhome.core.calendar.HostileFeeds.event
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlin.random.Random

/**
 * The hostile probes P1-P32 of docs/tasks/tv-home-m1.md, plus P33-P36 from the T2b reviews (docs/reviews/m1-t2b-reviews.md) (T2b acceptance tests). Run by `:core:hostileTest` in a
 * 96 MiB test JVM. Every probe runs with a frozen clock (`nanoTime = { 0L }`) inside a 1 s preemptive timeout, which
 * proves the deterministic limits bind, not the deadline.
 */
@Tag("hostile")
@Tag("ics-parse-cpu-memory-dos")
class HostileFeedsTest {
    private val ny = ZoneId.of("America/New_York")
    private val now = Instant.parse("2026-03-10T14:00:00Z") // Tue 10:00 EDT; window Mar 9 04:00Z .. Mar 13 04:00Z
    private val window = agendaWindow(now, ny)

    /** Frozen clock, 1 s preemptive timeout; also records the slowest probe for the handoff. */
    private fun probe(body: ByteArray, limits: IcsParser.Limits = IcsParser.Limits()): ParseOutcome {
        val t0 = System.nanoTime()
        val r = assertTimeoutPreemptively(Duration.ofSeconds(1), ThrowingSupplier {
            IcsParser.parse(body, window, ny, agendaFocus(window, ny), limits) { 0L }
        })
        val ms = (System.nanoTime() - t0) / 1_000_000
        val name = Thread.currentThread().stackTrace.firstOrNull { it.methodName.matches(Regex("p\\d\\d.*")) }?.methodName
        println("probe $name: ${body.size} B, $ms ms, $r")
        return r
    }

    private fun complete(o: ParseOutcome) = o as? ParseOutcome.Complete ?: fail("expected Complete, got $o")
    private fun exhausted(o: ParseOutcome) = (o as? ParseOutcome.Exhausted ?: fail("expected Exhausted, got $o")).limit
    private fun rejected(o: ParseOutcome) = (o as? ParseOutcome.Rejected ?: fail("expected Rejected, got $o")).reason
    private fun at(s: String) = EventTime.At(Instant.parse(s))
    private fun bytes(s: String) = s.toByteArray(Charsets.UTF_8)

    private val p1 = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nBEGIN:VTIMEZONE\r\nTZID:Evil\r\nBEGIN:STANDARD\r\nDTSTART:19000101T000000\r\n" +
        "RRULE:FREQ=SECONDLY\r\nTZOFFSETFROM:+0000\r\nTZOFFSETTO:+0100\r\nEND:STANDARD\r\nEND:VTIMEZONE\r\nBEGIN:VEVENT\r\nUID:a\r\n" +
        "DTSTART;TZID=Evil:20260310T090000\r\nSUMMARY:x\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n"

    @Test fun p01VtimezoneSecondlyFrom1900IsNeverInterpreted() {
        assertTrue(p1.length < 400, "${p1.length}")
        val r = complete(probe(bytes(p1)))
        assertEquals(listOf(at("2026-03-10T13:00:00Z")), r.events.map { it.start }) // unknown TZID: device zone
    }

    @Test fun p02SecondlyHugeCountFrom1970() {
        val r = complete(probe(HostileFeeds.calendar { event("UID:s\nDTSTART:19700101T000000Z\nRRULE:FREQ=SECONDLY;COUNT=2000000000") }))
        assertEquals(0, r.events.size)
        assertEquals(1, r.stats.unsupportedRules)
    }

    @Test fun p03TwentyThousandSecondlyEventsEndingBeforeTheWindow() {
        val r = complete(probe(HostileFeeds.calendar {
            repeat(20_000) { event("UID:s$it\nDTSTART:20260308T000000Z\nRRULE:FREQ=SECONDLY;UNTIL=20260309T035959Z") }
        }))
        assertEquals(0, r.events.size)
        assertEquals(20_000, r.stats.unsupportedRules)
    }

    @Test fun p04SecondlyAndMinutelyWithBySetPosFrom1970() {
        val r = complete(probe(HostileFeeds.calendar {
            repeat(1_000) { event("UID:s$it\nDTSTART:19700101T000000Z\nRRULE:FREQ=SECONDLY;BYSETPOS=1,-1,7") }
            repeat(1_000) { event("UID:m$it\nDTSTART:19700101T000000Z\nRRULE:FREQ=MINUTELY;BYSETPOS=-1") }
        }))
        assertEquals(0, r.events.size)
        assertEquals(2_000, r.stats.unsupportedRules)
    }

    @Test fun p05MinutelyAndHourlyShowDtstartOnly() {
        val r = complete(probe(HostileFeeds.calendar {
            event("UID:m\nDTSTART:20260310T150000Z\nRRULE:FREQ=MINUTELY")
            event("UID:h\nDTSTART:20260310T160000Z\nRRULE:FREQ=HOURLY;BYSETPOS=1")
        }))
        assertEquals(listOf(at("2026-03-10T15:00:00Z"), at("2026-03-10T16:00:00Z")), r.events.map { it.start })
        assertEquals(2, r.stats.unsupportedRules)
    }

    @Test fun p06TwentyKilobyteRruleIsUnsupported() {
        val rule = "FREQ=YEARLY;BYMONTHDAY=" + (1..3000).joinToString(",") { "${1 + it % 28}" } + ";BYDAY=" + List(3000) { "1MO" }.joinToString(",")
        assertTrue(rule.length > 20_000)
        val r = complete(probe(HostileFeeds.calendar { event("UID:y\nDTSTART:20260310T150000Z\nRRULE:$rule") }))
        assertEquals(listOf(at("2026-03-10T15:00:00Z")), r.events.map { it.start })
        assertEquals(1, r.stats.unsupportedRules)
    }

    /** The densest rule that fits 1 KiB: supported, so its per-period charge, not the line cap, must stop it. */
    private fun p7Rule(): String {
        val head = "FREQ=YEARLY;COUNT=1000000;BYMONTH=" + (1..12).joinToString(",") +
            ";BYMONTHDAY=" + ((1..31) + (-31..-1)).joinToString(",") + ";BYSETPOS=1,-1;BYDAY="
        val days = listOf("MO", "TU", "WE", "TH", "FR", "SA", "SU")
        val items = (1..53).flatMap { n -> days.flatMap { d -> listOf("$n$d", "-$n$d") } }
        val sb = StringBuilder(head)
        var first = true
        for (it in items) {
            val add = (if (first) "" else ",") + it
            if ("RRULE:".length + sb.length + add.length > IcsReader.MAX_RRULE_LINE) break
            sb.append(add)
            first = false
        }
        return sb.toString()
    }

    /**
     * Expectation changed by `count-walk-skips-active-series`: the COUNT is now found over the 400-year cycle with each
     * of the 14 year shapes counted once (charged), so the series fits its budget instead of being skipped. Its two
     * instances a year (BYSETPOS=1,-1: about 1 January and 31 December) are not in the March window.
     */
    @Test fun p07aDenseYearlyRuleWithinCapsIsChargedAndFitsItsSeriesBudget() {
        val rule = p7Rule()
        assertTrue("RRULE:$rule".length <= IcsReader.MAX_RRULE_LINE)
        assertTrue(IcsRules.parse(rule) is RuleSpec.Spec)
        val r = complete(probe(HostileFeeds.calendar { event("UID:p7\nDTSTART:19000101T090000Z\nRRULE:$rule") }))
        assertEquals(0, r.stats.skippedSeries)
        assertEquals(0, r.stats.unsupportedRules)
        assertEquals(0, r.events.size)
        assertTrue(r.stats.steps in 1_000..IcsParser.Limits().maxStepsPerSeries, "${r.stats.steps}")
    }

    /** 5M steps (manager decision 2026-10-31): 600 copies of P7 at about 9,350 steps each run them out. */
    @Test fun p07bSixHundredCopiesExhaustTheSteps() {
        val rule = p7Rule()
        val o = probe(HostileFeeds.calendar { repeat(600) { event("UID:p7-$it\nDTSTART:19000101T090000Z\nRRULE:$rule") } })
        assertEquals(ParseLimit.STEPS, exhausted(o))
    }

    @Test fun p08MillionEntryByDayLine() {
        val b = HostileFeeds.Bytes(5 shl 20).text(HostileFeeds.HEAD).line("BEGIN:VEVENT").line("UID:b")
            .line("DTSTART:20260310T150000Z").text("RRULE:FREQ=YEARLY;BYDAY=1MO").repeat(",1MO", 999_999).line("")
            .line("END:VEVENT").text(HostileFeeds.TAIL).bytes()
        assertTrue(b.size > 3_900_000)
        val r = complete(probe(b))
        assertEquals(listOf(at("2026-03-10T15:00:00Z")), r.events.map { it.start })
        assertEquals(1, r.stats.unsupportedRules)
    }

    @Test fun p09BareCrFeedHidingASecondlyVtimezone() {
        val bareCr = p1.replace("\r\n", "\r")
        assertTrue(bareCr.length < 400, "${bareCr.length}")
        assertEquals(RejectReason.LINE_ENDING, rejected(probe(bytes(bareCr))))
    }

    @Test fun p10SixtyThousandVeventsSeparatedByBareCr() {
        val b = HostileFeeds.Bytes(4 shl 20).text("BEGIN:VCALENDAR\rVERSION:2.0\r")
            .repeat("BEGIN:VEVENT\rUID:x\rDTSTART:20260310T150000Z\rEND:VEVENT\r", 60_000).text("END:VCALENDAR\r").bytes()
        assertEquals(RejectReason.LINE_ENDING, rejected(probe(b)))
    }

    @Test fun p11LfOnlyFeed() {
        val lf = HostileFeeds.calendar { event("UID:a\nDTSTART:20260310T150000Z\nSUMMARY:x") }.toString(Charsets.UTF_8).replace("\r\n", "\n")
        assertEquals(RejectReason.LINE_ENDING, rejected(probe(bytes(lf))))
    }

    private val vcal1 = "DAYLIGHT:TRUE;-04;19000101T000000;20991231T235959;EDT;EST\r\nTZ:-05\r\n"

    @Test fun p12Vcal10WithDaylightAndTz() {
        val ics = "BEGIN:VCALENDAR\r\nVERSION:1.0\r\n$vcal1" + "BEGIN:VEVENT\r\nUID:a\r\nDTSTART:20260310T150000Z\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n"
        assertEquals(RejectReason.VERSION, rejected(probe(bytes(ics))))
    }

    @Test fun p13Version20IgnoresDaylightAndTz() {
        val ev = "BEGIN:VEVENT\r\nUID:a\r\nDTSTART:20260310T090000\r\nSUMMARY:x\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n"
        val with = complete(probe(bytes("BEGIN:VCALENDAR\r\nVERSION:2.0\r\n$vcal1$ev")))
        val without = complete(probe(bytes("BEGIN:VCALENDAR\r\nVERSION:2.0\r\n$ev")))
        assertEquals(listOf(at("2026-03-10T13:00:00Z")), with.events.map { it.start }) // floating: device zone
        assertEquals(without.events, with.events)
    }

    @Test fun p14GoogleShapedNineMegabyteFeed() {
        val (body, expected) = HostileFeeds.google(vevents = 15_000, series = 300, descLen = 230)
        assertTrue(body.size in 8_500_000..10_485_760, "size ${body.size}")
        val r = complete(probe(body))
        assertEquals(15_000, r.stats.vevents)
        assertEquals(0, r.stats.skippedEvents + r.stats.unsupportedRules + r.stats.skippedSeries + r.stats.ignoredValues)
        assertTrue(!r.stats.truncated)
        val order = compareBy<EventInstance>({ startKey(it) }, { it.title }, { it.uid })
        assertEquals(expected.events.sortedWith(order), r.events.sortedWith(order))
        assertTrue(expected.events.size > 100, "expected ${expected.events.size}")
    }

    private fun startKey(e: EventInstance): Instant = when (val s = e.start) {
        is EventTime.At -> s.instant
        is EventTime.AllDay -> s.date.atStartOfDay(ny).toInstant()
    }

    @Test fun p15TwoThousandExdatesOkAndOneMoreSkipsTheEvent() {
        fun feed(n: Int) = HostileFeeds.calendar {
            line("BEGIN:VEVENT").line("UID:s").line("DTSTART:20260101T150000Z").line("RRULE:FREQ=DAILY").line("SUMMARY:Series")
            // n EXDATEs: the first is today's instance, the rest are seconds in 2025 (never instances)
            line("EXDATE:20260310T150000Z")
            repeat(n - 1) { line("EXDATE:20250101T%02d%02d%02dZ".format(it / 3600, it / 60 % 60, it % 60)) }
            line("END:VEVENT")
            event("UID:o\nDTSTART:20260311T170000Z\nSUMMARY:Other")
        }
        val ok = complete(probe(feed(2_000)))
        assertEquals(
            listOf(at("2026-03-09T15:00:00Z"), at("2026-03-11T15:00:00Z"), at("2026-03-11T17:00:00Z"), at("2026-03-12T15:00:00Z")),
            ok.events.map { it.start },
        )
        val over = complete(probe(feed(2_001)))
        assertEquals(1, over.stats.skippedEvents)
        assertEquals(listOf("Other"), over.events.map { it.title })
    }

    @Test fun p16SixHundredThousandRdates() {
        val oneLine = HostileFeeds.Bytes(12 shl 20).text(HostileFeeds.HEAD).line("BEGIN:VEVENT").line("UID:r")
            .line("DTSTART:20260310T150000Z").text("RDATE:20260310T150000Z").repeat(",20260311T150000Z", 599_999).line("")
            .line("END:VEVENT").text("BEGIN:VEVENT\r\nUID:o\r\nDTSTART:20260311T170000Z\r\nEND:VEVENT\r\n").text(HostileFeeds.TAIL).bytes()
        assertTrue(oneLine.size < 10_485_760)
        val a = complete(probe(oneLine))
        assertEquals(1, a.stats.skippedEvents)
        assertEquals(listOf(at("2026-03-11T17:00:00Z")), a.events.map { it.start })
        val lines = HostileFeeds.Bytes(10 shl 20).text(HostileFeeds.HEAD).line("BEGIN:VEVENT").line("UID:r")
            .line("DTSTART:20260310T150000Z").repeat("RDATE:20260311\r\n", 600_000)
            .line("END:VEVENT").text(HostileFeeds.TAIL).bytes()
        assertTrue(lines.size < 10_485_760)
        val b = complete(probe(lines))
        assertEquals(1, b.stats.skippedEvents)
        assertEquals(0, b.events.size)
    }

    @Test fun p17NineMegabyteLines() {
        assertEquals(8 * 1024, IcsReader.MAX_LINE) // the one line buffer
        fun feed(name: String, unit: String) = HostileFeeds.Bytes(10 shl 20).text(HostileFeeds.HEAD).line("BEGIN:VEVENT")
            .line("UID:big").line("DTSTART:20260310T150000Z").text(name).repeat(unit, 9_000_000 / unit.length).line("")
            .line("END:VEVENT").text(HostileFeeds.TAIL).bytes()
        val desc = complete(probe(feed("DESCRIPTION:", "abcdefghij")))
        assertEquals(listOf(at("2026-03-10T15:00:00Z")), desc.events.map { it.start })
        val summary = complete(probe(feed("SUMMARY:", "abcdefghé")))
        assertEquals("abcdefghé".repeat(30).take(200), summary.events.single().title)
        val dtstart = HostileFeeds.Bytes(10 shl 20).text(HostileFeeds.HEAD).line("BEGIN:VEVENT").line("UID:big")
            .text("DTSTART:20260310T150000Z").repeat("0", 9_000_000).line("").line("END:VEVENT").text(HostileFeeds.TAIL).bytes()
        val c = complete(probe(dtstart))
        assertEquals(1, c.stats.skippedEvents)
        assertEquals(0, c.events.size)
    }

    @Test fun p18TwoHundredThousandNestedComponents() {
        val b = HostileFeeds.Bytes(4 shl 20).text(HostileFeeds.HEAD).repeat("BEGIN:X-A\r\n", 200_000).bytes()
        assertEquals(RejectReason.NESTING, rejected(probe(b)))
        // depth 4 is allowed, 5 is not
        val four = HostileFeeds.calendar { line("BEGIN:X-A").line("BEGIN:X-B").line("BEGIN:X-C").line("END:X-C").line("END:X-B").line("END:X-A") }
        complete(probe(four))
        val five = HostileFeeds.calendar { line("BEGIN:X-A").line("BEGIN:X-B").line("BEGIN:X-C").line("BEGIN:X-D") }
        assertEquals(RejectReason.NESTING, rejected(probe(five)))
    }

    @Test fun p19MillionBySetPosValues() {
        val b = HostileFeeds.Bytes(9 shl 20).text(HostileFeeds.HEAD).line("BEGIN:VEVENT").line("UID:b")
            .line("DTSTART:20260310T150000Z").text("RRULE:FREQ=MONTHLY;BYDAY=MO,TU;BYSETPOS=1").repeat(",1", 999_999).line("")
            .line("END:VEVENT").text(HostileFeeds.TAIL).bytes()
        val r = complete(probe(b))
        assertEquals(listOf(at("2026-03-10T15:00:00Z")), r.events.map { it.start })
        assertEquals(1, r.stats.unsupportedRules)
    }

    @Test fun p20VtimezoneInsideAnXComponentIsNotInterpreted() {
        val r = complete(probe(HostileFeeds.calendar {
            line("BEGIN:X-WRAP").line("BEGIN:VTIMEZONE").line("TZID:Evil").line("BEGIN:STANDARD").line("DTSTART:19000101T000000")
                .line("RRULE:FREQ=SECONDLY").line("TZOFFSETFROM:+0000").line("TZOFFSETTO:+0100").line("END:STANDARD")
                .line("END:VTIMEZONE").line("END:X-WRAP")
            event("UID:a\nDTSTART;TZID=Evil:20260310T090000\nSUMMARY:x")
        }))
        assertEquals(listOf(at("2026-03-10T13:00:00Z")), r.events.map { it.start })
    }

    @Test fun p21SecondlyOverCrlf() {
        val r = complete(probe(HostileFeeds.calendar { event("UID:s\nDTSTART:20260310T150000Z\nRRULE:FREQ=SECONDLY;BYSECOND=0,1,2") }))
        assertEquals(listOf(at("2026-03-10T15:00:00Z")), r.events.map { it.start })
        assertEquals(1, r.stats.unsupportedRules)
    }

    @Test fun p22VtimezoneHiddenFourWays() {
        val tz = "TZID:Evil\r\nBEGIN:STANDARD\r\nDTSTART:19000101T000000\r\nRRULE:FREQ=SECONDLY\r\nTZOFFSETFROM:+0000\r\n" +
            "TZOFFSETTO:+0100\r\nEND:STANDARD\r\n"
        fun ics(begin: String) = bytes("BEGIN:VCALENDAR\r\nVERSION:2.0\r\n$begin\r\n${tz}END:VTIMEZONE\r\nBEGIN:VEVENT\r\nUID:a\r\n" +
            "DTSTART;TZID=Evil:20260310T090000\r\nSUMMARY:x\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n")
        for (b in listOf("begin:vtimezone", "BEGIN:VTIME\r\n ZONE")) {
            assertEquals(listOf(at("2026-03-10T13:00:00Z")), complete(probe(ics(b))).events.map { it.start }, b)
        }
        for (b in listOf("x.BEGIN:VTIMEZONE", "BEGIN;X=1:VTimeZone")) assertEquals(RejectReason.STRUCTURE, rejected(probe(ics(b))), b)
    }

    @Test
    @Tag("deadline-drops-recurring-silently")
    fun p23CountWalksFromYearOneExhaustTheSteps() {
        val o = probe(HostileFeeds.calendar {
            repeat(3_000) { event("UID:w$it\nDTSTART:00010101T090000Z\nRRULE:FREQ=DAILY;BYMONTHDAY=13;COUNT=1000000000") }
            event("UID:real\nDTSTART:20260310T170000Z\nSUMMARY:Real")
        })
        assertEquals(ParseLimit.STEPS, exhausted(o))
        // The shape P23 first used, DAILY;BYDAY=MO, is no longer a walk: counted over its 7-day cycle, every copy
        // shows Monday 9 March (the cap keeps 500, focus first, so "Real" on Tuesday stays).
        val r = complete(probe(HostileFeeds.calendar {
            repeat(3_000) { event("UID:w$it\nDTSTART:00010101T090000Z\nRRULE:FREQ=DAILY;BYDAY=MO;COUNT=1000000000") }
            event("UID:real\nDTSTART:20260310T170000Z\nSUMMARY:Real")
        }))
        assertEquals(IcsParser.MAX_INSTANCES, r.events.size)
        assertTrue(r.events.any { it.uid == "real" })
        assertEquals(setOf(at("2026-03-09T09:00:00Z"), at("2026-03-10T17:00:00Z")), r.events.map { it.start }.toSet())
    }

    @Test fun p24HundredYearInstancesAreSkippedByTheSeriesBudget() {
        val r = complete(probe(HostileFeeds.calendar {
            event("UID:l\nDTSTART:19260101T000000Z\nDTEND:20260101T000000Z\nRRULE:FREQ=DAILY")
            event("UID:o\nDTSTART:20260310T170000Z")
        }))
        assertEquals(1, r.stats.skippedSeries)
        assertEquals(listOf("o"), r.events.map { it.uid })
    }

    @Test fun p25ByHourMinuteSecondProduct86400() {
        val rule = "FREQ=DAILY;BYHOUR=${(0..23).joinToString(",")};BYMINUTE=${(0..59).joinToString(",")};BYSECOND=${(0..59).joinToString(",")}"
        val r = complete(probe(HostileFeeds.calendar { event("UID:d\nDTSTART:20260310T150000Z\nRRULE:$rule") }))
        assertEquals(listOf(at("2026-03-10T15:00:00Z")), r.events.map { it.start })
        assertEquals(1, r.stats.unsupportedRules)
    }

    @Test fun p26VeventCap() {
        fun feed(n: Int) = HostileFeeds.Bytes(4 shl 20).text(HostileFeeds.HEAD)
            .repeat("BEGIN:VEVENT\r\nUID:v\r\nDTSTART:20200101T000000Z\r\nEND:VEVENT\r\n", n).text(HostileFeeds.TAIL).bytes()
        assertEquals(ParseLimit.VEVENTS, exhausted(probe(feed(50_001))))
        assertEquals(50_000, complete(probe(feed(50_000))).stats.vevents)
    }

    @Test fun p27KeptSeriesCap() {
        fun feed(n: Int) = HostileFeeds.calendar { repeat(n) { event("UID:w$it\nDTSTART:20260101T150000Z\nRRULE:FREQ=WEEKLY") } }
        assertEquals(ParseLimit.SERIES, exhausted(probe(feed(5_001))))
        val r = complete(probe(feed(5_000)))
        assertEquals(IcsParser.MAX_INSTANCES, r.events.size) // 5,000 Thursday (Mar 12) instances, capped
        assertTrue(r.stats.truncated)
    }

    @Test
    @Tag("H8")
    fun p28FiftyThousandDistinctLongTzids() {
        val b = HostileFeeds.Bytes(12 shl 20).text(HostileFeeds.HEAD)
        repeat(50_000) { i ->
            // 64 one-char segments ("a/b/.../z") = 127 chars, plus one more: 128. The first 4 spell i in base 26.
            val segs = (0 until 64).map { k -> if (k < 4) 'a' + (i / intPow26(k)) % 26 else 'z' }.joinToString("/")
            val tzid = segs + "z"
            check(tzid.length == 128)
            b.line("BEGIN:VEVENT").line("UID:t$i").line("DTSTART;TZID=$tzid:20260310T090000").line("END:VEVENT")
        }
        val r = complete(probe(b.text(HostileFeeds.TAIL).bytes()))
        assertEquals(50_000, r.stats.vevents)
        assertEquals(setOf(at("2026-03-10T13:00:00Z")), r.events.map { it.start }.toSet()) // all in the device zone
    }

    private fun intPow26(k: Int): Int = (0 until k).fold(1) { a, _ -> a * 26 }

    @Test
    @Tag("H9")
    fun p29OutOfRangeByMonthIsUnsupportedNotEveryMonth() {
        val r = complete(probe(HostileFeeds.calendar { event("UID:y\nDTSTART:20250310T150000Z\nRRULE:FREQ=YEARLY;BYMONTH=13") }))
        assertEquals(0, r.events.size) // DTSTART (2025) only; never the 2026 instance
        assertEquals(1, r.stats.unsupportedRules)
        val inWindow = complete(probe(HostileFeeds.calendar { event("UID:y\nDTSTART:20260310T150000Z\nRRULE:FREQ=MONTHLY;BYMONTH=13") }))
        assertEquals(listOf(at("2026-03-10T15:00:00Z")), inWindow.events.map { it.start })
    }

    @Test fun p30Framing() {
        val ev = "BEGIN:VEVENT\r\nUID:a\r\nDTSTART:20260310T150000Z\r\nEND:VEVENT\r\n"
        val cal = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\n${ev}END:VCALENDAR\r\n"
        complete(probe(bytes(cal)))
        complete(probe(bytes("$cal\r\n\r\n"))) // empty lines after END are allowed
        for (bad in listOf(
            cal + cal,
            cal + "X-AFTER:1\r\n",
            "BEGIN:VCALENDAR\r\nVERSION:2.0\r\n$ev",
            "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nBEGIN:VEVENT\r\nUID:a\r\nBEGIN:VALARM\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n",
            "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nBEGIN:VCALENDAR\r\nEND:VCALENDAR\r\nEND:VCALENDAR\r\n",
            "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nEND;X=1:VCALENDAR\r\n",
        )) {
            assertEquals(RejectReason.STRUCTURE, rejected(probe(bytes(bad))), bad)
        }
        assertEquals(RejectReason.NOT_VCALENDAR, rejected(probe(bytes("X-FIRST:1\r\n$cal"))))
        assertEquals(RejectReason.NOT_VCALENDAR, rejected(probe(bytes(""))))
        // an optional UTF-8 BOM is fine
        complete(probe(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + bytes(cal)))
    }

    @Test fun p31VersionAndCalscale() {
        val ev = "BEGIN:VEVENT\r\nUID:a\r\nDTSTART:20260310T150000Z\r\nEND:VEVENT\r\n"
        assertEquals(RejectReason.VERSION, rejected(probe(bytes("BEGIN:VCALENDAR\r\n${ev}END:VCALENDAR\r\n"))))
        assertEquals(RejectReason.VERSION, rejected(probe(bytes("BEGIN:VCALENDAR\r\nPRODID:x\r\nEND:VCALENDAR\r\n"))))
        assertEquals(RejectReason.VERSION, rejected(probe(bytes("BEGIN:VCALENDAR\r\n${ev}VERSION:2.0\r\nEND:VCALENDAR\r\n"))))
        assertEquals(RejectReason.CALSCALE, rejected(probe(bytes("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nCALSCALE:HEBREW\r\n${ev}END:VCALENDAR\r\n"))))
        complete(probe(bytes("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nCALSCALE:gregorian\r\n${ev}END:VCALENDAR\r\n")))
    }

    // ------------------------------------------------------------ P33-P36 (T2b reviews, 2026-10-31)

    /**
     * P33 `ics-rule-setup-uncharged` / `uncharged-per-vevent-work`: per-VEVENT rule setup is charged before it is done,
     * and a rule that cannot reach the window is never built (no Rule, no times of day). Steps > 0 or Exhausted.
     */
    private val h10 = (0..9).joinToString(",")

    @Test
    @Tag("ics-rule-setup-uncharged")
    fun p33aFiftyThousandUnreachableDenseRulesAreChargedNotBuilt() {
        // code review: 50,000 VEVENTs starting 2030, 1,000 times of day each (about 7.75 MB)
        val review = HostileFeeds.Bytes(9 shl 20).text(HostileFeeds.HEAD)
            .repeat("BEGIN:VEVENT\r\nUID:r\r\nDTSTART:20300101T000000Z\r\nRRULE:FREQ=DAILY;BYHOUR=$h10;BYMINUTE=$h10;BYSECOND=$h10\r\nEND:VEVENT\r\n", 50_000)
            .text(HostileFeeds.TAIL).bytes()
        assertTrue(review.size > 7_000_000, "${review.size}")
        val a = complete(probe(review))
        assertEquals(50_000, a.stats.vevents)
        assertTrue(a.stats.steps >= 50_000L * 31, "${a.stats.steps}") // 1 + 30 BY values per rule
    }

    @Test
    @Tag("uncharged-per-vevent-work")
    fun p33bThirtyFiveThousandRulesEndingIn1970AreChargedNotBuilt() {
        // security review: about 35,000 VEVENTs ending in 1970, 984 times of day each (about 10.4 MB)
        val hours = (0..23).joinToString(",")
        val mins = (0..40).joinToString(",")
        val sec = HostileFeeds.Bytes(11 shl 20).text(HostileFeeds.HEAD)
        var n = 0
        while (sec.size < 10_400_000 && n < 50_000) {
            sec.line("BEGIN:VEVENT").line("UID:$n").line("DTSTART:19700101T000000")
                .line("RRULE:FREQ=DAILY;UNTIL=19700102T000000Z;BYHOUR=$hours;BYMINUTE=$mins").line("END:VEVENT")
            n++
        }
        val b = probe(sec.text(HostileFeeds.TAIL).bytes())
        assertTrue(b is ParseOutcome.Exhausted || (b as ParseOutcome.Complete).stats.steps >= n * 66L, "$b")
    }

    @Test
    @Tag("ics-rule-setup-uncharged")
    fun p33cFiftyThousandReachableDenseRulesExhaustTheSteps() {
        // the same dense rule reaching the window: built, its 1,000 times charged, and the steps run out
        val reach = HostileFeeds.Bytes(9 shl 20).text(HostileFeeds.HEAD)
            .repeat("BEGIN:VEVENT\r\nUID:r\r\nDTSTART:20260310T000000Z\r\nRRULE:FREQ=DAILY;BYHOUR=$h10;BYMINUTE=$h10;BYSECOND=$h10\r\nEND:VEVENT\r\n", 50_000)
            .text(HostileFeeds.TAIL).bytes()
        assertEquals(ParseLimit.STEPS, exhausted(probe(reach)))
    }

    private val real = "BEGIN:VEVENT\r\nUID:real\r\nDTSTART:20260310T190000Z\r\nDTEND:20260310T200000Z\r\nSUMMARY:REAL\r\nEND:VEVENT\r\n"

    /** One-minute instances of one master today local ([hours] x [mins]), and RECURRENCE-ID cancellations of the first [cancel]. */
    private fun spam(
        cancel: Int,
        overridesFirst: Boolean = false,
        uid: String = "spam",
        hours: String = (0..14).joinToString(","),
        mins: String = (0..59).joinToString(","),
        until: String = "20260310T235959Z",
    ): String {
        val master = "BEGIN:VEVENT\r\nUID:$uid\r\nDTSTART;TZID=America/New_York:20260310T000000\r\n" +
            "DTEND;TZID=America/New_York:20260310T000100\r\nRRULE:FREQ=DAILY;UNTIL=$until;BYHOUR=$hours;BYMINUTE=$mins\r\n" +
            "SUMMARY:$uid\r\nEND:VEVENT\r\n"
        val overrides = StringBuilder()
        for (k in 0 until cancel) {
            val t = "20260310T%02d%02d00".format(k / 60, k % 60)
            overrides.append("BEGIN:VEVENT\r\nUID:$uid\r\nRECURRENCE-ID;TZID=America/New_York:$t\r\n")
                .append("DTSTART;TZID=America/New_York:$t\r\nSTATUS:CANCELLED\r\nEND:VEVENT\r\n")
        }
        return if (overridesFirst) overrides.toString() + master else master + overrides
    }

    private fun feed(vararg parts: String) = bytes("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:x\r\n" + parts.joinToString("") + "END:VCALENDAR\r\n")

    /**
     * P34 `instance-cap-removal-order` (security #1, then `instance-cap-exhausted-one-invite` and
     * `focus-cap-per-range-not-per-day`): removal keys come from a first pass, so a late RECURRENCE-ID can never
     * remove a kept instance after the cap cut; and one UID gets at most 48 focus instances per local day.
     */
    @Test
    @Tag("instance-cap-removal-order")
    fun p34aDenseSeriesWithCancellationsNeverHidesTheRealEvent() {
        // probe A (security): 900 instances today, the first 600 cancelled after the master
        val after = complete(probe(feed(real, spam(cancel = 600))))
        assertTrue(after.events.any { it.title == "REAL" })
        assertEquals(IcsParser.FOCUS_PER_UID_PER_DAY, after.events.count { it.uid == "spam" })
        assertEquals(at("2026-03-10T14:00:00Z"), after.events.first { it.uid == "spam" }.start) // 10:00 EDT, the first not cancelled
        // overrides before their master: the same answer
        assertEquals(after.events, complete(probe(feed(real, spam(cancel = 600, overridesFirst = true)))).events)
        // a dense series alone: 48 today, the real event kept
        val alone = complete(probe(feed(real, spam(cancel = 0))))
        assertTrue(alone.events.any { it.title == "REAL" })
        assertEquals(IcsParser.FOCUS_PER_UID_PER_DAY, alone.events.count { it.uid == "spam" })
        assertTrue(alone.stats.truncated)
        // the 500 cap cut by distinct one-off events, then a cancellation of the earliest: removed before the cut, so
        // the 500 kept are the best 500 of the rest, the same in either order
        val ones = (0 until 600).joinToString("") { "BEGIN:VEVENT\r\nUID:o$it\r\nDTSTART:20260310T%02d%02d00Z\r\nEND:VEVENT\r\n".format(it / 60, it % 60) }
        val cancel = "BEGIN:VEVENT\r\nUID:o0\r\nRECURRENCE-ID:20260310T000000Z\r\nDTSTART:20260310T000000Z\r\nSTATUS:CANCELLED\r\nEND:VEVENT\r\n"
        val late = complete(probe(feed(ones, cancel)))
        assertEquals(IcsParser.MAX_INSTANCES, late.events.size)
        assertTrue(late.events.none { it.uid == "o0" })
        assertEquals(late.events, complete(probe(feed(cancel, ones))).events)
    }

    /** Probe E (security delta #1): one dense invite plus one moved instance is Complete, real events shown. */
    @Test
    @Tag("instance-cap-exhausted-one-invite")
    fun p34bOneDenseInviteWithAMovedInstanceIsComplete() {
        val hours = (0..23).joinToString(",")
        val moved = { t: String -> "BEGIN:VEVENT\r\nUID:s\r\nRECURRENCE-ID;TZID=America/New_York:20260310T$t\r\n" +
            "DTSTART;TZID=America/New_York:20300310T$t\r\nSUMMARY:s\r\nEND:VEVENT\r\n" }
        // every 20 minutes today and tomorrow (72 a day), 01:00 today moved to 2030: 48 a day kept
        val e = complete(probe(feed(real, spam(0, uid = "s", hours = hours, mins = "0,20,40", until = "20260312T035959Z"), moved("010000"))))
        assertTrue(e.events.any { it.title == "REAL" })
        assertEquals(2 * IcsParser.FOCUS_PER_UID_PER_DAY, e.events.count { it.uid == "s" })
        assertTrue(e.events.none { it.start == at("2026-03-10T05:00:00Z") }) // the moved one
        // every 30 minutes (48 a day, at the cap) with 01:30 moved: 47 today, 48 tomorrow, none cut
        val c = complete(probe(feed(real, spam(0, uid = "s", hours = hours, mins = "0,30", until = "20260312T035959Z"), moved("013000"))))
        assertTrue(c.events.any { it.title == "REAL" })
        assertEquals(95, c.events.count { it.uid == "s" })
        assertTrue(!c.stats.truncated)
        // code review: a 20-minute series and an unrelated weekly series whose override follows its master
        val weekly = "BEGIN:VEVENT\r\nUID:w\r\nDTSTART:20260303T150000Z\r\nDTEND:20260303T160000Z\r\nRRULE:FREQ=WEEKLY\r\nSUMMARY:w\r\nEND:VEVENT\r\n" +
            "BEGIN:VEVENT\r\nUID:w\r\nRECURRENCE-ID:20260310T150000Z\r\nDTSTART:20260310T170000Z\r\nDTEND:20260310T180000Z\r\nSUMMARY:w moved\r\nEND:VEVENT\r\n"
        val r = complete(probe(feed(real, spam(0, uid = "s", hours = hours, mins = "0,20,40", until = "20260312T035959Z"), weekly)))
        assertEquals(listOf(at("2026-03-10T17:00:00Z")), r.events.filter { it.uid == "w" }.map { it.start })
        assertTrue(r.events.any { it.title == "REAL" })
    }

    /** Probe F (security delta #2): 8 UIDs x 84 instances before 15:00 today; each gets 48, the 15:00 event stays. */
    @Test
    @Tag("focus-cap-per-range-not-per-day")
    fun p34cManyDenseUidsTodayCannotPushOutTheRealEvent() {
        val hours = (0..13).joinToString(",")
        val r = complete(probe(feed(real, *Array(8) { spam(0, uid = "u$it", hours = hours, mins = "0,10,20,30,40,50") })))
        assertTrue(r.events.any { it.title == "REAL" }, "${r.events.size}")
        for (u in 0 until 8) assertEquals(IcsParser.FOCUS_PER_UID_PER_DAY, r.events.count { it.uid == "u$u" })
    }

    /**
     * File order never changes the answer: the same VEVENTs (masters, one-offs, moved and cancelled overrides before
     * or after their master, a dense series over the per-day cap with cancellations) in random orders.
     */
    @Test
    @Tag("instance-cap-removal-order")
    fun p34dVeventOrderNeverChangesTheResult() {
        val rnd = Random(3434)
        repeat(20) { seed ->
            val vs = ArrayList<String>()
            fun v(body: String) { vs += "BEGIN:VEVENT\r\n$body\r\nEND:VEVENT\r\n" }
            v("UID:real\r\nDTSTART:20260310T190000Z\r\nDTEND:20260310T200000Z\r\nSUMMARY:REAL")
            repeat(rnd.nextInt(5, 40)) { i ->
                val h = rnd.nextInt(0, 24)
                val freq = listOf("DAILY", "WEEKLY", "DAILY;INTERVAL=2")[rnd.nextInt(3)]
                val d = rnd.nextInt(1, 10)
                v("UID:m$i\r\nDTSTART:202603%02dT%02d0000Z\r\nDTEND:202603%02dT%02d3000Z\r\nRRULE:FREQ=$freq\r\nSUMMARY:m$i".format(d, h, d, h))
                if (rnd.nextBoolean()) {
                    val day = rnd.nextInt(9, 13)
                    val ovr = if (rnd.nextBoolean()) "STATUS:CANCELLED" else "SUMMARY:m$i moved"
                    v("UID:m$i\r\nRECURRENCE-ID:202603%02dT%02d0000Z\r\nDTSTART:202603%02dT%02d1500Z\r\n$ovr".format(day, h, day, (h + 1) % 24))
                }
            }
            repeat(rnd.nextInt(0, 30)) { v("UID:o$it\r\nDTSTART:202603%02dT%02d%02d00Z\r\nSUMMARY:o$it".format(rnd.nextInt(9, 13), rnd.nextInt(24), rnd.nextInt(60))) }
            for (k in 0 until rnd.nextInt(0, 100)) {
                val t = "20260310T%02d%02d00".format(k / 60, k % 60)
                v("UID:spam\r\nRECURRENCE-ID;TZID=America/New_York:$t\r\nDTSTART;TZID=America/New_York:$t\r\nSTATUS:CANCELLED")
            }
            vs += spam(0)
            val first = complete(probe(feed(*vs.toTypedArray())))
            assertTrue(first.events.any { it.title == "REAL" }, "seed $seed")
            repeat(10) {
                vs.shuffle(rnd)
                assertEquals(first.events, complete(probe(feed(*vs.toTypedArray()))).events, "seed $seed")
            }
        }
    }

    /** The keys pass is charged (one step per key) and capped by the VEVENT cap: 50,000 overrides, then one more. */
    @Test
    @Tag("instance-cap-removal-order")
    fun p34eKeysPassIsChargedAndCapped() {
        fun overrides(n: Int) = HostileFeeds.Bytes(8 shl 20).text(HostileFeeds.HEAD)
            .repeat("BEGIN:VEVENT\r\nUID:k\r\nRECURRENCE-ID:20260310T150000Z\r\nDTSTART:20260310T150000Z\r\nSTATUS:CANCELLED\r\nEND:VEVENT\r\n", n)
            .text(HostileFeeds.TAIL).bytes()
        val r = complete(probe(overrides(50_000)))
        assertTrue(r.stats.steps >= 50_000, "${r.stats.steps}")
        assertEquals(ParseLimit.VEVENTS, exhausted(probe(overrides(50_001))))
    }

    /** P35 `exdate-uncharged` (security #3): 2,000 EXDATEs per VEVENT under more than 256 distinct long TZIDs. */
    @Test
    @Tag("exdate-uncharged")
    fun p35ExdateFloodWithDistinctTzidsIsCharged() {
        val b = HostileFeeds.Bytes(11 shl 20).text(HostileFeeds.HEAD)
        var v = 0
        var values = 0L
        val item = "19700101T090000"
        while (b.size < 10_300_000) {
            val tz = "/x$v/" + "a".repeat(100) + "/America/New_York"
            b.line("BEGIN:VEVENT").line("UID:e$v").line("DTSTART;TZID=America/New_York:19700101T090000")
            repeat(4) {
                val k = 7_900 / (item.length + 1)
                b.line("EXDATE;TZID=$tz:" + List(k) { item }.joinToString(","))
                values += k
            }
            b.line("END:VEVENT")
            v++
        }
        assertTrue(v > 256, "$v")
        val r = complete(probe(b.text(HostileFeeds.TAIL).bytes()))
        assertTrue(r.stats.steps >= values, "${r.stats.steps} < $values")
    }

    /**
     * P36 `count-walk-skips-active-series` (code review should-fix 1): COUNT rules with uneven periods, viewed on
     * 2026-03-31, show their instance (they were skipped by the series budget and silently cached without it).
     */
    @Test
    @Tag("count-walk-skips-active-series")
    fun p36UnevenCountRulesFrom2015ShowTheirInstance() {
        val w = agendaWindow(Instant.parse("2026-03-31T14:00:00Z"), ny)
        fun run(rule: String, start: String): ParseOutcome.Complete {
            val body = HostileFeeds.calendar { event("UID:c\nDTSTART;TZID=America/New_York:$start\nRRULE:$rule") }
            return complete(assertTimeoutPreemptively(Duration.ofSeconds(1), ThrowingSupplier {
                IcsParser.parse(body, w, ny, agendaFocus(w, ny)) { 0L }
            }))
        }
        for ((rule, start) in listOf(
            "FREQ=MONTHLY;BYMONTHDAY=31;COUNT=500" to "20150131T090000",
            "FREQ=MONTHLY;BYDAY=MO,TU,WE,TH,FR;BYSETPOS=-1;COUNT=300" to "20150130T090000",
        )) {
            val r = run(rule, start)
            assertEquals(listOf(at("2026-03-31T13:00:00Z")), r.events.map { it.start }, rule)
            assertEquals(0, r.stats.skippedSeries, rule)
        }
    }

    /** P32: seeded mutations of the P14 shape (at 1/100 scale so 2,000 runs fit the suite); never throws, < 200 ms. */
    @Test
    @Tag("one-bad-event-fails-feed")
    fun p32SeededFuzz() {
        val (base, _) = HostileFeeds.google(vevents = 150, series = 30, descLen = 230, seed = 32)
        val rnd = Random(3232)
        var worst = 0L
        val seen = HashMap<String, Int>()
        repeat(2_000) { iter ->
            val m = mutate(base, rnd)
            val t0 = System.nanoTime()
            val o = try {
                IcsParser.parse(m, window, ny, agendaFocus(window, ny)) { 0L }
            } catch (t: Throwable) {
                fail("iteration $iter threw ${t.javaClass.name}", t)
            }
            val ms = (System.nanoTime() - t0) / 1_000_000
            if (iter > 20) worst = maxOf(worst, ms) // after JIT warm-up
            assertTrue(ms < 200, "iteration $iter took $ms ms")
            val k = when (o) {
                is ParseOutcome.Complete -> "Complete"
                is ParseOutcome.Exhausted -> "Exhausted(${o.limit})"
                is ParseOutcome.Rejected -> "Rejected(${o.reason})"
            }
            seen[k] = (seen[k] ?: 0) + 1
        }
        println("p32: worst $worst ms; outcomes $seen")
        assertTrue((seen["Complete"] ?: 0) > 200, "$seen")
    }

    private fun mutate(base: ByteArray, rnd: Random): ByteArray {
        val text = base.toString(Charsets.ISO_8859_1) // byte-preserving
        val lines = text.split("\r\n").toMutableList()
        repeat(rnd.nextInt(1, 4)) {
            when (rnd.nextInt(7)) {
                0 -> { // byte flips
                    val b = lines.joinToString("\r\n").toByteArray(Charsets.ISO_8859_1)
                    repeat(rnd.nextInt(1, 6)) { b[rnd.nextInt(b.size)] = rnd.nextInt(256).toByte() }
                    return b
                }
                1 -> { val i = rnd.nextInt(lines.size); repeat(rnd.nextInt(1, 50)) { lines.add(i, lines[i]) } } // line duplication
                2 -> { val b = lines.joinToString("\r\n").toByteArray(Charsets.ISO_8859_1); return b.copyOf(rnd.nextInt(b.size)) } // truncation
                3 -> { val i = rnd.nextInt(lines.size); lines[i] = lines[i] + listOf("\r", "\n", "\rX", "\nX")[rnd.nextInt(4)] } // CR/LF injection
                4 -> { // huge numbers
                    val i = rnd.nextInt(lines.size)
                    lines[i] = lines[i].replace(Regex("[0-9]+")) { if (rnd.nextInt(3) == 0) "9".repeat(rnd.nextInt(5, 40)) else it.value }
                }
                5 -> { // random RRULE parts
                    val i = lines.indexOfFirst { it.startsWith("RRULE:") }.takeIf { it >= 0 } ?: rnd.nextInt(lines.size)
                    val parts = listOf("FREQ=SECONDLY", "FREQ=YEARLY", "INTERVAL=0", "INTERVAL=99999", "COUNT=1000000000",
                        "BYDAY=53MO,-53SU,1TU", "BYMONTHDAY=31,-31,15", "BYSETPOS=366,-366", "BYMONTH=2", "BYHOUR=0,23",
                        "BYMINUTE=0,59", "BYSECOND=60", "BYWEEKNO=1", "WKST=XX", "UNTIL=99991231T235959Z", "X-Y=1", "FREQ=DAILY")
                    lines[i] = "RRULE:" + (1..rnd.nextInt(1, 6)).joinToString(";") { parts[rnd.nextInt(parts.size)] }
                }
                else -> { val i = rnd.nextInt(lines.size); lines.removeAt(i) }
            }
        }
        return lines.joinToString("\r\n").toByteArray(Charsets.ISO_8859_1)
    }
}
