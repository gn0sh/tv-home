package com.gnosh.tvhome

import android.content.ComponentName
import android.content.Context
import android.content.ContextParams
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import tvhome.core.assistant.RecognizerChoice

/**
 * mic-to-claude: the digital assistant services. With the app chosen as the TV's digital assistant (Settings > Apps >
 * Default apps > Digital assistant app; docs/user/tv-home-setup.md), the remote's mic button (ASSIST) shows a session
 * here, and the session shows the voice overlay over whatever is playing (ADR 0002 "Voice overlay"). No hotword, no always-on listening:
 * this service only routes the button.
 */
class ClaudeVoiceService : VoiceInteractionService() {
    override fun onReady() {
        super.onReady()
        // assist-context-collected: never receive the other app's view structure or a screenshot.
        disabledShowContext = VoiceInteractionSession.SHOW_WITH_ASSIST or VoiceInteractionSession.SHOW_WITH_SCREENSHOT
    }
}

/** One session per button press: the voice overlay (VoiceOverlaySession.kt), or the full-screen Ask as a fallback. */
class ClaudeSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = VoiceOverlaySession(this)
}

/**
 * Google's preinstalled recognizer (RecognizerChoice: system Google packages only, never ours), or null. Used by the
 * forwarding recognizer and by the voice overlay, which listens with it directly.
 */
internal fun googleRecognizer(context: Context): ComponentName? {
    val found = runCatching {
        context.packageManager.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), PackageManager.MATCH_ALL)
            .mapNotNull { it.serviceInfo }
            .map {
                val flags = it.applicationInfo?.flags ?: 0
                val system = (flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0
                RecognizerChoice.Candidate(it.packageName, it.name, system)
            }
    }.getOrDefault(emptyList())
    return RecognizerChoice.pick(found, context.packageName)?.let { ComponentName(it.pkg, it.cls) }
}

/**
 * Required for the assistant role, which also makes it the TV's default speech recognizer (stub-recognizer-default).
 * It recognizes nothing itself: each request is forwarded to Google's preinstalled recognizer (RecognizerChoice: system
 * Google packages only) and every callback is relayed back. The microphone is opened by that recognizer, not by us. Because the
 * permission check runs over the whole attribution chain (us, then the calling app), forwarding needs RECORD_AUDIO
 * granted to this app; without it the target answers ERROR_INSUFFICIENT_PERMISSIONS, which is relayed as is.
 * With no other recognizer installed, a client gets an immediate error, never a hang.
 */
class ClaudeRecognitionService : RecognitionService() {
    private var inner: SpeechRecognizer? = null
    private val main = Handler(Looper.getMainLooper())

    // mic-stop-leak: after a stop, give the target STOP_GRACE_MS to send results or an error, then let go of it.
    private val releaseAfterStop = Runnable { release() }

    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {
        listener ?: return
        release()
        val target = target()
        if (target == null || recognizerIntent == null) {
            runCatching { listener.error(SpeechRecognizer.ERROR_CLIENT) }
            return
        }
        // Chain the caller's attribution so the target's RECORD_AUDIO check covers the app that really asked.
        // attribution-fallback-open: never forward under our own attribution alone.
        val ctx = runCatching {
            createContext(ContextParams.Builder().setNextAttributionSource(listener.callingAttributionSource).build())
        }.getOrNull()
        if (ctx == null) {
            runCatching { listener.error(SpeechRecognizer.ERROR_CLIENT) }
            return
        }
        val r = runCatching { SpeechRecognizer.createSpeechRecognizer(ctx, target) }.getOrNull()
        if (r == null) {
            runCatching { listener.error(SpeechRecognizer.ERROR_CLIENT) }
            return
        }
        inner = r
        r.setRecognitionListener(Relay(listener))
        runCatching { r.startListening(recognizerIntent) }.onFailure {
            release()
            runCatching { listener.error(SpeechRecognizer.ERROR_CLIENT) }
        }
    }

    override fun onStopListening(listener: Callback?) {
        runCatching { inner?.stopListening() }
        main.removeCallbacks(releaseAfterStop)
        if (inner != null) main.postDelayed(releaseAfterStop, STOP_GRACE_MS)
    }

    override fun onCancel(listener: Callback?) {
        runCatching { inner?.cancel() }
        release()
    }

    override fun onDestroy() {
        release()
        super.onDestroy()
    }

    private fun release() {
        main.removeCallbacks(releaseAfterStop)
        inner?.let { runCatching { it.destroy() } }
        inner = null
    }

    private companion object {
        const val STOP_GRACE_MS = 5_000L
    }

    private fun target(): ComponentName? = googleRecognizer(this)

    /** Relays the target's callbacks to our caller. Raw audio buffers are not relayed (we never handle audio). */
    private inner class Relay(private val cb: Callback) : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) { runCatching { cb.readyForSpeech(params ?: Bundle()) } }
        override fun onBeginningOfSpeech() { runCatching { cb.beginningOfSpeech() } }
        override fun onRmsChanged(rmsdB: Float) { runCatching { cb.rmsChanged(rmsdB) } }
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() { runCatching { cb.endOfSpeech() } }
        override fun onError(error: Int) { runCatching { cb.error(error) }; release() }
        override fun onResults(results: Bundle?) { runCatching { cb.results(results ?: Bundle()) }; release() }
        override fun onPartialResults(partialResults: Bundle?) { runCatching { cb.partialResults(partialResults ?: Bundle()) } }
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
        override fun onSegmentResults(segmentResults: Bundle) {
            if (Build.VERSION.SDK_INT >= 33) runCatching { cb.segmentResults(segmentResults) }
        }
        override fun onEndOfSegmentedSession() {
            if (Build.VERSION.SDK_INT >= 33) runCatching { cb.endOfSegmentedSession() }
            release()
        }
        override fun onLanguageDetection(results: Bundle) {
            if (Build.VERSION.SDK_INT >= 34) runCatching { cb.languageDetection(results) }
        }
    }
}
