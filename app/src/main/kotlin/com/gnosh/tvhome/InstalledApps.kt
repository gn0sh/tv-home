package com.gnosh.tvhome

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import androidx.compose.ui.graphics.asImageBitmap
import tvhome.core.apps.AppOrder
import tvhome.core.apps.LauncherApp

/**
 * The tile row's data: every activity with MAIN + LEANBACK_LAUNCHER (visible through the manifest's `<queries>`; no
 * QUERY_ALL_PACKAGES), ordered by [AppOrder]. Blocking (package manager and drawable loads): call off the main thread.
 */
object InstalledApps {
    private fun launcherIntent() = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)

    fun load(context: Context): List<TileApp> {
        val pm = context.packageManager
        // The flags-object overload is API 33+; minSdk is 31.
        @Suppress("DEPRECATION")
        val infos = pm.queryIntentActivities(launcherIntent(), 0)
        val byKey = HashMap<String, android.content.pm.ActivityInfo>()
        val apps = infos.mapNotNull { ri ->
            val ai = ri.activityInfo ?: return@mapNotNull null
            byKey["${ai.packageName}/${ai.name}"] = ai
            LauncherApp(ai.packageName, ai.name, ri.loadLabel(pm).toString().ifBlank { ai.packageName })
        }
        return AppOrder.order(apps, context.packageName).map { app ->
            val ai = byKey["${app.packageName}/${app.activityName}"]
            val banner = ai?.let { runCatching { it.loadBanner(pm) }.getOrNull() }
            if (banner != null) {
                TileApp(app, runCatching { banner.toImage(320, 180) }.getOrNull(), isBanner = true)
            } else {
                val icon = ai?.let { runCatching { it.loadIcon(pm) }.getOrNull() }
                TileApp(app, icon?.let { runCatching { it.toImage(160, 160) }.getOrNull() }, isBanner = false)
            }
        }
    }

    /** Starts the app in its own task, so Back from it returns here. False if it is gone or refuses. */
    fun launch(context: Context, app: LauncherApp): Boolean = try {
        context.startActivity(launcherIntent().setClassName(app.packageName, app.activityName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: SecurityException) {
        false
    }

    private fun Drawable.toImage(w: Int, h: Int) = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { b ->
        setBounds(0, 0, w, h)
        draw(Canvas(b))
    }.asImageBitmap()
}
