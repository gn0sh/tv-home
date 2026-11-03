package tvhome.core.board

import tvhome.core.agenda.AgendaDay
import tvhome.core.agenda.AgendaItem
import tvhome.core.agenda.TimeLabel
import tvhome.core.agenda.WEEK_DAYS
import tvhome.core.ui.Copy
import tvhome.core.ui.QuietMode
import tvhome.core.ui.RowModel
import tvhome.core.ui.formatDate
import tvhome.core.ui.formatDateLong
import tvhome.core.ui.formatTime
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * One timed event drawn on a day column. [startMin] and [endMin] are minutes from the column's local midnight, already
 * clipped to the day and to the grid's hours, with [endMin] at least [WeekGrid.MIN_BLOCK_MIN] after [startMin] so a
 * short event stays visible. [lane] of [lanes]: overlapping events sit side by side, each [1 / lanes] of the width.
 */
data class GridBlock(
    val startMin: Int,
    val endMin: Int,
    val lane: Int,
    val lanes: Int,
    /** "9:00" (12 h, no AM/PM; the axis says it) or "09:00"; "Continues" for an event that started before this day. */
    val time: String,
    val title: String,
    val description: String,
) {
    override fun toString(): String = "GridBlock($startMin-$endMin, lane $lane/$lanes, title=${title.length} chars)"
}

/**
 * One day column. [known] false: the calendar cache does not cover this day (or the calendar is unavailable), so the
 * column says so and has no events. [rows] is the day's full list in agenda order (all-day first), for the day list
 * OK opens: every event is reachable even when its block is too small for text.
 */
data class GridColumn(
    val date: LocalDate,
    val heading: String,
    val isToday: Boolean,
    val known: Boolean,
    val allDay: List<String>,
    val blocks: List<GridBlock>,
    val rows: List<RowModel>,
    val description: String,
) {
    val eventCount: Int get() = rows.size
    override fun toString(): String = "GridColumn($date, known=$known, ${allDay.size} all-day, ${blocks.size} blocks)"
}

/** The Board's week: hours [startHour, endHour) down the side, seven columns, and the "now" minute on today's column. */
data class WeekGrid(
    val startHour: Int,
    val endHour: Int,
    val columns: List<GridColumn>,
    /** Minutes from midnight for the now line on the today column; null when now is outside the grid's hours. */
    val nowMin: Int?,
) {
    val hours: Int get() = endHour - startHour

    /** Most all-day titles any column has (the strip's height follows it, capped by the app). */
    val maxAllDay: Int get() = columns.maxOfOrNull { it.allDay.size } ?: 0

    companion object {
        /** The visible range when nothing falls outside it: 8 AM to 9 PM. */
        const val DEFAULT_START = 8
        const val DEFAULT_END = 21

        /** A block is drawn at least this long; an event with no end counts as [NO_END_MIN]. */
        const val MIN_BLOCK_MIN = 20
        const val NO_END_MIN = 30
    }
}

/** How much text a block of this size can carry at the Board's block type size (22 sp on a 24 sp line). */
object BlockText {
    const val LINE_DP = 24f
    const val PAD_DP = 1f
    const val MIN_WIDTH_DP = 44f

    /** 0: compact (no text; the day list has it); 1: "9:00 Title"; 2 or more: time, then the title over the rest. */
    fun lines(heightDp: Float, widthDp: Float, fontScale: Float = 1f): Int {
        val k = fontScale.coerceAtLeast(1f)
        if (widthDp < MIN_WIDTH_DP * k) return 0
        return ((heightDp - 2 * PAD_DP) / (LINE_DP * k)).toInt().coerceIn(0, 4)
    }

    /** Gap between lanes and around a block, dp. */
    const val BLOCK_GAP_DP = 4f

    /** A block's drawn width in a day column whose inner width (inside its own padding) is [innerColDp]. */
    fun blockWidth(innerColDp: Float, lanes: Int): Float = innerColDp / lanes.coerceAtLeast(1) - BLOCK_GAP_DP

    /** A block's drawn height for [hourDp] per hour. */
    fun blockHeight(b: GridBlock, hourDp: Float): Float = (b.endMin - b.startMin) / 60f * hourDp

    /**
     * Blocks of [c] too small for any text at this size (board-hidden-count-width): the column's "N more" count, by the
     * same widths and heights the column draws with.
     */
    fun hiddenCount(c: GridColumn, hourDp: Float, innerColDp: Float, fontScale: Float = 1f): Int =
        c.blocks.count { lines(blockHeight(it, hourDp), blockWidth(innerColDp, it.lanes), fontScale) == 0 }

    /** An hour label's height (one 22 sp line) at font scale 1. */
    const val LABEL_DP = 28f

    /** Hours between axis labels so no two overlap: ceil(label height / hour height), at least 1. */
    fun labelStep(hourDp: Float, fontScale: Float = 1f): Int {
        if (hourDp <= 0f) return 24
        return kotlin.math.ceil(LABEL_DP * fontScale.coerceAtLeast(1f) / hourDp).toInt().coerceIn(1, 24)
    }
}

/**
 * Builds the week grid from the agenda's [week] days (today first; days missing from it are not known). Pure: the
 * caller passes the clock and zone. [quiet]: every title is "Busy" (spec M7-7), here and in the day lists.
 *
 * Range: [WeekGrid.DEFAULT_START] to [WeekGrid.DEFAULT_END], widened to whole hours so every event that starts on a
 * shown day fits (its start and its end on that day). The part of an event continuing from the day before is clipped to
 * the range's top and never widens it, so one overnight event cannot squash the whole week.
 */
fun weekGrid(week: List<AgendaDay>, known: Boolean, now: Instant, zone: ZoneId, is24h: Boolean, quiet: Boolean): WeekGrid {
    val today = now.atZone(zone).toLocalDate()
    val byDate = if (known) week.associateBy { it.date } else emptyMap()
    val dates = (0 until WEEK_DAYS).map { today.plusDays(it.toLong()) }
    val spans = dates.associateWith { d -> byDate[d]?.items.orEmpty().mapNotNull { span(it, d, zone) } }

    var startMin = WeekGrid.DEFAULT_START * 60
    var endMin = WeekGrid.DEFAULT_END * 60
    for (list in spans.values) for (s in list) {
        if (!s.continues) startMin = minOf(startMin, s.start)
        endMin = maxOf(endMin, s.end)
    }
    val startHour = (startMin / 60).coerceIn(0, 23)
    val endHour = ((endMin + 59) / 60).coerceIn(startHour + 1, 24)
    val lo = startHour * 60
    val hi = endHour * 60

    val columns = dates.map { d ->
        val day = byDate[d]
        val heading = formatDate(d)
        if (day == null) {
            return@map GridColumn(d, heading, d == today, false, emptyList(), emptyList(), emptyList(), "${formatDateLong(d)}, not loaded")
        }
        val allDay = day.items.filter { it.label == TimeLabel.AllDay }.map { QuietMode.title(it.title, quiet, max = MAX_TITLE) }
        // A part continuing from the day before that ends before the visible hours is not drawn (grid-continues-offrange);
        // the day list keeps it.
        val drawn = spans.getValue(d).filter { !(it.continues && it.end <= lo) }.map { s ->
            val a = s.start.coerceIn(lo, hi - WeekGrid.MIN_BLOCK_MIN)
            val b = maxOf(s.end.coerceIn(lo, hi), a + WeekGrid.MIN_BLOCK_MIN)
            Triple(s, a, b)
        }
        val lanes = packLanes(drawn.map { it.second to it.third })
        val blocks = drawn.mapIndexed { i, (s, a, b) ->
            val title = QuietMode.title(s.item.title, quiet, max = MAX_TITLE)
            val time = if (s.continues) Copy.CONTINUES else shortTime(s.start, is24h)
            GridBlock(a, b, lanes[i].first, lanes[i].second, time, title, "$time, $title")
        }
        val rows = day.items.map { rowOf(it, zone, is24h, quiet) }
        val count = rows.size
        val desc = "${formatDateLong(d)}, " + when (count) { 0 -> Copy.NOTHING_SCHEDULED; 1 -> "1 event"; else -> "$count events" }
        GridColumn(d, heading, d == today, true, allDay, blocks, rows, desc)
    }
    val nowLocal = now.atZone(zone)
    val nowMinute = nowLocal.hour * 60 + nowLocal.minute
    return WeekGrid(startHour, endHour, columns, nowMinute.takeIf { it in lo until hi })
}

private const val MAX_TITLE = 80

/** A timed item as minutes on [date]: start clipped to midnight (then [continues]), end clipped to the next midnight. */
private class Span(val item: AgendaItem, val start: Int, val end: Int, val continues: Boolean)

/**
 * Positions are wall-clock minutes (local hour * 60 + minute), like the axis and the now line, so a DST day draws a
 * 9:00 event at 9:00 (grid-dst-minutes). Midnight-to-midnight is 0 to [DAY_MIN]; on the fall-back day the repeated hour
 * shares its minutes, on the spring-forward day the skipped hour is empty.
 */
private fun span(item: AgendaItem, date: LocalDate, zone: ZoneId): Span? {
    if (item.label == TimeLabel.AllDay) return null
    val start = item.startsAt ?: return null
    val dayStart = date.atStartOfDay(zone).toInstant()
    val dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant()
    val end = item.endsAt?.takeIf { it.isAfter(start) } ?: start.plus(Duration.ofMinutes(WeekGrid.NO_END_MIN.toLong()))
    fun clock(t: Instant): Int = t.atZone(zone).toLocalTime().let { it.hour * 60 + it.minute }
    val continues = start.isBefore(dayStart)
    val a = if (continues) 0 else clock(start)
    val b = if (!end.isBefore(dayEnd)) DAY_MIN else clock(end)
    return Span(item, a, maxOf(b, a), continues).takeIf { b > a || !continues }
}

private const val DAY_MIN = 24 * 60

/**
 * Side-by-side lanes for overlapping intervals (half-open, minutes). Intervals are taken by start, then longer first; each
 * goes into the lowest free lane; a cluster (a run of intervals linked by overlap) shares one lane count, so every
 * block of the cluster has the same width. Returns (lane, lanes) per input index.
 */
fun packLanes(intervals: List<Pair<Int, Int>>): List<Pair<Int, Int>> {
    val order = intervals.indices.sortedWith(compareBy<Int>({ intervals[it].first }, { -intervals[it].second }))
    val lane = IntArray(intervals.size)
    val lanes = IntArray(intervals.size)
    var cluster = ArrayList<Int>()
    var clusterEnd = Int.MIN_VALUE
    val laneEnds = ArrayList<Int>()
    fun close() {
        val n = laneEnds.size
        for (i in cluster) lanes[i] = n
        cluster = ArrayList(); laneEnds.clear()
    }
    for (i in order) {
        val (s, e) = intervals[i]
        if (cluster.isNotEmpty() && s >= clusterEnd) close()
        var l = laneEnds.indexOfFirst { it <= s }
        if (l < 0) { l = laneEnds.size; laneEnds += e } else laneEnds[l] = e
        lane[i] = l
        cluster += i
        clusterEnd = if (cluster.size == 1) e else maxOf(clusterEnd, e)
    }
    if (cluster.isNotEmpty()) close()
    return intervals.indices.map { lane[it] to lanes[it] }
}

/** "9:00" / "12:30" (12 h, the axis gives AM/PM) or "09:00" (24 h). */
private fun shortTime(min: Int, is24h: Boolean): String {
    val t = java.time.LocalTime.of((min / 60) % 24, min % 60)
    return if (is24h) formatTime(t, true) else formatTime(t, false).substringBefore(' ')
}

private fun rowOf(item: AgendaItem, zone: ZoneId, is24h: Boolean, quiet: Boolean): RowModel {
    val time = when (val l = item.label) {
        TimeLabel.AllDay -> Copy.ALL_DAY
        TimeLabel.Continues -> Copy.CONTINUES
        is TimeLabel.Starts -> {
            val start = formatTime(l.time, is24h)
            val end = item.endsAt?.atZone(zone)?.toLocalTime()?.let { formatTime(it.withSecond(0).withNano(0), is24h) }
            if (end != null) "$start – $end" else start
        }
    }
    val title = QuietMode.title(item.title, quiet, max = Int.MAX_VALUE)
    return RowModel(time, title, "$time, $title")
}
