package com.bbro.kidtube.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.bbro.kidtube.data.entity.ChildEntity
import com.bbro.kidtube.data.remote.Platform
import com.bbro.kidtube.share.PlanOffer
import com.bbro.kidtube.share.SavedInfo
import com.bbro.kidtube.share.ShareContent
import com.bbro.kidtube.share.ShareLink
import com.bbro.kidtube.share.SharePhase
import com.bbro.kidtube.share.ShareUiState
import com.bbro.kidtube.ui.auth.SignInContent
import com.bbro.kidtube.ui.auth.SignInUiState
import com.bbro.kidtube.ui.theme.KidTubeTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private const val STATUS_BAR_PX = 120
private const val NAV_BAR_PX = 150

/**
 * U3b B1: the app runs edge-to-edge, so every screen must keep its content clear of the status and navigation bars.
 * The system bars are simulated by dispatching window insets to the activity; a title must start below the status bar.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-w411dp-h900dp")
class EdgeToEdgeTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun showWithSystemBars(content: @Composable () -> Unit) {
        compose.activityRule.scenario.onActivity { WindowCompat.setDecorFitsSystemWindows(it.window, false) }
        compose.setContent { KidTubeTheme { content() } }
        compose.runOnUiThread {
            ViewCompat.dispatchApplyWindowInsets(
                compose.activity.window.decorView,
                WindowInsetsCompat.Builder()
                    .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, STATUS_BAR_PX, 0, 0))
                    .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, NAV_BAR_PX))
                    .build(),
            )
        }
        compose.waitForIdle()
    }

    private fun assertBelowStatusBar(text: String) {
        val top = compose.onNodeWithText(text).fetchSemanticsNode().boundsInRoot.top
        assertTrue("'$text' starts at $top px, under the ${STATUS_BAR_PX}px status bar", top >= STATUS_BAR_PX)
    }

    private val ali = ChildEntity("1", "Ali", null, null)
    private val link = ShareLink("https://youtu.be/dQw4w9WgXcQ", "youtube:dQw4w9WgXcQ", Platform.YOUTUBE, null, null)
    private val form = ShareUiState(phase = SharePhase.FORM, link = link, children = listOf(ali), selected = setOf("1"))

    private fun share(state: ShareUiState) = showWithSystemBars {
        ShareContent(state, {}, {}, {}, {}, {}, {}, {}, {})
    }

    @Test
    fun shareSheet_titleIsBelowTheStatusBar() {
        share(form)
        assertBelowStatusBar("Save this video")
    }

    @Test
    fun savedScreen_titleIsBelowTheStatusBar() {
        share(ShareUiState(phase = SharePhase.SAVED, saved = SavedInfo(libraryOnly = false)))
        assertBelowStatusBar("Saved")
    }

    @Test
    fun unsupportedLinkScreen_titleIsBelowTheStatusBar() {
        share(ShareUiState(phase = SharePhase.UNSUPPORTED))
        assertBelowStatusBar("This link is not supported")
    }

    @Test
    fun parentOnlyAndNoChildrenScreens_areBelowTheStatusBar() {
        share(ShareUiState(phase = SharePhase.NOT_PARENT))
        assertBelowStatusBar("Sharing is for parents")
    }

    @Test
    fun planOfferState_isBelowTheStatusBar() {
        share(form.copy(planOffer = PlanOffer(listOf("Pip"))))
        assertBelowStatusBar("Save this video")
    }

    @Test
    fun signIn_control_titleIsBelowTheStatusBar() {
        showWithSystemBars {
            SignInContent(
                SignInUiState(), {}, {}, {}, {}, {}, {}, {}, {},
            )
        }
        assertBelowStatusBar("Parent sign in")
    }
}
