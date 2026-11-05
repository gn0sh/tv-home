package com.gnosh.tvhome.probe

import android.app.Activity
import android.app.usage.UsageStatsManager
import android.content.ContentValues
import android.content.pm.PackageManager
import android.media.tv.TvContract
import android.os.Bundle
import android.util.Log

/**
 * probe-watch-next (throwaway branch, never merged; qa build only). Modes (extra "mode"):
 * query: TvContract.WatchNextPrograms as this app sees it (all packages or only ours), with the READ_EPG_DATA state;
 * insert_own: one Watch Next row of our own; usage: UsageStatsManager recent packages (last 7 days).
 * Results go to logcat tag WatchProbe, one line each. Made-up data only.
 */
class WatchNextProbeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        when (intent?.getStringExtra("mode")) {
            "insert_own" -> insertOwn()
            "usage" -> usage()
            else -> query()
        }
        finish()
    }

    private fun p(s: String) { Log.i(TAG, s) }

    private fun perm(name: String) = if (checkSelfPermission(name) == PackageManager.PERMISSION_GRANTED) "granted" else "denied"

    private fun query() {
        p("query: READ_EPG_DATA=${perm(EPG)}")
        val cols = arrayOf(
            TvContract.WatchNextPrograms._ID,
            TvContract.WatchNextPrograms.COLUMN_PACKAGE_NAME,
            TvContract.WatchNextPrograms.COLUMN_TITLE,
            TvContract.WatchNextPrograms.COLUMN_EPISODE_TITLE,
            TvContract.WatchNextPrograms.COLUMN_SEASON_DISPLAY_NUMBER,
            TvContract.WatchNextPrograms.COLUMN_EPISODE_DISPLAY_NUMBER,
            TvContract.WatchNextPrograms.COLUMN_WATCH_NEXT_TYPE,
            TvContract.WatchNextPrograms.COLUMN_INTENT_URI,
            TvContract.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS,
            TvContract.WatchNextPrograms.COLUMN_POSTER_ART_URI,
        )
        try {
            contentResolver.query(TvContract.WatchNextPrograms.CONTENT_URI, cols, null, null, null).use { c ->
                if (c == null) { p("query: cursor=null"); return }
                p("query: rows=${c.count}")
                while (c.moveToNext()) {
                    p("query: row id=${c.getLong(0)} pkg=${c.getString(1)} title=${c.getString(2)} ep=${c.getString(3)} s=${c.getString(4)} e=${c.getString(5)} type=${c.getInt(6)} intent=${c.getString(7)?.take(80)} last=${c.getLong(8)} poster=${c.getString(9) != null}")
                }
            }
        } catch (t: Throwable) {
            p("query: threw ${t.javaClass.simpleName}: ${t.message?.take(200)}")
        }
    }

    private fun insertOwn() {
        val v = ContentValues().apply {
            put(TvContract.WatchNextPrograms.COLUMN_TYPE, TvContract.WatchNextPrograms.TYPE_TV_EPISODE)
            put(TvContract.WatchNextPrograms.COLUMN_WATCH_NEXT_TYPE, TvContract.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE)
            put(TvContract.WatchNextPrograms.COLUMN_TITLE, "Probe Series (own)")
            put(TvContract.WatchNextPrograms.COLUMN_EPISODE_TITLE, "Pilot")
            put(TvContract.WatchNextPrograms.COLUMN_SEASON_DISPLAY_NUMBER, "1")
            put(TvContract.WatchNextPrograms.COLUMN_EPISODE_DISPLAY_NUMBER, "2")
            put(TvContract.WatchNextPrograms.COLUMN_INTENT_URI, "https://example.com/watch/1")
            put(TvContract.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS, System.currentTimeMillis())
        }
        try {
            p("insert_own: uri=${contentResolver.insert(TvContract.WatchNextPrograms.CONTENT_URI, v)}")
        } catch (t: Throwable) {
            p("insert_own: threw ${t.javaClass.simpleName}: ${t.message?.take(200)}")
        }
    }

    private fun usage() {
        val um = getSystemService(UsageStatsManager::class.java)
        val now = System.currentTimeMillis()
        try {
            val stats = um.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - 7L * 24 * 3600 * 1000, now)
            p("usage: entries=${stats?.size ?: -1} (empty means no PACKAGE_USAGE_STATS access)")
            stats.orEmpty().filter { it.lastTimeUsed > 0 }.sortedByDescending { it.lastTimeUsed }.distinctBy { it.packageName }.take(12).forEach {
                p("usage: pkg=${it.packageName} lastUsed=${(now - it.lastTimeUsed) / 1000}s_ago fg=${it.totalTimeInForeground / 1000}s")
            }
        } catch (t: Throwable) {
            p("usage: threw ${t.javaClass.simpleName}: ${t.message?.take(200)}")
        }
    }

    private companion object {
        const val TAG = "WatchProbe"
        const val EPG = "com.android.providers.tv.permission.READ_EPG_DATA"
    }
}
