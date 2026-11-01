package com.gnosh.tvhome

import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.net.Uri
import androidx.compose.ui.graphics.asImageBitmap
import tvhome.core.apps.AppOrder
import tvhome.core.apps.LauncherApp
import tvhome.core.assistant.OpenTarget
import tvhome.core.ui.HomeTheme
import java.io.File

/**
 * The tile row's data: every activity with MAIN + LEANBACK_LAUNCHER (visible through the manifest's `<queries>`; no
 * QUERY_ALL_PACKAGES), ordered by [AppOrder]. Blocking (package manager and drawable loads): call off the main thread.
 *
 * Tiles are cached by activity, package lastUpdateTime and pixel size: on each start the (cheap) launcher query runs
 * again so installs and removals show up, but banners are drawn again only for apps that changed.
 */
class InstalledApps {
    private class Cached(val updated: Long, val w: Int, val h: Int, val tile: TileApp)
    private val cache = HashMap<String, Cached>()

    /** [tileW]/[tileH] are the tile's size in pixels (dp x density), so bitmaps are drawn at display resolution. */
    @Synchronized
    fun load(context: Context, tileW: Int, tileH: Int): List<TileApp> {
        val pm = context.packageManager
        // The flags-object overload is API 33+; minSdk is 31.
        @Suppress("DEPRECATION")
        val infos = pm.queryIntentActivities(launcherIntent(), 0)
        val byKey = HashMap<String, android.content.pm.ActivityInfo>()
        val apps = infos.mapNotNull { ri ->
            val ai = ri.activityInfo ?: return@mapNotNull null
            byKey[key(ai.packageName, ai.name)] = ai
            LauncherApp(ai.packageName, ai.name, ri.loadLabel(pm).toString().ifBlank { ai.packageName })
        }
        val iconPx = minOf(tileW, tileH) * 3 / 5
        val out = AppOrder.order(apps, context.packageName).map { app ->
            val k = key(app.packageName, app.activityName)
            val ai = byKey[k]
            @Suppress("DEPRECATION")
            val updated = runCatching { pm.getPackageInfo(app.packageName, 0).lastUpdateTime }.getOrDefault(-1L)
            val hit = cache[k]
            if (hit != null && hit.updated == updated && hit.w == tileW && hit.h == tileH && hit.tile.app == app) {
                hit.tile
            } else {
                val banner = ai?.let { runCatching { it.loadBanner(pm) }.getOrNull() }
                val tile = if (banner != null) {
                    TileApp(app, runCatching { banner.toImage(tileW, tileH) }.getOrNull(), isBanner = true)
                } else {
                    val icon = ai?.let { runCatching { it.loadIcon(pm) }.getOrNull() }
                    TileApp(app, icon?.let { runCatching { it.toImage(iconPx, iconPx) }.getOrNull() }, isBanner = false)
                }
                cache[k] = Cached(updated, tileW, tileH, tile)
                tile
            }
        }
        cache.keys.retainAll(out.map { key(it.app.packageName, it.app.activityName) }.toSet())
        return out
    }

    private fun key(pkg: String, activity: String) = "$pkg/$activity"

    companion object {
        private fun launcherIntent() = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)

        /** Starts the app in its own task, so Back from it returns here. False if it is gone or refuses. */
        fun launch(context: Context, app: LauncherApp): Boolean = try {
            context.startActivity(launcherIntent().setClassName(app.packageName, app.activityName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (e: ActivityNotFoundException) {
            false
        } catch (e: SecurityException) {
            false
        }

        /**
         * How Open launches an Ask Claude pick (spec AC5), decided up front so the screen can say which fallback is used:
         * the deep link as a VIEW intent restricted to the chosen package, else the app's own search for the title, else
         * the app's TV home. Each is resolved against the package manager first (the app is visible through the
         * `<queries>` LEANBACK_LAUNCHER entry, which makes its whole package visible). Nothing is logged.
         */
        fun planOpen(context: Context, target: OpenTarget): OpenPlan {
            val pm = context.packageManager
            val pkg = target.app.packageName
            val label = target.app.label
            fun resolves(i: Intent) = runCatching {
                @Suppress("DEPRECATION")
                pm.resolveActivity(i, 0) != null
            }.getOrDefault(false)
            fun plan(description: String, intent: Intent) = OpenPlan(description) {
                try {
                    context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    true
                } catch (e: ActivityNotFoundException) {
                    false
                } catch (e: SecurityException) {
                    false
                }
            }
            target.deepLink?.let { link ->
                val view = Intent(Intent.ACTION_VIEW, Uri.parse(link)).setPackage(pkg)
                // The link's host is shown, so Brian sees where a link points before pressing Open.
                val host = runCatching { Uri.parse(link).host }.getOrNull()?.removePrefix("www.").orEmpty()
                if (resolves(view)) return plan("Opens \u201C${target.title}\u201D directly in $label ($host).", view)
            }
            val search = Intent(Intent.ACTION_SEARCH).setPackage(pkg).putExtra(SearchManager.QUERY, target.title)
            if (resolves(search)) return plan("Opens $label's search for \u201C${target.title}\u201D.", search)
            val home = pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg)
            if (home != null) return plan("Opens $label's home. Look for \u201C${target.title}\u201D there.", home)
            return OpenPlan("$label can't be opened.") { false }
        }

        /** The home theme from app-private storage (`files/home-theme.json`); defaults if missing, too big or invalid. */
        fun loadTheme(context: Context): HomeTheme = runCatching {
            val f = File(context.filesDir, "home-theme.json")
            if (!f.isFile || f.length() > HomeTheme.MAX_BYTES) null else HomeTheme.parse(f.readText())
        }.getOrNull() ?: HomeTheme.DEFAULT

        private fun Drawable.toImage(w: Int, h: Int) = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { b ->
            setBounds(0, 0, w, h)
            draw(Canvas(b))
        }.asImageBitmap()
    }
}
