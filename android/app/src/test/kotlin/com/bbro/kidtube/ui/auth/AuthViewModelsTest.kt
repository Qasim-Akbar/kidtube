package com.bbro.kidtube.ui.auth

import com.bbro.kidtube.core.ActiveMode
import com.bbro.kidtube.core.DeviceNameSource
import com.bbro.kidtube.core.DeviceRole
import com.bbro.kidtube.core.ServerAddressError
import com.bbro.kidtube.core.SessionState
import com.bbro.kidtube.data.repo.AuthFailure
import com.bbro.kidtube.testing.AuthRig
import com.bbro.kidtube.testing.ContractFixtures
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The sign-in and pairing view models over the real auth stack (MockWebServer + backend contract fixtures). */
@OptIn(ExperimentalCoroutinesApi::class)
class AuthViewModelsTest {
    private lateinit var rig: AuthRig

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        rig = AuthRig()
    }

    @After
    fun tearDown() {
        rig.close()
        Dispatchers.resetMain()
    }

    private fun signIn() = SignInViewModel(rig.auth, rig.serverAddress, rig.pinStore, rig.connectivity)

    private fun pairing() = PairingViewModel(rig.auth, rig.serverAddress, rig.connectivity, DeviceNameSource { "Test phone" })

    private suspend fun SignInViewModel.idle() = withTimeout(10_000) { state.first { !it.busy } }

    private suspend fun PairingViewModel.idle() = withTimeout(10_000) { state.first { !it.busy } }

    private fun SignInViewModel.fill() {
        onEmailChange("mum@example.com")
        onPasswordChange("correct-horse-battery")
    }

    // ---- sign in -----------------------------------------------------------------------------

    @Test
    fun signIn_success_emitsSignedIn_withoutPinYet_andTheDeviceIsAParentDevice() = runBlocking {
        rig.respond(ContractFixtures.load(6))
        val vm = signIn().apply { fill() }

        vm.submit()
        val event = withTimeout(10_000) { vm.events.first() }

        assertEquals(SignInEvent.SignedIn(hasPin = false), event)
        assertEquals(DeviceRole.PARENT, rig.session.store.currentInfo().deviceRole)
        assertEquals(SessionState.ParentDevice(ActiveMode.PARENT), rig.session.store.currentState())
    }

    @Test
    fun signIn_whenAPinAlreadyExists_saysSo() = runBlocking {
        rig.pinStore.setPin("1234")
        rig.respond(ContractFixtures.load(6))
        val vm = signIn().apply { fill() }

        vm.submit()

        assertEquals(SignInEvent.SignedIn(hasPin = true), withTimeout(10_000) { vm.events.first() })
    }

    @Test
    fun signIn_emptyFields_sendNothing() = runBlocking {
        val vm = signIn()
        vm.onEmailChange("mum@example.com") // no password

        vm.submit()

        assertTrue(vm.state.value.fieldsMissing)
        assertEquals(0, rig.server.requestCount)
    }

    @Test
    fun signIn_wrongPassword_showsTheError_andTypingClearsIt() = runBlocking {
        rig.respond(ContractFixtures.load(5))
        val vm = signIn().apply { fill() }

        vm.submit()
        val state = vm.idle()

        assertEquals(AuthFailure.WrongCredentials, state.error)
        assertNull(rig.session.store.currentInfo().deviceRole)
        vm.onPasswordChange("another")
        assertNull(vm.state.value.error)
    }

    @Test
    fun signIn_rateLimited_showsRetryAfterSeconds() = runBlocking {
        rig.respond(ContractFixtures.load(84))
        val vm = signIn().apply { fill() }

        vm.submit()

        assertEquals(AuthFailure.RateLimited(489), vm.idle().error)
    }

    @Test
    fun signIn_offlineAndServerDown_areDifferentErrors() = runBlocking {
        rig.server.close()
        rig.online = false
        val vm = signIn().apply { fill() }
        vm.submit()
        assertEquals(AuthFailure.NoConnection, vm.idle().error)

        rig.online = true
        vm.submit()
        assertEquals(AuthFailure.ServerUnreachable, vm.idle().error)
    }

    @Test
    fun signIn_aBadServerAddress_isNotSent_andOpensAdvanced() = runBlocking {
        val strict = AuthRig(allowCleartext = false) // release rules: https only
        try {
            val vm = SignInViewModel(strict.auth, strict.serverAddress, strict.pinStore, strict.connectivity)
            vm.onEmailChange("mum@example.com")
            vm.onPasswordChange("correct-horse-battery")
            vm.onServerTextChange("http://insecure.example.com")

            vm.submit()
            val state = vm.idle()

            assertEquals(ServerAddressError.SCHEME, state.server.error)
            assertTrue(state.server.expanded)
            assertEquals(0, strict.server.requestCount)
        } finally {
            strict.close()
        }
    }

    @Test
    fun signIn_theTypedServerAddressIsSavedBeforeTheCall() = runBlocking {
        rig.respond(ContractFixtures.load(6))
        val vm = signIn().apply { fill() }
        vm.onServerTextChange(rig.server.url("/").toString().trimEnd('/')) // no trailing slash typed

        vm.submit()
        withTimeout(10_000) { vm.events.first() }

        assertEquals(rig.server.url("/").toString(), rig.serverAddress.current())
    }

    @Test
    fun createAccount_registersThenSignsIn() = runBlocking {
        rig.respond(ContractFixtures.load(2))
        rig.respond(ContractFixtures.load(6))
        val vm = signIn().apply {
            fill()
            onSetCreateMode(true)
            onFamilyNameChange("The Khans")
        }

        vm.submit()
        withTimeout(10_000) { vm.events.first() }

        assertEquals("/auth/register", rig.server.takeRequest().url.encodedPath)
        assertEquals("/auth/login", rig.server.takeRequest().url.encodedPath)
    }

    @Test
    fun createAccount_emailTaken() = runBlocking {
        rig.respond(ContractFixtures.load(3))
        val vm = signIn().apply {
            fill()
            onSetCreateMode(true)
        }

        vm.submit()
        val state = vm.idle()

        assertEquals(AuthFailure.EmailTaken, state.error)
        assertTrue("sign-ups still available", state.registrationAvailable)
        assertEquals(1, rig.server.requestCount)
    }

    @Test
    fun createAccount_serverWithoutSignUps_hidesTheOptionForGood() = runBlocking {
        rig.respond(403, """{"detail":"Registration is disabled"}""")
        val vm = signIn().apply {
            fill()
            onSetCreateMode(true)
        }

        vm.submit()
        val state = vm.idle()

        assertFalse(state.registrationAvailable)
        assertFalse("back to plain sign-in", state.createMode)
        assertEquals(AuthFailure.RegistrationClosed, state.error)
        vm.onSetCreateMode(true)
        assertFalse("cannot be switched on again", vm.state.value.createMode)
    }

    // ---- pairing -----------------------------------------------------------------------------

    @Test
    fun pairing_codeIsDigitsOnly_sixAtMost_andConnectNeedsAllSix() {
        val vm = pairing()
        vm.onCodeChange("12a3-45 678")
        assertEquals("123456", vm.state.value.code)
        assertTrue(vm.state.value.canConnect)
        vm.onCodeChange("123")
        assertFalse(vm.state.value.canConnect)
    }

    @Test
    fun pairing_shortCode_sendsNothing() = runBlocking {
        val vm = pairing()
        vm.onCodeChange("123")
        vm.connect()
        assertEquals(0, rig.server.requestCount)
    }

    @Test
    fun pairing_success_emitsPaired_andTheDeviceIsAKidDevice() = runBlocking {
        rig.respond(ContractFixtures.load(40))
        val vm = pairing()
        vm.onCodeChange("100001")

        vm.connect()
        val event = withTimeout(10_000) { vm.events.first() }

        assertEquals(PairingEvent.Paired, event)
        assertEquals(DeviceRole.KID, rig.session.store.currentInfo().deviceRole)
        assertEquals(SessionState.KidDevice, rig.session.store.currentState())
        val body = rig.server.takeRequest().body!!.utf8()
        assertTrue(body, body.contains("\"code\":\"100001\"") && body.contains("\"device_name\":\"Test phone\""))
    }

    @Test
    fun pairing_wrongCode_showsTheError_andNothingIsSaved() = runBlocking {
        rig.respond(ContractFixtures.load(46))
        val vm = pairing()
        vm.onCodeChange("000000")

        vm.connect()

        assertEquals(AuthFailure.WrongCode, vm.idle().error)
        assertNull(rig.session.store.currentInfo().deviceRole)
    }

    @Test
    fun pairing_rateLimited_andNoConnection() = runBlocking {
        rig.respond(ContractFixtures.load(83))
        val vm = pairing()
        vm.onCodeChange("100003")
        vm.connect()
        assertEquals(AuthFailure.RateLimited(553), vm.idle().error)

        rig.server.close()
        rig.online = false
        vm.connect()
        assertEquals(AuthFailure.NoConnection, vm.idle().error)
    }

    @Test
    fun pairing_aBadServerAddress_isNotSent() = runBlocking {
        val strict = AuthRig(allowCleartext = false)
        try {
            val vm = PairingViewModel(strict.auth, strict.serverAddress, strict.connectivity, DeviceNameSource { "x" })
            vm.onCodeChange("123456")
            vm.onServerTextChange("kidtube.example.com")

            vm.connect()
            val state = vm.idle()

            assertEquals(ServerAddressError.SCHEME, state.server.error)
            assertEquals(0, strict.server.requestCount)
        } finally {
            strict.close()
        }
    }
}
