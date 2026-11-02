package tvhome.core.ui

import java.time.LocalDate
import java.time.LocalTime
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HeroThemeTest {
    private val noon = LocalTime.of(12, 0)
    private fun badge(y: Int, m: Int, d: Int, t: LocalTime = noon) = HeroTheme.at(LocalDate.of(y, m, d), t).badge

    @Test fun halloweenRunsOct15To31() {
        assertNull(badge(2026, 10, 14))
        assertEquals("HALLOWEEN", badge(2026, 10, 15))
        assertEquals("HALLOWEEN", badge(2026, 10, 31))
        assertEquals(HeroMotif.MOON_BATS, HeroTheme.at(LocalDate.of(2026, 10, 20), noon).motif)
        assertNull(badge(2026, 11, 1))
    }

    @Test fun thanksgivingIsFourthThursdayAndThreeDaysBefore() {
        assertEquals(LocalDate.of(2026, 11, 26), HeroTheme.thanksgiving(2026))
        assertEquals(LocalDate.of(2025, 11, 27), HeroTheme.thanksgiving(2025))
        assertEquals(LocalDate.of(2023, 11, 23), HeroTheme.thanksgiving(2023))
        assertNull(badge(2026, 11, 22))
        for (day in 23..26) assertEquals("THANKSGIVING", badge(2026, 11, day), "Nov $day")
        assertNull(badge(2026, 11, 27))
        // A year where the 4th Thursday is early (Nov 22, 2029): the window starts Nov 19.
        assertEquals(LocalDate.of(2029, 11, 22), HeroTheme.thanksgiving(2029))
        assertEquals("THANKSGIVING", badge(2029, 11, 19))
        assertNull(badge(2029, 11, 18))
    }

    @Test fun christmasRunsDec1To26() {
        assertNull(badge(2026, 11, 30))
        assertEquals("CHRISTMAS", badge(2026, 12, 1))
        assertEquals("CHRISTMAS", badge(2026, 12, 26))
        assertNull(badge(2026, 12, 27))
        assertEquals(HeroMotif.SNOW, HeroTheme.at(LocalDate.of(2026, 12, 25), noon).motif)
    }

    @Test fun newYearIsDec31AndJan1() {
        assertNull(badge(2026, 12, 30))
        assertEquals("NEW YEAR", badge(2026, 12, 31))
        assertEquals("NEW YEAR", badge(2027, 1, 1))
        assertNull(badge(2027, 1, 2))
    }

    @Test fun valentinesAndIndependenceDayAreSingleDays() {
        assertNull(badge(2026, 2, 13))
        assertEquals("VALENTINE'S DAY", badge(2026, 2, 14))
        assertNull(badge(2026, 2, 15))
        assertNull(badge(2026, 7, 3))
        assertEquals("JULY 4", badge(2026, 7, 4))
        assertNull(badge(2026, 7, 5))
    }

    @Test fun holidayWinsOverTimeOfDay() {
        val d = LocalDate.of(2026, 10, 31)
        for (h in listOf(0, 3, 6, 12, 18, 23)) assertEquals("HALLOWEEN", HeroTheme.at(d, LocalTime.of(h, 0)).badge)
    }

    @Test fun timeOfDayBoundaries() {
        val d = LocalDate.of(2026, 3, 10)
        fun motif(h: Int, m: Int = 0) = HeroTheme.at(d, LocalTime.of(h, m)).motif
        assertEquals(HeroMotif.STARS, motif(4, 59))
        assertEquals(HeroMotif.DAWN, motif(5))
        assertEquals(HeroMotif.DAWN, motif(10, 59))
        assertEquals(HeroMotif.DAY, motif(11))
        assertEquals(HeroMotif.DAY, motif(16, 59))
        assertEquals(HeroMotif.SUNSET, motif(17))
        assertEquals(HeroMotif.SUNSET, motif(20, 59))
        assertEquals(HeroMotif.STARS, motif(21))
        assertEquals(HeroMotif.STARS, motif(0))
        for (h in 0..23) assertNull(HeroTheme.at(d, LocalTime.of(h, 0)).badge)
    }

    @Test fun nightIsDimmerThanEveryOtherTheme() {
        val night = HeroTheme.at(LocalDate.of(2026, 3, 10), LocalTime.of(23, 0))
        val nightL = Tokens.luminance(night.backdropBottom)
        assertTrue(nightL < 0.01, "night bottom luminance $nightL")
        for (t in HeroTheme.ALL) if (t !== night) {
            assertTrue(Tokens.luminance(t.backdropBottom) > nightL, "${t.badge ?: t.motif} not brighter than night")
        }
    }

    @Test fun textAndAccentKeepContrastOnEveryTheme() {
        for (t in HeroTheme.ALL) {
            val name = t.badge ?: t.motif.name
            for (bg in listOf(t.backdropTop, t.backdropBottom)) {
                assertTrue(Tokens.contrast(t.text, bg) >= 4.5, "$name text on ${bg.toString(16)}")
                assertTrue(Tokens.contrast(t.secondaryText, bg) >= 4.5, "$name secondary on ${bg.toString(16)}")
            }
            // The badge pill is the accent with backdropTop text; the accent alone must also read on the backdrop.
            assertTrue(Tokens.contrast(t.accent, t.backdropTop) >= 4.5, "$name accent vs top")
        }
    }
}
