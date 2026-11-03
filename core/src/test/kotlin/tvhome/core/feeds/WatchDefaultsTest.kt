package tvhome.core.feeds

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import tvhome.core.apps.LauncherApp
import java.time.Instant

/** watch-defaults: the built-in Watch map (CDE for broadcast, local and cable; streaming apps only when installed). */
class WatchDefaultsTest {
    private val t = Instant.parse("2026-11-04T01:00:00Z")
    private fun game(team: TeamConfig, vararg networks: String) =
        TeamGame(team, GameInfo(t, "Blues", true, GamePhase.UPCOMING, null, null, null, networks.toList()))
    private fun app(pkg: String, label: String) = LauncherApp(pkg, "$pkg.Main", label)

    // CDE's package is unknown: any package will do, it is found by its label.
    private val cde = app("net.example.cdetv", "CDE Lightband TV")
    private val espn = app("com.espn.gtv", "ESPN")
    private val youtube = app("com.google.android.youtube.tv", "YouTube")
    private val all: (String) -> Boolean = { true }

    private fun watch(apps: List<LauncherApp>, vararg networks: String, map: WatchMap = WatchMap.EMPTY) =
        map.orElse(WatchDefaults.map(apps)).watch(game(Teams.PREDATORS, *networks), all)

    @Test fun scrippsGoesToTheCdeAppByLabel() {
        val w = watch(listOf(youtube, cde), "SCRIPPS")!!
        assertEquals("net.example.cdetv", w.packageName); assertEquals("SCRIPPS", w.network)
        assertEquals("x.y.z", watch(listOf(app("x.y.z", "Lightband")), "Scripps Sports")!!.packageName)
        assertEquals("net.example.cdetv", watch(listOf(app("net.example.cdetv", "cde")), "The Spot")!!.packageName)
    }

    @Test fun espnPlusNeedsTheEspnApp() {
        assertEquals("com.espn.gtv", watch(listOf(cde, espn), "ESPN+")!!.packageName)
        assertNull(watch(listOf(cde, youtube), "ESPN+"), "ESPN+ is not on cable: no CDE fallback")
        // Found by label too when the package differs.
        assertEquals("a.b.espn", watch(listOf(app("a.b.espn", "ESPN")), "espn+")!!.packageName)
    }

    @Test fun broadcastAndLocalFormsGoToCde() {
        for (n in listOf("FOX", "ABC", "CBS", "NBC", "The CW", "ION", "PBS", "MyNetworkTV", "NBC 4", "FOX 17", "WTVF", "WSMV", "WBNS-TV", "WCMH 4")) {
            assertEquals(WatchDefaults.Kind.BROADCAST, WatchDefaults.kindOf(n), n)
            assertEquals("net.example.cdetv", watch(listOf(cde, espn), n)?.packageName, n)
        }
    }

    @Test fun cableUsesItsOwnAppElseCde() {
        assertEquals("com.espn.gtv", watch(listOf(cde, espn), "ESPN")!!.packageName)
        assertEquals("net.example.cdetv", watch(listOf(cde), "ESPN")!!.packageName)
        assertEquals("com.wbd.stream", watch(listOf(cde, app("com.wbd.stream", "Max")), "TNT")!!.packageName)
        for (n in listOf("TBS", "truTV", "FS1", "FS2", "NHL Network", "NHLN", "Big Ten Network", "BTN", "CBSSN", "FanDuel Sports Network South", "FDSN Ohio")) {
            assertEquals(WatchDefaults.Kind.CABLE, WatchDefaults.kindOf(n), n)
            assertEquals("net.example.cdetv", watch(listOf(cde), n)?.packageName, n)
        }
    }

    @Test fun streamingOnlyWhenInstalled() {
        val apps = listOf(cde, app("com.peacocktv.peacockandroid", "Peacock"), app("com.hulu.livingroomplus", "Hulu"))
        assertEquals("com.peacocktv.peacockandroid", watch(apps, "Peacock")!!.packageName)
        assertEquals("com.hulu.livingroomplus", watch(apps, "HULU")!!.packageName)
        for (n in listOf("Prime Video", "Max", "Paramount+", "Apple TV+", "Disney+")) assertNull(watch(apps, n), n)
    }

    @Test fun nothingMatchesNoPill() {
        assertNull(watch(listOf(youtube), "SCRIPPS"), "no CDE app installed")
        assertNull(watch(listOf(cde, espn), "SN+", "TVAS"), "unknown (Canadian) networks map to nothing")
        assertNull(watch(listOf(cde, espn)), "no network listed and no team default")
        assertNull(watch(emptyList(), "FOX"))
        assertNull(WatchDefaults.kindOf("Netflix"))
    }

    @Test fun firstListedNetworkWithAnAppWins() {
        // NHL lists ESPN+ first and HULU second; only the CDE app and Hulu are installed.
        val w = watch(listOf(cde, app("com.hulu.livingroomplus", "Hulu")), "ESPN+", "HULU", "SCRIPPS")!!
        assertEquals("HULU", w.network)
    }

    @Test fun adbMapOverridesTheDefaults() {
        val adb = WatchMap.parse("SCRIPPS=com.google.android.youtube.tv", "predators=SCRIPPS")
        assertEquals("com.google.android.youtube.tv", watch(listOf(cde, youtube), "SCRIPPS", map = adb)!!.packageName)
        // Networks the adb map does not name still get the defaults; its team default still applies.
        assertEquals("net.example.cdetv", watch(listOf(cde, youtube), "FOX", map = adb)!!.packageName)
        assertEquals("com.google.android.youtube.tv", watch(listOf(cde, youtube), map = adb)!!.packageName)
    }

    @Test fun resultDoesNotDependOnLauncherOrder() {
        val a = app("a.cde", "CDE"); val b = app("b.cde", "CDE Lightband")
        assertEquals(watch(listOf(a, b), "FOX"), watch(listOf(b, a), "FOX"))
    }
}
