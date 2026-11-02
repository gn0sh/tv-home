package com.gnosh.tvhome

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import tvhome.core.home.HomeRedirect

/**
 * M3 Home button: when the TV's own home screen comes up (the Home button), this brings the dashboard to the front,
 * with focus on its first tile. There is a brief flash of the TV's home first.
 *
 * Why not something firmer: Google TV's launcher declares HOME at priority 2, so "set-home-activity" cannot win
 * (home-role-priority), and the system handles Home (and the assistant keys) before an accessibility key filter sees
 * them (CI, PR #15). So the service gets only "window changed" events, reads only their package and activity class,
 * and acts only on the TV's default home activity (found in [onServiceConnected]); every other event is dropped at
 * once. No window content, nothing stored or logged.
 *
 * When to redirect, and the ways out (Home twice quickly; a pause in a crash loop), is [HomeRedirect] in :core.
 */
class HomeWatchService : AccessibilityService() {
    /** The TV's own home: its package and the class names its window may report (an alias and its target). */
    private var homePkg: String? = null
    private var homeClasses: Set<String> = emptySet()

    /** Packages of apps the user opens from a launcher; one coming up ends a double-press stay (m3-staying-reset-overlay). */
    private var apps: Set<String> = emptySet()
    private val policy = HomeRedirect()

    override fun onServiceConnected() {
        val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        // The default home only (home-set-stale): not Settings' FallbackHome or a setup wizard, which also declare HOME.
        val def = packageManager.resolveActivity(homeIntent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo
            ?.takeIf { it.packageName != packageName && it.packageName != "android" }
        homePkg = def?.packageName
        homeClasses = listOfNotNull(def?.name, def?.targetActivity).toSet()
        apps = packageManager.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0,
        ).mapNotNull { it.activityInfo?.packageName }.toSet() + packageName
        // Not narrowed with serviceInfo.packageNames: on API 34 that left uiautomator with a null root in our own
        // window (CI, PR #15). Events from other apps are dropped at the first lines of onAccessibilityEvent.
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val home = homePkg ?: return
        val pkg = event.packageName?.toString() ?: return
        if (pkg != home) {
            // Dialogs, the keyboard, the Assistant overlay or the screensaver do not end a stay; an app does.
            if (pkg in apps) policy.onAppShown()
            return
        }
        if (event.className?.toString() !in homeClasses) return
        if (!policy.onHomeShown(SystemClock.elapsedRealtime())) return
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
