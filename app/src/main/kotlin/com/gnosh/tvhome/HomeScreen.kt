package com.gnosh.tvhome

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import tvhome.core.apps.LauncherApp
import tvhome.core.ui.CALENDAR_TILE_LABEL
import tvhome.core.ui.CalendarTile
import tvhome.core.ui.HomeTheme
import tvhome.core.ui.ScreenModel
import tvhome.core.ui.Tokens

/** One tile: the app, and its banner (16:9) or, failing that, its icon, already drawn to a bitmap off the main thread. */
class TileApp(val app: LauncherApp, val image: ImageBitmap?, val isBanner: Boolean)

/** The tile row's data: still loading, loaded (possibly empty), or the package manager query failed. */
sealed interface AppsState {
    data object Loading : AppsState
    data class Loaded(val apps: List<TileApp>) : AppsState
    data object Failed : AppsState
}

private const val FOCUS_SCALE = 1.1f

/** On the full-screen calendar's root, for TalkBack and for the emulator QA check (scripts/ci/emulator-qa.sh). */
const val CALENDAR_FULL_SCREEN = "Calendar full screen"

/**
 * M2b home screen: one row of tiles. The first is the Calendar tile ([tile]); the app tiles follow. OK on the Calendar
 * tile switches this screen to the full-screen agenda ([agenda]); Back there returns to the row with focus on the
 * Calendar tile, and Back on the row is not handled here, so it exits the activity as before. The calendar tile never
 * waits on, or fails with, the package manager: while apps load or fail the row is the Calendar tile plus a message.
 * Initial focus goes to the Calendar tile. Colours and tile size come from [theme].
 */
@Composable
fun HomeScreen(apps: AppsState, theme: HomeTheme, onLaunch: (LauncherApp) -> Unit, tile: CalendarTile, agenda: ScreenModel) {
    var calendarOpen by rememberSaveable { mutableStateOf(false) }
    if (calendarOpen) {
        CalendarFullScreen(agenda, theme, onClose = { calendarOpen = false })
        return
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(color(theme.background))
            // Not focusable, on purpose (home-back-twice): Compose maps Back to FocusDirection.Exit, which moves focus
            // to the nearest focusable ancestor of the focused tile and consumes the key. A focusable root here made
            // the first Back land on it and only the second reach the activity. With no focusable ancestor, Exit
            // fails and Back exits as before. The Calendar tile is always present, so focus always has a tile.
            .padding(horizontal = Tokens.MARGIN_H_DP.dp, vertical = Tokens.MARGIN_V_DP.dp),
    ) {
        TileRow(apps, theme, tile, onOpenCalendar = { calendarOpen = true }, onLaunch)
    }
}

/**
 * The agenda filling the screen (reuses [AgendaPanel]: rows focusable, list scrolls with focus, focus starts on the
 * first row or on the panel when there are none). Back closes it.
 */
@Composable
private fun CalendarFullScreen(agenda: ScreenModel, theme: HomeTheme, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    Box(
        Modifier
            .fillMaxSize()
            .background(color(theme.background))
            .semantics { contentDescription = CALENDAR_FULL_SCREEN }
            .padding(horizontal = Tokens.MARGIN_H_DP.dp, vertical = Tokens.MARGIN_V_DP.dp),
    ) {
        AgendaPanel(agenda, Modifier.fillMaxSize(), takeFocus = true)
    }
}

@Composable
private fun TileRow(state: AppsState, theme: HomeTheme, tile: CalendarTile, onOpenCalendar: () -> Unit, onLaunch: (LauncherApp) -> Unit) {
    val apps = (state as? AppsState.Loaded)?.apps.orEmpty()
    val calendar = remember { FocusRequester() }
    // Every time the row enters composition (launch, and coming back from the full-screen calendar) the Calendar tile
    // takes focus. It is the first item, so it is composed on the first frame.
    LaunchedEffect(Unit) {
        withFrameNanos { }
        if (runCatching { calendar.requestFocus() }.isFailure) {
            withFrameNanos { }
            runCatching { calendar.requestFocus() }
        }
    }
    // Content padding leaves room for the 1.1x focus scale inside the safe area.
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = (theme.tileWidthDp / 16).dp, vertical = (theme.tileHeightDp / 13).dp),
        horizontalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        item(key = "calendar") { CalendarTileView(tile, theme, calendar, onOpenCalendar) }
        if (apps.isEmpty()) {
            item(key = "apps-message") {
                val msg = when (state) {
                    AppsState.Loading -> "Loading apps…"
                    AppsState.Failed -> "Couldn't list apps"
                    is AppsState.Loaded -> "No TV apps found"
                }
                Box(Modifier.height(theme.tileHeightDp.dp), contentAlignment = Alignment.Center) {
                    Text(msg, color = color(theme.secondaryText), fontSize = Tokens.SECONDARY_SP.sp)
                }
            }
        } else {
            items(apps, key = { it.app.packageName }) { t -> AppTile(t, theme) { onLaunch(t.app) } }
        }
    }
}

/** The tile box both kinds of tile share: size, focus scale, fill and ring, and OK/Enter to click. */
@Composable
private fun TileFrame(
    theme: HomeTheme,
    label: String,
    description: String,
    requester: FocusRequester?,
    onClick: () -> Unit,
    content: @Composable BoxScope.(focused: Boolean) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)
    Column(Modifier.width(theme.tileWidthDp.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(theme.tileWidthDp.dp, theme.tileHeightDp.dp)
                .graphicsLayer {
                    val s = if (focused) FOCUS_SCALE else 1f
                    scaleX = s; scaleY = s
                }
                .clip(shape)
                .background(color(if (focused) theme.focusFill else theme.tile), shape)
                .then(if (focused) Modifier.border(Tokens.RING_DP.dp, color(theme.focusRing), shape) else Modifier)
                .then(if (requester != null) Modifier.focusRequester(requester) else Modifier)
                .onFocusChanged { focused = it.isFocused }
                .semantics { contentDescription = description }
                .clickable(onClick = onClick), // focusable on TV; OK/Enter/D-pad centre clicks
            contentAlignment = Alignment.Center,
        ) { content(focused) }
        Spacer(Modifier.height(12.dp))
        Text(
            label,
            color = color(if (focused) theme.text else theme.secondaryText),
            fontSize = Tokens.SECONDARY_SP.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().clearAndSetSemantics { }, // the tile's description already names it
        )
    }
}

/**
 * Today's date large, then the next event (time bold, title, at most two lines) or a short status. Text sizes follow
 * the theme's tile height (designed at 135 dp). Calendar titles are plain text from outside: ellipsis, no markup.
 */
@Composable
private fun CalendarTileView(tile: CalendarTile, theme: HomeTheme, requester: FocusRequester, onClick: () -> Unit) {
    TileFrame(theme, CALENDAR_TILE_LABEL, tile.description, requester, onClick) {
        val k = theme.tileHeightDp / 135f
        val amber = color(Tokens.AMBER)
        // Read once, as the tile description; the texts are not read separately.
        Column(Modifier.fillMaxSize().clearAndSetSemantics { }.padding(horizontal = (14 * k).dp, vertical = (10 * k).dp)) {
            Text(tile.date, color = color(theme.text), fontSize = (28 * k).sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height((6 * k).dp))
            val body = buildAnnotatedString {
                tile.time?.let { withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(it) }; append("  ") }
                append(tile.text)
            }
            Text(
                body,
                color = if (tile.warn) amber else color(theme.text),
                fontSize = (18 * k).sp,
                maxLines = if (tile.note != null) 1 else 2,
                overflow = TextOverflow.Ellipsis,
            )
            tile.note?.let {
                Spacer(Modifier.height((4 * k).dp))
                Text("⚠ $it", color = amber, fontSize = (15 * k).sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun AppTile(t: TileApp, theme: HomeTheme, onClick: () -> Unit) {
    TileFrame(theme, t.app.label, t.app.label, null, onClick) {
        val img = t.image
        if (img != null) {
            Image(
                bitmap = img,
                contentDescription = null, // the tile's description names it
                contentScale = if (t.isBanner) ContentScale.Crop else ContentScale.Fit,
                modifier = if (t.isBanner) Modifier.fillMaxSize() else Modifier.size((minOf(theme.tileWidthDp, theme.tileHeightDp) * 3 / 5).dp),
            )
        } else {
            Text(t.app.label.take(1).uppercase(), color = color(theme.text), fontSize = 48.sp)
        }
    }
}
