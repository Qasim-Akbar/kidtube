package com.bbro.kidtube.core

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Small AES-256-GCM wrapper over an Android Keystore key (replaces the deprecated security-crypto).
 * The key never leaves the Keystore; callers store the returned blob (e.g. a PIN hash) wherever they like.
 *
 * Blob layout: 1 byte IV length | IV | ciphertext + 128-bit GCM tag.
 * [aad] binds a blob to its purpose (e.g. "parent-pin"); decrypting with different aad fails.
 */
class KeystoreCipher(private val keyAlias: String) : SecretCipher {

    override fun encrypt(plain: ByteArray, aad: ByteArray?): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        aad?.let(cipher::updateAAD)
        val iv = cipher.iv
        return byteArrayOf(iv.size.toByte()) + iv + cipher.doFinal(plain)
    }

    /** @throws java.security.GeneralSecurityException if the blob was tampered with, or the key is gone. */
    override fun decrypt(blob: ByteArray, aad: ByteArray?): ByteArray {
        require(blob.isNotEmpty()) { "empty blob" }
        val ivLength = blob[0].toInt() and 0xFF
        require(blob.size > 1 + ivLength) { "blob too short" }
        val iv = blob.copyOfRange(1, 1 + ivLength)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(TAG_BITS, iv))
        aad?.let(cipher::updateAAD)
        return cipher.doFinal(blob, 1 + ivLength, blob.size - 1 - ivLength)
    }

    fun deleteKey() {
        keyStore().deleteEntry(keyAlias)
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun getOrCreateKey(): SecretKey {
        (keyStore().getKey(keyAlias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
    }
}
