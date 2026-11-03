package tvhome.core.ui

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/** Emulator tour 2026-11-03: the hero card ran off the right edge and behind the apps row at 1920x1080, density 2.0. */
class HeroLayoutTest {
    @Test fun canvasScalesBothTvDensitiesToTheSameLayout() {
        assertEquals(1.5f, DesignCanvas.density(1920, 1080)) // whatever the TV's own density (1.5 or 2.0)
        assertEquals(1.0f, DesignCanvas.density(1280, 720))
        assertEquals(3.0f, DesignCanvas.density(3840, 2160))
        // A non-16:9 screen fits the canvas inside (letterbox), never crops it.
        assertEquals(1.0f, DesignCanvas.density(1280, 800))
        assertEquals(1f, DesignCanvas.density(0, 0))
    }

    @Test fun zonesStayInsideTheSafeAreaAndAboveTheApps() {
        for (appsTop in listOf(null, 0, 120, 200, 300, 348, 382, 400, 720)) {
            val l = HeroZones.layout(appsTop)
            for (z in l.all) {
                assertTrue(z.x >= HeroZones.SAFE_X && z.right <= HeroZones.SCREEN_W - HeroZones.SAFE_X, "$appsTop $z")
                assertTrue(z.y >= HeroZones.SAFE_Y && z.bottom <= HeroZones.SCREEN_H - HeroZones.SAFE_Y, "$appsTop $z")
                assertTrue(z.h >= 0)
            }
            for (z in listOf(l.left, l.right)) {
                if (appsTop != null && z.h > 0) assertTrue(z.bottom <= appsTop - HeroZones.APPS_GAP, "$appsTop $z")
            }
            assertFalse(l.left.overlaps(l.right))
            assertFalse(l.top.overlaps(l.left))
        }
    }

    @Test fun roomyScreenKeepsTheDesignZones() {
        assertEquals(HeroZones.HERO_RIGHT, HeroZones.layout(null).right)
        assertEquals(HeroZones.HERO_LEFT.copy(h = 348 - 8 - 112), HeroZones.layout(348).left)
    }
}

class HomeLayoutTest {
    @Test fun zoneMapIsDisjointInsideTheSafeAreaForEveryTileSize() {
        for (tile in listOf(68, 108, 135, 160)) {
            val m = HomeLayout.map(tile)
            for (z in m.all.filter { it.h > 0 }) {
                assertTrue(z.x >= HeroZones.SAFE_X && z.right <= HeroZones.SCREEN_W - HeroZones.SAFE_X, "$tile $z")
                assertTrue(z.y >= HeroZones.SAFE_Y && z.bottom <= HeroZones.SCREEN_H - HeroZones.SAFE_Y, "$tile $z")
            }
            val zs = m.all.filter { it.h > 0 && it.w > 0 }
            for (i in zs.indices) for (j in zs.indices) if (i != j) assertFalse(zs[i].overlaps(zs[j]), "$tile ${zs[i]} ${zs[j]}")
            assertEquals(HomeLayout.BRIEF_ROW_DP, m.brief.h)
            assertEquals(HeroZones.SCREEN_H - HeroZones.SAFE_Y, m.brief.bottom)
        }
    }

    /** Default theme (108 dp tiles): the numbers in the handoff's zone map. */
    @Test fun defaultMap() {
        val m = HomeLayout.map(108)
        assertEquals(HeroZones.Zone(64, 36, 1152, 64), m.topStrip)
        assertEquals(HeroZones.Zone(64, 112, 680, 188), m.heroLeft)
        assertEquals(HeroZones.Zone(768, 112, 448, 188), m.heroRight)
        assertEquals(HeroZones.Zone(64, 308, 1152, 168), m.apps)
        assertEquals(HeroZones.Zone(64, 476, 1152, 168), m.dock)
        assertEquals(HeroZones.Zone(64, 644, 1152, 40), m.brief)
    }
}

class BriefRowTest {
    /** The brief line's 32 dp text box (PR #24) plus the drift's travel above and below fits the reserved row. */
    @Test fun briefRowHoldsTheTextAndTheDrift() {
        assertEquals(32, HomeLayout.BRIEF_ROW_DP - 2 * Drift.MAX_Y_DP.toInt())
    }
}
