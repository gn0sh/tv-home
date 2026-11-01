package tvhome.core.apps

/** One launchable TV app: what `queryIntentActivities(MAIN + LEANBACK_LAUNCHER)` returned for it. */
data class LauncherApp(val packageName: String, val activityName: String, val label: String)

/** A favourite, matched by any of its known package names, or by its label ignoring case and surrounding spaces. */
data class Favorite(val label: String, val packages: List<String>)

object AppOrder {
    /** Spec M2 criterion 1: favourites first in this order. Package names are the Android TV builds of each app. */
    val FAVORITES: List<Favorite> = listOf(
        Favorite("Netflix", listOf("com.netflix.ninja")),
        Favorite("YouTube", listOf("com.google.android.youtube.tv")),
        Favorite("Disney+", listOf("com.disney.disneyplus")),
        Favorite("Prime Video", listOf("com.amazon.amazonvideo.livingroom")),
        Favorite("Max", listOf("com.wbd.stream", "com.hbo.hbonow")),
        Favorite("Hulu", listOf("com.hulu.livingroomplus")),
        Favorite("Apple TV", listOf("com.apple.atve.androidtv.appletv")),
        Favorite("Spotify", listOf("com.spotify.tv.android")),
    )

    /**
     * The tile row's order. One tile per package (the first entry seen wins), [ownPackage] left out, favourites in
     * [favorites] order (missing ones are skipped), then everything else by label ignoring case, ties by package.
     */
    fun order(apps: List<LauncherApp>, ownPackage: String, favorites: List<Favorite> = FAVORITES): List<LauncherApp> {
        val unique = LinkedHashMap<String, LauncherApp>()
        for (a in apps) if (a.packageName != ownPackage) unique.putIfAbsent(a.packageName, a)

        val out = ArrayList<LauncherApp>(unique.size)
        val taken = HashSet<String>()
        fun norm(s: String) = s.trim().lowercase()
        for (f in favorites) {
            val byPackage = f.packages.mapNotNull { unique[it] }
            val byLabel = unique.values.filter { norm(it.label) == norm(f.label) }.sortedBy { it.packageName }
            for (a in byPackage + byLabel) if (taken.add(a.packageName)) out += a
        }
        unique.values
            .filter { it.packageName !in taken }
            .sortedWith(compareBy<LauncherApp>({ norm(it.label) }, { it.packageName }))
            .forEach { out += it }
        return out
    }
}
