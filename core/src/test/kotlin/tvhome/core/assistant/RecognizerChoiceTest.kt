package tvhome.core.assistant

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import tvhome.core.assistant.RecognizerChoice.Candidate

class RecognizerChoiceTest {
    private val own = "com.gnosh.tvhome"
    private val ours = Candidate(own, "com.gnosh.tvhome.ClaudeRecognitionService", system = false)
    private val katniss = Candidate("com.google.android.katniss", "com.google.android.katniss.search.SearchRecognitionService", system = true)
    private val gsa = Candidate("com.google.android.googlequicksearchbox", "com.google.android.voicesearch.serviceapi.GoogleRecognitionService", system = true)
    private val other = Candidate("com.example.voice", "com.example.voice.Rec", system = true)

    @Test fun neverPicksOurOwnService() {
        assertNull(RecognizerChoice.pick(listOf(ours), own))
        assertNull(RecognizerChoice.pick(listOf(ours.copy(system = true)), own))
        assertNull(RecognizerChoice.pick(emptyList(), own))
    }

    @Test fun nonGoogleRecognizerIsNeverPicked() {
        assertNull(RecognizerChoice.pick(listOf(other), own))
        assertNull(RecognizerChoice.pick(listOf(ours, other, other.copy(system = false)), own))
    }

    @Test fun googlePackageNameAloneIsNotEnough() {
        // A sideloaded app can take a Google package name only if Google's is absent; it is not a system app.
        assertNull(RecognizerChoice.pick(listOf(katniss.copy(system = false), gsa.copy(system = false)), own))
        assertEquals(gsa, RecognizerChoice.pick(listOf(katniss.copy(system = false), gsa), own))
    }

    @Test fun prefersGoogleTvRecognizerThenTheGoogleApp() {
        assertEquals(katniss, RecognizerChoice.pick(listOf(other, gsa, ours, katniss), own))
        assertEquals(gsa, RecognizerChoice.pick(listOf(other, ours, gsa), own))
        assertEquals(katniss, RecognizerChoice.pick(listOf(katniss, gsa).reversed(), own))
    }

    @Test fun blankClassIsIgnored() {
        assertNull(RecognizerChoice.pick(listOf(katniss.copy(cls = "")), own))
    }
}
