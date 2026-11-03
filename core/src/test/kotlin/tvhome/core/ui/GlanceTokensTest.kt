package tvhome.core.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * M7-F1 (spec R3): every M7 text colour keeps 7:1 over its worst-case field, in the active, dimmed and near-black
 * states, and no M7 text is below 22 sp (33 px at 1080p).
 */
class GlanceTokensTest {
    private fun assertSeven(fg: Int, bg: Int, what: String) {
        val c = Tokens.contrast(fg, bg)
        assertTrue(c >= 7.0, "$what: ${fg.toString(16)} on ${bg.toString(16)} is ${"%.2f".format(c)}:1")
    }

    private val activeField = Tokens.white(GlanceTokens.BACKDROP_BRIGHTNESS)
    private val dimField = Tokens.white(OledTokens.DIM_BACKDROP_BRIGHTNESS)

    @Test fun whiteHelperIsAGrey() {
        assertEquals(0x404040, Tokens.white(0.25f))
        assertEquals(0x272727, Tokens.white(0.15f))
        assertEquals(0xFFFFFF, Tokens.white(1f))
        assertEquals(0x000000, Tokens.white(0f))
    }

    @Test fun scrimIsTheComplementOfBrightness() {
        assertEquals(0.75f, GlanceTokens.SCRIM_ALPHA, 1e-6f)
        assertTrue(OledTokens.DIM_BACKDROP_BRIGHTNESS < GlanceTokens.BACKDROP_BRIGHTNESS, "DIM is darker than active")
    }

    @Test fun heroTextKeepsSevenToOneOverAWhitePixelAtTheScrim() {
        assertSeven(GlanceTokens.HERO_TEXT, activeField, "hero text")
        assertSeven(GlanceTokens.HERO_SECONDARY, activeField, "hero secondary (line 2, age, chip, date)")
    }

    @Test fun everyHeroThemeUsesTokenTextColours() {
        // The hero draws each theme's text colours over the scrimmed backdrop; they must be ones the test above covers.
        for (t in HeroTheme.ALL) {
            assertSeven(t.text, activeField, "theme ${t.motif} text")
            assertSeven(t.secondaryText, activeField, "theme ${t.motif} secondary")
        }
    }

    @Test fun holidayBadgeIsReadable() {
        // The badge is backdropTop text on an accent pill (opaque, so the backdrop does not matter).
        for (t in HeroTheme.ALL.filter { it.badge != null }) {
            val c = Tokens.contrast(t.backdropTop, t.accent)
            assertTrue(c >= 7.0, "badge ${t.badge}: ${"%.2f".format(c)}:1")
        }
    }

    @Test fun alertCardKeepsSevenToOne() {
        assertSeven(GlanceTokens.ALERT_TEXT, GlanceTokens.ALERT_FILL, "alert text on alert fill")
    }

    @Test fun dimmedStateKeepsSevenToOne() {
        assertSeven(OledTokens.DIM_TEXT, dimField, "dim text over a white pixel at the dim scrim")
        assertSeven(OledTokens.DIM_TEXT, GlanceTokens.ALERT_FILL, "dim text on the alert fill")
        val d = HomeTheme.DEFAULT.dimmed()
        for ((name, fill) in listOf("background" to d.background, "tile" to d.tile, "focus fill" to d.focusFill)) {
            assertSeven(d.text, fill, "dimmed text on $name")
            assertSeven(d.secondaryText, fill, "dimmed secondary on $name")
        }
        val ring = Tokens.contrast(d.focusRing, d.background)
        assertTrue(ring >= 3.0, "dimmed ring on background: $ring")
        assertTrue(Tokens.luminance(d.focusRing) < Tokens.luminance(HomeTheme.DEFAULT.focusRing), "ring is dimmer")
    }

    @Test fun dimmedThemeOnlyChangesColoursThatDim() {
        val d = HomeTheme.DEFAULT.dimmed()
        assertEquals(HomeTheme.DEFAULT.copy(text = d.text, secondaryText = d.secondaryText, focusRing = d.focusRing), d)
        assertEquals(HomeTheme.DEFAULT.tileWidthDp, d.tileWidthDp)
    }

    @Test fun nearBlackClockKeepsSevenToOne() {
        assertSeven(OledTokens.NEAR_BLACK_CLOCK, OledTokens.NEAR_BLACK_BG, "near-black clock")
        assertEquals(0x000000, OledTokens.NEAR_BLACK_BG)
        // Not brighter than needed: the near-black clock stays below the dim text.
        assertTrue(Tokens.luminance(OledTokens.NEAR_BLACK_CLOCK) < Tokens.luminance(OledTokens.DIM_TEXT))
    }

    @Test fun noM7TextBelowTheFloor() {
        assertEquals(22, GlanceTokens.MIN_SP)
        for (sp in GlanceTokens.ALL_SP + OledTokens.NEAR_BLACK_CLOCK_SP) assertTrue(sp >= GlanceTokens.MIN_SP, "$sp sp")
        // Spec R3: hero text and the clock 56-72 px (1 sp = 1.5 px at 1080p).
        for (sp in listOf(GlanceTokens.HERO_SP, GlanceTokens.CLOCK_SP, GlanceTokens.ALERT_SP)) {
            assertTrue(sp * 1.5 in 56.0..72.0, "$sp sp = ${sp * 1.5} px")
        }
    }

    @Test fun gameDayScrimsKeepSevenToOneOverTheirArtsBrightest() {
        // Team-coloured scrims (navy, scarlet) at the usual 25%: the hero text over the art's brightest element, the
        // team colour at full strength (gold ribbon and rail; scarlet haze), composited as the app draws it.
        for (team in tvhome.core.feeds.Teams.ALL) {
            val px = GlanceTokens.composite(team.primary, team.scrim)
            assertSeven(GlanceTokens.HERO_TEXT, px, "${team.key} text over its ${team.primary.toString(16)} art")
            assertSeven(GlanceTokens.HERO_SECONDARY, px, "${team.key} secondary over its ${team.primary.toString(16)} art")
            assertSeven(GlanceTokens.HERO_SECONDARY, GlanceTokens.composite(0, team.scrim), "${team.key} over a black pixel")
        }
        assertEquals(0x404040, GlanceTokens.composite(0xFFFFFF, 0)) // black scrim: the usual white-pixel case
    }

    @Test fun gameDayScreenBackgroundKeepsTheHomeTextReadable() {
        for (team in tvhome.core.feeds.Teams.ALL) {
            val bg = team.screen ?: continue
            assertSeven(Tokens.TEXT, bg, "${team.key} tile labels on the screen background")
            assertSeven(Tokens.SECONDARY, bg, "${team.key} brief line on the screen background")
        }
    }
}
