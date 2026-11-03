package com.gnosh.tvhome

import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.tv.material3.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import tvhome.core.board.BlockText
import tvhome.core.board.Board
import tvhome.core.board.BoardCell
import tvhome.core.board.BoardForecast
import tvhome.core.board.BoardInputs
import tvhome.core.board.GridColumn
import tvhome.core.board.WeekGrid
import tvhome.core.board.board
import tvhome.core.feeds.FeedsSnapshot
import tvhome.core.feeds.LiveFeeds
import tvhome.core.feeds.PanelSwitches
import tvhome.core.ui.GlanceTokens
import tvhome.core.ui.HomeTheme
import tvhome.core.ui.IdleState
import tvhome.core.ui.Tokens
import tvhome.core.ui.dimmed
import tvhome.core.ui.formatTime
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/** On the Board's root, for TalkBack and the emulator QA / screenshot tour. */
const val BOARD_FULL_SCREEN = "Board full screen"

/** On the day list a column opens. */
const val BOARD_DAY_LIST = "Board day list"

/**
 * The Board (full screen from the dock's Board tile): this week as a grid (seven day columns, hours down the left,
 * timed events as blocks sized by duration, side by side when they overlap, all-day titles under the headers, a now
 * line on today), each day's forecast under its date, active NWS alerts on top, and a bottom strip with the next
 * Predators and Ohio State games (with where they are on) and today's weather. Every word and every block position
 * comes from `:core` ([board], [tvhome.core.board.weekGrid]); this only lays them out, each in its own box.
 *
 * Focus: one focus target per day column (Left/Right), starting on today; OK opens that day's full list (every event,
 * also those whose block is too small for text); Back closes the list, then the Board. The same feeds and calendar
 * session as the home hero: LiveFeeds runs here while the Board is open (the hero is not composed then). Burn-in rules
 * as on the home screen: dim, near-black, drift ([rememberIdleController]).
 */
@Composable
fun BoardFullScreen(theme: HomeTheme, quiet: Boolean, onClose: () -> Unit) {
    val context = LocalContext.current
    val graph = remember(context) { (context.applicationContext as TvHomeApp).graph }
    val now by rememberMinuteClock()
    var feeds by remember { mutableStateOf<LiveFeeds?>(null) }
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(graph, owner) {
        withFrameNanos { }
        val f = feeds ?: withContext(Dispatchers.Default) { graph.feeds }.also { feeds = it }
        owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            delay(BOARD_FEEDS_DELAY_MS)
            withContext(Dispatchers.Default) { f.run() }
        }
    }
    val emptyFeeds = remember { MutableStateFlow(FeedsSnapshot()) }
    val snap by (feeds?.snapshot ?: emptyFeeds).collectAsState()
    val agenda by graph.session.state.collectAsState()
    val settings by produceState(BoardSettings(PanelSwitches(), false), now) {
        value = withContext(Dispatchers.IO) {
            runCatching { BoardSettings(FeedSettings.switches(context), FeedSettings.location(context) != null) }
                .getOrDefault(BoardSettings(PanelSwitches(), false))
        }
    }
    val is24h = DateFormat.is24HourFormat(context)
    val model = remember(now, snap, agenda, settings, is24h, quiet) {
        board(BoardInputs(Instant.now(), ZoneId.systemDefault(), is24h, agenda, snap, settings.switches, settings.hasLocation, quiet))
    }

    val idle = rememberIdleController()
    val dim = idle.state.value == IdleState.DIM
    val shown = if (dim) theme.dimmed() else theme
    var openDay by remember { mutableStateOf<Int?>(null) }
    val columnFocus = remember { List(7) { FocusRequester() } }
    var focusedColumn by remember { mutableStateOf(0) }

    BackHandler(onBack = onClose)
    Box(
        Modifier
            .fillMaxSize()
            .background(color(theme.background))
            .onPreviewKeyEvent { idle.onPreviewKey(it) }
            .semantics { contentDescription = BOARD_FULL_SCREEN },
    ) {
        val day = openDay?.let { model.grid.columns.getOrNull(it) }
        if (day != null) {
            DayList(day, shown) { openDay = null }
        } else {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = Tokens.MARGIN_H_DP.dp, vertical = Tokens.MARGIN_V_DP.dp)
                    .graphicsLayer {
                        val o = idle.drift.value
                        translationX = o.dx.dp.toPx()
                        translationY = o.dy.dp.toPx()
                    },
                verticalArrangement = Arrangement.spacedBy(BOARD_GAP_DP.dp),
            ) {
                StatusRows(model, shown)
                WeekView(model, shown, columnFocus, onFocusColumn = { focusedColumn = it }, onOpen = { i -> openDay = i }, modifier = Modifier.weight(1f))
                BottomStrip(model, shown)
            }
            LaunchedEffect(Unit) {
                // On open, and back from a day list: focus the column it came from (today at first).
                withFrameNanos { }
                runCatching { columnFocus[focusedColumn].requestFocus() }
            }
        }
        if (idle.state.value == IdleState.NEAR_BLACK) NearBlackLayer(idle.drift)
    }
}

private const val BOARD_FEEDS_DELAY_MS = 300L
private const val BOARD_GAP_DP = 8
private const val GUTTER_DP = 72
private const val LINE_SP = GlanceTokens.MIN_SP // 22 sp: 33 px at 1080p
private const val LINE_DP = 28
private const val MAX_ALL_DAY_ROWS = 2

private val BLOCK_FILL = Color(0xFF2B4C7E)
private val TODAY_FILL = Color(0xFF18202A)
private val ALERT_FILL = color(GlanceTokens.ALERT_FILL)
private val AMBER = color(Tokens.AMBER)

private data class BoardSettings(val switches: PanelSwitches, val hasLocation: Boolean)

/** Alerts (severe on a red fill) and the calendar's age line, each a full-width row; nothing when there is neither. */
@Composable
private fun StatusRows(model: Board, theme: HomeTheme) {
    for (a in model.alerts) {
        Box(
            Modifier.fillMaxWidth().height(36.dp)
                .background(if (a.severe) ALERT_FILL else color(theme.tile), RoundedCornerShape(8.dp))
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(a.text, color = color(theme.text), fontSize = LINE_SP.sp, fontWeight = if (a.severe) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    val age = model.calendarAge
    if (age != null) {
        Text(
            (if (age.emphasised) "⚠ " else "") + age.text,
            color = if (age.emphasised) AMBER else color(theme.secondaryText), fontSize = LINE_SP.sp,
            fontWeight = if (age.emphasised) FontWeight.Bold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Headers (date, forecast), the all-day strip, the hour grid, and the per-column "more" row. */
@Composable
private fun WeekView(
    model: Board,
    theme: HomeTheme,
    focus: List<FocusRequester>,
    onFocusColumn: (Int) -> Unit,
    onOpen: (Int) -> Unit,
    modifier: Modifier,
) {
    val grid = model.grid
    val allDayRows = minOf(grid.maxAllDay, MAX_ALL_DAY_ROWS)
    Column(modifier) {
        Row(Modifier.fillMaxWidth()) {
            Spacer(Modifier.width(GUTTER_DP.dp))
            grid.columns.forEachIndexed { i, c -> HeaderCell(c, model.forecast.getOrNull(i), theme) }
        }
        if (allDayRows > 0) {
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Spacer(Modifier.width(GUTTER_DP.dp))
                grid.columns.forEach { c -> AllDayCell(c, allDayRows, theme) }
            }
        }
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f).padding(top = 6.dp)) {
            val bodyDp = maxHeight.value - LINE_DP // the "more" row under the grid
            val hourDp = bodyDp / grid.hours
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().height(bodyDp.dp)) {
                    HourAxis(grid, hourDp, theme)
                    grid.columns.forEachIndexed { i, c ->
                        DayColumn(grid, c, hourDp, theme, focus[i], onFocus = { onFocusColumn(i) }, onOpen = { if (c.known) onOpen(i) },
                            message = if (i == 0) model.calendarMessage else null)
                    }
                }
                Row(Modifier.fillMaxWidth().height(LINE_DP.dp)) {
                    Spacer(Modifier.width(GUTTER_DP.dp))
                    BoxWithConstraints(Modifier.weight(1f).fillMaxHeight()) {
                        val colDp = maxWidth.value / grid.columns.size
                        Row(Modifier.fillMaxSize()) {
                            grid.columns.forEach { c ->
                                val hidden = c.blocks.count { b ->
                                    BlockText.lines((b.endMin - b.startMin) / 60f * hourDp, colDp / b.lanes - 4) == 0
                                }
                                Box(Modifier.width(colDp.dp).fillMaxHeight().padding(horizontal = 4.dp), contentAlignment = Alignment.CenterStart) {
                                    if (hidden > 0) {
                                        Text("$hidden more · OK", color = color(theme.secondaryText), fontSize = LINE_SP.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RowScope.HeaderCell(c: GridColumn, f: BoardForecast?, theme: HomeTheme) {
    Column(
        Modifier.weight(1f)
            .background(if (c.isToday) TODAY_FILL else Color.Transparent, RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
            .clearAndSetSemantics { },
    ) {
        Text(c.heading, color = if (c.isToday) color(theme.focusRing) else color(theme.text), fontSize = LINE_SP.sp,
            fontWeight = if (c.isToday) FontWeight.Bold else FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(f?.temps ?: "", color = color(theme.secondaryText), fontSize = LINE_SP.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(f?.rain ?: "", color = AMBER, fontSize = LINE_SP.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Up to [rows] all-day titles; when a day has more, the last row says "+N more". */
@Composable
private fun RowScope.AllDayCell(c: GridColumn, rows: Int, theme: HomeTheme) {
    Column(Modifier.weight(1f).padding(horizontal = 2.dp).clearAndSetSemantics { }, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        val shownTitles = if (c.allDay.size > rows) c.allDay.take(rows - 1) else c.allDay
        for (t in shownTitles) {
            Text(t, color = color(theme.text), fontSize = LINE_SP.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().height(LINE_DP.dp).background(color(theme.tile), RoundedCornerShape(4.dp)).padding(horizontal = 6.dp))
        }
        if (c.allDay.size > rows) {
            Text("+${c.allDay.size - shownTitles.size} more", color = color(theme.secondaryText), fontSize = LINE_SP.sp, maxLines = 1,
                modifier = Modifier.height(LINE_DP.dp).padding(horizontal = 6.dp))
        }
    }
}

@Composable
private fun HourAxis(grid: WeekGrid, hourDp: Float, theme: HomeTheme) {
    val step = BlockText.labelStep(hourDp)
    val is24h = DateFormat.is24HourFormat(LocalContext.current)
    Box(Modifier.width(GUTTER_DP.dp).fillMaxHeight().clipToBounds().clearAndSetSemantics { }) {
        for (h in grid.startHour until grid.endHour step step) {
            val label = formatTime(LocalTime.of(h, 0), is24h).let { if (is24h) it else it.replace(":00", "") }
            Text(label, color = color(theme.secondaryText), fontSize = LINE_SP.sp, maxLines = 1,
                modifier = Modifier.offset(y = ((h - grid.startHour) * hourDp).dp))
        }
    }
}

/**
 * One day: its own box, focusable (ring when focused), the blocks placed by minute, the now line on today. A column the
 * calendar does not cover says "Not loaded yet"; [message] (first column) says why the whole calendar is missing.
 */
@Composable
private fun RowScope.DayColumn(
    grid: WeekGrid,
    c: GridColumn,
    hourDp: Float,
    theme: HomeTheme,
    requester: FocusRequester,
    onFocus: () -> Unit,
    onOpen: () -> Unit,
    message: String?,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(6.dp)
    BoxWithConstraints(
        Modifier
            .weight(1f)
            .fillMaxHeight()
            .padding(horizontal = 2.dp)
            .background(if (c.isToday) TODAY_FILL else Color.Transparent, shape)
            .then(if (focused) Modifier.border(Tokens.RING_DP.dp, color(theme.focusRing), shape) else Modifier)
            .focusRequester(requester)
            .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocus() }
            .clickable(onClick = onOpen)
            .clearAndSetSemantics { contentDescription = c.description }
            .clipToBounds(),
    ) {
        val colDp = maxWidth.value
        val top = grid.startHour * 60
        if (!c.known) {
            Text(message ?: tvhome.core.board.BoardCopy.NOT_LOADED, color = color(theme.secondaryText), fontSize = LINE_SP.sp,
                maxLines = 6, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(6.dp))
        }
        for (b in c.blocks) {
            val y = (b.startMin - top) / 60f * hourDp
            val h = (b.endMin - b.startMin) / 60f * hourDp
            val laneW = colDp / b.lanes
            val w = laneW - 4
            val lines = BlockText.lines(h, w)
            Box(
                Modifier
                    .offset(x = (laneW * b.lane + 2).dp, y = (y + 1).dp)
                    .size(w.dp, (h - 2).coerceAtLeast(2f).dp)
                    .background(BLOCK_FILL, RoundedCornerShape(4.dp))
                    .padding(horizontal = 4.dp, vertical = BlockText.PAD_DP.dp),
            ) {
                when {
                    lines == 1 -> Text("${b.time} ${b.title}", color = color(theme.text), fontSize = LINE_SP.sp, lineHeight = BlockText.LINE_DP.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    lines >= 2 -> Column {
                        Text(b.time, color = color(theme.secondaryText), fontSize = LINE_SP.sp, lineHeight = BlockText.LINE_DP.sp, maxLines = 1)
                        Text(b.title, color = color(theme.text), fontSize = LINE_SP.sp, lineHeight = BlockText.LINE_DP.sp, maxLines = lines - 1,
                            overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        val nowMin = grid.nowMin
        if (c.isToday && nowMin != null) {
            Box(Modifier.offset(y = ((nowMin - top) / 60f * hourDp).dp).fillMaxWidth().height(3.dp).background(AMBER))
        }
    }
}

/** Next Predators game, next Ohio State game, today's weather: three boxes side by side. */
@Composable
private fun BottomStrip(model: Board, theme: HomeTheme) {
    Row(Modifier.fillMaxWidth().heightIn(min = (3 * LINE_DP).dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        for (cell in model.games) CellBox(cell, theme)
        CellBox(model.weather, theme)
    }
}

@Composable
private fun RowScope.CellBox(cell: BoardCell, theme: HomeTheme) {
    val body = if (cell.warn) AMBER else color(theme.text)
    Column(
        Modifier.weight(1f).semantics(mergeDescendants = true) { contentDescription = (listOf(cell.heading) + cell.lines).joinToString(". ") },
    ) {
        Row {
            Text(cell.heading, color = color(theme.secondaryText), fontSize = LINE_SP.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            cell.lines.firstOrNull()?.let {
                Spacer(Modifier.width(10.dp))
                Text(it, color = body, fontSize = LINE_SP.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        for (line in cell.lines.drop(1).take(2)) {
            Text(line, color = body, fontSize = LINE_SP.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** A day's full list (every event, in agenda order). Rows focusable so a long day scrolls; Back returns to the week. */
@Composable
private fun DayList(c: GridColumn, theme: HomeTheme, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val first = remember { FocusRequester() }
    val panel = remember { FocusRequester() }
    LaunchedEffect(c.date) {
        withFrameNanos { }
        runCatching { if (c.rows.isEmpty()) panel.requestFocus() else first.requestFocus() }
    }
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = Tokens.MARGIN_H_DP.dp, vertical = Tokens.MARGIN_V_DP.dp)
            .semantics { contentDescription = BOARD_DAY_LIST },
    ) {
        Text(c.description, color = color(theme.text), fontSize = Tokens.HEADING_SP.sp, fontWeight = FontWeight.Medium, maxLines = 1,
            overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(12.dp))
        if (c.rows.isEmpty()) {
            Text(tvhome.core.ui.Copy.NOTHING_SCHEDULED, color = color(theme.secondaryText), fontSize = Tokens.ROW_SP.sp,
                modifier = Modifier.focusRequester(panel).focusable())
        }
        LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(Tokens.ROW_GAP_DP.dp)) {
            itemsIndexed(c.rows) { i, r ->
                var focused by remember { mutableStateOf(false) }
                val shape = RoundedCornerShape(8.dp)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = Tokens.ROW_HEIGHT_DP.dp)
                        .background(if (focused) color(theme.focusFill) else Color.Transparent, shape)
                        .then(if (focused) Modifier.border(Tokens.RING_DP.dp, color(theme.focusRing), shape) else Modifier)
                        .then(if (i == 0) Modifier.focusRequester(first) else Modifier)
                        .onFocusChanged { focused = it.isFocused }
                        .focusable()
                        .clearAndSetSemantics { contentDescription = r.description }
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(r.time, color = color(theme.secondaryText), fontSize = Tokens.SECONDARY_SP.sp, maxLines = 1,
                        modifier = Modifier.width(DAY_LIST_TIME_DP.dp))
                    Spacer(Modifier.width(20.dp))
                    Text(r.title, color = color(theme.text), fontSize = Tokens.ROW_SP.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** Wide enough for "10:30 AM – 11:45 AM" at 24 sp. */
private const val DAY_LIST_TIME_DP = 300
