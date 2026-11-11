package com.bbro.kidtube.ui.plans

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bbro.kidtube.R
import com.bbro.kidtube.data.remote.OnMiss
import com.bbro.kidtube.data.remote.PaceType
import com.bbro.kidtube.data.repo.PlanFailure
import com.bbro.kidtube.data.repo.PlanFailureKind
import com.bbro.kidtube.engine.DayStatus
import com.bbro.kidtube.ui.components.Chip
import com.bbro.kidtube.ui.components.LatinAwareText
import com.bbro.kidtube.ui.components.MessageBanner
import com.bbro.kidtube.ui.components.PrimaryButton
import com.bbro.kidtube.ui.components.StatusBadge
import com.bbro.kidtube.ui.components.VideoThumbnail
import com.bbro.kidtube.ui.theme.KidTubeDimens
import com.bbro.kidtube.ui.theme.KidTubeShapes
import com.bbro.kidtube.ui.theme.KidTubeTheme
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

internal const val PLANS_LIST_TAG = "plans_list"

/** Every action the Plans tab can ask for. A plain holder so the content can be rendered in tests with no-ops. */
class PlansActions(
    val onSelectChild: (String) -> Unit = {},
    val onAddChild: () -> Unit = {},
    val onCreateDefaultPlan: (String) -> Unit = {},
    val onMove: (childId: String, itemId: String, up: Boolean) -> Unit = { _, _, _ -> },
    val onEditItem: (itemId: String, required: Boolean, target: Int?, cap: Int?, onDone: () -> Unit) -> Unit = { _, _, _, _, _ -> },
    val onRemove: (itemId: String, onDone: () -> Unit) -> Unit = { _, _ -> },
    val onAddVideo: (childId: String, videoId: String, onDone: () -> Unit) -> Unit = { _, _, _ -> },
    val onSaveSettings: (childId: String, input: SettingsInput, onSaved: () -> Unit) -> Unit = { _, _, _ -> },
    val onGiveExtraTime: (childId: String, minutes: Int) -> Unit = { _, _ -> },
    val onClearError: () -> Unit = {},
    val onClearSettingsMessage: () -> Unit = {},
)

/** The Plans tab: the stateful wrapper around [PlansContent]. */
@Composable
fun PlansTab(onAddChild: () -> Unit, viewModel: PlansViewModel = hiltViewModel()) {
    val state = viewModel.state.collectAsStateWithLifecycle().value
    val error = viewModel.error.collectAsStateWithLifecycle().value
    val settingsMessage = viewModel.settingsMessage.collectAsStateWithLifecycle().value
    val snackbar = remember { SnackbarHostState() }
    val extraAdded = stringResource(R.string.plans_extra_added)
    LaunchedEffect(viewModel) {
        viewModel.uiEvents.collect { event ->
            when (event) {
                is PlansEvent.ExtraTimeAdded -> snackbar.showSnackbar(extraAdded)
            }
        }
    }
    val actions = remember(viewModel, onAddChild) {
        PlansActions(
            onSelectChild = viewModel::selectChild,
            onAddChild = onAddChild,
            onCreateDefaultPlan = viewModel::createDefaultPlan,
            onMove = viewModel::move,
            onEditItem = { id, required, target, cap, done -> viewModel.editItem(id, required, target, cap, done) },
            onRemove = { id, done -> viewModel.remove(id, done) },
            onAddVideo = { child, video, done -> viewModel.addVideo(child, video, done) },
            onSaveSettings = viewModel::saveSettings,
            onGiveExtraTime = viewModel::giveExtraTime,
            onClearError = viewModel::clearError,
            onClearSettingsMessage = viewModel::clearSettingsMessage,
        )
    }
    PlansContent(state, error, settingsMessage, snackbar, actions)
}

/** The Plans tab, stateless. */
@Composable
fun PlansContent(
    state: PlansUiState,
    error: PlanFailure?,
    settingsMessage: SettingsMessage?,
    snackbar: SnackbarHostState,
    actions: PlansActions,
) {
    val colors = KidTubeTheme.colors
    var editingSettings by remember { mutableStateOf(false) }
    var editingItem by remember { mutableStateOf<PlanItemRow?>(null) }
    var removing by remember { mutableStateOf<PlanItemRow?>(null) }
    var adding by remember { mutableStateOf(false) }
    val model = state.model

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Text(
                text = stringResource(R.string.nav_plans),
                style = MaterialTheme.typography.headlineLarge,
                color = colors.ink,
                modifier = Modifier.padding(start = KidTubeDimens.screenPadding, end = KidTubeDimens.screenPadding, top = KidTubeDimens.gap16).semantics { heading() },
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
            }
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = colors.primary, trackColor = colors.primarySoft)

            when {
                !state.loaded -> Unit
                model == null -> EmptyNoChildren(actions.onAddChild)
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize().testTag(PLANS_LIST_TAG),
                    contentPadding = PaddingValues(start = KidTubeDimens.screenPadding, end = KidTubeDimens.screenPadding, top = KidTubeDimens.gap8, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(KidTubeDimens.gap12),
                ) {
                    if (error != null) item(key = "error") { MessageBanner(planFailureText(error)) }
                    if (model.plan == null) {
                        item(key = "noplan") { NoPlanCard(state.busy) { actions.onCreateDefaultPlan(model.child.id) } }
                    } else {
                        item(key = "summary") {
                            PlanSummaryCard(
                                model = model,
                                busy = state.busy,
                                onEdit = { actions.onClearSettingsMessage(); editingSettings = true },
                                onExtra = { minutes -> actions.onGiveExtraTime(model.child.id, minutes) },
                            )
                        }
                        item(key = "items-header") { ItemsHeader(canAdd = !state.busy) { actions.onClearError(); adding = true } }
                        if (model.items.isEmpty()) {
                            item(key = "items-empty") { MutedText(stringResource(R.string.plans_items_empty)) }
                        }
                        items(model.items, key = { "item-${it.id}" }) { row ->
                            PlanItemCard(
                                row = row,
                                busy = state.busy,
                                onUp = { actions.onMove(model.child.id, row.id, true) },
                                onDown = { actions.onMove(model.child.id, row.id, false) },
                                onEdit = { actions.onClearError(); editingItem = row },
                                onRemove = { actions.onClearError(); removing = row },
                            )
                        }
                        item(key = "next") { WhatHappensNext(model) }
                    }
                }
            }
        }
        SnackbarHost(snackbar, modifier = Modifier.align(Alignment.BottomCenter).padding(KidTubeDimens.gap16))
    }

    if (editingSettings && model?.plan != null) {
        PlanSettingsForm(
            plan = model.plan,
            dailyAppMinutes = model.dailyAppMinutes,
            saving = state.busy,
            message = settingsMessage,
            onSave = { input -> actions.onSaveSettings(model.child.id, input) { editingSettings = false } },
            onDismiss = { actions.onClearSettingsMessage(); editingSettings = false },
        )
    }
    editingItem?.let { row ->
        ItemEditDialog(
            row = row,
            busy = state.busy,
            error = error,
            onSave = { required, target, cap -> actions.onEditItem(row.id, required, target, cap) { editingItem = null } },
            onDismiss = { actions.onClearError(); editingItem = null },
        )
    }
    removing?.let { row ->
        RemoveItemDialog(
            row = row,
            childName = model?.child?.name.orEmpty(),
            busy = state.busy,
            error = error,
            onConfirm = { actions.onRemove(row.id) { removing = null } },
            onDismiss = { actions.onClearError(); removing = null },
        )
    }
    if (adding && model != null) {
        AddVideoDialog(
            videos = model.pickable,
            busy = state.busy,
            error = error,
            onPick = { video -> actions.onAddVideo(model.child.id, video.id) { adding = false } },
            onDismiss = { actions.onClearError(); adding = false },
        )
    }
}

// ---- pieces ---------------------------------------------------------------------------------------------------------

@Composable
private fun EmptyNoChildren(onAddChild: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(KidTubeDimens.screenPadding), verticalArrangement = Arrangement.spacedBy(KidTubeDimens.gap16)) {
        MutedText(stringResource(R.string.plans_no_children))
        PrimaryButton(text = stringResource(R.string.add_child_action), onClick = onAddChild, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun NoPlanCard(busy: Boolean, onCreate: () -> Unit) {
    CardSurface {
        MutedText(stringResource(R.string.plans_no_plan))
        PrimaryButton(text = stringResource(R.string.child_create_default_plan), onClick = onCreate, loading = busy, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun CardSurface(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val colors = KidTubeTheme.colors
    Surface(shape = KidTubeShapes.card, color = colors.card, border = BorderStroke(KidTubeDimens.borderWidth, colors.line), modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(KidTubeDimens.gap16), verticalArrangement = Arrangement.spacedBy(KidTubeDimens.gap12)) { content() }
    }
}

@Composable
internal fun MutedText(text: String, modifier: Modifier = Modifier) {
    LatinAwareText(text, style = MaterialTheme.typography.bodyMedium, color = KidTubeTheme.colors.muted, modifier = modifier)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlanSummaryCard(model: PlanScreenModel, busy: Boolean, onEdit: () -> Unit, onExtra: (Int) -> Unit) {
    val colors = KidTubeTheme.colors
    val plan = model.plan ?: return
    CardSurface(Modifier.testTag("plan_summary")) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(KidTubeDimens.gap8)) {
            Text(
                stringResource(R.string.plans_plan_title),
                style = MaterialTheme.typography.titleLarge,
                color = colors.ink,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            SmallButton(stringResource(R.string.plans_edit), onClick = onEdit, enabled = !busy, modifier = Modifier.testTag("plan_edit"))
        }
        LatinAwareText(paceText(plan), style = MaterialTheme.typography.titleMedium, color = colors.ink)
        WeekdayChips(plan.activeWeekdays)
        val dates = buildString {
            append(stringResource(R.string.plans_starts, formatDate(plan.startDate)))
            append(" · ")
            append(plan.endDate?.let { stringResource(R.string.plans_ends, formatDate(it)) } ?: stringResource(R.string.plans_no_end))
        }
        LatinAwareText(dates, style = MaterialTheme.typography.bodyMedium, color = colors.ink)
        LabelValue(stringResource(R.string.plans_catchup_label), catchUpText(plan))
        LabelValue(
            stringResource(R.string.plans_limit_label),
            model.dailyAppMinutes?.let { pluralStringResource(R.plurals.plans_limit_minutes, it, it) } ?: stringResource(R.string.plans_limit_none),
        )
        HorizontalDivider(color = colors.line)
        if (model.dailyAppMinutes != null) {
            Text(stringResource(R.string.plans_extra_title), style = MaterialTheme.typography.titleMedium, color = colors.ink)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(KidTubeDimens.gap8), verticalArrangement = Arrangement.spacedBy(KidTubeDimens.gap8)) {
                EXTRA_TIME_MINUTES.forEach { minutes ->
                    SmallButton(
                        stringResource(R.string.plans_extra_option, minutes),
                        onClick = { onExtra(minutes) },
                        modifier = Modifier.testTag("extra_$minutes"),
                    )
                }
            }
        } else {
            MutedText(stringResource(R.string.plans_extra_hint), Modifier.testTag("extra_hint"))
        }
    }
}

/** The extra-time choices on the plan card. */
internal val EXTRA_TIME_MINUTES = listOf(10, 15, 30)

@Composable
internal fun paceText(plan: PlanSummary): String = when (plan.paceType) {
    PaceType.VIDEOS_PER_DAY -> pluralStringResource(R.plurals.plans_pace_videos, plan.paceValue, plan.paceValue)
    PaceType.MINUTES_PER_DAY -> pluralStringResource(R.plurals.plans_pace_minutes, plan.paceValue, plan.paceValue)
    PaceType.UNKNOWN -> ""
}

@Composable
private fun catchUpText(plan: PlanSummary): String {
    val minutes = plan.paceType == PaceType.MINUTES_PER_DAY
    val n = plan.maxExtraPerDay
    return when {
        n == 0 -> stringResource(if (minutes) R.string.plans_catchup_none_minutes else R.string.plans_catchup_none)
        minutes -> pluralStringResource(R.plurals.plans_catchup_minutes, n, n)
        else -> pluralStringResource(R.plurals.plans_catchup_videos, n, n)
    }
}

@Composable
private fun LabelValue(label: String, value: String) {
    val colors = KidTubeTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = colors.muted)
        LatinAwareText(value, style = MaterialTheme.typography.bodyLarge, color = colors.ink)
    }
}

/** The seven weekday labels, Monday first (resource strings, so Urdu names in the Urdu UI). */
@Composable
internal fun weekdayLabels(): List<String> = listOf(
    R.string.plans_weekday_mon, R.string.plans_weekday_tue, R.string.plans_weekday_wed, R.string.plans_weekday_thu,
    R.string.plans_weekday_fri, R.string.plans_weekday_sat, R.string.plans_weekday_sun,
).map { stringResource(it) }

/** Read-only weekday chips: the active days are filled. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WeekdayChips(mask: Int) {
    val colors = KidTubeTheme.colors
    val names = weekdayLabels()
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.testTag("weekday_chips")) {
        names.forEachIndexed { index, name ->
            val on = Weekdays.isSet(mask, index)
            Surface(
                shape = KidTubeShapes.chip,
                color = if (on) colors.primarySoft else colors.card,
                border = BorderStroke(if (on) KidTubeDimens.selectedBorderWidth else KidTubeDimens.borderWidth, if (on) colors.primary else colors.line),
                modifier = Modifier.semantics { stateDescription = if (on) "on" else "off" }.testTag("weekday_$index"),
            ) {
                LatinAwareText(
                    name,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (on) colors.ink else colors.muted,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
    }
}

/** A compact outlined button (48dp high) for secondary actions. */
@Composable
internal fun SmallButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val colors = KidTubeTheme.colors
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = KidTubeShapes.chip,
        color = colors.card,
        contentColor = if (enabled) colors.ink else colors.muted,
        border = BorderStroke(KidTubeDimens.selectedBorderWidth, if (enabled) colors.primary else colors.line),
        modifier = modifier,
    ) {
        LatinAwareText(
            text,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 18.dp).padding(vertical = 12.dp),
        )
    }
}

@Composable
private fun ItemsHeader(canAdd: Boolean, onAdd: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(KidTubeDimens.gap8)) {
        Text(
            stringResource(R.string.plans_items_title),
            style = MaterialTheme.typography.titleLarge,
            color = KidTubeTheme.colors.ink,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        SmallButton(stringResource(R.string.plans_add_video), onClick = onAdd, enabled = canAdd, modifier = Modifier.testTag("plan_add_video"))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlanItemCard(row: PlanItemRow, busy: Boolean, onUp: () -> Unit, onDown: () -> Unit, onEdit: () -> Unit, onRemove: () -> Unit) {
    val colors = KidTubeTheme.colors
    Surface(
        shape = KidTubeShapes.card,
        color = colors.card,
        border = BorderStroke(KidTubeDimens.borderWidth, colors.line),
        modifier = Modifier.fillMaxWidth().testTag("plan_item_${row.id}"),
    ) {
        Column(Modifier.padding(KidTubeDimens.gap12), verticalArrangement = Arrangement.spacedBy(KidTubeDimens.gap8)) {
            Row(horizontalArrangement = Arrangement.spacedBy(KidTubeDimens.gap12), verticalAlignment = Alignment.Top) {
                VideoThumbnail(url = row.thumbnailUrl, seed = row.id)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    LatinAwareText(row.title, style = MaterialTheme.typography.titleMedium, color = colors.ink, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (row.required) RequiredBadge()
                        TargetBadge(row)
                        row.dailyCap?.let { TagBadge(stringResource(R.string.plans_badge_cap, it)) }
                        StatusBadge(row.status)
                    }
                    if (row.anchorDate != null) {
                        val miss = when (row.onMiss) {
                            OnMiss.SKIP -> R.string.plans_on_miss_skip
                            OnMiss.DROP -> R.string.plans_on_miss_drop
                            else -> R.string.plans_on_miss_shift
                        }
                        MutedText(stringResource(R.string.plans_badge_anchor, formatDate(row.anchorDate)) + " · " + stringResource(miss))
                    }
                    if (row.facebook) {
                        LatinAwareText(stringResource(R.string.facebook_parents_only), style = MaterialTheme.typography.labelMedium, color = colors.pendingText)
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                RowIcon(Icons.Filled.KeyboardArrowUp, stringResource(R.string.plans_move_up), row.canMoveUp && !busy, onUp, "up_${row.id}")
                RowIcon(Icons.Filled.KeyboardArrowDown, stringResource(R.string.plans_move_down), row.canMoveDown && !busy, onDown, "down_${row.id}")
                RowIcon(Icons.Filled.Edit, stringResource(R.string.plans_edit_item), !busy, onEdit, "edit_${row.id}")
                RowIcon(Icons.Filled.Delete, stringResource(R.string.plans_remove_item), !busy, onRemove, "remove_${row.id}")
            }
        }
    }
}

@Composable
private fun RowIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, enabled: Boolean, onClick: () -> Unit, tag: String) {
    val colors = KidTubeTheme.colors
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(KidTubeDimens.minTouchTarget).testTag(tag)) {
        Icon(icon, contentDescription = description, tint = if (enabled) colors.ink else colors.muted.copy(alpha = 0.45f))
    }
}

@Composable
private fun RequiredBadge() {
    val colors = KidTubeTheme.colors
    Surface(shape = KidTubeShapes.chip, color = colors.sun, contentColor = colors.ink) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(Icons.Filled.Star, contentDescription = null, modifier = Modifier.size(14.dp))
            LatinAwareText(stringResource(R.string.plans_badge_required), style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun TargetBadge(row: PlanItemRow) {
    val text = when {
        row.viewTarget != null -> pluralStringResource(R.plurals.plans_badge_times, row.viewTarget, row.viewTarget)
        row.required -> pluralStringResource(R.plurals.plans_badge_times, 1, 1) // a required item without a target counts once
        else -> stringResource(R.string.plans_badge_repeats)
    }
    TagBadge(text)
}

@Composable
internal fun TagBadge(text: String, background: Color = KidTubeTheme.colors.line.copy(alpha = 0.55f)) {
    Surface(shape = KidTubeShapes.chip, color = background, contentColor = KidTubeTheme.colors.ink) {
        LatinAwareText(text, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
    }
}

/** "What happens next": the engine's projection for the next [PROJECTION_DAYS] days, read-only. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WhatHappensNext(model: PlanScreenModel) {
    val colors = KidTubeTheme.colors
    val weekdays = weekdayLabels()
    CardSurface(Modifier.testTag("plan_next")) {
        Text(stringResource(R.string.plans_next_title), style = MaterialTheme.typography.titleLarge, color = colors.ink, modifier = Modifier.semantics { heading() })
        model.days.forEach { day ->
            Row(Modifier.fillMaxWidth().testTag("day_${day.date}"), horizontalArrangement = Arrangement.spacedBy(KidTubeDimens.gap12)) {
                Column(Modifier.width(76.dp)) {
                    LatinAwareText(weekdays[day.date.dayOfWeek.value - 1], style = MaterialTheme.typography.titleSmall, color = colors.ink)
                    LatinAwareText(formatDayMonth(day.date), style = MaterialTheme.typography.labelMedium, color = colors.muted)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    when {
                        day.status == DayStatus.DAY_OFF -> MutedText(stringResource(R.string.plans_next_day_off))
                        day.status == DayStatus.OUTSIDE_DATES -> MutedText(stringResource(R.string.plans_next_outside))
                        day.entries.isEmpty() -> MutedText(stringResource(R.string.plans_next_nothing))
                        else -> day.entries.forEach { entry ->
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                if (entry.required) {
                                    Icon(Icons.Filled.Star, contentDescription = stringResource(R.string.plans_badge_required), tint = colors.coral, modifier = Modifier.size(16.dp))
                                } else {
                                    Spacer(Modifier.size(16.dp)) // keeps every title on the same line
                                }
                                LatinAwareText(entry.title, style = MaterialTheme.typography.bodyMedium, color = colors.ink)
                            }
                        }
                    }
                }
            }
        }
        if (model.unlimitedTitles.isNotEmpty()) {
            MutedText(stringResource(R.string.plans_next_unlimited, model.unlimitedTitles.joinToString(", ")), Modifier.testTag("next_unlimited"))
        }
    }
}

// ---- helpers --------------------------------------------------------------------------------------------------------

@Composable
internal fun formatDate(date: LocalDate): String {
    val locale = LocalConfiguration.current.locales[0]
    return remember(date, locale) { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).format(date) }
}

@Composable
private fun formatDayMonth(date: LocalDate): String {
    val locale = LocalConfiguration.current.locales[0]
    return remember(date, locale) { DateTimeFormatter.ofPattern("d MMM", locale).format(date) }
}

@Composable
internal fun planFailureText(failure: PlanFailure): String = when (failure.kind) {
    PlanFailureKind.NO_CONNECTION -> stringResource(R.string.plans_error_no_connection)
    PlanFailureKind.SERVER_UNREACHABLE -> stringResource(R.string.plans_error_server)
    PlanFailureKind.ALREADY_IN_PLAN -> stringResource(R.string.plans_error_already)
    PlanFailureKind.NOT_FOUND -> stringResource(R.string.plans_error_not_found)
    PlanFailureKind.INVALID ->
        failure.serverMessage?.let { stringResource(R.string.plans_error_invalid, it) } ?: stringResource(R.string.plans_error_invalid_generic)
    PlanFailureKind.RATE_LIMITED -> stringResource(R.string.error_rate_limited_unknown)
    PlanFailureKind.UNKNOWN -> stringResource(R.string.child_error_unknown)
}
