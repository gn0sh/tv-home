package tvhome.core.home

/**
 * M3 Home: decides, each time the TV's own home comes up, whether to bring the dashboard forward (HomeWatchService).
 *
 * - Home again within [doublePressMs] of a redirect: the TV's own home stays (its Settings gear, apps and inputs), and
 *   keeps staying, even if it reports itself again while its tabs are browsed, until [onAppShown] (home-recovery-unreachable).
 * - More than [burstMax] redirects within [burstMs]: redirecting pauses for [pauseMs], as in a crash loop where the
 *   dashboard dies on every start (home-redirect-crash-loop). Redirects are at least [doublePressMs] apart, so
 *   [burstMax] must stay below [burstMs] / [doublePressMs] to be reachable.
 *
 * Times are a monotonic clock in milliseconds; nothing here reads a clock itself.
 */
class HomeRedirect(
    private val doublePressMs: Long = 2_500,
    private val burstMax: Int = 6,
    private val burstMs: Long = 30_000,
    private val pauseMs: Long = 120_000,
) {
    init { require(burstMax < burstMs / doublePressMs) { "burstMax could never be reached" } }

    private var lastRedirect: Long? = null
    private val recent = ArrayDeque<Long>()
    private var pausedUntil = Long.MIN_VALUE
    var staying = false
        private set

    /** The TV's own home came up at [now]. True: bring the dashboard forward. */
    fun onHomeShown(now: Long): Boolean {
        if (staying || now < pausedUntil) return false
        val last = lastRedirect
        if (last != null && now - last < doublePressMs) { lastRedirect = null; staying = true; return false }
        while (recent.isNotEmpty() && now - recent.first() > burstMs) recent.removeFirst()
        if (recent.size >= burstMax) { pausedUntil = now + pauseMs; recent.clear(); lastRedirect = null; return false }
        recent.addLast(now)
        lastRedirect = now
        return true
    }

    /** The dashboard or an app the user opens came up: the next Home redirects again. */
    fun onAppShown() { staying = false }
}
