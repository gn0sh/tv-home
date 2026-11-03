package tvhome.core.ui

import tvhome.core.agenda.AgendaDay
import tvhome.core.agenda.TimeLabel
import tvhome.core.state.AgendaState
import tvhome.core.state.StaleCause
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

/** Exact on-screen wording (docs/design/tv-home-m1-agenda.md, States). Sentence case, no jargon. */
object Copy {
    const val CANT_REACH_TITLE = "Can't reach calendar"
    const val CANT_REACH_DETAIL = "Check the TV's Wi-Fi. It will try again soon."
    const val NOT_SET_UP_TITLE = "Calendar not set up"
    const val NOT_SET_UP_DETAIL = "Set it up from your PC: see the setup steps in the README."
    /** Not in the design table: Loading is transient (no cache, first fetch in flight, at most the 15 s call timeout). */
    const val LOADING_TITLE = "Loading calendar"
    const val FOOTER_CANT_REACH = "Can't reach calendar. Showing the last copy."
    const val FOOTER_UNREADABLE = "Calendar data unreadable. Showing the last copy."
    const val NOTHING_SCHEDULED = "Nothing scheduled"
    const val NO_TITLE = "(No title)"
    const val ALL_DAY = "All day"
    const val CONTINUES = "Continues"
    const val TODAY = "Today"
    const val TOMORROW = "Tomorrow"

    /** Live-region text: stable while the freshness class is unchanged, so TalkBack speaks only on a change. */
    const val LIVE_FRESH = "Calendar up to date"
    const val LIVE_STALE = "Calendar out of date"
}

/** One agenda row. [time] is the left column ("9:00 AM", "All day"); [description] is the TalkBack sentence. */
data class RowModel(val time: String, val title: String, val description: String) {
    /** Titles are personal data: counts only. */
    override fun toString(): String = "RowModel(time=$time, title=${title.length} chars)"
}

/** [rows] empty means the body under the heading is [Copy.NOTHING_SCHEDULED]. */
data class DayModel(val heading: String, val date: String, val headingDescription: String, val rows: List<RowModel>) {
    override fun toString(): String = "DayModel($heading, $date, ${rows.size} rows)"
}

/** Header right side. [emphasised]: amber, bold, warning triangle. [liveDescription] is the live-region text. */
data class AgeLine(val text: String, val emphasised: Boolean, val liveDescription: String)

sealed interface ScreenModel {
    /** Centered title and optional detail; no list, no age line. */
    data class Message(val title: String, val detail: String?) : ScreenModel

    /**
     * The list screen. [initialFocus] indexes the flat row list (today's rows, then tomorrow's), or is null when
     * there is no row to focus (focus then stays on the screen root).
     */
    data class Agenda(
        val today: DayModel,
        val tomorrow: DayModel,
        val age: AgeLine,
        val footer: String?,
        val initialFocus: Int?,
    ) : ScreenModel
}

/** 12h ("9:00 AM", "12:05 PM") or 24h ("09:00"), following the TV's clock setting passed in as [is24h]. */
fun formatTime(t: LocalTime, is24h: Boolean): String {
    val m = t.minute.toString().padStart(2, '0')
    if (is24h) return t.hour.toString().padStart(2, '0') + ":" + m
    val h = if (t.hour % 12 == 0) 12 else t.hour % 12
    return "$h:$m " + if (t.hour < 12) "AM" else "PM"
}

/** "Sat 31 Oct" (fixed English, like the design; the device locale is not consulted). */
fun formatDate(d: LocalDate): String =
    d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.ENGLISH) + " " + d.dayOfMonth + " " +
        d.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)

/** "Saturday 31 October". */
fun formatDateLong(d: LocalDate): String =
    d.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH) + " " + d.dayOfMonth + " " +
        d.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH)

private fun rowOf(label: TimeLabel, title: String, is24h: Boolean, hideTitles: Boolean): RowModel {
    val time = when (label) {
        TimeLabel.AllDay -> Copy.ALL_DAY
        TimeLabel.Continues -> Copy.CONTINUES
        is TimeLabel.Starts -> formatTime(label.time, is24h)
    }
    val shown = QuietMode.title(title, hideTitles, max = Int.MAX_VALUE)
    return RowModel(time, shown, "$time, $shown")
}

private fun dayOf(heading: String, day: AgendaDay, is24h: Boolean, hideTitles: Boolean) = DayModel(
    heading = heading,
    date = formatDate(day.date),
    headingDescription = "$heading, ${formatDateLong(day.date)}",
    rows = day.items.map { rowOf(it.label, it.title, is24h, hideTitles) },
)

/**
 * Initial focus: the first of today's rows that starts at or after [nowLocal], else the first row. Index into the
 * flat row list (today, then tomorrow); null when there are no rows.
 */
fun initialFocusIndex(today: AgendaDay, tomorrow: AgendaDay, nowLocal: LocalTime): Int? {
    if (today.items.isEmpty() && tomorrow.items.isEmpty()) return null
    val next = today.items.indexOfFirst { val l = it.label; l is TimeLabel.Starts && !l.time.isBefore(nowLocal) }
    return if (next >= 0) next else 0
}

/**
 * The one place that decides which words and rows each [AgendaState] shows. The UI only draws the result.
 *
 * Stale: the age line is amber/bold only when [AgendaState.Stale.emphasised]; the footer appears only then, and only
 * for CANT_REACH and UNREADABLE. OVERDUE has no footer: it also covers "old cache, this launch's fetch not done
 * yet", where "Can't reach calendar" would be untrue. An offline launch is fed to `reduce()` as a failed OFFLINE
 * fetch by `AgendaSession`, so it is CANT_REACH. A non-emphasised Stale looks like Ok (design: a failure with
 * data under 60 min old is silent).
 *
 * [hideTitles] (Quiet mode, M7-7.2): every row title and its spoken description is "Busy"; times stay. Callers pass
 * `quiet != false`, so titles stay hidden while the stored Quiet value is still loading.
 */
fun screenModel(state: AgendaState, now: Instant, zone: ZoneId, is24h: Boolean, hideTitles: Boolean = false): ScreenModel = when (state) {
    AgendaState.NotSetUp -> ScreenModel.Message(Copy.NOT_SET_UP_TITLE, Copy.NOT_SET_UP_DETAIL)
    AgendaState.CantReach -> ScreenModel.Message(Copy.CANT_REACH_TITLE, Copy.CANT_REACH_DETAIL)
    AgendaState.Unreadable -> ScreenModel.Message(AgendaState.Unreadable.TITLE, AgendaState.Unreadable.DETAIL)
    AgendaState.Loading -> ScreenModel.Message(Copy.LOADING_TITLE, null)
    is AgendaState.Ok -> agenda(state.today, state.tomorrow, AgeLine(state.ageText, false, Copy.LIVE_FRESH), null, now, zone, is24h, hideTitles)
    is AgendaState.NothingScheduled ->
        agenda(state.today, state.tomorrow, AgeLine(state.ageText, false, Copy.LIVE_FRESH), null, now, zone, is24h, hideTitles)
    is AgendaState.Stale -> {
        val footer = if (!state.emphasised) null else when (state.cause) {
            StaleCause.CANT_REACH -> Copy.FOOTER_CANT_REACH
            StaleCause.UNREADABLE -> Copy.FOOTER_UNREADABLE
            StaleCause.OVERDUE -> null
        }
        val live = if (state.emphasised) Copy.LIVE_STALE else Copy.LIVE_FRESH
        agenda(state.today, state.tomorrow, AgeLine(state.ageText, state.emphasised, live), footer, now, zone, is24h, hideTitles)
    }
}

private fun agenda(
    today: AgendaDay,
    tomorrow: AgendaDay,
    age: AgeLine,
    footer: String?,
    now: Instant,
    zone: ZoneId,
    is24h: Boolean,
    hideTitles: Boolean,
) = ScreenModel.Agenda(
    today = dayOf(Copy.TODAY, today, is24h, hideTitles),
    tomorrow = dayOf(Copy.TOMORROW, tomorrow, is24h, hideTitles),
    age = age,
    footer = footer,
    initialFocus = initialFocusIndex(today, tomorrow, now.atZone(zone).toLocalTime()),
)
