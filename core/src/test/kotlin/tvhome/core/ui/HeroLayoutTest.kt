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

    // --- Game-day scoreboard zones (design section 2) ---------------------------------------------------------------

    @Test fun gameDayDesignZonesFitWhenThereIsRoom() {
        for (appsTop in listOf(null, 720, 400, 318)) {
            val g = HeroZones.gameLayout(appsTop)!!
            assertEquals(HeroZones.Zone(540, 106, 676, 204), g.board, "$appsTop")
            assertEquals(HeroZones.Zone(540, 106, 676, 150), g.surface)
            assertEquals(HeroZones.Zone(540, 266, 676, 44), g.stub)
            assertEquals(1f, g.scale)
            assertEquals(HeroZones.TOP_STRIP, g.top)
        }
        assertEquals(HeroZones.GAME_LEFT, HeroZones.gameLayout(null)!!.left)
        assertEquals(HeroZones.Zone(64, 112, 456, 198), HeroZones.gameLayout(318)!!.left, "Now/Next ends above the apps row too")
    }

    @Test fun gameDaySurfaceShrinksDownToItsMinimum() {
        // The default theme's apps row (308): 10 dp short, so the surface is 140 and its type scales with it.
        val d = HeroZones.gameLayout(HomeLayout.map(108).apps.y)!!
        assertEquals(HeroZones.Zone(540, 106, 676, 140), d.surface)
        assertEquals(HeroZones.Zone(540, 256, 676, 44), d.stub)
        assertEquals(140f / 150f, d.scale)
        // The smallest board: a 110 dp surface.
        val min = HeroZones.gameLayout(106 + 110 + 10 + 44 + HeroZones.APPS_GAP)!!
        assertEquals(110, min.surface.h)
        assertEquals(44, min.stub.h)
        assertEquals(min.surface.bottom + HeroZones.BOARD_GAP_DP, min.stub.y)
    }

    @Test fun gameDayFallsBackToTheTextCardWithTooLittleRoom() {
        val tight = 106 + 110 + 10 + 44 + HeroZones.APPS_GAP - 1
        assertNull(HeroZones.gameLayout(tight))
        for (appsTop in listOf(0, 120, 200, 260)) assertNull(HeroZones.gameLayout(appsTop), "$appsTop")
        // The fallback is the unchanged text layout.
        assertEquals(HeroZones.HERO_RIGHT.copy(h = tight - 8 - 112), HeroZones.layout(tight).right)
    }

    @Test fun gameDayZonesDoNotOverlapAndStayInTheSafeArea() {
        for (appsTop in listOf<Int?>(null) + (0..720 step 2)) {
            val g = HeroZones.gameLayout(appsTop) ?: continue
            for (z in g.all + g.surface + g.stub) {
                assertTrue(z.x >= HeroZones.SAFE_X && z.right <= HeroZones.SCREEN_W - HeroZones.SAFE_X, "$appsTop $z")
                assertTrue(z.y >= HeroZones.SAFE_Y && z.bottom <= HeroZones.SCREEN_H - HeroZones.SAFE_Y, "$appsTop $z")
            }
            for (z in listOf(g.left, g.board)) if (appsTop != null && z.h > 0) assertTrue(z.bottom <= appsTop - HeroZones.APPS_GAP, "$appsTop $z")
            assertFalse(g.left.overlaps(g.board), "$appsTop")
            assertFalse(g.top.overlaps(g.left), "$appsTop")
            assertFalse(g.top.overlaps(g.board), "$appsTop")
            assertFalse(g.surface.overlaps(g.stub), "$appsTop")
            assertEquals(g.board.y, g.surface.y); assertEquals(g.board.bottom, g.stub.bottom)
            assertTrue(g.surface.h in HeroZones.BOARD_SURFACE_MIN_DP..HeroZones.BOARD_SURFACE_DP, "$appsTop ${g.surface}")
            // The stub's 1.07 focus scale (from its left edge, 1.5 dp up and down) stays inside the gap above the apps.
            assertTrue(HeroZones.BOARD_STUB_DP * 0.07 / 2 < HeroZones.APPS_GAP)
        }
    }

    /** Every allowed tile size: a board, or the text card; never a board that the map's hero band cannot hold. */
    @Test fun gameDayAtEveryTileSize() {
        for (tile in listOf(68, 108, 135, 160)) {
            val m = HomeLayout.map(tile)
            val g = HeroZones.gameLayout(m.apps.y)
            if (g != null) {
                assertTrue(g.board.bottom <= m.apps.y - HeroZones.APPS_GAP, "$tile")
                assertFalse(g.board.overlaps(m.apps), "$tile"); assertFalse(g.left.overlaps(m.apps), "$tile")
            }
        }
        assertNotNull(HeroZones.gameLayout(HomeLayout.map(108).apps.y), "the default theme shows the board")
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

/** layout-tile-range: every tile size the theme accepts fits the zone map and the dock row. */
class TileRangeTest {
    @Test fun everyAllowedTileSizeFits() {
        for (h in HomeTheme.TILE_HEIGHT_DP) {
            val m = HomeLayout.map(h)
            assertTrue(m.heroLeft.h >= HomeLayout.HERO_MIN_DP, "tile height $h: hero ${m.heroLeft.h}")
            assertTrue(m.apps.y >= m.heroLeft.bottom, "$h")
            assertEquals(HeroZones.SCREEN_H - HeroZones.SAFE_Y, m.brief.bottom)
        }
        for (w in HomeTheme.TILE_WIDTH_DP) {
            assertTrue(HomeLayout.dockWidth(w) <= HeroZones.SCREEN_W - 2 * HeroZones.SAFE_X, "tile width $w: dock ${HomeLayout.dockWidth(w)}")
        }
        // Just past the ranges, they would not fit: the ranges are not needlessly narrow.
        assertTrue(HomeLayout.dockWidth(HomeTheme.TILE_WIDTH_DP.last + 16) > HeroZones.SCREEN_W - 2 * HeroZones.SAFE_X)
        assertTrue(HomeLayout.map(HomeTheme.TILE_HEIGHT_DP.last + 12).heroLeft.h < HomeLayout.HERO_MIN_DP)
    }
}

class NowNextFontScaleTest {
    @Test fun line1DropsToOneLineWhenLargeTextNoLongerFits() {
        val h = HomeLayout.map(108).heroLeft.h // 188
        assertEquals(2, HeroZones.nowNextLine1Max(h, 1f))
        assertEquals(1, HeroZones.nowNextLine1Max(h, 1.3f))
        assertEquals(1, HeroZones.nowNextLine1Max(150, 1f))
    }
}
