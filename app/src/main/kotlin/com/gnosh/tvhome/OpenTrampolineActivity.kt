package com.gnosh.tvhome

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast

/**
 * overlay-open-fix: the voice overlay's Open. Started by the voice session with startAssistantActivity (a start the
 * platform allows from a voice session), so it is a foreground activity when it starts the Open intent that
 * InstalledApps.planOpen made. Not exported, so only this app can start it. It accepts only an intent aimed at
 * another app (a package or a component), drops any URI grants, shows nothing, and finishes at once.
 */
class OpenTrampolineActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val target = runCatching {
            if (Build.VERSION.SDK_INT >= 33) intent?.getParcelableExtra(EXTRA_TARGET, Intent::class.java)
            else @Suppress("DEPRECATION") intent?.getParcelableExtra<Intent>(EXTRA_TARGET)
        }.getOrNull()
        setIntent(Intent())
        val pkg = target?.`package` ?: target?.component?.packageName
        if (target != null && pkg != null && pkg != packageName) {
            target.selector = null
            target.clipData = null
            target.flags = (target.flags and GRANTS.inv()) or Intent.FLAG_ACTIVITY_NEW_TASK
            try {
                startActivity(target)
            } catch (e: ActivityNotFoundException) {
                couldNotOpen()
            } catch (e: SecurityException) {
                couldNotOpen()
            }
        } else {
            couldNotOpen()
        }
        finish()
    }

    private fun couldNotOpen() {
        runCatching { Toast.makeText(applicationContext, "Couldn't open that app.", Toast.LENGTH_SHORT).show() }
    }

    companion object {
        const val EXTRA_TARGET = "com.gnosh.tvhome.extra.OPEN_TARGET"
        private const val GRANTS = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
    }
}
