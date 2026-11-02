package com.gnosh.tvhome

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent

/**
 * M3 Home button: when the TV's own home screen comes up (the Home button, or after boot), this brings the dashboard
 * to the front, with focus on its first tile. There is a brief flash of the TV's home first.
 *
 * Why not something firmer: Google TV's launcher declares HOME at priority 2, so "set-home-activity" cannot win
 * (home-role-priority), and the system handles Home (and the assistant keys) before an accessibility key filter sees
 * them (CI, PR #15). So the service gets only "window changed" events, reads only their package and activity class,
 * and acts only on the TV's default home activity (found in [onServiceConnected]); every other event is dropped at
 * once. No window content, nothing stored or logged.
 *
 * Ways out (home-recovery-unreachable): pressing Home twice quickly leaves the TV's own home showing (its Settings gear,
 * apps and inputs), and the redirect stops for a while if it keeps firing, as it would if the dashboard crashed on
 * start (home-redirect-crash-loop).
 */
class HomeWatchService : AccessibilityService() {
    /** The TV's own home activity (package to class); null: nothing to watch. */
    private var home: Pair<String, String>? = null
    private var lastRedirect = 0L
    private val recent = ArrayDeque<Long>()
    private var pausedUntil = 0L

    override fun onServiceConnected() {
        val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        // The default home only (home-set-stale): not Settings' FallbackHome or a setup wizard, which also declare HOME.
        val def = packageManager.resolveActivity(homeIntent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo
        home = def?.takeIf { it.packageName != packageName && it.packageName != "android" }?.let { it.packageName to it.name }
        // Not narrowed with serviceInfo.packageNames: on API 34 that left uiautomator with a null root in our own
        // window (CI, PR #15). Events from other apps are dropped at the first lines of onAccessibilityEvent.
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val target = home ?: return
        if (event.packageName?.toString() != target.first || event.className?.toString() != target.second) return
        val now = SystemClock.elapsedRealtime()
        if (now < pausedUntil) return
        // A second Home within the window after a redirect: leave the TV's own home showing.
        if (now - lastRedirect < DOUBLE_PRESS_MS) { lastRedirect = 0L; return }
        while (recent.isNotEmpty() && now - recent.first() > BURST_MS) recent.removeFirst()
        if (recent.size >= BURST_MAX) { pausedUntil = now + PAUSE_MS; recent.clear(); return }
        recent.addLast(now)
        lastRedirect = now
        runCatching {
            startActivity(
                Intent(this, AgendaActivity::class.java)
                    .setAction(AgendaActivity.ACTION_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    override fun onInterrupt() = Unit

    private companion object {
        /** Home pressed again within this long after a redirect keeps the TV's own home. */
        const val DOUBLE_PRESS_MS = 2_500L

        /** A crash loop restarts every second or two; a person rarely presses Home that often. More than [BURST_MAX] redirects within [BURST_MS] pauses redirecting for [PAUSE_MS]. */
        const val BURST_MAX = 6
        const val BURST_MS = 10_000L
        const val PAUSE_MS = 120_000L
    }
}
