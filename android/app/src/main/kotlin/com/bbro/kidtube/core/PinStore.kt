package com.bbro.kidtube.core

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/** A salted PBKDF2 hash of a PIN. The PIN itself is never kept. */
class PinRecord(val iterations: Int, val salt: ByteArray, val hash: ByteArray) {
    fun encode(): String {
        val b64 = Base64.getEncoder()
        return "1:$iterations:${b64.encodeToString(salt)}:${b64.encodeToString(hash)}"
    }

    companion object {
        fun decode(text: String): PinRecord? {
            val parts = text.split(':')
            if (parts.size != 4 || parts[0] != "1") return null
            return try {
                val b64 = Base64.getDecoder()
                PinRecord(parts[1].toInt(), b64.decode(parts[2]), b64.decode(parts[3]))
            } catch (_: IllegalArgumentException) {
                null
            }
        }
    }
}

/** PBKDF2-HMAC-SHA256 with a random 16-byte salt (javax.crypto, available on every supported API level). */
class PinHasher(private val random: SecureRandom, private val iterations: Int) {
    @Inject
    constructor() : this(SecureRandom(), DEFAULT_ITERATIONS)

    fun create(pin: String): PinRecord {
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        return PinRecord(iterations, salt, derive(pin, salt, iterations))
    }

    /** Constant-time comparison against [record]. */
    fun verify(pin: String, record: PinRecord): Boolean =
        MessageDigest.isEqual(derive(pin, record.salt, record.iterations), record.hash)

    private fun derive(pin: String, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, iterations, KEY_BITS)
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    companion object {
        const val DEFAULT_ITERATIONS = 100_000
        private const val SALT_BYTES = 16
        private const val KEY_BITS = 256
    }
}

sealed interface PinResult {
    data object Success : PinResult

    /** Wrong PIN; [attemptsLeft] tries remain before the lock. */
    data class Wrong(val attemptsLeft: Int) : PinResult

    /** Too many wrong tries; nothing is checked until [remainingSeconds] have passed. */
    data class Locked(val remainingSeconds: Long) : PinResult

    /** No PIN has been created (or it could not be read). */
    data object NotSet : PinResult
}

/**
 * The parent PIN. Only a salted PBKDF2 hash is stored, as a blob encrypted with the Keystore-backed [SecretCipher] (like
 * the session tokens). Wrong tries are counted and persisted: [MAX_ATTEMPTS] wrong tries lock the PIN for [LOCK_MS].
 *
 * The lock is measured on the monotonic clock; the wall clock is only the fallback after a reboot (when the monotonic
 * clock restarts) and is capped at one lock period, so moving the device clock cannot skip the lock or extend it.
 * Time comes from the injected [Clock].
 */
@Singleton
class PinStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val cipher: SecretCipher,
    private val clock: Clock,
    private val hasher: PinHasher,
) {
    private val mutex = Mutex()

    val hasPin: Flow<Boolean> = dataStore.data.map { it[BLOB] != null }.distinctUntilChanged()

    suspend fun isSet(): Boolean = hasPin.first()

    /** Stores a new PIN (replacing any old one) and clears the wrong-try counter and any lock. */
    suspend fun setPin(pin: String) {
        require(isValidPin(pin)) { "PIN must be $PIN_LENGTH digits" }
        val record = withContext(Dispatchers.Default) { hasher.create(pin) }
        val blob = cipher.encrypt(record.encode().toByteArray(Charsets.UTF_8), AAD)
        mutex.withLock {
            dataStore.edit { p ->
                p[BLOB] = Base64.getEncoder().encodeToString(blob)
                clearLock(p)
            }
        }
    }

    suspend fun verify(pin: String): PinResult = mutex.withLock {
        val remaining = lockRemainingMs()
        if (remaining > 0) return@withLock PinResult.Locked(toSeconds(remaining))

        val record = readRecord() ?: return@withLock PinResult.NotSet
        val ok = withContext(Dispatchers.Default) { hasher.verify(pin, record) }
        if (ok) {
            dataStore.edit(::clearLock)
            return@withLock PinResult.Success
        }

        val attempts = (dataStore.data.first()[ATTEMPTS] ?: 0) + 1
        if (attempts >= MAX_ATTEMPTS) {
            val elapsed = clock.elapsedRealtimeMillis()
            dataStore.edit { p ->
                p[ATTEMPTS] = 0
                p[LOCK_START_ELAPSED] = elapsed
                p[LOCK_END_ELAPSED] = elapsed + LOCK_MS
                p[LOCK_END_WALL] = clock.now().toEpochMilli() + LOCK_MS
            }
            PinResult.Locked(toSeconds(LOCK_MS))
        } else {
            dataStore.edit { it[ATTEMPTS] = attempts }
            PinResult.Wrong(MAX_ATTEMPTS - attempts)
        }
    }

    /** Seconds left on the lock, 0 when not locked. */
    suspend fun lockRemainingSeconds(): Long = toSeconds(lockRemainingMs())

    suspend fun clear() {
        mutex.withLock {
            dataStore.edit { p ->
                p.remove(BLOB)
                clearLock(p)
            }
        }
    }

    // ---- internals -------------------------------------------------------------------------

    private suspend fun lockRemainingMs(): Long {
        val p = dataStore.data.first()
        val endElapsed = p[LOCK_END_ELAPSED] ?: return 0
        val nowElapsed = clock.elapsedRealtimeMillis()
        val startElapsed = p[LOCK_START_ELAPSED] ?: 0
        return if (nowElapsed >= startElapsed) {
            (endElapsed - nowElapsed).coerceAtLeast(0)
        } else { // the monotonic clock restarted: the device rebooted
            val wallRemaining = (p[LOCK_END_WALL] ?: 0) - clock.now().toEpochMilli()
            wallRemaining.coerceIn(0, LOCK_MS)
        }
    }

    private suspend fun readRecord(): PinRecord? {
        val stored = dataStore.data.first()[BLOB] ?: return null
        return try {
            PinRecord.decode(String(cipher.decrypt(Base64.getDecoder().decode(stored), AAD), Charsets.UTF_8))
        } catch (_: GeneralSecurityException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun clearLock(p: androidx.datastore.preferences.core.MutablePreferences) {
        p.remove(ATTEMPTS)
        p.remove(LOCK_START_ELAPSED)
        p.remove(LOCK_END_ELAPSED)
        p.remove(LOCK_END_WALL)
    }

    private fun toSeconds(ms: Long): Long = (ms + 999) / 1000

    companion object {
        const val PIN_LENGTH = 4
        const val MAX_ATTEMPTS = 5
        const val LOCK_MS = 30_000L

        fun isValidPin(pin: String): Boolean = pin.length == PIN_LENGTH && pin.all { it in '0'..'9' }

        private val AAD = "kidtube.pin.v1".toByteArray(Charsets.UTF_8)
        private val BLOB = stringPreferencesKey("secret_parent_pin")
        private val ATTEMPTS = intPreferencesKey("pin_failed_attempts")
        private val LOCK_START_ELAPSED = longPreferencesKey("pin_lock_start_elapsed")
        private val LOCK_END_ELAPSED = longPreferencesKey("pin_lock_end_elapsed")
        private val LOCK_END_WALL = longPreferencesKey("pin_lock_end_wall_ms")
    }
}
