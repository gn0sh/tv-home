package tvhome.core.assistant

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import tvhome.core.runSuspend
import java.time.Instant
import java.time.ZoneId

class VoiceOverlayTest {
    private var t = 1_000L
    private fun overlay() = VoiceOverlay({ t })
    private val apps = listOf(AskApp("YouTube", "com.google.android.youtube.tv"))
    private val reply = AskReply("Try Planet Earth.", null)

    @Test fun startsListeningAndShowsPartialText() {
        val o = overlay()
        assertTrue(o.state is OverlayState.Listening)
        o.partial("what's on\u0007 tonight")
        assertEquals("what's on tonight", (o.state as OverlayState.Listening).partial)
    }

    @Test fun finalResultSendsAtOnceThenShowsTheAnswer() {
        val o = overlay()
        assertEquals("what's on", o.final("  what's on "))
        assertTrue(o.state is OverlayState.Sending)
        o.outcome(AskOutcome.Answer(reply, canFollowUp = true))
        assertEquals(reply, (o.state as OverlayState.Answer).reply)
    }

    @Test fun emptyFinalIsNoSpeechAndNothingIsSent() {
        val o = overlay()
        assertNull(o.final("   "))
        assertEquals(VoiceError.NO_SPEECH, (o.state as OverlayState.Error).error)
        o.outcome(AskOutcome.Answer(reply, true)) // a stray outcome changes nothing
        assertTrue(o.state is OverlayState.Error)
    }

    @Test fun answerFadesAfterTwentySeconds() {
        val o = overlay()
        o.final("q"); o.outcome(AskOutcome.Answer(reply, true))
        assertEquals(t + 20_000, o.deadline())
        t += 19_999; assertFalse(o.tick()); assertTrue(o.state is OverlayState.Answer)
        t += 1; assertTrue(o.tick()); assertTrue(o.isDone)
        assertNull(o.deadline())
    }

    @Test fun errorLineFadesSooner() {
        val o = overlay()
        o.fail(VoiceError.NO_KEY)
        t += VoiceOverlay.ERROR_MS; assertTrue(o.tick()); assertTrue(o.isDone)
    }

    @Test fun listeningTimesOutAsNoSpeechAndAStuckSendAsOffline() {
        val a = overlay()
        t += VoiceOverlay.LISTEN_TIMEOUT_MS; assertTrue(a.tick())
        assertEquals(VoiceError.NO_SPEECH, (a.state as OverlayState.Error).error)
        val b = overlay()
        b.final("q"); t += VoiceOverlay.SEND_TIMEOUT_MS; assertTrue(b.tick())
        assertEquals(VoiceError.OFFLINE, (b.state as OverlayState.Error).error)
        b.outcome(AskOutcome.Answer(reply, true)) // too late: ignored
        assertTrue(b.state is OverlayState.Error)
    }

    @Test fun backEndsFromAnyStateAndOpenEndsOnlyAnAnswer() {
        val o = overlay(); o.back(); assertTrue(o.isDone)
        val p = overlay(); p.opened(); assertTrue(p.state is OverlayState.Listening)
        p.final("q"); p.outcome(AskOutcome.Answer(reply, true)); p.opened(); assertTrue(p.isDone)
    }

    @Test fun speechErrorCodesMapToOneLine() {
        assertEquals(VoiceError.OFFLINE, VoiceOverlay.speechErrorOf(2))
        assertEquals(VoiceError.NO_SPEECH, VoiceOverlay.speechErrorOf(7))
        assertEquals(VoiceError.NO_SPEECH, VoiceOverlay.speechErrorOf(6))
        assertEquals(VoiceError.MIC_DENIED, VoiceOverlay.speechErrorOf(9))
        assertEquals(VoiceError.NO_RECOGNIZER, VoiceOverlay.speechErrorOf(5))
        val o = overlay(); o.speechError(9)
        assertEquals(VoiceError.MIC_DENIED, (o.state as OverlayState.Error).error)
        VoiceError.entries.forEach { assertTrue(VoiceOverlayCopy.line(it).length in 1..48, it.name) }
    }

    @Test fun everyAskOutcomeMapsToAnError() {
        assertEquals(VoiceError.NO_KEY, VoiceOverlay.errorOf(AskOutcome.NotSetUp))
        assertEquals(VoiceError.CAP_REACHED, VoiceOverlay.errorOf(AskOutcome.CapReached))
        assertEquals(VoiceError.OFFLINE, VoiceOverlay.errorOf(AskOutcome.CantReach))
        assertEquals(VoiceError.BUSY, VoiceOverlay.errorOf(AskOutcome.Busy))
        assertEquals(VoiceError.KEY_REJECTED, VoiceOverlay.errorOf(AskOutcome.KeyRejected))
    }

    @Test fun noTextInToString() {
        val o = overlay()
        o.partial("secret words"); assertFalse(o.toString().contains("secret"))
        o.final("secret words"); assertFalse(o.toString().contains("secret"))
        o.outcome(AskOutcome.Answer(AskReply("secret answer", null), true)); assertFalse(o.toString().contains("secret"))
    }

    /** The overlay goes through the same AskEngine: the daily cap and the client errors apply unchanged. */
    @Test fun sameEngineLimitsApply() = runSuspend {
        var stored: String? = null
        val store = object : CountStore {
            override fun read() = stored
            override fun write(text: String) { stored = text }
        }
        val cap = DailyCap(store, limit = 1, clock = { Instant.parse("2026-11-04T12:00:00Z") }, zone = { ZoneId.of("UTC") })
        val engine = AskEngine({ _, _ -> ClaudeResult.Text("""{"answer":"Hi."}""") }, { true }, cap, { apps })
        val first = overlay()
        first.outcome(AskOutcome.NotSetUp) // ignored while listening
        first.final("hello")?.let { first.outcome(engine.ask(it)) }
        assertEquals("Hi.", (first.state as OverlayState.Answer).reply.answer)
        val second = overlay()
        second.final("again")?.let { second.outcome(AskEngine({ _, _ -> ClaudeResult.Text("""{"answer":"x"}""") }, { true }, cap, { apps }).ask(it)) }
        assertEquals(VoiceError.CAP_REACHED, (second.state as OverlayState.Error).error)
        val offline = overlay()
        offline.final("q")?.let { offline.outcome(AskEngine({ _, _ -> ClaudeResult.Failed(ClaudeError.NETWORK) }, { true }, DailyCap(object : CountStore {
            override fun read(): String? = null
            override fun write(text: String) = Unit
        }, clock = { Instant.EPOCH }, zone = { ZoneId.of("UTC") }), { apps }).ask(it)) }
        assertEquals(VoiceError.OFFLINE, (offline.state as OverlayState.Error).error)
    }
}

class VoiceOverlayEffectsTest {
    private var t = 0L
    private val seen = mutableListOf<OverlayEffect>()
    private fun overlay() = VoiceOverlay({ t }, { seen += it })
    private val reply = AskReply("ok", null)

    /** overlay-mic-after-timeout: a listen timeout releases the recognizer (and cancels any request). */
    @Test fun listenTimeoutReleasesTheMicAndCancels() {
        val o = overlay()
        assertTrue(seen.isEmpty())
        t += VoiceOverlay.LISTEN_TIMEOUT_MS; o.tick()
        assertEquals(listOf(OverlayEffect.RELEASE_RECOGNIZER, OverlayEffect.CANCEL_REQUEST), seen)
    }

    @Test fun sendingReleasesTheMicButKeepsTheRequest() {
        val o = overlay()
        o.final("q")
        assertEquals(listOf(OverlayEffect.RELEASE_RECOGNIZER), seen)
    }

    @Test fun leavingSendingCancelsTheRequest() {
        for (end in listOf<(VoiceOverlay) -> Unit>(
            { it.outcome(AskOutcome.Answer(reply, true)) },
            { it.outcome(AskOutcome.CantReach) },
            { t += VoiceOverlay.SEND_TIMEOUT_MS; it.tick() },
            { it.back() },
        )) {
            seen.clear()
            val o = overlay(); o.final("q"); seen.clear()
            end(o)
            assertTrue(OverlayEffect.CANCEL_REQUEST in seen, "$seen")
            assertTrue(OverlayEffect.RELEASE_RECOGNIZER in seen, "$seen")
        }
    }

    @Test fun backAndErrorsWhileListeningReleaseTheMic() {
        for (end in listOf<(VoiceOverlay) -> Unit>({ it.back() }, { it.speechError(7) }, { it.fail(VoiceError.NO_KEY) }, { it.final("") })) {
            seen.clear()
            val o = overlay()
            end(o)
            assertEquals(listOf(OverlayEffect.RELEASE_RECOGNIZER, OverlayEffect.CANCEL_REQUEST), seen)
        }
    }

    @Test fun partialTextKeepsListening() {
        val o = overlay(); o.partial("a"); o.partial("ab")
        assertTrue(seen.isEmpty(), "$seen")
    }
}

class VoiceOverlayFadeTest {
    private var t = 0L

    /** answer-fade: a key on the card restarts the 20 s fade; it does not end the answer. */
    @Test fun keepShowingRestartsTheFade() {
        val o = VoiceOverlay({ t })
        o.final("q"); o.outcome(AskOutcome.Answer(AskReply("a", null), false))
        t += 15_000; o.keepShowing()
        assertEquals(t + VoiceOverlay.ANSWER_MS, o.deadline())
        t += 19_999; assertFalse(o.tick())
        t += 1; assertTrue(o.tick()); assertTrue(o.isDone)
    }

    @Test fun keepShowingOnlyTouchesAnAnswer() {
        val o = VoiceOverlay({ t })
        val d = o.deadline()
        t += 5_000; o.keepShowing()
        assertEquals(d, o.deadline()) // listening timeout unchanged
    }
}
