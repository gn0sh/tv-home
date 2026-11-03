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

    /** watch-label-bidi: format characters (bidi override, zero-width space and joiner, BOM) give plain "Watch". */
    @Test fun formatCharactersArePlainWatch() {
        for (c in listOf('\u202E', '\u200B', '\u200D', '\uFEFF', '\u2066')) assertEquals("Watch", WatchPill.label("CD${c}E", fits), "U+%04X".format(c.code))
    }

    @Test fun descriptionNamesTheAppOnlyWhenShown() {
        val p = "Watch the game on "
        assertEquals("Watch the game on SCRIPPS, opens CDE", WatchPill.description(p, "SCRIPPS", "Watch on CDE"))
        assertEquals("Watch the game on SCRIPPS", WatchPill.description(p, "SCRIPPS", "Watch"))
    }
}
