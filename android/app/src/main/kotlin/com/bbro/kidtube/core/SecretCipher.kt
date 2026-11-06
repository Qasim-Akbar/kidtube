package com.bbro.kidtube.core

/** Authenticated encryption of small secrets (tokens, PIN blobs). [KeystoreCipher] is the real one; tests use a fake. */
interface SecretCipher {
    fun encrypt(plain: ByteArray, aad: ByteArray? = null): ByteArray

    /** @throws java.security.GeneralSecurityException if the blob was tampered with, bound to other aad, or the key is gone. */
    fun decrypt(blob: ByteArray, aad: ByteArray? = null): ByteArray
}
