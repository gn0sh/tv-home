package tvhome.core.secrets

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tvhome.core.FakeSecrets
import java.io.File
import javax.crypto.KeyGenerator

class FileSecretStoreTest {
    private val aesKey = KeyGenerator.getInstance("AES").also { it.init(256) }.generateKey()
    private fun store(dir: File, key: javax.crypto.SecretKey = aesKey) = FileSecretStore(dir, AesGcmSealer { key })
    private val url = FakeSecrets.CALENDAR_URL

    @Test fun roundTripAndClear(@TempDir dir: File) {
        val s = store(dir)
        assertNull(s.get(SecretKey.CALENDAR_URL))
        s.put(SecretKey.CALENDAR_URL, url)
        assertEquals(url, s.get(SecretKey.CALENDAR_URL))
        assertNull(s.get(SecretKey.ANTHROPIC_API_KEY))
        s.clear(SecretKey.CALENDAR_URL)
        assertNull(s.get(SecretKey.CALENDAR_URL))
        s.clear(SecretKey.CALENDAR_URL) // clearing nothing is fine
    }

    @Test fun survivesANewInstanceOverTheSameFiles(@TempDir dir: File) {
        store(dir).put(SecretKey.CALENDAR_URL, url)
        assertEquals(url, store(dir).get(SecretKey.CALENDAR_URL))
    }

    @Test fun fileHoldsNoPlaintextAndEveryWriteUsesAFreshIv(@TempDir dir: File) {
        val s = store(dir)
        val f = File(dir, "secret-calendar_url.bin")
        s.put(SecretKey.CALENDAR_URL, url)
        val first = f.readBytes()
        s.put(SecretKey.CALENDAR_URL, url)
        val second = f.readBytes()
        assertFalse(String(first, Charsets.ISO_8859_1).contains("private-"))
        assertFalse(first.contentEquals(second), "same value, same key: bytes must differ (random IV)")
        assertFalse(first.copyOfRange(2, 14).contentEquals(second.copyOfRange(2, 14)), "IVs differ")
        assertEquals(1, first[0].toInt())
        assertEquals(12, first[1].toInt())
        assertEquals(url, s.get(SecretKey.CALENDAR_URL))
    }

    @Test fun tamperedBlobReadsAsNoSecret(@TempDir dir: File) {
        val s = store(dir)
        s.put(SecretKey.CALENDAR_URL, url)
        val f = File(dir, "secret-calendar_url.bin")
        val b = f.readBytes()
        b[b.size - 1] = (b[b.size - 1].toInt() xor 1).toByte()
        f.writeBytes(b)
        assertNull(s.get(SecretKey.CALENDAR_URL))
    }

    @Test fun truncatedOrEmptyOrUnknownVersionReadsAsNoSecret(@TempDir dir: File) {
        val s = store(dir)
        val f = File(dir, "secret-calendar_url.bin")
        s.put(SecretKey.CALENDAR_URL, url)
        val good = f.readBytes()
        f.writeBytes(good.copyOf(20)); assertNull(s.get(SecretKey.CALENDAR_URL))
        f.writeBytes(ByteArray(0)); assertNull(s.get(SecretKey.CALENDAR_URL))
        f.writeBytes(good.also { it[0] = 2 }); assertNull(s.get(SecretKey.CALENDAR_URL))
    }

    @Test fun oversizeFileReadsAsNoSecret(@TempDir dir: File) {
        File(dir, "secret-calendar_url.bin").writeBytes(ByteArray(100_000) { 1 })
        assertNull(store(dir).get(SecretKey.CALENDAR_URL))
    }

    @Test fun aSealedFileMovedToAnotherSlotDoesNotOpen(@TempDir dir: File) {
        val s = store(dir)
        s.put(SecretKey.CALENDAR_URL, url)
        File(dir, "secret-calendar_url.bin").copyTo(File(dir, "secret-anthropic_api_key.bin"))
        assertNull(s.get(SecretKey.ANTHROPIC_API_KEY))
    }

    @Test fun aDifferentKeyCannotOpenIt(@TempDir dir: File) {
        store(dir).put(SecretKey.CALENDAR_URL, url)
        val other = KeyGenerator.getInstance("AES").also { it.init(256) }.generateKey()
        assertNull(store(dir, other).get(SecretKey.CALENDAR_URL))
    }

    @Test fun failedWriteLeavesTheOldValueAndNoTempFile(@TempDir dir: File) {
        val good = store(dir)
        good.put(SecretKey.CALENDAR_URL, url)
        val broken = FileSecretStore(dir, object : Sealer {
            override fun seal(plain: ByteArray, aad: ByteArray): ByteArray = throw IllegalStateException("boom")
            override fun open(blob: ByteArray, aad: ByteArray): ByteArray? = null
        })
        assertThrows(IllegalStateException::class.java) { broken.put(SecretKey.CALENDAR_URL, "x") }
        assertEquals(url, good.get(SecretKey.CALENDAR_URL))
        assertEquals(listOf("secret-calendar_url.bin"), dir.list()!!.toList())
    }

    @Test fun toStringsHideEverything(@TempDir dir: File) {
        val s = store(dir)
        s.put(SecretKey.CALENDAR_URL, url)
        assertEquals("FileSecretStore", s.toString())
        assertEquals("AesGcmSealer", AesGcmSealer { aesKey }.toString())
    }
}
