package com.gnosh.tvhome

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.text.format.DateFormat
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tvhome.core.assistant.AskEngine
import tvhome.core.assistant.OpenTarget
import tvhome.core.assistant.askAppsOf
import tvhome.core.ui.HomeTheme
import tvhome.core.ui.calendarTile
import tvhome.core.ui.screenModel
import java.time.Instant
import java.time.ZoneId

/**
 * The home screen (M2c): the app carousel, and below it a dock whose Calendar tile opens the full-screen agenda. Kept
 * under its M1 name so the launcher entry, CI checks and provisioning steps stay the same. It is a LEANBACK_LAUNCHER
 * entry only; becoming the default Home is M3.
 *
 * While STARTED: the app list is refreshed (so installs and removals show up on return; unchanged apps reuse their
 * cached tiles) in its own coroutine, and [com.gnosh.tvhome.AppGraph.session] refreshes the calendar (fetch now, then
 * every 15 minutes). The two never wait on each other, so calendar failures cannot block the tiles. Back on the row
 * exits; Back on the full-screen agenda returns to the row (HomeScreen).
 */
class AgendaActivity : ComponentActivity() {
    private var apps by mutableStateOf<AppsState>(AppsState.Loading)
    private var theme by mutableStateOf(HomeTheme.DEFAULT)
    private val installed = InstalledApps()
    private var layout by mutableStateOf(SavedLayout())
    private val layoutStore by lazy { AppLayoutStore(applicationContext) }
    private var entry by mutableStateOf(HomeEntry(0, null))

    /** M7-F6 Quiet mode, read on IO each time the screen starts (so an adb toggle applies); null until then. */
    private var quiet by mutableStateOf<Boolean?>(null)

    /** M3: [ACTION_ASK] opens Ask Claude, [ACTION_BOARD] the Board; anything else (Home, the launcher entry) shows the row. */
    private fun targetOf(intent: Intent?): String? = when (intent?.action) {
        ACTION_ASK -> HomeEntry.ASK
        ACTION_BOARD -> HomeEntry.BOARD
        else -> null
    }

    /**
     * Speech for Ask Claude: the system recognizer dialog (RecognizerIntent), so the app needs no RECORD_AUDIO
     * permission. The transcript goes only to [speechCallback]; it is never logged or stored.
     */
    private var speechCallback: ((String?) -> Unit)? = null
    private val speech = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val text = if (r.resultCode == RESULT_OK) {
            runCatching { r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull() }.getOrNull()
        } else {
            null
        }
        speechCallback?.invoke(text)
        speechCallback = null
    }

    private val askHost = object : AskHost {
        override fun newEngine(): AskEngine = (application as TvHomeApp).graph.newAskEngine {
            askAppsOf((apps as? AppsState.Loaded)?.apps.orEmpty().map { it.app })
        }

        override fun listen(onResult: (String?) -> Unit): Boolean = try {
            speechCallback = onResult
            speech.launch(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_PROMPT, "Ask Claude"),
            )
            true
        } catch (e: ActivityNotFoundException) {
            speechCallback = null
            false
        }

        override fun planOpen(target: OpenTarget): OpenPlan = InstalledApps.planOpen(this@AgendaActivity, target)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A recreated activity (savedInstanceState) keeps its own screen; only a fresh start honours the intent.
        if (savedInstanceState == null) targetOf(intent)?.let { entry = HomeEntry(1, it) }
        val session = (application as TvHomeApp).graph.session

        lifecycleScope.launch {
            theme = withContext(Dispatchers.IO) { InstalledApps.loadTheme(applicationContext) }
            layout = withContext(Dispatchers.IO) { runCatching { layoutStore.load() }.getOrNull() } ?: SavedLayout()
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { quiet = withContext(Dispatchers.IO) { FeedSettings.quiet(applicationContext) } }
                launch {
                    // Tiles are drawn on the design canvas (DesignCanvasBox), so size their bitmaps at its density.
                    val dm = resources.displayMetrics
                    val density = tvhome.core.ui.DesignCanvas.density(dm.widthPixels, dm.heightPixels)
                    val w = (theme.tileWidthDp * density).toInt()
                    val h = (theme.tileHeightDp * density).toInt()
                    val loaded = withContext(Dispatchers.IO) { runCatching { installed.load(applicationContext, w, h) }.getOrNull() }
                    val next = when {
                        loaded != null -> AppsState.Loaded(loaded)
                        apps is AppsState.Loaded -> apps // keep the last good row rather than blanking it
                        else -> AppsState.Failed
                    }
                    if (next != apps) apps = next
                }
                session.runWhileStarted()
            }
        }

        setContent {
            val state by session.state.collectAsStateWithLifecycle()
            val is24h = DateFormat.is24HourFormat(LocalContext.current)
            // The tile's "next event", the date and the agenda's Today/Tomorrow follow the clock, not only the 15-minute
            // refresh: re-pick once a minute, so the tile and the full screen agree after midnight.
            val minute by produceState(Instant.now().epochSecond / 60) {
                while (true) {
                    delay(60_000 - System.currentTimeMillis() % 60_000)
                    value = Instant.now().epochSecond / 60
                }
            }
            // Titles stay hidden until the stored Quiet value has loaded (null), so a guest never sees one flash. The same
            // value covers the tile and the full-screen calendar (quiet-calendar-fullscreen).
            val hideTitles = quiet != false
            val model = remember(state, is24h, minute, hideTitles) { screenModel(state, Instant.now(), ZoneId.systemDefault(), is24h, hideTitles) }
            val tile = remember(state, is24h, minute, hideTitles) { calendarTile(state, Instant.now(), ZoneId.systemDefault(), is24h, hideTitles) }
            DesignCanvasBox {
            HomeScreen(
                apps = apps,
                theme = theme,
                onLaunch = { app ->
                    if (!InstalledApps.launch(this@AgendaActivity, app)) {
                        Toast.makeText(this@AgendaActivity, "Couldn't open ${app.label}", Toast.LENGTH_SHORT).show()
                    }
                },
                tile = tile,
                agenda = model,
                ask = askHost,
                layout = layout,
                entry = entry,
                quiet = quiet,
                onQuietToggle = {
                    val q = quiet
                    if (q != null) { quiet = !q; FeedSettings.setQuiet(applicationContext, !q) } // prefs already loaded; apply() writes async
                },
                onLayoutChange = { next ->
                    // Unknown packages stay in the stored order and hidden set (layout-prune-transient): only display
                    // filters them. apply() updates the in-memory prefs at once and writes in call order, so saves
                    // cannot land out of order (layout-save-order); it is called on the main thread on purpose.
                    layout = next
                    runCatching { layoutStore.save(next) }
                },
            )
            }
        }
    }

    /** singleTask: Home and the launcher entry arrive here while the activity exists. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        entry = HomeEntry(entry.seq + 1, targetOf(intent))
    }

    companion object {
        /** Opens Ask Claude, listening (the PC remote or adb; the mic key cannot be caught, PR #15). Any app could send it; it only shows the Ask view. */
        const val ACTION_ASK = "com.gnosh.tvhome.action.ASK"

        /**
         * Opens the Board (adb and the emulator screenshot tour). Like [ACTION_ASK] any app could send it; it only shows
         * the Board, with Quiet applied (titles hidden until the stored Quiet value has loaded).
         */
        const val ACTION_BOARD = "com.gnosh.tvhome.action.BOARD"

        /** Shows the home row, focus on the first tile (sent by [HomeWatchService] when the TV's own home comes up). */
        const val ACTION_HOME = "com.gnosh.tvhome.action.HOME"
    }
}
