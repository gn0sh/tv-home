package tvhome.core.state

import tvhome.core.agenda.AgendaDay
import tvhome.core.agenda.agendaWindow
import tvhome.core.agenda.buildAgenda
import tvhome.core.calendar.FetchResult
import tvhome.core.calendar.InstantRange
import tvhome.core.calendar.SourceError
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

sealed interface AgendaState {
    /** Fresh enough to show without alarm. [ageText] is the header line, e.g. "Updated 4 min ago". */
    data class Ok(val today: AgendaDay, val tomorrow: AgendaDay, val updatedAt: Instant, val ageText: String) : AgendaState

    /** Ok, but both days are empty: both headings render "Nothing scheduled". */
    data class NothingScheduled(val today: AgendaDay, val tomorrow: AgendaDay, val updatedAt: Instant, val ageText: String) : AgendaState

    /** Showing a cache after a failed or overdue fetch. [emphasised] (age over 60 min) means amber, bold, warning and footer. */
    data class Stale(
        val today: AgendaDay,
        val tomorrow: AgendaDay,
        val updatedAt: Instant,
        val ageMinutes: Long,
        val ageText: String,
        val emphasised: Boolean,
    ) : AgendaState

    sealed interface Unavailable : AgendaState
    object NotSetUp : Unavailable { override fun toString() = "NotSetUp" }
    object CantReach : Unavailable { override fun toString() = "CantReach" }

    /** No usable cache and the feed arrived but could not be used (PARSE, TOO_LARGE). */
    object Unreadable : Unavailable {
        const val TITLE = "Calendar data unreadable"
        const val DETAIL = "It will try again soon."
        override fun toString() = "Unreadable"
    }
    object Loading : Unavailable { override fun toString() = "Loading" }
}

const val STALE_AFTER_MIN = 15L
const val EMPHASIS_AFTER_MIN = 60L

/** Design wording: under 1 min "just now"; 1-59 "N min ago"; hours "N hour(s) ago"; then "N day(s) ago". */
fun ageText(ageMinutes: Long): String {
    val m = ageMinutes.coerceAtLeast(0)
    return "Updated " + when {
        m < 1 -> "just now"
        m < 60 -> "$m min ago"
        m < 120 -> "1 hour ago"
        m < 24 * 60 -> "${m / 60} hours ago"
        m < 48 * 60 -> "1 day ago"
        else -> "${m / (24 * 60)} days ago"
    }
}

/**
 * The one place that decides what the TV shows.
 * - No secret: NotSetUp.
 * - A good fetch this session: its events, age from its fetchedAt (Ok / NothingScheduled).
 * - Else a cache whose window covers today and tomorrow: Ok / NothingScheduled while the last fetch has not failed and
 *   the cache is at most 15 min old; Stale (age shown) if the fetch failed or the cache is older than 15 min with no
 *   fetch yet. Stale is [AgendaState.Stale.emphasised] only past 60 min. A failure with a cache under 60 min old is
 *   therefore silent (design: "plain ok"), the UI renders non-emphasised Stale like Ok.
 * - Else (no usable cache): no fetch yet is Loading; PARSE / TOO_LARGE is Unreadable; any other failure is CantReach.
 */
fun reduce(hasSecret: Boolean, cache: CachedAgenda?, lastFetch: FetchResult?, now: Instant, zone: ZoneId): AgendaState {
    if (!hasSecret) return AgendaState.NotSetUp
    val needed = neededRange(now, zone)
    if (lastFetch is FetchResult.Ok) {
        return fresh(lastFetch.events, lastFetch.fetchedAt, now, zone)
    }
    val usable = cache?.takeIf { it.covers(needed) }
    if (usable == null) {
        return when (lastFetch) {
            null -> AgendaState.Loading
            is FetchResult.Failed ->
                if (lastFetch.error == SourceError.PARSE || lastFetch.error == SourceError.TOO_LARGE) AgendaState.Unreadable
                else AgendaState.CantReach
            is FetchResult.Ok -> error("unreachable")
        }
    }
    val age = ageMinutes(usable.fetchedAt, now)
    val failed = lastFetch is FetchResult.Failed
    if (!failed && age <= STALE_AFTER_MIN) return fresh(usable.events, usable.fetchedAt, now, zone)
    val (today, tomorrow) = buildAgenda(usable.events, now, zone)
    return AgendaState.Stale(today, tomorrow, usable.fetchedAt, age, ageText(age), age > EMPHASIS_AFTER_MIN)
}

private fun fresh(events: List<tvhome.core.calendar.EventInstance>, at: Instant, now: Instant, zone: ZoneId): AgendaState {
    val (today, tomorrow) = buildAgenda(events, now, zone)
    val text = ageText(ageMinutes(at, now))
    return if (today.items.isEmpty() && tomorrow.items.isEmpty()) {
        AgendaState.NothingScheduled(today, tomorrow, at, text)
    } else {
        AgendaState.Ok(today, tomorrow, at, text)
    }
}

private fun ageMinutes(from: Instant, now: Instant): Long = Duration.between(from, now).toMinutes().coerceAtLeast(0)

/** Start of today to end of tomorrow in [zone]. */
fun neededRange(now: Instant, zone: ZoneId): InstantRange {
    val today = now.atZone(zone).toLocalDate()
    return InstantRange(today.atStartOfDay(zone).toInstant(), today.plusDays(2).atStartOfDay(zone).toInstant())
}

/** Builds the cache for a good fetch. */
fun cacheOf(r: FetchResult.Ok, now: Instant, zone: ZoneId) = CachedAgenda(r.fetchedAt, agendaWindow(now, zone), r.events)
