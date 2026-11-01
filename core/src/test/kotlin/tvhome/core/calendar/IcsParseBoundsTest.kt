package tvhome.core.calendar

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.function.ThrowingSupplier
import tvhome.core.agenda.agendaFocus
import tvhome.core.agenda.agendaWindow
import tvhome.core.calendar.HostileFeeds.event
import tvhome.core.state.CachedAgenda
import java.io.File
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.random.Random

/** Deadline, interrupt, round-trip, iteration-bound and policy tests for T2b.1 (docs/tasks/tv-home-m1.md). */
class IcsParseBoundsTest {
    private val ny = ZoneId.of("America/New_York")
    private val now = Instant.parse("2026-03-10T14:00:00Z")
    private val window = agendaWindow(now, ny)

    private fun parse(body: ByteArray, nanoTime: () -> Long = { 0L }, limits: IcsParser.Limits = IcsParser.Limits()) =
        IcsParser.parse(body, window, ny, agendaFocus(window, ny), limits, nanoTime)

    private fun complete(o: ParseOutcome) = o as? ParseOutcome.Complete ?: fail("expected Complete, got $o")
    private fun at(s: String) = EventTime.At(Instant.parse(s))

    /** A fake clock: reads are counted; from read number [crossAt] on it is past any deadline. */
    private class FakeClock(private val crossAt: Int = Int.MAX_VALUE, private val onRead: (Int) -> Unit = {}) {
        var reads = 0
        val nanoTime: () -> Long = {
            reads++
            onRead(reads)
            if (reads >= crossAt) 3_600_000_000_000L else 0L
        }
    }

    /** About 10 MiB: one-off events in the window, each with a long DESCRIPTION (opaque bytes to tokenize). */
    private fun tenMiB(): ByteArray = HostileFeeds.calendar {
        var i = 0
        while (size < 10_400_000) {
            line("BEGIN:VEVENT").line("UID:e$i").line("DTSTART:20260310T150000Z").text("DESCRIPTION:").repeat("x", 8_000).line("")
                .line("END:VEVENT")
            i++
        }
    }

    // ------------------------------------------------------------------ D1, D2

    @Test
    @Tag("deadline-drops-recurring-silently")
    fun d1aDeadlineWhileTokenizingIsExhaustedAndPolledEvery64KiB() {
        val body = tenMiB()
        assertTrue(body.size in 10_000_000..10_485_760)
        val never = FakeClock()
        complete(parse(body, never.nanoTime))
        assertTrue(never.reads >= body.size / IcsReader.POLL_BYTES, "clock read ${never.reads} times for ${body.size} B")
        // crossing a third of the way in: never a Complete
        val c = FakeClock(crossAt = never.reads / 3)
        val o = parse(body, c.nanoTime)
        assertEquals(ParseLimit.DEADLINE, (o as? ParseOutcome.Exhausted)?.limit, o.toString())
    }

    @Test
    @Tag("deadline-drops-recurring-silently")
    fun d1bDeadlineDuringExpansionIsExhaustedNeverAShorterComplete() {
        val body = HostileFeeds.calendar {
            repeat(50) { event("UID:o$it\nDTSTART:20260310T1${it % 10}0000Z\nSUMMARY:One-off $it") }
            repeat(3_000) { event("UID:s$it\nDTSTART:20250101T080000Z\nRRULE:FREQ=DAILY;BYHOUR=8,9,10\nSUMMARY:Series $it") }
        }
        val bytePolls = body.size / IcsReader.POLL_BYTES + 2 // entry read plus the byte polls
        val never = FakeClock()
        val full = complete(parse(body, never.nanoTime))
        assertTrue(never.reads > bytePolls + 10, "reads ${never.reads}: the step polls must dominate")
        assertTrue(full.events.size == IcsParser.MAX_INSTANCES)
        val c = FakeClock(crossAt = (bytePolls + never.reads) / 2)
        val o = parse(body, c.nanoTime)
        assertEquals(ParseLimit.DEADLINE, (o as? ParseOutcome.Exhausted)?.limit, o.toString())
    }

    private fun interruptedWithin100ms(body: ByteArray, interruptAtRead: Int) {
        var interruptedAt = 0L
        val clock = FakeClock { n ->
            if (n == interruptAtRead) {
                interruptedAt = System.nanoTime()
                Thread.currentThread().interrupt()
            }
        }
        val thrown = assertTimeoutPreemptively(Duration.ofSeconds(5), ThrowingSupplier {
            val t = runCatching { parse(body, clock.nanoTime) }.exceptionOrNull()
            Thread.interrupted() // clear the flag for the runner
            t
        })
        val ms = (System.nanoTime() - interruptedAt) / 1_000_000
        assertTrue(thrown is InterruptedException, thrown.toString())
        assertTrue(ms < 100, "ended $ms ms after the interrupt")
    }

    @Test
    @Tag("cancel-returns-network-error")
    fun d2InterruptDuringTokenizingEndsWithin100ms() {
        interruptedWithin100ms(tenMiB(), interruptAtRead = 20)
    }

    @Test
    @Tag("cancel-returns-network-error")
    fun d2InterruptDuringExpansionEndsWithin100ms() {
        val body = HostileFeeds.calendar {
            repeat(3_000) { event("UID:w$it\nDTSTART:00010101T090000Z\nRRULE:FREQ=DAILY;BYMONTHDAY=13;COUNT=1000000000") }
        }
        val unbounded = IcsParser.Limits(maxSteps = Long.MAX_VALUE, maxStepsPerSeries = Long.MAX_VALUE)
        var interruptedAt = 0L
        val clock = FakeClock { n ->
            if (n == 1_000) {
                interruptedAt = System.nanoTime()
                Thread.currentThread().interrupt()
            }
        }
        val thrown = assertTimeoutPreemptively(Duration.ofSeconds(5), ThrowingSupplier {
            val t = runCatching { parse(body, clock.nanoTime, unbounded) }.exceptionOrNull()
            Thread.interrupted()
            t
        })
        assertTrue(thrown is InterruptedException, thrown.toString())
        assertTrue((System.nanoTime() - interruptedAt) / 1_000_000 < 100)
    }

    // ------------------------------------------------------------------ I1

    /** I1: whatever Complete returns round-trips through CachedAgenda unchanged, at the field caps. */
    @Test fun i1CompleteRoundTripsThroughTheCache() {
        val rnd = Random(11)
        val nasty = listOf("😀", "\\\\", "\"", "\\n", "\t", "\u0001", "é", "\\,", "\\;", "a", " ", " ", "𝄞")
        repeat(50) { feed ->
            val body = HostileFeeds.calendar {
                repeat(20) { i ->
                    fun text(n: Int) = (0 until n).joinToString("") { nasty[rnd.nextInt(nasty.size)] }
                    line("BEGIN:VEVENT")
                    folded("UID:" + text(rnd.nextInt(1, 260)) + "$feed-$i")
                    folded("SUMMARY:" + text(rnd.nextInt(0, 300)))
                    if (rnd.nextBoolean()) line("DTSTART;VALUE=DATE:20260310").line("DTEND;VALUE=DATE:20260312")
                    else line("DTSTART;TZID=America/New_York:20260311T0${rnd.nextInt(10)}3000").line("DURATION:PT45M")
                    line("END:VEVENT")
                }
            }
            val events = complete(parse(body)).events
            assertEquals(20, events.size)
            for (e in events) {
                assertTrue(e.title.length <= IcsText.MAX_CHARS && e.uid.length <= IcsText.MAX_CHARS)
                assertFalse(e.title.any { it < ' ' }, "C0 control in title")
                assertFalse(e.title.isNotEmpty() && Character.isHighSurrogate(e.title.last()), "split surrogate pair")
            }
            val cached = CachedAgenda(now, window, events)
            assertEquals(events, CachedAgenda.decode(cached.encode())?.events)
        }
    }

    // ------------------------------------------------------------------ I2

    /** I2: real helper iterations per period never exceed the charged static bound B(rule). */
    @Test
    @Tag("ics-parse-cpu-memory-dos")
    fun i2HelperIterationsNeverExceedTheChargedBound() {
        val rnd = Random(424242)
        val days = listOf("MO", "TU", "WE", "TH", "FR", "SA", "SU")
        val rules = ArrayList<String>()
        repeat(6_000) {
            val freq = listOf("DAILY", "WEEKLY", "MONTHLY", "YEARLY")[rnd.nextInt(4)]
            val parts = mutableListOf("FREQ=$freq")
            if (rnd.nextInt(3) == 0) parts += "INTERVAL=${rnd.nextInt(2, 5)}"
            val ord = freq == "MONTHLY" || freq == "YEARLY"
            if (rnd.nextInt(2) == 0) parts += "BYDAY=" + days.shuffled(rnd).take(rnd.nextInt(1, 5)).joinToString(",") {
                if (ord && rnd.nextInt(2) == 0) listOf(1, 2, 3, 4, 5, -1, -2, -5, 10, 20, -30, 53)[rnd.nextInt(12)].toString() + it else it
            }
            if (freq != "WEEKLY" && rnd.nextInt(3) == 0) parts += "BYMONTHDAY=" + (1..rnd.nextInt(1, 4)).map { listOf(1, 5, 15, 28, 29, 30, 31, -1, -3, -31, 10)[rnd.nextInt(11)] }.distinct().joinToString(",")
            if ((freq == "YEARLY" || rnd.nextInt(4) == 0) && rnd.nextInt(2) == 0) parts += "BYMONTH=" + (1..rnd.nextInt(1, 4)).map { rnd.nextInt(1, 13) }.distinct().joinToString(",")
            if (rnd.nextInt(5) == 0) parts += "BYSETPOS=" + (1..rnd.nextInt(1, 3)).map { listOf(1, -1, 2, 3, -2)[rnd.nextInt(5)] }.distinct().joinToString(",")
            if (freq != "YEARLY" && rnd.nextInt(6) == 0) parts += "BYHOUR=" + (1..rnd.nextInt(1, 3)).map { rnd.nextInt(0, 24) }.distinct().joinToString(",")
            if (freq != "YEARLY" && rnd.nextInt(8) == 0) parts += "BYMINUTE=" + (1..rnd.nextInt(1, 3)).map { rnd.nextInt(0, 60) }.distinct().joinToString(",")
            if (rnd.nextInt(6) == 0) parts += "WKST=" + days[rnd.nextInt(7)]
            when (rnd.nextInt(3)) {
                0 -> parts += "COUNT=${rnd.nextInt(1, 600)}"
                1 -> parts += "UNTIL=20${rnd.nextInt(25, 28)}${"%02d".format(rnd.nextInt(1, 13))}15T120000"
            }
            rules += parts.joinToString(";")
        }
        // the densest supported shapes, P7 included
        rules += "FREQ=YEARLY;COUNT=1000000;BYMONTH=1,2,3,4,5,6,7,8,9,10,11,12;BYMONTHDAY=" + ((1..31) + (-31..-1)).joinToString(",") +
            ";BYSETPOS=1,-1;BYDAY=" + (1..20).flatMap { n -> days.map { "$n$it" } }.joinToString(",")
        rules += "FREQ=YEARLY;BYDAY=" + (1..53).flatMap { n -> days.flatMap { listOf("$n$it", "-$n$it") } }.joinToString(",") +
            ";BYSETPOS=" + ((1..366) + (-366..-1)).joinToString(",")
        rules += "FREQ=MONTHLY;BYHOUR=0,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19,20,21,22,23;BYDAY=MO,TU,WE,TH,FR,SA,SU"
        var checked = 0
        var countChecked = 0
        for ((i, rr) in rules.withIndex()) {
            val spec = IcsRules.parse(rr) as? RuleSpec.Spec ?: continue
            val rule = Rule(spec.freq, spec.interval, spec.count, spec.until?.local, spec.byMonth, spec.byMonthDay, spec.byDay,
                spec.bySetPos, spec.byHour, spec.byMinute, spec.bySecond, spec.wkst)
            val start = if (i % 4 == 0) LocalDate.of(1950, 1, 1).plusDays(i.toLong()).atTime(9, 0) else LocalDate.of(2024, 1, 1).plusDays(i % 700L).atTime(10, 30)
            val e = RecurrenceExpander(rule, start, allDay = i % 9 == 0)
            if (!e.supported) continue
            val from = LocalDate.of(2026, 3, 8)
            e.between(from, from.plusDays(rnd.nextLong(4, 60)), Budget(Long.MAX_VALUE, 2_000_000, Long.MAX_VALUE) { 0L })
            assertTrue(e.maxTicksPerPeriod <= e.perPeriodBound, "rule $i $rr: ${e.maxTicksPerPeriod} > ${e.perPeriodBound}")
            assertTrue(e.maxCountTicks <= e.countBound, "rule $i $rr: count ${e.maxCountTicks} > ${e.countBound}")
            assertTrue(e.perPeriodBound <= 5_000, "rule $i: bound ${e.perPeriodBound}")
            if (e.maxTicksPerPeriod > 0) checked++
            if (e.maxCountTicks > 0) countChecked++
        }
        assertTrue(checked > 4_500, "only $checked rules exercised")
        assertTrue(countChecked > 300, "only $countChecked count-only paths exercised")
    }

    // ------------------------------------------------------------------ policies

    /** unsupported-rule-vanishes: every unsupported shape (architecture section 3) gives DTSTART only, counted. */
    @Test
    @Tag("unsupported-rule-vanishes")
    fun everyUnsupportedShapeShowsDtstartOnly() {
        val shapes = listOf(
            "FREQ=SECONDLY", "FREQ=MINUTELY", "FREQ=HOURLY", "FREQ=YEARLY;BYYEARDAY=1", "FREQ=YEARLY;BYWEEKNO=11",
            "FREQ=DAILY;BYHOUR=${(0..23).joinToString(",")};BYMINUTE=${(0..59).joinToString(",")}", "FREQ=DAILY;COUNT=0",
            "FREQ=DAILY;COUNT=1000000001", "FREQ=DAILY;BYMONTHDAY=" + (1..31).joinToString(",") { "1" } + ";X=" + "y".repeat(1000),
            "FREQ=YEARLY;BYMONTH=13", "FREQ=MONTHLY;BYMONTHDAY=32", "FREQ=MONTHLY;BYMONTHDAY=0", "FREQ=MONTHLY;BYDAY=0MO",
            "FREQ=YEARLY;BYDAY=54MO", "FREQ=MONTHLY;BYDAY=MO;BYSETPOS=367", "FREQ=MONTHLY;BYDAY=MO;BYSETPOS=0",
            "FREQ=DAILY;BYHOUR=24", "FREQ=DAILY;BYMINUTE=60", "FREQ=DAILY;BYSECOND=61", "FREQ=DAILY;BYSECOND=60",
            "FREQ=DAILY;X-NAME=1", "FREQ=DAILY;FREQ=DAILY", "FREQ=DAILY;INTERVAL=2;INTERVAL=3", "FREQ=DAILY;INTERVAL=0",
            "FREQ=DAILY;INTERVAL=100001", "INTERVAL=2", "FREQ=DAILY;BYDAY=", "FREQ=DAILY;BYDAY=XX", "FREQ=FORTNIGHTLY",
            "FREQ=DAILY;UNTIL=20261340", "FREQ=DAILY;COUNT=ten", "FREQ=WEEKLY;WKST=XX", "FREQ=DAILY;BYMONTH=1,,2",
        )
        for (rr in shapes) {
            val r = complete(parse(HostileFeeds.calendar { event("UID:u\nDTSTART:20260310T150000Z\nRRULE:$rr\nSUMMARY:s") }))
            assertEquals(listOf(at("2026-03-10T15:00:00Z")), r.events.map { it.start }, rr)
            assertEquals(1, r.stats.unsupportedRules, rr)
        }
    }

    /** allday-until-utc-frame: an all-day series' UTC UNTIL is compared by its date in the device zone. */
    @Test
    @Tag("allday-until-utc-frame")
    fun allDayUntilInUtcUsesTheDeviceZoneDate() {
        fun days(until: String) = complete(parse(HostileFeeds.calendar {
            event("UID:a\nDTSTART;VALUE=DATE:20260301\nRRULE:FREQ=DAILY;UNTIL=$until\nSUMMARY:Trip")
        })).events.map { (it.start as EventTime.AllDay).date.dayOfMonth }
        // 04:00Z Mar 11 is 00:00 EDT Mar 11: Mar 11 is the last day
        assertEquals(listOf(9, 10, 11), days("20260311T040000Z"))
        // 03:00Z Mar 11 is still Mar 10 in New York (a UTC frame would wrongly include Mar 11)
        assertEquals(listOf(9, 10), days("20260311T030000Z"))
        assertEquals(listOf(9, 10), days("20260310"))
    }

    @Test
    @Tag("one-bad-event-fails-feed")
    fun oneBadExdateValueIsIgnoredAndTheSeriesKept() {
        val r = complete(parse(HostileFeeds.calendar {
            event("UID:s\nDTSTART:20260301T150000Z\nRRULE:FREQ=DAILY\nEXDATE:20260310T150000Z,20260230T150000Z,garbage\nSUMMARY:Gym")
        }))
        assertEquals(2, r.stats.ignoredValues)
        assertEquals(listOf(9, 11, 12), r.events.map { (it.start as EventTime.At).instant.atZone(ny).dayOfMonth })
    }

    @Test fun overrideAfterItsOneOffMasterStillReplacesIt() {
        val r = complete(parse(HostileFeeds.calendar {
            event("UID:m\nDTSTART:20260310T150000Z\nSUMMARY:Original")
            event("UID:m\nRECURRENCE-ID:20260310T150000Z\nDTSTART:20260311T150000Z\nSUMMARY:Moved")
        }))
        assertEquals(listOf("Moved"), r.events.map { it.title })
    }

    @Test fun overrideBeforeItsSeriesAndCancelledOverrideRemoveTheOriginal() {
        val r = complete(parse(HostileFeeds.calendar {
            event("UID:w\nRECURRENCE-ID:20260310T150000Z\nDTSTART:20260310T190000Z\nSUMMARY:Moved")
            event("UID:w\nRECURRENCE-ID:20260311T150000Z\nDTSTART:20260311T150000Z\nSTATUS:CANCELLED\nSUMMARY:Gone")
            event("UID:w\nDTSTART:20260301T150000Z\nRRULE:FREQ=DAILY\nSUMMARY:Daily")
        }))
        assertEquals(
            listOf("Daily" to at("2026-03-09T15:00:00Z"), "Moved" to at("2026-03-10T19:00:00Z"), "Daily" to at("2026-03-12T15:00:00Z")),
            r.events.map { it.title to it.start },
        )
    }

    @Test fun uidAndSummaryAreCutAtACodePointBoundary() {
        val title = "a".repeat(199) + "😀" + "tail"
        val r = complete(parse(HostileFeeds.calendar { line("BEGIN:VEVENT"); folded("UID:${"u".repeat(199)}😀"); line("DTSTART:20260310T150000Z"); folded("SUMMARY:$title"); line("END:VEVENT") }))
        assertEquals("a".repeat(199), r.events.single().title)
        assertEquals("u".repeat(199), r.events.single().uid)
    }

    @Test fun malformedVeventIsSkippedAndCounted() {
        val r = complete(parse(HostileFeeds.calendar {
            event("UID:a\nDTSTART:20260230T100000Z") // Feb 30
            event("UID:b\nSUMMARY:no start")
            event("UID:c\nDTSTART:20260310T150000Z\nDURATION:PT1X")
            event("UID:d\nDTSTART;TZID=\"unterminated:20260310T150000Z")
            event("UID:e\nDTSTART:20260310T150000Z\nSUMMARY:Kept")
        }))
        assertEquals(4, r.stats.skippedEvents)
        assertEquals(listOf("Kept"), r.events.map { it.title })
    }

    @Test
    @Tag("H8")
    fun zoneMemoIsBoundedAndResolutionNeverThrows() {
        val memo = ZoneResolver.Memo(ny)
        repeat(1_000) { assertEquals(ny, memo.resolve("Nowhere/Zone$it")) }
        assertEquals(ZoneId.of("Europe/London"), memo.resolve("/mozilla.org/20070129_1/Europe/London"))
        assertEquals(ZoneId.of("America/Argentina/Buenos_Aires"), ZoneResolver.resolve("/x/America/Argentina/Buenos_Aires", ny))
        assertEquals(ny, ZoneResolver.resolve("a/".repeat(64), ny))
        assertEquals(ny, ZoneResolver.resolve("x".repeat(129), ny))
        // zone-memo-key-size: an 8 KiB TZID is resolved but never kept as a memo key
        val keys = ZoneResolver.Memo(ny)
        assertEquals(ny, keys.resolve("Europe/" + "x".repeat(8_000)))
        assertEquals(0, keys.size)
        assertEquals(ZoneId.of("Europe/London"), keys.resolve("Europe/London"))
        assertEquals(1, keys.size)
    }

    @Test fun parseStatsAndOutcomesPrintCountsOnly() {
        val r = parse(HostileFeeds.calendar { event("UID:secret-uid@example.test\nDTSTART:20260310T150000Z\nSUMMARY:Dr. Smith, oncology") })
        for (text in listOf(r.toString(), (r as ParseOutcome.Complete).stats.toString())) {
            assertFalse(text.contains("Smith") || text.contains("oncology") || text.contains("secret-uid"), text)
        }
        assertTrue(r.toString().startsWith("Complete(1 events"))
    }

    /** ADR 0004 H1: biweekly and its dependencies are on test classpaths only (never the app's runtime). */
    @Test fun biweeklyIsTestOnlyInTheLockfile() {
        val lock = File("gradle.lockfile").takeIf { it.isFile } ?: File("core/gradle.lockfile")
        val lines = lock.readLines()
        for (artifact in listOf("net.sf.biweekly:biweekly:", "com.github.mangstadt:vinnie:", "com.fasterxml.jackson.core:jackson-core:")) {
            val line = lines.single { it.startsWith(artifact) }
            val configs = line.substringAfter('=').split(',')
            assertTrue(configs.all { it.startsWith("test") }, line)
        }
    }

    @Test fun dayOfWeekDomainOfByDayIs749() {
        val all = (-53..53).flatMap { n -> DayOfWeek.values().map { WeekdayNum(n, it) } }
        assertEquals(749, all.size)
        assertEquals(749, Rule(Freq.YEARLY, byDay = all + all).byDay.size)
    }
}
