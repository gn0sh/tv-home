package com.gnosh.tvhome

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process
import tvhome.core.apps.RecentUse

/**
 * continue-tile: which apps Brian used last (UsageStats, the last 7 days), only when the GET_USAGE_STATS app op
 * is allowed (one adb command at install: `appops set com.gnosh.tvhome GET_USAGE_STATS allow`; undo with `ignore`, which an update does not change).
 * Null when it is not allowed, so the tile hides instead of showing an error. Package names and times only; nothing
 * is stored or logged. Blocking: call off the main thread.
 */
internal object RecentApps {
    private const val WINDOW_MS = 7L * 24 * 3600 * 1000

    fun allowed(context: Context): Boolean = runCatching {
        val ops = context.getSystemService(AppOpsManager::class.java) ?: return false
        ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName) == AppOpsManager.MODE_ALLOWED
    }.getOrDefault(false)

    fun recent(context: Context): List<RecentUse>? {
        if (!allowed(context)) return null
        return runCatching {
            val um = context.getSystemService(UsageStatsManager::class.java) ?: return null
            val now = System.currentTimeMillis()
            um.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - WINDOW_MS, now).orEmpty()
                // lastTimeVisible (API 29+, minSdk 31): when the app was last on screen, not when it last ran in the background.
                .map { RecentUse(it.packageName, maxOf(it.lastTimeVisible, 0L)) }
        }.getOrNull()
    }
}
