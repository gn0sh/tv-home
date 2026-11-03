package tvhome.core.feeds

import java.util.Locale

/**
 * The app a Watch pill opens: [network] is the name the game card shows ("ESPN+"), [packageName] the app to launch,
 * [appLabel] its launcher label when known ("CDE", "ESPN"): the pill says "Watch on <app>" ([tvhome.core.ui.WatchPill]).
 */
data class WatchTarget(val network: String, val packageName: String, val appLabel: String? = null)

/**
 * Which app carries a game (spec M7-4.2/4.3, A-M7-8): the feeds do not say, so this is a small map Brian sets over adb.
 * Two parts: network name to Android package, and a default network per team (by [TeamConfig.key]) for games whose feed
 * lists no network. Never scraped, never guessed: a game with no mapping has no Watch, and an app that is not installed
 * has no Watch.
 *
 * Resolution ([watch]): when the feed lists networks, only those decide (the first mapped one whose app is installed;
 * a listed network that is not mapped is never replaced by the team default, which could be the wrong app). When the
 * feed lists none, the team's default network is used.
 *
 * Build with [of] or [parse], which apply the limits. Network names match case-insensitively.
 */
class WatchMap private constructor(
    private val packages: Map<String, WatchTarget>,
    private val teamDefaults: Map<String, String>,
    /** For a network this map does not name: the built-in defaults ([WatchDefaults]), or null. */
    private val fallback: ((String) -> WatchTarget?)? = null,
) {
    /** The network that carries [game] and has a mapped app, installed or not ("ESPN+"); null when none maps. */
    fun network(game: TeamGame): String? = candidates(game).firstOrNull()?.network

    /** The target to open, or null: no mapping, or none of the mapped apps is installed. */
    fun watch(game: TeamGame, installed: (String) -> Boolean): WatchTarget? =
        candidates(game).firstOrNull { runCatching { installed(it.packageName) }.getOrDefault(false) }

    private fun candidates(game: TeamGame): List<WatchTarget> {
        val listed = game.game.networks
        val names = if (listed.isNotEmpty()) listed else listOfNotNull(teamDefaults[game.team.key])
        return resolveAll(names).distinctBy { it.packageName }
    }

    /** watch-adb-precedence: every one of [names] against this map's own entries first, then the fallback for each. */
    private fun resolveAll(names: List<String>): List<WatchTarget> {
        val own = names.mapNotNull { packages[key(it)] }
        val fb = fallback?.let { f -> names.mapNotNull { n -> runCatching { f(n) }.getOrNull() } }.orEmpty()
        return own + fb
    }

    /**
     * This map wins for the whole game (an adb-provisioned map is Brian's word): every network the game lists is tried
     * against its entries first, in feed order, and only when none of them has an installed app do [defaults] get a
     * turn, again in feed order. Its team defaults win too.
     */
    fun orElse(defaults: WatchMap): WatchMap = WatchMap(packages, teamDefaults + defaults.teamDefaults.filterKeys { it !in teamDefaults }) { n ->
        defaults.resolveAll(listOf(n)).firstOrNull()
    }

    /** True when nothing is mapped and there is no fallback (no Watch pill can show). */
    val isEmpty: Boolean get() = packages.isEmpty() && fallback == null

    override fun toString(): String = "WatchMap(${packages.size} networks, ${teamDefaults.size} defaults)"

    companion object {
        val EMPTY = WatchMap(emptyMap(), emptyMap())

        /** A map that names nothing itself and asks [resolve] for every network (the built-in defaults). */
        internal fun resolving(resolve: (String) -> WatchTarget?): WatchMap = WatchMap(emptyMap(), emptyMap(), resolve)
        const val MAX_NETWORKS = 32
        const val MAX_TEAMS = 8
        const val MAX_PACKAGE = 100
        private val PACKAGE = Regex("""[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+""")
        private val TEAM_KEY = Regex("""[a-z0-9-]{1,32}""")

        private fun key(network: String) = network.trim().lowercase(Locale.ROOT)

        /** True for a plausible Android package name (the launch intent is looked up by it; nothing else is accepted). */
        fun validPackage(p: String?): Boolean = p != null && p.length <= MAX_PACKAGE && PACKAGE.matches(p)

        /**
         * [networks]: network name to package. [defaults]: team key to network name. Entries that break a limit (name
         * over [Feeds.LABEL_CAP], control characters, bad package name, bad team key) are dropped, the rest kept; at
         * most [MAX_NETWORKS] and [MAX_TEAMS] entries.
         */
        fun of(networks: Map<String, String>, defaults: Map<String, String> = emptyMap()): WatchMap {
            val pk = LinkedHashMap<String, WatchTarget>()
            for ((n, p) in networks) {
                if (pk.size >= MAX_NETWORKS) break
                val name = n.trim()
                if (name.isEmpty() || name.length > Feeds.LABEL_CAP || name.any { it.isISOControl() }) continue
                if (!validPackage(p.trim())) continue
                pk.putIfAbsent(key(name), WatchTarget(name, p.trim()))
            }
            val td = LinkedHashMap<String, String>()
            for ((t, n) in defaults) {
                if (td.size >= MAX_TEAMS) break
                val team = t.trim()
                val net = n.trim()
                if (!TEAM_KEY.matches(team) || net.isEmpty() || net.length > Feeds.LABEL_CAP || net.any { it.isISOControl() }) continue
                td[team] = net
            }
            return WatchMap(pk, td)
        }

        /**
         * The adb text forms: [networks] is `ESPN+=com.espn.gtv;Peacock=com.peacocktv.peacockandroid`, [defaults] is
         * `predators=ESPN+;ohio-state=ESPN+`. Null, blank or malformed parts are skipped, never an error.
         */
        fun parse(networks: String?, defaults: String? = null): WatchMap = of(pairs(networks), pairs(defaults))

        private fun pairs(text: String?): Map<String, String> {
            if (text == null || text.length > MAX_SPEC) return emptyMap()
            val out = LinkedHashMap<String, String>()
            for (part in text.split(';')) {
                val i = part.indexOf('=')
                if (i <= 0) continue
                out.putIfAbsent(part.substring(0, i), part.substring(i + 1))
            }
            return out
        }

        private const val MAX_SPEC = 4096
    }
}
