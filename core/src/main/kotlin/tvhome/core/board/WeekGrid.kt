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
    /** The event's own minutes on this day (wall clock, before clipping to the grid): what [fit] re-clips from. */
    val spanStart: Int = startMin,
    val spanEnd: Int = endMin,
    /** Started the day before (drawn from midnight); never widens the range. */
    val continues: Boolean = false,
    /** The start with AM/PM ("6:00 AM") or 24 h ("06:00"): what a block pinned to the grid's edge says ([pinned]). */
    val fullTime: String = time,
    /** Set by [fit]: -1 starts above the shown hours (pinned to the top, "↑ 6:00 AM"), 1 below them ("↓ 10:00 PM"), else 0. */
    val pinned: Int = 0,
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
    /** Blocks too narrow for any text once fitted ([fit]): the column's "N more · OK" count. */
    val hidden: Int = 0,
) {
    val eventCount: Int get() = rows.size

    /**
     * The day list's line when it has no rows: "Nothing scheduled" only for a day the calendar covers; a day it does not
     * cover says "Not loaded yet" (never "Nothing scheduled" for data we do not have).
     */
    val emptyText: String get() = if (known) Copy.NOTHING_SCHEDULED else BoardCopy.NOT_LOADED
    override fun toString(): String = "GridColumn($date, known=$known, ${allDay.size} all-day, ${blocks.size} blocks)"
}

/** The Board's week: hours [startHour, endHour) down the side, seven columns, and the "now" minute on today's column. */
data class WeekGrid(
    val startHour: Int,
    val endHour: Int,
    val columns: List<GridColumn>,
    /** Minutes from midnight for the now line on the today column; null when now is outside the grid's hours. */
    val nowMin: Int?,
    /** Set by [fit]: dp per hour, and whether the "N more" row under the grid is needed (some block has no room for text). */
    val hourDp: Float? = null,
    val moreRow: Boolean = false,
    /** Now as minutes from local midnight (wall clock), whatever the range: [fit] re-derives [nowMin] from it. */
    val nowOfDay: Int = -1,
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
        return ((heightDp - GAP_Y_DP - 2 * PAD_DP) / (LINE_DP * k) + 0.001f).toInt().coerceIn(0, 4)
    }

    /** Space the app leaves between blocks stacked in time (the drawn box is this much shorter than its slot). */
    const val GAP_Y_DP = 2f

    /** The smallest slot that still carries one line of "time title" at [fontScale]. */
    fun oneLineDp(fontScale: Float = 1f): Float = LINE_DP * fontScale.coerceAtLeast(1f) + 2 * PAD_DP + GAP_Y_DP

    /** An hour is drawn at least this tall (per font scale) when the range has to be cut to fit ([fit]). */
    const val MIN_HOUR_DP = 32f

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
            val full = if (s.continues) Copy.CONTINUES else fullTime(s.start, is24h)
            GridBlock(a, b, lanes[i].first, lanes[i].second, time, title, "$time, $title", s.start, s.end, s.continues, full)
        }
        val rows = day.items.map { rowOf(it, zone, is24h, quiet) }
        val count = rows.size
        val desc = "${formatDateLong(d)}, " + when (count) { 0 -> Copy.NOTHING_SCHEDULED; 1 -> "1 event"; else -> "$count events" }
        GridColumn(d, heading, d == today, true, allDay, blocks, rows, desc)
    }
    val nowLocal = now.atZone(zone)
    val nowMinute = nowLocal.hour * 60 + nowLocal.minute
    return WeekGrid(startHour, endHour, columns, nowMinute.takeIf { it in lo until hi }, nowOfDay = nowMinute)
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

/** "6:00 AM" (12 h) or "06:00" (24 h). */
private fun fullTime(min: Int, is24h: Boolean): String = formatTime(java.time.LocalTime.of((min / 60) % 24, min % 60), is24h)

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

/**
 * The room the grid body has, as the app measures it: [bodyDp] tall (the hours, without the "more" row), each day column
 * [innerColDp] wide inside its padding, [moreRowDp] for the "N more · OK" row, at the user's [fontScale].
 */
data class GridFit(val bodyDp: Float, val innerColDp: Float, val moreRowDp: Float, val fontScale: Float = 1f)

/**
 * The grid sized to [f] (board-blocks-readable): every block gets at least one line of "time title", like a calendar's
 * week view, and overlaps are packed on those drawn slots so a lifted short block never covers the next one.
 *
 * - Hours: an hour is at least [BlockText.MIN_HOUR_DP] (font-scaled). When the week's range does not fit, the window of
 *   the hours that fit is the one where most events start, then the one covering most event minutes, then the one
 *   nearest [WeekGrid.DEFAULT_START]. An event outside it is pinned to the top or bottom edge, still labelled with its
 *   own start time, and the day list has it in full.
 * - Blocks: at least [BlockText.oneLineDp] tall. A block still without text is one too narrow (many side-by-side
 *   overlaps); only then does its column count it in [GridColumn.hidden] and the grid reserve the "more" row
 *   ([WeekGrid.moreRow]), fitted again in the smaller height.
 */
fun WeekGrid.fit(f: GridFit): WeekGrid {
    val first = fitTo(f.bodyDp, f)
    if (first.columns.none { it.hidden > 0 }) return first
    return fitTo(f.bodyDp - f.moreRowDp, f).copy(moreRow = true)
}

private fun WeekGrid.fitTo(bodyDp: Float, f: GridFit): WeekGrid {
    val k = f.fontScale.coerceAtLeast(1f)
    val body = bodyDp.coerceAtLeast(1f)
    val maxHours = (body / (BlockText.MIN_HOUR_DP * k)).toInt().coerceIn(1, 24)
    val (start, end) = if (hours <= maxHours) startHour to endHour else bestWindow(maxHours)
    val hourDp = body / (end - start)
    val lo = start * 60
    val hi = end * 60
    val minSlot = maxOf(WeekGrid.MIN_BLOCK_MIN, kotlin.math.ceil(BlockText.oneLineDp(k) / hourDp * 60f).toInt()).coerceAtMost(hi - lo)
    val cols = columns.map { c ->
        if (!c.known) return@map c.copy(hidden = 0)
        val kept = c.blocks.filter { !(it.continues && it.spanEnd <= lo) }
        val slots = kept.map { b ->
            val a = b.spanStart.coerceIn(lo, hi - minSlot)
            a to maxOf(b.spanEnd.coerceIn(lo, hi), a + minSlot)
        }
        val lanes = packLanes(slots)
        val blocks = kept.mapIndexed { i, b ->
            // pinned-block-ampm: a block moved to an edge says which way and carries AM/PM, so "6:00" by the 2 PM label
            // never reads as 6 PM.
            val pin = when {
                b.continues -> 0
                b.spanStart < lo -> -1
                b.spanStart >= hi -> 1
                else -> 0
            }
            val time = when (pin) { -1 -> "↑ ${b.fullTime}"; 1 -> "↓ ${b.fullTime}"; else -> b.time }
            b.copy(startMin = slots[i].first, endMin = slots[i].second, lane = lanes[i].first, lanes = lanes[i].second, time = time, pinned = pin)
        }
        val placed = c.copy(blocks = blocks)
        placed.copy(hidden = BlockText.hiddenCount(placed, hourDp, f.innerColDp, f.fontScale))
    }
    return copy(startHour = start, endHour = end, columns = cols, nowMin = nowOfDay.takeIf { it in lo until hi }, hourDp = hourDp, moreRow = false)
}

/** The [hours]-long window inside the current range that shows the most of the week (see [fit]). */
private fun WeekGrid.bestWindow(hours: Int): Pair<Int, Int> {
    val blocks = columns.filter { it.known }.flatMap { it.blocks }
    fun starts(s: Int) = blocks.count { !it.continues && it.spanStart >= s * 60 && it.spanStart < (s + hours) * 60 }
    fun minutes(s: Int) = blocks.sumOf { maxOf(0, minOf(it.spanEnd, (s + hours) * 60) - maxOf(it.spanStart, s * 60)) }
    val best = (startHour..endHour - hours).sortedWith(
        compareByDescending<Int> { starts(it) }.thenByDescending { minutes(it) }.thenBy { kotlin.math.abs(it - WeekGrid.DEFAULT_START) },
    ).first()
    return best to best + hours
}
