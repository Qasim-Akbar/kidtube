package com.bbro.kidtube.ui.kid

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.bbro.kidtube.R
import com.bbro.kidtube.ui.children.AvatarColor
import com.bbro.kidtube.ui.components.DOT_EMPTY_TAG
import com.bbro.kidtube.ui.components.DOT_FILLED_TAG
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

/** The kid picker, grid and status screens under the real en and ur (RTL) resource configurations. */
@RunWith(RobolectricTestRunner::class)
abstract class KidScreensRenderBase(private val lang: String, private val dir: LayoutDirection) {
    @get:Rule
    val compose = createComposeRule()

    private var seenDirection: LayoutDirection? = null
    private var seenLanguage: String? = null

    private val ali = KidChild("c-ali", "Ali", AvatarColor.CORAL)
    private val sara = KidChild("c-sara", "Sara", AvatarColor.SKY)

    private fun str(id: Int, vararg args: Any): String = RuntimeEnvironment.getApplication().getString(id, *args)

    private fun plural(id: Int, n: Int): String = RuntimeEnvironment.getApplication().resources.getQuantityString(id, n, n)

    private fun show(content: @Composable () -> Unit) {
        compose.setContent {
            seenDirection = LocalLayoutDirection.current
            seenLanguage = LocalConfiguration.current.locales[0].language
            KidTubeTheme { content() }
        }
        compose.waitForIdle()
        assertEquals(lang, seenLanguage)
        assertEquals(dir, seenDirection)
    }

    private fun SemanticsNodeInteraction.assertAbsent() = assertDoesNotExist()

    private fun tile(id: String, title: String? = "Video $id", viewed: Boolean = false) = KidTileModel(id, title, null, "v-$id", viewed)

    private fun grid(first: KidTileModel? = null, tiles: List<KidTileModel> = emptyList(), total: Int? = 3, left: Int = 2, time: TimeLeft? = null) =
        KidScreenState.Grid(ali, total, left, first, tiles, time)

    private fun showState(state: KidScreenState, open: (String) -> Unit = {}, switch: () -> Unit = {}) =
        show { KidGridContent(state, open, switch) }

    // ---- picker ----------------------------------------------------------------------------------------------

    @Test
    fun pickerOnAParentPhoneShowsChildrenAndTheParentsLock() {
        val picked = mutableListOf<String>()
        var parents = 0
        show { KidPickerContent(KidPickerUiState(listOf(ali, sara), lastChildId = "c-sara", canLeaveKidMode = true), { picked += it }, { parents++ }) }
        compose.onNodeWithText(str(R.string.kid_picker_title)).assertIsDisplayed()
        compose.onNodeWithText("Ali").assertIsDisplayed()
        compose.onNodeWithText("Sara").performClick()
        compose.onNodeWithText(str(R.string.kid_picker_parents)).performClick()
        assertEquals(listOf("c-sara"), picked)
        assertEquals(1, parents)
    }

    @Test
    fun pickerOnAKidOnlyDeviceHasNoParentsButton() {
        show { KidPickerContent(KidPickerUiState(listOf(ali), canLeaveKidMode = false), {}, {}) }
        compose.onNodeWithText("Ali").assertIsDisplayed()
        compose.onNodeWithText(str(R.string.kid_picker_parents)).assertAbsent()
    }

    @Test
    fun pickerWithOneChildStillShowsThePickerWithOneTap() {
        val picked = mutableListOf<String>()
        show { KidPickerContent(KidPickerUiState(listOf(ali)), { picked += it }, {}) }
        compose.onNodeWithText(str(R.string.kid_picker_title)).assertIsDisplayed()
        compose.onNodeWithText("Ali").performClick()
        assertEquals(listOf("c-ali"), picked)
    }

    @Test
    fun pickerWithNoChildrenSaysAskAParent() {
        show { KidPickerContent(KidPickerUiState(emptyList(), canLeaveKidMode = true), {}, {}) }
        compose.onNodeWithText(str(R.string.kid_picker_empty_title)).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.kid_picker_parents)).assertIsDisplayed()
    }

    // ---- grid ------------------------------------------------------------------------------------------------------

    @Test
    fun gridShowsTilesWithTitleDescriptionsAndLargeTargets() {
        val opened = mutableListOf<String>()
        showState(grid(tiles = listOf(tile("a"), tile("b", viewed = true), tile("c", title = null))), open = { opened += it })
        compose.onNodeWithContentDescription("Video a").assertIsDisplayed().assertHeightIsAtLeast(150.dp)
        compose.onNodeWithContentDescription("Video b").assertIsDisplayed()
        compose.onNodeWithContentDescription(str(R.string.kid_video_untitled)).assertIsDisplayed() // a video without a title
        compose.onNodeWithContentDescription("Video b").performClick()
        assertEquals(listOf("b"), opened)
        compose.onNodeWithText(str(R.string.kid_first_tag), useUnmergedTree = true).assertAbsent() // not locked: no First tile
    }

    @Test
    fun firstTileIsBigAndTaggedAndOnlyThereWhenGiven() {
        showState(grid(first = tile("r", "Counting to ten")))
        compose.onNodeWithContentDescription("Counting to ten").assertIsDisplayed().assertHeightIsAtLeast(200.dp)
        compose.onNodeWithText(str(R.string.kid_first_tag), useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun leftTodayDotsCountFilledAndEmpty() {
        showState(grid(tiles = listOf(tile("a")), total = 3, left = 2))
        assertEquals(2, compose.onAllNodesWithTag(DOT_FILLED_TAG, useUnmergedTree = true).fetchSemanticsNodes().size)
        assertEquals(1, compose.onAllNodesWithTag(DOT_EMPTY_TAG, useUnmergedTree = true).fetchSemanticsNodes().size)
        compose.onNodeWithContentDescription(plural(R.plurals.kid_left_today, 2)).assertIsDisplayed()
    }

    @Test
    fun noDotsInMinutesMode() {
        showState(grid(tiles = listOf(tile("a")), total = null))
        assertEquals(0, compose.onAllNodesWithTag(DOT_FILLED_TAG, useUnmergedTree = true).fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodesWithTag(DOT_EMPTY_TAG, useUnmergedTree = true).fetchSemanticsNodes().size)
    }

    @Test
    fun timeLeftBarShowsMinutesRoundedUp() {
        showState(grid(tiles = listOf(tile("a")), time = TimeLeft(secondsLeft = 781, limitSeconds = 1800)))
        compose.onNodeWithContentDescription(plural(R.plurals.kid_time_left, 14)).assertIsDisplayed()
    }

    @Test
    fun noTimeBarWithoutALimit() {
        showState(grid(tiles = listOf(tile("a")), time = null))
        compose.onNodeWithContentDescription(plural(R.plurals.kid_time_left, 14)).assertAbsent()
    }

    // ---- status screens ----------------------------------------------------------------------------------------------

    private fun status(kind: StatusKind, title: Int, subtitle: String) {
        var switched = 0
        showState(KidScreenState.Status(ali, kind), switch = { switched++ })
        compose.onNodeWithText(str(title)).assertIsDisplayed()
        compose.onNodeWithText(subtitle).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.kid_switch)).performClick()
        assertEquals(1, switched)
    }

    @Test fun doneForToday() = status(StatusKind.DONE, R.string.kid_done_title, str(R.string.kid_done_subtitle, "Ali"))

    @Test fun timesUp() = status(StatusKind.TIME_UP, R.string.kid_time_up_title, str(R.string.kid_time_up_subtitle, "Ali"))

    @Test fun dayOff() = status(StatusKind.DAY_OFF, R.string.kid_day_off_title, str(R.string.kid_day_off_subtitle, "Ali"))

    @Test fun nothingHereYet() = status(StatusKind.NOTHING, R.string.kid_empty_title, str(R.string.kid_empty_subtitle))

    @Test
    fun loadingShowsNoTextAndNoExit() {
        showState(KidScreenState.Loading)
        compose.onNodeWithText(str(R.string.kid_switch)).assertAbsent()
    }

    @Test
    fun urduCopyIsTheAgreedFirstDraft() {
        if (lang != "ur") return
        assertEquals("آج کے لیے بس!", str(R.string.kid_done_title))
        assertEquals("کل ملیں گے، Ali", str(R.string.kid_done_subtitle, "Ali"))
        assertEquals("آج کا وقت ختم", str(R.string.kid_time_up_title))
        assertEquals("آنکھوں کو آرام دیں، Ali", str(R.string.kid_time_up_subtitle, "Ali"))
        assertEquals("بچہ بدلیں", str(R.string.kid_switch))
    }
}

@Config(qualifiers = EN)
class KidScreensRenderEnTest : KidScreensRenderBase("en", LayoutDirection.Ltr)

@Config(qualifiers = UR)
class KidScreensRenderUrTest : KidScreensRenderBase("ur", LayoutDirection.Rtl)
