package com.bbro.kidtube.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.ResolvedTextDirection
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.LayoutDirection
import com.bbro.kidtube.data.entity.ChildEntity
import com.bbro.kidtube.data.remote.Platform
import com.bbro.kidtube.share.PlanOffer
import com.bbro.kidtube.share.SavedInfo
import com.bbro.kidtube.share.ShareContent
import com.bbro.kidtube.share.ShareLink
import com.bbro.kidtube.share.SharePhase
import com.bbro.kidtube.share.SharePreset
import com.bbro.kidtube.share.ShareUiState
import com.bbro.kidtube.ui.components.BadgeStatus
import com.bbro.kidtube.ui.components.CodeField
import com.bbro.kidtube.ui.components.KidTubeTextField
import com.bbro.kidtube.ui.parent.Assignment
import com.bbro.kidtube.ui.parent.LibraryContent
import com.bbro.kidtube.ui.parent.LibraryItem
import com.bbro.kidtube.ui.parent.LibraryNote
import com.bbro.kidtube.ui.parent.LibraryUiState
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

/** The share sheet and the library under the real en and ur (RTL) resource configurations, plus the LTR input fields. */
@RunWith(RobolectricTestRunner::class)
class ShareAndLibraryRenderTest {
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

    private val ali = ChildEntity("1", "Ali", null, null)
    private val sara = ChildEntity("2", "Sara", null, null)
    private val link = ShareLink("https://youtu.be/dQw4w9WgXcQ", "youtube:dQw4w9WgXcQ", Platform.YOUTUBE, null, null)

    private fun share(
        state: ShareUiState,
        onSave: () -> Unit = {},
        onToggle: (String) -> Unit = {},
        onCreate: () -> Unit = {},
        onWithout: () -> Unit = {},
    ) = show {
        ShareContent(
            state = state, onToggleChild = onToggle, onToggleAll = {}, onPreset = {}, onPinChange = {}, onSave = onSave,
            onCreatePlans = onCreate, onSaveWithoutPlan = onWithout, onClose = {},
            addChildDialog = { Box {} },
        )
    }

    private val form = ShareUiState(phase = SharePhase.FORM, link = link, children = listOf(ali, sara), selected = setOf("1"), pin = "1234")

    // ---- share sheet ----------------------------------------------------------------------------------------------

    @Test
    @Config(qualifiers = EN)
    fun share_en() {
        share(form)

        assertLocale("en", LayoutDirection.Ltr)
        compose.onNodeWithText("Save this video").assertIsDisplayed()
        compose.onNodeWithText("Who is it for?").assertIsDisplayed()
        compose.onNodeWithText("All children").assertIsDisplayed()
        compose.onNodeWithText("Ali").assertIsDisplayed()
        compose.onNodeWithText("How should it appear?").assertIsDisplayed()
        listOf("Once a day", "3 times total", "Must watch first", "Library only").forEach { compose.onNodeWithText(it).assertIsDisplayed() }
        compose.onNodeWithText("Parent PIN").assertIsDisplayed()
        compose.onNodeWithText("Save for Ali").assertIsEnabled()
        compose.onNodeWithText("youtu.be/dQw4w9WgXcQ").assertIsDisplayed() // no thumbnail or title known: the link
    }

    @Test
    @Config(qualifiers = UR)
    fun share_ur_rtl() {
        share(form)

        assertLocale("ur", LayoutDirection.Rtl)
        compose.onNodeWithText("یہ ویڈیو محفوظ کریں").assertIsDisplayed()
        compose.onNodeWithText("یہ کس کے لیے ہے؟").assertIsDisplayed()
        compose.onNodeWithText("تمام بچے").assertIsDisplayed()
        compose.onNodeWithText("یہ کیسے نظر آئے؟").assertIsDisplayed()
        compose.onNodeWithText("دن میں ایک بار").assertIsDisplayed()
        compose.onNodeWithText("صرف لائبریری").assertIsDisplayed()
        compose.onNodeWithText("والدین کا پن").assertIsDisplayed()
        compose.onNodeWithText("Ali کے لیے محفوظ کریں").assertIsEnabled()
    }

    @Test
    @Config(qualifiers = EN)
    fun share_saveIsDisabledWithoutAPin() {
        share(form.copy(pin = "12"))
        compose.onNodeWithText("Save for Ali").assertIsNotEnabled()
    }

    @Test
    @Config(qualifiers = EN)
    fun share_saveButtonNamesEveryone_orSaysAllChildren_orLibrary() {
        share(form.copy(selected = setOf("1", "2")))
        compose.onNodeWithText("Save for all children").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = EN)
    fun share_libraryOnly_savesToTheLibrary() {
        share(form.copy(preset = SharePreset.LIBRARY_ONLY, selected = emptySet()))
        compose.onNodeWithText("Save to library").assertIsEnabled()
    }

    @Test
    @Config(qualifiers = EN)
    fun share_offlineNote_andTheDuplicateNote() {
        share(form.copy(offline = true, note = com.bbro.kidtube.share.ShareNote.AlreadySaved(listOf("Ali"))))
        compose.onNodeWithText("No internet. The link is saved on this phone and sent when you are back online.").assertIsDisplayed()
        compose.onNodeWithText("Already saved for Ali.").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = EN)
    fun share_clickingSave_callsBack() {
        var saved = false
        share(form, onSave = { saved = true })
        compose.onNodeWithText("Save for Ali").performClick()
        assertTrue(saved)
    }

    @Test
    @Config(qualifiers = EN)
    fun share_noPlanOffer_hasBothChoices_andHidesSave() {
        var created = false
        var without = false
        share(form.copy(planOffer = PlanOffer(listOf("Pip"))), onCreate = { created = true }, onWithout = { without = true })

        compose.onNodeWithText("No plan yet for Pip").assertIsDisplayed()
        compose.onNodeWithText("Save for Ali").assertDoesNotExist()
        compose.onNodeWithText("Create plan and save").performClick()
        compose.onNodeWithText("Save without a plan").performClick()
        assertTrue(created && without)
    }

    @Test
    @Config(qualifiers = UR)
    fun share_noPlanOffer_ur_rtl() {
        share(form.copy(planOffer = PlanOffer(listOf("Pip"))))
        compose.onNodeWithText("Pip کا ابھی کوئی پلان نہیں").assertIsDisplayed()
        compose.onNodeWithText("پلان بنا کر محفوظ کریں").assertIsDisplayed()
        compose.onNodeWithText("بغیر پلان کے محفوظ کریں").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = EN)
    fun share_unsupportedLink() {
        share(ShareUiState(phase = SharePhase.UNSUPPORTED))
        compose.onNodeWithText("This link is not supported").assertIsDisplayed()
        compose.onNodeWithText("KidTube can save YouTube and Facebook video links.").assertIsDisplayed()
        compose.onNodeWithText("Close").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = UR)
    fun share_unsupportedLink_ur_rtl() {
        share(ShareUiState(phase = SharePhase.UNSUPPORTED))
        compose.onNodeWithText("یہ لنک سپورٹ نہیں ہوتا").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = EN)
    fun share_kidOnlyDevice_showsAMessage_notTheForm() {
        share(ShareUiState(phase = SharePhase.NOT_PARENT))
        compose.onNodeWithText("Sharing is for parents").assertIsDisplayed()
        compose.onNodeWithText("Who is it for?").assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = EN)
    fun share_noChildren_offersToAddOne() {
        share(ShareUiState(phase = SharePhase.NO_CHILDREN, link = link))
        compose.onNodeWithText("Add a child first").assertIsDisplayed()
        compose.onNodeWithText("Add child").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = EN)
    fun share_saved_confirms_andListsWhoWaitsForAPlan() {
        share(ShareUiState(phase = SharePhase.SAVED, saved = SavedInfo(false, listOf("Sara"), listOf("Pip"))))
        compose.onNodeWithText("Saved").assertIsDisplayed()
        compose.onNodeWithText("Left out, already saved for: Sara.").assertIsDisplayed()
        compose.onNodeWithText("Waiting for a plan for: Pip. The video is added once the plan exists.").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = UR)
    fun share_saved_ur_rtl() {
        share(ShareUiState(phase = SharePhase.SAVED, saved = SavedInfo(libraryOnly = true)))
        compose.onNodeWithText("محفوظ ہو گیا").assertIsDisplayed()
        compose.onNodeWithText("صرف لائبریری میں شامل ہوئی۔").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = EN)
    fun share_noChildSelected_showsTheHint_andSaveIsOff() {
        share(form.copy(selected = emptySet()))
        compose.onNodeWithText("Choose who this is for to save it.").assertIsDisplayed()
        compose.onNodeWithText("Save").assertIsNotEnabled()
    }

    @Test
    @Config(qualifiers = EN)
    fun share_withAChild_hasNoHint() {
        share(form)
        compose.onNodeWithText("Choose who this is for to save it.").assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = UR)
    fun share_noChildSelected_hint_ur() {
        share(form.copy(selected = emptySet()))
        compose.onNodeWithText("محفوظ کرنے کے لیے چنیں کہ یہ کس کے لیے ہے۔").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = EN)
    fun share_saveButtonDoesNotMove_whenTheOfflineNoteAppears() {
        val offline = androidx.compose.runtime.mutableStateOf(false)
        show {
            ShareContent(
                state = form.copy(offline = offline.value), onToggleChild = {}, onToggleAll = {}, onPreset = {}, onPinChange = {}, onSave = {},
                onCreatePlans = {}, onSaveWithoutPlan = {}, onClose = {}, addChildDialog = { Box {} },
            )
        }
        compose.waitForIdle()
        val before = compose.onNodeWithText("Save for Ali").fetchSemanticsNode().boundsInRoot.top
        offline.value = true
        compose.waitForIdle()
        compose.onNodeWithText("No internet. The link is saved on this phone and sent when you are back online.").assertIsDisplayed()
        val after = compose.onNodeWithText("Save for Ali").fetchSemanticsNode().boundsInRoot.top
        assertEquals("Save must not shift when the note appears", before, after, 0.5f)
        compose.onNodeWithTag(com.bbro.kidtube.share.SHARE_STATUS_SLOT_TAG).assertIsDisplayed()
    }

    // ---- library --------------------------------------------------------------------------------------------------

    private fun library(state: LibraryUiState, onSelect: (String?) -> Unit = {}, onKid: () -> Unit = {}, onAdd: () -> Unit = {}) = show {
        LibraryContent(state, onSelectChild = onSelect, onRefresh = {}, onAddChild = onAdd, onKidMode = onKid, onSettings = {})
    }

    private val items = listOf(
        LibraryItem("1", "Counting to ten", null, BadgeStatus.VERIFIED, listOf(Assignment("1", "Ali", SharePreset.ONCE_A_DAY))),
        LibraryItem("2", "youtu.be/abc", null, BadgeStatus.PENDING, emptyList(), LibraryNote.WAITING_TO_SEND, fromOutbox = true),
        LibraryItem("3", "Lost video", null, BadgeStatus.UNAVAILABLE, listOf(Assignment("2", "Sara", SharePreset.MUST_WATCH))),
    )
    private val filled = LibraryUiState(loaded = true, children = listOf(ali, sara), items = items, totalItems = 3)

    @Test
    @Config(qualifiers = EN)
    fun library_en_rowsBadgesAndChildFilter() {
        library(filled)

        assertLocale("en", LayoutDirection.Ltr)
        compose.onNodeWithText("Library").assertIsDisplayed()
        compose.onNodeWithText("Kid mode").assertIsDisplayed()
        compose.onNodeWithText("All").assertIsDisplayed()
        compose.onNodeWithText("Ali").assertIsDisplayed()
        compose.onNodeWithText("Counting to ten").assertIsDisplayed()
        compose.onNodeWithText("Verified").assertIsDisplayed()
        compose.onNodeWithText("Pending").assertIsDisplayed()
        compose.onNodeWithText("Unavailable").assertIsDisplayed()
        compose.onNodeWithText("Ali · Once a day").assertIsDisplayed()
        compose.onNodeWithText("Sara · Must watch first").assertIsDisplayed()
        compose.onNodeWithText("Library only").assertIsDisplayed() // the outbox link has no child
        compose.onNodeWithText("Waiting to send").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = UR)
    fun library_ur_rtl() {
        library(filled)

        assertLocale("ur", LayoutDirection.Rtl)
        compose.onNodeWithText("لائبریری").assertIsDisplayed()
        compose.onNodeWithText("بچوں کا موڈ").assertIsDisplayed()
        compose.onNodeWithText("سب").assertIsDisplayed()
        compose.onNodeWithText("تصدیق شدہ").assertIsDisplayed()
        compose.onNodeWithText("زیرِ انتظار").assertIsDisplayed()
        compose.onNodeWithText("دستیاب نہیں").assertIsDisplayed()
        compose.onNodeWithText("Ali · دن میں ایک بار").assertIsDisplayed()
        compose.onNodeWithText("بھیجنے کا انتظار").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = EN)
    fun library_kidModeButton_andChildChips_callBack() {
        var kid = false
        var picked: String? = "unset"
        library(filled, onSelect = { picked = it }, onKid = { kid = true })

        compose.onNodeWithText("Kid mode").performClick()
        compose.onNodeWithText("Sara").performClick()

        assertTrue(kid)
        assertEquals("2", picked)
    }

    @Test
    @Config(qualifiers = EN)
    fun library_empty_pointsToSharingFromYouTube_andOffersToAddAChild() {
        var added = false
        library(LibraryUiState(loaded = true), onAdd = { added = true })

        compose.onNodeWithText("No videos yet").assertIsDisplayed()
        compose.onNodeWithText("Open a video in YouTube, tap Share, then choose KidTube.").assertIsDisplayed()
        compose.onNodeWithText("Add child").performClick()
        assertTrue(added)
    }

    @Test
    @Config(qualifiers = UR)
    fun library_empty_ur_rtl() {
        library(LibraryUiState(loaded = true))
        compose.onNodeWithText("ابھی کوئی ویڈیو نہیں").assertIsDisplayed()
        compose.onNodeWithText("بچہ شامل کریں").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = EN)
    fun library_emptyForTheChosenChild_isNotTheFirstRunScreen() {
        library(LibraryUiState(loaded = true, children = listOf(ali, sara), selectedChildId = "2", items = emptyList(), totalItems = 3))
        compose.onNodeWithText("No videos for this child yet.").assertIsDisplayed()
        compose.onNodeWithText("No videos yet").assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = EN)
    fun library_emptyWithChildren_doesNotNagToAddOne() {
        library(LibraryUiState(loaded = true, children = listOf(ali)))
        compose.onNodeWithText("No videos yet").assertIsDisplayed()
        compose.onNodeWithText("Add child").assertDoesNotExist()
    }

    // ---- fields that are always left-to-right (point 0) ------------------------------------------------------------

    @Test
    @Config(qualifiers = UR)
    fun textField_inUrdu_keepsLatinTextOnTheLeft() {
        show {
            Box(Modifier.fillMaxWidth()) {
                KidTubeTextField(label = "ای میل", value = "mum@example.com", onValueChange = {}, modifier = Modifier.fillMaxWidth())
            }
        }

        assertLocale("ur", LayoutDirection.Rtl)
        // where the FIRST CHARACTER is drawn inside the text field: the left end, not the right end as RTL would put it
        val node = compose.onNodeWithText("mum@example.com").fetchSemanticsNode()
        val layouts = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        val layout = layouts.single()
        val first = layout.getBoundingBox(0)
        assertTrue("first character at ${first.left} of ${layout.size.width}", first.left < layout.size.width / 4f)
        assertEquals(ResolvedTextDirection.Ltr, layout.getParagraphDirection(0))
    }

    @Test
    @Config(qualifiers = UR)
    fun codeBoxes_inUrdu_fillLeftToRight() {
        show { CodeField(value = "123", onValueChange = {}, modifier = Modifier.fillMaxWidth()) }

        assertLocale("ur", LayoutDirection.Rtl)
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val first = compose.onNodeWithText("1", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val second = compose.onNodeWithText("2", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val third = compose.onNodeWithText("3", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue("digits must run left to right: $first, $second, $third", first.left < second.left && second.left < third.left)
        assertTrue("the first digit sits in the left half", first.left < root.width / 2)
    }

    // ---- dropped placements ------------------------------------------------------------------------------------------

    private fun dropped(reason: com.bbro.kidtube.ui.parent.NotAddedReason?, ids: List<String> = listOf("o1")) = LibraryUiState(
        loaded = true, children = listOf(ali), totalItems = 1,
        items = listOf(
            LibraryItem(
                "v1", "Lost video", null, BadgeStatus.UNAVAILABLE, emptyList(), LibraryNote.NOT_ADDED,
                notAddedReason = reason, dismissibleOutboxIds = ids,
            ),
        ),
    )

    @Test
    @Config(qualifiers = EN)
    fun library_droppedPlacement_unavailable_keepsTheBadge_andOffersRemove() {
        var removed = 0
        show { LibraryContent(dropped(com.bbro.kidtube.ui.parent.NotAddedReason.Unavailable), {}, {}, {}, {}, {}, onDismiss = { removed++ }) }
        compose.onNodeWithText("Not added to the plan: video unavailable").assertIsDisplayed()
        compose.onNodeWithText("Unavailable").assertIsDisplayed()
        compose.onNodeWithText("Remove").performClick()
        assertEquals(1, removed)
    }

    @Test
    @Config(qualifiers = EN)
    fun library_droppedPlacement_showsTheServersReason() {
        show { LibraryContent(dropped(com.bbro.kidtube.ui.parent.NotAddedReason.Server("Plan is full")), {}, {}, {}, {}, {}) }
        compose.onNodeWithText("Not added to the plan: Plan is full").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = EN)
    fun library_droppedPlacement_withoutAReadableReason_saysCouldNotBeAdded() {
        show { LibraryContent(dropped(com.bbro.kidtube.ui.parent.NotAddedReason.Unknown), {}, {}, {}, {}, {}) }
        compose.onNodeWithText("Could not be added").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = UR)
    fun library_droppedPlacement_ur_rtl() {
        show { LibraryContent(dropped(com.bbro.kidtube.ui.parent.NotAddedReason.Unavailable), {}, {}, {}, {}, {}) }
        assertLocale("ur", LayoutDirection.Rtl)
        compose.onNodeWithText("منصوبے میں شامل نہیں ہوئی: ویڈیو دستیاب نہیں").assertIsDisplayed()
        compose.onNodeWithText("ہٹائیں").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = EN)
    fun library_rowsThatCannotBeDismissed_haveNoRemoveButton() {
        show { LibraryContent(dropped(null, ids = emptyList()), {}, {}, {}, {}, {}) }
        compose.onNodeWithText("Remove").assertDoesNotExist()
    }
}
