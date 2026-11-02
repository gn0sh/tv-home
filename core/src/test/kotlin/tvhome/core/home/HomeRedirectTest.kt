package tvhome.core.home

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HomeRedirectTest {
    @Test fun singleHomeRedirects() {
        val r = HomeRedirect()
        assertTrue(r.onHomeShown(0))
        r.onAppShown()
        assertTrue(r.onHomeShown(10_000))
    }

    @Test fun doublePressStaysUntilAnAppComesUp() {
        val r = HomeRedirect()
        assertTrue(r.onHomeShown(0))
        r.onAppShown() // the dashboard came up
        assertFalse(r.onHomeShown(1_000)) // second Home
        assertTrue(r.staying)
        assertFalse(r.onHomeShown(20_000), "the stock home reporting itself again while browsed stays")
        assertFalse(r.onHomeShown(60_000))
        r.onAppShown()
        assertTrue(r.onHomeShown(61_000))
    }

    @Test fun homeJustAfterTheWindowRedirects() {
        val r = HomeRedirect()
        assertTrue(r.onHomeShown(0))
        assertTrue(r.onHomeShown(2_500))
    }

    @Test fun fastCrashLoopStopsAtTheSecondCycle() {
        val r = HomeRedirect()
        assertTrue(r.onHomeShown(0))
        assertFalse(r.onHomeShown(1_000))
        assertFalse(r.onHomeShown(2_000))
    }

    @Test fun slowCrashLoopPausesThenResumes() {
        val r = HomeRedirect()
        val redirected = (0 until 10).map { r.onHomeShown(it * 3_000L) }
        assertEquals(List(6) { true } + List(4) { false }, redirected) // the 7th (18 s) pauses until 138 s
        assertFalse(r.onHomeShown(137_000))
        assertTrue(r.onHomeShown(138_000))
    }

    @Test fun unreachableBurstIsRefused() {
        assertThrows(IllegalArgumentException::class.java) { HomeRedirect(doublePressMs = 2_500, burstMax = 6, burstMs = 10_000) }
    }
}
