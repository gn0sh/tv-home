package tvhome.core.ui

import tvhome.core.sky.Sun
import tvhome.core.sky.SunDay
import tvhome.core.sky.SunPosition
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.math.roundToInt

/** Where the backdrop draws the sun's centre: fractions of the backdrop image (1920 x 600), from its top left. */
data class SunSpot(val x: Float, val y: Float)

/**
 * The real sky at one moment, at the location the weather feed uses: the sun's [position] and the events of the solar
 * [day] around [at]. Built once a minute on the hero's clock tick; [of] is a few dozen trig calls.
 */
data class SkyNow(val at: Instant, val position: SunPosition, val day: SunDay) {
    companion object {
        /**
         * The day is the location's own (local mean solar time, from the longitude), never the device's time zone: a
         * TV whose zone is wrong or far away still gets the sunrise and sunset around [at] at that place, and a
         * location across the date line gets its own date.
         */
        fun of(at: Instant, lat: Double, lon: Double): SkyNow {
            val solar = ZoneOffset.ofTotalSeconds((lon * 240.0).roundToInt().coerceIn(-43_200, 43_200))
            return SkyNow(at, Sun.position(at, lat, lon), Sun.day(at.atOffset(solar).toLocalDate(), solar, lat, lon))
        }
    }
}

/**
 * Maps the real sun onto the backdrop art. Across: from [X_RISE] at sunrise to [X_SET] at sunset, by the fraction of the
 * day gone (east on the left, as when facing south), kept right of the calm title wash. Up: at elevation 0 the disc's
 * centre sits on the hills' top edge at that x ([BackdropSkyline], or the flat horizon), rising linearly with elevation
 * to [Y_TOP] at the day's highest elevation, which is under the top strip (clock) with room for the disc. Below the hills
 * the app clips it; more than [HIDDEN_BELOW] degrees down there is no sun at all.
 */
object SunArc {
    const val X_RISE = 0.56f
    const val X_SET = 0.92f

    /** Top strip bottom (100 dp of the 396 dp backdrop = 0.25 of the image) plus the largest disc and a margin. */
    const val Y_TOP = 0.33f

    /** Degrees: below this the disc and its glow are well under the hills, so nothing is drawn. */
    const val HIDDEN_BELOW = -3.0

    /** The spot for [sky], or null when the sun is down. [skyline] is the motif's hill line (null: flat horizon). */
    fun place(sky: SkyNow, skyline: FloatArray?): SunSpot? {
        val elev = sky.position.elevation
        if (elev < HIDDEN_BELOW) return null
        val day = sky.day
        val rise = day.sunrise
        val set = day.sunset
        val f = if (rise != null && set != null && set.isAfter(rise)) {
            Duration.between(rise, sky.at).toMillis().toDouble() / Duration.between(rise, set).toMillis()
        } else {
            // Midnight sun: the day's fraction by solar time, noon in the middle.
            0.5 + Duration.between(day.noon, sky.at).toMillis() / 86_400_000.0
        }
        val x = (X_RISE + (X_SET - X_RISE) * f.coerceIn(-0.1, 1.1)).toFloat()
        val ground = skyline?.let { lineAt(it, x) } ?: BackdropSkyline.HORIZON
        val k = (elev / day.noonElevation.coerceAtLeast(1.0)).coerceIn(-0.5, 1.0).toFloat()
        return SunSpot(x, ground - k * (ground - Y_TOP))
    }

    /**
     * The fixed spot each sun motif's art had before the sun followed the real sky (render_backdrops.py), used when no
     * location is set: the clock-bucket scenes look as they always did.
     */
    fun fixed(motif: HeroMotif): SunSpot? = when (motif) {
        HeroMotif.DAWN -> SunSpot(0.74f, BackdropSkyline.HORIZON - 0.10f)
        HeroMotif.DAY -> SunSpot(0.72f, 0.36f)
        HeroMotif.SUNSET -> SunSpot(0.74f, BackdropSkyline.HORIZON - 0.09f)
        else -> null
    }

    /** The skyline's height at [x] (0..1), linear between samples. */
    fun lineAt(skyline: FloatArray, x: Float): Float {
        if (skyline.isEmpty()) return BackdropSkyline.HORIZON
        if (skyline.size == 1) return skyline[0]
        val p = x.coerceIn(0f, 1f) * (skyline.size - 1)
        val i = p.toInt().coerceAtMost(skyline.size - 2)
        val t = p - i
        return skyline[i] + (skyline[i + 1] - skyline[i]) * t
    }
}
