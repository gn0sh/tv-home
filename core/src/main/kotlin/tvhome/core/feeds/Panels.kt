package tvhome.core.feeds

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import tvhome.core.state.CacheFile
import tvhome.core.state.ageText
import java.time.Duration
import java.time.Instant

/** A value and the time it was fetched (never the time it was drawn). ADR 0008 section 1. */
data class Stamped<out T>(val value: T, val at: Instant)

/**
 * How one panel treats age. Stale means `age >= staleAfter`. The age line ("Updated 15 min ago") is shown once
 * `age >= ageShownAfter`, so fresh panels carry no extra text. [hideWhenStale] false keeps a stale value on screen
 * (marked stale); true hides it.
 */
data class PanelPolicy(
    val staleAfter: Duration,
    val ageShownAfter: Duration = Duration.ofMinutes(15),
    val hideWhenStale: Boolean = true,
)

/** What a panel shows: switched off, nothing usable, or a value with its optional age line. */
sealed interface PanelView<out T> {
    object Off : PanelView<Nothing> { override fun toString() = "Off" }
    object NoData : PanelView<Nothing> { override fun toString() = "NoData" }
    data class Shown<out T>(val value: T, val ageText: String?, val stale: Boolean) : PanelView<T>
}

/** The panel table in ADR 0008 section 1. Games keep their own `GameDay` rules; the brief is M2. */
object PanelPolicies {
    val WEATHER = PanelPolicy(staleAfter = Duration.ofHours(2))
    val ALERTS = PanelPolicy(staleAfter = Duration.ofMinutes(30))
    val CALENDAR = PanelPolicy(staleAfter = Duration.ofHours(3))

    /**
     * A stamp further in the future than this is not trusted (clock went back, or a planted file): it is no data, so
     * it can never pass as fresh (security finding `panel-cache-future-stamp`). A little skew is tolerated.
     */
    val MAX_FUTURE_SKEW: Duration = Duration.ofMinutes(5)
}

/** Pure; the one staleness rule for every panel. */
fun <T> view(s: Stamped<T>?, p: PanelPolicy, enabled: Boolean, now: Instant): PanelView<T> {
    if (!enabled) return PanelView.Off
    s ?: return PanelView.NoData
    if (s.at.isAfter(now.plus(PanelPolicies.MAX_FUTURE_SKEW))) return PanelView.NoData
    val age = Duration.between(s.at, now).let { if (it.isNegative) Duration.ZERO else it }
    val stale = age >= p.staleAfter
    if (stale && p.hideWhenStale) return PanelView.NoData
    val text = if (age >= p.ageShownAfter) ageText(age.toMinutes()) else null
    return PanelView.Shown(s.value, text, stale)
}

/**
 * The on-disk envelope for one panel's last good value: `{"v":1,"at":"<ISO instant>","data":{...}}`. Any failure to
 * decode (corrupt, oversize, other version, future stamp) is "no cache", never an exception. Callers re-validate the
 * decoded data with the same limits as a fresh parse: the file is never trusted.
 */
object PanelCacheCodec {
    const val VERSION = 1

    /** Read cap per panel file (ADR 0008). */
    const val MAX_BYTES = 64 * 1024

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable private class Envelope(val v: Int, val at: String, val data: JsonElement)

    fun <T> encode(s: Stamped<T>, ser: KSerializer<T>): String =
        json.encodeToString(Envelope.serializer(), Envelope(VERSION, s.at.toString(), json.encodeToJsonElement(ser, s.value)))

    fun <T> decode(text: String?, ser: KSerializer<T>, now: Instant): Stamped<T>? {
        if (text == null || text.length > MAX_BYTES || text.toByteArray(Charsets.UTF_8).size > MAX_BYTES) return null
        return try {
            val e = json.decodeFromString(Envelope.serializer(), text)
            if (e.v != VERSION) return null
            val at = Instant.parse(e.at)
            if (at.isAfter(now.plus(PanelPolicies.MAX_FUTURE_SKEW))) return null
            Stamped(json.decodeFromJsonElement(ser, e.data), at)
        } catch (_: Throwable) {
            null
        }
    }

    /** Never throws. */
    fun <T> load(file: CacheFile, ser: KSerializer<T>, now: Instant): Stamped<T>? =
        try { decode(file.read(), ser, now) } catch (_: Throwable) { null }

    /** Never throws; a failed write leaves the previous file in place. True when written. */
    fun <T> save(file: CacheFile, s: Stamped<T>, ser: KSerializer<T>): Boolean = try {
        val text = encode(s, ser)
        if (text.toByteArray(Charsets.UTF_8).size > MAX_BYTES) false else { file.writeAtomic(text); true }
    } catch (_: Throwable) {
        false
    }
}
