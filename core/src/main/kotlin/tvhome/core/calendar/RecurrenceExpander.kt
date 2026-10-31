package tvhome.core.calendar

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.Year
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/** A parse-wide limit ran out: unwinds the whole parse, which then returns `Exhausted(limit)`. No stack trace. */
internal class Stop(val limit: ParseLimit) : Throwable() {
    override fun fillInStackTrace(): Throwable = this
}

/**
 * Work limit shared by one parse (ADR 0004 decisions 2 and 3): [maxSteps] steps in total, [maxStepsPerSeries] for any
 * one series, and a wall-clock [deadlineNanos] (from [nanoTime]) that started at parse entry.
 *
 * Steps are charged *before* the work they pay for ([take]). The deadline and the thread's interrupt flag are polled
 * every [CHECK_STEPS] steps and, by the reader, every 64 KiB of input ([poll]).
 */
internal class Budget(
    private val maxSteps: Long,
    private val maxStepsPerSeries: Long,
    private val deadlineNanos: Long,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    /** Steps charged so far, parse-wide. */
    var steps = 0L
        private set
    private var seriesSteps = 0L
    private var sinceCheck = 0L

    fun startSeries() {
        seriesSteps = 0
    }

    /**
     * Charges [n] steps before doing at most [n] units of work. False: this series is over its own budget (the
     * caller skips the series; the steps still count parse-wide). Throws [Stop] when the parse-wide steps or the
     * deadline run out, and [InterruptedException] when the thread is interrupted.
     */
    fun take(n: Long = 1): Boolean {
        steps += n
        seriesSteps += n
        sinceCheck += n
        if (sinceCheck >= CHECK_STEPS) {
            sinceCheck = 0
            poll()
        }
        if (steps > maxSteps) throw Stop(ParseLimit.STEPS)
        return seriesSteps <= maxStepsPerSeries
    }

    /** Interrupt flag, then deadline. */
    fun poll() {
        if (Thread.currentThread().isInterrupted) throw InterruptedException()
        if (nanoTime() - deadlineNanos > 0) throw Stop(ParseLimit.DEADLINE)
    }

    companion object {
        const val CHECK_STEPS = 512L
    }
}

internal enum class Freq { DAILY, WEEKLY, MONTHLY, YEARLY }

/** BYDAY entry: [n] = 0 means every such weekday in the period, otherwise the nth (negative: from the end). */
internal data class WeekdayNum(val n: Int, val day: DayOfWeek)

/**
 * The RRULE subset expanded here: DAILY, WEEKLY, MONTHLY, YEARLY with INTERVAL, COUNT, UNTIL, BYMONTH, BYMONTHDAY,
 * BYDAY (with ordinals), BYSETPOS, BYHOUR, BYMINUTE, BYSECOND and WKST. [until] is in the event's own local frame
 * (the caller converts UTC values).
 *
 * Every BYxxx list is normalised to a distinct, sorted list of values inside its RFC 5545 domain (BYMONTH 12,
 * BYMONTHDAY 62, BYDAY 749, BYHOUR 24, BYMINUTE 60, BYSECOND 61, BYSETPOS 732), so no list can be longer than its
 * domain, whatever the input said. Out-of-range values are a programming error here: `IcsRules.parse` makes such a
 * rule unsupported before it gets this far.
 */
internal class Rule(
    val freq: Freq,
    interval: Int = 1,
    val count: Long? = null,
    val until: LocalDateTime? = null,
    byMonth: List<Int> = emptyList(),
    byMonthDay: List<Int> = emptyList(),
    byDay: List<WeekdayNum> = emptyList(),
    bySetPos: List<Int> = emptyList(),
    byHour: List<Int> = emptyList(),
    byMinute: List<Int> = emptyList(),
    bySecond: List<Int> = emptyList(),
    val wkst: DayOfWeek = DayOfWeek.MONDAY,
) {
    val interval: Int = interval
    // bound: (ii) each list is first checked against its domain, then made distinct: at most the domain size
    val byMonth: List<Int> = domain(byMonth, 1..12)
    val byMonthDay: List<Int> = domain(byMonthDay, -31..31, zero = false)
    // bound: (ii) BYDAY is checked against its ordinal domain, then made distinct: at most 749 entries
    val byDay: List<WeekdayNum> = byDay.onEach { require(it.n in -53..53) { "BYDAY ordinal" } }.distinct()
        .sortedWith(compareBy({ it.day }, { it.n }))
    val bySetPos: List<Int> = domain(bySetPos, -366..366, zero = false)
    val byHour: List<Int> = domain(byHour, 0..23)
    val byMinute: List<Int> = domain(byMinute, 0..59)
    val bySecond: List<Int> = domain(bySecond, 0..60)

    init {
        require(interval in 1..MAX_INTERVAL) { "INTERVAL" }
        require(count == null || count in 1..MAX_COUNT) { "COUNT" }
    }

    companion object {
        const val MAX_INTERVAL = 100_000
        const val MAX_COUNT = 1_000_000_000L

        private fun domain(values: List<Int>, range: IntRange, zero: Boolean = true): List<Int> {
            // bound: (ii) callers pass lists built from domain sets; the require caps any other caller
            for (v in values) require(v in range && (zero || v != 0)) { "BY value out of range" }
            return values.distinct().sorted() // bound: (ii) all values are in range, so at most the domain size
        }
    }
}

/**
 * Expands a [Rule] from [dtStart] without walking from DTSTART: the period that holds the window is found by
 * arithmetic, so a 1900 birthday costs the same as a 2026 one. COUNT is honoured by counting the instances before
 * that period arithmetically when every period has the same number of instances (the shapes Google writes), and
 * otherwise by walking period by period under the series budget.
 *
 * Bounded by construction (ADR 0004 decision 2): each period's work is at most [perPeriodBound] helper iterations
 * (a static bound computed from the rule's shape), and that bound is charged to the [Budget] before the period is
 * computed. [maxTicksPerPeriod] records the real iterations (test-only check, I2). Day membership is O(1) through
 * masks built once from the domain-bounded rule sets; nothing loops over an input-sized list.
 */
internal class RecurrenceExpander(private val rule: Rule, private val dtStart: LocalDateTime, private val allDay: Boolean) {
    private val startDate = dtStart.toLocalDate()
    private val interval = rule.interval.toLong()

    /** The rule's UNTIL, in the series' local frame. */
    val until: LocalDateTime? get() = rule.until

    /** Times of day per candidate day, sorted; empty when there would be more than [MAX_PER_PERIOD]. */
    private val times: List<LocalTime> = buildTimes()
    private val weekStart0 = startDate.with(TemporalAdjusters.previousOrSame(rule.wkst))
    private val startYm = YearMonth.from(startDate)

    // ---- masks over fixed domains, built once (bound: (ii) each loop is over a domain-bounded Rule list) ----
    private val monthMask = BooleanArray(13).also { m -> for (v in rule.byMonth) m[v] = true }
    private val mdPos = BooleanArray(32).also { m -> for (v in rule.byMonthDay) if (v > 0) m[v] = true }
    private val mdNeg = BooleanArray(32).also { m -> for (v in rule.byMonthDay) if (v < 0) m[-v] = true }
    private val wdAny = BooleanArray(8)        // BYDAY entries with no ordinal, by ISO weekday 1..7
    private val wdSeen = BooleanArray(8)       // any BYDAY entry for that weekday (DAILY/WEEKLY ignore ordinals)
    private val ordPos = LongArray(8)          // bit n: the nth such weekday (1..53)
    private val ordNeg = LongArray(8)          // bit n: the nth from the end
    private val hasMd = rule.byMonthDay.isNotEmpty()
    private val hasBd = rule.byDay.isNotEmpty()
    private val hasMonths = rule.byMonth.isNotEmpty()

    init {
        // bound: (ii) at most 749 distinct BYDAY entries
        for (wd in rule.byDay) {
            val d = wd.day.value
            wdSeen[d] = true
            when {
                wd.n == 0 -> wdAny[d] = true
                wd.n > 0 -> ordPos[d] = ordPos[d] or (1L shl wd.n)
                else -> ordNeg[d] = ordNeg[d] or (1L shl -wd.n)
            }
        }
    }

    /** Days examined per period (the scan length), by the rule's shape. */
    private val scanDays: Int = when (rule.freq) {
        Freq.DAILY -> 1
        Freq.WEEKLY -> 7
        Freq.MONTHLY -> if (!hasMd && !hasBd) 1 else 31
        Freq.YEARLY -> when {
            hasMonths && !hasMd && !hasBd -> rule.byMonth.size
            hasMonths -> 31 * rule.byMonth.size
            !hasMd && !hasBd -> 1
            else -> 366
        }
    }

    /** False when the rule is too dense to expand within the per-period cap (the series shows DTSTART only). */
    val supported: Boolean = times.isNotEmpty() && maxDaysPerPeriod() * times.size <= MAX_PER_PERIOD

    /**
     * Static upper bound of the helper iterations [periodInstances] does for one period: the day scan, one per
     * candidate built, BYSETPOS picks plus their filter, the DTSTART filter, and period 0's DTSTART insert, plus one.
     */
    val perPeriodBound: Long = run {
        val cand = scanDays.toLong() * times.size.coerceAtLeast(1)
        1L + scanDays + cand + rule.bySetPos.size + cand + cand + (cand + 1)
    }

    /** Test-only (I2): the most helper iterations any one period really took. */
    internal var maxTicksPerPeriod = 0L
        private set
    private var ticks = 0L

    /**
     * Instance starts whose local date is in [from, to], ascending, DTSTART included. Null if the series budget ran
     * out before the answer was known (the caller skips the series rather than show a wrong COUNT cut). A parse-wide
     * exhaustion throws [Stop] from the [Budget].
     */
    fun between(from: LocalDate, to: LocalDate, budget: Budget): List<LocalDateTime>? {
        if (!supported) return null
        val k0 = periodIndex(from)
        var index = if (rule.count != null) countBefore(k0, budget) ?: return null else 0L
        val out = ArrayList<LocalDateTime>()
        var k = k0
        // bound: (iii) every iteration charges perPeriodBound (>= 1) in periodInstances before its work
        while (true) {
            val first = periodFirstDate(k) ?: break
            if (first.isAfter(to)) break
            if (rule.until != null && first.isAfter(rule.until.toLocalDate())) break
            val inst = periodInstances(k, budget) ?: return null
            // bound: (iii) at most the candidates already paid for by this period's charge
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

    private fun buildTimes(): List<LocalTime> {
        if (allDay) return listOf(LocalTime.MIDNIGHT)
        // BYSECOND=60 (a leap second) cannot be represented; it is dropped. A rule left with no second is too odd
        // to expand (DTSTART only), rather than silently using DTSTART's second.
        val h = rule.byHour.ifEmpty { listOf(dtStart.hour) }
        val m = rule.byMinute.ifEmpty { listOf(dtStart.minute) }
        // bound: (ii) BYSECOND is a domain set of at most 61
        val s = if (rule.bySecond.isEmpty()) listOf(dtStart.second) else rule.bySecond.filter { it <= 59 }
        if (s.isEmpty() || h.size.toLong() * m.size * s.size > MAX_PER_PERIOD) return emptyList()
        // bound: (ii) at most MAX_PER_PERIOD times (checked just above); lists are sorted, so the result is sorted
        val out = ArrayList<LocalTime>(h.size * m.size * s.size)
        for (hh in h) for (mm in m) for (ss in s) out += LocalTime.of(hh, mm, ss)
        return out
    }

    private fun tick() {
        ticks++
    }

    /** Sorted instance starts of period [k] that are >= DTSTART; period 0 always includes DTSTART itself. */
    private fun periodInstances(k: Long, budget: Budget): List<LocalDateTime>? {
        val first = periodFirstDate(k) ?: return emptyList()
        if (!budget.take(perPeriodBound)) return null
        ticks = 1
        var list = ArrayList<LocalDateTime>()
        scan(first) { d ->
            // bound: (ii) times has at most MAX_PER_PERIOD / maxDaysPerPeriod entries (supported)
            for (t in times) {
                tick()
                list += d.atTime(t)
            }
        }
        if (rule.bySetPos.isNotEmpty()) {
            val pick = BooleanArray(list.size)
            // bound: (ii) at most 732 distinct BYSETPOS values
            for (p in rule.bySetPos) {
                tick()
                val i = if (p > 0) p - 1 else list.size + p
                if (i in list.indices) pick[i] = true
            }
            val picked = ArrayList<LocalDateTime>()
            // bound: (iii) the candidates of this period, paid for by its charge
            for (i in list.indices) {
                tick()
                if (pick[i]) picked += list[i]
            }
            list = picked
        }
        val res = ArrayList<LocalDateTime>(list.size + 1)
        // bound: (iii) the candidates of this period
        for (x in list) {
            tick()
            if (!x.isBefore(dtStart)) res += x
        }
        if (k == 0L) {
            // bound: (iii) the candidates of this period, plus one
            var at = res.size
            var present = false
            // bound: (iii) this period's candidates (<= cand), paid for by its charge
            for (i in res.indices) {
                tick()
                if (res[i] == dtStart) present = true
                if (at == res.size && res[i].isAfter(dtStart)) at = i
            }
            tick()
            if (!present) res.add(at, dtStart)
        }
        if (ticks > maxTicksPerPeriod) maxTicksPerPeriod = ticks
        return res
    }

    /** Calls [emit] for each candidate day of the period starting [first], ascending; at most [scanDays] examined. */
    private inline fun scan(first: LocalDate, emit: (LocalDate) -> Unit) {
        when (rule.freq) {
            Freq.DAILY -> {
                tick()
                if (dailyOrWeeklyMatch(first, weekly = false)) emit(first)
            }
            Freq.WEEKLY -> {
                // bound: (ii) 7 days of one week
                for (i in 0L until 7L) {
                    tick()
                    val d = first.plusDays(i)
                    if (dailyOrWeeklyMatch(d, weekly = true)) emit(d)
                }
            }
            Freq.MONTHLY -> {
                val ym = YearMonth.from(first)
                if (!hasMd && !hasBd) {
                    tick()
                    // e.g. the 31st in a 30-day month: RFC 5545 skips it
                    if (startDate.dayOfMonth <= ym.lengthOfMonth() && monthOk(ym.monthValue)) emit(ym.atDay(startDate.dayOfMonth))
                } else if (monthOk(ym.monthValue)) {
                    // bound: (ii) at most 31 days of one month
                    for (dom in 1..ym.lengthOfMonth()) {
                        tick()
                        val d = ym.atDay(dom)
                        if (monthScopeMatch(d, ym.lengthOfMonth())) emit(d)
                    }
                } else {
                    tick()
                }
            }
            Freq.YEARLY -> {
                val year = first.year
                when {
                    hasMonths && !hasMd && !hasBd -> {
                        // bound: (ii) at most 12 distinct months, ascending
                        for (m in rule.byMonth) {
                            tick()
                            val ym = YearMonth.of(year, m)
                            if (startDate.dayOfMonth <= ym.lengthOfMonth()) emit(ym.atDay(startDate.dayOfMonth))
                        }
                    }
                    hasMonths -> {
                        // bound: (ii) at most 12 months x 31 days
                        for (m in rule.byMonth) {
                            val ym = YearMonth.of(year, m)
                            val len = ym.lengthOfMonth()
                            for (dom in 1..len) { // bound: (ii) at most 31
                                tick()
                                val d = ym.atDay(dom)
                                if (monthScopeMatch(d, len)) emit(d)
                            }
                        }
                    }
                    !hasMd && !hasBd -> {
                        tick()
                        val ym = YearMonth.of(year, startDate.monthValue)
                        if (startDate.dayOfMonth <= ym.lengthOfMonth()) emit(ym.atDay(startDate.dayOfMonth))
                    }
                    else -> {
                        val yearLen = Year.of(year).length()
                        val jan1 = LocalDate.of(year, 1, 1)
                        // bound: (ii) at most 366 days of one year
                        for (doy in 1..yearLen) {
                            tick()
                            val d = jan1.plusDays((doy - 1).toLong())
                            val okMd = !hasMd || monthDayMatch(d.dayOfMonth, d.lengthOfMonth())
                            val okBd = !hasBd || byDayMatch(d.dayOfWeek.value, doy, yearLen)
                            if (okMd && okBd) emit(d)
                        }
                    }
                }
            }
        }
    }

    private fun monthOk(m: Int) = !hasMonths || monthMask[m]

    private fun monthDayMatch(dom: Int, len: Int) = mdPos[dom] || mdNeg[len - dom + 1]

    /** DAILY and WEEKLY: BYDAY ordinals are ignored (RFC 5545 allows none there). */
    private fun dailyOrWeeklyMatch(d: LocalDate, weekly: Boolean): Boolean {
        val dow = d.dayOfWeek.value
        val dayOk = if (hasBd) wdSeen[dow] else !weekly || d.dayOfWeek == startDate.dayOfWeek
        return dayOk && (!hasMd || monthDayMatch(d.dayOfMonth, d.lengthOfMonth())) && monthOk(d.monthValue)
    }

    /** MONTHLY, and YEARLY with BYMONTH: BYMONTHDAY and BYDAY (ordinals within the month) both must hold. */
    private fun monthScopeMatch(d: LocalDate, len: Int): Boolean {
        val dom = d.dayOfMonth
        return (!hasMd || monthDayMatch(dom, len)) && (!hasBd || byDayMatch(d.dayOfWeek.value, dom, len))
    }

    /** Whether the day at 1-based position [pos] of a scope of [len] days, weekday [dow], matches some BYDAY entry. */
    private fun byDayMatch(dow: Int, pos: Int, len: Int): Boolean {
        if (wdAny[dow]) return true
        val nth = (pos - 1) / 7 + 1
        val nthFromEnd = (len - pos) / 7 + 1
        return (ordPos[dow] ushr nth) and 1L == 1L || (ordNeg[dow] ushr nthFromEnd) and 1L == 1L
    }

    // ---- COUNT ----

    /**
     * Instances in periods [0, k0). Arithmetic when every period after the first yields the same number
     * ([constantPerPeriod]); else a walk that stops early once COUNT is reached. Null when the series budget runs out.
     */
    private fun countBefore(k0: Long, budget: Budget): Long? {
        if (k0 == 0L) return 0
        val count = rule.count ?: return 0
        val first = periodInstances(0, budget)?.size?.toLong() ?: return null
        val c = constantPerPeriod()
        if (c != null) return first + (k0 - 1) * c
        var n = first
        var k = 1L
        // bound: (iii) every iteration charges perPeriodBound in periodInstances before its work
        while (k < k0 && n < count) {
            n += periodInstances(k, budget)?.size ?: return null
            k++
        }
        return n
    }

    /** Instances per period when it is the same for every period (proved by the rule's shape), else null. */
    private fun constantPerPeriod(): Long? {
        if (rule.bySetPos.isNotEmpty()) return null
        // bound: (ii) every collection operation below is over a domain-bounded Rule set (<= 62 or <= 749)
        val t = times.size.toLong()
        val md = rule.byMonthDay
        val safeMd = md.isNotEmpty() && (md.all { it in 1..28 } || md.all { it in -28..-1 })
        val bd = rule.byDay
        val safeBd = bd.isNotEmpty() && (bd.all { it.n in 1..4 } || bd.all { it.n in -4..-1 })
        return when (rule.freq) {
            Freq.DAILY -> if (!hasMonths && md.isEmpty() && bd.isEmpty()) t else null
            Freq.WEEKLY -> if (!hasMonths && md.isEmpty() && bd.all { it.n == 0 }) {
                (bd.map { it.day }.distinct().size.takeIf { it > 0 } ?: 1) * t
            } else {
                null
            }
            Freq.MONTHLY -> when {
                hasMonths -> null
                md.isEmpty() && bd.isEmpty() -> if (startDate.dayOfMonth <= 28) t else null
                bd.isEmpty() && safeMd -> md.size * t
                md.isEmpty() && safeBd -> bd.size * t
                else -> null
            }
            Freq.YEARLY -> {
                val months = rule.byMonth
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
