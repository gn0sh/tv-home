package tvhome.core.sky

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * Where the sun is in the sky at one moment: [elevation] above the horizon in degrees (with the usual atmospheric
 * refraction, so 0 is where it looks to touch a flat horizon), [azimuth] in degrees clockwise from true north, and
 * [morning] when it has not yet crossed the meridian (hour angle below 0), on either hemisphere.
 */
data class SunPosition(val elevation: Double, val azimuth: Double, val morning: Boolean)

/**
 * One local day's sun events, as instants. A null event does not happen that day: no [sunrise] and [sunset] in the polar
 * night ([polarNight]) or the midnight sun ([midnightSun]); no [dawn] or [dusk] (civil, sun 6 degrees below) on nights
 * that never get that dark. [noon] (solar transit) always exists; [noonElevation] is the day's highest elevation.
 */
data class SunDay(
    val dawn: Instant?,
    val sunrise: Instant?,
    val noon: Instant,
    val sunset: Instant?,
    val dusk: Instant?,
    val noonElevation: Double,
) {
    val midnightSun get() = sunrise == null && noonElevation > 0
    val polarNight get() = sunrise == null && noonElevation <= 0
}

/**
 * Offline solar position and sun events (the NOAA solar calculator equations, after Meeus): no network, no dependency.
 * Good to about a minute for events and a few hundredths of a degree for the position between 1900 and 2100, far more
 * than a TV backdrop needs. Longitude is east-positive (Clarksville TN is about 36.53, -87.36). Pure and cheap: a few
 * dozen trig calls per position.
 */
object Sun {
    /** Elevation of the sun's centre at sunrise and sunset: refraction (34') plus the disc's radius (16'). */
    const val RISE_SET_ELEVATION = -0.833

    /** Civil twilight: the sun's centre 6 degrees below the horizon. */
    const val CIVIL_ELEVATION = -6.0

    fun position(at: Instant, lat: Double, lon: Double): SunPosition {
        val e = ephemeris(at)
        val ha = hourAngle(at, lon, e.eqTimeMin)
        val latR = Math.toRadians(lat)
        val haR = Math.toRadians(ha)
        val cosZen = (sin(latR) * sin(e.decl) + cos(latR) * cos(e.decl) * cos(haR)).coerceIn(-1.0, 1.0)
        val geometric = 90.0 - Math.toDegrees(acos(cosZen))
        val az = Math.toDegrees(atan2(sin(haR), cos(haR) * sin(latR) - tan(e.decl) * cos(latR))) + 180.0
        return SunPosition(geometric + refraction(geometric), (az + 360.0) % 360.0, ha < 0)
    }

    /** The sun events of [date] in [zone] at [lat], [lon]: the transit nearest local noon and the events around it. */
    fun day(date: LocalDate, zone: ZoneId, lat: Double, lon: Double): SunDay {
        var noon = date.atTime(LocalTime.NOON).atZone(zone).toInstant()
        repeat(4) {
            val ha = hourAngle(noon, lon, ephemeris(noon).eqTimeMin)
            noon = noon.minus(hours(ha / 15.0))
        }
        val noonElevation = position(noon, lat, lon).elevation
        return SunDay(
            dawn = event(noon, lat, lon, CIVIL_ELEVATION, rising = true),
            sunrise = event(noon, lat, lon, RISE_SET_ELEVATION, rising = true),
            noon = noon,
            sunset = event(noon, lat, lon, RISE_SET_ELEVATION, rising = false),
            dusk = event(noon, lat, lon, CIVIL_ELEVATION, rising = false),
            noonElevation = noonElevation,
        )
    }

    /** When the sun's centre crosses [h0] degrees before (rising) or after [noon], or null if it does not that day. */
    private fun event(noon: Instant, lat: Double, lon: Double, h0: Double, rising: Boolean): Instant? {
        var t = noon
        repeat(5) {
            val e = ephemeris(t)
            val ha0 = halfDayArc(lat, e.decl, h0) ?: return null
            val target = if (rising) -ha0 else ha0
            // Hour angle relative to this day's transit (unwrapped around [noon], so midnight-sun edges stay on the day).
            val ha = hourAngle(t, lon, e.eqTimeMin)
            val offset = Duration.between(noon, t).toMillis() / 3_600_000.0 * 15.0
            val unwrapped = offset + wrap180(ha - offset)
            t = t.plus(hours((target - unwrapped) / 15.0))
        }
        return t
    }

    /** The hour angle (degrees) at which the sun's centre is at [h0], or null when it never reaches it (polar). */
    private fun halfDayArc(lat: Double, decl: Double, h0: Double): Double? {
        val latR = Math.toRadians(lat)
        val den = cos(latR) * cos(decl)
        if (abs(den) < 1e-12) return null
        val c = (sin(Math.toRadians(h0)) - sin(latR) * sin(decl)) / den
        if (c < -1.0 || c > 1.0) return null
        return Math.toDegrees(acos(c))
    }

    private class Ephemeris(val decl: Double, val eqTimeMin: Double)

    private fun ephemeris(at: Instant): Ephemeris {
        val jd = at.toEpochMilli() / 86_400_000.0 + 2440587.5
        val t = (jd - 2451545.0) / 36525.0
        val l0 = Math.toRadians((280.46646 + t * (36000.76983 + t * 0.0003032)).mod(360.0))
        val m = Math.toRadians(357.52911 + t * (35999.05029 - 0.0001537 * t))
        val ecc = 0.016708634 - t * (0.000042037 + 0.0000001267 * t)
        val c = sin(m) * (1.914602 - t * (0.004817 + 0.000014 * t)) + sin(2 * m) * (0.019993 - 0.000101 * t) +
            sin(3 * m) * 0.000289
        val trueLong = Math.toDegrees(l0) + c
        val omega = Math.toRadians(125.04 - 1934.136 * t)
        val lambda = Math.toRadians(trueLong - 0.00569 - 0.00478 * sin(omega))
        val eps0 = 23.0 + (26.0 + (21.448 - t * (46.815 + t * (0.00059 - t * 0.001813))) / 60.0) / 60.0
        val eps = Math.toRadians(eps0 + 0.00256 * cos(omega))
        val decl = asin(sin(eps) * sin(lambda))
        val y = tan(eps / 2).let { it * it }
        val eqTime = y * sin(2 * l0) - 2 * ecc * sin(m) + 4 * ecc * y * sin(m) * cos(2 * l0) -
            0.5 * y * y * sin(4 * l0) - 1.25 * ecc * ecc * sin(2 * m)
        return Ephemeris(decl, 4.0 * Math.toDegrees(eqTime))
    }

    /** Hour angle in degrees, -180..180 (negative before the transit). */
    private fun hourAngle(at: Instant, lon: Double, eqTimeMin: Double): Double {
        val utcMin = (at.toEpochMilli().mod(86_400_000L)) / 60_000.0
        val trueSolar = (utcMin + eqTimeMin + 4.0 * lon).mod(1440.0)
        return wrap180(trueSolar / 4.0 - 180.0)
    }

    /** NOAA's refraction correction (degrees) for a geometric elevation. */
    private fun refraction(h: Double): Double {
        if (h > 85.0) return 0.0
        val te = tan(Math.toRadians(h))
        val arcsec = when {
            h > 5.0 -> 58.1 / te - 0.07 / (te * te * te) + 0.000086 / (te * te * te * te * te)
            h > -0.575 -> 1735.0 + h * (-518.2 + h * (103.4 + h * (-12.79 + h * 0.711)))
            else -> -20.772 / te
        }
        return arcsec / 3600.0
    }

    private fun wrap180(d: Double): Double = (d + 180.0).mod(360.0) - 180.0

    private fun hours(h: Double): Duration = Duration.ofMillis((h * 3_600_000.0).toLong())
}
