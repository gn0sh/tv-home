package tvhome.core.ui

import java.time.Instant
import java.time.ZoneOffset
import kotlin.math.abs
import kotlin.math.roundToInt
import tvhome.core.sky.Sun
import tvhome.core.sky.SunDay
import tvhome.core.sky.SunPosition

/** Where the backdrop draws the sun's centre: fractions of the backdrop image (1920 x 600), from its top left. */
data class SunSpot(val x: Float, val y: Float)

/**
 * The real sky at one moment, at the location the weather feed uses: the sun's [position] and the events of the solar
 * [day] around [at]. Built once a minute on the hero's clock tick; [of] is a few dozen trig calls.
 */
data class SkyNow(
    val at: Instant,
    val position: SunPosition,
    val day: SunDay,
    /**
     * Azimuths (degrees from north) of the day's sunrise, transit and sunset; null rise/set when there is none. No
     * defaults (skynow-default-trap): build it with [of], which computes all three from the same place and day.
     */
    val riseAzimuth: Double?,
    val noonAzimuth: Double,
    val setAzimuth: Double?,
) {
    companion object {
        /**
         * The day is the location's own (local mean solar time, from the longitude), never the device's time zone: a
         * TV whose zone is wrong or far away still gets the sunrise and sunset around [at] at that place, and a
         * location across the date line gets its own date.
         */
        fun of(at: Instant, lat: Double, lon: Double): SkyNow {
            val solar = ZoneOffset.ofTotalSeconds((lon * 240.0).roundToInt().coerceIn(-43_200, 43_200))
            val day = Sun.day(at.atOffset(solar).toLocalDate(), solar, lat, lon)
            return SkyNow(
                at, Sun.position(at, lat, lon), day,
                riseAzimuth = day.sunrise?.let { Sun.position(it, lat, lon).azimuth },
                noonAzimuth = Sun.position(day.noon, lat, lon).azimuth,
                setAzimuth = day.sunset?.let { Sun.position(it, lat, lon).azimuth },
            )
        }
    }
}

/**
 * Maps the real sun onto the backdrop art as seen facing the noon sun (south for Brian): across by azimuth, the transit
 * at the centre (0.5), the sunrise azimuth at [X_RISE] on the left (east) and the sunset azimuth at [X_SET] on the right
 * (west), each side scaled to its own half of the day's span (sun-position-fix: the old 0.56-0.92 time arc put the
 * whole day right of centre, so a 2:39 PM sun sat by the sunset end). Up: at elevation 0 the disc's
 * centre sits on the hills' top edge at that x ([BackdropSkyline], or the flat horizon), rising linearly with elevation
 * to [Y_TOP] at the day's highest elevation, which is under the top strip (clock) with room for the disc. Below the hills
 * the app clips it; more than [HIDDEN_BELOW] degrees down there is no sun at all.
 */
object SunArc {
    const val X_RISE = 0.10f
    const val X_SET = 0.90f
    const val X_NOON = 0.5f

    /** Top strip bottom (100 dp of the 396 dp backdrop = 0.25 of the image) plus the largest disc and a margin. */
    const val Y_TOP = 0.33f

    /** Degrees: below this the disc and its glow are well under the hills, so nothing is drawn. */
    const val HIDDEN_BELOW = -3.0

    /** The spot for [sky], or null when the sun is down. [skyline] is the motif's hill line (null: flat horizon). */
    fun place(sky: SkyNow, skyline: FloatArray?): SunSpot? {
        val elev = sky.position.elevation
        if (elev < HIDDEN_BELOW) return null
        val day = sky.day
        // Signed degrees from the transit's azimuth. The sides assume Brian's view: northern hemisphere, facing south,
        // so negative is east (sunrise, left) and positive west (sunset, right). In the southern hemisphere the transit
        // is to the north and the same formula mirrors: sunrise on the right, sunset on the left (as facing north).
        val d = wrap180(sky.position.azimuth - sky.noonAzimuth)
        val riseSide = sky.riseAzimuth?.let { abs(wrap180(it - sky.noonAzimuth)) } ?: 180.0
        val setSide = sky.setAzimuth?.let { abs(wrap180(it - sky.noonAzimuth)) } ?: 180.0
        val f = if (d < 0) d / riseSide.coerceAtLeast(1.0) else d / setSide.coerceAtLeast(1.0) // -1 at rise, 1 at set
        val half = if (d < 0) X_NOON - X_RISE else X_SET - X_NOON
        val x = (X_NOON + half * f.coerceIn(-1.1, 1.1)).toFloat()
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

    private fun wrap180(deg: Double): Double = (deg + 180.0).mod(360.0) - 180.0

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
