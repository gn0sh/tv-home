package com.gnosh.tvhome

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.widget.TextView

/**
 * qa build only. Calls the TV's default SpeechRecognizer, which is ClaudeRecognitionService while the app holds the
 * assistant role, and shows the outcome as "recognizer-probe: <state>" for emulator-qa.sh to read from the UI dump:
 * ready, partial, results, error <code>, or none. ERROR_CLIENT (5) or ERROR_INSUFFICIENT_PERMISSIONS (9) means the
 * forwarding failed; anything else means Google's recognizer accepted the forwarded request. No audio is kept.
 */
class RecognizerProbeActivity : Activity() {
    private var recognizer: SpeechRecognizer? = null
    private lateinit var view: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        view = TextView(this).apply { textSize = 32f }
        setContentView(view)
        show("pending")
        val r = runCatching { SpeechRecognizer.createSpeechRecognizer(this) }.getOrNull() ?: return show("none")
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = show("ready")
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onError(error: Int) = show("error $error")
            override fun onResults(results: Bundle?) = show("results")
            override fun onPartialResults(partialResults: Bundle?) = show("partial")
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        runCatching {
            r.startListening(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName),
            )
        }.onFailure { show("error start") }
    }

    override fun onDestroy() {
        recognizer?.let { runCatching { it.destroy() } }
        recognizer = null
        super.onDestroy()
    }

    private fun show(state: String) {
        view.text = "recognizer-probe: $state"
    }
}
