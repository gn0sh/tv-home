package com.gnosh.tvhome

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import tvhome.core.assistant.AnthropicClient
import tvhome.core.assistant.AskApp
import tvhome.core.assistant.AskEngine
import tvhome.core.assistant.ClaudeClient
import tvhome.core.assistant.DailyCap
import tvhome.core.assistant.FileCountStore
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

    /**
     * Ask Claude (M1b lean). The key is read from [secrets] per call and goes only into the request header. The daily
     * count is a date and a number in no-backup storage, never a question.
     */
    val claude: ClaudeClient = AnthropicClient { secrets.get(SecretKey.ANTHROPIC_API_KEY) }
    val askCap = DailyCap(FileCountStore(File(app.noBackupFilesDir, "ask-count.txt")), clock = { Instant.now() }, zone = { ZoneId.systemDefault() })

    /** A fresh engine (and conversation) per visit to the Ask view; [apps] is read at each question. */
    fun newAskEngine(apps: () -> List<AskApp>): AskEngine =
        AskEngine(claude, hasKey = { runCatching { secrets.get(SecretKey.ANTHROPIC_API_KEY) }.getOrNull() != null }, cap = askCap, apps = apps)
}

private fun isOnline(context: Context): Boolean {
    val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
    val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
    return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
}
