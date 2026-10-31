package tvhome.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SkeletonTest {
    @Test
    fun buildIsWired() {
        assertEquals("m1", Version.NAME)
    }
}
