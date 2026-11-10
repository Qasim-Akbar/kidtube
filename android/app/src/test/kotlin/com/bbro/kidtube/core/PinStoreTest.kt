package com.bbro.kidtube.core

import com.bbro.kidtube.testing.FakeSecretCipher
import com.bbro.kidtube.testing.InMemoryPreferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant

class PinStoreTest {
    private val backing = InMemoryPreferencesDataStore()
    private val cipher = FakeSecretCipher()
    private val clock = FakeClock(Instant.parse("2026-11-10T10:00:00Z"))
    private val hasher = PinHasher(SecureRandom(), 1_000) // few iterations: fast tests

    private fun store(
        data: InMemoryPreferencesDataStore = backing,
        c: Clock = clock,
    ) = PinStore(data, cipher, c, hasher)

    // ---- hashing -----------------------------------------------------------------------------

    @Test
    fun hashIsSaltedAndVerifies() {
        val a = hasher.create("1234")
        val b = hasher.create("1234")

        assertFalse("random salt: same PIN, different salt", a.salt.contentEquals(b.salt))
        assertFalse("different salt, different hash", a.hash.contentEquals(b.hash))
        assertTrue(hasher.verify("1234", a))
        assertFalse(hasher.verify("1235", a))
        assertEquals(16, a.salt.size)
        assertEquals(32, a.hash.size)
    }

    @Test
    fun recordSurvivesEncodeDecode() {
        val record = hasher.create("0042")
        val back = PinRecord.decode(record.encode())!!

        assertEquals(record.iterations, back.iterations)
        assertArrayEquals(record.salt, back.salt)
        assertArrayEquals(record.hash, back.hash)
        assertTrue(hasher.verify("0042", back))
    }

    @Test
    fun garbageRecordsDoNotDecode() {
        assertEquals(null, PinRecord.decode("nonsense"))
        assertEquals(null, PinRecord.decode("2:1:a:b"))
        assertEquals(null, PinRecord.decode("1:x:***:***"))
    }

    // ---- storage -----------------------------------------------------------------------------

    @Test
    fun thePinItselfIsNeverStored() = runBlocking {
        val store = store()
        assertFalse(store.isSet())
        store.setPin("7391")

        assertTrue(store.isSet())
        val everything = backing.data.first().asMap().entries.joinToString(" ") { "${it.key.name}=${it.value}" }
        assertFalse("the PIN must not appear in the stored data", everything.contains("7391"))
        // The stored blob is the encrypted salted hash, not a bare digest of the PIN.
        assertTrue(everything.contains("secret_parent_pin"))
    }

    @Test
    fun setPinRejectsAnythingButFourDigits() = runBlocking {
        val store = store()
        for (bad in listOf("123", "12345", "12a4", "", "١٢٣٤")) {
            try {
                store.setPin(bad)
                throw AssertionError("accepted '$bad'")
            } catch (_: IllegalArgumentException) {
                // expected
            }
        }
        assertFalse(store.isSet())
    }

    @Test
    fun correctPinVerifiesAndWrongDoesNot() = runBlocking {
        val store = store()
        store.setPin("1234")

        assertEquals(PinResult.Success, store.verify("1234"))
        assertEquals(PinResult.Wrong(4), store.verify("0000"))
    }

    @Test
    fun verifyWithoutAPinSaysNotSet() = runBlocking {
        assertEquals(PinResult.NotSet, store().verify("1234"))
    }

    @Test
    fun pinSurvivesARestartAndChangePinReplacesIt() = runBlocking {
        store().setPin("1234")

        val restarted = store() // same backing = a new process
        assertEquals(PinResult.Success, restarted.verify("1234"))

        restarted.setPin("9876")
        assertEquals(PinResult.Wrong(4), restarted.verify("1234"))
        assertEquals(PinResult.Success, restarted.verify("9876"))
    }

    @Test
    fun clearRemovesThePin() = runBlocking {
        val store = store()
        store.setPin("1234")
        store.clear()
        assertFalse(store.isSet())
    }

    @Test
    fun anUndecryptablePinIsTreatedAsNotSet() = runBlocking {
        val store = store()
        store.setPin("1234")
        cipher.failDecrypt = true // the Keystore key was lost
        assertEquals(PinResult.NotSet, store.verify("1234"))
    }

    // ---- lockout -----------------------------------------------------------------------------

    private suspend fun PinStore.failTimes(n: Int): PinResult {
        var last: PinResult = PinResult.NotSet
        repeat(n) { last = verify("0000") }
        return last
    }

    @Test
    fun fiveWrongTriesLockForThirtySeconds() = runBlocking {
        val store = store()
        store.setPin("1234")

        assertEquals(PinResult.Wrong(4), store.verify("0000"))
        assertEquals(PinResult.Wrong(3), store.verify("0000"))
        assertEquals(PinResult.Wrong(2), store.verify("0000"))
        assertEquals(PinResult.Wrong(1), store.verify("0000"))
        assertEquals(PinResult.Locked(30), store.verify("0000"))
        assertEquals(30, store.lockRemainingSeconds())
    }

    @Test
    fun whileLockedEvenTheRightPinIsRefused() = runBlocking {
        val store = store()
        store.setPin("1234")
        store.failTimes(5)

        clock.advance(Duration.ofSeconds(10))
        assertEquals(PinResult.Locked(20), store.verify("1234"))
        clock.advance(Duration.ofSeconds(19))
        assertEquals(PinResult.Locked(1), store.verify("1234"))
    }

    @Test
    fun afterThirtySecondsItWorksAgainAndTheCounterStartsOver() = runBlocking {
        val store = store()
        store.setPin("1234")
        store.failTimes(5)

        clock.advance(Duration.ofSeconds(30))
        assertEquals(0, store.lockRemainingSeconds())
        assertEquals(PinResult.Wrong(4), store.verify("0000")) // a fresh set of 5 tries
        assertEquals(PinResult.Success, store.verify("1234"))
    }

    @Test
    fun aRightPinResetsTheWrongCounter() = runBlocking {
        val store = store()
        store.setPin("1234")
        store.failTimes(4)
        assertEquals(PinResult.Success, store.verify("1234"))

        assertEquals(PinResult.Wrong(4), store.verify("0000"))
    }

    @Test
    fun theLockSurvivesKillingTheApp() = runBlocking {
        store().apply {
            setPin("1234")
            failTimes(5)
        }
        clock.advance(Duration.ofSeconds(12))

        val restarted = store()
        assertEquals(PinResult.Locked(18), restarted.verify("1234"))
    }

    @Test
    fun theWrongCountSurvivesKillingTheApp() = runBlocking {
        store().apply {
            setPin("1234")
            failTimes(3)
        }
        assertEquals(PinResult.Wrong(1), store().verify("0000"))
    }

    @Test
    fun settingTheWallClockBackDoesNotExtendTheLock() = runBlocking {
        val store = store()
        store.setPin("1234")
        store.failTimes(5)

        clock.advance(Duration.ofSeconds(31)) // really 31 seconds later...
        clock.setWallClock(Instant.parse("2026-01-01T00:00:00Z")) // ...but the user winds the clock back months
        assertEquals(PinResult.Wrong(4), store.verify("0000")) // lock is over (monotonic time rules)
    }

    @Test
    fun settingTheWallClockForwardDoesNotSkipTheLock() = runBlocking {
        val store = store()
        store.setPin("1234")
        store.failTimes(5)

        clock.setWallClock(Instant.parse("2027-01-01T00:00:00Z")) // wall clock jumps, monotonic does not move
        assertEquals(PinResult.Locked(30), store.verify("1234"))
    }

    @Test
    fun afterARebootTheWallClockFallbackIsCappedAtOneLockPeriod() = runBlocking {
        val store = store()
        clock.advance(Duration.ofSeconds(100)) // the phone has been up for a while: 10:01:40
        store.setPin("1234")
        store.failTimes(5)

        // Reboot: a new Clock whose monotonic time restarted from 0 while 12 s of wall time passed (10:01:52).
        val rebooted = FakeClock(Instant.parse("2026-11-10T10:01:52Z"), elapsedMs = 0)
        assertEquals(PinResult.Locked(18), store(c = rebooted).verify("1234"))

        // A wall clock set far ahead cannot make a lock longer than 30 s.
        val tampered = FakeClock(Instant.parse("2020-01-01T00:00:00Z"), elapsedMs = 0)
        val result = store(c = tampered).verify("1234") as PinResult.Locked
        assertTrue(result.remainingSeconds <= 30)
        assertNotEquals(0, result.remainingSeconds)
    }

    @Test
    fun changingThePinClearsALock() = runBlocking {
        val store = store()
        store.setPin("1234")
        store.failTimes(5)

        store.setPin("5555")
        assertEquals(PinResult.Success, store.verify("5555"))
    }
}
