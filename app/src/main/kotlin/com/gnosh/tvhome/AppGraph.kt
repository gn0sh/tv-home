package com.gnosh.tvhome

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import tvhome.core.apps.ContinuePicks
import tvhome.core.assistant.AnthropicClient
import tvhome.core.assistant.OpenTarget
import tvhome.core.assistant.AskApp
import tvhome.core.assistant.AskEngine
import tvhome.core.assistant.BriefConfig
import tvhome.core.assistant.BriefEngine
import tvhome.core.assistant.BriefLedger
import tvhome.core.assistant.BriefTicker
import tvhome.core.assistant.ClaudeClient
import tvhome.core.assistant.DailyCap
import tvhome.core.assistant.FileCountStore
import tvhome.core.calendar.IcsCalendarSource
import tvhome.core.calendar.IcsFetcher
import tvhome.core.calendar.IcsParse
import tvhome.core.feeds.HttpFeedFetcher
import tvhome.core.feeds.LiveFeeds
import tvhome.core.feeds.PanelCacheCodec
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
     * Hero live feeds (M2f, M7): weather, NWS alerts and game schedules. Nothing runs until the hero starts
     * [LiveFeeds.run] after its first frame. The location and the per-panel switches come from [FeedSettings] (set over
     * adb, never in source; read on the feeds' own threads). Last-good values are cached one file per panel in
     * no-backup storage, read with the 64 KiB cap. Lazy (AC8): the hero first touches it after the first frame, so the
     * feed classes and the panel cache serializers are not loaded and built on the main thread in AgendaActivity.onCreate.
     */
    private val feedsLazy = lazy {
        LiveFeeds(
        HttpFeedFetcher(),
        location = { FeedSettings.location(app) },
        switches = { FeedSettings.switches(app) },
        caches = { id -> JvmCacheFile(File(app.noBackupFilesDir, "panels/$id.json"), PanelCacheCodec.MAX_BYTES) },
        )
    }
    val feeds: LiveFeeds by feedsLazy

    /**
     * The feeds if this process already built them, else null, without building anything (safe on the main thread
     * before the first frame: a field read). The hero starts from their last snapshot when it comes back (Calendar,
     * Board, Ask, a recreated activity), so game day, the card and the Watch pill show as they were left instead of
     * an empty snapshot for the ~200 ms until its post-first-frame effect attaches (game-state-after-board).
     */
    val feedsIfBuilt: LiveFeeds? get() = if (feedsLazy.isInitialized()) feedsLazy.value else null

    /**
     * Ask Claude (M1b lean). The key is read from [secrets] per call and goes only into the request header. The daily
     * count is a date and a number in no-backup storage, never a question.
     */
    private val anthropic = AnthropicClient { secrets.get(SecretKey.ANTHROPIC_API_KEY) }
    val claude: ClaudeClient = anthropic
    val askCap = DailyCap(FileCountStore(File(app.noBackupFilesDir, "ask-count.txt")), clock = { Instant.now() }, zone = { ZoneId.systemDefault() })

    /**
     * continue-tile: the last few Ask Claude Open picks (title, app, deep link), app-private in no-backup storage,
     * never logged. Written off the main thread after a successful Open (full-screen Ask and the voice overlay).
     */
    val continuePicks = ContinuePicks(
        FileCountStore(File(app.noBackupFilesDir, "continue-picks.json"), maxBytes = ContinuePicks.MAX_BYTES),
    ) { System.currentTimeMillis() }
    private val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun recordOpen(target: OpenTarget) {
        background.launch { runCatching { continuePicks.record(target) } }
    }

    /** A fresh engine (and conversation) per visit to the Ask view; [apps] is read at each question. */
    fun newAskEngine(apps: () -> List<AskApp>): AskEngine =
        AskEngine(claude, hasKey = ::hasAnthropicKey, cap = askCap, apps = apps)

    private fun hasAnthropicKey(): Boolean = runCatching { secrets.get(SecretKey.ANTHROPIC_API_KEY) }.getOrNull() != null

    /**
     * The one-line daily brief (M7-6, ADR 0008 section 5). Same key and client as Ask, its own ledger and cache files in
     * no-backup storage (no titles in either). On by default; it calls only when a key exists and the switch is not
     * provisioned off. Lazy and touched only off the main thread (the hero reads it on IO, after the first frame).
     */
    val brief: BriefTicker by lazy {
        val dir = app.noBackupFilesDir
        val clock = { Instant.now() }
        BriefTicker(BriefEngine(
            anthropic,
            BriefLedger(FileCountStore(File(dir, "brief-ledger.txt")), clock, { ZoneId.systemDefault() }),
            FileCountStore(File(dir, "brief-cache.json"), maxBytes = BriefConfig.MAX_CACHE_BYTES),
            clock,
            enabled = { FeedSettings.switches(app).brief },
            hasKey = ::hasAnthropicKey,
        ))
    }
}

private fun isOnline(context: Context): Boolean {
    val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
    val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
    return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
}
