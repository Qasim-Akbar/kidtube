package com.bbro.kidtube.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.bbro.kidtube.ui.children.AddChildContent
import com.bbro.kidtube.ui.children.AddChildUiState
import com.bbro.kidtube.ui.children.AvatarColor
import com.bbro.kidtube.ui.components.PinField
import com.bbro.kidtube.ui.components.PrimaryButton
import com.bbro.kidtube.ui.pin.PinGateContent
import com.bbro.kidtube.ui.pin.PinGateUiState
import com.bbro.kidtube.ui.theme.KidTubeTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private const val EN = "en-w411dp-h2000dp"
private const val UR = "ur-ldrtl-w411dp-h2000dp"

/** U3b fixes that show on screen: colour swatches, plurals, the PIN dots and the in-button spinner. */
@RunWith(RobolectricTestRunner::class)
class U3bFixesRenderTest {
    @get:Rule
    val compose = createComposeRule()

    private fun show(content: @Composable () -> Unit) = compose.setContent { KidTubeTheme { content() } }

    // ---- B2: five equal circles, wrapping on a narrow width ------------------------------------------------------------

    private fun swatchSizes(): List<Pair<Float, Float>> = listOf("Coral", "Yellow", "Sky blue", "Mint", "Lilac").map { name ->
        val bounds = compose.onNodeWithContentDescription(name).fetchSemanticsNode().boundsInRoot
        bounds.width to bounds.height
    }

    @Test
    @Config(qualifiers = EN)
    fun swatches_areFiveEqualCircles_atNormalWidth() {
        show { AddChildContent(AddChildUiState(name = "Ali"), {}, {}, {}, {}) }
        val sizes = swatchSizes()
        assertEquals(1, sizes.distinct().size)
        assertEquals(sizes.first().first, sizes.first().second, 0.5f) // square, so a circle
    }

    @Test
    @Config(qualifiers = EN)
    fun swatches_stayEqual_whenTheDialogIsNarrow_theRowWraps() {
        show { Box(Modifier.width(210.dp)) { AddChildContent(AddChildUiState(name = "Ali"), {}, {}, {}, {}) } }

        val sizes = swatchSizes()
        assertEquals("every swatch keeps the same size, none is squeezed: $sizes", 1, sizes.distinct().size)
        val tops = listOf("Coral", "Lilac").map { compose.onNodeWithContentDescription(it).fetchSemanticsNode().boundsInRoot.top }
        assertTrue("the last swatch wrapped onto a second line", tops[1] > tops[0])
    }

    @Test
    @Config(qualifiers = UR)
    fun swatches_equalInUrduToo() {
        show { Box(Modifier.width(210.dp)) { AddChildContent(AddChildUiState(name = "Ali"), {}, {}, {}, {}) } }
        // the colour names are Urdu now; the five swatches are still the five children of the radio group, all the same size
        val sizes = AvatarColor.entries.map { color -> color to compose.onAllNodes(androidx.compose.ui.test.isSelectable()).fetchSemanticsNodes().size }
        assertEquals(5, sizes.first().second)
    }

    // ---- B5: plurals -----------------------------------------------------------------------------------------------

    private fun gate(state: PinGateUiState) = show { PinGateContent(state, "Title", {}, {}) }

    @Test
    @Config(qualifiers = EN)
    fun triesLeft_oneIsSingular_manyIsPlural() {
        gate(PinGateUiState(attemptsLeft = 1))
        compose.onNodeWithText("Wrong PIN. 1 try left.").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = EN)
    fun triesLeft_many() {
        gate(PinGateUiState(attemptsLeft = 4))
        compose.onNodeWithText("Wrong PIN. 4 tries left.").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = EN)
    fun lockSeconds_oneIsSingular() {
        gate(PinGateUiState(lockedSeconds = 1))
        compose.onNodeWithText("Too many wrong tries. Try again in 1 second.").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = UR)
    fun triesLeft_urdu_oneAndMany() {
        gate(PinGateUiState(attemptsLeft = 1))
        compose.onNodeWithText("پن غلط ہے۔ 1 کوشش باقی ہے۔").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = UR)
    fun triesLeft_urdu_many() {
        gate(PinGateUiState(attemptsLeft = 3))
        compose.onNodeWithText("پن غلط ہے۔ 3 کوششیں باقی ہیں۔").assertIsDisplayed()
    }

    // ---- PIN dots ----------------------------------------------------------------------------------------------------

    @Test
    @Config(qualifiers = UR)
    fun pinField_isLtr_andNeverShowsDigits() {
        var value by mutableStateOf("12")
        var direction: LayoutDirection? = null
        show {
            direction = LocalLayoutDirection.current
            PinField(value = value, onValueChange = { value = it })
        }

        assertEquals(LayoutDirection.Rtl, direction) // the screen is RTL; the dots inside are laid out LTR
        compose.onNodeWithContentDescription("والدین کا پن، 4 ہندسے").assertIsDisplayed()
        listOf("1", "2").forEach { assertEquals("'$it' must not be drawn as text", 0, compose.onAllNodesWithText(it).fetchSemanticsNodes().size) }
    }

    @Test
    @Config(qualifiers = EN)
    fun pinField_takesDigitsOnly_atMostFour() {
        var value by mutableStateOf("")
        show { PinField(value = value, onValueChange = { value = it }) }

        compose.onNodeWithContentDescription("Parent PIN, 4 digits").performTextInput("1a2b3c4d5")

        assertEquals("1234", value)
    }

    // ---- in-button spinner ---------------------------------------------------------------------------------------------

    @Test
    @Config(qualifiers = EN)
    fun loadingButton_keepsItsSize_hidesTheLabel_andCannotBePressed() {
        var loading by mutableStateOf(false)
        show { PrimaryButton(text = "Sign in", onClick = {}, loading = loading) }
        val before = compose.onNode(hasClickAction()).fetchSemanticsNode().layoutInfo.height

        loading = true
        compose.waitForIdle()

        compose.onNodeWithText("Sign in").assertDoesNotExist()
        compose.onNodeWithContentDescription("Loading").assertIsDisplayed()
        compose.onNode(hasClickAction()).assertIsNotEnabled()
        assertEquals("same button height with the spinner as with the label", before, compose.onNode(hasClickAction()).fetchSemanticsNode().layoutInfo.height)
    }
}
