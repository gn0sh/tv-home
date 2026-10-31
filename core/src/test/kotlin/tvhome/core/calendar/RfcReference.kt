package tvhome.core.calendar

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.TemporalAdjusters

/**
 * Test-only brute-force reference for the RRULE subset of [Rule] (`expander-no-differential-test`). It shares no code
 * or shortcut with [RecurrenceExpander]: it walks every period from DTSTART, lists every day of the period, keeps the
 * days that pass the RFC 5545 section 3.3.10 BYxxx table, crosses them with the times of day, applies BYSETPOS, then
 * DTSTART, UNTIL and COUNT. BYDAY ordinals are found by counting the weekday through the scope (month or year), not
 * by arithmetic. Slow on purpose; only for recent DTSTARTs.
 *
 * Choices outside RFC 5545 match the expander's documented ones: BYDAY ordinals are ignored for DAILY and WEEKLY,
 * BYMONTHDAY only limits WEEKLY days (the weekday still comes from DTSTART without BYDAY), and BYSECOND=60 is dropped (no second left: unsupported, null).
 */
internal object RfcReference {
    /** Every instance with a local date <= [to], ascending; null when the rule is unsupported. */
    fun expand(rule: Rule, dtStart: LocalDateTime, allDay: Boolean, to: LocalDate): List<LocalDateTime>? {
        val times = times(rule, dtStart, allDay) ?: return null
        val out = ArrayList<LocalDateTime>()
        val sameWeekday = HashMap<Pair<LocalDate, DayOfWeek>, List<LocalDate>>()
        var emitted = 0L
        var k = 0L
        while (true) {
            val days = periodDays(rule, dtStart, k) ?: break
            if (days.first().isAfter(to)) break
            val scope = days.filter { matches(rule, dtStart, it, sameWeekday) }
            var cands = scope.flatMap { d -> times.map { d.atTime(it) } }.sorted()
            if (rule.bySetPos.isNotEmpty()) {
                val picked = sortedSetOf<LocalDateTime>()
                for (p in rule.bySetPos) {
                    val i = if (p > 0) p - 1 else cands.size + p
                    if (i in cands.indices) picked += cands[i]
                }
                cands = picked.toList()
            }
            var inst = cands.filter { it.isAfter(dtStart) }
            if (k == 0L) inst = listOf(dtStart) + inst // DTSTART is always the first instance
            for (x in inst) {
                if (rule.until != null && x.isAfter(rule.until)) return out
                if (rule.count != null && emitted >= rule.count) return out
                emitted++
                if (!x.toLocalDate().isAfter(to)) out += x
            }
            if (rule.until != null && days.last().isAfter(rule.until.toLocalDate())) break
            k++
        }
        return out
    }

    private fun times(rule: Rule, dtStart: LocalDateTime, allDay: Boolean): List<LocalTime>? {
        if (allDay) return listOf(LocalTime.MIDNIGHT)
        val hs = rule.byHour.ifEmpty { listOf(dtStart.hour) }
        val ms = rule.byMinute.ifEmpty { listOf(dtStart.minute) }
        val ss = if (rule.bySecond.isEmpty()) listOf(dtStart.second) else rule.bySecond.filter { it < 60 }
        if (ss.isEmpty()) return null
        val out = ArrayList<LocalTime>()
        for (h in hs) for (m in ms) for (s in ss) out += LocalTime.of(h, m, s)
        return out.sorted()
    }

    /** All days of period [k], in order. */
    private fun periodDays(rule: Rule, dtStart: LocalDateTime, k: Long): List<LocalDate>? {
        val n = k * rule.interval
        val (first, endExclusive) = when (rule.freq) {
            Freq.DAILY -> dtStart.toLocalDate().plusDays(n).let { it to it.plusDays(1) }
            Freq.WEEKLY -> dtStart.toLocalDate().with(TemporalAdjusters.previousOrSame(rule.wkst)).plusWeeks(n).let { it to it.plusWeeks(1) }
            Freq.MONTHLY -> dtStart.toLocalDate().withDayOfMonth(1).plusMonths(n).let { it to it.plusMonths(1) }
            Freq.YEARLY -> dtStart.toLocalDate().withDayOfYear(1).plusYears(n).let { it to it.plusYears(1) }
        }
        if (first.year > 9000) return null
        val days = ArrayList<LocalDate>()
        var d = first
        while (d.isBefore(endExclusive)) {
            days += d
            d = d.plusDays(1)
        }
        return days
    }

    private fun matches(rule: Rule, dtStart: LocalDateTime, d: LocalDate, cache: MutableMap<Pair<LocalDate, DayOfWeek>, List<LocalDate>>): Boolean {
        val start = dtStart.toLocalDate()
        if (rule.byMonth.isNotEmpty() && d.monthValue !in rule.byMonth) return false
        val hasMd = rule.byMonthDay.isNotEmpty()
        val hasBd = rule.byDay.isNotEmpty()
        if (hasMd && rule.byMonthDay.none { v -> if (v > 0) d.dayOfMonth == v else d.dayOfMonth == d.lengthOfMonth() + v + 1 }) return false
        if (hasBd && rule.byDay.none { byDayHolds(rule, it, d, cache) }) return false
        // The defaults RFC 5545 takes from DTSTART for what no BYxxx part expands: WEEKLY is expanded only by BYDAY
        // (BYMONTHDAY there only limits), MONTHLY and YEARLY by BYMONTHDAY or BYDAY.
        return when (rule.freq) {
            Freq.DAILY -> true
            Freq.WEEKLY -> hasBd || d.dayOfWeek == start.dayOfWeek
            Freq.MONTHLY -> hasMd || hasBd || d.dayOfMonth == start.dayOfMonth
            Freq.YEARLY -> hasMd || hasBd || (d.dayOfMonth == start.dayOfMonth && (rule.byMonth.isNotEmpty() || d.monthValue == start.monthValue))
        }
    }

    /** Whether [d] is the [wd]: the weekday's occurrences in the scope are listed and [d]'s place counted. */
    private fun byDayHolds(rule: Rule, wd: WeekdayNum, d: LocalDate, cache: MutableMap<Pair<LocalDate, DayOfWeek>, List<LocalDate>>): Boolean {
        if (d.dayOfWeek != wd.day) return false
        if (wd.n == 0 || rule.freq == Freq.DAILY || rule.freq == Freq.WEEKLY) return true
        val monthScope = rule.freq == Freq.MONTHLY || rule.byMonth.isNotEmpty()
        val scopeStart = if (monthScope) d.withDayOfMonth(1) else d.withDayOfYear(1)
        val same = cache.getOrPut(scopeStart to wd.day) {
            val scope = if (monthScope) {
                (1..d.lengthOfMonth()).map { d.withDayOfMonth(it) }
            } else {
                (1..d.lengthOfYear()).map { d.withDayOfYear(it) }
            }
            scope.filter { it.dayOfWeek == wd.day }
        }
        val nth = same.indexOf(d) + 1
        val nthFromEnd = same.size - same.indexOf(d)
        return if (wd.n > 0) nth == wd.n else nthFromEnd == -wd.n
    }
}
