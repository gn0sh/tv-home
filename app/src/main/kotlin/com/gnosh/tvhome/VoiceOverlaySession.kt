package com.gnosh.tvhome

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tvhome.core.assistant.AskApp
import tvhome.core.assistant.AskEngine
import tvhome.core.assistant.OverlayState
import tvhome.core.assistant.VoiceError
import tvhome.core.assistant.VoiceOverlay
import tvhome.core.assistant.VoiceOverlayCopy
import tvhome.core.assistant.askAppsOf

/** On the overlay panel, for TalkBack and for the emulator QA check (scripts/ci/emulator-qa.sh). */
const val VOICE_OVERLAY_DESC = "Ask Claude overlay"

/**
 * voice-overlay (ADR 0002 "Voice overlay (2026-11-04)"): the mic button's session. With RECORD_AUDIO granted it draws a
 * small card in the bottom-right corner of the session's own window (the system draws it over the current app; no
 * SYSTEM_ALERT_WINDOW), listens with Google's recognizer directly, sends the final transcript at once through the same
 * [AskEngine] as the full-screen view (key, daily cap, errors, no logging), and shows the answer, which fades. Open is
 * the only thing that launches anything. Without RECORD_AUDIO it falls back to the full-screen Ask view, as before.
 * The window is sized to the card, so the rest of the screen takes no input and playback keeps going. The session never
 * receives screen content (disabledShowContext). The logic is [VoiceOverlay] in :core; this class only wires it.
 */
internal class VoiceOverlaySession(service: VoiceInteractionSessionService) : VoiceInteractionSession(service) {
    private val main = Handler(Looper.getMainLooper())
    private val scope = MainScope()
    private var overlayMode = false
    private var machine: VoiceOverlay? = null
    private var engine: AskEngine? = null
    private var apps: List<AskApp> = emptyList()
    private var recognizer: SpeechRecognizer? = null
    private var job: Job? = null
    private var views: OverlayViews? = null
    private val tick = Runnable { machine?.let { if (it.tick()) render(it) else schedule(it) } }

    override fun onCreate() {
        super.onCreate()
        disabledShowContext = SHOW_WITH_ASSIST or SHOW_WITH_SCREENSHOT // assist-context-collected
        window?.window?.let { w ->
            // The window is only as big as the card, in the bottom-right corner; nothing else of the screen is covered.
            w.setLayout(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            w.setGravity(Gravity.BOTTOM or Gravity.END)
            w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            w.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
            w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }
    }

    /** Decided per press, before the window would show: overlay with the microphone, full-screen Ask without. */
    override fun onPrepareShow(args: Bundle?, showFlags: Int) {
        super.onPrepareShow(args, showFlags)
        overlayMode = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        setUiEnabled(overlayMode) // mic-session-window: no window at all on the full-screen fallback
    }

    override fun onCreateContentView(): View = OverlayViews(context).also { views = it }.root

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        stop()
        if (!overlayMode) {
            openFullScreen()
            main.postDelayed({ runCatching { hide() } }, HIDE_AFTER_MS)
            return
        }
        val m = VoiceOverlay({ SystemClock.elapsedRealtime() })
        machine = m
        render(m)
        job = scope.launch {
            val ctx = context
            apps = withContext(Dispatchers.IO) { runCatching { askAppsOf(InstalledApps.launcherApps(ctx)) }.getOrDefault(emptyList()) }
            val e = (ctx.applicationContext as TvHomeApp).graph.newAskEngine { apps }
            engine = e
            if (machine !== m) return@launch
            if (!e.checkKey()) return@launch fail(m, VoiceError.NO_KEY)
            val target = googleRecognizer(ctx) ?: return@launch fail(m, VoiceError.NO_RECOGNIZER)
            listen(m, target)
        }
    }

    private fun fail(m: VoiceOverlay, error: VoiceError) {
        if (machine !== m) return
        m.fail(error)
        render(m)
    }

    private fun listen(m: VoiceOverlay, target: ComponentName) {
        val r = runCatching { SpeechRecognizer.createSpeechRecognizer(context, target) }.getOrNull()
            ?: return fail(m, VoiceError.NO_RECOGNIZER)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit

            override fun onPartialResults(partialResults: Bundle?) {
                if (machine !== m) return
                m.partial(first(partialResults))
                render(m)
            }

            override fun onResults(results: Bundle?) {
                if (machine !== m) return
                val question = m.final(first(results))
                releaseRecognizer()
                render(m)
                if (question != null) send(m, question)
            }

            override fun onError(error: Int) {
                if (machine !== m) return
                m.speechError(error)
                releaseRecognizer()
                render(m)
            }
        })
        runCatching {
            r.startListening(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName),
            )
        }.onFailure {
            releaseRecognizer()
            fail(m, VoiceError.NO_RECOGNIZER)
        }
    }

    /** Straight to Claude on the final result: no Send step (Brian's ask). Same engine, so the same limits. */
    private fun send(m: VoiceOverlay, question: String) {
        val e = engine ?: return fail(m, VoiceError.UNWORKABLE)
        job = scope.launch {
            val outcome = e.ask(question)
            if (machine !== m) return@launch
            m.outcome(outcome)
            render(m)
        }
    }

    private fun render(m: VoiceOverlay) {
        if (machine !== m) return
        val v = views
        when (val s = m.state) {
            is OverlayState.Listening -> v?.show(status = s.partial.ifEmpty { VoiceOverlayCopy.LISTENING }, body = null, pulsing = true, wide = false)
            is OverlayState.Sending -> v?.show(status = VoiceOverlayCopy.SENDING, body = s.question, pulsing = true, wide = false)
            is OverlayState.Answer -> {
                v?.show(status = null, body = s.reply.answer, pulsing = false, wide = true)
                val open = s.reply.open
                val plan = open?.let { runCatching { InstalledApps.planOpen(context, it) }.getOrNull() }
                if (open != null && plan != null) {
                    v?.showOpen("Open in ${open.app.label}") {
                        if (plan.launch()) {
                            m.opened()
                            render(m)
                        } else {
                            v?.show(status = "Couldn't open ${open.app.label}.", body = s.reply.answer, pulsing = false, wide = true)
                        }
                    }
                }
            }
            is OverlayState.Error -> v?.show(status = VoiceOverlayCopy.line(s.error), body = null, pulsing = false, wide = true)
            OverlayState.Done -> {
                stop()
                runCatching { hide() }
                return
            }
        }
        schedule(m)
    }

    private fun schedule(m: VoiceOverlay) {
        main.removeCallbacks(tick)
        val d = m.deadline() ?: return
        main.postDelayed(tick, (d - SystemClock.elapsedRealtime()).coerceAtLeast(0L) + 50L)
    }

    /** Back: ends the session from any state (the card goes, playback underneath carries on). */
    override fun onBackPressed() {
        machine?.back()
        stop()
        runCatching { hide() }
    }

    override fun onHide() {
        stop()
        super.onHide()
    }

    override fun onDestroy() {
        stop()
        scope.cancel()
        super.onDestroy()
    }

    private fun stop() {
        main.removeCallbacksAndMessages(null)
        job?.cancel()
        job = null
        releaseRecognizer()
        machine = null
        engine = null
        views?.reset()
    }

    private fun releaseRecognizer() {
        recognizer?.let { runCatching { it.cancel() }; runCatching { it.destroy() } }
        recognizer = null
    }

    private fun openFullScreen() {
        // The assistant's own start path first; the plain start as a fallback (the bound voice service may start it).
        val opened = runCatching { startAssistantActivity(Intent(context, AssistActivity::class.java)); true }.getOrDefault(false)
        if (!opened) {
            runCatching {
                context.startActivity(
                    Intent(context, AgendaActivity::class.java).setAction(AgendaActivity.ACTION_ASK).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
    }

    private fun first(b: Bundle?): String? = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()

    private companion object {
        const val HIDE_AFTER_MS = 1_500L
    }
}

/**
 * The corner card, plain views (no Compose in the session window). Listening: about 15% of the screen width, a
 * pulsing Claude mark and the words heard so far. Answer or error: wider (about 28%) for reading, the text, and Open
 * when Claude named an app.
 */
private class OverlayViews(private val context: Context) {
    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, context.resources.displayMetrics).toInt()
    private val screenW = context.resources.displayMetrics.widthPixels

    private val mark = TextView(context).apply {
        text = "✦"
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(CLAUDE_ORANGE) }
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val status = TextView(context).apply {
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.START // the newest words stay visible
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }
    private val body = TextView(context).apply {
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        setLineSpacing(0f, 1.15f)
        maxLines = 10
        ellipsize = TextUtils.TruncateAt.END
        visibility = View.GONE
    }
    private val open = Button(context).apply { visibility = View.GONE; isAllCaps = false }
    private var pulse: ValueAnimator? = null

    val panel = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply { cornerRadius = dp(16f).toFloat(); setColor(PANEL_BG) }
        setPadding(dp(14f), dp(12f), dp(14f), dp(12f))
        contentDescription = VOICE_OVERLAY_DESC
        isFocusable = true
        isFocusableInTouchMode = true
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(mark, LinearLayout.LayoutParams(dp(32f), dp(32f)))
            addView(status, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(10f) })
        }
        addView(header)
        addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8f) })
        addView(open, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8f) })
    }

    val root = FrameLayout(context).apply {
        setPadding(dp(24f), dp(24f), dp(24f), dp(24f))
        addView(panel, FrameLayout.LayoutParams((screenW * 0.15f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END))
    }

    fun show(status: String?, body: String?, pulsing: Boolean, wide: Boolean) {
        panel.layoutParams = (panel.layoutParams as FrameLayout.LayoutParams).apply {
            width = (screenW * if (wide) 0.28f else 0.15f).toInt()
        }
        this.status.text = status.orEmpty()
        this.status.visibility = if (status == null) View.GONE else View.VISIBLE
        this.body.text = body.orEmpty()
        this.body.visibility = if (body == null) View.GONE else View.VISIBLE
        open.visibility = View.GONE
        open.setOnClickListener(null)
        if (pulsing) startPulse() else stopPulse()
        panel.requestFocus()
    }

    fun showOpen(label: String, onOpen: () -> Unit) {
        open.text = label
        open.visibility = View.VISIBLE
        open.setOnClickListener { onOpen() }
        open.requestFocus()
    }

    fun reset() {
        stopPulse()
        open.visibility = View.GONE
        open.setOnClickListener(null)
    }

    private fun startPulse() {
        if (pulse?.isRunning == true) return
        pulse = ObjectAnimator.ofPropertyValuesHolder(
            mark,
            PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.18f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.18f),
            PropertyValuesHolder.ofFloat(View.ALPHA, 1f, 0.6f),
        ).apply {
            duration = 700L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            start()
        }
    }

    private fun stopPulse() {
        pulse?.cancel()
        pulse = null
        mark.scaleX = 1f; mark.scaleY = 1f; mark.alpha = 1f
    }

    private companion object {
        val CLAUDE_ORANGE = Color.rgb(217, 119, 87)
        val PANEL_BG = Color.argb(235, 16, 20, 24)
    }
}
