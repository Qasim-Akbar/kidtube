package com.bbro.kidtube.core

import app.cash.turbine.test
import com.bbro.kidtube.testing.InMemoryPreferencesDataStore
import com.bbro.kidtube.testing.SessionHarness
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionStoreTest {
    private val harnesses = mutableListOf<SessionHarness>()

    private fun harness(backing: InMemoryPreferencesDataStore = InMemoryPreferencesDataStore()): SessionHarness =
        SessionHarness(backing).also { harnesses += it }

    @After
    fun tearDown() = harnesses.forEach { it.close() }

    @Test
    fun freshInstallIsUnauthenticated() = runBlocking {
        val store = harness().store
        assertEquals(SessionState.Unauthenticated, store.currentState())
        assertNull(store.currentInfo().deviceRole)
        assertNull(store.accessToken())
    }

    @Test
    fun parentLoginMakesAParentDeviceInParentMode() = runBlocking {
        val store = harness().store
        store.saveParentLogin("access-1", "refresh-1", "fam-1")

        assertEquals(SessionState.ParentDevice(ActiveMode.PARENT), store.currentState())
        val info = store.currentInfo()
        assertEquals(DeviceRole.PARENT, info.deviceRole)
        assertEquals("fam-1", info.familyId)
        assertEquals("access-1", store.accessToken())
        assertEquals("refresh-1", store.refreshToken())
        assertNull(store.deviceToken())
    }

    @Test
    fun activeModeIsPersistedSeparatelyAndEmitted() = runBlocking {
        val store = harness().store
        store.saveParentLogin("a", "r", "fam-1")

        store.sessionState.test {
            assertEquals(SessionState.ParentDevice(ActiveMode.PARENT), awaitItem())
            assertTrue(store.setActiveMode(ActiveMode.KID))
            assertEquals(SessionState.ParentDevice(ActiveMode.KID), awaitItem())
            assertTrue(store.setActiveMode(ActiveMode.PARENT))
            assertEquals(SessionState.ParentDevice(ActiveMode.PARENT), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun activeModeSurvivesProcessDeath() = runBlocking {
        val first = harness()
        first.store.saveParentLogin("access-1", "refresh-1", "fam-1")
        first.store.setActiveMode(ActiveMode.KID)
        first.close()

        // "Process death": a brand-new SessionStore over the same persisted preferences, as after a reboot.
        // (Everything the store knows must come from the DataStore; it keeps no state of its own.)
        val second = harness(first.backing).store
        assertEquals(SessionState.ParentDevice(ActiveMode.KID), second.currentState())
        assertEquals(ActiveMode.KID, second.currentInfo().activeMode)
        assertEquals("access-1", second.accessToken())
    }

    @Test
    fun parentDeviceInKidModeStillHoldsParentTokens() = runBlocking {
        val store = harness().store
        store.saveParentLogin("access-1", "refresh-1", "fam-1")
        store.setActiveMode(ActiveMode.KID)

        // API calls keep using the parent tokens; hiding parent screens is the UI layer's job.
        assertEquals("access-1", store.accessToken())
        assertEquals(DeviceRole.PARENT, store.currentInfo().deviceRole)
    }

    @Test
    fun kidRoleDeviceIsAlwaysKidModeAndIgnoresSetActiveMode() = runBlocking {
        val store = harness().store
        store.saveDevicePairing("device-token-1", "dev-1", "fam-1")
        assertEquals(SessionState.KidDevice, store.currentState())

        assertFalse(store.setActiveMode(ActiveMode.PARENT))
        assertFalse(store.setActiveMode(ActiveMode.KID))

        assertEquals(SessionState.KidDevice, store.currentState())
        assertEquals(ActiveMode.PARENT, store.currentInfo().activeMode) // nothing was written (default, meaningless here)
        assertEquals("device-token-1", store.deviceToken())
        assertEquals("dev-1", store.currentInfo().deviceId)
    }

    @Test
    fun setActiveModeIsIgnoredBeforeAnyRoleIsChosen() = runBlocking {
        val store = harness().store
        assertFalse(store.setActiveMode(ActiveMode.KID))
        assertEquals(SessionState.Unauthenticated, store.currentState())
    }

    @Test
    fun pairingReplacesParentCredentialsAndLoginReplacesDeviceCredentials() = runBlocking {
        val store = harness().store
        store.saveParentLogin("a", "r", "fam-1")
        store.saveDevicePairing("dt", "dev-1", "fam-1")
        assertNull(store.accessToken())
        assertNull(store.refreshToken())
        assertEquals("dt", store.deviceToken())

        store.saveParentLogin("a2", "r2", "fam-1")
        assertNull(store.deviceToken())
        assertEquals("a2", store.accessToken())
        assertEquals(SessionState.ParentDevice(ActiveMode.PARENT), store.currentState())
    }

    @Test
    fun rejectedRefreshTokenMeansNeedsLoginAndLoginClearsIt() = runBlocking {
        val store = harness().store
        store.saveParentLogin("a", "r", "fam-1")
        store.setActiveMode(ActiveMode.KID)

        store.markNeedsLogin()
        assertEquals(SessionState.NeedsLogin, store.currentState())
        assertEquals("fam-1", store.currentInfo().familyId) // nothing is wiped

        store.saveParentLogin("a2", "r2", "fam-1")
        // logging back in keeps the persisted mode: a kid-mode phone does not silently become parent mode
        assertEquals(SessionState.ParentDevice(ActiveMode.KID), store.currentState())
    }

    @Test
    fun revokedDeviceTokenMeansDeviceRevokedUntilRePaired() = runBlocking {
        val store = harness().store
        store.saveDevicePairing("dt", "dev-1", "fam-1")
        store.markDeviceRevoked()
        assertEquals(SessionState.DeviceRevoked, store.currentState())

        store.saveDevicePairing("dt2", "dev-2", "fam-1")
        assertEquals(SessionState.KidDevice, store.currentState())
    }

    @Test
    fun authProblemMarkersOnlyApplyToTheMatchingRole() = runBlocking {
        val kid = harness().store
        kid.saveDevicePairing("dt", "dev-1", "fam-1")
        kid.markNeedsLogin() // a kid device has no login
        assertEquals(SessionState.KidDevice, kid.currentState())
    }

    @Test
    fun parentMarkDeviceRevokedIsIgnored() = runBlocking {
        val store = harness().store
        store.saveParentLogin("a", "r", "fam-1")
        store.markDeviceRevoked()
        assertEquals(SessionState.ParentDevice(ActiveMode.PARENT), store.currentState())
    }

    @Test
    fun logoutClearsSecretsOnly() = runBlocking {
        val store = harness().store
        store.saveParentLogin("a", "r", "fam-1")
        store.setFamilyTimezone("Asia/Karachi")
        store.setActiveMode(ActiveMode.KID)

        store.clearSecrets()

        assertEquals(SessionState.NeedsLogin, store.currentState())
        assertNull(store.accessToken())
        assertNull(store.refreshToken())
        val info = store.currentInfo()
        assertEquals(DeviceRole.PARENT, info.deviceRole)
        assertEquals("fam-1", info.familyId)
        assertEquals("Asia/Karachi", info.familyTimezone)
        assertEquals(ActiveMode.KID, info.activeMode)
    }

    @Test
    fun secretsAreEncryptedAtRest() = runBlocking {
        val h = harness()
        h.store.saveParentLogin("PLAINTEXT-ACCESS-TOKEN", "PLAINTEXT-REFRESH-TOKEN", "fam-1")
        h.store.saveDevicePairing("PLAINTEXT-DEVICE-TOKEN", "dev-1", "fam-1")

        val text = h.storedText()
        assertTrue("something must be persisted", text.isNotEmpty())
        listOf("PLAINTEXT-ACCESS-TOKEN", "PLAINTEXT-REFRESH-TOKEN", "PLAINTEXT-DEVICE-TOKEN").forEach {
            assertFalse("$it must not be stored in clear", text.contains(it))
        }
        assertEquals("PLAINTEXT-DEVICE-TOKEN", h.store.deviceToken()) // and it still round-trips
    }

    @Test
    fun anUndecryptableSecretIsTreatedAsAbsent() = runBlocking {
        val h = harness()
        h.store.saveParentLogin("a", "r", "fam-1")

        h.cipher.failDecrypt = true // Keystore key lost / invalidated
        assertNull(h.store.refreshToken())

        // the unreadable secret was discarded, so the state now says what the user must do
        assertEquals(SessionState.NeedsLogin, h.store.currentState())
    }

    @Test
    fun familyTimezoneIsStored() = runBlocking {
        val store = harness().store
        assertNull(store.currentInfo().familyTimezone)
        store.setFamilyTimezone("Europe/London")
        assertEquals("Europe/London", store.currentInfo().familyTimezone)
    }
}
