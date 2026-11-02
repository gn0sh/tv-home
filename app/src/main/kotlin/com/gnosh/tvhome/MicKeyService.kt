package com.gnosh.tvhome

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * M3 mic button: a key-filter-only accessibility service (res/xml/mic_key_filter.xml; no window content). The remote's
 * assist, voice and search keys open Ask Claude, listening, from any app; the key is consumed so Google Assistant does
 * not open as well. Every other key returns false at once: nothing is buffered, read further or logged.
 */
class MicKeyService : AccessibilityService() {
    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode !in MIC_KEYS) return false
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            runCatching {
                startActivity(
                    Intent(this, AgendaActivity::class.java)
                        .setAction(AgendaActivity.ACTION_ASK)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
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
