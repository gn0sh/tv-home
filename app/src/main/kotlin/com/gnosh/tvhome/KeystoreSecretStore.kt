package com.gnosh.tvhome

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import tvhome.core.secrets.AesGcmSealer
import tvhome.core.secrets.FileSecretStore
import tvhome.core.secrets.SecretStore
import java.security.KeyStore
import javax.crypto.KeyGenerator

/**
 * Secrets sealed with a non-exportable AES-256-GCM key in AndroidKeyStore, one file per secret in `noBackupFilesDir`.
 * The file format, the random IV on every write, and the "does not open means no secret" rule live in `:core`
 * ([FileSecretStore], [AesGcmSealer]) and are tested on the JVM; this class only supplies the Keystore key.
 */
class KeystoreSecretStore(context: Context) :
    SecretStore by FileSecretStore(context.noBackupFilesDir, AesGcmSealer(::keystoreKey)) {
    override fun toString(): String = "KeystoreSecretStore"
}

private const val ANDROID_KEYSTORE = "AndroidKeyStore"
private const val KEY_ALIAS = "tvhome.secrets.v1"

/** The Keystore key, created on first use. Never read out: the Keystore only hands back a handle. */
@Synchronized
private fun keystoreKey(): javax.crypto.SecretKey {
    val ks = KeyStore.getInstance(ANDROID_KEYSTORE)
    ks.load(null)
    (ks.getKey(KEY_ALIAS, null) as? javax.crypto.SecretKey)?.let { return it }
    val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
    gen.init(
        KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build(),
    )
    return gen.generateKey()
}
