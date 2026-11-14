package tvhome.core.feeds

import tvhome.core.apps.LauncherApp
import java.util.Locale

/**
 * The built-in Watch map (watch-defaults): which installed app carries a game's network when Brian has provisioned
 * nothing over adb (an adb map still wins, network by network: [WatchMap.orElse]). Brian, 2026-11-03: "we use the CDE
 * app for network and local channels". CDE is CDE Lightband's live TV app; its Google TV package is not known, so it is
 * found by its label, never by a guessed package.
 *
 * Order, per network the feed lists:
 * 0. Scripps (Scripps, Scripps Sports, The Spot, WNPX): the Nashville Predators app when installed, else as 1.
 * 1. Broadcast and local ([Kind.BROADCAST]: ABC, CBS, NBC, FOX, The CW, ION, MyNetworkTV, PBS, Scripps / Scripps Sports,
 *    The Spot, "<network> <channel>" forms like "NBC 4", the Nashville and Columbus call signs like "WTVF", and other
 *    US call signs with a suffix or channel like "WXYZ-TV"): the CDE app, when exactly one CDE app is installed.
 * 2. National cable ([Kind.CABLE]: ESPN, ESPN2, ESPNU, ESPNews, SEC Network, ACC Network, TNT, TBS, truTV, FS1, FS2, NHL
 *    Network, Big Ten Network, CBS Sports Network, FanDuel Sports Network): its own app when installed (ESPN; Max for
 *    TNT, TBS, truTV; FOX Sports; FanDuel Sports Network), else the CDE app (cable channels are on CDE live TV).
 * 3. Streaming only ([Kind.STREAMING]: ESPN+, Peacock, Prime Video, Max / HBO Max, Hulu, Paramount+, Apple TV, Disney+):
 *    its own app when installed, else nothing (they are not on cable).
 * 4. Anything else: nothing (no pill rather than a wrong app).
 *
 * An app is found by its package (only packages the repo's architecture doc lists, docs/architecture/tv-home.md) or by
 * its launcher label. Apps are searched in package order, so the answer does not depend on the launcher's order.
 */
object WatchDefaults {
    enum class Kind { BROADCAST, CABLE, STREAMING }

    /**
     * One app: known packages, then labels that equal it (normalised), then labels that contain one of [labelWords].
     * [unique]: a label match counts only when exactly one launchable app matches; two or more is ambiguous, so none.
     */
    private class AppRule(
        val packages: Set<String> = emptySet(),
        val labels: Set<String> = emptySet(),
        val labelWords: List<String> = emptyList(),
        val unique: Boolean = false,
    ) {
        fun find(apps: List<LauncherApp>): LauncherApp? {
            apps.firstOrNull { it.packageName in packages }?.let { return it }
            val byLabel = apps.filter { a ->
                norm(a.label) in labels || labelWords.any { w -> " ${norm(a.label)} ".contains(" $w ") }
            }.distinctBy { it.packageName }
            return if (unique && byLabel.size > 1) null else byLabel.firstOrNull { norm(it.label) in labels } ?: byLabel.firstOrNull()
        }
    }

    /**
     * watch-cde-ambiguous: by label only while its package is unknown, and only when exactly one app matches (two
     * "CDE" apps: no pill rather than the wrong one). Pin the real package in [CDE_PACKAGES] once it is confirmed on the
     * TV (`adb shell pm list packages`); never a guessed one.
     */
    private val CDE_PACKAGES: Set<String> = emptySet()
    private val CDE = AppRule(CDE_PACKAGES, labelWords = listOf("cde", "lightband"), unique = true)
    private val ESPN = AppRule(setOf("com.espn.gtv"), setOf("espn"))
    private val MAX = AppRule(setOf("com.wbd.stream", "com.hbo.hbonow"), setOf("max", "hbo max"))
    private val PEACOCK = AppRule(setOf("com.peacocktv.peacockandroid"), setOf("peacock", "peacock tv"))
    private val PRIME = AppRule(setOf("com.amazon.amazonvideo.livingroom"), setOf("prime video", "amazon prime video"))
    private val HULU = AppRule(setOf("com.hulu.livingroomplus"), setOf("hulu"))
    private val PARAMOUNT = AppRule(setOf("com.cbs.ott"), setOf("paramount+", "paramount plus"))
    private val APPLE = AppRule(setOf("com.apple.atve.androidtv.appletv"), setOf("apple tv", "apple tv+"))
    private val DISNEY = AppRule(setOf("com.disney.disneyplus"), setOf("disney+", "disney plus"))
    private val FOX_SPORTS = AppRule(labels = setOf("fox sports", "fox one"))
    private val FANDUEL = AppRule(labelWords = listOf("fanduel sports"))

    /**
     * Brian 2026-11-13: "for games on scripps, use the nashville predators app. that is where we watch it." Its package is
     * not known here, so it is found by its launcher label (any label with the word "predators"), only when exactly one
     * app matches. Scripps games fall back to the CDE app when the Predators app is not installed.
     */
    private val PREDATORS_APP = AppRule(labelWords = listOf("predators"), unique = true)
    private val SCRIPPS = setOf("scripps", "scripps sports", "the spot", "wnpx")

    private val BROADCAST = setOf(
        "abc", "cbs", "nbc", "fox", "cw", "the cw", "ion", "ion tv", "mynetworktv", "mytv", "pbs",
        "scripps", "scripps sports", "the spot",
    )
    private val CABLE: Map<String, AppRule?> = mapOf(
        "espn" to ESPN, "espn2" to ESPN, "espnu" to ESPN, "espnews" to ESPN, "sec network" to ESPN, "secn" to ESPN,
        "acc network" to ESPN, "accn" to ESPN,
        "tnt" to MAX, "tbs" to MAX, "trutv" to MAX, "tru tv" to MAX,
        "fs1" to FOX_SPORTS, "fs2" to FOX_SPORTS, "fox sports 1" to FOX_SPORTS, "fox sports 2" to FOX_SPORTS,
        "nhl network" to null, "nhln" to null, "big ten network" to null, "btn" to null,
        "cbs sports network" to null, "cbssn" to null,
        "fanduel sports network" to FANDUEL, "fdsn" to FANDUEL,
    )
    private val STREAMING: Map<String, AppRule> = mapOf(
        "espn+" to ESPN, "peacock" to PEACOCK, "prime video" to PRIME, "amazon prime video" to PRIME, "prime" to PRIME,
        "max" to MAX, "hbo max" to MAX, "hulu" to HULU, "paramount+" to PARAMOUNT, "apple tv" to APPLE, "apple tv+" to APPLE,
        "disney+" to DISNEY,
    )

    /**
     * Local stations (watch-callsign-overmatch): the Nashville and Columbus over-the-air call signs by name, and any other
     * US call sign only with a station suffix or a channel number ("WXYZ-TV", "WXYZ 4"), so names like "WWE" or "KAYO"
     * are not taken for a local channel.
     */
    private val LOCAL_STATIONS = setOf(
        // Nashville
        "wkrn", "wsmv", "wtvf", "wztv", "wnpt", "wnab", "wuxp", "wpgd", "wnpx", "wjfb", "wllc",
        // Columbus
        "wbns", "wcmh", "wsyx", "wtte", "wosu", "wwho", "wsfj",
    )
    private val CALL_SIGN = Regex("""[kw][a-z]{2,3}(-(tv|dt|cd|ld)( \d{1,3})?| \d{1,3})""")
    private val STATION_FORM = Regex("""([kw][a-z]{2,3})(-(tv|dt|cd|ld))?( \d{1,3})?""")
    private val CHANNEL_FORM = Regex("""(abc|cbs|nbc|fox|cw|pbs|ion) \d{1,3}""")

    private fun isLocalStation(n: String) = STATION_FORM.matchEntire(n)?.groupValues?.get(1) in LOCAL_STATIONS

    /** "FanDuel Sports Network South", "FanDuel SN Ohio", "FDSN South". */
    private fun isFanDuel(n: String) = n.startsWith("fanduel") || n == "fdsn" || n.startsWith("fdsn ")

    /** What kind of channel [network] is, or null when unknown. */
    fun kindOf(network: String): Kind? {
        val n = norm(network)
        return when {
            n in STREAMING -> Kind.STREAMING
            n in CABLE || isFanDuel(n) -> Kind.CABLE
            n in BROADCAST || CHANNEL_FORM.matches(n) || CALL_SIGN.matches(n) || isLocalStation(n) -> Kind.BROADCAST
            else -> null
        }
    }

    /** The default target for [network] among [apps] (launchable TV apps), or null: see the order above. */
    fun target(network: String, apps: List<LauncherApp>): WatchTarget? {
        val sorted = apps.sortedBy { it.packageName }
        val n = norm(network)
        val app = when (kindOf(network)) {
            Kind.BROADCAST -> if (n in SCRIPPS) PREDATORS_APP.find(sorted) ?: CDE.find(sorted) else CDE.find(sorted)
            Kind.CABLE -> (if (isFanDuel(n)) FANDUEL else CABLE[n])?.find(sorted) ?: CDE.find(sorted)
            Kind.STREAMING -> STREAMING.getValue(n).find(sorted)
            null -> null
        } ?: return null
        if (!WatchMap.validPackage(app.packageName)) return null
        return WatchTarget(network.trim(), app.packageName, app.label.trim().ifEmpty { null })
    }

    /** The built-in map over [apps]; every target it gives is one of them, so it is installed by construction. */
    fun map(apps: List<LauncherApp>): WatchMap {
        val snapshot = apps.toList()
        return WatchMap.resolving { target(it, snapshot) }
    }

    /** Lower case, single spaces, "+" kept ("ESPN+" is not "ESPN"); other punctuation becomes a space except "-". */
    private fun norm(s: String): String =
        s.lowercase(Locale.ROOT).map { if (it.isLetterOrDigit() || it == '+' || it == '-') it else ' ' }.joinToString("")
            .split(' ').filter { it.isNotEmpty() }.joinToString(" ")
}
