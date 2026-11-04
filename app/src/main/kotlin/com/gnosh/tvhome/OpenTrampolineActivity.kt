package com.gnosh.tvhome

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import tvhome.core.assistant.OpenFilter

/**
 * overlay-open-fix: the voice overlay's Open. Started by the voice session with startAssistantActivity, the documented
 * way for a voice session to start an activity, so it is a foreground activity when it starts the Open intent that
 * InstalledApps.planOpen made. (Hypothesis, not proven: a plain startActivity from the session's service context may
 * be dropped as a background activity start; the emulator opened the app either way.) Not exported, so only this app
 * can start it. [OpenFilter]: only an intent aimed at another app (package and component both other than ours), URI
 * grants dropped. Shows nothing and finishes at once; failures go to [OpenLog] and a short toast.
 */
class OpenTrampolineActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val target = runCatching {
            if (Build.VERSION.SDK_INT >= 33) intent?.getParcelableExtra(EXTRA_TARGET, Intent::class.java)
            else @Suppress("DEPRECATION") intent?.getParcelableExtra<Intent>(EXTRA_TARGET)
        }.getOrNull()
        val route = intent?.getStringExtra(EXTRA_ROUTE)?.take(16) ?: "none"
        setIntent(Intent())
        if (target != null && OpenFilter.accepts(target.`package`, target.component?.packageName, packageName)) {
            target.selector = null
            target.clipData = null
            target.flags = OpenFilter.flags(target.flags)
            try {
                startActivity(target)
                OpenLog.record(this, "trampoline", route, "started")
            } catch (e: ActivityNotFoundException) {
                couldNotOpen(route, "failed:not_found")
            } catch (e: SecurityException) {
                couldNotOpen(route, "failed:security")
            }
        } else {
            couldNotOpen(route, "refused")
        }
        finish()
    }

    private fun couldNotOpen(route: String, result: String) {
        OpenLog.record(this, "trampoline", route, result)
        runCatching { Toast.makeText(applicationContext, "Couldn't open that app.", Toast.LENGTH_SHORT).show() }
    }

    companion object {
        const val EXTRA_TARGET = "com.gnosh.tvhome.extra.OPEN_TARGET"
        const val EXTRA_ROUTE = "com.gnosh.tvhome.extra.OPEN_ROUTE"
    }
}
