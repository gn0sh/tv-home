package tvhome.core.apps

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AppLayoutTest {
    private val installed = listOf("a", "b", "c", "d")

    @Test fun emptyStateKeepsDefaultOrder() {
        assertEquals(installed, AppLayout.apply(emptyList(), emptySet(), installed))
    }

    @Test fun savedOrderWins() {
        assertEquals(listOf("c", "a", "b", "d"), AppLayout.apply(listOf("c", "a"), emptySet(), installed))
    }

    @Test fun newAppsGoAtTheEndInInstalledOrder() {
        assertEquals(listOf("d", "a", "b", "c", "e"), AppLayout.apply(listOf("d", "a"), emptySet(), installed + "e"))
    }

    @Test fun uninstalledAreDropped() {
        assertEquals(listOf("c", "a", "b", "d"), AppLayout.apply(listOf("c", "gone", "a"), emptySet(), installed))
    }

    @Test fun hiddenAreLeftOutAndListedSeparately() {
        assertEquals(listOf("a", "c", "d"), AppLayout.apply(emptyList(), setOf("b"), installed))
        assertEquals(listOf("b"), AppLayout.hiddenApps(emptyList(), setOf("b", "gone"), installed))
        assertEquals(setOf("b"), AppLayout.prune(setOf("b", "gone"), installed))
    }

    @Test fun moveRightAndLeftSwapNeighbours() {
        assertEquals(listOf("b", "a", "c", "d"), AppLayout.moveRight(emptyList(), emptySet(), installed, "a"))
        assertEquals(listOf("a", "c", "b", "d"), AppLayout.moveLeft(emptyList(), emptySet(), installed, "c"))
    }

    @Test fun movesAtTheEdgesChangeNothing() {
        assertEquals(installed, AppLayout.moveLeft(emptyList(), emptySet(), installed, "a"))
        assertEquals(installed, AppLayout.moveRight(emptyList(), emptySet(), installed, "d"))
        assertEquals(installed, AppLayout.moveRight(emptyList(), emptySet(), installed, "zzz"))
    }

    @Test fun movesSkipHiddenApps() {
        // b is hidden: moving a right swaps it with c, the next visible app.
        val order = AppLayout.moveRight(emptyList(), setOf("b"), installed, "a")
        assertEquals(listOf("c", "b", "a", "d"), order)
        assertEquals(listOf("c", "a", "d"), AppLayout.apply(order, setOf("b"), installed))
    }

    @Test fun moveToFront() {
        assertEquals(listOf("c", "a", "b", "d"), AppLayout.moveToFront(emptyList(), installed, "c"))
        assertEquals(installed, AppLayout.moveToFront(emptyList(), installed, "a"))
        assertEquals(installed, AppLayout.moveToFront(emptyList(), installed, "zzz"))
    }

    @Test fun hideAndUnhideKeepThePlace() {
        val order = AppLayout.moveToFront(emptyList(), installed, "c")
        val hidden = AppLayout.hide(emptySet(), "c")
        assertEquals(listOf("a", "b", "d"), AppLayout.apply(order, hidden, installed))
        assertEquals(listOf("c", "a", "b", "d"), AppLayout.apply(order, AppLayout.unhide(hidden, "c"), installed))
    }

    @Test fun orderRoundTripsAndToleratesJunk() {
        assertEquals(listOf("a", "b"), AppLayout.decodeOrder(AppLayout.encodeOrder(listOf("a", "b"))))
        assertEquals(emptyList<String>(), AppLayout.decodeOrder(null))
        assertEquals(listOf("a", "b"), AppLayout.decodeOrder(" a \n\nb\na"))
    }
}
