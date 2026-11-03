package tvhome.core.ui

import java.time.LocalDate
import java.time.ZoneId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SunArcTest {
    private val lat = 36.53
    private val lon = -87.36
    private val central = ZoneId.of("America/Chicago")
    private fun sky(y: Int, mo: Int, d: Int, h: Int, m: Int = 0) =
        SkyNow.of(LocalDate.of(y, mo, d).atTime(h, m).atZone(central).toInstant(), lat, lon)

    private val flat = FloatArray(3) { 0.6f }

    @Test fun sunriseSitsOnTheHillsAtTheLeftEndOfTheArc() {
        val day = sky(2026, 11, 3, 12).day
        val s = SunArc.place(SkyNow.of(day.sunrise!!, lat, lon), flat)
        assertNotNull(s)
        assertEquals(SunArc.X_RISE, s!!.x, 0.002f)
        // Elevation is about -0.4 at sunrise (refraction): the centre is a hair under the line, the top half shows.
        assertEquals(0.6f, s.y, 0.01f)
    }

    @Test fun sunsetIsAtTheRightEndAndNoonAtTheTop() {
        val day = sky(2026, 11, 3, 12).day
        val set = SunArc.place(SkyNow.of(day.sunset!!, lat, lon), flat)!!
        assertEquals(SunArc.X_SET, set.x, 0.002f)
        val noon = SunArc.place(SkyNow.of(day.noon, lat, lon), flat)!!
        assertEquals(SunArc.Y_TOP, noon.y, 0.001f)
        assertTrue(noon.x > SunArc.X_RISE && noon.x < SunArc.X_SET)
    }

    @Test fun sevenPmInNovemberHasNoSun() {
        assertNull(SunArc.place(sky(2026, 11, 3, 19), flat))
        assertNull(SunArc.place(sky(2026, 11, 3, 17, 30), flat)) // after civil dusk
    }

    @Test fun neverAboveTheTopOrLeftOfTheCalmZoneAllYear() {
        var d = LocalDate.of(2026, 1, 1)
        while (d.year == 2026) {
            for (h in 0..23) for (m in listOf(0, 30)) {
                val s = SunArc.place(SkyNow.of(d.atTime(h, m).atZone(central).toInstant(), lat, lon), flat)
                    ?: continue
                assertTrue(s.y >= SunArc.Y_TOP - 1e-4f, "$d $h:$m y ${s.y}")
                assertTrue(s.x in 0.52f..0.96f, "$d $h:$m x ${s.x}")
            }
            d = d.plusDays(7)
        }
    }

    @Test fun moveIsSmallPerMinute() {
        // Updated on the minute tick only: one minute moves the disc a fraction of a percent of the image.
        val a = SunArc.place(sky(2026, 11, 3, 10, 0), flat)!!
        val b = SunArc.place(sky(2026, 11, 3, 10, 1), flat)!!
        assertTrue(kotlin.math.abs(a.x - b.x) < 0.002f && kotlin.math.abs(a.y - b.y) < 0.002f)
    }

    @Test fun midnightSunUsesSolarTime() {
        val oslo = ZoneId.of("Europe/Oslo")
        val noon = tvhome.core.sky.Sun.day(LocalDate.of(2026, 6, 21), oslo, 69.65, 18.96).noon
        val s = SunArc.place(SkyNow.of(noon, 69.65, 18.96), null)!!
        assertEquals((SunArc.X_RISE + SunArc.X_SET) / 2, s.x, 0.002f)
        assertEquals(SunArc.Y_TOP, s.y, 0.001f)
    }

    @Test fun lineAtInterpolates() {
        val line = floatArrayOf(0.5f, 0.7f, 0.6f)
        assertEquals(0.5f, SunArc.lineAt(line, 0f), 1e-6f)
        assertEquals(0.6f, SunArc.lineAt(line, 0.25f), 1e-6f)
        assertEquals(0.7f, SunArc.lineAt(line, 0.5f), 1e-6f)
        assertEquals(0.6f, SunArc.lineAt(line, 1f), 1e-6f)
        assertEquals(0.6f, SunArc.lineAt(line, 2f), 1e-6f)
    }

    @Test fun generatedSkylinesFitTheArt() {
        for (m in listOf(HeroMotif.DAWN, HeroMotif.DAY, HeroMotif.SUNSET)) {
            val line = BackdropSkyline.of(m)!!
            assertEquals(193, line.size, "$m samples")
            // The hills crest around the horizon, never up in the sky where the noon sun goes, never off the image.
            for (v in line) assertTrue(v > SunArc.Y_TOP + 0.08f && v <= 1f, "$m skyline $v")
        }
        assertNull(BackdropSkyline.of(HeroMotif.STARS))
    }

    @Test fun fixedSpotsOnlyForSunMotifs() {
        assertNotNull(SunArc.fixed(HeroMotif.DAY))
        assertNull(SunArc.fixed(HeroMotif.STARS))
        assertNull(SunArc.fixed(HeroMotif.GAME))
    }

    @Test fun dayIsTheLocationsNotTheDeviceZones() {
        // 7 PM CST on Nov 3 is already Nov 4 in UTC (a TV left on UTC): the sky still uses Clarksville's Nov 3.
        val sky = SkyNow.of(java.time.Instant.parse("2026-11-04T01:00:00Z"), lat, lon)
        val nov3 = tvhome.core.sky.Sun.day(LocalDate.of(2026, 11, 3), central, lat, lon)
        assertEquals(nov3.sunset, sky.day.sunset)
        // Mid-afternoon in Auckland (UTC+13, across the date line): its own Nov 3, sunrise on the UTC date before.
        val akl = SkyNow.of(java.time.Instant.parse("2026-11-03T03:00:00Z"), -36.85, 174.76)
        val sunrise = akl.day.sunrise!!
        assertTrue(kotlin.math.abs(java.time.Duration.between(java.time.Instant.parse("2026-11-02T17:14:05Z"), sunrise).seconds) < 120, "$sunrise")
        assertNotNull(SunArc.place(akl, flat))
    }
}
