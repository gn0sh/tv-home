package tvhome.core.sky

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Reference values from an independent ephemeris (PyEphem 4.x, VSOP87): sunrise and sunset with the standard 34' of
 * refraction plus the upper limb (NOAA's -0.833 degrees), civil twilight at the centre 6 degrees down, and the position
 * without refraction (compared within the refraction allowance).
 * Clarksville TN (ZIP 37042) is 36.53 N, 87.36 W.
 */
class SunTest {
    private val lat = 36.53
    private val lon = -87.36
    private val central = ZoneId.of("America/Chicago")

    private fun assertNear(expected: String, actual: Instant?, toleranceS: Long = 120, what: String = expected) {
        assertNotNull(actual, what)
        val d = abs(Duration.between(Instant.parse(expected), actual).seconds)
        assertTrue(d <= toleranceS, "$what: $actual is ${d}s from $expected")
    }

    @Test fun clarksvilleNovember3Events() {
        val d = Sun.day(LocalDate.of(2026, 11, 3), central, lat, lon)
        assertNear("2026-11-03T11:48:06Z", d.dawn, what = "civil dawn 5:48 AM CST")
        assertNear("2026-11-03T12:14:55Z", d.sunrise, what = "sunrise 6:15 AM CST")
        assertNear("2026-11-03T22:50:38Z", d.sunset, what = "sunset 4:51 PM CST")
        assertNear("2026-11-03T23:17:27Z", d.dusk, what = "civil dusk 5:17 PM CST")
        assertNear("2026-11-03T17:33:00Z", d.noon, toleranceS = 60, what = "transit")
        assertFalse(d.polarNight || d.midnightSun)
    }

    @Test fun clarksvilleSolsticeEventsCrossUtcMidnight() {
        val d = Sun.day(LocalDate.of(2026, 6, 21), central, lat, lon)
        assertNear("2026-06-21T10:00:57Z", d.dawn)
        assertNear("2026-06-21T10:31:34Z", d.sunrise, what = "sunrise 5:32 AM CDT")
        assertNear("2026-06-22T01:11:03Z", d.sunset, what = "sunset 8:11 PM CDT (next UTC day)")
        assertNear("2026-06-22T01:41:40Z", d.dusk)
        assertEquals(76.9, d.noonElevation, 0.2)
    }

    @Test fun dstChangeDaysUseTheDaysOwnSun() {
        val spring = Sun.day(LocalDate.of(2026, 3, 8), central, lat, lon) // clocks forward at 2 AM
        assertNear("2026-03-08T12:10:11Z", spring.sunrise, what = "7:10 AM CDT")
        assertNear("2026-03-08T23:50:36Z", spring.sunset, what = "6:51 PM CDT")
        val fall = Sun.day(LocalDate.of(2026, 11, 1), central, lat, lon) // clocks back at 2 AM
        assertNear("2026-11-01T12:12:55Z", fall.sunrise, what = "6:13 AM CST")
        assertNear("2026-11-01T22:52:39Z", fall.sunset, what = "4:53 PM CST")
    }

    @Test fun positionMatchesReference() {
        fun check(at: String, elev: Double, az: Double, morning: Boolean) {
            val p = Sun.position(Instant.parse(at), lat, lon)
            assertEquals(elev, p.elevation, 0.1, "$at elevation")
            assertEquals(az, p.azimuth, 0.1, "$at azimuth")
            assertEquals(morning, p.morning, "$at morning")
        }
        check("2026-11-03T14:00:00Z", 17.95, 125.62, morning = true) // 8 AM CST
        check("2026-11-03T18:00:00Z", 37.85, 188.26, morning = false) // noon CST: just past the transit
        check("2026-11-04T01:00:00Z", -26.39, 270.03, morning = false) // 7 PM CST: long set
        check("2026-06-21T17:00:00Z", 72.86, 136.27, morning = true) // noon CDT
    }

    @Test fun sevenPmInNovemberIsNight() {
        // The bug this fixes: at 7 PM the clock buckets still said "evening" with a sun on the horizon.
        val p = Sun.position(Instant.parse("2026-11-04T01:00:00Z"), lat, lon)
        assertTrue(p.elevation < -6.0)
    }

    @Test fun southernHemisphereMorningIsBeforeTransit() {
        val sydney = -33.87 to 151.21
        val am = Sun.position(Instant.parse("2026-11-03T22:00:00Z"), sydney.first, sydney.second) // 9 AM AEDT
        assertEquals(37.58, am.elevation, 0.15)
        assertEquals(83.33, am.azimuth, 0.15)
        assertTrue(am.morning)
        val pm = Sun.position(Instant.parse("2026-11-04T06:00:00Z"), sydney.first, sydney.second) // 5 PM AEDT
        assertEquals(28.82, pm.elevation, 0.15)
        assertFalse(pm.morning)
    }

    @Test fun polarNightHasNoSunriseButCivilTwilight() {
        val d = Sun.day(LocalDate.of(2026, 12, 21), ZoneId.of("Europe/Oslo"), 69.65, 18.96) // Tromso
        assertNull(d.sunrise)
        assertNull(d.sunset)
        assertTrue(d.polarNight)
        assertFalse(d.midnightSun)
        assertEquals(-3.1, d.noonElevation, 0.3)
        assertNear("2026-12-21T08:31:15Z", d.dawn)
        assertNear("2026-12-21T12:53:08Z", d.dusk)
    }

    @Test fun midnightSunHasNoSunsetOrDusk() {
        val d = Sun.day(LocalDate.of(2026, 6, 21), ZoneId.of("Europe/Oslo"), 69.65, 18.96)
        assertNull(d.sunrise)
        assertNull(d.sunset)
        assertNull(d.dawn)
        assertNull(d.dusk)
        assertTrue(d.midnightSun)
    }

    @Test fun polesDoNotDivideByZero() {
        val north = Sun.day(LocalDate.of(2026, 6, 21), ZoneId.of("UTC"), 90.0, 0.0)
        assertNull(north.sunrise)
        assertTrue(north.midnightSun)
        val south = Sun.day(LocalDate.of(2026, 6, 21), ZoneId.of("UTC"), -90.0, 0.0)
        assertTrue(south.polarNight)
        assertTrue(Sun.position(Instant.parse("2026-06-21T12:00:00Z"), -90.0, 0.0).elevation < 0)
    }

    @Test fun everyDayOfTheYearHasOrderedEventsInClarksville() {
        var d = LocalDate.of(2026, 1, 1)
        while (d.year == 2026) {
            val s = Sun.day(d, central, lat, lon)
            val ev = listOf(s.dawn, s.sunrise, s.noon, s.sunset, s.dusk)
            ev.forEach { assertNotNull(it, "$d") }
            assertEquals(ev, ev.sortedBy { it }, "$d order")
            assertEquals(d, LocalDate.ofInstant(s.noon, central), "$d transit on the day")
            // Geometric -0.833 at sunrise; refraction lifts what you see to about -0.4 (the upper limb on the horizon).
            assertEquals(-0.43, Sun.position(s.sunrise!!, lat, lon).elevation, 0.1, "$d sunrise elevation")
            d = d.plusDays(1)
        }
    }
}
