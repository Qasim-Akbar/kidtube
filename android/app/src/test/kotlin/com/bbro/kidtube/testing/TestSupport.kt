package com.bbro.kidtube.testing

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.bbro.kidtube.core.SecretCipher
import com.bbro.kidtube.core.SessionStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.GeneralSecurityException
import java.time.Instant
import java.util.Base64
import javax.crypto.AEADBadTagException

/**
 * Reversible stand-in for the Keystore cipher: XOR-scrambles the bytes (so plaintext never appears in the DataStore
 * file) and, like GCM, refuses to decrypt under different AAD.
 */
class FakeSecretCipher : SecretCipher {
    var failDecrypt = false

    override fun encrypt(plain: ByteArray, aad: ByteArray?): ByteArray {
        val tag = aad ?: ByteArray(0)
        return byteArrayOf(tag.size.toByte()) + tag + scramble(plain)
    }

    override fun decrypt(blob: ByteArray, aad: ByteArray?): ByteArray {
        if (failDecrypt) throw GeneralSecurityException("key lost")
        require(blob.isNotEmpty()) { "empty blob" }
        val n = blob[0].toInt()
        val stored = blob.copyOfRange(1, 1 + n)
        if (!stored.contentEquals(aad ?: ByteArray(0))) throw AEADBadTagException("aad mismatch")
        return scramble(blob.copyOfRange(1 + n, blob.size))
    }

    private fun scramble(bytes: ByteArray) = ByteArray(bytes.size) { (bytes[it].toInt() xor 0x5A).toByte() }
}

/**
 * In-memory stand-in for the Preferences DataStore. (The real file-backed one cannot replace its file on a Windows JVM:
 * File.renameTo and Okio's atomic move both fail from the second write on, whereas Android's Linux is fine.)
 * It honours the same contract the SessionStore relies on: serialised read-modify-write via updateData, a Flow of
 * the latest value, and state that outlives any one SessionStore (use the same instance to simulate a restart).
 */
class InMemoryPreferencesDataStore : DataStore<Preferences> {
    private val state = MutableStateFlow<Preferences>(emptyPreferences())
    private val mutex = Mutex()

    override val data: Flow<Preferences> = state

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
        mutex.withLock { transform(state.value).also { state.value = it } }
}

/** A [SessionStore] over [backing]. Pass an existing [backing] to a new harness to simulate a process restart. */
class SessionHarness(
    val backing: InMemoryPreferencesDataStore = InMemoryPreferencesDataStore(),
    val cipher: FakeSecretCipher = FakeSecretCipher(),
) {
    val store = SessionStore(backing, cipher)

    /** Every value currently persisted, as text (what would be in the file). */
    suspend fun storedText(): String = backing.data.first().asMap().values.joinToString(" | ")

    fun close() = Unit
}

/** A structurally valid but unsigned JWT with the claims the backend puts in its tokens. [unique] makes tokens distinct. */
fun fakeJwt(exp: Instant, familyId: String = "fam-1", type: String = "access", unique: String = ""): String {
    val enc = Base64.getUrlEncoder().withoutPadding()
    fun part(text: String) = enc.encodeToString(text.toByteArray())
    val payload = """{"sub":"parent-1","family_id":"$familyId","type":"$type","exp":${exp.epochSecond},"jti":"$unique"}"""
    return part("""{"alg":"HS256","typ":"JWT"}""") + "." + part(payload) + "." + part("signature")
}

fun resourceText(path: String): String =
    checkNotNull(object {}.javaClass.getResourceAsStream(path)) { "missing test resource $path" }
        .bufferedReader().use { it.readText() }
