package com.bbro.kidtube.ui.plans

import android.graphics.Bitmap
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import com.bbro.kidtube.data.entity.ChildEntity
import com.bbro.kidtube.data.repo.PlanFailure
import com.bbro.kidtube.data.repo.PlanFailureKind
import com.bbro.kidtube.data.repo.SavePart
import com.bbro.kidtube.data.repo.SettingsOutcome
import com.bbro.kidtube.ui.theme.KidTubeTheme
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Renders the Plans screens to PNG for design review. Skipped unless KIDTUBE_SCREENSHOTS names the output directory. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
abstract class PlansScreenshotsBase(private val tag: String) {
    @get:Rule
    val compose = createComposeRule()

    private val children = listOf(ChildEntity("c-ali", "Ali", "coral", 30), ChildEntity("c-sara", "Sara", "sky", null))

    private fun shot(name: String, nodeTag: String? = null, content: @androidx.compose.runtime.Composable () -> Unit) {
        val dir = System.getenv("KIDTUBE_SCREENSHOTS")
        assumeTrue("set KIDTUBE_SCREENSHOTS to render screenshots", dir != null)
        compose.setContent { KidTubeTheme { content() } }
        compose.waitForIdle()
        val file = File(dir, "p1-$name-$tag.png").also { it.parentFile?.mkdirs() }
        val node = if (nodeTag == null) compose.onRoot() else compose.onNode(hasTestTag(nodeTag))
        file.outputStream().use { node.captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun content(model: PlanScreenModel?, busy: Boolean = false, error: PlanFailure? = null, actions: PlansActions = PlansActions()) =
        @androidx.compose.runtime.Composable {
            PlansContent(PlansUiState(true, children, "c-ali", model, busy), error, null, SnackbarHostState(), actions)
        }

    @Test fun tab() = shot("tab", content = content(sampleModel()))

    @Test fun tabNoLimit() = shot("tab-nolimit", content = content(sampleModel(limit = null)))

    @Test fun tabError() = shot("tab-error", content = content(sampleModel(), busy = true, error = PlanFailure(PlanFailureKind.NO_CONNECTION)))

    @Test fun noPlan() = shot("noplan", content = content(sampleModel().copy(plan = null, items = emptyList(), days = emptyList())))

    @Test fun form() = shot("form", "plan_form") {
        PlanSettingsForm(sampleModel().plan!!, 30, saving = false, message = null, onSave = {}, onDismiss = {})
    }

    @Test fun formPartial() = shot("form-partial", "plan_form") {
        val outcome = SettingsOutcome(SavePart.Saved, SavePart.Failed(PlanFailure(PlanFailureKind.NO_CONNECTION)))
        PlanSettingsForm(sampleModel().plan!!, 30, false, SettingsMessage(outcome), {}, {})
    }

    @Test fun itemDialog() = shot("item", "item_dialog") {
        ItemEditDialog(sampleModel().items[2], busy = false, error = null, onSave = { _, _, _ -> }, onDismiss = {})
    }

    @Test fun addVideo() = shot("add", "add_video_dialog") {
        AddVideoDialog(sampleModel().pickable, busy = false, error = null, onPick = {}, onDismiss = {})
    }

    @Test fun removeDialog() = shot("remove") {
        RemoveItemDialog(sampleModel().items[1], "Ali", busy = false, error = null, onConfirm = {}, onDismiss = {})
    }
}

@Config(qualifiers = "en-w411dp-h2400dp-xxhdpi")
class PlansScreenshotsEnTest : PlansScreenshotsBase("en")

@Config(qualifiers = "ur-ldrtl-w411dp-h2400dp-xxhdpi")
class PlansScreenshotsUrTest : PlansScreenshotsBase("ur")
