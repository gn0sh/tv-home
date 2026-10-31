package tvhome.core.secrets

import tvhome.core.io.readUpTo
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec

/** Seals a value into an opaque blob and opens it again. [aad] binds a blob to its slot. Never logs or throws text. */
interface Sealer {
    fun seal(plain: ByteArray, aad: ByteArray): ByteArray

    /** Null for anything that does not open: tampered, wrong slot, wrong key, truncated, unknown version. */
    fun open(blob: ByteArray, aad: ByteArray): ByteArray?
}

/**
 * AES-256-GCM. Blob layout: `[version=1][ivLen][iv][ciphertext+16-byte tag]`. The IV is never chosen here: the cipher
 * is initialised with the key only, so the provider draws a fresh random IV for every write (AndroidKeyStore
 * requires it; SunJCE does the same). [key] is the Keystore key in the app and a software key in tests.
 */
class AesGcmSealer(private val key: () -> javax.crypto.SecretKey) : Sealer {
    override fun seal(plain: ByteArray, aad: ByteArray): ByteArray {
        val c = Cipher.getInstance(TRANSFORMATION)
        c.init(Cipher.ENCRYPT_MODE, key())
        c.updateAAD(aad)
        val ct = c.doFinal(plain)
        val iv = c.iv
        check(iv != null && iv.size == IV_BYTES) { "iv" }
        return byteArrayOf(VERSION, iv.size.toByte()) + iv + ct
    }

    override fun open(blob: ByteArray, aad: ByteArray): ByteArray? {
        if (blob.size < 2 + IV_BYTES + TAG_BYTES || blob[0] != VERSION || blob[1].toInt() != IV_BYTES) return null
        return try {
            val c = Cipher.getInstance(TRANSFORMATION)
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BYTES * 8, blob, 2, IV_BYTES))
            c.updateAAD(aad)
            c.doFinal(blob, 2 + IV_BYTES, blob.size - 2 - IV_BYTES)
        } catch (_: GeneralSecurityException) {
            null
        }
    }

    override fun toString(): String = "AesGcmSealer"

    companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val VERSION: Byte = 1
        const val IV_BYTES = 12
        const val TAG_BYTES = 16
    }
}

/**
 * One sealed file per [SecretKey] in [dir] (the app passes `noBackupFilesDir`). Writes are atomic (temp file, rename).
 * A file that does not open (key lost, tampered, too big) reads as no secret. Values and file contents never appear in
 * [toString]. The android side is `KeystoreSecretStore`, which only supplies the Keystore key.
 */
class FileSecretStore(private val dir: File, private val sealer: Sealer) : SecretStore {
    @Synchronized override fun get(k: SecretKey): String? {
        val f = fileOf(k)
        return try {
            if (!f.isFile) return null
            val blob = f.inputStream().use { it.readUpTo(MAX_BLOB_BYTES + 1) }
            if (blob.size > MAX_BLOB_BYTES) return null
            sealer.open(blob, aadOf(k))?.toString(Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    @Synchronized override fun put(k: SecretKey, v: String) {
        val blob = sealer.seal(v.toByteArray(Charsets.UTF_8), aadOf(k))
        val target = fileOf(k)
        val tmp = File(dir, target.name + ".tmp")
        try {
            dir.mkdirs()
            FileOutputStream(tmp).use { out ->
                out.write(blob)
                out.flush()
                out.fd.sync()
            }
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (t: Throwable) {
            tmp.delete()
            throw t
        }
    }

    @Synchronized override fun clear(k: SecretKey) {
        fileOf(k).delete()
    }

    private fun fileOf(k: SecretKey) = File(dir, "secret-${k.name.lowercase()}.bin")

    private fun aadOf(k: SecretKey) = "tvhome.secret.v1:${k.name}".toByteArray(Charsets.UTF_8)

    override fun toString(): String = "FileSecretStore"

    private companion object {
        const val MAX_BLOB_BYTES = 4096
    }
}
