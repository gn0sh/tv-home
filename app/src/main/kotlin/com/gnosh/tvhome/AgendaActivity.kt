package com.gnosh.tvhome

import android.os.Bundle
import android.text.format.DateFormat
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import tvhome.core.ui.screenModel
import java.time.Instant
import java.time.ZoneId

/**
 * The agenda screen. Refresh runs only while STARTED: `repeatOnLifecycle` starts [com.gnosh.tvhome.AppGraph.session]
 * on ON_START (fetch now, then every 15 minutes) and cancels it on ON_STOP, which cancels the fetch and parse.
 * Back exits (nothing handles it). No cache write happens here.
 */
class AgendaActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val session = (application as TvHomeApp).graph.session

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                session.runWhileStarted()
            }
        }

        setContent {
            val state by session.state.collectAsStateWithLifecycle()
            val is24h = DateFormat.is24HourFormat(LocalContext.current)
            val model = remember(state, is24h) { screenModel(state, Instant.now(), ZoneId.systemDefault(), is24h) }
            AgendaScreen(model)
        }
    }
}
