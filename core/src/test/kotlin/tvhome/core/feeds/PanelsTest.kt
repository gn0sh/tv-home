package tvhome.core.feeds

import kotlinx.serialization.Serializable
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import tvhome.core.state.CacheFile
import java.time.Duration
import java.time.Instant

class PanelsTest {
    private val t0 = Instant.parse("2026-11-02T20:00:00Z")
    private val p = PanelPolicy(staleAfter = Duration.ofMinutes(30))

    @Serializable data class Box(val n: Int, val s: String)

    @Test fun staleAtThresholdMinusOneZeroPlusOneSecond() {
        val s = Stamped("x", t0)
        val keep = p.copy(hideWhenStale = false)
        val at = t0.plus(Duration.ofMinutes(30))
        assertEquals(false, (view(s, keep, true, at.minusSeconds(1)) as PanelView.Shown).stale)
        assertEquals(true, (view(s, keep, true, at) as PanelView.Shown).stale)
        assertEquals(true, (view(s, keep, true, at.plusSeconds(1)) as PanelView.Shown).stale)
        assertTrue(view(s, p, true, at.minusSeconds(1)) is PanelView.Shown)
        assertEquals(PanelView.NoData, view(s, p, true, at))
        assertEquals(PanelView.NoData, view(s, p, true, at.plusSeconds(1)))
    }

    @Test fun ageTextOnlyFromFifteenMinutesWithAFakeClock() {
        val s = Stamped(1, t0)
        val w = PanelPolicies.WEATHER
        fun age(min: Long) = (view(s, w, true, t0.plus(Duration.ofMinutes(min))) as PanelView.Shown).ageText
        assertNull(age(14))
        assertEquals("Updated 15 min ago", age(15))
        assertEquals("Updated 1 hour ago", age(61))
    }

    @Test fun offAndNoData() {
        assertEquals(PanelView.Off, view(Stamped(1, t0), p, false, t0))
        assertEquals(PanelView.NoData, view<Int>(null, p, true, t0))
        assertEquals(PanelView.Off, view<Int>(null, p, false, t0))
    }

    @Test fun futureStampIsNoDataBeyondFiveMinutesOfSkew() {
        assertTrue(view(Stamped(1, t0.plus(Duration.ofMinutes(5))), p, true, t0) is PanelView.Shown)
        assertEquals(PanelView.NoData, view(Stamped(1, t0.plus(Duration.ofMinutes(5)).plusSeconds(1)), p, true, t0))
        assertEquals(PanelView.NoData, view(Stamped(1, t0.plus(Duration.ofDays(365))), PanelPolicies.ALERTS, true, t0))
    }

    @Test fun codecRoundTrip() {
        val s = Stamped(Box(3, "hi"), t0)
        val text = PanelCacheCodec.encode(s, Box.serializer())
        assertTrue(text.startsWith("""{"v":1,"at":"2026-11-02T20:00:00Z","data":"""), text)
        assertEquals(s, PanelCacheCodec.decode(text, Box.serializer(), t0))
    }

    @Test fun corruptOversizeWrongVersionAndFutureAreNull() {
        val ser = Box.serializer()
        val good = PanelCacheCodec.encode(Stamped(Box(3, "hi"), t0), ser)
        for (bad in listOf(null, "", "{", "null", "[]", good.dropLast(3), good.replace("\"v\":1", "\"v\":2"),
            good.replace("2026-11-02T20:00:00Z", "yesterday"), """{"v":1,"at":"2026-11-02T20:00:00Z","data":{"n":"x"}}""")) {
            assertNull(PanelCacheCodec.decode(bad, ser, t0), bad)
        }
        val big = """{"v":1,"at":"2026-11-02T20:00:00Z","data":{"n":1,"s":"${"a".repeat(PanelCacheCodec.MAX_BYTES)}"}}"""
        assertNull(PanelCacheCodec.decode(big, ser, t0))
        // Multi-byte characters count in bytes, not chars.
        val wide = """{"v":1,"at":"2026-11-02T20:00:00Z","data":{"n":1,"s":"${"é".repeat(PanelCacheCodec.MAX_BYTES / 2)}"}}"""
        assertNull(PanelCacheCodec.decode(wide, ser, t0))
        val future = PanelCacheCodec.encode(Stamped(Box(1, "x"), t0.plus(Duration.ofMinutes(6))), ser)
        assertNull(PanelCacheCodec.decode(future, ser, t0))
    }

    @Test fun loadAndSaveNeverThrow() {
        val broken = object : CacheFile {
            override fun read(): String = throw java.io.IOException("x")
            override fun writeAtomic(text: String) = throw java.io.IOException("x")
        }
        assertNull(PanelCacheCodec.load(broken, Box.serializer(), t0))
        assertFalse(PanelCacheCodec.save(broken, Stamped(Box(1, "x"), t0), Box.serializer()))
    }

    @Test fun jvmCacheFileHonoursItsCapAndMakesTheDirectory() {
        val dir = java.nio.file.Files.createTempDirectory("panels").toFile()
        val f = tvhome.core.state.JvmCacheFile(java.io.File(dir, "panels/x.json"), PanelCacheCodec.MAX_BYTES)
        assertTrue(PanelCacheCodec.save(f, Stamped(Box(1, "x"), t0), Box.serializer()))
        assertEquals(Box(1, "x"), PanelCacheCodec.load(f, Box.serializer(), t0)!!.value)
        java.io.File(dir, "panels/x.json").writeText("a".repeat(PanelCacheCodec.MAX_BYTES + 1))
        assertNull(f.read())
        dir.deleteRecursively()
    }
}
