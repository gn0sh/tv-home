package tvhome.core.apps

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AppOrderTest {
    private val own = "com.gnosh.tvhome"
    private fun app(pkg: String, label: String) = LauncherApp(pkg, "$pkg.Main", label)
    private fun labels(apps: List<LauncherApp>) = apps.map { it.label }

    private val netflix = app("com.netflix.ninja", "Netflix")
    private val youtube = app("com.google.android.youtube.tv", "YouTube")
    private val disney = app("com.disney.disneyplus", "Disney+")
    private val prime = app("com.amazon.amazonvideo.livingroom", "Prime Video")
    private val max = app("com.wbd.stream", "Max")
    private val hulu = app("com.hulu.livingroomplus", "Hulu")
    private val appleTv = app("com.apple.atve.androidtv.appletv", "Apple TV")
    private val spotify = app("com.spotify.tv.android", "Spotify")

    @Test fun allFavoritesInConfiguredOrderThenRestAlphabetical() {
        val input = listOf(app("z.settings", "Settings"), spotify, appleTv, hulu, app("a.plex", "Plex"), max, prime, disney, youtube, netflix)
        assertEquals(
            listOf("Netflix", "YouTube", "Disney+", "Prime Video", "Max", "Hulu", "Apple TV", "Spotify", "Plex", "Settings"),
            labels(AppOrder.order(input, own)),
        )
    }

    @Test fun missingFavoritesAreSkipped() {
        val input = listOf(app("x.tubi", "Tubi"), spotify, youtube)
        assertEquals(listOf("YouTube", "Spotify", "Tubi"), labels(AppOrder.order(input, own)))
    }

    @Test fun noFavoritesInstalledIsPlainAlphabetical() {
        val input = listOf(app("b", "beta"), app("a", "Alpha"), app("c", "Gamma"))
        assertEquals(listOf("Alpha", "beta", "Gamma"), labels(AppOrder.order(input, own)))
    }

    @Test fun ownPackageIsExcluded() {
        val input = listOf(app(own, "Agenda"), netflix)
        assertEquals(listOf("Netflix"), labels(AppOrder.order(input, own)))
    }

    @Test fun duplicatePackagesGiveOneTileFirstWins() {
        val input = listOf(netflix, LauncherApp("com.netflix.ninja", "other.Activity", "Netflix"), app("p", "Plex"), app("p", "Plex 2"))
        val out = AppOrder.order(input, own)
        assertEquals(listOf("Netflix", "Plex"), labels(out))
        assertEquals("com.netflix.ninja.Main", out[0].activityName)
    }

    @Test fun duplicateFavoriteEntriesDoNotRepeatTiles() {
        val favs = listOf(Favorite("Netflix", listOf("com.netflix.ninja")), Favorite("netflix", emptyList()), Favorite("YouTube", listOf("com.google.android.youtube.tv")))
        assertEquals(listOf("Netflix", "YouTube"), labels(AppOrder.order(listOf(youtube, netflix), own, favs)))
    }

    @Test fun labelMatchIgnoresCaseAndSpaces() {
        val sideloaded = app("com.example.netflixbuild", "  NETFLIX ")
        assertEquals(listOf("  NETFLIX ", "Alpha"), labels(AppOrder.order(listOf(app("a", "Alpha"), sideloaded), own)))
    }

    @Test fun packageMatchWinsOverLabelAndBothAreKeptInFavoriteSlot() {
        val lookalike = app("com.example.max", "max")
        val out = AppOrder.order(listOf(lookalike, app("a", "Alpha"), max, netflix), own)
        assertEquals(listOf("com.netflix.ninja", "com.wbd.stream", "com.example.max", "a"), out.map { it.packageName })
    }

    @Test fun alternativePackageForAFavoriteMatches() {
        val oldMax = app("com.hbo.hbonow", "HBO Max")
        assertEquals(listOf("YouTube", "HBO Max", "Alpha"), labels(AppOrder.order(listOf(app("a", "Alpha"), oldMax, youtube), own)))
    }

    @Test fun restSortIgnoresCaseAndBreaksTiesByPackage() {
        val input = listOf(app("pkg.b", "plex"), app("pkg.a", "Plex"), app("pkg.c", "apple"))
        assertEquals(listOf("pkg.c", "pkg.a", "pkg.b"), AppOrder.order(input, own).map { it.packageName })
    }

    @Test fun emptyInputGivesEmptyRow() {
        assertEquals(emptyList<LauncherApp>(), AppOrder.order(emptyList(), own))
    }
}
