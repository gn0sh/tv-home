package com.gnosh.tvhome

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onPreviewKeyEvent
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
import tvhome.core.assistant.AskCopy
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
 * M2c home screen: two rows. On top the app carousel ([apps]: tiles, or a message while loading / failed / empty);
 * below it the dock, a left-aligned row of dashboard tiles: the Calendar tile ([tile]), then Ask Claude (M1b). The dock
 * never waits on the package manager or on Claude. OK on a dock tile switches this screen to that tile's full screen
 * (the agenda, or the Ask view through [ask]); Back there returns here with focus on the same tile. Back here is not
 * handled, so it exits the activity with one press. Colours and tile size come from [theme].
 *
 * Focus: on launch the first app tile, or the Calendar tile while there are no apps; if apps arrive before the user
 * has pressed a key, focus moves to the first app tile. Down from the app row reaches the dock (2D focus search); Up
 * returns to the app tile that last had focus (focusRestorer on the row, falling back to the remembered tile, then the
 * first). The row's scroll position and last-focused tile live above the full-screen switch, so they survive it.
 */
@Composable
fun HomeScreen(
    apps: AppsState,
    theme: HomeTheme,
    onLaunch: (LauncherApp) -> Unit,
    tile: CalendarTile,
    agenda: ScreenModel,
    ask: AskHost,
) {
    // Which dock tile's full screen is showing (null: home), and which dock tile gets focus back after it closes.
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    var returningTo by rememberSaveable { mutableStateOf<String?>(null) }
    // Kept while a full screen is showing (declared before the switch), so Up after Back still restores.
    val rowState = rememberLazyListState()
    val appFocus = remember { AppFocus() }
    fun close() { returningTo = open; open = null }
    when (open) {
        DOCK_CALENDAR -> { CalendarFullScreen(agenda, theme, onClose = ::close); return }
        DOCK_ASK -> { AskFullScreen(ask, theme, onClose = ::close); return }
    }
    val appList = (apps as? AppsState.Loaded)?.apps.orEmpty()
    val hasApps = appList.isNotEmpty()
    val firstApp = appList.firstOrNull()?.let { appFocus.requester(it.app.packageName) }
    val calendar = remember { FocusRequester() }
    val askTile = remember { FocusRequester() }
    // Initial focus is settled once it reached the first app, the user pressed a key, or we came back from a full
    // screen (focus belongs on that dock tile then). Until then it may move from the Calendar tile (placed there while
    // apps load) to the first app tile.
    var settled by remember { mutableStateOf(returningTo != null) }
    LaunchedEffect(Unit) {
        val back = returningTo ?: return@LaunchedEffect
        returningTo = null
        focusSoon(if (back == DOCK_ASK) askTile else calendar)
    }
    LaunchedEffect(hasApps) {
        if (settled) return@LaunchedEffect
        // Re-check after the frame: a key press in that frame settles focus, and must not be overridden (dock-preview-steal).
        if (firstApp != null) { if (focusSoon(firstApp) { !settled }) settled = true } else focusSoon(calendar) { !settled }
    }
    Column(
        Modifier
            .fillMaxSize()
            .background(color(theme.background))
            // Key handlers are not focus targets, so this does not reintroduce home-back-twice.
            .onPreviewKeyEvent { settled = true; false }
            // Nothing here is focusable, on purpose (home-back-twice): Compose maps Back to FocusDirection.Exit, which
            // moves focus to the nearest focusable ancestor of the focused tile and consumes the key. A focusable
            // container above the tiles would take the first Back and only the second would reach the activity.
            .padding(horizontal = Tokens.MARGIN_H_DP.dp, vertical = Tokens.MARGIN_V_DP.dp),
    ) {
        AppRow(apps, appList, theme, rowState, appFocus, onLaunch)
        Dock(theme) {
            CalendarTileView(tile, theme, calendar, onClick = { open = DOCK_CALENDAR })
            AskTileView(theme, askTile, onClick = { open = DOCK_ASK })
        }
    }
}

/**
 * Requests focus after the next frame (the target may not be composed yet), retrying once. True when the request was
 * made (Compose 1.7 throws for a requester that is not attached yet).
 */
private suspend fun focusSoon(target: FocusRequester, stillWanted: () -> Boolean = { true }): Boolean {
    withFrameNanos { }
    if (!stillWanted()) return false
    if (runCatching { target.requestFocus() }.isSuccess) return true
    withFrameNanos { }
    if (!stillWanted()) return false
    return runCatching { target.requestFocus() }.isSuccess
}

/** One FocusRequester per app tile (by package), and the package of the tile that last had focus. */
private class AppFocus {
    private val requesters = HashMap<String, FocusRequester>()
    var lastFocused: String? = null

    fun requester(pkg: String): FocusRequester = requesters.getOrPut(pkg) { FocusRequester() }

    /** Fallback when focusRestorer has nothing saved (e.g. the row was recomposed): the last app if listed, else the first. */
    fun fallback(apps: List<TileApp>): FocusRequester? {
        val pkg = lastFocused?.takeIf { p -> apps.any { it.app.packageName == p } } ?: apps.firstOrNull()?.app?.packageName
        return pkg?.let(::requester)
    }

    fun forget(keep: Set<String>) { requesters.keys.retainAll(keep) }
}

/** Vertical room for the 1.1x focus scale above and below a tile, so neither row clips its neighbour's ring. */
private fun rowPadding(theme: HomeTheme) =
    PaddingValues(horizontal = (theme.tileWidthDp / 16).dp, vertical = (theme.tileHeightDp / 13).dp)

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
@OptIn(ExperimentalComposeUiApi::class)
private fun AppRow(
    state: AppsState,
    apps: List<TileApp>,
    theme: HomeTheme,
    rowState: LazyListState,
    appFocus: AppFocus,
    onLaunch: (LauncherApp) -> Unit,
) {
    if (apps.isEmpty()) {
        // The message takes the row's place, at the row's height, so the dock does not jump when apps arrive.
        val msg = when (state) {
            AppsState.Loading -> "Loading apps…"
            AppsState.Failed -> "Couldn't list apps"
            is AppsState.Loaded -> "No TV apps found"
        }
        Box(
            Modifier.fillMaxWidth().padding(rowPadding(theme)).height((theme.tileHeightDp + TILE_LABEL_DP).dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(msg, color = color(theme.secondaryText), fontSize = Tokens.SECONDARY_SP.sp)
        }
        return
    }
    LaunchedEffect(apps) { appFocus.forget(apps.mapTo(HashSet()) { it.app.packageName }) }
    // A lazy row clips to its bounds; the content padding leaves room for the focus scale. focusRestorer only sets
    // enter/exit focus properties and focusGroup cannot take focus itself, so Back still finds no focusable ancestor
    // (home-back-twice).
    LazyRow(
        state = rowState,
        modifier = Modifier
            .fillMaxWidth()
            .focusRestorer { appFocus.fallback(apps) ?: FocusRequester.Default }
            .focusGroup(),
        contentPadding = rowPadding(theme),
        horizontalArrangement = Arrangement.spacedBy(TILE_GAP_DP.dp),
    ) {
        items(apps, key = { it.app.packageName }) { t ->
            val pkg = t.app.packageName
            AppTile(t, theme, appFocus.requester(pkg), onFocused = { appFocus.lastFocused = pkg }) { onLaunch(t.app) }
        }
    }
}

/**
 * The dock: dashboard tiles under the app row, left-aligned with the first app tile. A plain (non-lazy, non-clipping,
 * non-focusable) Row, so more tiles can be added as further children.
 */
@Composable
private fun Dock(theme: HomeTheme, tiles: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(rowPadding(theme)),
        horizontalArrangement = Arrangement.spacedBy(TILE_GAP_DP.dp),
    ) { tiles() }
}

private const val TILE_GAP_DP = 28

/** Label under a tile: 12 dp gap plus one line of secondary text. */
private const val TILE_LABEL_DP = 44

/** The tile box both kinds of tile share: size, focus scale, fill and ring, and OK/Enter to click. */
@Composable
private fun TileFrame(
    theme: HomeTheme,
    label: String,
    description: String,
    requester: FocusRequester?,
    onClick: () -> Unit,
    onFocused: () -> Unit = {},
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
                .onFocusChanged {
                    focused = it.isFocused
                    if (it.isFocused) onFocused()
                }
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

/** The Ask Claude tile: its name large and a one-line hint. It shows no state, so it never waits on Claude. */
@Composable
private fun AskTileView(theme: HomeTheme, requester: FocusRequester, onClick: () -> Unit) {
    TileFrame(theme, AskCopy.TILE_LABEL, "${AskCopy.TILE_LABEL}. ${AskCopy.TILE_HINT}.", requester, onClick) {
        val k = theme.tileHeightDp / 135f
        Column(Modifier.fillMaxSize().clearAndSetSemantics { }.padding(horizontal = (14 * k).dp, vertical = (10 * k).dp)) {
            Text(AskCopy.TILE_LABEL, color = color(theme.text), fontSize = (28 * k).sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height((6 * k).dp))
            Text(AskCopy.TILE_HINT, color = color(theme.secondaryText), fontSize = (18 * k).sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

private const val DOCK_CALENDAR = "calendar"
private const val DOCK_ASK = "ask"

@Composable
private fun AppTile(t: TileApp, theme: HomeTheme, requester: FocusRequester?, onFocused: () -> Unit, onClick: () -> Unit) {
    TileFrame(theme, t.app.label, t.app.label, requester, onClick, onFocused) {
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
