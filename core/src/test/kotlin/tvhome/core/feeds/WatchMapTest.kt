package tvhome.core.feeds

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Instant

/** M7-B9: network to package, team default network, never a wrong or uninstalled Watch. */
class WatchMapTest {
    private val t = Instant.parse("2026-11-04T01:00:00Z")
    private fun game(team: TeamConfig, vararg networks: String) =
        TeamGame(team, GameInfo(t, "Blues", true, GamePhase.UPCOMING, null, null, null, networks.toList()))

    private val map = WatchMap.parse(
        "ESPN+=com.espn.gtv;Peacock=com.peacocktv.peacockandroid; BTN = com.btn.app ;bad=not a package;=com.x.y",
        "predators=ESPN+;ohio-state=BTN;Bad Key=ESPN+",
    )
    private val all: (String) -> Boolean = { true }

    @Test fun feedNetworkNamesAreUsedWhenPresent() {
        val w = map.watch(game(Teams.PREDATORS, "SCRIPPS", "espn+"), all)
        assertEquals(WatchTarget("ESPN+", "com.espn.gtv"), w, "case-insensitive; the unmapped SCRIPPS is skipped")
        assertEquals("ESPN+", map.network(game(Teams.PREDATORS, "SCRIPPS", "ESPN+")))
    }

    @Test fun theTeamDefaultNetworkIsUsedOnlyWhenTheFeedListsNone() {
        assertEquals("com.espn.gtv", map.watch(game(Teams.PREDATORS), all)!!.packageName)
        assertEquals("com.btn.app", map.watch(game(Teams.OHIO_STATE), all)!!.packageName)
    }

    @Test fun aListedButUnmappedNetworkNeverFallsBackToTheDefault() {
        // The feed says Scripps (local TV); the default ESPN+ would be the wrong app.
        assertNull(map.watch(game(Teams.PREDATORS, "SCRIPPS"), all))
        assertNull(map.network(game(Teams.PREDATORS, "SCRIPPS")))
    }

    @Test fun noMappingMeansNoWatch() {
        assertNull(WatchMap.EMPTY.watch(game(Teams.PREDATORS, "ESPN+"), all))
        assertNull(WatchMap.EMPTY.watch(game(Teams.PREDATORS), all))
        assertNull(WatchMap.parse(null, null).watch(game(Teams.PREDATORS), all))
        val noDefault = WatchMap.parse("ESPN+=com.espn.gtv", null)
        assertNull(noDefault.watch(game(Teams.PREDATORS), all))
    }

    @Test fun anUninstalledAppMeansNoWatch() {
        assertNull(map.watch(game(Teams.PREDATORS, "ESPN+"), { false }))
        assertNull(map.watch(game(Teams.PREDATORS, "ESPN+"), { throw IllegalStateException() }), "a failing check is not installed")
        // The network name is still known, so the card can say where it is on.
        assertEquals("ESPN+", map.network(game(Teams.PREDATORS, "ESPN+")))
        // First mapped network not installed, second is: the second.
        val w = map.watch(game(Teams.PREDATORS, "ESPN+", "Peacock"), { it == "com.peacocktv.peacockandroid" })
        assertEquals("Peacock", w!!.network)
    }

    @Test fun badEntriesAreDroppedAndTheRestKept() {
        assertNull(map.watch(game(Teams.PREDATORS, "bad"), all), "not a package name")
        assertEquals("com.btn.app", map.watch(game(Teams.OHIO_STATE, "BTN"), all)!!.packageName, "spaces trimmed")
        assertFalse(WatchMap.validPackage("com.x; rm -rf"))
        assertFalse(WatchMap.validPackage("single"))
        assertFalse(WatchMap.validPackage("a.b" + "c".repeat(100)))
        assertTrue(WatchMap.validPackage("com.espn.gtv"))
    }

    @Test fun limitsAreEnforced() {
        val many = (1..100).associate { "N$it" to "com.app.n$it" }
        val m = WatchMap.of(many)
        assertNull(m.watch(game(Teams.PREDATORS, "N33"), all), "only the first 32 networks are kept")
        assertNotNull(m.watch(game(Teams.PREDATORS, "N32"), all))
        assertNull(WatchMap.of(mapOf("x".repeat(41) to "com.a.b")).watch(game(Teams.PREDATORS, "x".repeat(41)), all))
        assertEquals(WatchMap.EMPTY.toString().length > 0, true)
        assertFalse(map.toString().contains("com.espn"), "no package names in logs")
    }
}
