package com.bbro.kidtube.ui.reports

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.bbro.kidtube.R
import com.bbro.kidtube.data.entity.ChildEntity
import com.bbro.kidtube.data.remote.ReportDto
import com.bbro.kidtube.data.repo.ReportFailureKind
import com.bbro.kidtube.data.repo.ReportRange
import com.bbro.kidtube.testing.ContractFixtures
import com.bbro.kidtube.ui.theme.KidTubeTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDate

/** Matches every node whose test tag starts with [prefix]. */
private fun tagStartsWith(prefix: String) =
    SemanticsMatcher("tag starts with $prefix") { it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(prefix) == true }

internal val reportChildren = listOf(ChildEntity("c-ali", "Ali", "coral", 30), ChildEntity("c-sara", "Sara", "sky", null))

internal fun fixtureReport(number: Int): ReportDto = ContractFixtures.load(number).decodeBody()

internal fun reportState(
    report: ReportDto?,
    range: ReportRange = ReportRange.WEEK,
    failure: ReportFailureKind? = null,
    refreshing: Boolean = false,
    anchor: LocalDate = LocalDate.parse("2026-01-05"),
    today: LocalDate = LocalDate.parse("2026-01-05"),
) = ReportsUiState(true, reportChildren, "c-ali", range, anchor, today, report, refreshing, failure)

/** The Reports tab under the real en and ur (RTL) resource configurations, on a tall screen so every card is composed. */
@RunWith(RobolectricTestRunner::class)
abstract class ReportsRenderBase(private val lang: String, private val dir: LayoutDirection) {
    @get:Rule
    val compose = createComposeRule()

    private var seenDirection: LayoutDirection? = null
    private var seenLanguage: String? = null
    private val resources get() = RuntimeEnvironment.getApplication().resources
    private fun str(id: Int, vararg args: Any): String = RuntimeEnvironment.getApplication().getString(id, *args)

    private fun show(state: ReportsUiState, actions: ReportsActions = ReportsActions()) {
        compose.setContent {
            seenDirection = LocalLayoutDirection.current
            seenLanguage = LocalConfiguration.current.locales[0].language
            KidTubeTheme { ReportsContent(state, actions) }
        }
        compose.waitForIdle()
        assertEquals(lang, seenLanguage)
        assertEquals(dir, seenDirection)
    }

    private fun inCard(tag: String, text: String) = hasText(text) and hasAnyAncestor(hasTestTag(tag))

    // ---- week ---------------------------------------------------------------------------------------------------------------

    @Test
    fun weekShowsTheFourNumbersExactlyAsTheServerSent() {
        show(reportState(fixtureReport(77)))
        compose.onNodeWithTag("report_summary").assertIsDisplayed()
        compose.onNode(inCard("report_card_views", "1")).assertIsDisplayed()
        compose.onNode(inCard("report_card_watch", resources.durationText(96))).assertIsDisplayed()
        compose.onNode(inCard("report_card_app", resources.durationText(1200))).assertIsDisplayed()
        compose.onNode(inCard("report_card_errors", "1")).assertIsDisplayed()
    }

    @Test
    fun weekChartHasSevenBarsEachWithADescription_andAZeroDayIsStillThere() {
        show(reportState(fixtureReport(77)))
        compose.onNodeWithTag("report_chart").assertIsDisplayed()
        val bars = compose.onAllNodes(tagStartsWith("report_bar_"))
        bars.assertCountEquals(7)
        val monday = compose.onNodeWithTag("report_bar_2026-01-05").fetchSemanticsNode()
        val description = monday.config[SemanticsProperties.ContentDescription].single()
        assertTrue(description, description.contains(resources.durationText(96)))
        val tuesday = compose.onNodeWithTag("report_bar_2026-01-06").fetchSemanticsNode()
        assertTrue(tuesday.config[SemanticsProperties.ContentDescription].single().contains(resources.durationText(0)))
        // heights: the busiest day fills the plot, a zero day is a thin but real line
        compose.onNodeWithTag("report_fill_2026-01-05").assertHeightIsEqualTo(140.dp)
        compose.onNodeWithTag("report_fill_2026-01-06").assertHeightIsEqualTo(2.dp)
        compose.onNodeWithTag("report_fill_2026-01-06").assertIsDisplayed()
        // all seven weekday labels fit
        compose.onAllNodes(hasTestTag("report_label_0")).assertCountEquals(1)
        compose.onAllNodes(hasTestTag("report_label_6")).assertCountEquals(1)
    }

    @Test
    fun theMetricToggleSwitchesTheBarDescriptionsToViews() {
        show(reportState(fixtureReport(77)))
        compose.onNodeWithTag("metric_watch").assertIsOn()
        compose.onNodeWithTag("metric_views").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("metric_views").assertIsOn()
        val description = compose.onNodeWithTag("report_bar_2026-01-05").fetchSemanticsNode().config[SemanticsProperties.ContentDescription].single()
        assertTrue(description, description.contains(resources.getQuantityString(R.plurals.reports_views, 1, 1)))
    }

    @Test
    fun topVideosAndProblemsAreListed() {
        show(reportState(fixtureReport(77)))
        compose.onNodeWithTag("report_top").assertIsDisplayed()
        compose.onNode(inCard("report_top", "Counting to ten")).assertIsDisplayed()
        compose.onNodeWithTag("report_problems").assertIsDisplayed()
        compose.onNode(inCard("report_problems", "The alphabet song")).assertIsDisplayed()
        compose.onNode(hasText(str(R.string.reports_problem_line, "150", resources.getQuantityString(R.plurals.reports_times, 1, 1)))).assertIsDisplayed()
    }

    @Test
    fun planProgressShowsWhatTheReportHas() {
        show(reportState(fixtureReport(77)))
        compose.onNodeWithTag("report_plan").assertIsDisplayed()
        compose.onNodeWithTag("plan_vs_watched").assertIsDisplayed()
        compose.onNodeWithTag("plan_finished").assertIsDisplayed()
        compose.onNodeWithTag("plan_finish").assertIsDisplayed()
        compose.onNodeWithTag("plan_item_00000000-0000-4000-8000-000000000017").assertIsDisplayed()
    }

    // ---- day and month -----------------------------------------------------------------------------------------------------------

    @Test
    fun dayListsTheWatchedVideosInsteadOfAChart() {
        show(reportState(fixtureReport(76), range = ReportRange.DAY))
        compose.onNodeWithTag("report_chart").assertDoesNotExist()
        compose.onNodeWithText(str(R.string.reports_day_videos_title)).assertIsDisplayed()
        val report = fixtureReport(76)
        report.topVideos.forEach { compose.onNodeWithTag("top_${it.videoId}").assertIsDisplayed() }
        compose.onNode(inCard("report_card_watch", resources.durationText(210))).assertIsDisplayed()
    }

    @Test
    fun monthHasOneBarPerDay_butNotAllLabels() {
        show(reportState(fixtureReport(78), range = ReportRange.MONTH))
        val bars = compose.onAllNodes(tagStartsWith("report_bar_"))
        bars.assertCountEquals(31)
        val labels = compose.onAllNodes(tagStartsWith("report_label_")).fetchSemanticsNodes()
        assertTrue("some labels are skipped: ${labels.size}", labels.size in 4..16)
        compose.onNodeWithTag("report_label_0").assertIsDisplayed()
    }

    // ---- period navigation ----------------------------------------------------------------------------------------------------------

    @Test
    fun theLabelNamesThePeriod_nextIsOffAtTodayAndOnInThePast() {
        show(reportState(fixtureReport(77)))
        compose.onNodeWithTag("report_period_label").assertIsDisplayed()
        compose.onNodeWithTag("report_next").assertIsNotEnabled()
        compose.onNodeWithTag("report_prev").assertIsEnabled()
    }

    @Test
    fun arrowsAndSegmentsAndChipsCallTheActions() {
        val calls = mutableListOf<String>()
        val actions = ReportsActions(
            onSelectChild = { calls += "child:$it" },
            onSelectRange = { calls += "range:$it" },
            onPrevious = { calls += "prev" },
            onNext = { calls += "next" },
        )
        show(reportState(fixtureReport(77), anchor = LocalDate.parse("2025-12-29")), actions)
        compose.onNodeWithTag("report_prev").performClick()
        compose.onNodeWithTag("report_next").performClick()
        compose.onNodeWithTag("range_0").performClick()
        compose.onNodeWithTag("range_2").performClick()
        compose.onNodeWithText("Sara").performClick()
        assertEquals(listOf("prev", "next", "range:DAY", "range:MONTH", "child:c-sara"), calls)
        compose.onNodeWithTag("range_1").assertIsSelected()
    }

    // ---- states ---------------------------------------------------------------------------------------------------------------------------

    @Test
    fun anEmptyPeriodSaysSo() {
        show(reportState(fixtureReport(79)))
        compose.onNodeWithTag("report_empty").assertIsDisplayed()
        compose.onNodeWithText(str(R.string.reports_empty_week)).assertIsDisplayed()
        compose.onNodeWithTag("report_summary").assertDoesNotExist()
        compose.onNodeWithTag("report_chart").assertDoesNotExist()
    }

    @Test
    fun anEmptyDayAndMonthUseTheirOwnWords() {
        show(reportState(fixtureReport(79), range = ReportRange.DAY))
        compose.onNodeWithText(str(R.string.reports_empty_day)).assertIsDisplayed()
    }

    @Test
    fun loadingShowsPlaceholdersNotNumbers() {
        show(reportState(null, refreshing = true))
        compose.onNodeWithTag("report_loading").assertIsDisplayed()
        compose.onNodeWithTag("report_summary").assertDoesNotExist()
    }

    @Test
    fun noConnectionShowsNeedsInternetWithARetryButton() {
        var retried = 0
        show(reportState(null, failure = ReportFailureKind.NO_CONNECTION), ReportsActions(onRefresh = { retried++ }))
        compose.onNodeWithTag("report_offline").assertIsDisplayed()
        compose.onNodeWithText(str(R.string.reports_offline_title)).assertIsDisplayed()
        compose.onNodeWithTag("report_retry").assertIsDisplayed().performClick()
        assertEquals(1, retried)
    }

    @Test
    fun otherFailuresHaveTheirOwnWordsAndRetryToo() {
        show(reportState(null, failure = ReportFailureKind.SERVER_UNREACHABLE))
        compose.onNodeWithText(str(R.string.reports_server_title)).assertIsDisplayed()
        compose.onNodeWithTag("report_retry").assertIsDisplayed()
    }

    @Test
    fun aFailedRefreshKeepsTheOldReportAndSaysSo() {
        show(reportState(fixtureReport(77), failure = ReportFailureKind.NO_CONNECTION))
        compose.onNodeWithTag("report_stale").assertIsDisplayed()
        compose.onNodeWithTag("report_summary").assertIsDisplayed()
    }

    @Test
    fun withoutChildrenThereIsAnAddChildButton() {
        show(ReportsUiState(loaded = true))
        compose.onNodeWithText(str(R.string.reports_no_children)).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.add_child_action)).assertIsDisplayed()
    }
}

@Config(qualifiers = "en-w411dp-h2400dp-xxhdpi")
class ReportsRenderEnTest : ReportsRenderBase("en", LayoutDirection.Ltr)

@Config(qualifiers = "ur-ldrtl-w411dp-h2400dp-xxhdpi")
class ReportsRenderUrTest : ReportsRenderBase("ur", LayoutDirection.Rtl)
