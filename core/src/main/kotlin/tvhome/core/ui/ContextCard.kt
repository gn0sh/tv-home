package tvhome.core.ui

import tvhome.core.feeds.AlertSet
import tvhome.core.feeds.GameDay
import tvhome.core.feeds.TeamGame
import java.time.Instant
import java.time.ZoneId

/** Hero right 40%: one glance item, whatever it shows. */
sealed interface ContextCard {
    /** A severe NWS alert (40 sp): "Tornado Warning" and "until 7:45 PM". */
    data class Alert(val event: String, val until: String?) : ContextCard {
        val text: String get() = if (until == null) event else "$event $until"
    }

    /** Today's game for a followed team, as the top bar showed it in M2f. */
    data class Game(val line: String, val teamKey: String) : ContextCard
}

/**
 * Milestone 1 priority: an OVERRIDE alert, else today's game line. M2 (task B9) adds live > upcoming > time of day.
 * [alerts] must already be the set that applies now (see [activeAlerts]).
 */
fun contextCard(alerts: AlertSet?, game: TeamGame?, now: Instant, zone: ZoneId, is24h: Boolean): ContextCard? {
    alerts?.override?.let { a -> return ContextCard.Alert(a.event, a.until?.let { untilText(it, now, zone, is24h) }) }
    game ?: return null
    return ContextCard.Game(GameDay.line(game, now, zone), game.team.key)
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
