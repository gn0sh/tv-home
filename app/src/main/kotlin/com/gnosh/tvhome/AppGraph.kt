package com.gnosh.tvhome

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import tvhome.core.calendar.IcsCalendarSource
import tvhome.core.calendar.IcsFetcher
import tvhome.core.calendar.IcsParse
import tvhome.core.secrets.SecretKey
import tvhome.core.secrets.SecretStore
import tvhome.core.state.AgendaRefresher
import tvhome.core.state.AgendaSession
import tvhome.core.state.JvmCacheFile
import java.io.File
import java.time.Instant
import java.time.ZoneId

/** The few long-lived objects. Wiring only; behaviour is in `:core`. */
class AppGraph(app: Application) {
    val secrets: SecretStore = KeystoreSecretStore(app)

    private val cache = JvmCacheFile(File(app.noBackupFilesDir, "agenda-cache.json"))

    /** The agenda screen's engine. Its only cache writer is the [AgendaRefresher] inside it. */
    val session: AgendaSession = AgendaSession(
        refresher = AgendaRefresher(
            source = IcsCalendarSource(secrets, IcsFetcher(), IcsParse.ics()),
            cache = cache,
            zone = { ZoneId.systemDefault() },
            clock = { Instant.now() },
        ),
        cache = cache,
        hasSecret = { secrets.get(SecretKey.CALENDAR_URL) != null },
        online = { isOnline(app) },
        zone = { ZoneId.systemDefault() },
        clock = { Instant.now() },
    )
}

private fun isOnline(context: Context): Boolean {
    val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
    val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
    return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
}
