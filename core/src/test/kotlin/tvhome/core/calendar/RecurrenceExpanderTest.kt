package tvhome.core.calendar

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import kotlin.random.Random

class RecurrenceExpanderTest {
    private fun budget(steps: Long = 10_000_000, perSeries: Long = 10_000_000) =
        Budget(steps, perSeries, System.nanoTime() + 60_000_000_000L)

    private fun dt(s: String) = LocalDateTime.parse(s)

    /**
     * `expander-no-differential-test`: seeded differential test against [RfcReference], a brute-force walk of every
     * day from DTSTART (biweekly is no longer the expansion oracle: it differs from RFC 5545 on about 5% of random
     * rules). Rules mix every supported part; DTSTARTs are 2000-2026, windows in 2026. Every rule is compared with an
     * unlimited budget; with the production per-series budget a COUNT rule must not be skipped unless it is the
     * one residual walk (DAILY with BYMONTH or BYMONTHDAY).
     */
    @Test
    fun matchesBruteForceRfcReferenceOnRandomRules() {
        val rnd = Random(20261031)
        val days = DayOfWeek.values().toList()
        var compared = 0
        var nonEmpty = 0
        var skippedAtProductionBudget = 0
        var countRules = 0
        repeat(4_000) { iter ->
            val freq = Freq.values()[rnd.nextInt(4)]
            val monthly = freq == Freq.MONTHLY || freq == Freq.YEARLY
            fun ords() = listOf(1, 2, 3, 4, 5, -1, -2, -5, 10, -20, 53)[rnd.nextInt(11)]
            val byDay = if (rnd.nextInt(2) == 0) {
                days.shuffled(rnd).take(rnd.nextInt(1, 6)).map { WeekdayNum(if ((monthly || rnd.nextInt(8) == 0) && rnd.nextInt(2) == 0) ords() else 0, it) }
            } else {
                emptyList()
            }
            val byMonthDay = if (rnd.nextInt(3) == 0 && (freq != Freq.WEEKLY || rnd.nextInt(4) == 0)) {
                (1..rnd.nextInt(1, 4)).map { listOf(1, 5, 13, 15, 28, 29, 30, 31, -1, -2, -3, -31, 10)[rnd.nextInt(13)] }
            } else {
                emptyList()
            }
            val byMonth = if ((freq == Freq.YEARLY && rnd.nextInt(2) == 0) || rnd.nextInt(8) == 0) (1..rnd.nextInt(1, 4)).map { rnd.nextInt(1, 13) } else emptyList()
            val bySetPos = if (rnd.nextInt(5) == 0) (1..rnd.nextInt(1, 4)).map { listOf(1, -1, 2, 3, -2, 5, 20, -10)[rnd.nextInt(8)] } else emptyList()
            val byHour = if (rnd.nextInt(6) == 0) (1..rnd.nextInt(1, 4)).map { rnd.nextInt(0, 24) } else emptyList()
            val byMinute = if (rnd.nextInt(8) == 0) (1..rnd.nextInt(1, 3)).map { rnd.nextInt(0, 60) } else emptyList()
            val bySecond = if (rnd.nextInt(12) == 0) (1..rnd.nextInt(1, 3)).map { listOf(0, 30, 59, 60)[rnd.nextInt(4)] } else emptyList()
            val wkst = if (rnd.nextInt(4) == 0) days[rnd.nextInt(7)] else DayOfWeek.MONDAY
            val interval = if (rnd.nextInt(3) == 0) rnd.nextInt(2, 6) else 1
            val start = LocalDate.of(2000, 1, 1).plusDays(rnd.nextLong(0, 9_600)).atTime(rnd.nextInt(0, 24), listOf(0, 15, 30, 45)[rnd.nextInt(4)])
            val allDay = rnd.nextInt(10) == 0
            val dtStart = if (allDay) start.toLocalDate().atStartOfDay() else start
            val count = if (rnd.nextInt(3) == 0) rnd.nextLong(1, 1_500) else null
            val until = if (count == null && rnd.nextInt(2) == 0) LocalDate.of(2026, 1, 1).plusDays(rnd.nextLong(-400, 300)).atTime(12, 0) else null
            val rule = Rule(freq, interval, count, until, byMonth, byMonthDay, byDay, bySetPos, byHour, byMinute, bySecond, wkst)
            val from = LocalDate.of(2026, 1, 1).plusDays(rnd.nextLong(0, 300))
            val to = from.plusDays(rnd.nextLong(3, 40))
            val what = "iteration $iter: DTSTART=$dtStart allDay=$allDay freq=$freq interval=$interval count=$count until=$until " +
                "byMonth=$byMonth byMonthDay=$byMonthDay byDay=$byDay bySetPos=$bySetPos byHour=$byHour byMinute=$byMinute " +
                "bySecond=$bySecond wkst=$wkst window=$from..$to"

            val expander = RecurrenceExpander(rule, dtStart, allDay)
            val reference = RfcReference.expand(rule, dtStart, allDay, to)?.filter { !it.toLocalDate().isBefore(from) }
            if (!expander.supported) return@repeat
            assertEquals(reference, expander.between(from, to, budget()), what)
            compared++
            if (!reference.isNullOrEmpty()) nonEmpty++
            if (count != null) {
                countRules++
                val prod = Budget(IcsParser.Limits().maxSteps, IcsParser.Limits().maxStepsPerSeries, Long.MAX_VALUE) { 0L }
                val r = RecurrenceExpander(rule, dtStart, allDay).between(from, to, prod)
                // the one residual walk: DAILY with BYMONTH or BYMONTHDAY and COUNT (no calendar app writes it)
                val walk = freq == Freq.DAILY && (byMonth.isNotEmpty() || byMonthDay.isNotEmpty())
                if (r == null) {
                    assertTrue(walk, "skipped at the production budget: $what")
                    skippedAtProductionBudget++
                } else {
                    assertEquals(reference, r, "production budget, $what")
                }
            }
        }
        println("rfc differential: compared $compared, non-empty $nonEmpty, COUNT rules $countRules, skipped at the production budget $skippedAtProductionBudget")
        assertTrue(compared >= 3_500, "compared only $compared")
        assertTrue(nonEmpty >= 1_000, "only $nonEmpty non-empty comparisons")
        assertTrue(skippedAtProductionBudget * 20 <= countRules, "$skippedAtProductionBudget of $countRules COUNT rules skipped")
    }

    /**
     * `count-walk-skips-active-series`: COUNT rules whose periods do not all have the same count used to walk with the
     * pessimistic per-period charge and run out of the series budget; now they are counted over the calendar cycle.
     */
    @Test
    fun countRulesWithUnevenPeriodsShowTheirInstanceAtTheProductionBudget() {
        val prod = { Budget(IcsParser.Limits().maxSteps, IcsParser.Limits().maxStepsPerSeries, Long.MAX_VALUE) { 0L } }
        val day = LocalDate.of(2026, 3, 31)
        val the31st = RecurrenceExpander(Rule(Freq.MONTHLY, count = 500, byMonthDay = listOf(31)), dt("2015-01-31T09:00"), false)
        assertEquals(listOf(dt("2026-03-31T09:00")), the31st.between(day.minusDays(1), day.plusDays(1), prod()))
        val weekdays = listOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY).map { WeekdayNum(0, it) }
        val lastWeekday = RecurrenceExpander(Rule(Freq.MONTHLY, count = 300, byDay = weekdays, bySetPos = listOf(-1)), dt("2015-01-30T09:00"), false)
        assertEquals(listOf(dt("2026-03-31T09:00")), lastWeekday.between(day.minusDays(1), day.plusDays(1), prod()))
        // and the COUNT cut is exact: the 79th 31st is 2026-03-31 (7 a year from 2015, then January and March 2026)
        val cut = RecurrenceExpander(Rule(Freq.MONTHLY, count = 79, byMonthDay = listOf(31)), dt("2015-01-31T09:00"), false)
        assertEquals(listOf(dt("2026-03-31T09:00")), cut.between(day.minusDays(1), day.plusDays(1), prod()))
        val past = RecurrenceExpander(Rule(Freq.MONTHLY, count = 78, byMonthDay = listOf(31)), dt("2015-01-31T09:00"), false)
        assertEquals(emptyList<LocalDateTime>(), past.between(day.minusDays(1), day.plusDays(1), prod()))
        // a 1900 start: 1,514 one-step lookups (each calendar shape of month counted once), not 1,514 charged scans
        val old = RecurrenceExpander(Rule(Freq.MONTHLY, count = 1_000_000, byMonthDay = listOf(31)), dt("1900-01-31T09:00"), false)
        assertEquals(listOf(dt("2026-03-31T09:00")), old.between(day.minusDays(1), day.plusDays(1), prod()))
        // a year-1 start: more periods than one 4,800-month cycle, so whole cycles are multiplied out. The COUNT that
        // ends exactly on 2026-03-31, and one fewer, agree with the brute-force reference.
        val y1 = dt("0001-01-31T09:00")
        val all = RfcReference.expand(Rule(Freq.MONTHLY, count = 1_000_000, byMonthDay = listOf(31)), y1, false, day)!!
        val exact = all.indexOf(dt("2026-03-31T09:00")) + 1L
        assertTrue(exact > 14_000, "$exact")
        for ((count, expected) in listOf(exact to listOf(dt("2026-03-31T09:00")), exact - 1 to emptyList())) {
            val bySetPosToo = listOf(emptyList(), listOf(1)) // with BYSETPOS the cycle path is the only arithmetic one
            for (sp in bySetPosToo) {
                val e = RecurrenceExpander(Rule(Freq.MONTHLY, count = count, byMonthDay = listOf(31), bySetPos = sp), y1, false)
                assertEquals(expected, e.between(day.minusDays(1), day.plusDays(1), prod()), "COUNT=$count BYSETPOS=$sp")
            }
        }
        // YEARLY over 400-year cycles: the last Friday of February from year 1
        val yearly = Rule(Freq.YEARLY, count = 3_000, byMonth = listOf(2), byDay = listOf(WeekdayNum(-1, DayOfWeek.FRIDAY)))
        val ys = RfcReference.expand(yearly, dt("0001-02-23T08:00"), false, LocalDate.of(2026, 3, 1))!!
        assertEquals(ys.filter { it.year == 2026 }, RecurrenceExpander(yearly, dt("0001-02-23T08:00"), false)
            .between(LocalDate.of(2026, 2, 1), LocalDate.of(2026, 3, 1), prod()))
        assertEquals(listOf(dt("2026-02-27T08:00")), ys.filter { it.year == 2026 })
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
        // DAILY + BYMONTHDAY has no constant count per period nor a short cycle, so COUNT needs a walk from year 1:
        // over budget. (DAILY + BYDAY alone is counted over its 7-day cycle: see the differential test.)
        val rule = Rule(Freq.DAILY, count = 1_000_000_000, byMonthDay = listOf(13))
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
