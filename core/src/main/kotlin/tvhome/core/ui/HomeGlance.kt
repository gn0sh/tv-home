package tvhome.core.ui

import tvhome.core.feeds.FeedsSnapshot
import tvhome.core.feeds.GameDay
import tvhome.core.feeds.GamePhase
import tvhome.core.feeds.PanelPolicies
import tvhome.core.feeds.PanelSwitches
import tvhome.core.feeds.PanelView
import tvhome.core.feeds.WatchMap
import tvhome.core.feeds.WatchTarget
import tvhome.core.feeds.view
import tvhome.core.state.AgendaState
import java.time.Instant
import java.time.ZoneId

/** Glance items in priority order (highest first). ADR 0008 section 2. */
enum class GlanceItem { CLOCK, ALERT_CARD, NOW_1, CHIP, CARD, NOW_2, BRIEF }

/** R2: at most [MAX] glance items. A candidate past the budget displaces the lowest priority, never stacks. */
object GlanceBudget {
    const val MAX = 6

    fun fit(candidates: Set<GlanceItem>): Set<GlanceItem> =
        (candidates + GlanceItem.CLOCK).sortedBy { it.ordinal }.take(MAX).toSet()
}

/** Hero zones in dp at 1280x720 (ADR 0008 section 3). ux may move the numbers; the structure is fixed. */
object HeroZones {
    data class Zone(val x: Int, val y: Int, val w: Int, val h: Int) {
        val right get() = x + w
        val bottom get() = y + h
        fun overlaps(o: Zone) = x < o.right && o.x < right && y < o.bottom && o.y < bottom
    }

    const val SCREEN_W = 1280
    const val SCREEN_H = 720
    val TOP_STRIP = Zone(64, 36, 1152, 64)
    val HERO_LEFT = Zone(64, 112, 680, 270)
    val HERO_RIGHT = Zone(768, 112, 448, 270)
    val ALL = listOf(TOP_STRIP, HERO_LEFT, HERO_RIGHT)

    /** Overscan safe area: 5% of the design canvas on every side (64 x 36 dp at 1280x720). */
    const val SAFE_X = SCREEN_W / 20
    const val SAFE_Y = SCREEN_H / 20

    /** Space kept between the hero text and the top of the apps row (its focus-scale room is inside the row). */
    const val APPS_GAP = 8

    /**
     * How many lines Now/Next line 1 may take in a left zone [zoneH] dp tall at [fontScale] (board-fontscale): 2 when two
     * lines of it (44 sp on a 50 sp line), line 2 (28 sp, ~34) and the age line (22 sp, ~28) with their 8 dp gaps fit,
     * else 1 (the zone clips anything that still does not fit).
     */
    fun nowNextLine1Max(zoneH: Int, fontScale: Float): Int {
        val k = fontScale.coerceAtLeast(1f)
        val need = 2 * 50 * k + 34 * k + 28 * k + 16
        return if (zoneH >= need) 2 else 1
    }

    /** The three zones for one frame. */
    data class Layout(val top: Zone, val left: Zone, val right: Zone) {
        val all get() = listOf(top, left, right)
    }

    /**
     * The zones when the apps row starts at [appsTop] (dp on the design canvas; null before it is measured): the hero
     * text zones end [APPS_GAP] above it, so no hero line is ever drawn behind a tile (emulator tour, 2026-11-03). Never
     * taller than the design zones, never negative, always inside the safe area.
     */
    fun layout(appsTop: Int?): Layout {
        val limit = minOf(appsTop ?: SCREEN_H, SCREEN_H - SAFE_Y) - APPS_GAP
        fun fit(z: Zone) = z.copy(h = (limit - z.y).coerceIn(0, z.h))
        return Layout(TOP_STRIP, fit(HERO_LEFT), fit(HERO_RIGHT))
    }
}

/**
 * The home screen's zone map on the design canvas (1280 x 720 dp), top to bottom, each in its own box with no overlap
 * (Brian 2026-11-03: "a lot of stuff layered on top of each other"). Inside the 5% safe area ([HeroZones.SAFE_X],
 * [HeroZones.SAFE_Y]): the top strip, the hero band (Now/Next left, context card right), the apps row band, the dock
 * band (dashboard tiles and pills in one row), and a reserved full-width row for the daily brief line (m7-brief), so
 * nothing moves when the line comes and goes. Bands follow the theme's tile height (focus-scale room included).
 */
object HomeLayout {
    /** The brief line's reserved row under the dock (PR #24 uses 40 dp: 32 dp text plus the drift's travel). */
    const val BRIEF_ROW_DP = 40

    /** The hero text zones are never shorter than this at any allowed tile size (two 44 sp lines, line 2, the age). */
    const val HERO_MIN_DP = 150

    // The dock row (app HomeScreen uses these): two dashboard tiles, then the pills at their widest.
    const val TILE_GAP_DP = 28
    const val PILL_GAP_DP = 16
    /** "✦ Ask Claude" needs about 160 dp; 210 leaves the Watch pill room (watch-pill-espn-fallback). */
    const val ASK_PILL_DP = 210

    /**
     * Wide enough for "Watch on" plus a short app name at 24 sp (ESPN, Max, CDE, Hulu, Peacock: about 200 dp of text in
     * 260 less 2 x 24 dp padding); the dock still fits at the widest tiles ([dockWidth], HeroLayoutTest).
     */
    const val WATCH_PILL_MAX_DP = 260
    const val QUIET_PILL_MAX_DP = 170
    const val DOCK_TILES = 2

    /** Horizontal padding of a tile row (room for the 1.1x focus scale): a sixteenth of the tile width each side. */
    fun rowPadH(tileWidthDp: Int): Int = tileWidthDp / 16

    /** The dock row's widest content: tiles, the gaps between all children, and every pill at its maximum. */
    fun dockWidth(tileWidthDp: Int): Int =
        2 * rowPadH(tileWidthDp) + DOCK_TILES * tileWidthDp + DOCK_TILES * TILE_GAP_DP +
            ASK_PILL_DP + WATCH_PILL_MAX_DP + QUIET_PILL_MAX_DP + 2 * PILL_GAP_DP

    /** One tile row band: vertical padding for the 1.1x focus scale, the tile, and its label (12 dp gap + one line). */
    fun rowBand(tileHeightDp: Int): Int = 2 * (tileHeightDp / 13) + tileHeightDp + TILE_LABEL_DP
    const val TILE_LABEL_DP = 44

    data class Map(
        val topStrip: HeroZones.Zone,
        val heroLeft: HeroZones.Zone,
        val heroRight: HeroZones.Zone,
        val apps: HeroZones.Zone,
        val dock: HeroZones.Zone,
        val brief: HeroZones.Zone,
    ) {
        val all get() = listOf(topStrip, heroLeft, heroRight, apps, dock, brief)
    }

    fun map(tileHeightDp: Int): Map {
        val w = HeroZones.SCREEN_W - 2 * HeroZones.SAFE_X
        val bottom = HeroZones.SCREEN_H - HeroZones.SAFE_Y
        val band = rowBand(tileHeightDp)
        val brief = HeroZones.Zone(HeroZones.SAFE_X, bottom - BRIEF_ROW_DP, w, BRIEF_ROW_DP)
        val dock = HeroZones.Zone(HeroZones.SAFE_X, brief.y - band, w, band)
        val apps = HeroZones.Zone(HeroZones.SAFE_X, dock.y - band, w, band)
        val hero = HeroZones.layout(apps.y)
        return Map(hero.top, hero.left, hero.right, apps, dock, brief)
    }
}

/**
 * The design canvas (1280 x 720 dp): the app draws on it at any screen size by scaling dp, so a 1080p screen at 2.0
 * density (960 x 540 dp, the TV emulator) lays out exactly like one at 1.5 (emulator tour 2026-11-03: the hero card ran
 * off the right edge at 2.0). Physical sizes stay what the design asked for: 1 sp is 1.5 px at 1080p either way.
 */
object DesignCanvas {
    const val WIDTH_DP = HeroZones.SCREEN_W
    const val HEIGHT_DP = HeroZones.SCREEN_H

    /** Pixels per design dp for a screen of [widthPx] x [heightPx]: the canvas fits inside, never cropped. */
    fun density(widthPx: Int, heightPx: Int): Float {
        if (widthPx <= 0 || heightPx <= 0) return 1f
        return minOf(widthPx.toFloat() / WIDTH_DP, heightPx.toFloat() / HEIGHT_DP)
    }
}

object GlanceCopy {
    const val ALERTS_UNKNOWN = "alerts unknown"
}

/** Everything [homeGlance] reads. The brief is M2 (null here shows nothing). */
data class GlanceInputs(
    val now: Instant,
    val zone: ZoneId,
    val is24h: Boolean,
    val feeds: FeedsSnapshot,
    val switches: PanelSwitches,
    val hasLocation: Boolean,
    val agenda: AgendaState,
    val travel: List<TravelRule> = emptyList(),
    val brief: String? = null,
    val quiet: Boolean = false,
    /** Network name to app, and whether an app is installed (both read on the caller's thread; keep them cheap). */
    val watchMap: WatchMap = WatchMap.EMPTY,
    val isInstalled: (String) -> Boolean = { false },
)

/**
 * The only text the hero, the top strip and the brief line draw. Null fields are not drawn. [items] is what counts
 * against [GlanceBudget]; age lines, the Quiet marker and the chip's alert suffix belong to their item.
 */
data class HomeGlance(
    val clock: String,
    val date: String,
    val chip: String?,
    val nowNext: NowNext?,
    val card: ContextCard?,
    val brief: String?,
    val quiet: Boolean,
    val items: Set<GlanceItem>,
    /** The Watch pill (dock band): today's live or upcoming game, an app mapped for it, and that app installed. Else null. */
    val watch: WatchTarget? = null,
) {
    override fun toString(): String = "HomeGlance($clock, $date, chip=$chip, $nowNext, card=${card?.javaClass?.simpleName}, items=$items)"
}

fun homeGlance(i: GlanceInputs): HomeGlance {
    val local = i.now.atZone(i.zone)
    val clock = formatTime(local.toLocalTime().withSecond(0).withNano(0), i.is24h)
    val date = formatDate(local.toLocalDate())

    val weather = (view(i.feeds.weather, PanelPolicies.WEATHER, i.switches.weather, i.now) as? PanelView.Shown)?.value
    val alertsOn = i.switches.alerts && i.hasLocation
    val alertsView = view(i.feeds.alerts, PanelPolicies.ALERTS, alertsOn, i.now)
    val alerts = (alertsView as? PanelView.Shown)?.value?.let { activeAlerts(it, i.now) }
    val suffix = when {
        alerts != null -> alerts.suffixText()
        alertsView == PanelView.NoData && i.feeds.alertsChecked -> GlanceCopy.ALERTS_UNKNOWN
        else -> null
    }
    val chip = listOfNotNull(weather?.chip, suffix).joinToString(" · ").ifEmpty { null }

    val game = if (i.switches.games) GameDay.select(i.feeds.games?.value.orEmpty(), i.now, i.zone) else null
    val timeOfDay = timeOfDayCard(weather, i.agenda, i.now, i.zone, i.quiet, i.is24h)
    val watch = game?.takeIf { it.game.phase != GamePhase.FINAL }?.let { i.watchMap.watch(it, i.isInstalled) }
    val next = if (i.switches.games && game == null) GameDay.next(i.feeds.games?.value.orEmpty(), null, i.now) else null
    val card = contextCard(alerts, game, i.now, i.zone, i.is24h, timeOfDay, i.watchMap, watch, next)
    val nn = nowNext(i.agenda, i.now, i.zone, i.travel, i.quiet, i.is24h)
    val brief = i.brief?.trim()?.ifEmpty { null }

    val candidates = buildSet {
        if (chip != null) add(GlanceItem.CHIP)
        when (card) {
            is ContextCard.Alert -> add(GlanceItem.ALERT_CARD)
            is ContextCard.Game, is ContextCard.TimeOfDay -> add(GlanceItem.CARD)
            null -> Unit
        }
        if (nn != null) add(GlanceItem.NOW_1)
        if (nn?.line2 != null) add(GlanceItem.NOW_2)
        if (brief != null) add(GlanceItem.BRIEF)
    }
    val kept = GlanceBudget.fit(candidates)
    return HomeGlance(
        clock = clock,
        date = date,
        chip = chip.takeIf { GlanceItem.CHIP in kept },
        nowNext = nn?.takeIf { GlanceItem.NOW_1 in kept }?.let { if (GlanceItem.NOW_2 in kept) it else it.copy(line2 = null) },
        card = card.takeIf { GlanceItem.ALERT_CARD in kept || GlanceItem.CARD in kept },
        brief = brief.takeIf { GlanceItem.BRIEF in kept },
        quiet = i.quiet,
        items = kept,
        watch = watch,
    )
}
