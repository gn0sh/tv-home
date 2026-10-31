package tvhome.core.secrets

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import tvhome.core.FakeSecrets
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

class ProvisionTest {
    private val url = FakeSecrets.CALENDAR_URL
    private fun extras(vararg p: Pair<String, String>): (String) -> String? = p.toMap()::get

    @Test fun validUrlExtraIsStoredTrimmed() {
        val c = Provision.fromExtras(extras("calendar_url" to "  $url\t"))
        assertTrue(c is ProvisionCommand.Store)
        c as ProvisionCommand.Store
        assertEquals(SecretKey.CALENDAR_URL, c.key)
        assertEquals(url, c.value)
    }

    @Test fun badUrlIsRejected() {
        assertEquals(
            ProvisionCommand.Rejected(ProvisionError.BAD_FORMAT),
            Provision.fromExtras(extras("calendar_url" to "https://example.com/x")),
        )
        assertEquals(
            ProvisionCommand.Rejected(ProvisionError.EMPTY),
            Provision.fromExtras(extras("calendar_url" to "  ")),
        )
        // trailing newline is refused, not silently dropped
        assertTrue(Provision.fromExtras(extras("calendar_url" to "$url\n")) is ProvisionCommand.Rejected)
    }

    @Test fun clearWinsAndNeedsAKnownKey() {
        assertEquals(ProvisionCommand.Clear(SecretKey.CALENDAR_URL), Provision.fromExtras(extras("clear" to "calendar_url", "calendar_url" to url)))
        assertEquals(ProvisionCommand.Rejected(ProvisionError.BAD_FORMAT), Provision.fromExtras(extras("clear" to "everything")))
    }

    @Test fun nothingAndAmbiguous() {
        assertEquals(ProvisionCommand.NoOp, Provision.fromExtras(extras()))
        assertEquals(
            ProvisionCommand.Rejected(ProvisionError.BAD_FORMAT),
            Provision.fromExtras(extras("calendar_url" to url, "anthropic_api_key" to FakeSecrets.API_KEY)),
        )
    }

    @Test fun streamIsReadStrictlyAndBounded() {
        val ok = Provision.fromStream(SecretKey.CALENDAR_URL, ByteArrayInputStream(url.toByteArray()))
        assertTrue(ok is ProvisionCommand.Store && ok.value == url)
        assertTrue(Provision.fromStream(SecretKey.CALENDAR_URL, ByteArrayInputStream(ByteArray(5000) { 'a'.code.toByte() })) is ProvisionCommand.Rejected)
        assertTrue(Provision.fromStream(SecretKey.CALENDAR_URL, ByteArrayInputStream(byteArrayOf(0xC3.toByte(), 0x28))) is ProvisionCommand.Rejected)
        assertTrue(Provision.fromStream(SecretKey.CALENDAR_URL, ByteArrayInputStream(ByteArray(0))) is ProvisionCommand.Rejected)
    }

    @Test fun streamReadStopsAtTheLimit() {
        var served = 0
        val endless = object : InputStream() {
            override fun read(): Int { served++; return 'a'.code }
            override fun read(b: ByteArray, off: Int, len: Int): Int { served += len; java.util.Arrays.fill(b, off, off + len, 'a'.code.toByte()); return len }
        }
        assertTrue(Provision.fromStream(SecretKey.CALENDAR_URL, endless) is ProvisionCommand.Rejected)
        assertTrue(served <= Provision.MAX_VALUE_BYTES + 1, "read $served")
    }

    @Test fun apiKeyUsesItsOwnPattern() {
        assertTrue(Provision.fromStream(SecretKey.ANTHROPIC_API_KEY, ByteArrayInputStream(FakeSecrets.API_KEY.toByteArray())) is ProvisionCommand.Store)
        assertTrue(Provision.fromStream(SecretKey.ANTHROPIC_API_KEY, ByteArrayInputStream(url.toByteArray())) is ProvisionCommand.Rejected)
    }

    @Test fun applyStoresClearsAndReportsFixedText() {
        val store = InMemorySecretStore()
        assertEquals(ProvisionOutcome.SAVED_CALENDAR, Provision.apply(store, ProvisionCommand.Store(SecretKey.CALENDAR_URL, url)))
        assertEquals(url, store.get(SecretKey.CALENDAR_URL))
        assertEquals(ProvisionOutcome.CLEARED_CALENDAR, Provision.apply(store, ProvisionCommand.Clear(SecretKey.CALENDAR_URL)))
        assertNull(store.get(SecretKey.CALENDAR_URL))
        assertEquals(ProvisionOutcome.INVALID, Provision.apply(store, ProvisionCommand.Rejected(ProvisionError.BAD_FORMAT)))
        assertEquals(ProvisionOutcome.NOTHING, Provision.apply(store, ProvisionCommand.NoOp))
    }

    @Test fun aFailingStoreIsFailedWithNoText() {
        val failing = object : SecretStore {
            override fun get(k: SecretKey): String? = null
            override fun put(k: SecretKey, v: String) { throw IOException("disk said: $v") }
            override fun clear(k: SecretKey) {}
        }
        val out = Provision.apply(failing, ProvisionCommand.Store(SecretKey.CALENDAR_URL, url))
        assertEquals(ProvisionOutcome.FAILED, out)
        assertTrue(ProvisionOutcome.entries.none { it.message.contains("private-") || it.message.contains("https") })
    }

    @Test fun commandToStringHidesTheValue() {
        assertFalse(ProvisionCommand.Store(SecretKey.CALENDAR_URL, url).toString().contains("private-"))
    }

    // ---- release crash handler text (#3) ----

    @Test fun crashSummaryHasClassNamesOnly() {
        val t = IllegalStateException("leak: $url", java.net.UnknownHostException("host ${FakeSecrets.API_KEY}"))
        val s = CrashSummary.of(t)
        assertEquals("tvhome crash: java.lang.IllegalStateException <- java.net.UnknownHostException", s)
        assertFalse(s.contains("private-") || s.contains("sk-ant-") || s.contains("leak"))
    }

    @Test fun crashSummaryIsBoundedAndSurvivesCauseLoops() {
        var t: Throwable = RuntimeException()
        repeat(50) { t = RuntimeException(t) }
        assertEquals(5, CrashSummary.of(t).removePrefix("tvhome crash: ").split(" <- ").size)
        val self = object : RuntimeException() { override val cause: Throwable get() = this }
        assertTrue(CrashSummary.of(self).startsWith("tvhome crash: "))
    }
}
