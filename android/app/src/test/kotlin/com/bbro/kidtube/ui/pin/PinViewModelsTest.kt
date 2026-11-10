package com.bbro.kidtube.ui.pin

import com.bbro.kidtube.core.FakeClock
import com.bbro.kidtube.core.PinHasher
import com.bbro.kidtube.core.PinResult
import com.bbro.kidtube.core.PinStore
import com.bbro.kidtube.testing.FakeSecretCipher
import com.bbro.kidtube.testing.InMemoryPreferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.security.SecureRandom
import java.time.Duration

@OptIn(ExperimentalCoroutinesApi::class)
class PinViewModelsTest {
    private val clock = FakeClock()
    private val store = PinStore(InMemoryPreferencesDataStore(), FakeSecretCipher(), clock, PinHasher(SecureRandom(), 1_000))

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private suspend fun PinGateViewModel.until(predicate: (PinGateUiState) -> Boolean) =
        withTimeout(10_000) { state.first { predicate(it) } }

    // ---- PinGate ------------------------------------------------------------------------------

    @Test
    fun gate_rightPin_isVerified() = runBlocking {
        store.setPin("1234")
        val gate = PinGateViewModel(store)

        gate.onPinChange("1234")

        assertTrue(gate.until { it.verified }.verified)
    }

    @Test
    fun gate_wrongPin_showsTriesLeft_andClearsTheField() = runBlocking {
        store.setPin("1234")
        val gate = PinGateViewModel(store)

        gate.onPinChange("0000")
        val state = gate.until { it.attemptsLeft != null }

        assertEquals(4, state.attemptsLeft)
        assertEquals("", state.pin)
        assertFalse(state.verified)
    }

    @Test
    fun gate_onlyChecksOnceFourDigitsAreIn() = runBlocking {
        store.setPin("1234")
        val gate = PinGateViewModel(store)

        gate.onPinChange("12")
        assertEquals("12", gate.state.value.pin)
        assertFalse(gate.state.value.checking)
        assertEquals(PinResult.Success, store.verify("1234")) // untouched: the partial entry did not count as a try
    }

    @Test
    fun gate_fiveWrongTries_lock_thenUnlockAfterThirtySeconds() = runBlocking {
        store.setPin("1234")
        val gate = PinGateViewModel(store)

        repeat(5) {
            gate.onPinChange("0000")
            gate.until { !it.checking && it.pin.isEmpty() && (it.attemptsLeft != null || it.locked) }
        }
        assertTrue(gate.state.value.locked)
        assertEquals(30, gate.state.value.lockedSeconds)

        // while locked, typing does nothing (not even the right PIN)
        gate.onPinChange("1234")
        assertFalse(gate.state.value.verified)

        clock.advance(Duration.ofSeconds(31))
        gate.refreshLock()
        assertFalse(gate.until { !it.locked }.locked)

        gate.onPinChange("1234")
        assertTrue(gate.until { it.verified }.verified)
    }

    @Test
    fun gate_resetPicksUpAnExistingLock() = runBlocking {
        store.setPin("1234")
        repeat(5) { store.verify("0000") }
        val gate = PinGateViewModel(store)

        gate.reset()

        assertTrue(gate.until { it.locked }.locked)
    }

    // ---- create / change PIN ------------------------------------------------------------------

    @Test
    fun create_needsFourDigits() = runBlocking {
        val vm = CreatePinViewModel(store)
        vm.onPinChange("12")
        vm.onConfirmChange("12")

        vm.save()

        assertEquals(CreatePinError.TOO_SHORT, vm.state.value.error)
        assertFalse(store.isSet())
    }

    @Test
    fun create_mismatchIsRejected() = runBlocking {
        val vm = CreatePinViewModel(store)
        vm.onPinChange("1234")
        vm.onConfirmChange("1235")

        vm.save()

        assertEquals(CreatePinError.MISMATCH, vm.state.value.error)
        assertFalse(store.isSet())
    }

    @Test
    fun create_savesTheHash_andAnnouncesIt() = runBlocking {
        val vm = CreatePinViewModel(store)
        vm.onPinChange("1a2b3c4d5") // letters dropped, capped at 4 digits
        vm.onConfirmChange("1234")
        assertEquals("1234", vm.state.value.pin)

        vm.save()
        withTimeout(10_000) { vm.saved.first() }

        assertEquals(PinResult.Success, store.verify("1234"))
        assertEquals("", vm.state.value.pin) // not kept in memory after saving
    }

    @Test
    fun change_replacesTheOldPin() = runBlocking {
        store.setPin("1234")
        val vm = CreatePinViewModel(store)
        vm.onPinChange("4321")
        vm.onConfirmChange("4321")

        vm.save()
        withTimeout(10_000) { vm.saved.first() }

        assertEquals(PinResult.Wrong(4), store.verify("1234"))
        assertEquals(PinResult.Success, store.verify("4321"))
    }
}
