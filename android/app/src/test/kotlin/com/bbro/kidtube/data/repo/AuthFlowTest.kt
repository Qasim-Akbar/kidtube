package com.bbro.kidtube.data.repo

import com.bbro.kidtube.core.ActiveMode
import com.bbro.kidtube.core.DeviceRole
import com.bbro.kidtube.core.SessionState
import com.bbro.kidtube.data.remote.ApiResult
import com.bbro.kidtube.data.remote.RegisterRequest
import com.bbro.kidtube.testing.AuthRig
import com.bbro.kidtube.testing.ContractFixtures
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sign-in, registration and pairing through the real wiring against a MockWebServer. Status codes, headers and bodies of
 * the answers come from the backend-generated contract fixtures (the two registration-closed answers, 403 and 404, are
 * the only synthetic ones: the test backend always allows registration).
 */
class AuthFlowTest {
    private val rig = AuthRig()
    private val online = { rig.online }

    @After
    fun tearDown() = rig.close()

    private suspend fun login() = rig.auth.login("mum@example.com", "wrong-password")

    private suspend fun register() = rig.auth.register("mum@example.com", "correct-horse-battery")

    private suspend fun pair() = rig.auth.pairDevice("000000", "Tablet")

    // ---- sign in -----------------------------------------------------------------------------

    @Test
    fun successfulLogin_savesTheSession_andMakesThisAParentDevice() = runBlocking {
        rig.respond(ContractFixtures.load(6))

        assertTrue(rig.auth.login("mum@example.com", "correct-horse-battery") is ApiResult.Success)

        assertEquals(SessionState.ParentDevice(ActiveMode.PARENT), rig.session.store.currentState())
        val info = rig.session.store.currentInfo()
        assertEquals(DeviceRole.PARENT, info.deviceRole)
        assertEquals("00000000-0000-4000-8000-000000000002", info.familyId)
        assertEquals("POST", rig.server.takeRequest().method)
    }

    @Test
    fun wrongPassword_401() = runBlocking {
        rig.respond(ContractFixtures.load(5))
        assertEquals(AuthFailure.WrongCredentials, AuthFailures.fromLogin(login(), online))
        assertNull(rig.session.store.currentInfo().deviceRole) // nothing saved
    }

    @Test
    fun rateLimited_carriesRetryAfterSeconds() = runBlocking {
        rig.respond(ContractFixtures.load(84)) // 429, Retry-After: 489
        assertEquals(AuthFailure.RateLimited(489), AuthFailures.fromLogin(login(), online))
    }

    @Test
    fun rateLimitedWithoutRetryAfter_isStillRateLimited() = runBlocking {
        rig.respond(429, """{"detail":"Too many failed login attempts"}""")
        assertEquals(AuthFailure.RateLimited(null), AuthFailures.fromLogin(login(), online))
    }

    @Test
    fun noConnection_whenThePhoneIsOffline() = runBlocking {
        rig.server.close() // connection refused
        rig.online = false
        assertEquals(AuthFailure.NoConnection, AuthFailures.fromLogin(login(), online))
    }

    @Test
    fun serverUnreachable_whenOnlineButNothingAnswers() = runBlocking {
        rig.server.close()
        rig.online = true
        assertEquals(AuthFailure.ServerUnreachable, AuthFailures.fromLogin(login(), online))
    }

    @Test
    fun serverUnreachable_whenTheAddressIsNotAKidTubeServer() = runBlocking {
        rig.respond(404, "<html>not found</html>")
        assertEquals(AuthFailure.ServerUnreachable, AuthFailures.fromLogin(login(), online))
        rig.respond(503, "")
        assertEquals(AuthFailure.ServerUnreachable, AuthFailures.fromLogin(login(), online))
        rig.respond(200, "<html>captive portal</html>")
        assertEquals(AuthFailure.ServerUnreachable, AuthFailures.fromLogin(login(), online))
    }

    @Test
    fun otherServerErrors_areUnknownWithTheirCode() = runBlocking {
        rig.respond(500, """{"detail":"boom"}""")
        assertEquals(AuthFailure.Unknown(500), AuthFailures.fromLogin(login(), online))
    }

    // ---- create account ----------------------------------------------------------------------

    @Test
    fun register_success_returnsTheNewAccount() = runBlocking {
        rig.respond(ContractFixtures.load(2))
        val result = register()
        assertTrue(result is ApiResult.Success)
        assertEquals("mum@example.com", (result as ApiResult.Success).value.email)
        // registering does not sign in by itself
        assertNull(rig.session.store.currentInfo().deviceRole)
    }

    @Test
    fun register_emailTaken_409() = runBlocking {
        rig.respond(ContractFixtures.load(3))
        assertEquals(AuthFailure.EmailTaken, AuthFailures.fromRegister(register(), online))
    }

    @Test
    fun register_validationError_422() = runBlocking {
        rig.respond(ContractFixtures.load(4))
        assertEquals(AuthFailure.InvalidInput, AuthFailures.fromRegister(register(), online))
    }

    @Test
    fun register_403And404_meanRegistrationIsClosed() = runBlocking {
        rig.respond(403, """{"detail":"Registration is disabled"}""")
        assertEquals(AuthFailure.RegistrationClosed, AuthFailures.fromRegister(register(), online))
        rig.respond(404, """{"detail":"Not Found"}""")
        assertEquals(AuthFailure.RegistrationClosed, AuthFailures.fromRegister(register(), online))
    }

    @Test
    fun register_sendsTheFamilyNameAndDefaultsTheRest() = runBlocking {
        rig.respond(ContractFixtures.load(2))
        rig.auth.register("mum@example.com", "correct-horse-battery", "The Khans")
        val body = rig.server.takeRequest().body!!.utf8()
        assertTrue(body, body.contains("\"family_name\":\"The Khans\""))
        assertEquals(RegisterRequest("mum@example.com", "correct-horse-battery", "The Khans").email, "mum@example.com")
    }

    // ---- pairing -----------------------------------------------------------------------------

    @Test
    fun pairing_success_savesTheDeviceToken_andMakesThisAKidDevice() = runBlocking {
        rig.respond(ContractFixtures.load(40))

        assertTrue(rig.auth.pairDevice("100001", "Kids tablet") is ApiResult.Success)

        assertEquals(SessionState.KidDevice, rig.session.store.currentState())
        assertEquals(DeviceRole.KID, rig.session.store.currentInfo().deviceRole)
        assertEquals("kidtube-device-token-placeholder-0000000001", rig.session.store.deviceToken())
        assertNull(rig.session.store.accessToken())
        val request = rig.server.takeRequest()
        assertEquals("/devices/pair", request.url.encodedPath)
        assertNull("pairing is anonymous", request.headers["Authorization"])
    }

    @Test
    fun pairing_wrongOrExpiredCode_400() = runBlocking {
        rig.respond(ContractFixtures.load(46))
        assertEquals(AuthFailure.WrongCode, AuthFailures.fromPair(pair(), online))
        assertNull(rig.session.store.currentInfo().deviceRole)
    }

    @Test
    fun pairing_malformedCode_422_isAlsoAWrongCode() = runBlocking {
        rig.respond(ContractFixtures.load(47))
        assertEquals(AuthFailure.WrongCode, AuthFailures.fromPair(pair(), online))
    }

    @Test
    fun pairing_rateLimited() = runBlocking {
        rig.respond(ContractFixtures.load(83)) // Retry-After: 553
        assertEquals(AuthFailure.RateLimited(553), AuthFailures.fromPair(pair(), online))
    }

    @Test
    fun pairing_noConnection() = runBlocking {
        rig.server.close()
        rig.online = false
        assertEquals(AuthFailure.NoConnection, AuthFailures.fromPair(pair(), online))
    }
}
