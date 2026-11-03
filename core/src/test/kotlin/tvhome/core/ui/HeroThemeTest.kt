package tvhome.core.ui

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import tvhome.core.sky.Sun
import tvhome.core.sky.SunPosition
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

    // Real sky (a location is set): Clarksville TN, 2026-11-03, sunset 4:51 PM CST and civil dusk 5:17 PM.
    private val clarksville = 36.53 to -87.36
    private val central = ZoneId.of("America/Chicago")
    private fun skyMotif(date: LocalDate, h: Int, m: Int = 0): HeroMotif {
        val at = date.atTime(h, m).atZone(central).toInstant()
        return HeroTheme.at(date, LocalTime.of(h, m), Sun.position(at, clarksville.first, clarksville.second)).motif
    }

    @Test fun novemberEveningFollowsTheRealSunset() {
        val d = LocalDate.of(2026, 11, 3)
        assertEquals(HeroMotif.STARS, skyMotif(d, 5, 0)) // before civil dawn (5:48)
        assertEquals(HeroMotif.DAWN, skyMotif(d, 6, 0)) // twilight
        assertEquals(HeroMotif.DAWN, skyMotif(d, 6, 30)) // just risen, under 6 degrees
        assertEquals(HeroMotif.DAY, skyMotif(d, 8, 0))
        assertEquals(HeroMotif.DAY, skyMotif(d, 15, 30)) // the old buckets also said day
        assertEquals(HeroMotif.SUNSET, skyMotif(d, 16, 30)) // golden hour, the old buckets said day
        assertEquals(HeroMotif.SUNSET, skyMotif(d, 17, 5)) // after sunset, in civil twilight
        assertEquals(HeroMotif.STARS, skyMotif(d, 17, 30)) // after civil dusk
        assertEquals(HeroMotif.STARS, skyMotif(d, 19, 0)) // the bug: the old buckets showed a sun on the horizon here
    }

    @Test fun juneEveningStaysLightLate() {
        val d = LocalDate.of(2026, 6, 21)
        assertEquals(HeroMotif.DAY, skyMotif(d, 18, 30)) // old buckets: evening
        assertEquals(HeroMotif.SUNSET, skyMotif(d, 20, 0))
        assertEquals(HeroMotif.SUNSET, skyMotif(d, 20, 30)) // after the 8:11 PM sunset, before the 8:42 PM civil dusk
        assertEquals(HeroMotif.STARS, skyMotif(d, 21, 0)) // old buckets: evening
    }

    @Test fun skyBandsByElevation() {
        val d = LocalDate.of(2026, 3, 10)
        val t = LocalTime.of(12, 0) // the clock says day; the sky decides
        fun m(elev: Double, morning: Boolean) = HeroTheme.at(d, t, SunPosition(elev, 0.0, morning)).motif
        assertEquals(HeroMotif.STARS, m(-6.01, true))
        assertEquals(HeroMotif.DAWN, m(-6.0, true))
        assertEquals(HeroMotif.DAWN, m(5.99, true))
        assertEquals(HeroMotif.SUNSET, m(-6.0, false))
        assertEquals(HeroMotif.SUNSET, m(5.99, false))
        assertEquals(HeroMotif.DAY, m(6.0, false))
        assertEquals(HeroMotif.DAY, m(60.0, true))
        assertEquals(HeroMotif.STARS, m(-40.0, false))
    }

    @Test fun holidaysKeepTheirLookWithTheRealSky() {
        val sun = SunPosition(-30.0, 0.0, morning = false)
        assertEquals("HALLOWEEN", HeroTheme.at(LocalDate.of(2026, 10, 31), noon, sun).badge)
        assertEquals(HeroMotif.SUNSET, HeroTheme.at(LocalDate.of(2026, 11, 26), noon, sun).motif)
        assertEquals("THANKSGIVING", HeroTheme.at(LocalDate.of(2026, 11, 26), noon, sun).badge)
    }

    @Test fun noLocationKeepsTheClockBuckets() {
        val d = LocalDate.of(2026, 11, 3)
        for (h in 0..23) assertEquals(HeroTheme.at(d, LocalTime.of(h, 0)), HeroTheme.at(d, LocalTime.of(h, 0), null))
        assertEquals(HeroMotif.SUNSET, HeroTheme.at(d, LocalTime.of(19, 0), null).motif)
    }

    @Test fun skyThemesAreTheSameObjectsAsTheClockOnes() {
        // The real sky only picks among the existing scenes: no new palette escapes the contrast checks above.
        val d = LocalDate.of(2026, 3, 10)
        for (e in listOf(-20.0, 0.0, 20.0)) for (mo in listOf(true, false)) {
            assertTrue(HeroTheme.ALL.any { it === HeroTheme.at(d, noon, SunPosition(e, 0.0, mo)) })
        }
    }

    @Test fun deadBandHoldsTheSceneAtTheLines() {
        val d = LocalDate.of(2026, 3, 10)
        fun m(e: Double, morning: Boolean, prev: HeroTheme?) = HeroTheme.at(d, noon, SunPosition(e, 0.0, morning), prev)
        val day = m(30.0, false, null)
        val evening = m(3.0, false, null)
        val night = m(-20.0, false, null)
        val morning = m(3.0, true, null)
        // A noon that peaks right at +6: day stays day down to 5.5, and sunset stays sunset up to 6.5.
        assertEquals(HeroMotif.DAY, m(5.6, false, day).motif)
        assertEquals(HeroMotif.SUNSET, m(5.4, false, day).motif)
        assertEquals(HeroMotif.DAWN, m(6.4, true, morning).motif)
        assertEquals(HeroMotif.DAY, m(6.6, true, morning).motif)
        // A summer midnight that dips right to -6: night holds to -5.5, twilight to -6.5.
        assertEquals(HeroMotif.STARS, m(-5.6, true, night).motif)
        assertEquals(HeroMotif.DAWN, m(-5.4, true, night).motif)
        assertEquals(HeroMotif.SUNSET, m(-6.4, false, evening).motif)
        assertEquals(HeroMotif.STARS, m(-6.6, false, evening).motif)
        // Dawn and sunset swap at the transit or solar midnight only (no dead band on the morning flag).
        assertEquals(HeroMotif.SUNSET, m(4.0, false, morning).motif)
        assertEquals(HeroMotif.DAWN, m(-5.9, true, evening).motif)
        // A holiday as the previous theme holds nothing.
        val halloween = HeroTheme.at(LocalDate.of(2026, 10, 31), noon)
        assertEquals(HeroMotif.DAY, m(6.1, false, halloween).motif)
    }

    @Test fun deadBandWalkThroughAPeakAtSixDoesNotFlicker() {
        // Elevation wobbling 5.8..6.2 around a noon peak: one scene, whichever it started in.
        val d = LocalDate.of(2026, 3, 10)
        var prev: HeroTheme? = null
        val seen = mutableSetOf<HeroMotif>()
        for (i in 0 until 40) {
            val e = 6.0 + 0.2 * kotlin.math.sin(i.toDouble())
            prev = HeroTheme.at(d, noon, SunPosition(e, 0.0, i < 20), prev)
            if (i > 0) seen += prev.motif
        }
        assertTrue(seen.size == 1, "$seen")
    }
}
