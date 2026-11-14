package tvhome.core.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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

    // --- Game-day scoreboard (design section 6) ----------------------------------------------------------------------

    private val preds = BoardPalette.PREDATORS
    private val osu = BoardPalette.OHIO_STATE

    /** The lightest surface paint the abbreviations touch: the top-lit gradient at the letters' cap line. */
    private val rinkUnderLetters = BoardTokens.rinkAt(BoardTokens.LETTER_TOP)
    private val fieldUnderLetters = BoardTokens.fieldAt(BoardTokens.LETTER_TOP, band = true)

    @Test fun boardSurfaceGradientsRunLightToDark() {
        assertEquals(BoardTokens.RINK_TOP, BoardTokens.rinkAt(0f))
        assertEquals(BoardTokens.RINK_MID, BoardTokens.rinkAt(BoardTokens.RINK_MID_AT))
        assertEquals(BoardTokens.RINK_BOTTOM, BoardTokens.rinkAt(1f))
        assertEquals(BoardTokens.FIELD_TOP, BoardTokens.fieldAt(0f))
        assertEquals(BoardTokens.FIELD_BOTTOM, BoardTokens.fieldAt(1f))
        for (i in 0 until 20) {
            assertTrue(Tokens.luminance(BoardTokens.rinkAt(i / 20f)) >= Tokens.luminance(BoardTokens.rinkAt((i + 1) / 20f)))
            assertTrue(Tokens.luminance(BoardTokens.fieldAt(i / 20f)) >= Tokens.luminance(BoardTokens.fieldAt((i + 1) / 20f)))
        }
        assertTrue(BoardTokens.LETTER_TOP in 0.25f..0.32f, "the cap line: ${BoardTokens.LETTER_TOP}")
    }

    @Test fun boardLetteringThatKeepsSevenToOne() {
        assertSeven(preds.opp, rinkUnderLetters, "opponent on the rink")
        assertSeven(osu.own, fieldUnderLetters, "Ohio State on the field (on a light band)")
        // Time and score: white over the rink at its lightest (inside the circle, from its top) and on the field's box.
        assertSeven(BoardTokens.CENTER_TEXT, BoardTokens.rinkAt(0f), "time and score on the rink")
        assertSeven(BoardTokens.CENTER_TEXT, BoardTokens.CENTRE_BOX, "time on the field's centre box")
        assertSeven(BoardTokens.CENTER_TEXT, BoardTokens.fieldAt(0f, band = true), "score on the field")
        // The sheen's peak over the rink under white lettering.
        assertSeven(preds.opp, BoardTokens.mix(rinkUnderLetters, 0xFFFFFF, BoardTokens.SHEEN_ALPHA), "opponent under the sheen")
    }

    /**
     * The approved palette (mockup.html, frames 1 and 5) puts three large-lettering pairs under the design's 7:1:
     * Predators gold on the lit top of the rink, the opponent's grey on a light band of the field, and white on the
     * scarlet ticket stub. They are 60 to 132 dp bold (WCAG AAA for large text is 4.5:1), so this pins what ships and
     * fails if any gets worse. Raising them to 7:1 means changing an approved colour: a design decision (handoff).
     */
    @Test fun boardLetteringBelowSevenIsPinned() {
        fun pinned(fg: Int, bg: Int, atLeast: Double, what: String) {
            val c = Tokens.contrast(fg, bg)
            assertTrue(c >= atLeast, "$what: ${"%.2f".format(c)}:1")
            assertTrue(c >= 4.5, "$what: under large-text AAA")
            assertTrue(c < 7.0, "$what now keeps 7:1 (${"%.2f".format(c)}): move it to boardLetteringThatKeepsSevenToOne")
        }
        pinned(preds.own, rinkUnderLetters, 5.9, "Predators gold on the rink at the cap line")
        pinned(osu.opp, fieldUnderLetters, 6.7, "opponent grey on a light field band at the cap line")
        pinned(osu.ticketInk, osu.ticketStub, 6.7, "white on the scarlet ticket stub")
        // Lower on the surface the same pairs pass (the letters' baseline is at 0.71 of the height).
        assertSeven(preds.own, BoardTokens.rinkAt(0.70f), "gold on the lower rink")
        assertSeven(osu.opp, BoardTokens.fieldAt(0.5f, band = true), "opponent grey on the lower field")
    }

    @Test fun stubTextKeepsSevenToOne() {
        for ((name, p) in listOf("Predators" to preds, "Ohio State" to osu)) {
            assertSeven(p.stubAccent, p.stubFill, "$name stub label")
            assertSeven(p.stubText, p.stubFill, "$name stub pick")
            assertSeven(p.focusInk, p.focusFill, "$name focused stub")
            // DIM: outline and text only, on the dimmed backdrop.
            assertSeven(OledTokens.DIM_TEXT, dimField, "$name stub in DIM")
        }
    }

    @Test fun ticketTextKeepsSevenToOne() {
        for ((name, p) in listOf("Predators" to preds, "Ohio State" to osu)) {
            assertSeven(GlanceTokens.TICKET_TEXT, p.ticketBody, "$name ticket body")
            assertSeven(GlanceTokens.HERO_SECONDARY, p.ticketBody, "$name ticket secondary (the time, Back to close)")
            assertSeven(p.ticketAccent, p.ticketBody, "$name stakes")
        }
        assertSeven(preds.ticketInk, preds.ticketStub, "Predators ticket stub")
    }

    /** With the board there is no scrim: the hero text sits on the board backdrop itself (gradient, glow, light cone). */
    @Test fun heroTextKeepsSevenToOneOnTheBoardBackdrop() {
        for ((name, p) in listOf("Predators" to preds, "Ohio State" to osu)) {
            for (base in listOf(p.artTop, p.artMid, p.artBottom)) {
                val worst = BoardTokens.mix(BoardTokens.mix(base, p.glow, BoardTokens.GLOW_TEXT_MAX), p.cone, p.coneAlpha)
                assertSeven(GlanceTokens.HERO_TEXT, worst, "$name hero text on the board backdrop")
                assertSeven(GlanceTokens.HERO_SECONDARY, worst, "$name hero secondary on the board backdrop")
            }
        }
        assertTrue(BoardTokens.GLOW_TEXT_MAX < BoardTokens.GLOW_ALPHA)
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

    @Test fun everyThemeKeepsSevenToOneOverItsArtsBrightestTextZonePixel() {
        // BackdropPeaks is measured from the shipped images (render_backdrops.py): the brightest pixel inside the top
        // strip, left zone and right card. Composited over each theme's scrim (team colour on game days) as drawn.
        val themes = HeroTheme.ALL + tvhome.core.feeds.Teams.ALL.map { team ->
            HeroTheme.forGame(tvhome.core.feeds.TeamGame(team, tvhome.core.feeds.GameInfo(
                java.time.Instant.EPOCH, "X", true, tvhome.core.feeds.GamePhase.UPCOMING, null, null, null)))
        }
        for (t in themes) {
            // The art peak (tinted when the image belongs to another theme), and that pixel under the strongest
            // snowflake and raindrop (alphas capped by BackdropArt.precipScale), each scrimmed as drawn.
            val name = t.badge ?: t.motif.name
            val (peak, snow, rain) = BackdropArt.worstUnderText(t)
            for ((what, px) in listOf("art" to peak, "a white snowflake" to snow, "rain" to rain)) {
                assertSeven(GlanceTokens.HERO_TEXT, px, "$name text over $what")
                assertSeven(GlanceTokens.HERO_SECONDARY, px, "$name secondary over $what")
            }
            if (t.scrim == 0) {
                assertEquals(1f, BackdropArt.precipScale(t, 0xFFFFFF, BackdropArt.SNOW_MAX_ALPHA), "$name snow uncapped")
            }
        }
        // Thanksgiving and Valentine's are drawn over SUNSET's image with their own tint.
        val thanks = HeroTheme.at(java.time.LocalDate.of(2026, 11, 26), java.time.LocalTime.NOON)
        assertTrue(BackdropArt.tinted(thanks))
        assertEquals(0x404040, GlanceTokens.composite(0xFFFFFF, 0)) // black scrim: the usual white-pixel case
    }

    @Test fun gameDayScreenAppliesOnlyWhenItReads() {
        val preds = tvhome.core.feeds.Teams.PREDATORS
        val on = HomeTheme.DEFAULT.forGameDay(preds.screen, preds.tile)
        assertEquals(preds.screen, on.background)
        assertEquals(preds.tile, on.tile)
        assertSeven(on.text, on.background, "tile labels on the navy screen")
        assertSeven(on.secondaryText, on.background, "brief line on the navy screen")
        assertSeven(on.secondaryText, on.tile, "dock tile secondary text on the navy tile")
        assertTrue(Tokens.contrast(on.tile, on.background) >= HomeTheme.GAME_SEPARATION, "tile stands out")
        assertTrue(Tokens.contrast(on.focusFill, on.background) >= HomeTheme.GAME_SEPARATION, "focused fill stands out")
        // A custom theme whose text would not read on navy keeps its own screen.
        val dark = HomeTheme.DEFAULT.copy(secondaryText = 0x5A6270)
        assertEquals(dark, dark.forGameDay(preds.screen, preds.tile))
        assertEquals(HomeTheme.DEFAULT, HomeTheme.DEFAULT.forGameDay(null, null))
    }

    @Test fun eachTeamScrimGetsItsOwnParticleCap() {
        fun theme(team: tvhome.core.feeds.TeamConfig) = HeroTheme.forGame(tvhome.core.feeds.TeamGame(team,
            tvhome.core.feeds.GameInfo(java.time.Instant.EPOCH, "X", true, tvhome.core.feeds.GamePhase.UPCOMING, null, null, null)))
        val osu = theme(tvhome.core.feeds.Teams.OHIO_STATE)
        val preds = theme(tvhome.core.feeds.Teams.PREDATORS)
        assertEquals(0.656f, BackdropArt.precipScale(osu, 0xFFFFFF, BackdropArt.SNOW_MAX_ALPHA), 0.01f, "Ohio State snow")
        assertEquals(1f, BackdropArt.precipScale(osu, BackdropArt.RAIN, BackdropArt.RAIN_MAX_ALPHA), "Ohio State rain")
        assertEquals(0.01f, BackdropArt.precipScale(preds, 0xFFFFFF, BackdropArt.SNOW_MAX_ALPHA), 0.01f, "Predators snow")
        assertEquals(0.085f, BackdropArt.precipScale(preds, BackdropArt.RAIN, BackdropArt.RAIN_MAX_ALPHA), 0.01f, "Predators rain")
        // Indoors: no weather overlay (and no animation) in the arena; Ohio State and black-scrim themes keep theirs.
        for (p in tvhome.core.feeds.Precip.values()) {
            assertFalse(BackdropArt.showsPrecip(preds, p), "Predators $p")
            assertTrue(BackdropArt.showsPrecip(osu, p), "Ohio State $p")
            for (t in HeroTheme.ALL) assertTrue(BackdropArt.showsPrecip(t, p), "${t.badge ?: t.motif} $p")
        }
    }
}
