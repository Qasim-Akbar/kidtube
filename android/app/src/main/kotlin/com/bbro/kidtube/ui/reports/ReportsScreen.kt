package com.bbro.kidtube.ui.reports

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bbro.kidtube.R
import com.bbro.kidtube.data.remote.ReportDto
import com.bbro.kidtube.data.repo.ReportFailureKind
import com.bbro.kidtube.data.repo.ReportRange
import com.bbro.kidtube.ui.components.Chip
import com.bbro.kidtube.ui.components.LatinAwareText
import com.bbro.kidtube.ui.components.MessageBanner
import com.bbro.kidtube.ui.components.PrimaryButton
import com.bbro.kidtube.ui.components.SegmentedControl
import com.bbro.kidtube.ui.components.VideoThumbnail
import com.bbro.kidtube.ui.plans.MutedText
import com.bbro.kidtube.ui.plans.formatDate
import com.bbro.kidtube.ui.plans.weekdayLabels
import com.bbro.kidtube.ui.theme.KidTubeDimens
import com.bbro.kidtube.ui.theme.KidTubeShapes
import com.bbro.kidtube.ui.theme.KidTubeTheme
import java.time.format.DateTimeFormatter

internal const val REPORTS_LIST_TAG = "reports_list"

/** Every action the Reports tab can ask for; no-ops by default so the content renders in tests. */
class ReportsActions(
    val onSelectChild: (String) -> Unit = {},
    val onSelectRange: (ReportRange) -> Unit = {},
    val onPrevious: () -> Unit = {},
    val onNext: () -> Unit = {},
    val onRefresh: () -> Unit = {},
    val onAddChild: () -> Unit = {},
)

/** The Reports tab: the stateful wrapper around [ReportsContent]. */
@Composable
fun ReportsTab(onAddChild: () -> Unit, viewModel: ReportsViewModel = hiltViewModel()) {
    val state = viewModel.state.collectAsStateWithLifecycle().value
    val actions = remember(viewModel, onAddChild) {
        ReportsActions(
            onSelectChild = viewModel::selectChild,
            onSelectRange = viewModel::selectRange,
            onPrevious = viewModel::previous,
            onNext = viewModel::next,
            onRefresh = viewModel::refresh,
            onAddChild = onAddChild,
        )
    }
    ReportsContent(state, actions)
}

/** The Reports tab, stateless. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportsContent(state: ReportsUiState, actions: ReportsActions) {
    val colors = KidTubeTheme.colors
    val report = state.report
    Column(Modifier.fillMaxSize()) {
        Text(
            text = stringResource(R.string.nav_reports),
            style = MaterialTheme.typography.headlineLarge,
            color = colors.ink,
            modifier = Modifier
                .padding(start = KidTubeDimens.screenPadding, end = KidTubeDimens.screenPadding, top = KidTubeDimens.gap16)
                .semantics { heading() },
        )
        if (state.children.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = KidTubeDimens.screenPadding, vertical = KidTubeDimens.gap12),
                horizontalArrangement = Arrangement.spacedBy(KidTubeDimens.gap8),
            ) {
                state.children.forEach { child ->
                    Chip(
                        text = child.name,
                        selected = state.selectedChildId == child.id,
                        onClick = { actions.onSelectChild(child.id) },
                        role = Role.RadioButton,
                    )
                }
            }
            val ranges = ReportRange.entries
            SegmentedControl(
                options = listOf(R.string.reports_range_day, R.string.reports_range_week, R.string.reports_range_month).map { stringResource(it) },
                selectedIndex = ranges.indexOf(state.range),
                onSelect = { actions.onSelectRange(ranges[it]) },
                tagPrefix = "range",
                modifier = Modifier.padding(horizontal = KidTubeDimens.screenPadding),
            )
            if (state.anchor != null) PeriodNavigator(state, actions)
        }

        when {
            !state.loaded -> Unit
            state.children.isEmpty() -> Column(
                Modifier.fillMaxSize().padding(KidTubeDimens.screenPadding),
                verticalArrangement = Arrangement.spacedBy(KidTubeDimens.gap16),
            ) {
                MutedText(stringResource(R.string.reports_no_children))
                PrimaryButton(text = stringResource(R.string.add_child_action), onClick = actions.onAddChild, modifier = Modifier.fillMaxWidth())
            }
            else -> PullToRefreshBox(
                isRefreshing = state.refreshing && report != null,
                onRefresh = actions.onRefresh,
                modifier = Modifier.fillMaxSize(),
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().testTag(REPORTS_LIST_TAG),
                    contentPadding = PaddingValues(
                        start = KidTubeDimens.screenPadding, end = KidTubeDimens.screenPadding,
                        top = KidTubeDimens.gap12, bottom = 32.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(KidTubeDimens.gap12),
                ) {
                    if (report == null) {
                        item(key = "state") {
                            val failure = state.failure
                            if (failure != null && !state.refreshing) FailureState(failure, actions.onRefresh) else LoadingPlaceholders()
                        }
                    } else {
                        if (state.failure != null) {
                            item(key = "stale") { MessageBanner(stringResource(R.string.reports_stale), Modifier.testTag("report_stale")) }
                        }
                        reportItems(report, state.range)
                    }
                }
            }
        }
    }
}

private fun LazyListScope.reportItems(report: ReportDto, range: ReportRange) {
    if (report.isEmptyPeriod()) {
        item(key = "empty") { EmptyCard(range) }
    } else {
        item(key = "summary") { SummaryGrid(report) }
        if (range != ReportRange.DAY) item(key = "chart") { ChartCard(report) }
        if (report.topVideos.isNotEmpty()) item(key = "top") { VideosCard(report, range) }
        if (report.playbackErrors.isNotEmpty()) item(key = "problems") { ProblemsCard(report) }
    }
    if (report.plannedVsWatched != null || report.planProgress != null || report.perVideo.isNotEmpty()) {
        item(key = "plan") { PlanProgressCard(report) }
    }
}

// ---- navigation ---------------------------------------------------------------------------------------------------------

@Composable
private fun PeriodNavigator(state: ReportsUiState, actions: ReportsActions) {
    val colors = KidTubeTheme.colors
    val anchor = state.anchor ?: return
    val locale = LocalConfiguration.current.locales[0]
    val label = remember(state.range, anchor, locale) { ReportPeriods.label(state.range, anchor, locale) }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = KidTubeDimens.gap8, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NavArrow(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.reports_prev), true, actions.onPrevious, "report_prev")
        LatinAwareText(
            text = label,
            style = MaterialTheme.typography.titleLarge,
            color = colors.ink,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f).testTag("report_period_label").semantics { heading() },
        )
        NavArrow(Icons.AutoMirrored.Filled.ArrowForward, stringResource(R.string.reports_next), state.canGoNext, actions.onNext, "report_next")
    }
}

@Composable
private fun NavArrow(icon: ImageVector, description: String, enabled: Boolean, onClick: () -> Unit, tag: String) {
    val colors = KidTubeTheme.colors
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(KidTubeDimens.minTouchTarget).testTag(tag)) {
        Icon(icon, contentDescription = description, tint = if (enabled) colors.ink else colors.muted.copy(alpha = 0.4f))
    }
}

// ---- cards ----------------------------------------------------------------------------------------------------------------

@Composable
private fun ReportCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val colors = KidTubeTheme.colors
    Surface(
        shape = KidTubeShapes.card,
        color = colors.card,
        border = BorderStroke(KidTubeDimens.borderWidth, colors.line),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(KidTubeDimens.gap16), verticalArrangement = Arrangement.spacedBy(KidTubeDimens.gap12)) { content() }
    }
}

@Composable
private fun SectionTitle(text: String, color: Color = KidTubeTheme.colors.ink) {
    Text(text, style = MaterialTheme.typography.titleLarge, color = color, modifier = Modifier.semantics { heading() })
}

/** "1h 05m" / "5 min" / "40 s" in the current language. */
@Composable
internal fun durationText(seconds: Int): String {
    val resources = LocalContext.current.resources
    val configuration = LocalConfiguration.current
    return remember(seconds, configuration) { resources.durationText(seconds) }
}

@Composable
private fun SummaryGrid(report: ReportDto) {
    Column(Modifier.testTag("report_summary"), verticalArrangement = Arrangement.spacedBy(KidTubeDimens.gap12)) {
        Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(KidTubeDimens.gap12)) {
            StatCard(stringResource(R.string.reports_card_views), report.totals.viewsCompleted.toString(), "report_card_views", Modifier.weight(1f).fillMaxHeight())
            StatCard(stringResource(R.string.reports_card_watch), durationText(report.totals.watchSeconds), "report_card_watch", Modifier.weight(1f).fillMaxHeight())
        }
        Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(KidTubeDimens.gap12)) {
            StatCard(stringResource(R.string.reports_card_app), durationText(report.totals.appSeconds), "report_card_app", Modifier.weight(1f).fillMaxHeight())
            StatCard(
                stringResource(R.string.reports_card_errors), report.errorCount().toString(), "report_card_errors",
                Modifier.weight(1f).fillMaxHeight(), warn = report.errorCount() > 0,
            )
        }
    }
}

@Composable
private fun StatCard(label: String, value: String, tag: String, modifier: Modifier, warn: Boolean = false) {
    val colors = KidTubeTheme.colors
    Surface(
        shape = KidTubeShapes.card,
        color = if (warn) colors.errorBg else colors.card,
        border = BorderStroke(KidTubeDimens.borderWidth, if (warn) colors.errorBg else colors.line),
        modifier = modifier.testTag(tag),
    ) {
        Column(Modifier.padding(KidTubeDimens.gap16), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            LatinAwareText(value, style = MaterialTheme.typography.headlineMedium, color = if (warn) colors.errorText else colors.ink)
            LatinAwareText(label, style = MaterialTheme.typography.labelLarge, color = if (warn) colors.errorText else colors.muted)
        }
    }
}

@Composable
private fun EmptyCard(range: ReportRange) {
    ReportCard(Modifier.testTag("report_empty")) {
        LatinAwareText(
            stringResource(
                when (range) {
                    ReportRange.DAY -> R.string.reports_empty_day
                    ReportRange.WEEK -> R.string.reports_empty_week
                    ReportRange.MONTH -> R.string.reports_empty_month
                },
            ),
            style = MaterialTheme.typography.titleMedium,
            color = KidTubeTheme.colors.muted,
        )
    }
}

// ---- chart --------------------------------------------------------------------------------------------------------------------

private val PLOT_HEIGHT = 140.dp
private val MIN_BAR = 2.dp

@Composable
private fun ChartCard(report: ReportDto) {
    var metric by rememberSaveable { mutableStateOf(ChartMetric.WATCH_TIME) }
    val bars = remember(report, metric) { chartBars(report, metric) }
    val peak = bars.maxOfOrNull { it.value } ?: 0
    ReportCard(Modifier.testTag("report_chart")) {
        SectionTitle(stringResource(R.string.reports_chart_title))
        Row(horizontalArrangement = Arrangement.spacedBy(KidTubeDimens.gap8)) {
            Chip(
                stringResource(R.string.reports_metric_watch), metric == ChartMetric.WATCH_TIME,
                { metric = ChartMetric.WATCH_TIME }, Modifier.testTag("metric_watch"), Role.RadioButton,
            )
            Chip(
                stringResource(R.string.reports_metric_views), metric == ChartMetric.VIEWS,
                { metric = ChartMetric.VIEWS }, Modifier.testTag("metric_views"), Role.RadioButton,
            )
        }
        if (peak > 0) {
            val peakText = if (metric == ChartMetric.WATCH_TIME) durationText(peak) else pluralStringResource(R.plurals.reports_views, peak, peak)
            LatinAwareText(
                stringResource(R.string.reports_chart_peak, peakText),
                style = MaterialTheme.typography.labelMedium, color = KidTubeTheme.colors.muted,
            )
        }
        DayBarsChart(bars, metric, weekly = report.range == ReportRange.WEEK.wire)
    }
}

/**
 * One bar per day, drawn with plain boxes. Heights are proportional to the largest day; a zero day keeps a thin [MIN_BAR] line.
 * Labels under the bars are weekday names (week) or day numbers (month); [labelIndices] skips some when the slots are too narrow.
 * Each bar carries its own content description ("Mon 5 Jan: 1 min").
 */
@Composable
internal fun DayBarsChart(bars: List<ChartBar>, metric: ChartMetric, weekly: Boolean, modifier: Modifier = Modifier) {
    val colors = KidTubeTheme.colors
    val locale = LocalConfiguration.current.locales[0]
    val names = weekdayLabels()
    val labels = remember(bars, names, weekly) {
        bars.map { if (weekly) names[it.date.dayOfWeek.value - 1] else it.date.dayOfMonth.toString() }
    }
    val dayFormat = remember(locale) { DateTimeFormatter.ofPattern("EEE d MMM", locale) }
    val max = bars.maxOfOrNull { it.value } ?: 0
    val resources = LocalContext.current.resources
    BoxWithConstraints(modifier.fillMaxWidth().testTag("report_bars")) {
        val slot = maxWidth / bars.size.coerceAtLeast(1)
        val shown = remember(bars.size, slot, labels) { labelIndices(bars.size, slot.value, estimatedLabelWidth(labels)) }
        Column {
            Row(Modifier.fillMaxWidth().height(PLOT_HEIGHT), verticalAlignment = Alignment.Bottom) {
                bars.forEach { bar ->
                    val valueText = if (metric == ChartMetric.WATCH_TIME) {
                        resources.durationText(bar.value)
                    } else {
                        resources.getQuantityString(R.plurals.reports_views, bar.value, bar.value)
                    }
                    val description = stringResource(R.string.reports_bar_description, dayFormat.format(bar.date), valueText)
                    Box(
                        Modifier.weight(1f).fillMaxHeight().testTag("report_bar_${bar.date}").semantics { contentDescription = description },
                        contentAlignment = Alignment.BottomCenter,
                    ) {
                        val height = barHeight(bar.value, max, PLOT_HEIGHT.value, MIN_BAR.value).dp
                        Box(
                            Modifier
                                .fillMaxWidth(if (bars.size > 14) 0.7f else 0.55f)
                                .height(height)
                                .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                                .background(if (bar.value > 0) colors.primary else colors.muted.copy(alpha = 0.55f))
                                .testTag("report_fill_${bar.date}"),
                        )
                    }
                }
            }
            HorizontalDivider(color = colors.line, thickness = 2.dp)
            Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                labels.forEachIndexed { index, text ->
                    Box(Modifier.weight(1f), contentAlignment = Alignment.TopCenter) {
                        if (index in shown) {
                            LatinAwareText(
                                text,
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.muted,
                                maxLines = 1,
                                overflow = TextOverflow.Visible,
                                modifier = Modifier.wrapContentWidth(unbounded = true).testTag("report_label_$index"),
                            )
                        }
                    }
                }
            }
        }
    }
}

// ---- lists --------------------------------------------------------------------------------------------------------------------

@Composable
private fun VideosCard(report: ReportDto, range: ReportRange) {
    val colors = KidTubeTheme.colors
    ReportCard(Modifier.testTag("report_top")) {
        SectionTitle(stringResource(if (range == ReportRange.DAY) R.string.reports_day_videos_title else R.string.reports_top_title))
        report.topVideos.forEach { video ->
            Row(
                Modifier.fillMaxWidth().testTag("top_${video.videoId}"),
                horizontalArrangement = Arrangement.spacedBy(KidTubeDimens.gap12),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                VideoThumbnail(url = video.thumbnailUrl, seed = video.videoId)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    LatinAwareText(
                        video.title ?: stringResource(R.string.reports_untitled),
                        style = MaterialTheme.typography.titleMedium, color = colors.ink, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                    LatinAwareText(
                        stringResource(
                            R.string.reports_video_line,
                            pluralStringResource(R.plurals.reports_views, video.views, video.views),
                            durationText(video.watchSeconds),
                        ),
                        style = MaterialTheme.typography.bodyMedium, color = colors.muted,
                    )
                }
            }
        }
    }
}

@Composable
private fun ProblemsCard(report: ReportDto) {
    val colors = KidTubeTheme.colors
    Surface(
        shape = KidTubeShapes.card,
        color = colors.errorBg.copy(alpha = 0.45f),
        border = BorderStroke(KidTubeDimens.borderWidth, colors.errorBg),
        modifier = Modifier.fillMaxWidth().testTag("report_problems"),
    ) {
        Column(Modifier.padding(KidTubeDimens.gap16), verticalArrangement = Arrangement.spacedBy(KidTubeDimens.gap12)) {
            SectionTitle(stringResource(R.string.reports_problems_title), colors.errorText)
            LatinAwareText(stringResource(R.string.reports_problems_hint), style = MaterialTheme.typography.bodyMedium, color = colors.errorText)
            report.playbackErrors.forEach { error ->
                Column(Modifier.testTag("problem_${error.videoId}_${error.code}"), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    LatinAwareText(
                        error.title ?: stringResource(R.string.reports_untitled),
                        style = MaterialTheme.typography.titleMedium, color = colors.ink, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                    LatinAwareText(
                        stringResource(R.string.reports_problem_line, error.code, pluralStringResource(R.plurals.reports_times, error.count, error.count)),
                        style = MaterialTheme.typography.bodyMedium, color = colors.errorText,
                    )
                }
            }
        }
    }
}

@Composable
private fun PlanProgressCard(report: ReportDto) {
    val colors = KidTubeTheme.colors
    ReportCard(Modifier.testTag("report_plan")) {
        SectionTitle(stringResource(R.string.reports_plan_title))
        report.plannedVsWatched?.takeIf { it.activeDays > 0 }?.let { planned ->
            LatinAwareText(
                if (planned.unit == "seconds") {
                    stringResource(R.string.reports_plan_watched_time, durationText(planned.actual), durationText(planned.expected))
                } else {
                    stringResource(R.string.reports_plan_watched_videos, planned.actual, planned.expected)
                },
                style = MaterialTheme.typography.bodyLarge, color = colors.ink, modifier = Modifier.testTag("plan_vs_watched"),
            )
        }
        if (report.perVideo.isNotEmpty()) {
            LatinAwareText(
                stringResource(R.string.reports_plan_finished, report.finishedItems(), report.perVideo.size),
                style = MaterialTheme.typography.bodyLarge, color = colors.ink, modifier = Modifier.testTag("plan_finished"),
            )
        }
        report.planProgress?.takeIf { it.remainingFiniteViews > 0 }?.let { progress ->
            LatinAwareText(
                pluralStringResource(R.plurals.reports_plan_remaining, progress.remainingFiniteViews, progress.remainingFiniteViews),
                style = MaterialTheme.typography.bodyLarge, color = colors.ink,
            )
            LatinAwareText(
                progress.finishDate?.let { stringResource(R.string.reports_plan_finish, formatDate(it)) }
                    ?: stringResource(R.string.reports_plan_no_finish),
                style = MaterialTheme.typography.bodyLarge, color = colors.ink, modifier = Modifier.testTag("plan_finish"),
            )
        }
        if (report.perVideo.isNotEmpty()) {
            HorizontalDivider(color = colors.line)
            report.perVideo.forEach { item ->
                Row(
                    Modifier.fillMaxWidth().testTag("plan_item_${item.itemId}"),
                    horizontalArrangement = Arrangement.spacedBy(KidTubeDimens.gap12),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LatinAwareText(
                        item.title ?: stringResource(R.string.reports_untitled),
                        style = MaterialTheme.typography.bodyLarge, color = colors.ink, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    LatinAwareText(
                        when {
                            item.completed -> stringResource(R.string.reports_item_done)
                            item.viewTarget != null -> stringResource(R.string.reports_item_progress, item.viewsTotal, item.viewTarget)
                            else -> pluralStringResource(R.plurals.reports_views, item.viewsTotal, item.viewsTotal)
                        },
                        style = MaterialTheme.typography.labelLarge,
                        color = if (item.completed) colors.verifiedText else colors.muted,
                    )
                }
            }
        }
    }
}

// ---- states -------------------------------------------------------------------------------------------------------------------

@Composable
private fun FailureState(kind: ReportFailureKind, onRetry: () -> Unit) {
    val (title, body) = when (kind) {
        ReportFailureKind.NO_CONNECTION -> R.string.reports_offline_title to R.string.reports_offline_body
        ReportFailureKind.SERVER_UNREACHABLE -> R.string.reports_server_title to R.string.reports_server_body
        ReportFailureKind.RATE_LIMITED -> R.string.reports_error_title to R.string.error_rate_limited_unknown
        ReportFailureKind.NOT_FOUND, ReportFailureKind.UNKNOWN -> R.string.reports_error_title to R.string.reports_error_body
    }
    ReportCard(Modifier.testTag(if (kind == ReportFailureKind.NO_CONNECTION) "report_offline" else "report_failure")) {
        SectionTitle(stringResource(title))
        MutedText(stringResource(body))
        PrimaryButton(text = stringResource(R.string.reports_retry), onClick = onRetry, modifier = Modifier.fillMaxWidth().testTag("report_retry"))
    }
}

/** Static grey blocks in the shape of the summary cards and the chart (no animation, so nothing keeps the UI busy). */
@Composable
private fun LoadingPlaceholders() {
    val loading = stringResource(R.string.loading_description)
    Column(
        Modifier.testTag("report_loading").semantics { contentDescription = loading },
        verticalArrangement = Arrangement.spacedBy(KidTubeDimens.gap12),
    ) {
        repeat(2) {
            Row(horizontalArrangement = Arrangement.spacedBy(KidTubeDimens.gap12)) {
                PlaceholderBlock(88.dp, Modifier.weight(1f))
                PlaceholderBlock(88.dp, Modifier.weight(1f))
            }
        }
        PlaceholderBlock(220.dp, Modifier.fillMaxWidth())
    }
}

@Composable
private fun PlaceholderBlock(height: Dp, modifier: Modifier) {
    Box(modifier.height(height).clip(KidTubeShapes.card).background(KidTubeTheme.colors.line.copy(alpha = 0.6f)))
}
