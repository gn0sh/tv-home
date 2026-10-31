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
 * Release builds only (`#3 okhttp-key-in-exception`). The stock handler prints the exception message and stack to
 * logcat and the dropbox, and a message can carry a header value. This one writes exception class names only, then
 * ends the process. It never delegates to the stock handler. (R8 removes the platform Log calls in release, so this uses System.err.)
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
