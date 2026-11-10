package com.bbro.kidtube.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.LayoutDirection
import com.bbro.kidtube.core.ServerAddressError
import com.bbro.kidtube.data.repo.AuthFailure
import com.bbro.kidtube.ui.auth.PairingContent
import com.bbro.kidtube.ui.auth.PairingUiState
import com.bbro.kidtube.ui.auth.ServerAddressUiState
import com.bbro.kidtube.ui.auth.SignInContent
import com.bbro.kidtube.ui.auth.SignInUiState
import com.bbro.kidtube.ui.pin.CreatePinContent
import com.bbro.kidtube.ui.pin.CreatePinError
import com.bbro.kidtube.ui.pin.CreatePinUiState
import com.bbro.kidtube.ui.pin.PinGateContent
import com.bbro.kidtube.ui.pin.PinGateUiState
import com.bbro.kidtube.ui.theme.KidTubeTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private const val EN = "en-w411dp-h2000dp"
private const val UR = "ur-ldrtl-w411dp-h2000dp"

/** The sign-in, pairing, create-PIN and PIN-gate screens under the real en and ur (RTL) resource configurations. */
@RunWith(RobolectricTestRunner::class)
class NewScreensRenderTest {
    @get:Rule
    val compose = createComposeRule()

    private var direction: LayoutDirection? = null
    private var language: String? = null

    private fun show(content: @Composable () -> Unit) {
        compose.setContent {
            direction = LocalLayoutDirection.current
            language = LocalConfiguration.current.locales[0].language
            KidTubeTheme { content() }
        }
    }

    private fun assertLocale(lang: String, dir: LayoutDirection) {
        assertEquals(lang, language)
        assertEquals(dir, direction)
    }

    private val noop: () -> Unit = {}
    private val noText: (String) -> Unit = {}

    private fun signIn(state: SignInUiState = SignInUiState(), onSetCreate: (Boolean) -> Unit = {}, onToggle: () -> Unit = {}) =
        show {
            SignInContent(
                state = state, onEmailChange = noText, onPasswordChange = noText, onFamilyNameChange = noText,
                onServerTextChange = noText, onToggleAdvanced = onToggle, onSetCreateMode = onSetCreate,
                onSubmit = noop, onBack = noop,
            )
        }

    private fun pairing(state: PairingUiState = PairingUiState()) =
        show { PairingContent(state, noText, noText, noop, noop, noop) }

    private fun createPin(state: CreatePinUiState = CreatePinUiState(), isChange: Boolean = false) =
        show { CreatePinContent(state, isChange, noText, noText, noop, noop) }

    private fun pinGate(state: PinGateUiState = PinGateUiState()) =
        show { PinGateContent(state, title = stringTitle(), onPinChange = noText, onCancel = noop) }

    @Composable
    private fun stringTitle() = androidx.compose.ui.res.stringResource(com.bbro.kidtube.R.string.pin_gate_title)

    // ---- sign in ------------------------------------------------------------------------------

    @Test
    @Config(qualifiers = EN)
    fun signIn_en() {
        signIn(SignInUiState(error = AuthFailure.RateLimited(489)))

        assertLocale("en", LayoutDirection.Ltr)
        compose.onNodeWithText("Parent sign in").assertIsDisplayed()
        compose.onNodeWithText("Email").assertIsDisplayed()
        compose.onNodeWithText("Password").assertIsDisplayed()
        compose.onNodeWithText("Sign in").assertIsDisplayed()
        compose.onNodeWithText("Create account").assertIsDisplayed()
        compose.onNodeWithText("Advanced").assertIsDisplayed()
        compose.onNodeWithText("Too many tries. Try again in 489 seconds.").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = UR)
    fun signIn_ur_rtl() {
        signIn(SignInUiState(error = AuthFailure.RateLimited(489)))

        assertLocale("ur", LayoutDirection.Rtl)
        compose.onNodeWithText("والدین کا سائن اِن").assertIsDisplayed()
        compose.onNodeWithText("ای میل").assertIsDisplayed()
        compose.onNodeWithText("پاس ورڈ").assertIsDisplayed()
        compose.onNodeWithText("سائن اِن").assertIsDisplayed()
        compose.onNodeWithText("مزید اختیارات").assertIsDisplayed()
        compose.onNodeWithText("بہت زیادہ کوششیں۔ 489 سیکنڈ بعد دوبارہ کوشش کریں۔").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = EN)
    fun signIn_serverAddressIsCollapsedUnderAdvanced() {
        var expanded = false
        signIn(onToggle = { expanded = true })

        compose.onNodeWithText("Server address").assertDoesNotExist()
        compose.onNodeWithText("Advanced").performClick()
        assertEquals(true, expanded)
    }

    @Test
    @Config(qualifiers = EN)
    fun signIn_expandedShowsTheAddressAndItsError() {
        signIn(SignInUiState(server = ServerAddressUiState(text = "kidtube.example.com", expanded = true, error = ServerAddressError.SCHEME)))

        compose.onNodeWithText("Server address").assertIsDisplayed()
        compose.onNodeWithText("The address must start with https://").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = EN)
    fun signIn_registrationClosed_hidesCreateAccountAndShowsAPlainMessage() {
        signIn(SignInUiState(registrationAvailable = false, error = AuthFailure.RegistrationClosed))

        compose.onNodeWithText("Create account").assertDoesNotExist()
        compose.onNodeWithText(
            "New accounts are turned off on this server. Sign in with an existing account, or ask the person who runs the server.",
        ).assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = EN)
    fun signIn_createModeShowsFamilyName_andSwitchesBack() {
        var requested: Boolean? = null
        signIn(SignInUiState(createMode = true), onSetCreate = { requested = it })

        compose.onNodeWithText("Family name (optional)").assertIsDisplayed()
        compose.onNodeWithText("I already have an account").performClick()
        assertEquals(false, requested)
    }

    // ---- pairing ------------------------------------------------------------------------------

    @Test
    @Config(qualifiers = EN)
    fun pairing_en() {
        pairing(PairingUiState(code = "123", error = AuthFailure.WrongCode))

        assertLocale("en", LayoutDirection.Ltr)
        compose.onNodeWithText("Connect this phone").assertIsDisplayed()
        compose.onNodeWithText("6-digit code").assertIsDisplayed()
        compose.onNodeWithText("Connect").assertIsNotEnabled()
        compose.onNodeWithText("That code is wrong or has expired. Ask a parent for a new one.").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = UR)
    fun pairing_ur_rtl_withASixDigitCode_enablesConnect() {
        pairing(PairingUiState(code = "123456"))

        assertLocale("ur", LayoutDirection.Rtl)
        compose.onNodeWithText("اس فون کو جوڑیں").assertIsDisplayed()
        compose.onNodeWithText("6 ہندسوں کا کوڈ").assertIsDisplayed()
        compose.onNodeWithText("جوڑیں").assertIsEnabled()
        compose.onNodeWithText("1").assertIsDisplayed() // digits stay Latin
    }

    // ---- create PIN ---------------------------------------------------------------------------

    @Test
    @Config(qualifiers = EN)
    fun createPin_en() {
        createPin(CreatePinUiState(error = CreatePinError.MISMATCH))

        assertLocale("en", LayoutDirection.Ltr)
        compose.onNodeWithText("Create a 4-digit PIN").assertIsDisplayed()
        compose.onNodeWithText("Parent PIN").assertIsDisplayed()
        compose.onNodeWithText("Confirm PIN").assertIsDisplayed()
        compose.onNodeWithText("Save PIN").assertIsDisplayed()
        compose.onNodeWithText("The two PINs do not match.").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = UR)
    fun createPin_ur_rtl() {
        createPin(CreatePinUiState(error = CreatePinError.MISMATCH))

        assertLocale("ur", LayoutDirection.Rtl)
        compose.onNodeWithText("4 ہندسوں کا پن بنائیں").assertIsDisplayed()
        compose.onNodeWithText("پن کی تصدیق کریں").assertIsDisplayed()
        compose.onNodeWithText("پن محفوظ کریں").assertIsDisplayed()
        compose.onNodeWithText("دونوں پن ایک جیسے نہیں ہیں۔").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = EN)
    fun changePin_usesTheChangeTitle() {
        createPin(isChange = true)
        compose.onNodeWithText("Choose a new PIN").assertIsDisplayed()
    }

    // ---- PIN gate -----------------------------------------------------------------------------

    @Test
    @Config(qualifiers = EN)
    fun pinGate_en_showsTriesLeft_thenTheLock() {
        pinGate(PinGateUiState(attemptsLeft = 3))
        assertLocale("en", LayoutDirection.Ltr)
        compose.onNodeWithText("Enter parent PIN").assertIsDisplayed()
        compose.onNodeWithText("Wrong PIN. 3 tries left.").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = EN)
    fun pinGate_en_locked() {
        pinGate(PinGateUiState(lockedSeconds = 27))
        compose.onNodeWithText("Too many wrong tries. Try again in 27 seconds.").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = UR)
    fun pinGate_ur_rtl() {
        pinGate(PinGateUiState(attemptsLeft = 3))
        assertLocale("ur", LayoutDirection.Rtl)
        compose.onNodeWithText("والدین کا پن درج کریں").assertIsDisplayed()
        compose.onNodeWithText("پن غلط ہے۔ 3 کوششیں باقی ہیں۔").assertIsDisplayed()
        compose.onNodeWithText("منسوخ کریں").assertIsDisplayed()
    }
}
