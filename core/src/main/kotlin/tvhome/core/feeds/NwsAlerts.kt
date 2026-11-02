package tvhome.core.feeds

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.time.Instant
import java.time.OffsetDateTime
import java.util.Locale

/** OVERRIDE takes the context card; SUFFIX is appended to the weather chip. */
enum class AlertKind { OVERRIDE, SUFFIX }

/** One active NWS alert. [event] is cleaned and at most 40 chars; [until] is `ends`, else `expires`, else null. */
data class NwsAlert(val event: String, val severity: String, val kind: AlertKind, val until: Instant?)

/** At most [NwsAlerts.MAX_ALERTS], OVERRIDE first. Empty means "checked, nothing active". */
data class AlertSet(val alerts: List<NwsAlert>) {
    val override: NwsAlert? get() = alerts.firstOrNull { it.kind == AlertKind.OVERRIDE }
    val suffixes: List<NwsAlert> get() = alerts.filter { it.kind == AlertKind.SUFFIX }

    /** "Flood Watch", or "Flood Watch +1" when more than one; null when there are none. */
    fun suffixText(): String? {
        val s = suffixes
        val first = s.firstOrNull() ?: return null
        return if (s.size == 1) first.event else "${first.event} +${s.size - 1}"
    }
}

/** Which alerts take over the card (architecture 11.3, security review `nws-hostile-json`, real TX capture). */
object AlertRules {
    private val OVERRIDE_SEVERITY = setOf("severe", "extreme")

    /** Event-name endings that are never an override, whatever the severity (spec M7-2.3: chip suffix). */
    private val NEVER_OVERRIDE = listOf("Watch", "Advisory", "Statement", "Outlook", "Message")

    /** Life-safety events that always override, whatever their name or severity (security finding `nws-cem-suffix`). */
    private val ALWAYS_OVERRIDE = setOf("civil emergency message")

    /**
     * OVERRIDE iff the severity is Severe or Extreme and the event is not a Watch, Advisory, Statement, Outlook or
     * Message, and either the event ends in "Warning" or "Emergency" or the severity is Extreme (an unknown Extreme
     * event overrides). The real NWS feed sends a Tornado Watch with severity Extreme (CI capture 2026-11-02), so
     * "Extreme always overrides" would put every Watch on the card. Call with the raw trimmed event name, before it
     * is cut to 40 chars, so a long name keeps its ending. Exception: "Civil Emergency Message" always overrides.
     */
    fun classify(event: String, severity: String): AlertKind {
        val sev = severity.trim().lowercase(Locale.ROOT)
        val name = event.trim()
        if (name.lowercase(Locale.ROOT) in ALWAYS_OVERRIDE) return AlertKind.OVERRIDE
        if (sev !in OVERRIDE_SEVERITY) return AlertKind.SUFFIX
        if (NEVER_OVERRIDE.any { name.endsWith(it, ignoreCase = true) }) return AlertKind.SUFFIX
        val strong = name.endsWith("Warning", ignoreCase = true) || name.endsWith("Emergency", ignoreCase = true)
        return if (strong || sev == "extreme") AlertKind.OVERRIDE else AlertKind.SUFFIX
    }
}

object NwsAlerts {
    const val MAX_ALERTS = 8

    /** Deeper JSON than this is not an NWS document; rejected before parsing (`nws-hostile-json`). */
    const val MAX_DEPTH = 32

    private val json = Json { ignoreUnknownKeys = true }
    private val KEEP_TYPES = setOf("Alert", "Update")
    private val SEVERITY_RANK = listOf("Extreme", "Severe", "Moderate", "Minor")

    /** `https://api.weather.gov/alerts/active?point=36.53,-87.36` (two decimals, like Open-Meteo). */
    fun url(loc: Location): HttpUrl =
        ("https://api.weather.gov/alerts/active?point=" +
            "%.2f".format(Locale.ROOT, loc.lat) + "," + "%.2f".format(Locale.ROOT, loc.lon)).toHttpUrl()

    /**
     * The active alerts that apply now, or null when [raw] is not a GeoJSON FeatureCollection (feed changed, error page,
     * hostile document). Never throws. Kept: `status == "Actual"`, `messageType` Alert or Update, and
     * `now < (ends ?: expires)` (kept when both are missing: the endpoint lists only active alerts). A feature missing
     * `event` is skipped. Duplicates of one event collapse to the one lasting longest.
     */
    fun parse(raw: String, now: Instant): AlertSet? = try {
        if (depthOver(raw, MAX_DEPTH)) null else parseUnsafe(raw, now)
    } catch (_: Throwable) {
        null // includes StackOverflowError and OutOfMemoryError on hostile input
    }

    private fun parseUnsafe(raw: String, now: Instant): AlertSet? {
        val root = json.parseToJsonElement(raw) as? JsonObject ?: return null
        val features = root["features"] as? JsonArray ?: return null
        val out = ArrayList<NwsAlert>()
        for (f in features) {
            val p = (f as? JsonObject)?.get("properties") as? JsonObject ?: continue
            if (p["status"].str() != "Actual") continue
            if (p["messageType"].str() !in KEEP_TYPES) continue
            val rawEvent = p["event"].str()?.trim() ?: continue
            val event = Feeds.clean(rawEvent) ?: continue
            val severity = Feeds.clean(p["severity"].str()) ?: "Unknown"
            val until = instant(p["ends"].str()) ?: instant(p["expires"].str())
            if (until != null && !now.isBefore(until)) continue
            out += NwsAlert(event, severity, AlertRules.classify(rawEvent, severity), until)
        }
        return AlertSet(finish(out))
    }

    /** Dedupe by event (longest-lasting wins), order OVERRIDE first then by severity and end, cap at [MAX_ALERTS]. */
    internal fun finish(alerts: List<NwsAlert>): List<NwsAlert> =
        alerts.groupBy { it.event }
            .map { (_, same) -> same.maxWith(compareBy<NwsAlert, Instant?>(nullsLast()) { it.until }) }
            .sortedWith(
                compareBy<NwsAlert> { it.kind.ordinal }
                    .thenBy { SEVERITY_RANK.indexOf(it.severity).let { i -> if (i < 0) SEVERITY_RANK.size else i } }
                    .thenBy(nullsLast()) { it.until },
            )
            .take(MAX_ALERTS)

    private fun instant(s: String?): Instant? = s?.let { runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull() }

    /** True when brackets nest deeper than [max] outside strings. Linear, no allocation. */
    internal fun depthOver(raw: String, max: Int): Boolean {
        var depth = 0
        var inString = false
        var escape = false
        for (c in raw) {
            if (inString) {
                when {
                    escape -> escape = false
                    c == '\\' -> escape = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '{', '[' -> if (++depth > max) return true
                '}', ']' -> depth--
            }
        }
        return false
    }

    // ---- Disk cache (M7-B4). The file is never trusted: decoded data goes through the same limits as a parse. ----

    @Serializable internal class AlertDto(val event: String, val severity: String, val kind: String, val until: String? = null)
    @Serializable internal class AlertSetDto(val alerts: List<AlertDto>)

    internal fun toDto(s: AlertSet) = AlertSetDto(s.alerts.map { AlertDto(it.event, it.severity, it.kind.name, it.until?.toString()) })

    /** Null when anything is out of bounds: an unknown kind, a bad instant, an empty event. Strings re-cleaned. */
    internal fun fromDto(d: AlertSetDto): AlertSet? {
        if (d.alerts.size > MAX_ALERTS) return null
        val out = d.alerts.map { a ->
            val kind = AlertKind.entries.firstOrNull { it.name == a.kind } ?: return null
            val until = a.until?.let { runCatching { Instant.parse(it) }.getOrNull() ?: return null }
            NwsAlert(Feeds.clean(a.event) ?: return null, Feeds.clean(a.severity) ?: "Unknown", kind, until)
        }
        return AlertSet(finish(out))
    }
}
