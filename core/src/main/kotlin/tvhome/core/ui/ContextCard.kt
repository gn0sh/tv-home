package tvhome.core.ui

import tvhome.core.agenda.AgendaDay
import tvhome.core.agenda.TimeLabel
import tvhome.core.feeds.AlertSet
import tvhome.core.feeds.GameDay
import tvhome.core.feeds.GamePhase
import tvhome.core.feeds.PanelPolicies
import tvhome.core.feeds.PanelView
import tvhome.core.feeds.Stamped
import tvhome.core.feeds.TeamGame
import tvhome.core.feeds.WatchMap
import tvhome.core.feeds.WatchTarget
import tvhome.core.feeds.WeatherNow
import tvhome.core.feeds.view
import tvhome.core.state.AgendaState
import java.time.Instant
import java.time.ZoneId

/** Hero right 40%: one glance item, whatever it shows. */
sealed interface ContextCard {
    /** A severe NWS alert (40 sp): "Tornado Warning" and "until 7:45 PM". */
    data class Alert(val event: String, val until: String?) : ContextCard {
        val text: String get() = if (until == null) event else "$event $until"
    }

    /**
     * Today's game for a followed team. [line] is the whole sentence the card draws (opponent, start or score, and the
     * carrying [network] when one is mapped and the game is not over). [phase] lets the card tell live from upcoming.
     */
    data class Game(
        val line: String, val teamKey: String, val phase: GamePhase = GamePhase.UPCOMING, val network: String? = null,
        /** Set for a game that is not today ([NEXT_GAME]); null lets the card name the phase (Live, Today, Final). */
        val heading: String? = null,
    ) : ContextCard {
        companion object { const val NEXT_GAME = "Next game" }
    }

    /**
     * The quiet-hours card (spec M7-4.5): in the morning today's weather arc, in the evening tomorrow's first event and
     * the overnight low. [heading] is "Today" or "Tomorrow"; [detail] is an optional second line. Event titles in
     * [text] are "Busy" in Quiet mode.
     */
    data class TimeOfDay(val heading: String, val text: String, val detail: String? = null) : ContextCard {
        /** Titles are personal data: counts only. */
        override fun toString(): String = "TimeOfDay($heading, ${text.length} chars, ${detail?.length} chars)"
    }
}

/**
 * The context card, by priority (spec M7-4.5, tested as a table): a severe alert, then a live game, then an upcoming game
 * today, then (an ended game's final, until local midnight, the M2f rule), then the [next] game of either team on a
 * later day (Brian 2026-11-03: the home screen always carries it), then the [timeOfDay] card. [alerts] must
 * already be the set that applies now (see [activeAlerts]); [game] is [GameDay.select]'s pick, which already ranks live
 * over upcoming over final. The game line names [watch]'s network when a Watch target exists, else [watchMap]'s.
 */
fun contextCard(
    alerts: AlertSet?,
    game: TeamGame?,
    now: Instant,
    zone: ZoneId,
    is24h: Boolean,
    timeOfDay: ContextCard.TimeOfDay? = null,
    watchMap: WatchMap = WatchMap.EMPTY,
    watch: WatchTarget? = null,
    /** The next game of either team when none is today ([GameDay.next]); shown above the time-of-day card. */
    next: TeamGame? = null,
): ContextCard? {
    alerts?.override?.let { a -> return ContextCard.Alert(a.event, a.until?.let { untilText(it, now, zone, is24h) }) }
    if (game != null) {
        // The pill's network when there is a pill, so the card and the pill always name the same one; else what the
        // feed lists ("TV: not announced yet" when it lists none: where it is on is the question).
        val network = if (game.game.phase == GamePhase.FINAL) null else watch?.network ?: watchMap.network(game)
        val tv = when {
            game.game.phase == GamePhase.FINAL -> null
            network != null -> network
            else -> GameDay.tvText(game.game)
        }
        val line = GameDay.line(game, now, zone).let { if (tv != null) "$it · $tv" else it }
        return ContextCard.Game(line, game.team.key, game.game.phase, network)
    }
    if (next != null) {
        return ContextCard.Game(GameDay.nextLine(next, now, zone), next.team.key, next.game.phase, null, ContextCard.Game.NEXT_GAME)
    }
    return timeOfDay
}

private const val MORNING_FROM = 5
private const val MORNING_TO = 12
private const val EVENING_FROM = 17

/**
 * Morning (05:00 to 11:59 local): "Today", the high with its hour, then the low and rain. Evening (17:00 on):
 * "Tomorrow", the first event with its time (titles "Busy" when [quiet]; left out when the calendar is 3 h stale or
 * unavailable) and tonight's low. Other hours, or nothing to say, give null. [weather] must already be through [view].
 */
fun timeOfDayCard(weather: WeatherNow?, agenda: AgendaState, now: Instant, zone: ZoneId, quiet: Boolean, is24h: Boolean): ContextCard.TimeOfDay? {
    val local = now.atZone(zone)
    val hour = local.hour
    fun temp(t: Int) = "$t°"
    return when {
        hour in MORNING_FROM until MORNING_TO -> {
            val w = weather ?: return null
            val high = w.highF ?: return null
            val at = w.highHour?.let { " around " + formatTime(java.time.LocalTime.of(it, 0), is24h) }.orEmpty()
            val rain = w.nextPrecip ?: w.precipNow?.let { "${it.word} now" }
            val detail = listOfNotNull(w.lowF?.let { "Low ${temp(it)}" }, rain).joinToString(" · ").ifEmpty { null }
            ContextCard.TimeOfDay("Today", "High ${temp(high)}$at", detail)
        }
        hour >= EVENING_FROM -> {
            val event = tomorrowFirst(agenda, now, zone, quiet, is24h)
            val low = weather?.overnightLowF?.let { "Low tonight ${temp(it)}" }
            val lines = listOfNotNull(event, low)
            if (lines.isEmpty()) null else ContextCard.TimeOfDay("Tomorrow", lines[0], lines.getOrNull(1))
        }
        else -> null
    }
}

/** "Tomorrow: Dentist at 9:00 AM", "Nothing on tomorrow", or null when the calendar cannot be trusted. */
private fun tomorrowFirst(state: AgendaState, now: Instant, zone: ZoneId, quiet: Boolean, is24h: Boolean): String? {
    val (days, updatedAt) = when (state) {
        is AgendaState.Ok -> listOf(state.today, state.tomorrow) to state.updatedAt
        is AgendaState.NothingScheduled -> listOf(state.today, state.tomorrow) to state.updatedAt
        is AgendaState.Stale -> listOf(state.today, state.tomorrow) to state.updatedAt
        is AgendaState.Unavailable -> return null
    }
    if (view(Stamped(Unit, updatedAt), PanelPolicies.CALENDAR, true, now) !is PanelView.Shown) return null
    val tomorrowDate = now.atZone(zone).toLocalDate().plusDays(1)
    val day: AgendaDay = days.firstOrNull { it.date == tomorrowDate } ?: return null
    val timed = day.items.firstOrNull { it.label is TimeLabel.Starts && it.startsAt != null }
    if (timed != null) {
        val time = formatTime(timed.startsAt!!.atZone(zone).toLocalTime().withSecond(0).withNano(0), is24h)
        return NowNextCopy.tomorrow(QuietMode.title(timed.title, quiet), time)
    }
    val allDay = day.items.firstOrNull { it.label == TimeLabel.AllDay } ?: return NowNextCopy.NOTHING_TOMORROW
    return NowNextCopy.tomorrow(QuietMode.title(allDay.title, quiet), null)
}

/** "until 7:45 PM", or "until Tue 7:45 AM" when it ends on another day. */
internal fun untilText(at: Instant, now: Instant, zone: ZoneId, is24h: Boolean): String {
    val local = at.atZone(zone)
    val time = formatTime(local.toLocalTime().withSecond(0).withNano(0), is24h)
    return if (local.toLocalDate() == now.atZone(zone).toLocalDate()) "until $time"
    else "until " + formatDate(local.toLocalDate()).substringBefore(' ') + " " + time
}

/** Drops alerts that have ended since the last fetch (spec M7-2.5: they disappear on their own). */
fun activeAlerts(s: AlertSet, now: Instant): AlertSet = AlertSet(s.alerts.filter { it.until == null || now.isBefore(it.until) })
