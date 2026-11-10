package com.bbro.kidtube.ui.setup

import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.LayoutDirection
import com.bbro.kidtube.core.AppLanguage
import com.bbro.kidtube.core.DeviceRole
import com.bbro.kidtube.ui.theme.KidTubeTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Renders the stateless Setup content under the real resource configuration for each language. */
@RunWith(RobolectricTestRunner::class)
class SetupScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private var direction: LayoutDirection? = null
    private var language: String? = null
    private val roles = mutableListOf<DeviceRole>()
    private val languages = mutableListOf<AppLanguage>()

    private fun show(current: AppLanguage) {
        compose.setContent {
            direction = LocalLayoutDirection.current
            language = LocalConfiguration.current.locales[0].language
            KidTubeTheme {
                SetupContent(
                    language = current,
                    onLanguageSelected = { languages += it },
                    onRoleSelected = { roles += it },
                )
            }
        }
    }

    @Test
    @Config(qualifiers = "en")
    fun english_rendersLeftToRight() {
        show(AppLanguage.ENGLISH)

        assertEquals("en", language)
        assertEquals(LayoutDirection.Ltr, direction)
        compose.onNodeWithText("Whose phone is this?").assertIsDisplayed()
        compose.onNodeWithText("Parent phone").assertIsDisplayed()
        compose.onNodeWithText("Kid's phone").assertIsDisplayed()
        compose.onNodeWithText("English").assertIsDisplayed()
        compose.onNodeWithText("اردو").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "ur-ldrtl")
    fun urdu_rendersRightToLeft() {
        show(AppLanguage.URDU)

        assertEquals("ur", language)
        assertEquals(LayoutDirection.Rtl, direction)
        compose.onNodeWithText("یہ فون کس کا ہے؟").assertIsDisplayed()
        compose.onNodeWithText("والدین کا فون").assertIsDisplayed()
        compose.onNodeWithText("بچے کا فون").assertIsDisplayed()
        compose.onNodeWithContentDescription("کِڈ ٹیوب کا لوگو").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "en")
    fun tappingTheCardsChoosesTheRole() {
        show(AppLanguage.ENGLISH)

        compose.onNodeWithText("Parent phone").performClick()
        compose.onNodeWithText("Kid's phone").performClick()

        assertEquals(listOf(DeviceRole.PARENT, DeviceRole.KID), roles)
    }

    @Test
    @Config(qualifiers = "en")
    fun tappingTheOtherLanguageReportsIt() {
        show(AppLanguage.ENGLISH)

        compose.onNodeWithText("اردو").performClick()

        assertEquals(listOf(AppLanguage.URDU), languages)
    }
}
