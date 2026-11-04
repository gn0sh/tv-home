package tvhome.core.assistant

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class OpenFilterTest {
    private val own = "com.gnosh.tvhome"

    @Test fun acceptsAnotherAppByPackageOrComponent() {
        assertTrue(OpenFilter.accepts("com.netflix.ninja", null, own))
        assertTrue(OpenFilter.accepts(null, "com.netflix.ninja", own))
        assertTrue(OpenFilter.accepts("com.netflix.ninja", "com.netflix.ninja", own))
    }

    /** trampoline-component-pkg: ours in either place is refused, so the trampoline can't reach our own components. */
    @Test fun refusesOurOwnPackageInEitherPlace() {
        assertFalse(OpenFilter.accepts(own, null, own))
        assertFalse(OpenFilter.accepts(null, own, own))
        assertFalse(OpenFilter.accepts("com.netflix.ninja", own, own))
        assertFalse(OpenFilter.accepts(own, "com.netflix.ninja", own))
    }

    @Test fun refusesAnIntentNamingNoApp() {
        assertFalse(OpenFilter.accepts(null, null, own))
        assertFalse(OpenFilter.accepts("", " ", own))
    }

    @Test fun stripsEveryUriGrantAndAddsNewTask() {
        val all = 0x1 or 0x2 or 0x40 or 0x80
        val other = 0x00008000 // FLAG_ACTIVITY_CLEAR_TASK, kept
        val f = OpenFilter.flags(all or other)
        assertEquals(0, f and all)
        assertEquals(other, f and other)
        assertEquals(OpenFilter.FLAG_ACTIVITY_NEW_TASK, f and OpenFilter.FLAG_ACTIVITY_NEW_TASK)
        assertEquals(OpenFilter.FLAG_ACTIVITY_NEW_TASK, OpenFilter.flags(0))
    }
}
