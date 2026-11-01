package tvhome.core.state

import java.time.Duration
import java.time.Instant

/** When to fetch. Pure: the app owns the timer and connectivity check and asks this on each trigger. */
class RefreshPolicy(private val interval: Duration = Duration.ofMinutes(15)) {
    enum class Trigger { START, TICK }
    enum class Decision { FETCH, SKIP_OFFLINE, SKIP_TOO_SOON }

    /** START always fetches when online. TICK (while visible) fetches when [interval] has passed since the last attempt. Offline never fetches. */
    fun decide(trigger: Trigger, online: Boolean, lastAttempt: Instant?, now: Instant): Decision = when {
        !online -> Decision.SKIP_OFFLINE
        trigger == Trigger.START -> Decision.FETCH
        lastAttempt == null || Duration.between(lastAttempt, now) >= interval -> Decision.FETCH
        else -> Decision.SKIP_TOO_SOON
    }

    /** Delay until the next TICK while visible. */
    fun tickDelay(): Duration = interval
}
