package com.gnosh.tvhome

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tvhome.core.secrets.Provision
import tvhome.core.ui.Tokens

/**
 * Started from adb only: `am start -n com.gnosh.tvhome/.ProvisionActivity --es calendar_url '<value>'` (or
 * `--es clear calendar_url`). The manifest gives it no intent-filter, `excludeFromRecents`, `noHistory` and the DUMP
 * permission. The value is read once, the stored intent is replaced (`#10`), it is re-validated by the same regex as the
 * script (`#3`), and the screen shows a fixed sentence, never the value. It then removes its task.
 */
class ProvisionActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val received: Intent? = intent
        val cmd = Provision.fromExtras { name -> runCatching { received?.getStringExtra(name) }.getOrNull() }
        setIntent(Intent()) // drop the extras from this activity's copy of the intent
        val outcome = Provision.apply((application as TvHomeApp).graph.secrets, cmd)

        setContent {
            Box(Modifier.fillMaxSize().background(color(Tokens.BACKGROUND)), contentAlignment = Alignment.Center) {
                Text(outcome.message, color = color(Tokens.TEXT), fontSize = Tokens.HEADING_SP.sp)
            }
        }
        lifecycleScope.launch {
            delay(SHOW_MS)
            finishAndRemoveTask()
        }
    }

    private companion object {
        const val SHOW_MS = 1500L
    }
}
