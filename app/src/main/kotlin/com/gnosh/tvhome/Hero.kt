package com.gnosh.tvhome

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
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
import tvhome.core.feeds.WatchMap
import tvhome.core.feeds.WatchTarget
import tvhome.core.feeds.LiveFeeds
import tvhome.core.feeds.PanelPolicies
import tvhome.core.feeds.PanelView
import tvhome.core.feeds.view
import tvhome.core.feeds.PanelSwitches
import tvhome.core.feeds.Precip
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
import tvhome.core.ui.HeroZones
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
    briefColor: Int,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val now by rememberMinuteClock()
    val graph = remember(context) { (context.applicationContext as TvHomeApp).graph }
    // LiveFeeds is built (AppGraph's lazy) on Default after the first frame, never on the main thread (AC8). Feed work
    // then starts after a short settle, only while the activity is STARTED (ADR 0008 section 1: nothing polls while
    // Brian watches another app); returning restarts it, and LiveFeeds fetches only what is due. It stops when the hero
    // leaves the composition. Until it exists the hero draws an empty snapshot.
    var feeds by remember { mutableStateOf<LiveFeeds?>(null) }
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
    val settings by produceState(HeroSettings.NONE, now) {
        value = withContext(Dispatchers.IO) { runCatching { HeroSettings.read(context) }.getOrDefault(HeroSettings.NONE) }
    }
    val is24h = DateFormat.is24HourFormat(context)
    val zone = ZoneId.systemDefault()
    val instant = Instant.now()
    val inputs = remember(now, snap, agenda, settings, is24h, quiet) {
        // isInstalled reads a set built on IO (HeroSettings.read): no package manager call on the main thread.
        GlanceInputs(
            instant, zone, is24h, snap, settings.switches, settings.hasLocation, agenda, settings.travel,
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
    val briefWidthPx = with(density) { (LocalConfiguration.current.screenWidthDp - 2 * BRIEF_SIDE_DP).dp.toPx() }
    // Filled here, on main, in the same composition as quiet: pure and cheap (brief-quiet-flash).
    val brief = remember(briefSource, facts, quiet, briefWidthPx) {
        briefSource?.let { src ->
            briefLine(src.raw, facts, quiet) { t ->
                measurer.measure(t, BRIEF_STYLE, maxLines = 1, softWrap = false, density = density).size.width <= briefWidthPx
            }
        }
    }
    val glance = remember(inputs, brief) { homeGlance(inputs.copy(brief = brief)) }
    LaunchedEffect(glance.watch) { onWatch(glance.watch) }
    val weather = (view(snap.weather, PanelPolicies.WEATHER, settings.switches.weather, instant) as? PanelView.Shown)?.value
    val game = if (settings.switches.games) GameDay.select(snap.games?.value.orEmpty(), instant, zone) else null
    // Game day beats holiday beats time of day; weather never changes the theme (chip and overlay only).
    val resolved = HeroTheme.resolve(now.toLocalDate(), now.toLocalTime(), game)
    val dim = idle.value == IdleState.DIM
    val ink = HeroInk(
        text = if (dim) OledTokens.DIM_TEXT else resolved.text,
        secondary = if (dim) OledTokens.DIM_TEXT else resolved.secondaryText,
    )
    // Not focusable; the test tag lets the emulator QA read the idle state (scripts/ci/emulator-qa.sh).
    Box(modifier.fillMaxSize().testTag(HERO_IDLE_TAG_PREFIX + idle.value.name)) {
        HeroBackdrop(
            resolved, weather?.precipNow,
            scrimAlpha = { if (idle.value == IdleState.DIM) 1f - OledTokens.DIM_BACKDROP_BRIGHTNESS else GlanceTokens.SCRIM_ALPHA },
            modifier = Modifier.fillMaxWidth().fillMaxHeight(HERO_BACKDROP_FRACTION),
        )
        val drifting = Modifier.graphicsLayer {
            val o = drift.value
            translationX = o.dx.dp.toPx()
            translationY = o.dy.dp.toPx()
        }
        HeroTopStrip(glance, resolved, ink, dim, drifting)
        HeroNowNext(glance.nowNext, ink, drifting)
        HeroCard(glance.card, ink, drifting)
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

/** Height of the brief text in its row. */
private const val BRIEF_TEXT_DP = 32

/** The dock band's brief row (reserved by the home screen under the dock): the text plus the drift's vertical travel. */
val BRIEF_ROW_DP: Int = BRIEF_TEXT_DP + 2 * Drift.MAX_Y_DP.toInt()

/** Test tag of the brief line (uiautomator resource-id), read by the emulator QA check. */
const val DOCK_BRIEF_TAG = "dock_brief"

private const val FEEDS_START_DELAY_MS = 1500L

/** The text colours the hero draws with: the theme's, or the DIM grey. */
private class HeroInk(val text: Int, val secondary: Int)

/** The adb-set settings the hero reads ([FeedSettings]). */
private data class HeroSettings(
    val switches: PanelSwitches,
    val hasLocation: Boolean,
    val travel: List<TravelRule>,
    val watchMap: WatchMap = WatchMap.EMPTY,
    /** The watch map's packages that have a TV launch intent, checked once a minute on IO. */
    val installed: Set<String> = emptySet(),
) {
    companion object {
        val NONE = HeroSettings(PanelSwitches(), hasLocation = false, travel = emptyList())

        /** Disk and package manager: call off the main thread. */
        fun read(context: Context) = HeroSettings(
            FeedSettings.switches(context), FeedSettings.location(context) != null, FeedSettings.travelRules(context),
            FeedSettings.watchMap(context),
            FeedSettings.watchPackages(context).filterTo(HashSet()) { InstalledApps.canLaunch(context, it) },
        )
    }
}

/** Places a composable on one of the fixed [HeroZones] (dp at 1280x720). */
private fun Modifier.zone(z: HeroZones.Zone): Modifier = offset(z.x.dp, z.y.dp).size(z.w.dp, z.h.dp)

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
private fun HeroTopStrip(g: HomeGlance, theme: HeroTheme, ink: HeroInk, dim: Boolean, drifting: Modifier) {
    Row(
        Modifier.zone(HeroZones.TOP_STRIP).then(drifting),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.weight(1f, fill = false), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
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
                modifier = Modifier.padding(start = 24.dp),
            )
        }
    }
}

/** Hero left 60%: Now/Next line 1 (44 sp), line 2 (28 sp), the data-age line (22 sp). Nothing when hidden. */
@Composable
private fun HeroNowNext(nn: NowNext?, ink: HeroInk, drifting: Modifier) {
    nn ?: return
    Column(Modifier.zone(HeroZones.HERO_LEFT).then(drifting), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            nn.line1,
            color = rgb(ink.text),
            fontSize = GlanceTokens.HERO_SP.sp,
            lineHeight = (GlanceTokens.HERO_SP + 6).sp,
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
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
@Composable
private fun HeroCard(card: ContextCard?, ink: HeroInk, drifting: Modifier) {
    card ?: return
    Box(Modifier.zone(HeroZones.HERO_RIGHT).then(drifting), contentAlignment = Alignment.TopStart) {
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
                Modifier.semantics(mergeDescendants = true) { contentDescription = HERO_CARD_DESC_PREFIX + card.line },
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    when (card.phase) { GamePhase.LIVE -> GAME_LIVE; GamePhase.FINAL -> GAME_FINAL; GamePhase.UPCOMING -> GAME_TODAY },
                    color = rgb(ink.secondary), fontSize = GlanceTokens.DATE_SP.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                )
                Text(
                    card.line,
                    color = rgb(ink.text),
                    fontSize = GlanceTokens.CARD_SP.sp,
                    lineHeight = (GlanceTokens.CARD_SP + 6).sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
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
 * - The image is drawn with the theme's own gradient over it at [TINT_ALPHA] when the theme is not the one the image was
 *   rendered for (Thanksgiving and Valentine's share SUNSET, New Year and July 4 share STARS, every team shares GAME).
 * - The rain and snow particles are drawn on top of the image.
 * - A black scrim ([scrimAlpha], read in the draw phase) goes over everything but the bottom fade (M7, R3).
 * - Only Christmas animates (slow snowfall), plus the rain or snow overlay while it is falling now. The loop ticks about 15 times a second with a delay, not every vsync, and
 *   does not run at all when the system animator scale is 0 (snow-uiautomator-idle: QA sets that so uiautomator can
 *   reach an idle state; it also honours the user's "remove animations" choice).
 */
@Composable
private fun HeroBackdrop(theme: HeroTheme, precip: Precip?, scrimAlpha: () -> Float, modifier: Modifier) {
    val context = LocalContext.current
    val frozen = remember(context) { animationsFrozen(context) }
    var ready by remember { mutableStateOf(false) }
    val tick = remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        withFrameNanos { } // the first frame has been drawn with the plain gradient
        ready = true
    }
    var backdrop by remember { mutableStateOf<Backdrop?>(null) }
    val imageAlpha = remember { Animatable(0f) }
    LaunchedEffect(ready, theme.motif) {
        if (!ready) return@LaunchedEffect
        if (backdrop?.motif == theme.motif) { imageAlpha.snapTo(1f); return@LaunchedEffect } // back to the shown image
        // Decode first, so the current image stays up until the new one is ready (backdrop-alpha-stuck).
        val image = withContext(Dispatchers.IO) { decodeBackdrop(context, theme.motif) } ?: return@LaunchedEffect
        imageAlpha.snapTo(0f)
        backdrop = Backdrop(theme.motif, image)
        if (frozen) imageAlpha.snapTo(1f) else imageAlpha.animateTo(1f, tween(CROSSFADE_MS))
    }
    val animate = ready && !frozen && (theme.motif == HeroMotif.SNOW || precip != null)
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
                val fade = Brush.verticalGradient(listOf(Color.Transparent, rgb(Tokens.BACKGROUND)), startY = h * 0.6f, endY = h)
                val hill = if (theme.motif == HeroMotif.SNOW) hillPath(w, h) else null
                val moon = if (theme.motif == HeroMotif.STARS) moonPath(w, h) else null
                val tint = if (theme.backdropTop != renderedTop(theme.motif)) base else null
                onDrawBehind {
                    drawRect(base)
                    // Read in the draw phase only: a decode or a crossfade step redraws this layer, nothing else.
                    val image = backdrop?.takeIf { it.motif == theme.motif }?.image
                    val alpha = if (image == null) 0f else imageAlpha.value
                    if (ready && alpha < 1f) {
                        when (theme.motif) {
                            HeroMotif.MOON_BATS -> drawMoonAndBats(theme)
                            HeroMotif.SNOW -> { if (image == null) drawSnow(tick.longValue); hill?.let { drawPath(it, Color.White.copy(alpha = 0.08f)) } }
                            HeroMotif.DAWN -> drawSun(theme, 0.74f, 0.98f, 0.11f, 0.30f)
                            HeroMotif.DAY -> { drawSun(theme, 0.80f, 0.34f, 0.07f, 0.22f); drawClouds(0.22f) }
                            HeroMotif.SUNSET -> drawSun(theme, 0.74f, 0.88f, 0.14f, 0.34f)
                            HeroMotif.STARS -> drawStars(theme, moon)
                            HeroMotif.GAME -> drawSun(theme, 0.80f, 0.36f, 0.08f, 0.22f)
                        }
                    }
                    if (image != null && alpha > 0f) {
                        drawBackdrop(image, alpha)
                        tint?.let { drawRect(it, alpha = TINT_ALPHA * alpha) }
                        // Christmas snowfall keeps falling over the rendered scene.
                        if (theme.motif == HeroMotif.SNOW) drawSnow(tick.longValue)
                    }
                    if (ready) {
                        // Weather motif overlay (never a theme change): particles only when it is falling now.
                        when (precip) {
                            Precip.SNOW -> if (theme.motif != HeroMotif.SNOW) drawSnow(tick.longValue)
                            Precip.RAIN -> drawRain(tick.longValue)
                            null -> Unit
                        }
                    }
                    // R3: the scrim caps the brightest backdrop pixel at 25% (15% in DIM), so hero text keeps 7:1 over
                    // any art. Under the fade, so the backdrop still meets the screen background without a seam.
                    drawRect(Color.Black, alpha = scrimAlpha())
                    drawRect(fade)
                }
            },
    )
}

private class Backdrop(val motif: HeroMotif, val image: ImageBitmap)

private const val CROSSFADE_MS = 700

/** How much of the theme's gradient goes over an image rendered for another theme's palette. */
private const val TINT_ALPHA = 0.38f

private fun backdropRes(motif: HeroMotif): Int = when (motif) {
    HeroMotif.DAWN -> R.drawable.hero_dawn
    HeroMotif.DAY -> R.drawable.hero_day
    HeroMotif.SUNSET -> R.drawable.hero_sunset
    HeroMotif.STARS -> R.drawable.hero_stars
    HeroMotif.MOON_BATS -> R.drawable.hero_moon_bats
    HeroMotif.SNOW -> R.drawable.hero_snow
    HeroMotif.GAME -> R.drawable.hero_game
}

/** The backdropTop of the theme each image was rendered for (MOTIFS in tools/art/render_backdrops.py); GAME has none. */
private fun renderedTop(motif: HeroMotif): Int? = when (motif) {
    HeroMotif.DAWN -> 0x1F3B66
    HeroMotif.DAY -> 0x1B4A86
    HeroMotif.SUNSET -> 0x2E2250
    HeroMotif.STARS -> 0x04060B
    HeroMotif.MOON_BATS -> 0x120A1E
    HeroMotif.SNOW -> 0x0A2418
    HeroMotif.GAME -> null
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

private fun DrawScope.drawSnow(tickNs: Long) {
    val t = tickNs / 1_000_000_000.0
    for (i in 0 until 48) {
        val speed = 0.03 + 0.05 * frac(i * 0.37f)
        val y = ((frac(i * 0.7548776f + 0.2f) + t * speed) % 1.0).toFloat()
        val sway = sin(t * 0.5 + i).toFloat() * size.width * 0.006f
        val x = size.width * frac(i * 0.6180339f + 0.05f) + sway
        val r = size.height * (0.004f + 0.007f * frac(i * 0.53f))
        drawCircle(Color.White.copy(alpha = 0.35f + 0.4f * frac(i * 0.29f)), r, Offset(x, size.height * y))
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

private fun DrawScope.drawRain(tickNs: Long) {
    val t = tickNs / 1_000_000_000.0
    for (i in 0 until 70) {
        val speed = 0.6 + 0.5 * frac(i * 0.37f)
        val y = ((frac(i * 0.7548776f + 0.2f) + t * speed) % 1.0).toFloat()
        val x = size.width * frac(i * 0.6180339f + 0.05f) - y * size.width * 0.02f
        val len = size.height * (0.03f + 0.03f * frac(i * 0.53f))
        drawLine(
            Color(0xFFAFC8F0).copy(alpha = 0.25f + 0.2f * frac(i * 0.29f)),
            Offset(x, size.height * y), Offset(x - len * 0.2f, size.height * y + len), strokeWidth = 2f,
        )
    }
}

private fun frac(x: Float): Float = x - kotlin.math.floor(x)
