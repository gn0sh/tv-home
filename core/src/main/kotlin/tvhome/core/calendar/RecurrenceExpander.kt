package tvhome.core.calendar

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/**
 * Work limit shared by one parse: [maxSteps] candidate dates in total, [maxStepsPerSeries] for any one series, and a
 * wall-clock [deadlineNanos] (from [nanoTime]). Checks the thread's interrupt flag so a cancelled caller
 * (`runInterruptible`) stops the parse promptly.
 */
internal class Budget(
    private val maxSteps: Long,
    private val maxStepsPerSeries: Long,
    private val deadlineNanos: Long,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    var exhausted = false
        private set
    private var steps = 0L
    private var seriesSteps = 0L
    private var sinceCheck = 0L

    fun startSeries() {
        seriesSteps = 0
    }

    /** False once the parse-wide or this series' budget is used up; the caller stops expanding. */
    fun take(n: Int = 1): Boolean {
        if (exhausted) return false
        steps += n
        seriesSteps += n
        sinceCheck += n
        if (sinceCheck >= 512) {
            sinceCheck = 0
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            if (nanoTime() - deadlineNanos > 0) exhausted = true
        }
        if (steps > maxSteps) exhausted = true
        return !exhausted && seriesSteps <= maxStepsPerSeries
    }
}

internal enum class Freq { DAILY, WEEKLY, MONTHLY, YEARLY }

/** BYDAY entry: [n] = 0 means every such weekday in the period, otherwise the nth (negative: from the end). */
internal data class WeekdayNum(val n: Int, val day: DayOfWeek)

/**
 * The RRULE subset expanded here: DAILY, WEEKLY, MONTHLY, YEARLY with INTERVAL, COUNT, UNTIL, BYMONTH, BYMONTHDAY,
 * BYDAY (with ordinals), BYSETPOS, BYHOUR, BYMINUTE, BYSECOND and WKST. Anything else (SECONDLY, MINUTELY, HOURLY,
 * BYYEARDAY, BYWEEKNO) is unsupported and the series shows only its DTSTART (see docs/architecture/tv-home.md).
 * [until] is in the event's own local frame (UTC UNTIL values are converted by the caller).
 */
internal class Rule(
    val freq: Freq,
    val interval: Int = 1,
    val count: Long? = null,
    val until: LocalDateTime? = null,
    val byMonth: List<Int> = emptyList(),
    val byMonthDay: List<Int> = emptyList(),
    val byDay: List<WeekdayNum> = emptyList(),
    val bySetPos: List<Int> = emptyList(),
    val byHour: List<Int> = emptyList(),
    val byMinute: List<Int> = emptyList(),
    val bySecond: List<Int> = emptyList(),
    val wkst: DayOfWeek = DayOfWeek.MONDAY,
)

/**
 * Expands a [Rule] from [dtStart] without walking from DTSTART: the period that holds the window is found by
 * arithmetic, so a 1900 birthday costs the same as a 2026 one. COUNT is honoured by counting the instances before
 * that period arithmetically when every period has the same number of instances (the shapes Google writes), and
 * otherwise by walking period by period under the series budget. Every candidate costs one [Budget] step.
 */
internal class RecurrenceExpander(private val rule: Rule, private val dtStart: LocalDateTime, private val allDay: Boolean) {
    private val startDate = dtStart.toLocalDate()
    private val interval = rule.interval.coerceAtLeast(1).toLong()
    private val times: List<LocalTime> = if (allDay) {
        listOf(LocalTime.MIDNIGHT)
    } else {
        val h = rule.byHour.filter { it in 0..23 }.ifEmpty { listOf(dtStart.hour) }.distinct()
        val m = rule.byMinute.filter { it in 0..59 }.ifEmpty { listOf(dtStart.minute) }.distinct()
        val s = rule.bySecond.filter { it in 0..59 }.ifEmpty { listOf(dtStart.second) }.distinct()
        if (h.size.toLong() * m.size * s.size > MAX_PER_PERIOD) {
            emptyList()
        } else {
            h.flatMap { hh -> m.flatMap { mm -> s.map { ss -> LocalTime.of(hh, mm, ss) } } }.sorted()
        }
    }
    private val weekStart0 = startDate.with(TemporalAdjusters.previousOrSame(rule.wkst))
    private val startYm = YearMonth.from(startDate)

    /** False when the rule is too dense to expand within the per-period cap. */
    val supported: Boolean = times.isNotEmpty() && maxDaysPerPeriod() * times.size <= MAX_PER_PERIOD

    /**
     * Instance starts whose local date is in [from, to], ascending, DTSTART included. Null if a budget ran out
     * before the answer was known (the caller drops the series rather than show a wrong COUNT cut).
     */
    fun between(from: LocalDate, to: LocalDate, budget: Budget): List<LocalDateTime>? {
        if (!supported) return null
        val k0 = periodIndex(from)
        var index = if (rule.count != null) countBefore(k0, budget) ?: return null else 0L
        val out = ArrayList<LocalDateTime>()
        var k = k0
        while (true) {
            val first = periodFirstDate(k) ?: break
            if (first.isAfter(to)) break
            if (rule.until != null && first.isAfter(rule.until.toLocalDate())) break
            val inst = periodInstances(k, budget) ?: return null
            for (x in inst) {
                if (rule.until != null && x.isAfter(rule.until)) return out
                if (rule.count != null && index >= rule.count) return out
                index++
                val d = x.toLocalDate()
                if (!d.isBefore(from) && !d.isAfter(to)) out += x
            }
            k++
        }
        return out
    }

    // ---- periods ----

    private fun periodIndex(date: LocalDate): Long {
        if (!date.isAfter(startDate)) return 0
        val units = when (rule.freq) {
            Freq.DAILY -> ChronoUnit.DAYS.between(startDate, date)
            Freq.WEEKLY -> ChronoUnit.DAYS.between(weekStart0, date) / 7
            Freq.MONTHLY -> ChronoUnit.MONTHS.between(startYm, YearMonth.from(date))
            Freq.YEARLY -> (date.year - startDate.year).toLong()
        }
        return (units / interval).coerceAtLeast(0)
    }

    /** First local date of period [k]; null past year 9999 (nothing real lives there). */
    private fun periodFirstDate(k: Long): LocalDate? {
        val step = k * interval
        if (step < 0 || step > 4_000_000) return null
        val d = when (rule.freq) {
            Freq.DAILY -> startDate.plusDays(step)
            Freq.WEEKLY -> weekStart0.plusDays(step * 7)
            Freq.MONTHLY -> startYm.plusMonths(step).atDay(1)
            Freq.YEARLY -> LocalDate.of((startDate.year + step).coerceAtMost(10_000L).toInt(), 1, 1)
        }
        return if (d.year > 9999) null else d
    }

    private fun maxDaysPerPeriod(): Int = when (rule.freq) {
        Freq.DAILY -> 1
        Freq.WEEKLY -> 7
        Freq.MONTHLY -> 31
        Freq.YEARLY -> 366
    }

    /** Sorted instance starts of period [k] that are >= DTSTART; period 0 always includes DTSTART itself. */
    private fun periodInstances(k: Long, budget: Budget): List<LocalDateTime>? {
        val first = periodFirstDate(k) ?: return emptyList()
        val days = candidateDays(first)
        if (!budget.take(1 + days.size * times.size)) return null
        var list = ArrayList<LocalDateTime>(days.size * times.size)
        for (d in days) for (t in times) list += d.atTime(t)
        if (rule.bySetPos.isNotEmpty()) {
            val picked = sortedSetOf<LocalDateTime>()
            for (p in rule.bySetPos) {
                val i = if (p > 0) p - 1 else list.size + p
                if (i in list.indices) picked += list[i]
            }
            list = ArrayList(picked)
        }
        val res = list.filter { !it.isBefore(dtStart) }
        return if (k == 0L && dtStart !in res) (res + dtStart).sorted() else res
    }

    private fun candidateDays(first: LocalDate): List<LocalDate> {
        val days: List<LocalDate> = when (rule.freq) {
            Freq.DAILY -> listOf(first).filter { dayFilter(it) && monthDayFilter(it) }
            Freq.WEEKLY -> {
                val wanted = rule.byDay.map { it.day }.distinct().ifEmpty { listOf(startDate.dayOfWeek) }
                wanted.map { first.plusDays(((it.value - rule.wkst.value + 7) % 7).toLong()) }
                    .filter { monthDayFilter(it) }
            }
            Freq.MONTHLY -> monthDays(YearMonth.from(first))
            Freq.YEARLY -> yearDays(first.year)
        }
        return days.filter { rule.byMonth.isEmpty() || it.monthValue in rule.byMonth }.distinct().sorted()
    }

    private fun dayFilter(d: LocalDate) = rule.byDay.isEmpty() || rule.byDay.any { it.day == d.dayOfWeek }

    private fun monthDayFilter(d: LocalDate): Boolean {
        if (rule.byMonthDay.isEmpty()) return true
        val len = d.lengthOfMonth()
        return rule.byMonthDay.any { md -> (if (md > 0) md else len + md + 1) == d.dayOfMonth }
    }

    private fun monthDayList(ym: YearMonth): List<LocalDate> {
        val len = ym.lengthOfMonth()
        return rule.byMonthDay.mapNotNull { md ->
            val day = if (md > 0) md else len + md + 1
            if (day in 1..len) ym.atDay(day) else null
        }
    }

    /** Days in [from, to] matching BYDAY; ordinals count within that range (month or year). */
    private fun byDayIn(from: LocalDate, to: LocalDate): List<LocalDate> {
        val out = ArrayList<LocalDate>()
        for (wd in rule.byDay) {
            val all = ArrayList<LocalDate>(53)
            var d = from.with(TemporalAdjusters.nextOrSame(wd.day))
            while (!d.isAfter(to)) {
                all += d
                d = d.plusWeeks(1)
            }
            when {
                wd.n == 0 -> out += all
                wd.n > 0 -> all.getOrNull(wd.n - 1)?.let { out += it }
                else -> all.getOrNull(all.size + wd.n)?.let { out += it }
            }
        }
        return out
    }

    private fun monthDays(ym: YearMonth): List<LocalDate> {
        val hasMd = rule.byMonthDay.isNotEmpty()
        val hasBd = rule.byDay.isNotEmpty()
        return when {
            hasMd && hasBd -> monthDayList(ym).filter { dayFilterWithOrdinal(it, ym.atDay(1), ym.atEndOfMonth()) }
            hasMd -> monthDayList(ym)
            hasBd -> byDayIn(ym.atDay(1), ym.atEndOfMonth())
            startDate.dayOfMonth <= ym.lengthOfMonth() -> listOf(ym.atDay(startDate.dayOfMonth))
            else -> emptyList() // e.g. the 31st in a 30-day month: RFC 5545 skips it
        }
    }

    private fun dayFilterWithOrdinal(d: LocalDate, from: LocalDate, to: LocalDate): Boolean =
        if (rule.byDay.all { it.n == 0 }) dayFilter(d) else d in byDayIn(from, to)

    private fun yearDays(year: Int): List<LocalDate> {
        if (rule.byMonth.isNotEmpty()) {
            return rule.byMonth.filter { it in 1..12 }.distinct().flatMap { m ->
                val ym = YearMonth.of(year, m)
                if (rule.byMonthDay.isEmpty() && rule.byDay.isEmpty()) {
                    if (startDate.dayOfMonth <= ym.lengthOfMonth()) listOf(ym.atDay(startDate.dayOfMonth)) else emptyList()
                } else {
                    monthDays(ym)
                }
            }
        }
        val jan1 = LocalDate.of(year, 1, 1)
        val dec31 = LocalDate.of(year, 12, 31)
        return when {
            rule.byMonthDay.isNotEmpty() -> (1..12).flatMap { monthDayList(YearMonth.of(year, it)) }
                .filter { rule.byDay.isEmpty() || dayFilterWithOrdinal(it, jan1, dec31) }
            rule.byDay.isNotEmpty() -> byDayIn(jan1, dec31)
            else -> {
                val ym = YearMonth.of(year, startDate.monthValue)
                if (startDate.dayOfMonth <= ym.lengthOfMonth()) listOf(ym.atDay(startDate.dayOfMonth)) else emptyList()
            }
        }
    }

    // ---- COUNT ----

    /**
     * Instances in periods [0, k0). Arithmetic when every period after the first yields the same number
     * ([constantPerPeriod]); else a walk that stops early once COUNT is reached. Null when the budget runs out.
     */
    private fun countBefore(k0: Long, budget: Budget): Long? {
        if (k0 == 0L) return 0
        val count = rule.count ?: return 0
        val first = periodInstances(0, budget)?.size?.toLong() ?: return null
        val c = constantPerPeriod()
        if (c != null) return first + (k0 - 1) * c
        var n = first
        var k = 1L
        while (k < k0 && n < count) {
            n += periodInstances(k, budget)?.size ?: return null
            k++
        }
        return n
    }

    /** Instances per period when it is the same for every period (proved by the rule's shape), else null. */
    private fun constantPerPeriod(): Long? {
        if (rule.bySetPos.isNotEmpty()) return null
        val t = times.size.toLong()
        val md = rule.byMonthDay.distinct()
        val safeMd = md.isNotEmpty() && (md.all { it in 1..28 } || md.all { it in -28..-1 })
        val bd = rule.byDay.distinct()
        val safeBd = bd.isNotEmpty() && (bd.all { it.n in 1..4 } || bd.all { it.n in -4..-1 })
        return when (rule.freq) {
            Freq.DAILY -> if (rule.byMonth.isEmpty() && md.isEmpty() && bd.isEmpty()) t else null
            Freq.WEEKLY -> if (rule.byMonth.isEmpty() && md.isEmpty() && bd.all { it.n == 0 }) {
                (bd.map { it.day }.distinct().size.takeIf { it > 0 } ?: 1) * t
            } else {
                null
            }
            Freq.MONTHLY -> when {
                rule.byMonth.isNotEmpty() -> null
                md.isEmpty() && bd.isEmpty() -> if (startDate.dayOfMonth <= 28) t else null
                bd.isEmpty() && safeMd -> md.size * t
                md.isEmpty() && safeBd -> bd.size * t
                else -> null
            }
            Freq.YEARLY -> {
                val months = rule.byMonth.filter { it in 1..12 }.distinct()
                when {
                    months.isEmpty() && md.isEmpty() && bd.isEmpty() ->
                        if (!(startDate.monthValue == 2 && startDate.dayOfMonth == 29)) t else null
                    months.isEmpty() -> null
                    md.isEmpty() && bd.isEmpty() -> if (startDate.dayOfMonth <= 28) months.size * t else null
                    bd.isEmpty() && safeMd -> months.size * md.size * t
                    md.isEmpty() && safeBd -> months.size * bd.size * t
                    else -> null
                }
            }
        }
    }

    companion object {
        /** Candidates in one period (days x times); denser rules are unsupported. */
        const val MAX_PER_PERIOD = 1_000
    }
}
