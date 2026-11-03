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
