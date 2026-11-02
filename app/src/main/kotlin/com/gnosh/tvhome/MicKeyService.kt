package com.gnosh.tvhome

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * M3 Home and mic buttons: a key-filter-only accessibility service (res/xml/mic_key_filter.xml; no window content).
 * The remote's Home key shows the dashboard's home row, and its assist, voice and search keys open Ask Claude,
 * listening, from any app. Those keys are consumed, so the TV's own launcher and Google Assistant do not open as well.
 * Every other key returns false at once: nothing is buffered, read further or logged.
 *
 * Holding Home opens the TV's Settings (home-recovery-unreachable): with Home taken, the stock launcher's gear and its
 * long-press panel are out of reach, and Settings > System > Accessibility is where this service is turned off.
 * If the dashboard cannot be started, Home is passed to the system instead (home-dead-on-start-failure).
 */
class MicKeyService : AccessibilityService() {
    /** True while the current Home press is being passed to the system (its start failed). */
    private var passHome = false
    private val main by lazy { Handler(Looper.getMainLooper()) }
    private val openSettings = Runnable {
        runCatching { startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val home = event.keyCode == KeyEvent.KEYCODE_HOME
        if (!home && event.keyCode !in MIC_KEYS) return false
        if (home && passHome) {
            if (event.action == KeyEvent.ACTION_UP) passHome = false
            return false
        }
        if (event.action == KeyEvent.ACTION_UP) { if (home) main.removeCallbacks(openSettings); return true }
        if (event.action != KeyEvent.ACTION_DOWN) return true
        if (event.repeatCount == 0) {
            val open = Intent(this, AgendaActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .setAction(if (home) AgendaActivity.ACTION_HOME else AgendaActivity.ACTION_ASK)
            val started = runCatching { startActivity(open) }.isSuccess
            if (home && !started) { passHome = true; return false }
            // A held key's repeats are made after this filter, so it may never see them: a timer finds the hold
            // (hold-path-untested: this is also the path CI's injected long press takes; repeats are ignored).
            if (home) { main.removeCallbacks(openSettings); main.postDelayed(openSettings, HOLD_MS) }
        }
        return true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onDestroy() { main.removeCallbacks(openSettings); super.onDestroy() }

    private companion object {
        val MIC_KEYS = setOf(KeyEvent.KEYCODE_ASSIST, KeyEvent.KEYCODE_VOICE_ASSIST, KeyEvent.KEYCODE_SEARCH)

        /**
         * How long Home must be held to open Settings: past a normal press (about 0.1-0.2 s), and under the 0.4 s an
         * injected long press (`input keyevent --longpress`, the system long-press timeout) holds, so CI covers it.
         */
        const val HOLD_MS = 330L
    }
}
