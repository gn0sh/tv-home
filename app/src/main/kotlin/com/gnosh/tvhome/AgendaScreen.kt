package com.gnosh.tvhome

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import kotlinx.coroutines.launch
import tvhome.core.ui.AgeLine
import tvhome.core.ui.Copy
import tvhome.core.ui.DayModel
import tvhome.core.ui.RowModel
import tvhome.core.ui.ScreenModel
import tvhome.core.ui.Tokens

internal fun color(rgb: Int): Color = Color(0xFF000000.toInt() or rgb)

private val BG = color(Tokens.BACKGROUND)
private val TEXT = color(Tokens.TEXT)
private val SECONDARY = color(Tokens.SECONDARY)
private val AMBER = color(Tokens.AMBER)
private val FILL = color(Tokens.FOCUS_FILL)
private val RING = color(Tokens.FOCUS_RING)

/**
 * Draws a [ScreenModel]; every word and row was decided in `:core` (`screenModel`), so this file only lays things out.
 * Dark theme, 5% overscan margins, one column. In M1 the only focusable things are the rows and the screen root, so
 * there is no focus trap; Back is not handled here and exits the activity.
 */
@Composable
fun AgendaScreen(model: ScreenModel) {
    Box(
        Modifier
            .fillMaxSize()
            .background(BG)
            .focusable() // keeps focus on the root when there is no row, so D-pad presses have somewhere to land
            .padding(horizontal = Tokens.MARGIN_H_DP.dp, vertical = Tokens.MARGIN_V_DP.dp),
    ) {
        when (model) {
            is ScreenModel.Message -> MessageBody(model)
            is ScreenModel.Agenda -> AgendaBody(model)
        }
    }
}

@Composable
private fun MessageBody(m: ScreenModel.Message) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(m.title, color = TEXT, fontSize = Tokens.HEADING_SP.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
        m.detail?.let { detail ->
            Spacer(Modifier.height(12.dp))
            Text(detail, color = SECONDARY, fontSize = Tokens.ROW_SP.sp, textAlign = TextAlign.Center)
        }
    }
}

/** What the list holds, top to bottom. Built once per model. */
private sealed interface Entry {
    val key: String

    class Empty(override val key: String) : Entry
    class Heading(override val key: String, val day: DayModel) : Entry

    /** [flat] indexes the focusable rows; [headingItem] is the list index of this day's heading item, if it has one. */
    class Row(override val key: String, val flat: Int, val row: RowModel, val headingItem: Int?) : Entry
}

private fun entriesOf(m: ScreenModel.Agenda): List<Entry> {
    val out = ArrayList<Entry>()
    var flat = 0
    // Rows are keyed by what identifies them (day, time, title, occurrence), not by position, so a refresh that
    // inserts an event keeps focus on the same event. Identical rows get an occurrence number.
    val seen = HashMap<String, Int>()
    fun keyOf(day: String, r: RowModel): String {
        val base = "$day|${r.time}|${r.title}"
        val n = seen.merge(base, 1, Int::plus)!!
        return "$base|$n"
    }
    // Today's heading is the fixed header, so its rows have no heading item above them.
    if (m.today.rows.isEmpty()) out += Entry.Empty("today-empty")
    m.today.rows.forEach { out += Entry.Row(keyOf("t", it), flat, it, null); flat++ }
    val tomorrowHeading = out.size
    out += Entry.Heading("tomorrow-heading", m.tomorrow)
    if (m.tomorrow.rows.isEmpty()) out += Entry.Empty("tomorrow-empty")
    m.tomorrow.rows.forEachIndexed { i, r -> out += Entry.Row(keyOf("m", r), flat, r, if (i == 0) tomorrowHeading else null); flat++ }
    return out
}

@Composable
private fun AgendaBody(m: ScreenModel.Agenda) {
    Column(Modifier.widthIn(max = Tokens.MAX_CONTENT_WIDTH_DP.dp).fillMaxHeight()) {
        Header(m.today, m.age)
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(SECONDARY))
        Spacer(Modifier.height(12.dp))
        AgendaList(m, Modifier.weight(1f))
        m.footer?.let { footer ->
            Spacer(Modifier.height(8.dp))
            Text(footer, color = AMBER, fontSize = Tokens.SECONDARY_SP.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun Header(today: DayModel, age: AgeLine) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
        Row(
            Modifier.semantics(mergeDescendants = true) { heading(); contentDescription = today.headingDescription },
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(today.heading, color = TEXT, fontSize = Tokens.HEADING_SP.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.width(16.dp))
            Text(today.date, color = SECONDARY, fontSize = Tokens.SECONDARY_SP.sp)
        }
        Spacer(Modifier.weight(1f))
        AgeText(age)
    }
}

/**
 * "Updated 4 min ago". Emphasised: amber, bold, with a warning triangle (never colour alone). The live region speaks
 * [AgeLine.liveDescription], which changes only when freshness changes, so TalkBack is silent as the minutes tick.
 */
@Composable
private fun AgeText(age: AgeLine) {
    Row(
        Modifier.clearAndSetSemantics {
            liveRegion = LiveRegionMode.Polite
            contentDescription = age.liveDescription
        },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (age.emphasised) {
            Text("⚠", color = AMBER, fontSize = Tokens.AGE_SP.sp)
            Spacer(Modifier.width(8.dp))
        }
        Text(
            age.text,
            color = if (age.emphasised) AMBER else SECONDARY,
            fontSize = Tokens.AGE_SP.sp,
            fontWeight = if (age.emphasised) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

@Composable
private fun AgendaList(m: ScreenModel.Agenda, modifier: Modifier) {
    val entries = remember(m) { entriesOf(m) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // Initial focus is chosen once, when the list first appears. Later refreshes never move focus or scroll position.
    val initialFlat = remember { m.initialFocus }
    val initialRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        val target = initialFlat ?: return@LaunchedEffect
        val item = entries.indexOfFirst { it is Entry.Row && it.flat == target }
        if (item < 0) return@LaunchedEffect
        listState.scrollToItem(item)
        // Wait until the row is laid out (in the visible items) so its FocusRequester is attached; give up after a
        // few frames rather than loop forever. Retry once if the first request races the attach.
        var frames = 0
        while (listState.layoutInfo.visibleItemsInfo.none { it.index == item } && frames < 30) {
            withFrameNanos { }
            frames++
        }
        withFrameNanos { }
        if (runCatching { initialRequester.requestFocus() }.isFailure) {
            withFrameNanos { }
            runCatching { initialRequester.requestFocus() }
        }
    }

    Box(modifier) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(Tokens.ROW_GAP_DP.dp),
        ) {
            itemsIndexed(entries, key = { _, e -> e.key }) { _, e ->
                when (e) {
                    is Entry.Empty -> Text(
                        Copy.NOTHING_SCHEDULED,
                        color = SECONDARY,
                        fontSize = Tokens.ROW_SP.sp,
                        modifier = Modifier.padding(start = 16.dp),
                    )
                    is Entry.Heading -> DayHeading(e.day)
                    is Entry.Row -> AgendaRow(
                        row = e.row,
                        requester = if (e.flat == initialFlat) initialRequester else null,
                        onFocused = {
                            // Focus skips headings; bring this day's heading into view with its first row.
                            val h = e.headingItem
                            if (h != null && listState.firstVisibleItemIndex > h) scope.launch { listState.animateScrollToItem(h) }
                        },
                    )
                }
            }
        }
        ScrollCues(listState)
    }
}

@Composable
private fun DayHeading(day: DayModel) {
    Row(
        Modifier
            .padding(top = 12.dp)
            .semantics(mergeDescendants = true) { heading(); contentDescription = day.headingDescription },
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(day.heading, color = TEXT, fontSize = Tokens.DAY_HEADING_SP.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.width(16.dp))
        Text(day.date, color = SECONDARY, fontSize = Tokens.SECONDARY_SP.sp)
    }
}

@Composable
private fun AgendaRow(row: RowModel, requester: FocusRequester?, onFocused: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)
    val quietTime = row.time == Copy.ALL_DAY || row.time == Copy.CONTINUES
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = Tokens.ROW_HEIGHT_DP.dp)
            .then(if (requester != null) Modifier.focusRequester(requester) else Modifier)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            }
            .background(if (focused) FILL else Color.Transparent, shape)
            .then(if (focused) Modifier.border(Tokens.RING_DP.dp, RING, shape) else Modifier)
            .focusable()
            // One sentence per row for TalkBack ("9:00 AM, Dentist"); the two Text children are not read separately.
            .clearAndSetSemantics { contentDescription = row.description }
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            row.time,
            color = if (quietTime) SECONDARY else TEXT,
            fontSize = (if (quietTime) Tokens.SECONDARY_SP else Tokens.ROW_SP).sp,
            fontWeight = if (quietTime) FontWeight.Normal else FontWeight.Medium,
            textAlign = TextAlign.End,
            maxLines = 1,
            modifier = Modifier.width(Tokens.TIME_COLUMN_DP.dp),
        )
        Spacer(Modifier.width(20.dp))
        // Calendar text is plain text from outside: one line, ellipsis, no markup.
        Text(row.title, color = TEXT, fontSize = Tokens.ROW_SP.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Small chevrons at the right edge, only while rows exist off screen in that direction. */
@Composable
private fun BoxScope.ScrollCues(state: LazyListState) {
    if (state.canScrollBackward) {
        Text("▲", color = SECONDARY, fontSize = Tokens.SECONDARY_SP.sp, modifier = Modifier.align(Alignment.TopEnd))
    }
    if (state.canScrollForward) {
        Text("▼", color = SECONDARY, fontSize = Tokens.SECONDARY_SP.sp, modifier = Modifier.align(Alignment.BottomEnd))
    }
}
