package tvhome.core.assistant

/**
 * voice-overlay (ADR 0002 "Voice overlay (2026-11-04)"): the mic button's background Ask. A small corner card over
 * whatever is playing: listening (pulsing icon, partial text), then sending at once on the final result (no Send
 * step), then the answer or one short error line, which fades on its own. Back ends it; Open is the only thing that
 * launches anything (decision 3). Pure: the app feeds it speech events, the [AskOutcome] and the clock, and draws
 * [state]. Text is personal data: no toString here prints it.
 */
enum class VoiceError { NO_KEY, CAP_REACHED, OFFLINE, KEY_REJECTED, REQUEST_REJECTED, BUSY, UNWORKABLE, NO_SPEECH, NO_RECOGNIZER, MIC_DENIED }

object VoiceOverlayCopy {
    const val LISTENING = "Listening…"
    const val SENDING = "Asking Claude…"

    /** One short line per error, for the same card. */
    fun line(e: VoiceError): String = when (e) {
        VoiceError.NO_KEY -> "Ask Claude isn't set up yet."
        VoiceError.CAP_REACHED -> "That's today's questions used up."
        VoiceError.OFFLINE -> "Can't reach Claude. Check the Wi-Fi."
        VoiceError.KEY_REJECTED -> "Claude rejected the key."
        VoiceError.REQUEST_REJECTED -> "Claude rejected the request."
        VoiceError.BUSY -> "Claude is busy. Try again in a minute."
        VoiceError.UNWORKABLE -> "Couldn't work that out."
        VoiceError.NO_SPEECH -> "Didn't catch that."
        VoiceError.NO_RECOGNIZER -> "Voice input isn't available."
        VoiceError.MIC_DENIED -> "Microphone not allowed for Ask Claude."
    }
}

sealed interface OverlayState {
    /** [partial]: the words heard so far (empty at first). */
    class Listening(val partial: String, val since: Long) : OverlayState {
        override fun toString() = "Listening(${partial.length} chars)"
    }

    class Sending(val question: String, val since: Long) : OverlayState {
        override fun toString() = "Sending(${question.length} chars)"
    }

    class Answer(val reply: AskReply, val shownAt: Long) : OverlayState {
        override fun toString() = "Answer($reply)"
    }

    data class Error(val error: VoiceError, val shownAt: Long) : OverlayState

    object Done : OverlayState { override fun toString() = "Done" }
}

/**
 * What the session must do when the state changes (overlay-mic-after-timeout): leaving Listening releases the
 * recognizer (no microphone after a timeout, an error, Back or the answer), leaving Sending cancels the request.
 */
enum class OverlayEffect { RELEASE_RECOGNIZER, CANCEL_REQUEST }

class VoiceOverlay(
    private val now: () -> Long,
    /** Run for every state change, before the new state is drawn. */
    private val effects: (OverlayEffect) -> Unit = {},
    val listenTimeoutMs: Long = LISTEN_TIMEOUT_MS,
    val sendTimeoutMs: Long = SEND_TIMEOUT_MS,
    val answerMs: Long = ANSWER_MS,
    val errorMs: Long = ERROR_MS,
) {
    var state: OverlayState = OverlayState.Listening("", now())
        private set(next) {
            val changed = field::class != next::class
            field = next
            // On each change of step: every step but Listening holds no recognizer, every step but Sending runs no
            // request (leaving Listening also cancels the setup work: key check, app list).
            if (changed && next !is OverlayState.Listening) effects(OverlayEffect.RELEASE_RECOGNIZER)
            if (changed && next !is OverlayState.Sending) effects(OverlayEffect.CANCEL_REQUEST)
        }

    val isDone: Boolean get() = state === OverlayState.Done

    /** An error before listening starts (no key, no recognizer, mic denied). */
    fun fail(error: VoiceError) {
        if (state is OverlayState.Listening || state is OverlayState.Sending) state = OverlayState.Error(error, now())
    }

    fun partial(text: String?) {
        val s = state as? OverlayState.Listening ?: return
        state = OverlayState.Listening(cleanQuestion(text.orEmpty()), s.since)
    }

    /** The final transcript. Returns the question to send now, or null (nothing heard: the card says so). */
    fun final(text: String?): String? {
        if (state !is OverlayState.Listening) return null
        val q = cleanQuestion(text.orEmpty())
        if (q.isEmpty()) {
            state = OverlayState.Error(VoiceError.NO_SPEECH, now())
            return null
        }
        state = OverlayState.Sending(q, now())
        return q
    }

    /** A SpeechRecognizer error code (android.speech.SpeechRecognizer.ERROR_*). */
    fun speechError(code: Int) {
        if (state is OverlayState.Listening) state = OverlayState.Error(speechErrorOf(code), now())
    }

    fun outcome(o: AskOutcome) {
        if (state !is OverlayState.Sending) return
        state = when (o) {
            is AskOutcome.Answer -> OverlayState.Answer(o.reply, now())
            else -> OverlayState.Error(errorOf(o), now())
        }
    }

    /** Back: ends the session from any state. */
    fun back() { state = OverlayState.Done }

    /** answer-fade: a key on the card, or focus moving to Open, restarts the answer's fade. */
    fun keepShowing() {
        val s = state as? OverlayState.Answer ?: return
        state = OverlayState.Answer(s.reply, now())
    }

    /** Open was pressed and the app launched. */
    fun opened() { if (state is OverlayState.Answer) state = OverlayState.Done }

    /** When [tick] next needs to run, or null when nothing is pending. */
    fun deadline(): Long? = when (val s = state) {
        is OverlayState.Listening -> s.since + listenTimeoutMs
        is OverlayState.Sending -> s.since + sendTimeoutMs
        is OverlayState.Answer -> s.shownAt + answerMs
        is OverlayState.Error -> s.shownAt + errorMs
        OverlayState.Done -> null
    }

    /** Applies timeouts and the fade. Returns true if the state changed. */
    fun tick(): Boolean {
        val d = deadline() ?: return false
        if (now() < d) return false
        state = when (state) {
            is OverlayState.Listening -> OverlayState.Error(VoiceError.NO_SPEECH, now())
            is OverlayState.Sending -> OverlayState.Error(VoiceError.OFFLINE, now())
            else -> OverlayState.Done
        }
        return true
    }

    override fun toString() = "VoiceOverlay($state)"

    companion object {
        /** No final result this long after the mic opened: "Didn't catch that". */
        const val LISTEN_TIMEOUT_MS = 20_000L
        /** Longer than the client's own timeouts, so this only catches a stuck call. */
        const val SEND_TIMEOUT_MS = 90_000L
        /** The answer card fades after this. */
        const val ANSWER_MS = 20_000L
        const val ERROR_MS = 8_000L

        // android.speech.SpeechRecognizer error codes (stable public API values).
        fun speechErrorOf(code: Int): VoiceError = when (code) {
            1, 2, 4, 11 -> VoiceError.OFFLINE // network timeout, network, server, server disconnected
            6, 7 -> VoiceError.NO_SPEECH // speech timeout, no match
            9 -> VoiceError.MIC_DENIED // insufficient permissions
            else -> VoiceError.NO_RECOGNIZER // audio, client, busy, too many requests, language
        }

        fun errorOf(o: AskOutcome): VoiceError = when (o) {
            AskOutcome.NotSetUp -> VoiceError.NO_KEY
            AskOutcome.CapReached -> VoiceError.CAP_REACHED
            AskOutcome.CantReach -> VoiceError.OFFLINE
            AskOutcome.KeyRejected -> VoiceError.KEY_REJECTED
            AskOutcome.RequestRejected -> VoiceError.REQUEST_REJECTED
            AskOutcome.Busy -> VoiceError.BUSY
            AskOutcome.EmptyQuestion -> VoiceError.NO_SPEECH
            AskOutcome.Unworkable, AskOutcome.FollowUpUsed, is AskOutcome.Answer -> VoiceError.UNWORKABLE
        }
    }
}
