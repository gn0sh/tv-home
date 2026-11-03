package tvhome.core.ui

import tvhome.core.agenda.AgendaDay
import tvhome.core.agenda.AgendaItem
import tvhome.core.agenda.TimeLabel
import tvhome.core.agenda.titlesMatch
import tvhome.core.feeds.PanelPolicies
import tvhome.core.feeds.PanelView
import tvhome.core.feeds.Stamped
import tvhome.core.feeds.view
import tvhome.core.state.AgendaState
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/**
 * "Leave [minutes] before an event whose title contains [keyword]" (case-insensitive). Set over adb; none by default.
 * Build with [of], which applies the limits (architecture 11.3).
 */
data class TravelRule(val keyword: String, val minutes: Int) {
    override fun toString(): String = "TravelRule(${keyword.length} chars, $minutes min)"

    companion object {
        const val MAX_RULES = 10
        const val MAX_KEYWORD = 40
        val MINUTES = 1..180

        fun of(keyword: String?, minutes: Int?): TravelRule? {
            val k = keyword?.trim() ?: return null
            if (k.isEmpty() || k.length > MAX_KEYWORD || k.any { it.isISOControl() }) return null
            if (minutes == null || minutes !in MINUTES) return null
            return TravelRule(k, minutes)
        }
    }
}

/** Hero left zone: line 1 (44 sp), line 2 (28 sp), age line (22 sp). Titles are personal data: counts only. */
data class NowNext(val line1: String, val line2: String?, val ageText: String?) {
    override fun toString(): String = "NowNext(${line1.length} chars, ${line2?.length} chars, $ageText)"
}

/** Wording in one place, so ux can change it without touching the rules. */
object NowNextCopy {
    const val NOTHING_TODAY = "Nothing on today"
    const val NOTHING_TOMORROW = "Nothing on tomorrow"
    const val BUSY = QuietMode.BUSY
    const val STARTING_NOW = "starting now"
    const val LEAVE_NOW = "Leave now"
    const val MAX_TITLE = 60

    fun inMinutes(m: Long) = "in $m min"
    fun at(time: String) = "at $time"
    fun nowUntil(time: String) = "now, until $time"
    fun leaveBy(time: String) = "Leave by $time"
    fun today(title: String) = "Today: $title"
    fun tomorrow(title: String, time: String?) = if (time == null) "Tomorrow: $title" else "Tomorrow: $title at $time"
    fun then(title: String, time: String) = "Then $title at $time"
    fun notUpdatedSince(time: String) = "Calendar not updated since $time"
}

/** The same appointment twice, by the merge step's own rule ([titlesMatch]; these are timed items with no uid). */
private fun sameItem(a: AgendaItem, b: AgendaItem): Boolean =
    a.startsAt == b.startsAt && a.endsAt == b.endsAt && titlesMatch(a.title, b.title, allDay = a.label == TimeLabel.AllDay)

/** Countdown and "Leave by" apply from this far ahead (inclusive). */
val COUNTDOWN_WINDOW: Duration = Duration.ofMinutes(60)

/**
 * The Now/Next lines (spec M7-3). Null means hidden (calendar not set up, loading, unreachable with no copy).
 * - Line 1: the next timed event when it starts within 60 min, else one in progress, else the next timed event.
 *   An event is "next" until a minute after its start (like the Calendar tile); in progress means past that and
 *   before its end. Within 60 min: "in N min" (or "Leave by 5:40 PM" when a [TravelRule] matches), "starting now"
 *   under a minute; further out: "at 5:30 PM". In progress: "now, until 5:30 PM".
 * - Line 2: the other of those two, else the next timed event after line 1, else "Today: <all-day title>".
 * - Nothing timed left today: "Today: <all-day>" (line 2 tomorrow's first), else tomorrow's first, else
 *   "Nothing on today". All-day events never count down.
 * - Calendar data 3 h or older: one line, "Calendar not updated since 2:10 PM". Age line from 15 min.
 * - [quiet]: every title is "Busy"; times stay. Travel rules still match the real title.
 */
fun nowNext(
    state: AgendaState,
    now: Instant,
    zone: ZoneId,
    travel: List<TravelRule>,
    quiet: Boolean,
    is24h: Boolean,
): NowNext? {
    val (days, updatedAt) = when (state) {
        is AgendaState.Ok -> listOf(state.today, state.tomorrow) to state.updatedAt
        is AgendaState.NothingScheduled -> listOf(state.today, state.tomorrow) to state.updatedAt
        is AgendaState.Stale -> listOf(state.today, state.tomorrow) to state.updatedAt
        is AgendaState.Unavailable -> return null
    }
    val local = now.atZone(zone)
    val todayDate = local.toLocalDate()
    fun time(i: Instant) = formatTime(i.atZone(zone).toLocalTime().withSecond(0).withNano(0), is24h)

    val age = when (val v = view(Stamped(Unit, updatedAt), PanelPolicies.CALENDAR, true, now)) {
        is PanelView.Shown -> v.ageText
        else -> {
            val at = updatedAt.atZone(zone)
            val since = if (at.toLocalDate() == todayDate) time(updatedAt) else formatDate(at.toLocalDate())
            return NowNext(NowNextCopy.notUpdatedSince(since), null, null)
        }
    }

    fun shown(item: AgendaItem): String = QuietMode.title(item.title, quiet)

    val today = days.firstOrNull { it.date == todayDate }
    val tomorrow = days.firstOrNull { it.date == todayDate.plusDays(1) }
    if (today == null) return NowNext(NO_EVENTS_CACHED, null, age)

    val rules = travel.take(TravelRule.MAX_RULES)
    val oneMinute = Duration.ofMinutes(1)
    fun upcoming(i: AgendaItem) = i.startsAt != null && i.startsAt.plus(oneMinute).isAfter(now)
    fun inProgress(i: AgendaItem) = i.startsAt != null && !upcoming(i) && i.endsAt != null && now.isBefore(i.endsAt)

    val timed = today.items.filter { it.startsAt != null && it.label != TimeLabel.AllDay }
    val next = timed.filter(::upcoming).sortedBy { it.startsAt }
    val current = timed.filter(::inProgress).sortedByDescending { it.startsAt }.firstOrNull()
    val allDay = today.items.firstOrNull { it.label == TimeLabel.AllDay }

    fun whenText(i: AgendaItem): String {
        if (inProgress(i)) return "${shown(i)} ${NowNextCopy.nowUntil(time(i.endsAt!!))}"
        val start = i.startsAt!!
        val left = Duration.between(now, start)
        if (left > COUNTDOWN_WINDOW) return "${shown(i)} ${NowNextCopy.at(time(start))}"
        val rule = rules.firstOrNull { i.title.contains(it.keyword, ignoreCase = true) }
        if (rule != null) {
            val leave = start.minus(Duration.ofMinutes(rule.minutes.toLong()))
            return "${shown(i)} · " + if (now.isBefore(leave)) NowNextCopy.leaveBy(time(leave)) else NowNextCopy.LEAVE_NOW
        }
        val m = left.toMinutes()
        return "${shown(i)} " + if (m < 1) NowNextCopy.STARTING_NOW else NowNextCopy.inMinutes(m)
    }

    fun tomorrowFirst(): String? {
        val d: AgendaDay = tomorrow ?: return null
        val t = d.items.firstOrNull { it.label is TimeLabel.Starts && it.startsAt != null }
        if (t != null) return NowNextCopy.tomorrow(shown(t), time(t.startsAt!!))
        val a = d.items.firstOrNull { it.label == TimeLabel.AllDay } ?: return null
        return NowNextCopy.tomorrow(shown(a), null)
    }

    val soon = next.firstOrNull()?.takeIf { Duration.between(now, it.startsAt) <= COUNTDOWN_WINDOW }
    val first = soon ?: current ?: next.firstOrNull()
    if (first == null) {
        if (allDay != null) return NowNext(NowNextCopy.today(shown(allDay)), tomorrowFirst(), age)
        return NowNext(tomorrowFirst() ?: NowNextCopy.NOTHING_TODAY, null, age)
    }
    val second: String? = when {
        first === soon && current != null -> whenText(current)
        // Never the headline's event again (calendar-dedupe): a copy with the same times and a matching title is skipped.
        else -> next.firstOrNull { it !== first && !sameItem(it, first) }?.let { NowNextCopy.then(shown(it), time(it.startsAt!!)) }
    } ?: allDay?.let { NowNextCopy.today(shown(it)) }
    return NowNext(whenText(first), second, age)
}
