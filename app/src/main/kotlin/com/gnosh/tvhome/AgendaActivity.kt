package com.gnosh.tvhome

import android.os.Bundle
import android.text.format.DateFormat
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import tvhome.core.ui.HomeTheme
import tvhome.core.ui.calendarTile
import tvhome.core.ui.screenModel
import java.time.Instant
import java.time.ZoneId

/**
 * The home screen (M2b): a row of tiles, the Calendar tile first, which opens the full-screen agenda. Kept under its
 * M1 name so the launcher entry, CI checks and provisioning steps stay the same. It is a LEANBACK_LAUNCHER entry only; becoming the default Home is M3.
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val session = (application as TvHomeApp).graph.session

        lifecycleScope.launch {
            theme = withContext(Dispatchers.IO) { InstalledApps.loadTheme(applicationContext) }
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    val density = resources.displayMetrics.density
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
            val model = remember(state, is24h, minute) { screenModel(state, Instant.now(), ZoneId.systemDefault(), is24h) }
            val tile = remember(state, is24h, minute) { calendarTile(state, Instant.now(), ZoneId.systemDefault(), is24h) }
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
            )
        }
    }
}
