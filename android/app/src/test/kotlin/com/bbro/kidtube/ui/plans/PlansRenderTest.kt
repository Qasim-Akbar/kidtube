package com.bbro.kidtube.ui.plans

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.unit.LayoutDirection
import com.bbro.kidtube.R
import com.bbro.kidtube.data.entity.ChildEntity
import com.bbro.kidtube.data.remote.OnMiss
import com.bbro.kidtube.data.remote.PaceType
import com.bbro.kidtube.data.repo.PlanAction
import com.bbro.kidtube.data.repo.PlanFailure
import com.bbro.kidtube.data.repo.PlanFailureKind
import com.bbro.kidtube.data.repo.SavePart
import com.bbro.kidtube.data.repo.SettingsOutcome
import com.bbro.kidtube.engine.DayStatus
import com.bbro.kidtube.ui.components.BadgeStatus
import com.bbro.kidtube.ui.theme.KidTubeTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDate

internal fun sampleModel(limit: Int? = 30, paceType: PaceType = PaceType.VIDEOS_PER_DAY): PlanScreenModel {
    val d = LocalDate.parse("2026-01-05")
    return PlanScreenModel(
        child = ChildEntity("c-ali", "Ali", "coral", limit),
        plan = PlanSummary(paceType, if (paceType == PaceType.VIDEOS_PER_DAY) 3 else 30, 0b0011111, d, null, 2),
        items = listOf(
            PlanItemRow("i1", "Counting to ten", null, BadgeStatus.VERIFIED, false, true, 1, null, null, null, canMoveUp = false, canMoveDown = true),
            PlanItemRow("i2", "Colours with Pip", null, BadgeStatus.VERIFIED, false, false, null, null, null, null, true, true),
            PlanItemRow("i3", "The alphabet song", null, BadgeStatus.PENDING, false, false, 3, 1, null, null, true, true),
            PlanItemRow("i4", "Animal sounds", null, BadgeStatus.PENDING, true, false, null, null, LocalDate.parse("2026-01-12"), OnMiss.DROP, true, false),
        ),
        pickable = listOf(PickableVideo("v9", "Shapes and colours", null, BadgeStatus.VERIFIED, false), PickableVideo("v8", "Facebook clip", null, BadgeStatus.PENDING, true)),
        days = listOf(
            DayRow(d, DayStatus.SCHEDULED, listOf(DayEntry("Counting to ten", true), DayEntry("The alphabet song", false))),
            DayRow(d.plusDays(1), DayStatus.SCHEDULED, listOf(DayEntry("The alphabet song", false))),
            DayRow(d.plusDays(2), DayStatus.SCHEDULED, emptyList()),
            DayRow(d.plusDays(3), DayStatus.DAY_OFF, emptyList()),
            DayRow(d.plusDays(4), DayStatus.OUTSIDE_DATES, emptyList()),
        ),
        unlimitedTitles = listOf("Colours with Pip"),
    )
}

/** The Plans tab and its forms under the real en and ur (RTL) resource configurations. */
@RunWith(RobolectricTestRunner::class)
abstract class PlansRenderBase(private val lang: String, private val dir: LayoutDirection) {
    @get:Rule
    val compose = createComposeRule()

    private var seenDirection: LayoutDirection? = null
    private var seenLanguage: String? = null
    private fun str(id: Int, vararg args: Any): String = RuntimeEnvironment.getApplication().getString(id, *args)

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

    private fun tab(
        model: PlanScreenModel? = sampleModel(),
        busy: Boolean = false,
        error: PlanFailure? = null,
        actions: PlansActions = PlansActions(),
    ) = show {
        val children = listOf(ChildEntity("c-ali", "Ali", "coral", 30), ChildEntity("c-sara", "Sara", "sky", null))
        PlansContent(PlansUiState(true, children, "c-ali", model, busy), error, null, SnackbarHostState(), actions)
    }

    private fun scrollTo(tag: String) {
        compose.onNodeWithTag(PLANS_LIST_TAG).performScrollToNode(hasTestTag(tag))
    }

    // ---- summary ------------------------------------------------------------------------------------------------------

    @Test
    fun summaryShowsPaceWeekdaysCatchUpAndLimit() {
        tab()
        compose.onNodeWithTag("plan_summary").assertIsDisplayed()
        compose.onNodeWithText(RuntimeEnvironment.getApplication().resources.getQuantityString(R.plurals.plans_pace_videos, 3, 3)).assertIsDisplayed()
        compose.onNodeWithTag("weekday_0").assertIsDisplayed() // Monday first
        compose.onNodeWithText(RuntimeEnvironment.getApplication().resources.getQuantityString(R.plurals.plans_catchup_videos, 2, 2)).assertIsDisplayed()
        compose.onAllNodesWithText(RuntimeEnvironment.getApplication().resources.getQuantityString(R.plurals.plans_limit_minutes, 30, 30)).onFirst().assertIsDisplayed()
    }

    @Test
    fun minutesPlanSaysMinutes() {
        tab(sampleModel(paceType = PaceType.MINUTES_PER_DAY))
        compose.onAllNodesWithText(RuntimeEnvironment.getApplication().resources.getQuantityString(R.plurals.plans_pace_minutes, 30, 30)).onFirst().assertIsDisplayed()
        compose.onNodeWithText(RuntimeEnvironment.getApplication().resources.getQuantityString(R.plurals.plans_catchup_minutes, 2, 2)).assertIsDisplayed()
    }

    @Test
    fun extraTimeButtonsExistOnlyWithADailyLimit_otherwiseAHint() {
        val given = mutableListOf<Int>()
        tab(actions = PlansActions(onGiveExtraTime = { _, m -> given += m }))
        compose.onNodeWithTag("extra_15").performClick()
        compose.onNodeWithTag("extra_10").assertIsDisplayed()
        compose.onNodeWithTag("extra_30").assertIsDisplayed()
        assertEquals(listOf(15), given)
        compose.onAllNodesHint().assertCountEquals0()
    }

    @Test
    fun noLimitHidesTheButtonsAndShowsTheHint() {
        tab(sampleModel(limit = null))
        compose.onNodeWithTag("extra_hint").assertIsDisplayed()
        compose.onNodeWithText(str(R.string.plans_extra_hint)).assertIsDisplayed()
        compose.onNodeWithTag("extra_15").assertDoesNotExistCompat()
    }

    // ---- items ---------------------------------------------------------------------------------------------------------

    @Test
    fun itemRowsShowBadges_andTheEdgeMoveButtonsAreDisabled() {
        tab()
        scrollTo("plan_item_i1")
        compose.onNodeWithText(str(R.string.plans_badge_required)).assertIsDisplayed()
        compose.onNodeWithTag("up_i1").assertIsNotEnabled()
        compose.onNodeWithTag("down_i1").assertIsEnabled()
        scrollTo("plan_item_i3")
        compose.onNodeWithText(str(R.string.plans_badge_cap, 1)).assertIsDisplayed()
        compose.onNodeWithText(RuntimeEnvironment.getApplication().resources.getQuantityString(R.plurals.plans_badge_times, 3, 3)).assertIsDisplayed()
        scrollTo("plan_item_i4")
        compose.onNodeWithTag("down_i4").assertIsNotEnabled()
        compose.onNodeWithText(str(R.string.facebook_parents_only)).assertIsDisplayed()
    }

    @Test
    fun theMissRuleShowsForAnchoredItemsOnly() {
        tab()
        scrollTo("plan_item_i4")
        compose.onNode(hasText(str(R.string.plans_on_miss_drop), substring = true)).assertIsDisplayed()
        compose.onNode(hasText(str(R.string.plans_on_miss_shift), substring = true)).assertDoesNotExistCompat()
    }

    @Test
    fun moveEditAndRemoveReachTheActions_removeAsksFirst() {
        val calls = mutableListOf<String>()
        tab(actions = PlansActions(
            onMove = { _, id, up -> calls += "move $id $up" },
            onRemove = { id, done -> calls += "remove $id"; done() },
        ))
        scrollTo("plan_item_i2")
        compose.onNodeWithTag("down_i2").performClick()
        compose.onNodeWithTag("remove_i2").performClick()
        compose.onNodeWithText(str(R.string.plans_remove_title)).assertIsDisplayed()
        assertEquals(listOf("move i2 false"), calls) // nothing removed yet
        compose.onNodeWithTag("remove_confirm").performClick()
        assertEquals(listOf("move i2 false", "remove i2"), calls)
    }

    @Test
    fun editOpensTheItemDialog_requiredHidesRepeat_andSavesTargetAndCap() {
        var saved: Triple<Boolean, Int?, Int?>? = null
        tab(actions = PlansActions(onEditItem = { _, r, t, c, done -> saved = Triple(r, t, c); done() }))
        scrollTo("plan_item_i2")
        compose.onNodeWithTag("edit_i2").performClick()
        compose.onNodeWithTag("item_dialog").assertIsDisplayed()
        compose.onNodeWithTag("item_repeat").assertIsDisplayed() // optional with no target: repeats
        compose.onNodeWithTag("item_required").performClick()
        compose.onNodeWithTag("item_repeat").assertDoesNotExistCompat() // required: always a target
        compose.onNodeWithTag("target_plus").performClick()
        compose.onNodeWithTag("item_cap_none").performClick() // turn the cap on
        compose.onNodeWithTag("cap_plus").performClick()
        compose.onNodeWithTag("item_save").performClick()
        assertEquals(Triple(true, 2, 2), saved)
    }

    @Test
    fun addVideoListsLibraryVideosAndAddsTheTappedOne() {
        var added: String? = null
        tab(actions = PlansActions(onAddVideo = { _, v, done -> added = v; done() }))
        compose.onNodeWithTag("plan_add_video").performClick()
        compose.onNodeWithTag("add_video_dialog").assertIsDisplayed()
        compose.onNodeWithText("Facebook clip").assertIsDisplayed()
        compose.onNode(hasTestTag("pick_v8") and hasText(str(R.string.facebook_parents_only), substring = true)).assertExists()
        compose.onNodeWithTag("pick_v9").performClick()
        assertEquals("v9", added)
    }

    @Test
    fun errorBannerShowsTheMappedMessage_andBusyShowsProgress() {
        tab(error = PlanFailure(PlanFailureKind.NO_CONNECTION), busy = true)
        compose.onNodeWithText(str(R.string.plans_error_no_connection)).assertIsDisplayed()
        compose.onNodeWithTag("plan_edit").assertIsNotEnabled()
        compose.onNodeWithTag("plan_add_video").assertIsNotEnabled()
    }

    @Test
    fun serverValidationMessageIsShownVerbatim() {
        tab(error = PlanFailure(PlanFailureKind.INVALID, "This video is unavailable"))
        compose.onNodeWithText(str(R.string.plans_error_invalid, "This video is unavailable")).assertIsDisplayed()
    }

    // ---- what happens next ------------------------------------------------------------------------------------------------

    @Test
    fun projectionListsDaysWithTitlesStarsDayOffAndUnlimitedNote() {
        tab()
        scrollTo("plan_next")
        compose.onNodeWithTag("plan_next").assertIsDisplayed()
        compose.onNodeWithTag("day_2026-01-05").assertIsDisplayed()
        compose.onNodeWithText(str(R.string.plans_next_day_off)).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.plans_next_outside)).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.plans_next_nothing)).assertIsDisplayed()
        compose.onNodeWithTag("next_unlimited").assertIsDisplayed()
    }

    @Test
    fun noPlanOffersTheDefaultPlan() {
        var created: String? = null
        tab(model = sampleModel().copy(plan = null, items = emptyList(), days = emptyList()), actions = PlansActions(onCreateDefaultPlan = { created = it }))
        compose.onNodeWithText(str(R.string.plans_no_plan)).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.child_create_default_plan)).performClick()
        assertEquals("c-ali", created)
    }

    // ---- the plan form ------------------------------------------------------------------------------------------------------

    @Test
    fun planFormUsesSteppers_alwaysCarriesTheCatchUpValue_andCannotDropTheLastWeekday() {
        var sent: SettingsInput? = null
        tab(actions = PlansActions(onSaveSettings = { _, input, _ -> sent = input }))
        compose.onNodeWithTag("plan_edit").performClick()
        compose.onNodeWithTag("plan_form").assertIsDisplayed()
        compose.onNodeWithTag("pace_plus").performClick() // 3 -> 4
        compose.onNodeWithTag("catchup_plus").performClick() // 2 -> 3
        compose.onNodeWithTag("limit_minus").performClick() // 30 -> 25
        compose.onNodeWithTag("form_weekday_0").performClick() // Monday off
        compose.onNodeWithTag("form_save").performClick()
        val s = sent!!
        assertEquals(4, s.paceValue)
        assertEquals(3, s.maxExtraPerDay)
        assertEquals(25, s.dailyAppMinutes)
        assertEquals(0b0011110, s.activeWeekdays)
        assertEquals(LocalDate.parse("2026-01-05"), s.startDate)
        assertEquals(null, s.endDate)
    }

    @Test
    fun switchingToMinutesResetsToMinuteSteps_andTheLimitCanBeTurnedOff() {
        var sent: SettingsInput? = null
        tab(actions = PlansActions(onSaveSettings = { _, input, _ -> sent = input }))
        compose.onNodeWithTag("plan_edit").performClick()
        compose.onNodeWithTag("pace_minutes").performClick()
        compose.onNodeWithTag("pace_plus").performClick() // 30 -> 35
        repeat(6) { compose.onNodeWithTag("limit_minus").performClick() } // 30 -> 0 = no limit
        compose.onNodeWithTag("form_save").performClick()
        assertEquals(PaceType.MINUTES_PER_DAY, sent!!.paceType)
        assertEquals(35, sent!!.paceValue)
        assertEquals(30, sent!!.maxExtraPerDay)
        assertEquals(null, sent!!.dailyAppMinutes)
    }

    @Test
    fun partialSaveMessageSaysWhichPartSavedAndWhichDidNot_andTheFormStaysOpen() {
        val outcome = SettingsOutcome(SavePart.Saved, SavePart.Failed(PlanFailure(PlanFailureKind.NO_CONNECTION)))
        show {
            PlanSettingsForm(sampleModel().plan!!, 30, saving = false, message = SettingsMessage(outcome), onSave = {}, onDismiss = {})
        }
        compose.onNodeWithText(str(R.string.plans_save_plan_saved)).assertIsDisplayed()
        compose.onNode(hasText(str(R.string.plans_save_limit_failed), substring = true)).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.plans_save_retry)).assertIsDisplayed()
        compose.onNodeWithTag("plan_form").assertIsDisplayed()
    }

    @Test
    fun planFailedMessageSaysNothingWasSent() {
        val outcome = SettingsOutcome(SavePart.Failed(PlanFailure(PlanFailureKind.INVALID, "bad")), SavePart.NotTried)
        show { PlanSettingsForm(sampleModel().plan!!, 30, false, SettingsMessage(outcome), {}, {}) }
        compose.onNode(hasText(str(R.string.plans_save_plan_failed), substring = true)).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.plans_save_limit_not_tried)).assertIsDisplayed()
    }

    @Test
    fun noChildrenAsksToAddOne() {
        var asked = false
        show { PlansContent(PlansUiState(true, emptyList(), null, null, false), null, null, SnackbarHostState(), PlansActions(onAddChild = { asked = true })) }
        compose.onNodeWithText(str(R.string.plans_no_children)).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.add_child_action)).performClick()
        assertEquals(true, asked)
        assertNotNull(PlanAction.Success(Unit))
    }
}

// tiny compat helpers so the tests read well
private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertDoesNotExistCompat() = assertDoesNotExist()

private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesHint() = onAllNodes(hasTestTag("extra_hint"))

private fun androidx.compose.ui.test.SemanticsNodeInteractionCollection.assertCountEquals0() = assertCountEquals(0)

@Config(qualifiers = "en-w411dp-h2400dp")
class PlansRenderEnTest : PlansRenderBase("en", LayoutDirection.Ltr)

@Config(qualifiers = "ur-ldrtl-w411dp-h2400dp")
class PlansRenderUrTest : PlansRenderBase("ur", LayoutDirection.Rtl)
