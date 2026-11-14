package com.gnosh.tvhome

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.provider.Settings
import android.text.format.DateFormat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.math.sin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import tvhome.core.feeds.FeedsSnapshot
import tvhome.core.feeds.GameDay
import tvhome.core.feeds.GamePhase
import tvhome.core.feeds.Pick
import tvhome.core.feeds.WatchDefaults
import tvhome.core.feeds.WatchMap
import tvhome.core.feeds.WatchTarget
import tvhome.core.feeds.LiveFeeds
import tvhome.core.feeds.PanelPolicies
import tvhome.core.feeds.PanelView
import tvhome.core.feeds.view
import tvhome.core.feeds.PanelSwitches
import tvhome.core.feeds.Precip
import tvhome.core.feeds.Location
import tvhome.core.ui.BackdropArt
import tvhome.core.ui.BackdropSkyline
import tvhome.core.ui.BoardPalette
import tvhome.core.ui.BoardTokens
import tvhome.core.ui.SkyNow
import tvhome.core.ui.SunArc
import tvhome.core.ui.SunSpot
import tvhome.core.ui.ContextCard
import tvhome.core.ui.DriftOffset
import tvhome.core.ui.Drift
import tvhome.core.assistant.briefFacts
import tvhome.core.assistant.briefLine
import tvhome.core.assistant.BriefSource
import tvhome.core.ui.GlanceInputs
import tvhome.core.ui.GlanceTokens
import tvhome.core.ui.HeroMotif
import tvhome.core.ui.HeroTheme
import tvhome.core.ui.DesignCanvas
import tvhome.core.ui.HeroZones
import tvhome.core.ui.HomeLayout
import tvhome.core.ui.HomeGlance
import tvhome.core.ui.IdleState
import tvhome.core.ui.NowNext
import tvhome.core.ui.OledTokens
import tvhome.core.ui.Tokens
import tvhome.core.ui.TravelRule
import tvhome.core.ui.homeGlance

/** Fraction of the screen height the backdrop covers (under the app row, as in the mockup). */
const val HERO_BACKDROP_FRACTION = 0.55f

/** Content description prefix of the Now/Next line 1, read by the emulator QA check (scripts/ci/emulator-qa.sh). */
const val HERO_NOW_DESC_PREFIX = "Hero now: "

/**
 * Test tag prefix of the hero layer (followed by the IdleState name). Exposed as the uiautomator resource-id
 * (testTagsAsResourceId on the home screen's root) for the emulator QA check; TalkBack does not read it.
 */
const val HERO_IDLE_TAG_PREFIX = "hero_idle_"

/** Test tag of the Quiet marker in the top strip (uiautomator resource-id), read by the emulator QA check. */
const val HERO_QUIET_TAG = "hero_quiet"

/** The visible Quiet marker (spec M7-7.1). */
const val QUIET_MARKER = "Quiet"

private const val GAME_LIVE = "Live"
private const val GAME_TODAY = "Today"
private const val GAME_FINAL = "Final"

/** Content description prefix of the context card, read by the emulator QA check. */
const val HERO_CARD_DESC_PREFIX = "Hero card: "

/**
 * The M7 hero layer (ADR 0008 sections 2-4): backdrop under a 25% scrim, the top strip (clock and date left, weather
 * chip right), Now/Next in the left 60% and the context card (severe alert or today's game) in the right 40%. Every
 * string comes from [homeGlance]; this only draws. Not focusable and no focus targets (home-back-twice): the Calendar
 * tile in the dock is how OK reaches the calendar.
 *
 * It owns the minute clock, so the countdown and the theme re-evaluate at each tick without recomposing the home
 * screen. [idle] dims the backdrop and text (DIM; NEAR_BLACK is drawn over everything by [NearBlackLayer]); [drift]
 * moves the top strip and the hero text, read in the layer phase only. [quiet] (M7-F6) hides event titles in every
 * string; [onWatch] reports the Watch pill's target (M7-F5) to the dock, which is outside the hero. The one-line brief
 * (M7-F6) is drawn here, in the dock band's brief row ([BRIEF_ROW_DP], which the home screen reserves), so it is filled
 * in the same composition as [quiet] (no title for even a frame after Quiet turns on) and drifts with the hero text.
 * [briefColor] is the home theme's secondary text (the row sits on the theme background, below the backdrop).
 */
@Composable
fun HeroLayer(
    idle: State<IdleState>,
    drift: State<DriftOffset>,
    quiet: Boolean,
    onWatch: (WatchTarget?) -> Unit,
    /** The screen and tile colours the hero's theme asks for (team colours on game day), or null for the home theme's. */
    onScreen: (Pair<Int, Int>?) -> Unit = {},
    /** The screen background actually drawn behind the tiles, as it eases (read in the draw phase): the fade meets it. */
    screenColor: () -> Int = { Tokens.BACKGROUND },
    briefColor: Int,
    modifier: Modifier = Modifier,
    /** Top of the apps row in design dp (null until measured): the hero text ends above it ([HeroZones.layout]). */
    appsTop: State<Int?> = remember { mutableStateOf(null) },
) {
    val context = LocalContext.current
    val now by rememberMinuteClock()
    val graph = remember(context) { (context.applicationContext as TvHomeApp).graph }
    // LiveFeeds is built (AppGraph's lazy) on Default after the first frame, never on the main thread (AC8). Feed work
    // then starts after a short settle, only while the activity is STARTED (ADR 0008 section 1: nothing polls while
    // Brian watches another app); returning restarts it, and LiveFeeds fetches only what is due. It stops when the hero
    // leaves the composition. Until it exists the hero draws an empty snapshot.
    // Already built in this process (coming back to home): its snapshot at once; else null until after the first frame.
    var feeds by remember { mutableStateOf(graph.feedsIfBuilt) }
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(graph, owner) {
        withFrameNanos { }
        val f = feeds ?: withContext(Dispatchers.Default) { graph.feeds }.also { feeds = it }
        owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            delay(FEEDS_START_DELAY_MS)
            withContext(Dispatchers.Default) { f.run() }
        }
    }
    val emptyFeeds = remember { MutableStateFlow(FeedsSnapshot()) }
    val snap by (feeds?.snapshot ?: emptyFeeds).collectAsState()
    val agenda by graph.session.state.collectAsState()
    // The adb-set settings, read off the main thread once a minute (SharedPreferences: memory after the first read).
    // Starts from the last read in this process (memory, no disk), so a recreated activity has its location at once.
    val settings by produceState(HeroSettings.last ?: HeroSettings.NONE, now) {
        value = withContext(Dispatchers.IO) { runCatching { HeroSettings.read(context) }.getOrDefault(HeroSettings.EMPTY) }
    }
    val is24h = DateFormat.is24HourFormat(context)
    val zone = ZoneId.systemDefault()
    val instant = Instant.now()
    val inputs = remember(now, snap, agenda, settings, is24h, quiet) {
        // isInstalled reads a set built on IO (HeroSettings.read): no package manager call on the main thread.
        GlanceInputs(
            instant, zone, is24h, if (settings.qaPicks.isEmpty()) snap else snap.copy(picks = snap.picks + settings.qaPicks), settings.switches, settings.location != null, agenda, settings.travel,
            quiet = quiet, watchMap = settings.watchMap, isInstalled = { it in settings.installed },
        )
    }
    // M7-F6 brief. When home became visible (STARTED), else null: the ticker refreshes only while home is seen.
    var visibleSince by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(owner) {
        owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            visibleSince = SystemClock.elapsedRealtime()
            try { awaitCancellation() } finally { visibleSince = null }
        }
    }
    // Bumped after each refresh, so the line is re-read from the cache.
    var briefRefreshed by remember { mutableIntStateOf(0) }
    val facts = remember(inputs) { briefFacts(inputs) }
    // The switch and the cache file, read on IO (never on the main thread; the hero is composed after the first frame,
    // AC8). The source holds no title (placeholders only), so holding it across a Quiet change is safe. Keyed on the
    // switch too (settings are re-read each minute), so switching the brief off hides it within a minute (brief-off-lag).
    val briefSource by produceState<BriefSource?>(null, facts, briefRefreshed, settings.switches.brief) {
        value = withContext(Dispatchers.IO) { runCatching { graph.brief.load(facts) }.getOrNull() }
    }
    // On each minute tick: at most one request per slot (BriefLedger), off the main thread. Its own scope, so a feed
    // update or the next tick never cancels a request that is in flight (a cancel would still spend the slot).
    val briefScope = rememberCoroutineScope()
    LaunchedEffect(now) {
        val since = visibleSince ?: return@LaunchedEffect
        val tickFacts = facts
        briefScope.launch {
            val ran = withContext(Dispatchers.IO) {
                runCatching { graph.brief.onTick(tickFacts, SystemClock.elapsedRealtime() - since) }.getOrDefault(false)
            }
            if (ran) briefRefreshed++
        }
    }
    // Fit by measurement (brief-line-truncation): the row's real width (screen minus the safe margins and the drift
    // travel on both sides) at the row's font. A line that does not fit drops template parts, or the cached line falls
    // back to the template, so the band never ellipsizes mid-part. Same at 1280x720 and 1920x1080 (dp and sp scale).
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    // The row's real width: the home screen is laid out on the design canvas (DesignCanvasBox), whose width is
    // DesignCanvas.WIDTH_DP whatever the device's own dp width (960 on a 2.0-density 1080p screen).
    val briefWidthPx = with(density) { (DesignCanvas.WIDTH_DP - 2 * BRIEF_SIDE_DP).dp.toPx() }
    // Filled here, on main, in the same composition as quiet: pure and cheap (brief-quiet-flash).
    val brief = remember(briefSource, facts, quiet, briefWidthPx) {
        briefSource?.let { src ->
            briefLine(src.raw, facts, quiet) { t ->
                measurer.measure(t, BRIEF_STYLE, maxLines = 1, softWrap = false, density = density).size.width <= briefWidthPx
            }
        }
    }
    val glance = remember(inputs, brief) { homeGlance(inputs.copy(brief = brief)) }
    // The pill names the app it opens (watch-pill-truncated): an adb-mapped target gets its launcher label here.
    LaunchedEffect(glance.watch, settings.labels) {
        onWatch(glance.watch?.let { w -> if (w.appLabel != null) w else w.copy(appLabel = settings.labels[w.packageName]) })
    }
    val weather = (view(snap.weather, PanelPolicies.WEATHER, settings.switches.weather, instant) as? PanelView.Shown)?.value
    val game = if (settings.switches.games) GameDay.select(snap.games?.value.orEmpty(), instant, zone) else null
    // The real sky at the weather location, on the minute tick only (a few dozen trig calls). Null without a location:
    // the scenes keep the clock buckets and the sun its old fixed spot (never a guessed sky). Until the settings have
    // been read once, whether there is a location is unknown: the backdrop waits ([HeroBackdrop] settled), so a cold
    // start never shows the clock's scene and then the sky's (sky-cold-start-flash).
    val sky = remember(now, settings.location) { settings.location?.let { SkyNow.of(instant, it.lat, it.lon) } }
    // Game day beats holiday beats time of day; weather never changes the theme (chip and overlay only). The last sky
    // scene feeds the dead band at the scene lines (HeroTheme.at).
    val lastScene = remember { SceneMemo() }
    val resolved = remember(now, game, sky) {
        HeroTheme.resolve(now.toLocalDate(), now.toLocalTime(), game, sky?.position, lastScene.theme)
            .also { if (game == null) lastScene.theme = it }
    }
    val sun = rememberUpdatedState(remember(sky, resolved.motif) { heroSun(sky, resolved.motif) })
    val screenAsk = resolved.screen?.let { s -> resolved.tile?.let { t -> s to t } }
    LaunchedEffect(screenAsk) { onScreen(screenAsk) }
    val dim = idle.value == IdleState.DIM
    val ink = HeroInk(
        text = if (dim) OledTokens.DIM_TEXT else resolved.text,
        secondary = if (dim) OledTokens.DIM_TEXT else resolved.secondaryText,
    )
    // Game-day scoreboard: only while the card has a board, its team has a palette and the zones have room for it.
    // Otherwise everything below is as it was (the text card in the usual zones, the usual backdrop).
    val gameCard = (glance.card as? ContextCard.Game)?.takeIf { it.board != null }
    val boardPalette = gameCard?.let { BoardPalette.of(it.teamKey) }
    val gameZones = if (boardPalette != null) HeroZones.gameLayout(appsTop.value) else null
    val boardArt = boardPalette?.takeIf { gameZones != null }
    // Not focusable; the test tag lets the emulator QA read the idle state (scripts/ci/emulator-qa.sh).
    Box(modifier.fillMaxSize().testTag(HERO_IDLE_TAG_PREFIX + idle.value.name)) {
        HeroBackdrop(
            resolved, weather?.precipNow,
            boardArt = boardArt,
            settled = settings.loaded,
            screenColor = screenColor,
            sun = { sun.value },
            dimmed = { idle.value == IdleState.DIM },
            modifier = Modifier.fillMaxWidth().fillMaxHeight(HERO_BACKDROP_FRACTION),
        )
        val drifting = Modifier.graphicsLayer {
            val o = drift.value
            translationX = o.dx.dp.toPx()
            translationY = o.dy.dp.toPx()
        }
        val zones = HeroZones.layout(appsTop.value)
        // Game day accent (preds-arena): a thin full-strength team-colour line just under the hero text, in the gap
        // above the apps row where no text sits. It drifts with the text (OLED burn-in: a fixed line would mark the
        // panel), stays inside the drift's travel at both ends, and is hidden in DIM and Quiet (edge-line-burn-in).
        // Not drawn with the scoreboard: the boards carry the team colour.
        val edge = resolved.edge
        if (edge != null && !dim && !quiet && boardArt == null) {
            Box(
                Modifier
                    .offset(
                        (HeroZones.SAFE_X + Drift.MAX_X_DP.toInt()).dp,
                        (maxOf(zones.left.bottom, zones.right.bottom) + EDGE_GAP_DP).dp,
                    )
                    .size((HeroZones.SCREEN_W - 2 * (HeroZones.SAFE_X + Drift.MAX_X_DP.toInt())).dp, EDGE_DP.dp)
                    .then(drifting)
                    .background(rgb(edge)),
            )
        }
        HeroTopStrip(glance, resolved, ink, dim, drifting, zones.top)
        val board = gameCard?.board
        if (gameCard != null && board != null && boardArt != null && gameZones != null) {
            HeroNowNext(glance.nowNext, ink, drifting, gameZones.left)
            HeroGameBoard(gameCard, board, boardArt, gameZones, dim, ink.text, drifting)
        } else {
            HeroNowNext(glance.nowNext, ink, drifting, zones.left)
            HeroCard(glance.card, ink, drifting, zones.right)
        }
        // In the home screen's reserved brief row (HomeLayout.BRIEF_ROW_DP, the zone map's bottom band): the text box sits
        // inside it with the drift's travel above and below, so it never leaves the row or the safe area.
        glance.brief?.let { line ->
            Text(
                line,
                style = BRIEF_STYLE,
                color = rgb(if (dim) OledTokens.DIM_TEXT else briefColor),
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Clip, // measured to fit above; never an ellipsis
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = BRIEF_SIDE_DP.dp, end = BRIEF_SIDE_DP.dp, bottom = (Tokens.MARGIN_V_DP + Drift.MAX_Y_DP.toInt()).dp)
                    .height(BRIEF_TEXT_DP.dp)
                    .then(drifting)
                    .testTag(DOCK_BRIEF_TAG),
            )
        }
    }
}

/** The brief row's text: the tile-label size (24 sp = 36 px at 1080p, over the 32 px minimum). */
private val BRIEF_STYLE = TextStyle(fontSize = Tokens.SECONDARY_SP.sp)

/** Left and right inset of the brief line: the 5% overscan safe margin plus the drift's travel, so it never leaves it. */
private val BRIEF_SIDE_DP = Tokens.MARGIN_H_DP + Drift.MAX_X_DP.toInt()

/** The dock band's brief row (reserved by the home screen under the dock; the zone map's [HomeLayout.BRIEF_ROW_DP]). */
val BRIEF_ROW_DP: Int = HomeLayout.BRIEF_ROW_DP

/** Height of the brief text in its row: the row less the drift's vertical travel above and below (32 dp). */
private val BRIEF_TEXT_DP: Int = BRIEF_ROW_DP - 2 * Drift.MAX_Y_DP.toInt()

/** Test tag of the brief line (uiautomator resource-id), read by the emulator QA check. */
const val DOCK_BRIEF_TAG = "dock_brief"

private const val FEEDS_START_DELAY_MS = 1500L

/**
 * The game-day edge line: 2 dp, 2 dp under the text zones. With the drift's 4 dp travel it stays within
 * [HeroZones.APPS_GAP] (8 dp) above the apps row.
 */
private const val EDGE_DP = 2
private const val EDGE_GAP_DP = 2

/** The theme shown last minute, outside game day: the dead band's memory (written in a remember block only). */
private class SceneMemo(var theme: HeroTheme? = null)

/** The text colours the hero draws with: the theme's, or the DIM grey. */
private class HeroInk(val text: Int, val secondary: Int)

/** The adb-set settings the hero reads ([FeedSettings]). */
private data class HeroSettings(
    val switches: PanelSwitches,
    /** The weather location; the hero's sun follows the real sky there (null: the clock buckets). */
    val location: Location?,
    val travel: List<TravelRule>,
    val watchMap: WatchMap = WatchMap.EMPTY,
    /** The watch map's packages that have a TV launch intent, checked once a minute on IO. */
    val installed: Set<String> = emptySet(),
    /** Launcher labels by package, for the Watch pill's "Watch on <app>". */
    val labels: Map<String, String> = emptyMap(),
    /** False until the settings have been read once: whether a location is set is not known yet. */
    val loaded: Boolean = true,
    /** qa build only ([PickQaHook]): picks planted by the emulator QA, by team key. Always empty in release and debug. */
    val qaPicks: Map<String, Pick> = emptyMap(),
) {
    companion object {
        /** Not read yet. */
        val NONE = HeroSettings(PanelSwitches(), location = null, travel = emptyList(), loaded = false)

        /** Read, but the read failed: the defaults, shown as they are. */
        val EMPTY = NONE.copy(loaded = true)

        /** The last successful read in this process. */
        @Volatile var last: HeroSettings? = null

        /** Disk and package manager: call off the main thread. */
        fun read(context: Context): HeroSettings {
            // watch-defaults: the adb map first, then the built-in one over the launchable apps (CDE for broadcast,
            // local and cable channels; streaming apps when installed). Its targets are launchable by construction.
            val apps = InstalledApps.launcherApps(context)
            val installed = FeedSettings.watchPackages(context).filterTo(HashSet()) { InstalledApps.canLaunch(context, it) }
            apps.mapTo(installed) { it.packageName }
            return HeroSettings(
                FeedSettings.switches(context), FeedSettings.location(context), FeedSettings.travelRules(context),
                FeedSettings.watchMap(context).orElse(WatchDefaults.map(apps)),
                installed,
                apps.associate { it.packageName to it.label },
                qaPicks = PickQaHook.planted(context),
            ).also { last = it }
        }
    }
}

/** Places a composable on one of the fixed [HeroZones] (dp at 1280x720). */
private fun Modifier.zone(z: HeroZones.Zone): Modifier = offset(z.x.dp, z.y.dp).size(z.w.dp, z.h.dp).clipToBounds()

/**
 * The current local time. Driven by the system's minute tick and by time and time-zone changes (no `delay`), so it stays
 * right across device sleep, a clock change or a zone change (clock-sleep-stale). The receiver is registered while the
 * composable is composed and unregistered when it leaves. All three actions are protected system broadcasts, so no
 * exported/not-exported flag is needed.
 */
@Composable
internal fun rememberMinuteClock(): State<LocalDateTime> {
    val context = LocalContext.current
    val state = remember { mutableStateOf(LocalDateTime.now()) }
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) { state.value = LocalDateTime.now() }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        context.registerReceiver(receiver, filter)
        state.value = LocalDateTime.now() // catch up on anything missed before registering
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }
    return state
}

/** Top strip: clock (40 sp) and date left, holiday badge after them; weather chip with any alert suffix right. */
@Composable
private fun HeroTopStrip(g: HomeGlance, theme: HeroTheme, ink: HeroInk, dim: Boolean, drifting: Modifier, zone: HeroZones.Zone) {
    // The clock, date, Quiet and badge are measured first at their full width (never clipped: "LIV" in the emulator
    // tour); the chip takes what is left, right-aligned, and ellipsizes inside the safe area.
    Row(
        drifting.zone(zone),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Text(g.clock, color = rgb(ink.text), fontSize = GlanceTokens.CLOCK_SP.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text(g.date, color = rgb(ink.secondary), fontSize = GlanceTokens.DATE_SP.sp, maxLines = 1)
            if (g.quiet) {
                // M7-F6: the visible Quiet marker (titles show as "Busy").
                Text(
                    QUIET_MARKER, color = rgb(ink.secondary), fontSize = GlanceTokens.DATE_SP.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, modifier = Modifier.testTag(HERO_QUIET_TAG),
                )
            }
            val badge = theme.badge
            if (badge != null && dim) {
                // DIM: no bright pill, just the dim grey text (7:1 on the dimmed field, GlanceTokensTest).
                Text(badge, color = rgb(OledTokens.DIM_TEXT), fontSize = GlanceTokens.BADGE_SP.sp, fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.6.sp, maxLines = 1)
            } else if (badge != null) {
                Text(
                    badge,
                    color = rgb(theme.backdropTop),
                    fontSize = GlanceTokens.BADGE_SP.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.6.sp,
                    maxLines = 1,
                    modifier = Modifier
                        .background(rgb(theme.accent), RoundedCornerShape(50))
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
        }
        val chip = g.chip
        if (chip != null) {
            Text(
                chip, color = rgb(ink.secondary), fontSize = GlanceTokens.CHIP_SP.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.End, modifier = Modifier.weight(1f).padding(start = 24.dp),
            )
        }
    }
}

/** Hero left 60%: Now/Next line 1 (44 sp), line 2 (28 sp), the data-age line (22 sp). Nothing when hidden. */
@Composable
private fun HeroNowNext(nn: NowNext?, ink: HeroInk, drifting: Modifier, zone: HeroZones.Zone) {
    nn ?: return
    if (zone.h <= 0) return
    Column(drifting.zone(zone), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            nn.line1,
            color = rgb(ink.text),
            fontSize = GlanceTokens.HERO_SP.sp,
            lineHeight = (GlanceTokens.HERO_SP + 6).sp,
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.Bold,
            // One line when a large font scale leaves no room for two (board-fontscale).
            maxLines = HeroZones.nowNextLine1Max(zone.h, LocalDensity.current.fontScale),
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.semantics { contentDescription = HERO_NOW_DESC_PREFIX + nn.line1 },
        )
        nn.line2?.let {
            Text(it, color = rgb(ink.secondary), fontSize = GlanceTokens.HERO_LINE2_SP.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        nn.ageText?.let {
            Text(it, color = rgb(ink.secondary), fontSize = GlanceTokens.AGE_SP.sp, maxLines = 1)
        }
    }
}

/** Hero right 40%: a severe alert on an opaque red card (40 sp), or today's game line. Nothing when there is neither. */
/**
 * The game card with a pick is one focus target (Up from the apps row reaches it, being the only target above): a ring
 * while focused, and OK opens the whole pick in a dialog (Brian 2026-11-13: "make it selectable.. the full quote pops
 * out"). A leaf with no focusable parent, so Back from it still leaves the screen in one press (home-back-twice), and
 * the dialog is its own window, so it adds no target to the home window. Back closes it.
 */
@Composable
private fun PickDialog(full: String, ink: HeroInk, onDismiss: () -> Unit) {
    // Wider than the platform default, so the longest analysis (Picks.MAX_ANALYSIS) fits the screen without scrolling.
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Text(
            full,
            color = rgb(PICK_DIALOG_TEXT),
            fontSize = GlanceTokens.DATE_SP.sp,
            lineHeight = (GlanceTokens.DATE_SP + 8).sp,
            modifier = Modifier
                .width(900.dp)
                .background(rgb(PICK_DIALOG_FILL), RoundedCornerShape(16.dp))
                .border(3.dp, rgb(ink.secondary), RoundedCornerShape(16.dp))
                .padding(32.dp),
        )
    }
}

private const val PICK_DIALOG_TEXT = 0xF2F4F8
private const val PICK_DIALOG_FILL = 0x0B0D12

@Composable
private fun HeroCard(card: ContextCard?, ink: HeroInk, drifting: Modifier, zone: HeroZones.Zone) {
    card ?: return
    if (zone.h <= 0) return
    var pickFocused by remember { mutableStateOf(false) }
    var pickOpen by remember { mutableStateOf(false) }
    val pickFull = (card as? ContextCard.Game)?.pickFull?.takeIf { ink.text != OledTokens.DIM_TEXT }
    if (pickOpen && pickFull != null) PickDialog(pickFull, ink) { pickOpen = false }
    Box(drifting.zone(zone), contentAlignment = Alignment.TopStart) {
        when (card) {
            is ContextCard.Alert -> Column(
                Modifier
                    .fillMaxWidth()
                    .background(rgb(GlanceTokens.ALERT_FILL), RoundedCornerShape(12.dp))
                    .padding(horizontal = 24.dp, vertical = 20.dp)
                    .semantics(mergeDescendants = true) { contentDescription = HERO_CARD_DESC_PREFIX + card.text },
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // The alert text is the hero text colour in DIM too (the fill keeps 7:1 for both; GlanceTokensTest).
                val c = if (ink.text == OledTokens.DIM_TEXT) OledTokens.DIM_TEXT else GlanceTokens.ALERT_TEXT
                Text(
                    card.event, color = rgb(c), fontSize = GlanceTokens.ALERT_SP.sp, lineHeight = (GlanceTokens.ALERT_SP + 4).sp,
                    fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                card.until?.let { Text(it, color = rgb(c), fontSize = GlanceTokens.ALERT_UNTIL_SP.sp, maxLines = 1) }
            }
            is ContextCard.Game -> Column(
                Modifier
                    .then(
                        if (pickFull == null) Modifier
                        else Modifier
                            .onFocusChanged { pickFocused = it.isFocused }
                            .border(3.dp, if (pickFocused) rgb(ink.text) else Color.Transparent, RoundedCornerShape(12.dp))
                            .clickable { pickOpen = true }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                    .semantics(mergeDescendants = true) { contentDescription = HERO_CARD_DESC_PREFIX + card.line },
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    card.heading ?: when (card.phase) { GamePhase.LIVE -> GAME_LIVE; GamePhase.FINAL -> GAME_FINAL; GamePhase.UPCOMING -> GAME_TODAY },
                    color = rgb(ink.secondary), fontSize = GlanceTokens.DATE_SP.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                )
                Text(
                    card.line,
                    color = rgb(ink.text),
                    fontSize = GlanceTokens.CARD_SP.sp,
                    lineHeight = (GlanceTokens.CARD_SP + 6).sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = if (card.pick != null) 2 else 3,
                    overflow = TextOverflow.Ellipsis,
                )
                // Claude's game-day pick (Brian 2026-11-13): secondary ink, under the game line, never pushing it out.
                card.pick?.let {
                    Text(
                        it,
                        color = rgb(ink.secondary),
                        fontSize = GlanceTokens.DATE_SP.sp,
                        lineHeight = (GlanceTokens.DATE_SP + 4).sp,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            // Spec M7-4.5: morning weather arc, or evening tomorrow's first event and the overnight low.
            is ContextCard.TimeOfDay -> Column(
                Modifier.semantics(mergeDescendants = true) {
                    contentDescription = HERO_CARD_DESC_PREFIX + listOfNotNull(card.heading, card.text, card.detail).joinToString(" ")
                },
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(card.heading, color = rgb(ink.secondary), fontSize = GlanceTokens.DATE_SP.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text(
                    card.text, color = rgb(ink.text), fontSize = GlanceTokens.CARD_SP.sp, lineHeight = (GlanceTokens.CARD_SP + 6).sp,
                    fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                card.detail?.let {
                    Text(it, color = rgb(ink.secondary), fontSize = GlanceTokens.HERO_LINE2_SP.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

private const val OPAQUE = 0xFF000000.toInt()

private fun rgb(c: Int, alpha: Float = 1f) = Color(c or OPAQUE).copy(alpha = alpha)

/**
 * The backdrop: a pre-rendered image per motif (tools/art/render_backdrops.py, res/drawable-nodpi/hero_*.webp) over the
 * theme gradient, with the Canvas motif drawing as the fallback until the image is in. No network. Takes just the theme,
 * so it is skipped when the focus changes. Nothing here is focusable (home-back-twice, app-row-reentry).
 *
 * - Own `graphicsLayer`, so the animation redraws this layer and not the screen (snow-redraw-scope).
 * - `drawWithCache` builds the brushes and the hill and moon paths once per size and theme, not per frame.
 * - First frame stays cheap: only the gradient and the bottom fade are drawn until the first frame has been shown; the
 *   motif, the image decode and the animation start after that (cold-start budget, AC8). The decode runs on IO and the
 *   image crossfades in over the Canvas motif (no crossfade when animations are off).
 * - The image is drawn with the theme's own gradient over it at [BackdropArt.TINT_ALPHA] when the theme is not the one the image was
 *   rendered for (Thanksgiving and Valentine's share SUNSET, New Year and July 4 share STARS, Ohio State's stadium is GAME, the Predators' arena ARENA).
 * - The sun scenes' images (DAWN, DAY, SUNSET) have no sun: [sun] places it where the real sun is (core SunArc, moved on
 *   the minute tick only), clipped to the sky above the art's hills (BackdropSkyline), only over the decoded image and
 *   fading in with it. Null [sun]: no sun.
 * - Until [settled] (the hero's settings read once, so whether a location is set is known), only the screen background
 *   is drawn and nothing is decoded: no clock scene that the real sky then replaces.
 * - The rain and snow particles are drawn on top of the image.
 * - A scrim (read in the draw phase) goes over everything but the bottom fade (M7, R3): black at
 *   [GlanceTokens.SCRIM_ALPHA], or the theme's [HeroTheme.scrim] team colour on game days (navy, scarlet) so the whole
 *   hero reads in it; while [dimmed], black at the DIM brightness. The bottom fade goes to [screenColor], the screen
 *   background actually drawn (navy on a Predators game day), so it meets the screen without a seam.
 * - Only Christmas animates (slow snowfall), plus the rain or snow overlay while it is falling now. The loop ticks about 15 times a second with a delay, not every vsync, and
 *   does not run at all when the system animator scale is 0 (snow-uiautomator-idle: QA sets that so uiautomator can
 *   reach an idle state; it also honours the user's "remove animations" choice).
 */
@Composable
private fun HeroBackdrop(
    theme: HeroTheme,
    precip: Precip?,
    /**
     * Set while the game-day scoreboard shows: no arena or stadium image, no scrim and no weather overlay, but the team
     * gradient with a soft glow behind the surface and two faint light cones from the top (mockup.html `.art`).
     */
    boardArt: BoardPalette? = null,
    settled: Boolean,
    sun: () -> SunSpot?,
    screenColor: () -> Int,
    dimmed: () -> Boolean,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val frozen = remember(context) { animationsFrozen(context) }
    var ready by remember { mutableStateOf(false) }
    val tick = remember { mutableLongStateOf(0L) }
    LaunchedEffect(settled) {
        if (!settled) return@LaunchedEffect // the scene is not known yet (no location read): nothing is decoded
        withFrameNanos { } // the first frame has been drawn with the plain gradient
        ready = true
    }
    var backdrop by remember { mutableStateOf<Backdrop?>(null) }
    val imageAlpha = remember { Animatable(0f) }
    val plain = boardArt != null
    LaunchedEffect(ready, theme.motif, plain) {
        if (!ready || plain) return@LaunchedEffect // the scoreboard's backdrop has no image: nothing is decoded
        if (backdrop?.motif == theme.motif) { imageAlpha.snapTo(1f); return@LaunchedEffect } // back to the shown image
        // Decode first, so the current image stays up until the new one is ready (backdrop-alpha-stuck).
        val image = withContext(Dispatchers.IO) { decodeBackdrop(context, theme.motif) } ?: return@LaunchedEffect
        imageAlpha.snapTo(0f)
        backdrop = Backdrop(theme.motif, image)
        if (frozen) imageAlpha.snapTo(1f) else imageAlpha.animateTo(1f, tween(CROSSFADE_MS))
    }
    // No overlay (and no tick) when its capped alpha would be invisible: the Predators' indoor arena (BackdropArt).
    // None with the scoreboard either: its backdrop has no scrim for the particle caps to rely on.
    val overlay = precip?.takeIf { !plain && BackdropArt.showsPrecip(theme, it) }
    val animate = ready && !frozen && (theme.motif == HeroMotif.SNOW || overlay != null)
    // The particle tick runs only while the activity is STARTED (no animation work behind another app).
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(animate, owner) {
        if (!animate) return@LaunchedEffect
        val t0 = SystemClock.elapsedRealtime()
        owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                delay(FRAME_MS)
                tick.longValue = (SystemClock.elapsedRealtime() - t0) * 1_000_000L
            }
        }
    }
    Box(
        modifier
            .graphicsLayer { }
            .drawWithCache {
                val w = size.width
                val h = size.height
                val base = Brush.verticalGradient(listOf(rgb(theme.backdropTop), rgb(theme.backdropBottom)))
                // Fade into the screen background so the tile row sits on a calm base.
                // The fade follows the screen colour as it eases (fade-seam-during-ease): rebuilt only when it changes.
                var fadeFor = -1
                var fade: Brush = Brush.verticalGradient(listOf(Color.Transparent, Color.Transparent))
                fun fadeTo(c: Int): Brush {
                    if (c != fadeFor) {
                        fade = Brush.verticalGradient(listOf(Color.Transparent, rgb(c)), startY = h * 0.6f, endY = h)
                        fadeFor = c
                    }
                    return fade
                }
                val scrimColour = rgb(theme.scrim)
                val hill = if (theme.motif == HeroMotif.SNOW) hillPath(w, h) else null
                val moon = if (theme.motif == HeroMotif.STARS) moonPath(w, h) else null
                val tint = if (BackdropArt.tinted(theme)) base else null
                // Particle alphas capped so text keeps 7:1 over a team-coloured scrim (precip-over-team-scrim).
                val snowScale = BackdropArt.precipScale(theme, 0xFFFFFF, BackdropArt.SNOW_MAX_ALPHA)
                val rainScale = BackdropArt.precipScale(theme, BackdropArt.RAIN, BackdropArt.RAIN_MAX_ALPHA)
                val sunLook = sunLook(theme)
                val art = ArtFit(w, h)
                val sky = sunLook?.let { skyPath(art, BackdropSkyline.of(theme.motif)) }
                var sunCache: Pair<SunSpot, SunBrushes>? = null
                val board = boardArt?.let { boardBackdrop(it, w, h) }
                onDrawBehind {
                    // Until the settings are read, the plain screen background: the scene is not known yet.
                    val screen = screenColor()
                    if (board != null && settled) {
                        // The scoreboard's own backdrop: its colours are final, so no scrim; in DIM the usual black one.
                        val dim = dimmed()
                        drawBoardBackdrop(board, lit = !dim)
                        if (dim) drawRect(Color.Black, alpha = 1f - OledTokens.DIM_BACKDROP_BRIGHTNESS)
                        drawRect(fadeTo(screen))
                        return@onDrawBehind
                    }
                    if (settled) drawRect(base) else drawRect(rgb(screen))
                    // Read in the draw phase only: a decode or a crossfade step redraws this layer, nothing else.
                    val image = backdrop?.takeIf { it.motif == theme.motif }?.image
                    val alpha = if (image == null) 0f else imageAlpha.value
                    if (ready && alpha < 1f) {
                        when (theme.motif) {
                            HeroMotif.MOON_BATS -> drawMoonAndBats(theme)
                            HeroMotif.SNOW -> { if (image == null) drawSnow(tick.longValue, snowScale); hill?.let { drawPath(it, Color.White.copy(alpha = 0.08f)) } }
                            HeroMotif.DAWN, HeroMotif.SUNSET -> Unit // the sun is drawn below, over image or not
                            HeroMotif.DAY -> drawClouds(0.22f)
                            HeroMotif.STARS -> drawStars(theme, moon)
                            HeroMotif.GAME -> if (sun() != null) drawSun(theme, 0.80f, 0.36f, 0.08f, 0.22f)
                            HeroMotif.ARENA -> Unit // indoors: the navy gradient until the image is in
                        }
                    }
                    if (image != null && alpha > 0f) {
                        drawBackdrop(image, alpha)
                        tint?.let { drawRect(it, alpha = BackdropArt.TINT_ALPHA * alpha) }
                        // Christmas snowfall keeps falling over the rendered scene.
                        if (theme.motif == HeroMotif.SNOW) drawSnow(tick.longValue, snowScale)
                    }
                    // The sun scenes' art has no sun: it is drawn where the real one is (read in the draw phase, so the
                    // minute's move redraws this layer only), clipped to the sky above the hills.
                    // Only over the decoded image, fading in with it (sun-clip-fallback): never on the bare gradient.
                    val spot = if (image != null && alpha > 0f && sunLook != null && sky != null) sun() else null
                    if (spot != null && sunLook != null && sky != null) {
                        val brushes = sunCache?.takeIf { it.first == spot }?.second
                            ?: sunBrushes(sunLook, art.at(spot), art.px(sunLook.radius)).also { sunCache = spot to it }
                        clipPath(sky) { drawRealSun(sunLook, brushes, alpha) }
                    }
                    if (ready) {
                        // Weather motif overlay (never a theme change): particles only when it is falling now.
                        when (precip) {
                            Precip.SNOW -> if (theme.motif != HeroMotif.SNOW && overlay != null) drawSnow(tick.longValue, snowScale)
                            Precip.RAIN -> if (overlay != null) drawRain(tick.longValue, rainScale)
                            null -> Unit
                        }
                    }
                    // R3: the scrim caps the brightest backdrop pixel at 25% (15% in DIM), so hero text keeps 7:1 over
                    // any art. Under the fade, so the backdrop still meets the screen background without a seam.
                    if (dimmed()) drawRect(Color.Black, alpha = 1f - OledTokens.DIM_BACKDROP_BRIGHTNESS)
                    else drawRect(scrimColour, alpha = GlanceTokens.SCRIM_ALPHA)
                    drawRect(fadeTo(screen))
                }
            },
    )
}

private class Backdrop(val motif: HeroMotif, val image: ImageBitmap)

/** The scoreboard backdrop's brushes and cone shapes, built once per size and palette. */
private class BoardBackdrop(val base: Brush, val glow: Brush, val glowAt: Offset, val glowR: Float, val cones: List<Pair<Path, Brush>>)

/**
 * mockup.html `.art` on its 1280 x 400 box: a three-stop team gradient, a 560 x 190 glow ellipse centred at (890, 190)
 * behind the surface, and two light cones 17 degrees wide from above the top edge at x 700 and 1080.
 */
private fun boardBackdrop(p: BoardPalette, w: Float, h: Float): BoardBackdrop {
    val base = Brush.verticalGradient(0f to rgb(p.artTop), p.artMidAt to rgb(p.artMid), 1f to rgb(p.artBottom))
    val glowAt = Offset(w * 890f / 1280f, h * 190f / 400f)
    val glowR = h * 190f / 400f
    val glow = Brush.radialGradient(listOf(rgb(p.glow, BoardTokens.GLOW_ALPHA), rgb(p.glow, 0f)), glowAt, glowR)
    val light = rgb(p.cone, p.coneAlpha)
    // (apex x, the cone's first edge in degrees clockwise from straight up): the CSS conic gradients' `from` angles.
    val cones = listOf(700f to 168.0, 1080f to 176.0).map { (x, from) ->
        val apex = Offset(w * x / 1280f, -h * 40f / 400f)
        val reach = h * 1.4f
        fun edge(deg: Double): Offset {
            val a = Math.toRadians(deg)
            return Offset(apex.x + reach * kotlin.math.sin(a).toFloat(), apex.y - reach * kotlin.math.cos(a).toFloat())
        }
        val first = edge(from)
        val last = edge(from + 17.0)
        val path = Path().apply { moveTo(apex.x, apex.y); lineTo(first.x, first.y); lineTo(last.x, last.y); close() }
        // Brightest 8 degrees in, fading to nothing at both edges (across the cone, between its two far corners).
        path to Brush.linearGradient(0f to Color.Transparent, 8f / 17f to light, 1f to Color.Transparent, start = first, end = last)
    }
    return BoardBackdrop(base, glow, glowAt, glowR, cones)
}

/** [lit] is false in DIM: the gradient only, no glow and no cones. */
private fun DrawScope.drawBoardBackdrop(b: BoardBackdrop, lit: Boolean) {
    drawRect(b.base)
    if (!lit) return
    scale(560f / 190f, 1f, pivot = b.glowAt) { drawCircle(b.glow, b.glowR, b.glowAt) }
    for ((path, brush) in b.cones) drawPath(path, brush)
}

private const val CROSSFADE_MS = 700


private fun backdropRes(motif: HeroMotif): Int = when (motif) {
    HeroMotif.DAWN -> R.drawable.hero_dawn
    HeroMotif.DAY -> R.drawable.hero_day
    HeroMotif.SUNSET -> R.drawable.hero_sunset
    HeroMotif.STARS -> R.drawable.hero_stars
    HeroMotif.MOON_BATS -> R.drawable.hero_moon_bats
    HeroMotif.SNOW -> R.drawable.hero_snow
    HeroMotif.GAME -> R.drawable.hero_game
    HeroMotif.ARENA -> R.drawable.hero_arena
}


/**
 * Decodes the motif's 1920x600 WebP. Call off the main thread. ARGB_8888, not RGB_565: 565 bands the dim night and dawn
 * skies visibly, and the 4.6 MB bitmap is one at a time. prepareToDraw uploads it to the GPU here, not on the first draw.
 * Null if the decode fails; the Canvas motif stays.
 */
private fun decodeBackdrop(context: Context, motif: HeroMotif): ImageBitmap? = runCatching {
    val opts = BitmapFactory.Options().apply {
        inPreferredConfig = Bitmap.Config.ARGB_8888
        inScaled = false
    }
    BitmapFactory.decodeResource(context.resources, backdropRes(motif), opts)?.also { it.prepareToDraw() }?.asImageBitmap()
}.getOrNull()

/** Cover the backdrop area: scale to fill, centred horizontally, top aligned (the bottom fades out anyway). */
private fun DrawScope.drawBackdrop(image: ImageBitmap, alpha: Float) {
    val dw = size.width
    val dh = size.height
    if (dw <= 0f || dh <= 0f) return
    val scale = maxOf(dw / image.width, dh / image.height)
    val sw = (dw / scale).toInt().coerceIn(1, image.width)
    val sh = (dh / scale).toInt().coerceIn(1, image.height)
    drawImage(
        image,
        srcOffset = IntOffset((image.width - sw) / 2, 0),
        srcSize = IntSize(sw, sh),
        dstSize = IntSize(dw.toInt(), dh.toInt()),
        alpha = alpha,
    )
}

/** True when the system animator duration scale is 0 (developer option, or QA via `settings put global`). */
private fun animationsFrozen(context: Context): Boolean = runCatching {
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
}.getOrDefault(false)

private const val FRAME_MS = 66L

private fun DrawScope.drawSun(theme: HeroTheme, cx: Float, cy: Float, r: Float, glowAlpha: Float) {
    val c = Offset(size.width * cx, size.height * cy)
    val rad = size.height * r
    drawCircle(rgb(theme.accent, glowAlpha * 0.5f), rad * 2.6f, c)
    drawCircle(rgb(theme.accent, glowAlpha), rad * 1.6f, c)
    drawCircle(rgb(theme.accent, 0.9f), rad, c)
}

/** The sun's spot for this minute: the real sun when a location is set, else the art's old fixed spot. */
private fun heroSun(sky: SkyNow?, motif: HeroMotif): SunSpot? = when {
    sky == null -> SunArc.fixed(motif) ?: if (motif == HeroMotif.GAME) GAME_SUN else null
    motif == HeroMotif.GAME -> if (sky.position.elevation >= 0) GAME_SUN else null
    else -> SunArc.place(sky, BackdropSkyline.of(motif))
}

/** GAME's Canvas fallback sun (the image has none); only its presence is used. */
private val GAME_SUN = SunSpot(0.80f, 0.36f)

/** Size and colours of the sun in each sun motif, as the art had it (render_backdrops.py), glow in the theme's accent. */
private class SunLook(val radius: Float, val core: Int, val halo: Int, val accent: Int, val streak: Int?)

private fun sunLook(theme: HeroTheme): SunLook? = when (theme.motif) {
    HeroMotif.DAWN -> SunLook(0.05f, 0xFFF0C8, 0xFFD9A0, theme.accent, 0xE89A6A)
    HeroMotif.DAY -> SunLook(0.04f, 0xFFF6D8, 0xFFF0C8, theme.accent, null)
    HeroMotif.SUNSET -> SunLook(0.065f, 0xFFD08A, 0xFFB070, theme.accent, 0xE07048)
    else -> null
}

/** Image (1920 x 600) fractions to canvas pixels, the same cover fit as [drawBackdrop]. */
private class ArtFit(w: Float, h: Float) {
    private val scale = maxOf(w / ART_W, h / ART_H)
    private val dx = (w - ART_W * scale) / 2
    fun x(fx: Float) = dx + fx * ART_W * scale
    fun y(fy: Float) = fy * ART_H * scale
    fun at(s: SunSpot) = Offset(x(s.x), y(s.y))
    fun px(fy: Float) = fy * ART_H * scale
}

private const val ART_W = 1920f
private const val ART_H = 600f

/** The sky above the hills (or above the flat horizon), as a clip: the sun never shows in front of the land. */
private fun skyPath(art: ArtFit, line: FloatArray?): Path = Path().apply {
    val pts = line ?: floatArrayOf(BackdropSkyline.HORIZON, BackdropSkyline.HORIZON)
    moveTo(art.x(0f), 0f)
    for (i in pts.indices) lineTo(art.x(i.toFloat() / (pts.size - 1)), art.y(pts[i]))
    lineTo(art.x(1f), 0f)
    close()
}

private class SunBrushes(val c: Offset, val r: Float, val wide: Brush, val near: Brush, val streak: Brush?)

/** Built once per spot (once a minute), not per frame: a rain or snow overlay redraws the layer 15 times a second. */
private fun sunBrushes(look: SunLook, c: Offset, r: Float) = SunBrushes(
    c, r,
    wide = Brush.radialGradient(listOf(rgb(look.accent, 0.30f), rgb(look.accent, 0.10f), Color.Transparent), c, r * 9f),
    near = Brush.radialGradient(listOf(rgb(look.halo, 0.55f), rgb(look.halo, 0f)), c, r * 2.6f),
    streak = look.streak?.let { Brush.radialGradient(listOf(rgb(it, 0.45f), rgb(it, 0f)), c, r * 3f) },
)

private fun DrawScope.drawRealSun(look: SunLook, b: SunBrushes, alpha: Float) {
    // Low sun: a warm band along the horizon, the art's old stretched glow (3 radii tall, 24 wide).
    b.streak?.let { s -> scale(8f, 1f, pivot = b.c) { drawCircle(s, b.r * 3f, b.c, alpha = alpha) } }
    drawCircle(b.wide, b.r * 9f, b.c, alpha = alpha)
    drawCircle(b.near, b.r * 2.6f, b.c, alpha = alpha)
    drawCircle(rgb(look.core), b.r, b.c, alpha = alpha)
}

private fun DrawScope.drawClouds(alpha: Float) {
    val h = size.height
    fun cloud(cx: Float, cy: Float, s: Float) {
        val base = Offset(size.width * cx, h * cy)
        val r = h * 0.05f * s
        drawCircle(Color.White.copy(alpha = alpha), r, base)
        drawCircle(Color.White.copy(alpha = alpha), r * 1.3f, base + Offset(r * 1.2f, -r * 0.4f))
        drawCircle(Color.White.copy(alpha = alpha), r, base + Offset(r * 2.4f, 0f))
        drawRoundRect(Color.White.copy(alpha = alpha), base + Offset(-r * 0.5f, 0f), Size(r * 3.8f, r * 0.9f),
            androidx.compose.ui.geometry.CornerRadius(r * 0.45f))
    }
    cloud(0.58f, 0.52f, 1f)
    cloud(0.90f, 0.62f, 0.8f)
}

private fun DrawScope.drawStars(theme: HeroTheme, moon: Path?) {
    // Deterministic scatter, kept to the right and upper part away from the title; dim for night.
    for (i in 0 until 46) {
        val x = frac(i * 0.6180339f + 0.13f)
        val y = frac(i * 0.7548776f + 0.31f) * 0.8f
        val a = 0.25f + 0.45f * frac(i * 0.3247f)
        drawCircle(rgb(theme.text, a), size.height * (0.004f + 0.004f * frac(i * 0.57f)), Offset(size.width * (0.4f + 0.6f * x), size.height * y))
    }
    // A quiet crescent moon.
    if (moon != null) drawPath(moon, rgb(theme.text, 0.55f))
}

private fun moonPath(w: Float, h: Float): Path {
    val c = Offset(w * 0.82f, h * 0.40f)
    val r = h * 0.09f
    val moon = Path().apply { addOval(androidx.compose.ui.geometry.Rect(c, r)) }
    val bite = Path().apply { addOval(androidx.compose.ui.geometry.Rect(c + Offset(r * 0.5f, -r * 0.2f), r * 0.9f)) }
    return Path.combine(PathOperation.Difference, moon, bite)
}

private fun DrawScope.drawMoonAndBats(theme: HeroTheme) {
    val c = Offset(size.width * 0.78f, size.height * 0.46f)
    val r = size.height * 0.16f
    drawCircle(rgb(theme.accent, 0.10f), r * 1.9f, c)
    drawCircle(rgb(theme.accent, 0.16f), r * 1.4f, c)
    drawCircle(rgb(0xF2E3B8, 0.92f), r, c)
    drawCircle(rgb(0xD8C792, 0.55f), r * 0.16f, c + Offset(-r * 0.35f, -r * 0.2f))
    drawCircle(rgb(0xD8C792, 0.55f), r * 0.11f, c + Offset(r * 0.30f, r * 0.30f))
    drawBat(Offset(size.width * 0.70f, size.height * 0.38f), size.height * 0.07f)
    drawBat(Offset(size.width * 0.86f, size.height * 0.30f), size.height * 0.05f)
    drawBat(Offset(size.width * 0.63f, size.height * 0.56f), size.height * 0.04f)
}

private fun DrawScope.drawBat(center: Offset, s: Float) {
    val p = Path().apply {
        moveTo(center.x, center.y - s * 0.25f)
        // right wing, scalloped edge
        quadraticBezierTo(center.x + s * 0.6f, center.y - s * 0.9f, center.x + s * 1.6f, center.y - s * 0.4f)
        quadraticBezierTo(center.x + s * 1.3f, center.y - s * 0.1f, center.x + s * 1.2f, center.y + s * 0.2f)
        quadraticBezierTo(center.x + s * 0.9f, center.y, center.x + s * 0.6f, center.y + s * 0.35f)
        quadraticBezierTo(center.x + s * 0.3f, center.y + s * 0.1f, center.x, center.y + s * 0.5f)
        // left wing, mirrored
        quadraticBezierTo(center.x - s * 0.3f, center.y + s * 0.1f, center.x - s * 0.6f, center.y + s * 0.35f)
        quadraticBezierTo(center.x - s * 0.9f, center.y, center.x - s * 1.2f, center.y + s * 0.2f)
        quadraticBezierTo(center.x - s * 1.3f, center.y - s * 0.1f, center.x - s * 1.6f, center.y - s * 0.4f)
        quadraticBezierTo(center.x - s * 0.6f, center.y - s * 0.9f, center.x, center.y - s * 0.25f)
        close()
    }
    drawPath(p, Color(0xFF0A0610))
    // ears
    drawCircle(Color(0xFF0A0610), s * 0.12f, center + Offset(-s * 0.12f, -s * 0.32f))
    drawCircle(Color(0xFF0A0610), s * 0.12f, center + Offset(s * 0.12f, -s * 0.32f))
}

/** [scale] multiplies the flake alphas (at most [BackdropArt.SNOW_MAX_ALPHA]); see [BackdropArt.precipScale]. */
private fun DrawScope.drawSnow(tickNs: Long, scale: Float) {
    val t = tickNs / 1_000_000_000.0
    for (i in 0 until 48) {
        val speed = 0.03 + 0.05 * frac(i * 0.37f)
        val y = ((frac(i * 0.7548776f + 0.2f) + t * speed) % 1.0).toFloat()
        val sway = sin(t * 0.5 + i).toFloat() * size.width * 0.006f
        val x = size.width * frac(i * 0.6180339f + 0.05f) + sway
        val r = size.height * (0.004f + 0.007f * frac(i * 0.53f))
        drawCircle(Color.White.copy(alpha = (0.35f + 0.4f * frac(i * 0.29f)) * scale), r, Offset(x, size.height * y))
    }
}

/** A few static hills along the bottom of the backdrop. */
private fun hillPath(w: Float, h: Float): Path = Path().apply {
    moveTo(0f, h)
    lineTo(0f, h * 0.86f)
    cubicTo(w * 0.25f, h * 0.78f, w * 0.45f, h * 0.92f, w * 0.7f, h * 0.84f)
    cubicTo(w * 0.85f, h * 0.80f, w * 0.95f, h * 0.86f, w, h * 0.83f)
    lineTo(w, h)
    close()
}

/** [scale] multiplies the drop alphas (at most [BackdropArt.RAIN_MAX_ALPHA]); see [BackdropArt.precipScale]. */
private fun DrawScope.drawRain(tickNs: Long, scale: Float) {
    val t = tickNs / 1_000_000_000.0
    for (i in 0 until 70) {
        val speed = 0.6 + 0.5 * frac(i * 0.37f)
        val y = ((frac(i * 0.7548776f + 0.2f) + t * speed) % 1.0).toFloat()
        val x = size.width * frac(i * 0.6180339f + 0.05f) - y * size.width * 0.02f
        val len = size.height * (0.03f + 0.03f * frac(i * 0.53f))
        drawLine(
            Color(0xFF000000.toInt() or BackdropArt.RAIN).copy(alpha = (0.25f + 0.2f * frac(i * 0.29f)) * scale),
            Offset(x, size.height * y), Offset(x - len * 0.2f, size.height * y + len), strokeWidth = 2f,
        )
    }
}

private fun frac(x: Float): Float = x - kotlin.math.floor(x)
