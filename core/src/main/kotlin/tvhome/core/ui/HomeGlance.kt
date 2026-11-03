package tvhome.core.ui

import tvhome.core.feeds.FeedsSnapshot
import tvhome.core.feeds.GameDay
import tvhome.core.feeds.PanelPolicies
import tvhome.core.feeds.PanelSwitches
import tvhome.core.feeds.PanelView
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
    val card = contextCard(alerts, game, i.now, i.zone, i.is24h)
    val nn = nowNext(i.agenda, i.now, i.zone, i.travel, i.quiet, i.is24h)
    val brief = i.brief?.trim()?.ifEmpty { null }

    val candidates = buildSet {
        if (chip != null) add(GlanceItem.CHIP)
        when (card) {
            is ContextCard.Alert -> add(GlanceItem.ALERT_CARD)
            is ContextCard.Game -> add(GlanceItem.CARD)
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
    )
}
