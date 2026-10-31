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
 * The hostile probes P1-P32 of docs/tasks/tv-home-m1.md (T2b acceptance tests). Run by `:core:hostileTest` in a
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

    @Test fun p07aDenseYearlyRuleWithinCapsIsSkippedByItsSeriesBudget() {
        val rule = p7Rule()
        assertTrue("RRULE:$rule".length <= IcsReader.MAX_RRULE_LINE)
        assertTrue(IcsRules.parse(rule) is RuleSpec.Spec)
        val r = complete(probe(HostileFeeds.calendar { event("UID:p7\nDTSTART:19000101T090000Z\nRRULE:$rule") }))
        assertEquals(1, r.stats.skippedSeries)
        assertEquals(0, r.stats.unsupportedRules)
        assertEquals(0, r.events.size)
    }

    @Test fun p07bHundredCopiesExhaustTheSteps() {
        val rule = p7Rule()
        val o = probe(HostileFeeds.calendar { repeat(100) { event("UID:p7-$it\nDTSTART:19000101T090000Z\nRRULE:$rule") } })
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
            repeat(3_000) { event("UID:w$it\nDTSTART:00010101T090000Z\nRRULE:FREQ=DAILY;BYDAY=MO;COUNT=1000000000") }
            event("UID:real\nDTSTART:20260310T170000Z\nSUMMARY:Real")
        })
        assertEquals(ParseLimit.STEPS, exhausted(o))
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
