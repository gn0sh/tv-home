package tvhome.core.feeds

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import tvhome.core.ui.TravelRule

/**
 * The panels that have an off switch (ADR 0008 section 1). [key] is the id used over adb (`--bind id:s:alerts`) and in
 * the stored preference name [prefKey] (`panel.alerts`). Matching is exact: no trimming, no case folding.
 */
enum class PanelId(val key: String) {
    WEATHER("weather"),
    ALERTS("alerts"),
    GAMES("games"),
    BRIEF("brief");

    val prefKey: String get() = PREFIX + key

    companion object {
        const val PREFIX = "panel."

        fun of(id: String?): PanelId? = entries.firstOrNull { it.key == id }
    }
}

/** The stored on/off value per panel, or null when nothing is stored (the [PanelSwitches] default applies). */
fun panelSwitches(stored: (PanelId) -> Boolean?): PanelSwitches {
    val d = PanelSwitches()
    fun on(id: PanelId, def: Boolean) = runCatching { stored(id) }.getOrNull() ?: def
    return PanelSwitches(
        weather = on(PanelId.WEATHER, d.weather),
        alerts = on(PanelId.ALERTS, d.alerts),
        games = on(PanelId.GAMES, d.games),
        brief = on(PanelId.BRIEF, d.brief),
    )
}

/**
 * Validation of the non-secret settings put on the TV over adb (`content insert --bind ...`; M7-F2). Each function takes
 * the bound values exactly as `ContentValues` holds them and returns the value to store, or null to refuse. Strict:
 * the exact set of keys, the exact types (`b:` is a Boolean, `i:` an Int, `s:` a String; a String "false" or a Long is
 * refused), and the exact words. A refusal stores nothing.
 */
object SettingsValidator {
    const val TV_PROFILE_LG = "lg"
    const val TV_PROFILE_VIZIO = "vizio"

    /** `--bind id:s:<panel> --bind on:b:<true|false>`. */
    fun panel(values: Map<String, Any?>): Pair<PanelId, Boolean>? {
        if (values.keys != setOf("id", "on")) return null
        val id = PanelId.of(values["id"] as? String) ?: return null
        val on = values["on"] as? Boolean ?: return null
        return id to on
    }

    /** `--bind value:s:lg` or `vizio`. */
    fun tvProfile(values: Map<String, Any?>): String? {
        if (values.keys != setOf("value")) return null
        return (values["value"] as? String)?.takeIf { it == TV_PROFILE_LG || it == TV_PROFILE_VIZIO }
    }

    /** Seconds for the emulator QA idle override (`--bind seconds:i:20`): DIM after it, NEAR_BLACK after twice it. */
    val IDLE_TEST_SECONDS = 5..600

    fun idleTest(values: Map<String, Any?>): Int? {
        if (values.keys != setOf("seconds")) return null
        return (values["seconds"] as? Int)?.takeIf { it in IDLE_TEST_SECONDS }
    }

    /** How long an /idle_test override lasts after it was set, so one left behind on a TV cannot stick. */
    val IDLE_TEST_TTL: java.time.Duration = java.time.Duration.ofMinutes(60)

    /**
     * The stored override if it still applies: [seconds] in range, set at [setAtMs] (wall clock), less than
     * [IDLE_TEST_TTL] ago, and not in the future beyond a minute of clock skew. Null otherwise (the caller clears it).
     */
    fun idleTestActive(seconds: Int?, setAtMs: Long?, nowMs: Long): Int? {
        if (seconds == null || seconds !in IDLE_TEST_SECONDS || setAtMs == null) return null
        val age = nowMs - setAtMs
        if (age < -60_000L || age >= IDLE_TEST_TTL.toMillis()) return null
        return seconds
    }

    /**
     * The watch map over adb (M7-F5): `--bind networks:s:ESPN+=com.espn.gtv;Peacock=com.peacocktv.peacockandroid`,
     * optionally `--bind defaults:s:predators=ESPN+`. Stricter than [WatchMap.parse], which drops bad entries: here any
     * bad entry refuses the whole insert, so nothing half-applied is stored. Returns the two texts to store (defaults ""
     * when absent), each re-parsed by [WatchMap.parse] on read.
     */
    fun watchMap(values: Map<String, Any?>): Pair<String, String>? {
        if (values.keys != setOf("networks") && values.keys != setOf("networks", "defaults")) return null
        val networks = values["networks"] as? String ?: return null
        val defaults = if ("defaults" in values) values["defaults"] as? String ?: return null else ""
        if (networks.length > MAX_WATCH_TEXT || defaults.length > MAX_WATCH_TEXT) return null
        val nets = watchPairs(networks) ?: return null
        if (nets.isEmpty() || nets.size > WatchMap.MAX_NETWORKS) return null
        if (nets.any { (name, pkg) -> !watchName(name) || !WatchMap.validPackage(pkg) }) return null
        if (nets.map { it.first.lowercase(java.util.Locale.ROOT) }.toSet().size != nets.size) return null
        val defs = if (defaults.isEmpty()) emptyList() else watchPairs(defaults) ?: return null
        if (defs.size > WatchMap.MAX_TEAMS) return null
        if (defs.any { (team, net) -> !TEAM_KEY.matches(team) || !watchName(net) }) return null
        if (defs.map { it.first }.toSet().size != defs.size) return null
        return networks to defaults
    }

    private const val MAX_WATCH_TEXT = 4096
    private val TEAM_KEY = Regex("""[a-z0-9-]{1,32}""")

    private fun watchName(n: String) = n.isNotEmpty() && n == n.trim() && n.length <= Feeds.LABEL_CAP && n.none { it.isISOControl() }

    /** `a=b;c=d` exactly: every part has a non-empty key and value, no empty parts. Null when malformed. */
    private fun watchPairs(text: String): List<Pair<String, String>>? =
        text.split(';').map { part ->
            val i = part.indexOf('=')
            if (i <= 0 || i == part.length - 1) return null
            part.substring(0, i) to part.substring(i + 1)
        }

    /** The packages a stored networks text names (valid ones only), for the installed check off the main thread. */
    fun watchPackages(networks: String?): Set<String> =
        networks.orEmpty().split(';').mapNotNull { p -> p.substringAfter('=', "").trim().takeIf { WatchMap.validPackage(it) } }.toSet()

    /** `--bind on:b:true`: Quiet mode on or off (M7-F6). */
    fun quiet(values: Map<String, Any?>): Boolean? {
        if (values.keys != setOf("on")) return null
        return values["on"] as? Boolean
    }

    /** `--bind keyword:s:<text> --bind minutes:i:<1..180>`; the keyword rules are [TravelRule.of]'s. */
    fun travelRule(values: Map<String, Any?>): TravelRule? {
        if (values.keys != setOf("keyword", "minutes")) return null
        val keyword = values["keyword"] as? String ?: return null
        val minutes = values["minutes"] as? Int ?: return null
        return TravelRule.of(keyword, minutes)
    }
}

/**
 * The stored travel rules: a JSON array in one preference. [decode] re-checks every rule (the file is never trusted) and
 * any bad entry or more than [TravelRule.MAX_RULES] makes the whole list empty: no "Leave by" rather than a wrong one.
 */
object TravelRules {
    private const val MAX_TEXT = 4096
    private val json = Json { ignoreUnknownKeys = false }

    @Serializable private class Dto(val keyword: String, val minutes: Int)

    private val ser = ListSerializer(Dto.serializer())

    fun encode(rules: List<TravelRule>): String = json.encodeToString(ser, rules.map { Dto(it.keyword, it.minutes) })

    fun decode(text: String?): List<TravelRule> {
        if (text == null || text.length > MAX_TEXT) return emptyList()
        val dtos = runCatching { json.decodeFromString(ser, text) }.getOrNull() ?: return emptyList()
        if (dtos.size > TravelRule.MAX_RULES) return emptyList()
        val rules = dtos.map { TravelRule.of(it.keyword, it.minutes) ?: return emptyList() }
        return rules
    }

    /**
     * [existing] with [rule] added; a rule with the same keyword (ignoring case) is replaced in place. Null when the
     * list is full ([TravelRule.MAX_RULES]) and [rule] is new: the caller stores nothing.
     */
    fun add(existing: List<TravelRule>, rule: TravelRule): List<TravelRule>? {
        val i = existing.indexOfFirst { it.keyword.equals(rule.keyword, ignoreCase = true) }
        if (i >= 0) return existing.toMutableList().also { it[i] = rule }
        if (existing.size >= TravelRule.MAX_RULES) return null
        return existing + rule
    }
}
