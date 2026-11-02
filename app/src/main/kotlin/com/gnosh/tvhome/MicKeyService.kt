package com.gnosh.tvhome

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * M3 Home and mic buttons: a key-filter-only accessibility service (res/xml/mic_key_filter.xml; no window content).
 * The remote's Home key shows the dashboard's home row, and its assist, voice and search keys open Ask Claude,
 * listening, from any app. Those keys are consumed, so the TV's own launcher and Google Assistant do not open as well.
 * Every other key returns false at once: nothing is buffered, read further or logged.
 */
class MicKeyService : AccessibilityService() {
    override fun onKeyEvent(event: KeyEvent): Boolean {
        val home = event.keyCode == KeyEvent.KEYCODE_HOME
        if (!home && event.keyCode !in MIC_KEYS) return false
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            runCatching {
                val open = Intent(this, AgendaActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(if (home) open.setAction(AgendaActivity.ACTION_HOME) else open.setAction(AgendaActivity.ACTION_ASK))
            }
        }
        return true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    private companion object {
        val MIC_KEYS = setOf(KeyEvent.KEYCODE_ASSIST, KeyEvent.KEYCODE_VOICE_ASSIST, KeyEvent.KEYCODE_SEARCH)
    }
}
