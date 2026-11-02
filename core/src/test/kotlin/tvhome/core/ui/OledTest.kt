package tvhome.core.ui

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.math.abs
import kotlin.math.hypot

class OledTest {
    @Test fun driftStaysInBoundsAndMovesAtMostOneDpPer20sOver24h() {
        var prev = Drift.offset(Duration.ZERO)
        var maxX = 0f; var maxY = 0f
        val step = Duration.ofSeconds(20)
        var t = Duration.ZERO
        while (t <= Duration.ofHours(24)) {
            val o = Drift.offset(t)
            assertTrue(abs(o.dx) <= 6f && abs(o.dy) <= 4f, "$t $o")
            assertTrue(hypot((o.dx - prev.dx).toDouble(), (o.dy - prev.dy).toDouble()) <= 1.0, "jump at $t")
            maxX = maxOf(maxX, abs(o.dx)); maxY = maxOf(maxY, abs(o.dy))
            prev = o; t = t.plus(step)
        }
        assertTrue(maxX > 5.5f && maxY > 3.5f, "uses the range: $maxX $maxY")
        // Closed path: back to the start after an hour.
        val h = Drift.offset(Duration.ofHours(1))
        assertEquals(0f, h.dx, 1e-3f); assertEquals(0f, h.dy, 1e-3f)
    }

    @Test fun driftOffForVizio() {
        assertEquals(DriftOffset.ZERO, Drift.offsetFor(TvProfile.VIZIO_LED, Duration.ofMinutes(7)))
        assertNotEquals(DriftOffset.ZERO, Drift.offsetFor(TvProfile.LG_OLED, Duration.ofMinutes(7)))
    }

    private var ms = 0L
    private fun min(m: Long) { ms += m * 60_000 }

    @Test fun lgDimsAt10AndGoesNearBlackAt30() {
        val c = IdleClock(TvProfile.LG_OLED) { ms }
        ms += 10 * 60_000 - 1; assertEquals(IdleState.ACTIVE, c.state())
        ms += 1; assertEquals(IdleState.DIM, c.state())
        ms += 20 * 60_000 - 1; assertEquals(IdleState.DIM, c.state())
        assertEquals(Duration.ofMillis(1), c.nextChangeIn())
        ms += 1; assertEquals(IdleState.NEAR_BLACK, c.state())
        assertNull(c.nextChangeIn())
        min(600); assertEquals(IdleState.NEAR_BLACK, c.state())
    }

    @Test fun vizioDimsAt30AndNeverGoesNearBlack() {
        val c = IdleClock(TvProfile.VIZIO_LED) { ms }
        min(29); assertEquals(IdleState.ACTIVE, c.state())
        min(1); assertEquals(IdleState.DIM, c.state())
        min(600); assertEquals(IdleState.DIM, c.state())
        assertNull(c.nextChangeIn())
    }

    @Test fun aKeyResets() {
        val c = IdleClock(TvProfile.LG_OLED) { ms }
        min(31); assertEquals(IdleState.NEAR_BLACK, c.state())
        c.onKey(); assertEquals(IdleState.ACTIVE, c.state())
        assertEquals(Duration.ofMinutes(10), c.nextChangeIn())
        min(9); c.onKey(); min(9); assertEquals(IdleState.ACTIVE, c.state())
        min(1); assertEquals(IdleState.DIM, c.state())
        c.onKey(); assertEquals(IdleState.ACTIVE, c.state())
    }

    @Test fun clockGoingBackwardsIsActive() {
        ms = 1_000_000
        val c = IdleClock(TvProfile.LG_OLED) { ms }
        ms = 0
        assertEquals(IdleState.ACTIVE, c.state())
    }

    @Test fun profileResolveSettingThenEdidThenLg() {
        assertEquals(TvProfile.VIZIO_LED, TvProfile.resolve("vizio", "GSM"))
        assertEquals(TvProfile.LG_OLED, TvProfile.resolve(" LG ", "VIZ"))
        assertEquals(TvProfile.VIZIO_LED, TvProfile.resolve(null, "VIZ"))
        assertEquals(TvProfile.VIZIO_LED, TvProfile.resolve("bogus", "viz"))
        assertEquals(TvProfile.LG_OLED, TvProfile.resolve(null, "GSM"))
        assertEquals(TvProfile.LG_OLED, TvProfile.resolve(null, "SAM"))
        assertEquals(TvProfile.LG_OLED, TvProfile.resolve(null, null))
        assertEquals(TvProfile.LG_OLED, TvProfile.resolve("", ""))
    }

    @Test fun idleTokensKeepSevenToOne() {
        // Worst case for DIM: a white backdrop pixel at 15%.
        val f = (255 * OledTokens.DIM_FIELD_ALPHA).toInt()
        val field = (f shl 16) or (f shl 8) or f
        assertTrue(Tokens.contrast(OledTokens.DIM_TEXT, field) >= 7.0, "${Tokens.contrast(OledTokens.DIM_TEXT, field)}")
        assertTrue(Tokens.contrast(OledTokens.NEAR_BLACK_CLOCK, OledTokens.NEAR_BLACK_BG) >= 7.0)
    }
}
