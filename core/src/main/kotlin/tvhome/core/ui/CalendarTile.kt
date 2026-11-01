package tvhome.core.ui

import tvhome.core.agenda.AgendaDay
import tvhome.core.agenda.TimeLabel
import tvhome.core.state.AgendaState
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/**
 * The face of the Calendar tile, the first tile of the home screen row (M2b). OK on it opens the full-screen agenda.
 *
 * [date] is today's date in the TV's zone ("Sun 1 Nov"), shown large. Below it either the next event ([time] set,
 * [text] its title) or a short status in [text] (no more events, or the ScreenModel message title). [warn]: draw
 * [text] in amber (can't reach, unreadable). [note]: the age line of an emphasised stale copy, amber. [description]
 * is the one TalkBack sentence for the whole tile.
 */
data class CalendarTile(
    val date: String,
    val time: String?,
    val text: String,
    val warn: Boolean,
    val note: String?,
    val description: String,
) {
    /** Titles are personal data: counts only. */
    override fun toString(): String = "CalendarTile($date, time=$time, text=${text.length} chars, warn=$warn, note=$note)"
}

const val CALENDAR_TILE_LABEL = "Calendar"
const val NO_MORE_TODAY = "No more events today"

/**
 * The next event today: the first item that starts at or after [now] (to the minute shown on screen, so a 9:00
 * event is "next" until 9:01). All-day and continuing items are not "next"; they show in the full-screen agenda.
 * Items come sorted from `buildAgenda`, so the first match is the earliest.
 */
fun nextEvent(day: AgendaDay, now: Instant, zone: ZoneId): Pair<LocalTime, String>? {
    val local = now.atZone(zone)
    if (day.date != local.toLocalDate()) return null
    val minute = local.toLocalTime().withSecond(0).withNano(0)
    for (it in day.items) {
        val l = it.label
        if (l is TimeLabel.Starts && !l.time.isBefore(minute)) return l.time to it.title
    }
    return null
}

/** The one place that decides what the Calendar tile shows for each [AgendaState]. */
fun calendarTile(state: AgendaState, now: Instant, zone: ZoneId, is24h: Boolean): CalendarTile {
    val today = now.atZone(zone).toLocalDate()
    val date = formatDate(today)
    val spoken = "$CALENDAR_TILE_LABEL, ${formatDateLong(today)}"
    fun status(text: String, warn: Boolean) = CalendarTile(date, null, text, warn, null, "$spoken. $text.")
    fun events(day: AgendaDay, tomorrow: AgendaDay, note: String?): CalendarTile {
        // The cached "today" is today in practice (reduce() checks the window); after midnight, before the next
        // refresh, the cached "tomorrow" is today.
        val d = if (day.date == today) day else tomorrow
        val next = nextEvent(d, now, zone)
        val noteSpoken = note?.let { " $it." }.orEmpty()
        if (next == null) return CalendarTile(date, null, NO_MORE_TODAY, false, note, "$spoken. $NO_MORE_TODAY.$noteSpoken")
        val time = formatTime(next.first, is24h)
        val title = next.second.trim().ifEmpty { Copy.NO_TITLE }
        return CalendarTile(date, time, title, false, note, "$spoken. Next: $time, $title.$noteSpoken")
    }
    return when (state) {
        AgendaState.NotSetUp -> status(Copy.NOT_SET_UP_TITLE, false)
        AgendaState.Loading -> status(Copy.LOADING_TITLE, false)
        AgendaState.CantReach -> status(Copy.CANT_REACH_TITLE, true)
        AgendaState.Unreadable -> status(AgendaState.Unreadable.TITLE, true)
        is AgendaState.Ok -> events(state.today, state.tomorrow, null)
        is AgendaState.NothingScheduled -> events(state.today, state.tomorrow, null)
        // Same rule as screenModel(): a non-emphasised Stale looks like Ok.
        is AgendaState.Stale -> events(state.today, state.tomorrow, if (state.emphasised) state.ageText else null)
    }
}
