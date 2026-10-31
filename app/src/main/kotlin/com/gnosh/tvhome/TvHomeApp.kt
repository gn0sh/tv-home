package com.gnosh.tvhome

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Process
import tvhome.core.secrets.CrashSummary
import kotlin.system.exitProcess

class TvHomeApp : Application() {
    /** Built on first use, so a provider call or an activity never races another. */
    val graph: AppGraph by lazy { AppGraph(this) }

    // attachBaseContext runs before any ContentProvider is created, so the handler covers them too.
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        if ((base.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) == 0) ReleaseCrashHandler.install()
    }
}

/**
 * Release builds only (`#3 okhttp-key-in-exception`). This is NOT the control that keeps secrets out of crash logs:
 * since Android 8 the runtime's own logging handler runs first and already writes "FATAL EXCEPTION" with the message
 * and stack to `logcat -b crash`, before this handler is reached. The control is that no exception carrying a secret
 * escapes (the fetch, provisioning, Keystore and cache boundaries catch Throwable and map to enums). What this handler
 * adds: it does not delegate to the stock handler, so no `data_app_crash` entry reaches the dropbox, and it writes only
 * class names itself. (R8 removes the platform Log calls in release, so it uses System.err.)
 */
object ReleaseCrashHandler {
    fun install() {
        Thread.setDefaultUncaughtExceptionHandler { _, t ->
            try {
                System.err.println(CrashSummary.of(t))
            } catch (_: Throwable) {
                // nothing safe left to do
            }
            Process.killProcess(Process.myPid())
            exitProcess(10)
        }
    }
}
