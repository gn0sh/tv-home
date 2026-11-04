package com.gnosh.tvhome.probe

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/** assist-probe: logs every key event the accessibility key filter receives, and passes it on. */
class KeyProbeService : AccessibilityService() {
    override fun onServiceConnected() {
        Log.i(TAG, "key filter connected")
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        Log.i(TAG, "key ${KeyEvent.keyCodeToString(event.keyCode)} action=${event.action} repeat=${event.repeatCount} " +
            "longPress=${event.isLongPress}")
        return false
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    companion object { const val TAG = "KeyProbe" }
}
