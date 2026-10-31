package tvhome.core.calendar

import tvhome.core.agenda.agendaFocus
import java.time.ZoneId

/**
 * Seam to [IcsParser] (architecture section 3a, ADR 0004): takes the raw body bytes and the window, returns exactly
 * one [ParseOutcome], never throws for any input. Blocking and CPU-bound; [IcsCalendarSource] runs it on its CPU dispatcher inside
 * `runInterruptible`. It throws only [InterruptedException] (cancellation); anything else is a bug that the source
 * boundary maps to `PARSE`.
 */
fun interface IcsParse {
    fun parse(body: ByteArray, window: InstantRange): ParseOutcome

    companion object {
        /**
         * The real parser. [zone] is read per call so a device time-zone change is picked up. When the 500-instance
         * cap cuts, today and tomorrow (see [agendaFocus]) are kept first.
         */
        fun ics(zone: () -> ZoneId = ZoneId::systemDefault): IcsParse = IcsParse { body, window ->
            val z = zone()
            IcsParser.parse(body, window, z, focus = agendaFocus(window, z))
        }
    }
}

/** The result of one bounded parse. Only [Complete] is ever shown or cached (N1). */
sealed interface ParseOutcome {
    /** Every limit that bound was per item (counted in [stats]); [events] is the full answer for the window. */
    class Complete(val events: List<EventInstance>, val stats: ParseStats) : ParseOutcome {
        /** Counts only: titles and uids are personal data (N6). */
        override fun toString(): String = "Complete(${events.size} events, $stats)"
    }

    /** A parse-wide limit ran out, so the answer is unknown. Nothing from it may be shown or cached. */
    class Exhausted(val limit: ParseLimit) : ParseOutcome {
        override fun toString(): String = "Exhausted($limit)"
    }

    /** The bytes are not a framing-valid iCalendar 2.0 stream (ADR 0004 decision 1). */
    class Rejected(val reason: RejectReason) : ParseOutcome {
        override fun toString(): String = "Rejected($reason)"
    }
}

/**
 * Parse-wide limits: wall-clock deadline, global steps, VEVENTs per feed, kept recurring series per feed, and
 * INSTANCES: the 500-instance cap cut instances and a RECURRENCE-ID then removed kept ones, so the cut-off ones
 * cannot be known (`instance-cap-removal-order`).
 */
enum class ParseLimit { DEADLINE, STEPS, VEVENTS, SERIES, INSTANCES }

/** Framing violations; each rejects the whole feed. */
enum class RejectReason { NOT_VCALENDAR, LINE_ENDING, VERSION, CALSCALE, NESTING, STRUCTURE }

/**
 * Counts only, never content (N6).
 * - [vevents]: top-level VEVENTs read.
 * - [skippedEvents]: malformed VEVENTs skipped (bad value, kept line over 8 KiB, over 2,000 EXDATE+RDATE values).
 * - [unsupportedRules]: series shown as their DTSTART only (rule not expanded).
 * - [skippedSeries]: series over their own step budget, skipped whole.
 * - [ignoredValues]: single bad EXDATE/RDATE values ignored.
 * - [truncated]: more than 500 instances overlapped the window; the extra ones were cut.
 * - [steps]: global budget steps charged.
 */
data class ParseStats(
    val vevents: Int,
    val skippedEvents: Int,
    val unsupportedRules: Int,
    val skippedSeries: Int,
    val ignoredValues: Int,
    val truncated: Boolean,
    val steps: Long,
)
