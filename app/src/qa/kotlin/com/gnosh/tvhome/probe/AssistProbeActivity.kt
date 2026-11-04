package com.gnosh.tvhome.probe

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.widget.TextView

/** assist-probe: shows and logs which intent opened it. */
class AssistProbeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "activity opened by action=${intent?.action}")
        setContentView(TextView(this).apply { text = "Assist probe: ${intent?.action}"; textSize = 40f })
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        Log.i(TAG, "activity new intent action=${intent.action}")
    }

    companion object { const val TAG = "AssistProbe" }
}
