package com.gnosh.tvhome

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.pm.PackageManager
import android.view.accessibility.AccessibilityEvent

/**
 * M3 Home button: when the TV's own home screen comes up (the Home button, or after boot), this brings the dashboard
 * to the front, with focus on its first tile. There is a brief flash of the TV's home first.
 *
 * Why not something firmer: Google TV's launcher declares HOME at priority 2, so "set-home-activity" cannot win
 * (home-role-priority), and the system handles Home (and the assistant keys) before an accessibility key filter sees
 * them (CI, PR #15). So the service gets only "window changed" events, reads only their package and activity class,
 * and acts only on the TV's own home activities (found in [onServiceConnected]); every other event is dropped at once.
 * No window content, nothing stored or logged. The quick panel that holding Home opens is a different screen, so it still works, and so does Settings.
 */
class HomeWatchService : AccessibilityService() {
    /** The TV's own home activities, as package to class. */
    private var homes: Set<Pair<String, String>> = emptySet()

    override fun onServiceConnected() {
        val found = packageManager.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
            PackageManager.MATCH_DEFAULT_ONLY,
        ).mapNotNull { it.activityInfo }
            .filter { it.packageName != packageName && it.packageName != "android" }
            .map { it.packageName to it.name }
            .toSet()
        homes = found
        // Not narrowed with serviceInfo.packageNames: on API 34 that left uiautomator with a null root in our own
        // window (CI, PR #15). Events from other apps are dropped at the first line of onAccessibilityEvent.
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        val cls = event.className?.toString() ?: return
        if ((pkg to cls) !in homes) return
        runCatching {
            startActivity(
                Intent(this, AgendaActivity::class.java)
                    .setAction(AgendaActivity.ACTION_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    override fun onInterrupt() = Unit
}
