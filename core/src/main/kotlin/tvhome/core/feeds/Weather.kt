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
    /** Today's daily maximum and minimum (°F), when the document has them. */
    val highF: Int? = null,
    val lowF: Int? = null,
    /** Local hour (0-23) of today's warmest hourly temperature; null when the hourly series is missing. */
    val highHour: Int? = null,
    /** Lowest hourly temperature of the coming (or current) night, 7 PM to 7 AM; null when the hours are not all there. */
    val overnightLowF: Int? = null,
    /** The daily forecast from today (Board: up to [WeatherFeed.FORECAST_DAYS]); empty when the document has none. */
    val days: List<DayForecast> = emptyList(),
) {
    val chip: String
        get() {
            // Drop whole parts, never cut mid-word: first "rain after ..", then the condition.
            val temp = "$tempF°F"
            val withCond = if (condition.isNotEmpty()) "$temp $condition" else temp
            val full = if (nextPrecip != null) "$withCond · $nextPrecip" else withCond
            return listOf(full, withCond, temp).first { it.length <= Feeds.LABEL_CAP }
        }
}

/**
 * One forecast day. [condition] is the fixed label for the day's WMO code ("" when unknown); [precipChance] is the
 * day's maximum precipitation probability in percent, null when missing.
 */
data class DayForecast(val date: java.time.LocalDate, val highF: Int, val lowF: Int, val condition: String, val precipChance: Int?)

object WeatherFeed {
    /** Days asked for: today and the six after it (Board's 7-day forecast). */
    const val FORECAST_DAYS = 7
    private val json = Json { ignoreUnknownKeys = true }
    private val HOUR_FORMAT = DateTimeFormatter.ofPattern("h a", Locale.US)
    private const val WINDOW_HOURS = 12L

    fun url(loc: Location): HttpUrl =
        ("https://api.open-meteo.com/v1/forecast" +
            "?latitude=${"%.2f".format(Locale.ROOT, loc.lat)}&longitude=${"%.2f".format(Locale.ROOT, loc.lon)}" +
            "&current=temperature_2m,weather_code&hourly=weather_code,temperature_2m" +
            "&daily=temperature_2m_max,temperature_2m_min,weather_code,precipitation_probability_max" +
            "&temperature_unit=fahrenheit&forecast_days=$FORECAST_DAYS&timezone=auto").toHttpUrl()

    /** Any missing or malformed required field is null ("no weather"), never an exception. */
    fun parse(raw: String): WeatherNow? = runCatching { parseUnsafe(raw) }.getOrNull()

    private fun parseUnsafe(raw: String): WeatherNow? {
        if (NwsAlerts.depthOver(raw, MAX_DEPTH)) return null
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
        val extra = timeOfDayFields(root, now)
        return WeatherNow(
            temp.roundToInt(), describe(code), precipNow, next, extra.high, extra.low, extra.highHour, extra.overnightLow,
            dailyForecast(root, now.toLocalDate()),
        )
    }

    /**
     * Days from [today] on, at most [FORECAST_DAYS], in order. A day missing its high or low is dropped (never drawn with
     * a made-up value); a missing code or precipitation chance is just left out. Never fails the whole document.
     */
    private fun dailyForecast(root: JsonObject, today: java.time.LocalDate): List<DayForecast> = runCatching {
        val daily = root["daily"] as? JsonObject ?: return@runCatching emptyList()
        val days = daily["time"] as? JsonArray ?: return@runCatching emptyList()
        val highs = daily["temperature_2m_max"] as? JsonArray
        val lows = daily["temperature_2m_min"] as? JsonArray
        val codes = daily["weather_code"] as? JsonArray
        val chances = daily["precipitation_probability_max"] as? JsonArray
        val out = ArrayList<DayForecast>()
        for (i in 0 until minOf(days.size, MAX_DAYS)) {
            val date = (days[i] as? JsonPrimitive)?.contentOrNull?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() } ?: continue
            if (date.isBefore(today) || out.any { it.date == date }) continue
            val high = temp(highs?.getOrNull(i)) ?: continue
            val low = temp(lows?.getOrNull(i)) ?: continue
            val code = (codes?.getOrNull(i) as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
            val chance = (chances?.getOrNull(i) as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
                ?.takeIf { it in 0.0..100.0 }?.roundToInt()
            out += DayForecast(date, high, low, code?.let(::describe).orEmpty(), chance)
            if (out.size >= FORECAST_DAYS) break
        }
        out.sortedBy { it.date }
    }.getOrDefault(emptyList())

    private class Extra(val high: Int?, val low: Int?, val highHour: Int?, val overnightLow: Int?)

    private fun temp(e: kotlinx.serialization.json.JsonElement?): Int? =
        (e as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull?.takeIf { it in -100.0..150.0 }?.roundToInt()

    /**
     * Daily min/max for today and the hourly temperatures behind the morning arc and the overnight low. Every part is
     * optional: a missing or malformed part is null and never fails the whole weather document.
     */
    private fun timeOfDayFields(root: JsonObject, now: LocalDateTime): Extra = runCatching {
        val today = now.toLocalDate()
        var high: Int? = null
        var low: Int? = null
        val daily = root["daily"] as? JsonObject
        val days = daily?.get("time") as? JsonArray
        if (days != null) {
            val i = days.take(MAX_DAYS).indexOfFirst { (it as? JsonPrimitive)?.contentOrNull == today.toString() }
            if (i >= 0) {
                high = temp((daily["temperature_2m_max"] as? JsonArray)?.getOrNull(i))
                low = temp((daily["temperature_2m_min"] as? JsonArray)?.getOrNull(i))
            }
        }
        val hourly = root["hourly"] as? JsonObject
        val times = hourly?.get("time") as? JsonArray
        val temps = hourly?.get("temperature_2m") as? JsonArray
        val byHour = HashMap<LocalDateTime, Int>()
        if (times != null && temps != null) {
            for (i in 0 until minOf(times.size, temps.size, MAX_HOURS)) {
                val t = (times[i] as? JsonPrimitive)?.contentOrNull?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() } ?: continue
                byHour[t] = temp(temps[i]) ?: continue
            }
        }
        val todays = byHour.filterKeys { it.toLocalDate() == today }
        val peak = todays.maxByOrNull { it.value }
        // The night in progress (before 7 AM) started yesterday evening; otherwise it starts tonight at 7 PM.
        val nightStart = (if (now.hour < NIGHT_END) today.minusDays(1) else today).atTime(NIGHT_START, 0)
        val from = maxOf(nightStart, now.truncatedTo(java.time.temporal.ChronoUnit.HOURS))
        val nightEnd = nightStart.toLocalDate().plusDays(1).atTime(NIGHT_END, 0)
        val hours = generateSequence(from) { it.plusHours(1) }.takeWhile { !it.isAfter(nightEnd) }.toList()
        val night = hours.map { byHour[it] }
        val overnight = if (night.isNotEmpty() && night.all { it != null }) night.filterNotNull().min() else null
        Extra(high, low, peak?.key?.hour, overnight)
    }.getOrDefault(Extra(null, null, null, null))

    private const val MAX_DAYS = 16
    private const val MAX_DEPTH = 32
    private const val NIGHT_START = 19
    private const val NIGHT_END = 7

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
