package com.bbro.kidtube.ui.kid

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import com.bbro.kidtube.R
import com.bbro.kidtube.ui.theme.KidTubeTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

private const val EN = "en-w411dp-h2000dp"
private const val UR = "ur-ldrtl-w411dp-h2000dp"

/** The player screen and the "cannot play" tile under the real en and ur (RTL) resource configurations. */
@RunWith(RobolectricTestRunner::class)
abstract class KidPlayerRenderBase(private val lang: String, private val dir: LayoutDirection) {
    @get:Rule
    val compose = createComposeRule()

    private var seenDirection: LayoutDirection? = null
    private var seenLanguage: String? = null

    private fun str(id: Int) = RuntimeEnvironment.getApplication().getString(id)

    private fun SemanticsNodeInteraction.assertAbsent() = assertDoesNotExist()

    private val standIn: @Composable (PlayerSurface, Modifier) -> Unit = { _, modifier -> Box(modifier.fillMaxSize().testTag("video_surface")) }

    private fun show(state: PlayerUiState, onToggle: () -> Unit = {}, onAll: () -> Unit = {}) {
        compose.setContent {
            seenDirection = LocalLayoutDirection.current
            seenLanguage = LocalConfiguration.current.locales[0].language
            KidTubeTheme { PlayerContent(state, onToggle, onAll, standIn) }
        }
        compose.waitForIdle()
        assertEquals(lang, seenLanguage)
        assertEquals(dir, seenDirection)
    }

    private fun youTube(playing: Boolean, progress: Float = 0.4f, title: String? = "Counting to ten") =
        PlayerUiState.Ready(title, PlayerSurface.YouTube("abc"), playing, progress)

    @Test
    fun playerHasTheVideoAreaTitlePlayButtonAndAllVideos() {
        var toggled = 0
        var left = 0
        show(youTube(playing = false), onToggle = { toggled++ }, onAll = { left++ })
        compose.onNodeWithTag("video_surface").assertIsDisplayed()
        compose.onNodeWithText("Counting to ten").assertIsDisplayed()
        compose.onNodeWithContentDescription(str(R.string.kid_player_play)).performClick()
        compose.onNodeWithText(str(R.string.kid_player_all_videos)).performClick()
        assertEquals(1, toggled)
        assertEquals(1, left)
    }

    @Test
    fun theVideoAreaIsSixteenByNine() {
        show(youTube(playing = true))
        val b = compose.onNodeWithTag("video_surface").getBoundsInRoot()
        assertEquals(16f / 9f, b.width.value / b.height.value, 0.02f)
    }

    @Test
    fun theButtonShowsPauseWhilePlayingAndPlayWhenPaused() {
        show(youTube(playing = true))
        compose.onNodeWithContentDescription(str(R.string.kid_player_pause)).assertIsDisplayed()
        compose.onNodeWithContentDescription(str(R.string.kid_player_play)).assertAbsent()
    }

    @Test
    fun noSeekBarFullscreenOrLinks() {
        show(youTube(playing = true))
        // Exactly two things can be pressed: the Play/Pause button and "All videos". (The progress line is not a control.)
        val clickable = compose.onAllNodes(androidx.compose.ui.test.hasClickAction()).fetchSemanticsNodes()
        assertEquals(2, clickable.size)
        assertEquals(0, compose.onAllNodes(androidx.compose.ui.test.hasSetTextAction()).fetchSemanticsNodes().size)
    }

    @Test
    fun facebookHasNoPlayPauseButtonOfOurs() {
        show(PlayerUiState.Ready("Facebook video", PlayerSurface.Facebook("https://www.facebook.com/plugins/video.php"), true, 0f))
        compose.onNodeWithTag("video_surface").assertIsDisplayed()
        compose.onNodeWithContentDescription(str(R.string.kid_player_pause)).assertAbsent()
        compose.onNodeWithText(str(R.string.kid_player_all_videos)).assertIsDisplayed()
    }

    @Test
    fun theErrorTileIsFriendlyAndOffersTheWayBack() {
        var left = 0
        show(PlayerUiState.Error("Counting to ten"), onAll = { left++ })
        compose.onNodeWithText(str(R.string.kid_player_cannot_play)).assertIsDisplayed()
        compose.onNodeWithTag("video_surface").assertAbsent() // no broken player behind it
        compose.onNodeWithContentDescription(str(R.string.kid_player_play)).assertAbsent()
        compose.onNodeWithText(str(R.string.kid_player_all_videos)).performClick()
        assertEquals(1, left)
    }

    @Test
    fun loadingShowsOnlyTheBackground() {
        show(PlayerUiState.Loading)
        compose.onNodeWithText(str(R.string.kid_player_all_videos)).assertAbsent()
    }

    @Test
    fun urduStringsAreThereForThePlayer() {
        if (lang != "ur") return
        assertEquals("تمام ویڈیوز", str(R.string.kid_player_all_videos))
        assertEquals("یہ ویڈیو نہیں چل سکتی", str(R.string.kid_player_cannot_play))
    }
}

@Config(qualifiers = EN)
class KidPlayerRenderEnTest : KidPlayerRenderBase("en", LayoutDirection.Ltr)

@Config(qualifiers = UR)
class KidPlayerRenderUrTest : KidPlayerRenderBase("ur", LayoutDirection.Rtl)
