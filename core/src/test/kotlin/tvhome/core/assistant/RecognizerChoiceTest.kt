package tvhome.core.assistant

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import tvhome.core.assistant.RecognizerChoice.Candidate

class RecognizerChoiceTest {
    private val own = "com.gnosh.tvhome"
    private val ours = Candidate(own, "com.gnosh.tvhome.ClaudeRecognitionService")
    private val katniss = Candidate("com.google.android.katniss", "com.google.android.katniss.search.SearchRecognitionService")
    private val gsa = Candidate("com.google.android.googlequicksearchbox", "com.google.android.voicesearch.serviceapi.GoogleRecognitionService")
    private val other = Candidate("com.example.voice", "com.example.voice.Rec")

    @Test fun neverPicksOurOwnService() {
        assertNull(RecognizerChoice.pick(listOf(ours), own))
        assertNull(RecognizerChoice.pick(emptyList(), own))
        assertEquals(other, RecognizerChoice.pick(listOf(ours, other), own))
    }

    @Test fun prefersGoogleTvRecognizerThenOtherGoogleThenAnyOther() {
        assertEquals(katniss, RecognizerChoice.pick(listOf(other, gsa, ours, katniss), own))
        assertEquals(gsa, RecognizerChoice.pick(listOf(other, ours, gsa), own))
    }

    @Test fun orderOfTheListDoesNotMatter() {
        val a = Candidate("com.b.voice", "com.b.voice.Rec")
        val b = Candidate("com.a.voice", "com.a.voice.Rec")
        assertEquals(b, RecognizerChoice.pick(listOf(a, b), own))
        assertEquals(b, RecognizerChoice.pick(listOf(b, a), own))
    }

    @Test fun blankEntriesAreIgnored() {
        assertNull(RecognizerChoice.pick(listOf(Candidate("", "x"), Candidate("com.x", "")), own))
    }
}
