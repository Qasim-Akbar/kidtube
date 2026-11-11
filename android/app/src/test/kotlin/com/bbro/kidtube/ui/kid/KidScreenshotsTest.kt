package com.bbro.kidtube.ui.kid

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.graphics.asAndroidBitmap
import com.bbro.kidtube.ui.children.AvatarColor
import com.bbro.kidtube.ui.theme.KidTubeTheme
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Renders the kid screens to PNG files for design review. Skipped unless the environment variable KIDTUBE_SCREENSHOTS names
 * the output directory (qa-screenshots/ is git-ignored), so normal runs write nothing.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
abstract class KidScreenshotsBase(private val tag: String) {
    @get:Rule
    val compose = createComposeRule()

    private val ali = KidChild("c-ali", "Ali", AvatarColor.CORAL)
    private val sara = KidChild("c-sara", "Sara", AvatarColor.SKY)

    private fun shot(name: String, content: @androidx.compose.runtime.Composable () -> Unit) {
        val dir = System.getenv("KIDTUBE_SCREENSHOTS")
        assumeTrue("set KIDTUBE_SCREENSHOTS to render screenshots", dir != null)
        compose.setContent { KidTubeTheme { content() } }
        compose.waitForIdle()
        val file = File(dir, "k2-$name-$tag.png").also { it.parentFile?.mkdirs() }
        file.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun tile(id: String, title: String, viewed: Boolean = false) = KidTileModel(id, title, null, id, viewed)

    @Test fun picker() = shot("picker") {
        KidPickerContent(KidPickerUiState(listOf(ali, sara, KidChild("3", "Omar", AvatarColor.MINT), KidChild("4", "Noor", AvatarColor.LILAC)), "c-sara", canLeaveKidMode = true), {}, {})
    }

    @Test fun gridFree() = shot("grid") {
        KidGridContent(
            KidScreenState.Grid(ali, 3, 2, null, listOf(tile("a", "Counting to ten", viewed = true), tile("b", "Colours with Pip"), tile("c", "Alphabet song"), tile("d", "Animal sounds")), TimeLeft(780, 1800)),
            {}, {},
        )
    }

    @Test fun gridFirst() = shot("grid-first") {
        KidGridContent(KidScreenState.Grid(ali, 3, 3, tile("r", "Counting to ten"), emptyList(), null), {}, {})
    }

    @Test fun done() = shot("done") { KidGridContent(KidScreenState.Status(ali, StatusKind.DONE), {}, {}) }

    @Test fun timeUp() = shot("time-up") { KidGridContent(KidScreenState.Status(ali, StatusKind.TIME_UP), {}, {}) }

    @Test fun dayOff() = shot("day-off") { KidGridContent(KidScreenState.Status(ali, StatusKind.DAY_OFF), {}, {}) }

    private val fakeVideo: @androidx.compose.runtime.Composable (PlayerSurface, androidx.compose.ui.Modifier) -> Unit = { _, m ->
        androidx.compose.foundation.layout.Box(m.fillMaxSize().background(androidx.compose.ui.graphics.Color(0xFF3A5A63)))
    }

    @Test fun playerPlaying() = shot("player") {
        PlayerContent(PlayerUiState.Ready("Counting to ten with Pip", PlayerSurface.YouTube("x"), true, 0.4f), {}, {}, fakeVideo)
    }

    @Test fun playerPaused() = shot("player-paused") {
        PlayerContent(PlayerUiState.Ready("Counting to ten with Pip", PlayerSurface.YouTube("x"), false, 0.4f), {}, {}, fakeVideo)
    }

    @Test fun playerError() = shot("player-error") { PlayerContent(PlayerUiState.Error("Counting to ten with Pip"), {}, {}, fakeVideo) }

    @Test fun nothing() = shot("nothing") { KidGridContent(KidScreenState.Status(sara, StatusKind.NOTHING), {}, {}) }
}

@Config(qualifiers = "en-w411dp-h891dp-xxhdpi")
class KidScreenshotsEnTest : KidScreenshotsBase("en")

@Config(qualifiers = "ur-ldrtl-w411dp-h891dp-xxhdpi")
class KidScreenshotsUrTest : KidScreenshotsBase("ur")
