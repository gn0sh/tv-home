package tvhome.core.assistant

import java.util.concurrent.atomic.AtomicBoolean

/**
 * The home screen's side of the brief (M7-F6, architecture 11.4 flow 3). The hero calls [onTick] on each minute tick;
 * it hands the facts to [BriefEngine.refreshIfDue] only when the home screen is visible ([visibleForMs] not null) and
 * has been for at least [settleMs], so the feeds have had time to fill in and a slot is not spent on an empty
 * snapshot right after a cold start. One refresh at a time: a tick that comes while a request is still running is
 * skipped (the engine's ledger would refuse it anyway, but this keeps it from even reading the ledger).
 */
class BriefTicker(private val engine: BriefEngine, private val settleMs: Long = SETTLE_MS) {
    private val busy = AtomicBoolean(false)

    /** True when a refresh ran (the caller then redraws the line from the cache). Never throws but for cancellation. */
    suspend fun onTick(facts: BriefFacts, visibleForMs: Long?): Boolean {
        if (visibleForMs == null || visibleForMs < settleMs) return false
        if (!busy.compareAndSet(false, true)) return false
        try {
            engine.refreshIfDue(facts)
        } finally {
            busy.set(false)
        }
        return true
    }

    override fun toString(): String = "BriefTicker"

    companion object {
        /** Feeds start 1.5 s after the first frame; a weather fetch takes a few seconds. */
        const val SETTLE_MS = 20_000L
    }
}
