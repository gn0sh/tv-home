package tvhome.core.calendar

import biweekly.Biweekly
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.TimeZone
import kotlin.random.Random

class RecurrenceExpanderTest {
    private fun budget(steps: Long = 10_000_000, perSeries: Long = 10_000_000) =
        Budget(steps, perSeries, System.nanoTime() + 60_000_000_000L)

    private fun dt(s: String) = LocalDateTime.parse(s)

    /**
     * Seeded property test against an independent oracle: biweekly's own RRULE iterator, walked from a recent DTSTART
     * (where walking is cheap and its answers are trusted). Our expander jumps to the window instead.
     */
    @Test
    fun matchesBiweeklyIteratorOnRandomRulesFromRecentStarts() {
        val rnd = Random(20261031)
        val days = listOf("MO", "TU", "WE", "TH", "FR", "SA", "SU")
        var compared = 0
        var nonEmpty = 0
        repeat(1500) { iter ->
            val freq = listOf("DAILY", "WEEKLY", "MONTHLY", "YEARLY")[rnd.nextInt(4)]
            val parts = mutableListOf("FREQ=$freq")
            if (rnd.nextInt(3) == 0) parts += "INTERVAL=${rnd.nextInt(2, 4)}"
            val ordinals = freq == "MONTHLY" || freq == "YEARLY"
            if (rnd.nextInt(2) == 0) {
                val picked = days.shuffled(rnd).take(rnd.nextInt(1, 4))
                parts += "BYDAY=" + picked.joinToString(",") {
                    if (ordinals && rnd.nextInt(2) == 0) listOf(1, 2, 3, 4, -1, -2)[rnd.nextInt(6)].toString() + it else it
                }
                if (rnd.nextInt(4) == 0 && freq != "DAILY") parts += "BYSETPOS=" + listOf(1, -1, 2)[rnd.nextInt(3)]
            } else if (freq != "WEEKLY" && rnd.nextInt(2) == 0) {
                parts += "BYMONTHDAY=" + (1..rnd.nextInt(1, 3)).map { listOf(1, 5, 15, 28, 29, 30, 31, -1, -3)[rnd.nextInt(9)] }.distinct().joinToString(",")
            }
            if (freq == "YEARLY" && rnd.nextInt(2) == 0) parts += "BYMONTH=" + (1..rnd.nextInt(1, 3)).map { rnd.nextInt(1, 13) }.distinct().joinToString(",")
            when (rnd.nextInt(3)) {
                0 -> parts += "COUNT=${rnd.nextInt(1, 400)}"
                1 -> parts += "UNTIL=2026${"%02d".format(rnd.nextInt(1, 13))}15T000000Z"
            }
            val start = LocalDate.of(2023, 1, 1).plusDays(rnd.nextLong(0, 900)).atTime(10, 30)
            val rrule = parts.joinToString(";")
            val from = LocalDate.of(2026, 1, 1).plusDays(rnd.nextLong(0, 300))
            val to = from.plusDays(rnd.nextLong(3, 40))

            // Known biweekly bug: DAILY;INTERVAL>1 with BYDAY yields consecutive days (see dailyIntervalWithByDay...).
            if (freq == "DAILY" && "INTERVAL" in rrule && "BYDAY" in rrule) return@repeat
            // Known biweekly bug: WEEKLY BYSETPOS=1 returned two days of one week. BYSETPOS is covered by bySetPos... below.
            if ("BYSETPOS" in rrule) return@repeat
            val oracle = oracle(start, rrule, from, to) ?: return@repeat
            val rule = parseRule(start, rrule)
            val ours = RecurrenceExpander(rule, start, allDay = false).between(from, to, budget())
            assertEquals(oracle, ours, "iteration $iter: DTSTART=$start RRULE=$rrule window=$from..$to")
            compared++
            if (oracle.isNotEmpty()) nonEmpty++
        }
        assertTrue(compared >= 400, "compared only $compared")
        assertTrue(nonEmpty >= 200, "only $nonEmpty non-empty comparisons")
    }

    /** biweekly's iterator, UTC; null when DTSTART does not match the rule (there biweekly and RFC 5545 differ). */
    private fun oracle(start: LocalDateTime, rrule: String, from: LocalDate, to: LocalDate): List<LocalDateTime>? {
        val ics = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nBEGIN:VEVENT\r\nUID:o\r\nDTSTART:${fmt(start)}Z\r\nRRULE:$rrule\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n"
        val ev = Biweekly.parse(ics).first().events.single()
        val it = ev.getDateIterator(TimeZone.getTimeZone("UTC"))
        val all = ArrayList<LocalDateTime>()
        var n = 0
        while (it.hasNext() && n++ < 200_000) {
            val d = LocalDateTime.ofInstant(it.next().toInstant(), ZoneOffset.UTC)
            if (d.toLocalDate().isAfter(to)) break
            all += d
        }
        // biweekly drops a DTSTART the rule does not produce; RFC 5545 counts it. Compare only synchronised starts.
        if (all.isEmpty() || all.first() != start) return null
        return all.filter { !it.toLocalDate().isBefore(from) }
    }

    private fun fmt(d: LocalDateTime) = "%04d%02d%02dT%02d%02d%02d".format(d.year, d.monthValue, d.dayOfMonth, d.hour, d.minute, d.second)

    private fun parseRule(start: LocalDateTime, rrule: String): Rule {
        val m = rrule.split(';').associate { it.substringBefore('=') to it.substringAfter('=') }
        val list = { k: String -> m[k]?.split(',')?.map { it.toInt() }.orEmpty() }
        return Rule(
            freq = Freq.valueOf(m.getValue("FREQ")),
            interval = m["INTERVAL"]?.toInt() ?: 1,
            count = m["COUNT"]?.toLong(),
            until = m["UNTIL"]?.let { LocalDateTime.of(it.substring(0, 4).toInt(), it.substring(4, 6).toInt(), it.substring(6, 8).toInt(), 0, 0) },
            byMonth = list("BYMONTH"),
            byMonthDay = list("BYMONTHDAY"),
            byDay = m["BYDAY"]?.split(',')?.map { s ->
                val code = s.takeLast(2)
                val n = s.dropLast(2).ifEmpty { "0" }.toInt()
                WeekdayNum(n, DayOfWeek.values().first { it.name.startsWith(code) })
            }.orEmpty(),
            bySetPos = list("BYSETPOS"),
        )
    }

    /** Found by the oracle test: biweekly 0.6.8 returned Mar 8, 13 and 14 here; every other day cannot be consecutive. */
    @Test
    fun dailyIntervalWithByDayKeepsTheInterval() {
        val rule = Rule(Freq.DAILY, interval = 2, until = dt("2026-03-15T00:00"),
            byDay = listOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY, DayOfWeek.FRIDAY).map { WeekdayNum(0, it) })
        val r = RecurrenceExpander(rule, dt("2023-12-30T10:30"), false).between(LocalDate.of(2026, 3, 8), LocalDate.of(2026, 4, 1), budget())
        assertEquals(listOf(dt("2026-03-13T10:30")), r) // Mar 13 is 804 days on (even) and a Friday; Mar 15 is past UNTIL
    }

    @Test
    fun bySetPosPicksWithinEachPeriod() {
        val weekdays = listOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY).map { WeekdayNum(0, it) }
        // Outlook's "last weekday of the month"
        val last = RecurrenceExpander(Rule(Freq.MONTHLY, byDay = weekdays, bySetPos = listOf(-1)), dt("2020-01-31T09:00"), false)
            .between(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 5, 31), budget())
        assertEquals(listOf("2026-01-30", "2026-02-27", "2026-03-31", "2026-04-30", "2026-05-29").map { dt("${it}T09:00") }, last)
        // WEEKLY MO,TU,FR with BYSETPOS=1: the first of the set in each week, i.e. Monday
        val firstOfWeek = RecurrenceExpander(
            Rule(Freq.WEEKLY, byDay = listOf(DayOfWeek.MONDAY, DayOfWeek.FRIDAY, DayOfWeek.TUESDAY).map { WeekdayNum(0, it) }, bySetPos = listOf(1)),
            dt("2023-02-20T10:30"), false,
        ).between(LocalDate.of(2026, 3, 30), LocalDate.of(2026, 4, 12), budget())
        assertEquals(listOf(dt("2026-03-30T10:30"), dt("2026-04-06T10:30")), firstOfWeek)
    }

    @Test
    fun dailyFrom1900JumpsToTheWindowWithoutWalking() {
        val b = Budget(1_000, 1_000, System.nanoTime() + 60_000_000_000L) // far too small to walk 46,000 days
        val r = RecurrenceExpander(Rule(Freq.DAILY), dt("1900-01-01T09:00"), false)
            .between(LocalDate.of(2026, 3, 9), LocalDate.of(2026, 3, 12), b)
        assertEquals((9..12).map { dt("2026-03-%02dT09:00".format(it)) }, r)
    }

    @Test
    fun dailyCountFrom1900IsExactAtTheBoundary() {
        val start = dt("1900-01-01T09:00")
        val lastDay = LocalDate.of(2026, 3, 10)
        val count = ChronoUnit.DAYS.between(start.toLocalDate(), lastDay) + 1 // the COUNTth instance is Mar 10
        val b = Budget(1_000, 1_000, System.nanoTime() + 60_000_000_000L)
        val r = RecurrenceExpander(Rule(Freq.DAILY, count = count), start, false)
            .between(LocalDate.of(2026, 3, 8), LocalDate.of(2026, 3, 12), b)
        assertEquals(listOf(dt("2026-03-08T09:00"), dt("2026-03-09T09:00"), dt("2026-03-10T09:00")), r)
    }

    @Test
    fun weeklyByDayCountFrom1950IsArithmeticAndExact() {
        // DTSTART Wed 1950-03-01; MO,WE,FR. First week yields WE, FR (2); every later week 3.
        val start = dt("1950-03-01T18:00")
        val rule = Rule(Freq.WEEKLY, byDay = listOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY).map { WeekdayNum(0, it) })
        val all = RecurrenceExpander(rule, start, false).between(start.toLocalDate(), LocalDate.of(2026, 3, 13), budget())!!
        val targetIndex = all.indexOf(dt("2026-03-11T18:00")) // the Wednesday in the window
        val counted = Rule(rule.freq, count = (targetIndex + 1).toLong(), byDay = rule.byDay)
        val small = Budget(1_000, 1_000, System.nanoTime() + 60_000_000_000L)
        val r = RecurrenceExpander(counted, start, false).between(LocalDate.of(2026, 3, 9), LocalDate.of(2026, 3, 13), small)
        assertEquals(listOf(dt("2026-03-09T18:00"), dt("2026-03-11T18:00")), r)
    }

    @Test
    fun walkPathRunsOutOfSeriesBudgetAndReturnsNull() {
        // DAILY + BYDAY has no constant count per period, so COUNT needs a walk from year 1: over budget.
        val rule = Rule(Freq.DAILY, count = 1_000_000_000, byDay = listOf(WeekdayNum(0, DayOfWeek.MONDAY)))
        val b = Budget(10_000_000, 10_000, System.nanoTime() + 60_000_000_000L)
        assertNull(RecurrenceExpander(rule, dt("0001-01-01T09:00"), false).between(LocalDate.of(2026, 3, 9), LocalDate.of(2026, 3, 12), b))
    }

    @Test
    fun tooDenseRuleIsUnsupported() {
        val rule = Rule(Freq.YEARLY, byHour = (0..23).toList(), byMinute = (0..59).toList())
        val e = RecurrenceExpander(rule, dt("2026-01-01T00:00"), false)
        assertTrue(!e.supported)
        assertNull(e.between(LocalDate.of(2026, 3, 9), LocalDate.of(2026, 3, 12), budget()))
    }

    @Test
    fun monthly31stSkipsShortMonthsAndLeapDayBirthdayOnlyInLeapYears() {
        val m = RecurrenceExpander(Rule(Freq.MONTHLY), dt("2026-01-31T08:00"), false)
            .between(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 6, 30), budget())
        assertEquals(listOf("2026-01-31", "2026-03-31", "2026-05-31").map { dt("${it}T08:00") }, m)
        val leap = RecurrenceExpander(Rule(Freq.YEARLY), dt("1904-02-29T00:00"), true)
        assertEquals(emptyList<LocalDateTime>(), leap.between(LocalDate.of(2026, 2, 27), LocalDate.of(2026, 3, 2), budget()))
        assertEquals(listOf(dt("2028-02-29T00:00")), leap.between(LocalDate.of(2028, 2, 27), LocalDate.of(2028, 3, 2), budget()))
    }
}
