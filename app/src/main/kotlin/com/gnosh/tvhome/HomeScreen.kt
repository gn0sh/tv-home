package com.gnosh.tvhome

import androidx.activity.compose.BackHandler
import android.os.SystemClock
import android.view.ViewConfiguration
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
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
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.Text
import tvhome.core.apps.AppLayout
import tvhome.core.apps.LauncherApp
import tvhome.core.assistant.AskCopy
import tvhome.core.ui.CALENDAR_TILE_LABEL
import tvhome.core.ui.CalendarTile
import tvhome.core.feeds.WatchTarget
import tvhome.core.ui.HomeTheme
import tvhome.core.ui.IdleState
import tvhome.core.ui.OledTokens
import tvhome.core.ui.dimmed
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
 * M2d home screen: two rows and a button. On top the app carousel ([apps]: tiles, or a message while loading / failed /
 * empty); below it the dock, a left-aligned row of dashboard tiles holding the Calendar tile ([tile]); at the bottom
 * centre, in the same band as the dock, a small "Ask Claude" pill button (M2d, replacing the M1b Ask tile). Behind it all
 * the M7 hero ([HeroLayer]) shows the clock, weather, Now/Next and the context card (each tile names itself in its own
 * label), and the idle layer ([IdleController]) dims it when left alone. Neither waits on the package manager or on
 * Claude. OK on the Calendar tile or the button switches this screen to its full screen (the agenda, or the Ask view
 * through [ask]); Back there returns here with focus on the same tile or button. Back here is not handled, so it exits
 * the activity with one press. Colours and tile size come from [theme].
 *
 * Focus: on launch the first app tile, or the Calendar tile while there are no apps; if apps arrive before the user
 * has pressed a key, focus moves to the first app tile. Down from an app tile goes to the dock (2D search: the Calendar
 * tile, or the Ask button from a tile far right; an explicit down from a scrolled lazy item lost focus on Android, see
 * fix-app-row-scroll); Up from the button goes to the Calendar tile (set explicitly). Up from
 * the dock returns to the app tile that last had focus if it is on screen, else to the tile above (a custom enter on
 * the row, not focusRestorer: see [AppFocus.enterTarget]). The row's scroll position and last-focused tile live above
 * the full-screen switch, so they survive it.
 */
@Composable
@OptIn(ExperimentalComposeUiApi::class) // testTagsAsResourceId
fun HomeScreen(
    apps: AppsState,
    theme: HomeTheme,
    onLaunch: (LauncherApp) -> Unit,
    tile: CalendarTile,
    agenda: ScreenModel,
    ask: AskHost,
    layout: SavedLayout,
    onLayoutChange: (SavedLayout) -> Unit,
    entry: HomeEntry = HomeEntry(0, null),
    /** M7-F6 Quiet mode; null until the stored value has loaded (titles stay hidden until then). */
    quiet: Boolean? = false,
    onQuietToggle: () -> Unit = {},
) {
    // Which dock tile's full screen is showing (null: home), and which dock tile gets focus back after it closes.
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    var returningTo by rememberSaveable { mutableStateOf<String?>(null) }
    // M3: the remote's Home button (HomeWatchService, ACTION_HOME) shows the home row from anywhere, with focus on the
    // first tile; ACTION_ASK opens Ask Claude, listening, as a fresh session even if Ask was open.
    var pendingHome by remember { mutableStateOf(false) }
    var askSession by remember { mutableIntStateOf(0) }
    LaunchedEffect(entry.seq) {
        if (entry.seq == 0) return@LaunchedEffect
        returningTo = null
        if (entry.target == DOCK_ASK) askSession++
        open = entry.target
        if (entry.target == null) pendingHome = true
    }
    // Kept while a full screen is showing (declared before the switch), so Up after Back still restores.
    val rowState = rememberLazyListState()
    val appFocus = remember { AppFocus() }
    fun close() { returningTo = open; open = null }
    when (open) {
        DOCK_CALENDAR -> { CalendarFullScreen(agenda, theme, onClose = ::close); return }
        DOCK_ASK -> { key(askSession) { AskFullScreen(ask, theme, onClose = ::close) }; return }
    }
    // Everything installed (default order), then the user's order and hidden set applied (M2g, AppLayout in :core).
    val allApps = (apps as? AppsState.Loaded)?.apps.orEmpty()
    val installedPkgs = remember(allApps) { allApps.map { it.app.packageName } }
    val byPkg = remember(allApps) { allApps.associateBy { it.app.packageName } }
    val appList = remember(allApps, layout) {
        AppLayout.apply(layout.order, layout.hidden, installedPkgs).mapNotNull { byPkg[it] }
    }
    val hiddenList = remember(allApps, layout) {
        AppLayout.hiddenApps(layout.order, layout.hidden, installedPkgs).mapNotNull { byPkg[it] }
    }
    // The row's focus targets in order: the visible apps, then the Hidden tile when there are hidden apps.
    val rowKeys = remember(appList, hiddenList) {
        appList.map { it.app.packageName } + if (hiddenList.isNotEmpty()) listOf(HIDDEN_KEY) else emptyList()
    }
    val hasApps = rowKeys.isNotEmpty()
    val firstApp = rowKeys.firstOrNull()?.let { appFocus.requester(it) }
    // The tile whose menu is open, whether the hidden list is open, and which row tile should get focus (and scroll
    // into view) once the next composition has the new order.
    var menuFor by remember { mutableStateOf<String?>(null) }
    var hiddenOpen by remember { mutableStateOf(false) }
    var pendingFocus by remember { mutableStateOf<String?>(null) }
    fun visiblePkgs() = appList.map { it.app.packageName }
    fun commit(next: SavedLayout, focusKey: String?, keepScroll: Boolean = false) {
        // A move keeps the row where it is (move-scroll-anchor): a LazyRow keeps its first visible item's key in place
        // when the data changes, so moving the first tile right would scroll the row along with it and look like
        // nothing moved. This position is used instead on the next measure; pendingFocus then brings the tile into view.
        if (keepScroll) rowState.requestScrollToItem(rowState.firstVisibleItemIndex, rowState.firstVisibleItemScrollOffset)
        onLayoutChange(next)
        pendingFocus = focusKey
    }
    fun onTileAction(pkg: String, action: TileAction) {
        menuFor = null
        when (action) {
            TileAction.Left -> commit(layout.copy(order = AppLayout.moveLeft(layout.order, layout.hidden, installedPkgs, pkg)), pkg, keepScroll = true)
            TileAction.Right -> commit(layout.copy(order = AppLayout.moveRight(layout.order, layout.hidden, installedPkgs, pkg)), pkg, keepScroll = true)
            TileAction.Front -> commit(layout.copy(order = AppLayout.moveToFront(layout.order, installedPkgs, pkg)), pkg, keepScroll = true)
            TileAction.Hide -> {
                val before = visiblePkgs()
                val rest = before - pkg
                // Focus goes to the tile that slides into the hidden one's place, else the one before it, else the Hidden tile.
                val next = rest.getOrNull(before.indexOf(pkg)) ?: rest.lastOrNull() ?: HIDDEN_KEY
                commit(layout.copy(order = AppLayout.stored(layout.order, installedPkgs), hidden = AppLayout.hide(layout.hidden, pkg)), next)
            }
            TileAction.Cancel -> pendingFocus = pkg
        }
    }
    fun onUnhide(pkg: String) {
        val last = hiddenList.size <= 1
        if (last) hiddenOpen = false
        commit(layout.copy(hidden = AppLayout.unhide(layout.hidden, pkg)), if (last) pkg else null)
    }
    LaunchedEffect(pendingFocus) {
        val key = pendingFocus ?: return@LaunchedEffect
        // Two frames: frame callbacks run before that frame's layout, so after the second one the row has been measured
        // with the new layout (and any move-scroll-anchor request applied) and layoutInfo below is not the old one.
        withFrameNanos { }; withFrameNanos { }
        val index = rowKeys.indexOf(key)
        if (index >= 0) {
            val info = rowState.layoutInfo
            val item = info.visibleItemsInfo.firstOrNull { it.index == index }
            val whole = item != null && item.offset >= info.viewportStartOffset && item.offset + item.size <= info.viewportEndOffset
            if (!whole) runCatching { rowState.animateScrollToItem(index) }
            focusSoon(appFocus.requester(key))
        }
        pendingFocus = null
    }
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
    LaunchedEffect(pendingHome, firstApp, apps is AppsState.Loading) {
        if (!pendingHome) return@LaunchedEffect
        menuFor = null; hiddenOpen = false
        val first = firstApp
        if (first == null) {
            if (apps is AppsState.Loading) return@LaunchedEffect // the initial-focus effect places focus once they load
            focusSoon(calendar) // no app tiles (pending-home-stuck): the dock is the home focus
        } else {
            runCatching { rowState.scrollToItem(0) }
            focusSoon(first)
        }
        settled = true
        pendingHome = false
    }
    var heroReady by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { withFrameNanos { }; heroReady = true }
    // M7-F4 burn-in protection: DIM swaps to the dimmed theme and image alpha; NEAR_BLACK covers the screen. The M3 Home
    // redirect wakes it too.
    val idle = rememberIdleController()
    LaunchedEffect(entry.seq) { if (entry.seq != 0) idle.wake() }
    val idleState = idle.state.value
    // M7-F5: the Watch pill's target, reported by the hero (homeGlance owns the rule); null hides the pill.
    var watch by remember { mutableStateOf<WatchTarget?>(null) }
    val watchPill = remember { FocusRequester() }
    val quietPill = remember { FocusRequester() }
    // watch-pill-focus-loss: the pill can vanish while focused (the game ended); focus moves on to Quiet, else Ask.
    var watchFocused by remember { mutableStateOf(false) }
    LaunchedEffect(watch == null) {
        if (watch == null && watchFocused) {
            watchFocused = false
            if (!focusSoon(quietPill)) focusSoon(askTile)
        }
    }
    val context = LocalContext.current
    val shown = if (idleState == IdleState.DIM) theme.dimmed() else theme
    Box(
        Modifier
            .fillMaxSize()
            .background(color(theme.background))
            // Test tags (the hero's idle state) show as uiautomator resource-ids for the emulator QA; not read aloud.
            .semantics { testTagsAsResourceId = true }
            // Key handlers are not focus targets, so this does not reintroduce home-back-twice. In NEAR_BLACK the
            // first key only wakes the screen (consumed); otherwise every key passes through.
            .onPreviewKeyEvent { e -> settled = true; idle.onPreviewKey(e) },
    ) {
        // The hero is drawn behind the tiles and has no focus target (home-back-twice): nothing here is focusable, on
        // purpose. Compose maps Back to FocusDirection.Exit, which moves focus to the nearest focusable ancestor of the
        // focused tile and consumes the key. A focusable container above the tiles would take the first Back and only
        // the second would reach the activity.
        // Composed only after the first frame (cold-start budget, AC8): the hero's theme, clock and receivers cost about
        // 0.8 s on the emulator's first frame; the tiles come first and the hero appears one frame later.
        if (heroReady) HeroLayer(idle.state, idle.drift, quiet = quiet != false, onWatch = { watch = it }, briefColor = shown.secondaryText)
        CompositionLocalProvider(LocalImageAlpha provides if (idleState == IdleState.DIM) OledTokens.DIM_IMAGE_ALPHA else 1f) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = Tokens.MARGIN_H_DP.dp, vertical = Tokens.MARGIN_V_DP.dp),
        ) {
            Spacer(Modifier.weight(1f))
            AppRow(
                apps, appList, hiddenList.size, rowKeys, shown, rowState, appFocus, onLaunch,
                onMenu = { menuFor = it }, onOpenHidden = { hiddenOpen = true },
            )
            Dock(shown) {
                CalendarTileView(tile, shown, calendar, down = askTile, onClick = { open = DOCK_CALENDAR })
            }
            // M7-F6: the dock band's own full-width row for the brief line, always reserved (so nothing moves when the
            // line comes and goes). The hero draws the line into it (filled with Quiet in the same frame, drifting).
            Spacer(Modifier.height(BRIEF_ROW_DP.dp))
        }
        // Bottom centre, in the dock's band (beside the Calendar tile) so the hero gets the room: the pill is centred on
        // the tile's box, inside the safe-area margins.
        Box(
            Modifier.fillMaxSize().padding(bottom = (Tokens.MARGIN_V_DP + BRIEF_ROW_DP + dockPillInset(theme)).dp),
            contentAlignment = Alignment.BottomCenter,
        ) {
            AskButton(shown, askTile, up = calendar, onFocused = {}, onClick = { open = DOCK_ASK })
        }
        // R1 focus order in the dock band: Calendar > Ask > Watch (game day only) > Quiet (last). Right of the Ask pill,
        // right-aligned in the same band; Up from either returns to the Calendar tile, like Ask.
        Box(
            Modifier.fillMaxSize().padding(end = Tokens.MARGIN_H_DP.dp, bottom = (Tokens.MARGIN_V_DP + BRIEF_ROW_DP + dockPillInset(theme)).dp),
            contentAlignment = Alignment.BottomEnd,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(PILL_GAP_DP.dp), verticalAlignment = Alignment.CenterVertically) {
                watch?.let { w ->
                    DockPill(
                        shown, label = "Watch ${w.network}", description = WATCH_DESC_PREFIX + w.network,
                        requester = watchPill, up = calendar, maxWidthDp = WATCH_PILL_MAX_DP, active = false,
                        // The focus loss that comes with the pill's removal (watch already null) must not clear the flag.
                        onFocus = { f -> if (f) watchFocused = true else if (watch != null) watchFocused = false },
                    ) { InstalledApps.launchPackage(context, w.packageName) }
                }
                val on = quiet == true
                DockPill(
                    shown, label = if (on) "Quiet on" else "Quiet", description = if (on) QUIET_DESC_ON else QUIET_DESC_OFF,
                    requester = quietPill, up = calendar, maxWidthDp = QUIET_PILL_MAX_DP, active = on,
                    onClick = onQuietToggle,
                )
            }
        }
        }
        if (idleState == IdleState.NEAR_BLACK) NearBlackLayer(idle.drift)
    }
    // Dialogs are separate windows, so they add no focus target to the home window (home-back-twice). Back closes
    // them (onDismissRequest) and focus is put back on the tile that opened them.
    val menuTile = menuFor?.let { byPkg[it] }
    if (menuTile != null) {
        val pkg = menuTile.app.packageName
        TileMenu(
            theme, menuTile.app.label,
            onAction = { onTileAction(pkg, it) },
            onDismiss = { menuFor = null; pendingFocus = pkg },
        )
    }
    if (hiddenOpen && hiddenList.isNotEmpty()) {
        HiddenAppsDialog(
            theme, hiddenList,
            onUnhide = { onUnhide(it.app.packageName) },
            onDismiss = { hiddenOpen = false; pendingFocus = HIDDEN_KEY },
        )
    }
    // An app that vanished (uninstalled) while its menu or the hidden list was open closes them.
    LaunchedEffect(menuTile == null, hiddenList.isEmpty()) {
        if (menuTile == null) menuFor = null
        if (hiddenList.isEmpty()) hiddenOpen = false
    }
}

/** Tile images' alpha: 1, or [OledTokens.DIM_IMAGE_ALPHA] while the screen is dimmed. */
private val LocalImageAlpha = compositionLocalOf { 1f }

/** Distance from the dock band's bottom to the bottom of a pill that is centred on the Calendar tile's box. */
private fun dockPillInset(theme: HomeTheme): Int =
    rowPadding(theme).calculateBottomPadding().value.toInt() + TILE_LABEL_DP + (theme.tileHeightDp - ASK_BUTTON_H_DP) / 2

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

    /**
     * Where focus goes when it enters the row from outside (Up from the dock): the tile that last had focus, if it is
     * still listed and on screen; otherwise null, and the 2D search picks the tile above the dock.
     *
     * Not focusRestorer (fix-app-row-scroll): it saves the focus group's direct child focus target, which for a LazyRow
     * is the row's own scrollable container, not the tile. Entering the row then gave focus to that invisible container,
     * and Right/Left had nothing to move to, so the row could not be scrolled until focus left it.
     */
    fun enterTarget(keys: List<String>, row: LazyListState): FocusRequester? {
        val pkg = lastFocused ?: return null
        val index = keys.indexOf(pkg)
        if (index < 0 || row.layoutInfo.visibleItemsInfo.none { it.index == index }) return null
        return requester(pkg)
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
    hiddenCount: Int,
    rowKeys: List<String>,
    theme: HomeTheme,
    rowState: LazyListState,
    appFocus: AppFocus,
    onLaunch: (LauncherApp) -> Unit,
    onMenu: (String) -> Unit,
    onOpenHidden: () -> Unit,
) {
    if (apps.isEmpty() && hiddenCount == 0) {
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
    LaunchedEffect(rowKeys) { appFocus.forget(rowKeys.toHashSet()) }
    // A lazy row clips to its bounds; the content padding leaves room for the focus scale. The enter property and
    // focusGroup cannot take focus themselves, so Back still finds no focusable ancestor (home-back-twice).
    LazyRow(
        state = rowState,
        modifier = Modifier
            .fillMaxWidth()
            .focusProperties { enter = { appFocus.enterTarget(rowKeys, rowState) ?: FocusRequester.Default } }
            .focusGroup(),
        contentPadding = rowPadding(theme),
        horizontalArrangement = Arrangement.spacedBy(TILE_GAP_DP.dp),
    ) {
        items(apps, key = { it.app.packageName }) { t ->
            val pkg = t.app.packageName
            AppTile(
                t, theme, appFocus.requester(pkg),
                onFocused = { appFocus.lastFocused = pkg },
                onLongClick = { onMenu(pkg) },
            ) { onLaunch(t.app) }
        }
        if (hiddenCount > 0) {
            item(key = HIDDEN_KEY) {
                val label = "Hidden apps ($hiddenCount)"
                TileFrame(
                    theme, label, label, appFocus.requester(HIDDEN_KEY), onOpenHidden,
                    onFocused = { appFocus.lastFocused = HIDDEN_KEY },
                ) {
                    Text("$hiddenCount", color = color(theme.text), fontSize = 48.sp)
                }
            }
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
    down: FocusRequester? = null,
    onLongClick: (() -> Unit)? = null,
    content: @Composable BoxScope.(focused: Boolean) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    // Holding OK (M2g tile menu). combinedClickable's long click did not fire for a held DPAD_CENTER on the emulator (the
    // tile launched instead), so OK is handled here: a press shorter than the long-press timeout clicks, a longer one
    // (a repeat flagged long-press, or held past the timeout) opens the menu once; the release after it does nothing.
    // Held time is measured on arrival, not from event times: `input keyevent --duration` sends the release with the
    // press's own event time, so event times read as a short press there.
    val longTimeout = remember { ViewConfiguration.getLongPressTimeout().toLong() }
    val downAt = remember { mutableLongStateOf(-1L) }
    val longFired = remember { mutableStateOf(false) }
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
                .then(if (down != null) Modifier.focusProperties { this.down = down } else Modifier)
                .onFocusChanged {
                    focused = it.isFocused
                    if (it.isFocused) onFocused()
                    // A press whose release went elsewhere (the menu opened) does not carry over (tile-down-reset).
                    else { downAt.longValue = -1L; longFired.value = false }
                }
                .semantics {
                    contentDescription = description
                    // The menu is reachable without the held key too (tile-a11y-longclick).
                    val menu = onLongClick
                    if (menu != null) this.onLongClick(label = null) { menu(); true }
                }
                .then(
                    if (onLongClick == null) Modifier else Modifier.onPreviewKeyEvent { e ->
                        if (e.key != Key.DirectionCenter && e.key != Key.Enter && e.key != Key.NumPadEnter) {
                            return@onPreviewKeyEvent false
                        }
                        val n = e.nativeKeyEvent
                        when (e.type) {
                            KeyEventType.KeyDown -> {
                                if (n.repeatCount == 0) {
                                    downAt.longValue = SystemClock.uptimeMillis(); longFired.value = false
                                } else if (!longFired.value && downAt.longValue >= 0 &&
                                    (n.isLongPress || SystemClock.uptimeMillis() - downAt.longValue >= longTimeout)
                                ) {
                                    longFired.value = true; onLongClick()
                                }
                                true
                            }
                            KeyEventType.KeyUp -> {
                                if (downAt.longValue >= 0 && !longFired.value && !n.isCanceled) {
                                    if (SystemClock.uptimeMillis() - downAt.longValue >= longTimeout) onLongClick() else onClick()
                                }
                                downAt.longValue = -1L; longFired.value = false
                                true
                            }
                            else -> false
                        }
                    },
                )
                // Focusable on TV; OK/Enter/D-pad centre clicks (tiles with a menu handle OK above).
                .clickable(onClick = onClick),
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
private fun CalendarTileView(
    tile: CalendarTile,
    theme: HomeTheme,
    requester: FocusRequester,
    down: FocusRequester,
    onFocused: () -> Unit = {},
    onClick: () -> Unit,
) {
    TileFrame(theme, CALENDAR_TILE_LABEL, tile.description, requester, onClick, onFocused, down) {
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

/**
 * The Ask Claude pill: small (about 250 x 56 dp), "✦ Ask Claude", the tiles' focus treatment (scale, fill, ring) in the
 * theme's colours. Up goes to the Calendar tile (explicit, so it never jumps to an app tile). It shows no state, so it
 * never waits on Claude. A single focus target with no focusable parent (home-back-twice).
 */
@Composable
private fun AskButton(theme: HomeTheme, requester: FocusRequester, up: FocusRequester, onFocused: () -> Unit, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(50)
    Row(
        Modifier
            .size(ASK_BUTTON_W_DP.dp, ASK_BUTTON_H_DP.dp)
            .graphicsLayer {
                val s = if (focused) FOCUS_SCALE else 1f
                scaleX = s; scaleY = s
            }
            .clip(shape)
            .background(color(if (focused) theme.focusFill else theme.tile), shape)
            .then(if (focused) Modifier.border(Tokens.RING_DP.dp, color(theme.focusRing), shape) else Modifier)
            .focusRequester(requester)
            .focusProperties { this.up = up }
            .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocused() }
            .clearAndSetSemantics { contentDescription = "${AskCopy.TILE_LABEL}. ${AskCopy.TILE_HINT}." }
            .clickable(onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("\u2726", color = color(theme.focusRing), fontSize = 22.sp)
        Spacer(Modifier.width(10.dp))
        Text(AskCopy.TILE_LABEL, color = color(theme.text), fontSize = Tokens.SECONDARY_SP.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

/**
 * A dock-band pill (M7 Watch and Quiet): focusable, OK clicks, same fill, ring and scale as the Ask pill. [active] marks
 * a pill that is switched on (Quiet) with the focus-ring colour as its text. The description names it for TalkBack and
 * the emulator QA.
 */
@Composable
private fun DockPill(
    theme: HomeTheme,
    label: String,
    description: String,
    requester: FocusRequester,
    up: FocusRequester,
    maxWidthDp: Int,
    active: Boolean,
    onFocus: (Boolean) -> Unit = {},
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(50)
    Box(
        Modifier
            .height(ASK_BUTTON_H_DP.dp)
            .widthIn(min = PILL_MIN_DP.dp, max = maxWidthDp.dp)
            .graphicsLayer {
                val s = if (focused) FOCUS_SCALE else 1f
                scaleX = s; scaleY = s
            }
            .clip(shape)
            .background(color(if (focused) theme.focusFill else theme.tile), shape)
            .then(if (focused) Modifier.border(Tokens.RING_DP.dp, color(theme.focusRing), shape) else Modifier)
            .focusRequester(requester)
            .focusProperties { this.up = up }
            .onFocusChanged { focused = it.isFocused; onFocus(it.isFocused) }
            .semantics(mergeDescendants = true) { contentDescription = description }
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label, color = color(if (active) theme.focusRing else theme.text), fontSize = Tokens.SECONDARY_SP.sp,
            fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.clearAndSetSemantics { },
        )
    }
}

/** Descriptions of the Watch and Quiet pills, read by the emulator QA check (scripts/ci/emulator-qa.sh). */
const val WATCH_DESC_PREFIX = "Watch the game on "
const val QUIET_DESC_ON = "Quiet mode, on. Event titles are hidden."
const val QUIET_DESC_OFF = "Quiet mode, off. Turn on to hide event titles."

private const val PILL_GAP_DP = 16
private const val PILL_MIN_DP = 140
private const val WATCH_PILL_MAX_DP = 240
private const val QUIET_PILL_MAX_DP = 170

private const val ASK_BUTTON_W_DP = 250
private const val ASK_BUTTON_H_DP = 56

private const val DOCK_CALENDAR = "calendar"

/** A request from outside the screen (M3): [seq] counts them; [target] null shows the home row, [ASK] opens Ask Claude. */
data class HomeEntry(val seq: Int, val target: String?) {
    companion object { const val ASK = DOCK_ASK }
}
private const val DOCK_ASK = "ask"

/** Focus-requester key of the "Hidden apps (N)" tile; cannot clash with a package name. */
private const val HIDDEN_KEY = "::hidden"

private val OK_KEYS = setOf(Key.Enter, Key.DirectionCenter, Key.NumPadEnter)

/** The tile menu's entries, in the order shown (M2g). */
private enum class TileAction(val label: String) {
    Cancel("Cancel"), Left("Move left"), Right("Move right"), Front("Move to front"), Hide("Hide"),
}

/** The small menu a long press on an app tile opens. The first entry (Cancel, the safe one) has focus; Back closes it. */
@Composable
private fun TileMenu(theme: HomeTheme, title: String, onAction: (TileAction) -> Unit, onDismiss: () -> Unit) {
    val first = remember { FocusRequester() }
    Dialog(onDismissRequest = onDismiss) {
        LaunchedEffect(Unit) { focusSoon(first) }
        DialogCard(theme, title) {
            TileAction.entries.forEachIndexed { i, action ->
                MenuRow(theme, action.label, if (i == 0) first else null) { onAction(action) }
            }
        }
    }
}

/** The list the Hidden tile opens: OK on an app shows it on the row again. Back closes it. */
@Composable
private fun HiddenAppsDialog(theme: HomeTheme, hidden: List<TileApp>, onUnhide: (TileApp) -> Unit, onDismiss: () -> Unit) {
    val first = remember { FocusRequester() }
    Dialog(onDismissRequest = onDismiss) {
        // On open, and after an app leaves the list (the focused row is gone), focus goes to the first row.
        LaunchedEffect(hidden.size) { focusSoon(first) }
        DialogCard(theme, "Hidden apps") {
            Text("OK on an app puts it back on the row.", color = color(theme.secondaryText), fontSize = Tokens.SECONDARY_SP.sp)
            Spacer(Modifier.height(8.dp))
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                itemsIndexed(hidden, key = { _, t -> t.app.packageName }) { i, t ->
                    MenuRow(theme, t.app.label, if (i == 0) first else null) { onUnhide(t) }
                }
            }
        }
    }
}

@Composable
private fun DialogCard(theme: HomeTheme, title: String, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    val armed = remember { booleanArrayOf(false) }
    Column(
        Modifier
            .width(420.dp)
            .clip(shape)
            .background(color(theme.background), shape)
            .border(Tokens.RING_DP.dp, color(theme.secondaryText), shape)
            .padding(24.dp)
            // menu-held-ok-repeat: OK is still held down when the dialog opens, and a real remote then sends repeated
            // KeyDowns and finally a KeyUp. Swallow OK/Enter events until a fresh press (KeyDown, repeatCount 0)
            // arrives, so the held press cannot choose an entry.
            .onPreviewKeyEvent { e ->
                if (armed[0] || e.key !in OK_KEYS) {
                    false
                } else if (e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount == 0) {
                    armed[0] = true
                    false
                } else {
                    true
                }
            },
    ) {
        Text(title, color = color(theme.text), fontSize = 22.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(12.dp))
        content()
    }
}

/** One focusable row in a dialog, in the tiles' focus colours. */
@Composable
private fun MenuRow(theme: HomeTheme, label: String, requester: FocusRequester?, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clip(shape)
            .background(color(if (focused) theme.focusFill else theme.tile), shape)
            .then(if (focused) Modifier.border(Tokens.RING_DP.dp, color(theme.focusRing), shape) else Modifier)
            .then(if (requester != null) Modifier.focusRequester(requester) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(label, color = color(if (focused) theme.text else theme.secondaryText), fontSize = Tokens.SECONDARY_SP.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun AppTile(t: TileApp, theme: HomeTheme, requester: FocusRequester?, onFocused: () -> Unit, onLongClick: () -> Unit, onClick: () -> Unit) {
    TileFrame(theme, t.app.label, t.app.label, requester, onClick, onFocused, onLongClick = onLongClick) {
        val img = t.image
        if (img != null) {
            Image(
                bitmap = img,
                contentDescription = null, // the tile's description names it
                contentScale = if (t.isBanner) ContentScale.Crop else ContentScale.Fit,
                alpha = LocalImageAlpha.current,
                modifier = if (t.isBanner) Modifier.fillMaxSize() else Modifier.size((minOf(theme.tileWidthDp, theme.tileHeightDp) * 3 / 5).dp),
            )
        } else {
            Text(t.app.label.take(1).uppercase(), color = color(theme.text), fontSize = 48.sp)
        }
    }
}
