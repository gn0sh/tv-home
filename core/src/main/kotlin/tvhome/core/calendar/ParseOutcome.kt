package tvhome.core.calendar

/**
 * Contract of the bounded parse (architecture 3a, ADR 0004). Exactly one outcome per body. Only [Complete] is ever
 * shown or cached. T2b.2 added this file so the fetch/source/state side compiles; T2b.1 owns its contents.
 */
sealed interface ParseOutcome {
    class Complete(val events: List<EventInstance>, val stats: ParseStats) : ParseOutcome {
        /** Counts only (titles are personal data). */
        override fun toString(): String = "Complete(${events.size} events, $stats)"
    }

    class Exhausted(val limit: ParseLimit) : ParseOutcome {
        override fun toString(): String = "Exhausted($limit)"
    }

    class Rejected(val reason: RejectReason) : ParseOutcome {
        override fun toString(): String = "Rejected($reason)"
    }
}

enum class ParseLimit { DEADLINE, STEPS, VEVENTS, SERIES }

enum class RejectReason { NOT_VCALENDAR, LINE_ENDING, VERSION, CALSCALE, NESTING, STRUCTURE }

/** Counts only. */
data class ParseStats(
    val vevents: Int,
    val skippedEvents: Int,
    val unsupportedRules: Int,
    val skippedSeries: Int,
    val ignoredValues: Int,
    val truncated: Boolean,
    val steps: Long,
)
