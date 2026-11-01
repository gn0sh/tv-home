package tvhome.core.feeds

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/** A place on Earth, set by Brian on the device (never in source). Stored and sent at two decimals (about 1 km). */
data class Location(val lat: Double, val lon: Double) {
    companion object {
        fun of(lat: Double, lon: Double): Location? =
            if (lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0) Location(lat, lon) else null
    }
}

enum class Precip(val word: String) { RAIN("rain"), SNOW("snow") }

/**
 * What the top-bar chip shows. [precipNow] drives the motif overlay only; it never changes the theme.
 * [nextPrecip] is e.g. "rain after 9 PM", only when nothing falls now.
 */
data class WeatherNow(
    val tempF: Int,
    val condition: String,
    val precipNow: Precip?,
    val nextPrecip: String?,
) {
    val chip: String
        get() = buildString {
            append(tempF).append("°F")
            if (condition.isNotEmpty()) append(' ').append(condition)
            if (nextPrecip != null) append(" · ").append(nextPrecip)
        }.take(Feeds.LABEL_CAP)
}

object WeatherFeed {
    private val json = Json { ignoreUnknownKeys = true }
    private val HOUR_FORMAT = DateTimeFormatter.ofPattern("h a", Locale.US)
    private const val WINDOW_HOURS = 12L

    fun url(loc: Location): HttpUrl =
        ("https://api.open-meteo.com/v1/forecast" +
            "?latitude=${"%.2f".format(Locale.ROOT, loc.lat)}&longitude=${"%.2f".format(Locale.ROOT, loc.lon)}" +
            "&current=temperature_2m,weather_code&hourly=weather_code" +
            "&temperature_unit=fahrenheit&forecast_days=2&timezone=auto").toHttpUrl()

    /** Any missing or malformed required field is null ("no weather"), never an exception. */
    fun parse(raw: String): WeatherNow? = runCatching { parseUnsafe(raw) }.getOrNull()

    private fun parseUnsafe(raw: String): WeatherNow? {
        val root = json.parseToJsonElement(raw) as? JsonObject ?: return null
        val current = root["current"] as? JsonObject ?: return null
        val temp = current.number("temperature_2m") ?: return null
        if (temp !in -100.0..150.0) return null
        val code = current.int("weather_code") ?: return null
        val now = (current["time"] as? JsonPrimitive)?.contentOrNull?.let { LocalDateTime.parse(it) } ?: return null

        val precipNow = precipOf(code)
        var next: String? = null
        if (precipNow == null) {
            val hourly = root["hourly"] as? JsonObject
            val times = hourly?.get("time") as? JsonArray
            val codes = hourly?.get("weather_code") as? JsonArray
            if (times != null && codes != null) {
                val from = now.truncatedTo(java.time.temporal.ChronoUnit.HOURS)
                for (i in 0 until minOf(times.size, codes.size, MAX_HOURS)) {
                    val t = (times[i] as? JsonPrimitive)?.contentOrNull?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() } ?: continue
                    if (!t.isAfter(from) || Duration.between(now, t) > Duration.ofHours(WINDOW_HOURS)) continue
                    val p = precipOf((codes[i] as? JsonPrimitive)?.intOrNull ?: continue) ?: continue
                    next = "${p.word} after ${HOUR_FORMAT.format(t)}"
                    break
                }
            }
        }
        return WeatherNow(temp.roundToInt(), describe(code), precipNow, next)
    }

    private const val MAX_HOURS = 96

    private fun JsonObject.number(key: String): Double? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull

    /** WMO weather interpretation codes, as Open-Meteo documents them. */
    internal fun precipOf(code: Int): Precip? = when (code) {
        in 51..67, in 80..82, in 95..99 -> Precip.RAIN
        in 71..77, in 85..86 -> Precip.SNOW
        else -> null
    }

    /** Fixed labels only; an unknown code gives an empty string (the chip then shows just the temperature). */
    internal fun describe(code: Int): String = when (code) {
        0 -> "Clear"
        1 -> "Mostly clear"
        2 -> "Partly cloudy"
        3 -> "Overcast"
        45, 48 -> "Fog"
        51, 53, 55 -> "Drizzle"
        56, 57 -> "Freezing drizzle"
        61 -> "Light rain"
        63 -> "Rain"
        65 -> "Heavy rain"
        66, 67 -> "Freezing rain"
        71 -> "Light snow"
        73 -> "Snow"
        75 -> "Heavy snow"
        77 -> "Snow grains"
        80 -> "Light showers"
        81 -> "Showers"
        82 -> "Heavy showers"
        85, 86 -> "Snow showers"
        95 -> "Thunderstorm"
        96, 99 -> "Thunderstorm, hail"
        else -> ""
    }
}

/** Shared limits for text that came from outside. */
object Feeds {
    /** Longest team name or label we will keep or draw. */
    const val LABEL_CAP = 40

    /** Printable, trimmed, at most [LABEL_CAP] characters; null if nothing is left. */
    fun clean(s: String?): String? {
        s ?: return null
        val out = StringBuilder()
        var count = 0
        var marks = 0
        var i = 0
        while (i < s.length && count < LABEL_CAP) {
            val cp = s.codePointAt(i)
            i += Character.charCount(cp)
            val type = Character.getType(cp)
            when (type.toByte()) {
                Character.CONTROL, Character.FORMAT, Character.SURROGATE, Character.UNASSIGNED, Character.PRIVATE_USE,
                Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR -> continue // bidi controls, zero-width, lone surrogates
                Character.NON_SPACING_MARK, Character.ENCLOSING_MARK, Character.COMBINING_SPACING_MARK ->
                    if (++marks > MAX_MARKS) continue
                else -> marks = 0
            }
            out.appendCodePoint(cp)
            count++
        }
        return out.toString().trim().takeIf { it.isNotEmpty() }
    }

    private const val MAX_MARKS = 2
}

internal fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
