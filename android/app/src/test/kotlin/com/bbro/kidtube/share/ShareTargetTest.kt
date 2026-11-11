package com.bbro.kidtube.share

import com.bbro.kidtube.core.DeviceRole
import com.bbro.kidtube.testing.SessionHarness
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareTargetTest {
    @Test
    fun onlyAParentRoleOffersTheShareTarget() {
        assertTrue(ShareTargetPolicy.enabledFor(DeviceRole.PARENT))
        assertFalse(ShareTargetPolicy.enabledFor(DeviceRole.KID))
        assertFalse(ShareTargetPolicy.enabledFor(null)) // not set up yet
    }

    /** Runs the production [ShareTargetManager] against a real SessionStore and records what it tells the system. */
    private fun withManager(block: suspend (SessionHarness, MutableStateFlow<Boolean?>, List<Boolean>) -> Unit) = runBlocking {
        val session = SessionHarness()
        val latest = MutableStateFlow<Boolean?>(null)
        val history = mutableListOf<Boolean>()
        val scope = CoroutineScope(Dispatchers.Unconfined)
        scope.launch {
            ShareTargetManager(session.store) { enabled ->
                history += enabled
                latest.value = enabled
            }.keepInSync()
        }
        try {
            block(session, latest, history)
        } finally {
            scope.cancel()
        }
    }

    private suspend fun MutableStateFlow<Boolean?>.await(expected: Boolean) = withTimeout(10_000) { first { it == expected } }

    @Test
    fun aFreshInstallHasNoShareTarget() = withManager { _, latest, _ ->
        assertEquals(false, latest.await(false))
    }

    @Test
    fun signingInAsAParent_enablesIt() = withManager { session, latest, _ ->
        session.store.saveParentLogin("access", "refresh", "fam-1")
        assertEquals(true, latest.await(true))
    }

    @Test
    fun aKidOnlyDevice_neverHasIt() = withManager { session, latest, history ->
        session.store.saveDevicePairing("device-token", "dev-1", "fam-1")
        latest.await(false)
        assertFalse("a kid device must never be told to enable the share target", history.any { it })
    }

    @Test
    fun ifTheRoleEverChangesAwayFromParent_itIsSwitchedOffAgain() = withManager { session, latest, _ ->
        session.store.saveParentLogin("access", "refresh", "fam-1")
        latest.await(true)
        session.store.saveDevicePairing("device-token", "dev-1", "fam-1")
        assertEquals(false, latest.await(false))
    }
}
