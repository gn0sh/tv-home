package com.gnosh.tvhome

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.speech.RecognitionService
import android.speech.SpeechRecognizer

/**
 * mic-to-claude: the digital assistant services. With the app chosen as the TV's digital assistant (Settings > Apps >
 * Default apps > Digital assistant app; docs/user/tv-home-setup.md), the remote's mic button (ASSIST) shows a session
 * here, and the session opens Ask Claude, listening (ADR 0002's recognizer path). No hotword, no always-on listening,
 * no audio of our own: this service only routes the button.
 */
class ClaudeVoiceService : VoiceInteractionService()

/** One session per button press; it has no window of its own. */
class ClaudeSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = AskSession(this)

    private class AskSession(service: VoiceInteractionSessionService) : VoiceInteractionSession(service) {
        private val main = Handler(Looper.getMainLooper())

        override fun onShow(args: Bundle?, showFlags: Int) {
            super.onShow(args, showFlags)
            // The assistant's own start path first; the plain start as a fallback (the bound voice service may start it).
            val opened = runCatching { startAssistantActivity(Intent(context, AssistActivity::class.java)); true }.getOrDefault(false)
            if (!opened) {
                runCatching {
                    context.startActivity(
                        Intent(context, AgendaActivity::class.java).setAction(AgendaActivity.ACTION_ASK).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            }
            // Nothing to show here: close the session once the Ask screen has had time to come up.
            main.postDelayed({ runCatching { hide() } }, HIDE_AFTER_MS)
        }

        override fun onHide() {
            main.removeCallbacksAndMessages(null)
            super.onHide()
        }
    }

    private companion object {
        const val HIDE_AFTER_MS = 1_500L
    }
}

/**
 * Required for the assistant role (a voice interaction service must name a recognition service). It recognizes
 * nothing: the app holds no RECORD_AUDIO, and Ask Claude listens through the system recognizer dialog instead. A
 * client that binds it gets an immediate error, never a hang.
 */
class ClaudeRecognitionService : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {
        runCatching { listener?.error(SpeechRecognizer.ERROR_CLIENT) }
    }

    override fun onCancel(listener: Callback?) = Unit

    override fun onStopListening(listener: Callback?) = Unit
}
