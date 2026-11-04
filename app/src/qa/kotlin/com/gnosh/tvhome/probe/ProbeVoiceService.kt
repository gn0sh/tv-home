package com.gnosh.tvhome.probe

import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.speech.RecognitionService
import android.util.Log

/** assist-probe: the stub voice interaction service that qualifies the app for ROLE_ASSISTANT. */
class ProbeVoiceService : VoiceInteractionService() {
    override fun onReady() {
        super.onReady()
        Log.i(AssistProbeActivity.TAG, "voice service ready")
    }
}

class ProbeSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = object : VoiceInteractionSession(this) {
        override fun onShow(args: Bundle?, showFlags: Int) {
            super.onShow(args, showFlags)
            Log.i(AssistProbeActivity.TAG, "session onShow flags=$showFlags")
            runCatching {
                startAssistantActivity(Intent(context, AssistProbeActivity::class.java).setAction("probe.SESSION_SHOW"))
            }.onFailure { Log.i(AssistProbeActivity.TAG, "startAssistantActivity failed: $it") }
        }
    }
}

class ProbeRecognitionService : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {
        Log.i(AssistProbeActivity.TAG, "recognition start")
    }
    override fun onCancel(listener: Callback?) = Unit
    override fun onStopListening(listener: Callback?) = Unit
}
