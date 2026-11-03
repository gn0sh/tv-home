package tvhome.core.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class WatchPillTest {
    /** About what fits in the 200 dp pill at 24 sp: 14 characters. */
    private val fits: (String) -> Boolean = { it.length <= 14 }

    @Test fun namesTheAppWhenItFits() {
        assertEquals("Watch on CDE", WatchPill.label("CDE", fits))
        assertEquals("Watch on ESPN", WatchPill.label(" ESPN ", fits))
    }

    @Test fun longLabelIsPlainWatchNeverCut() {
        assertEquals("Watch", WatchPill.label("CDE Lightband TV", fits))
        assertEquals("Watch", WatchPill.label("x".repeat(500)) { true })
    }

    @Test fun noLabelOrBadLabelIsPlainWatch() {
        assertEquals("Watch", WatchPill.label(null, fits))
        assertEquals("Watch", WatchPill.label("  ", fits))
        assertEquals("Watch", WatchPill.label("Bad\u0007", fits))
        assertEquals("Watch", WatchPill.label("CDE") { error("measure failed") })
    }
}
