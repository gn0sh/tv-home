package tvhome.core.apps

/**
 * continue-tile: the home row's "Continue" tile. Android keeps other apps' Continue watching rows for the system
 * launcher only (ACCESS_ALL_EPG_DATA, signature|privileged; probe-watch-next), so the tile names the streaming app Brian
 * used last (UsageStats, if allowed) and opens that app's TV home. No usage access: no tile.
 */
data class ContinueTile(val packageName: String)

/** One app's last use, from UsageStats. */
data class RecentUse(val packageName: String, val lastUsedMillis: Long)

object ContinueChoice {
    /** System apps that have a TV launcher entry but are not streaming apps. */
    val NOT_STREAMING = setOf(
        "com.android.tv.settings",
        "com.google.android.tv.settings",
        "com.android.vending",
        "com.google.android.apps.tv.launcherx",
        "com.google.android.tvlauncher",
        "com.google.android.katniss",
    )

    /**
     * The tile: the most recently visible app that is in the launcher list, not hidden by Brian ([hidden],
     * continue-hidden-app), not [NOT_STREAMING] and not ours; else no tile. [recent] is null when UsageStats is not allowed.
     */
    fun choose(
        recent: List<RecentUse>?,
        launcherPkgs: Set<String>,
        ownPackage: String,
        hidden: Set<String> = emptySet(),
    ): ContinueTile? = recent.orEmpty()
        .filter {
            it.lastUsedMillis > 0 && it.packageName in launcherPkgs && it.packageName !in hidden &&
                it.packageName != ownPackage && it.packageName !in NOT_STREAMING
        }
        .maxByOrNull { it.lastUsedMillis }
        ?.let { ContinueTile(it.packageName) }

    /** The tile's spoken description, e.g. "Continue in Netflix". */
    fun description(tile: ContinueTile, appLabel: (String) -> String?): String =
        "Continue in ${appLabel(tile.packageName) ?: "the app you used last"}"
}
