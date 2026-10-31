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
        /** Both days empty: render the "Nothing scheduled" body under the stale header (and footer when emphasised). */
        val nothingScheduled: Boolean,
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
 * The one place that decides what the TV shows. A good fetch from this session and the stored cache go through the
 * same age and window rules (a session Ok is just a fresher [CachedAgenda]); only the "fetch failed" flag differs.
 * - No secret: NotSetUp.
 * - Data (session Ok, else cache) whose window covers today and tomorrow:
 *   Ok / NothingScheduled when the last fetch did not fail and the data is at most 15 min old;
 *   otherwise Stale (age shown): fetch failed, or older than 15 min with no newer fetch (e.g. offline since).
 *   Stale is emphasised from exactly 60:00 old, measured in seconds. A failure with data under 60 min old is silent
 *   (design: "plain ok"): the UI renders non-emphasised Stale like Ok.
 * - No usable data: no fetch yet is Loading; a session Ok whose window has since passed is CantReach;
 *   PARSE / TOO_LARGE is Unreadable; every other failure is CantReach. That includes INSECURE_URL and NOT_CONFIGURED,
 *   which the app should not reach with a validated secret; CantReach is the safe fallback and never shows the URL.
 */
fun reduce(hasSecret: Boolean, cache: CachedAgenda?, lastFetch: FetchResult?, now: Instant, zone: ZoneId): AgendaState {
    if (!hasSecret) return AgendaState.NotSetUp
    val data = if (lastFetch is FetchResult.Ok) cacheOf(lastFetch, zone) else cache
    val usable = data?.takeIf { it.covers(neededRange(now, zone)) }
    if (usable == null) {
        if (lastFetch == null) return AgendaState.Loading
        // A session Ok whose window has passed is treated like a failed fetch: nothing usable to show.
        val failed = lastFetch as? FetchResult.Failed ?: return AgendaState.CantReach
        return if (failed.error == SourceError.PARSE || failed.error == SourceError.TOO_LARGE) AgendaState.Unreadable
        else AgendaState.CantReach
    }
    val ageSec = Duration.between(usable.fetchedAt, now).seconds.coerceAtLeast(0)
    val minutes = ageSec / 60
    val failed = lastFetch is FetchResult.Failed
    val (today, tomorrow) = buildAgenda(usable.events, now, zone)
    val empty = today.items.isEmpty() && tomorrow.items.isEmpty()
    if (!failed && ageSec <= STALE_AFTER_MIN * 60) {
        val text = ageText(minutes)
        return if (empty) AgendaState.NothingScheduled(today, tomorrow, usable.fetchedAt, text)
        else AgendaState.Ok(today, tomorrow, usable.fetchedAt, text)
    }
    return AgendaState.Stale(today, tomorrow, usable.fetchedAt, minutes, ageText(minutes), ageSec >= EMPHASIS_AFTER_MIN * 60, empty)
}

/** Start of today to end of tomorrow in [zone]. */
fun neededRange(now: Instant, zone: ZoneId): InstantRange {
    val today = now.atZone(zone).toLocalDate()
    return InstantRange(today.atStartOfDay(zone).toInstant(), today.plusDays(2).atStartOfDay(zone).toInstant())
}

/** Builds the cache for a good fetch; the window is derived from the fetch time, not from "now". */
fun cacheOf(r: FetchResult.Ok, zone: ZoneId) = CachedAgenda(r.fetchedAt, agendaWindow(r.fetchedAt, zone), r.events)
