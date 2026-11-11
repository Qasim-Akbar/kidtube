package com.bbro.kidtube.ui.plans

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.bbro.kidtube.R
import com.bbro.kidtube.data.remote.PaceType
import com.bbro.kidtube.data.repo.PlanFailure
import com.bbro.kidtube.data.repo.SavePart
import com.bbro.kidtube.ui.components.Chip
import com.bbro.kidtube.ui.components.LatinAwareText
import com.bbro.kidtube.ui.components.MessageBanner
import com.bbro.kidtube.ui.components.PrimaryButton
import com.bbro.kidtube.ui.components.Stepper
import com.bbro.kidtube.ui.components.VideoRow
import com.bbro.kidtube.ui.theme.KidTubeDimens
import com.bbro.kidtube.ui.theme.KidTubeShapes
import com.bbro.kidtube.ui.theme.KidTubeTheme
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

private const val MAX_TARGET = 99
private const val MAX_CAP = 20

// ---- plan settings form (full screen) ---------------------------------------------------------------------------------

/**
 * The plan settings form. Pace and catch-up use steppers, the weekdays are toggle chips, the dates use the Material date picker.
 * The values live in this composable, so after a partial save the form stays open with exactly what the parent entered.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlanSettingsForm(
    plan: PlanSummary,
    dailyAppMinutes: Int?,
    saving: Boolean,
    message: SettingsMessage?,
    onSave: (SettingsInput) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = KidTubeTheme.colors
    var paceType by remember { mutableStateOf(plan.paceType) }
    var pace by remember { mutableIntStateOf(plan.paceValue) }
    var mask by remember { mutableIntStateOf(plan.activeWeekdays) }
    var maxExtra by remember { mutableIntStateOf(plan.maxExtraPerDay) }
    var start by remember { mutableStateOf(plan.startDate) }
    var end by remember { mutableStateOf(plan.endDate) }
    var limit by remember { mutableIntStateOf(dailyAppMinutes ?: 0) } // 0 = no limit
    var pickingStart by remember { mutableStateOf(false) }
    var pickingEnd by remember { mutableStateOf(false) }
    val minutes = paceType == PaceType.MINUTES_PER_DAY
    val names = weekdayLabels()

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(color = colors.ground, modifier = Modifier.fillMaxSize().testTag("plan_form")) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().padding(KidTubeDimens.screenPadding), verticalArrangement = Arrangement.spacedBy(KidTubeDimens.gap12)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(KidTubeDimens.gap8)) {
                    Text(stringResource(R.string.plans_form_title), style = MaterialTheme.typography.headlineMedium, color = colors.ink, modifier = Modifier.weight(1f).semantics { heading() })
                    SmallButton(stringResource(R.string.action_cancel), onClick = onDismiss, modifier = Modifier.testTag("form_cancel"))
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(KidTubeDimens.gap16)) {
                    FormSection(stringResource(R.string.plans_form_count_by)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(KidTubeDimens.gap8)) {
                            Chip(stringResource(R.string.plans_form_videos), paceType == PaceType.VIDEOS_PER_DAY, onClick = {
                                if (paceType != PaceType.VIDEOS_PER_DAY) { paceType = PaceType.VIDEOS_PER_DAY; pace = 3; maxExtra = 1 }
                            }, role = Role.RadioButton, modifier = Modifier.testTag("pace_videos"))
                            Chip(stringResource(R.string.plans_form_minutes), minutes, onClick = {
                                if (!minutes) { paceType = PaceType.MINUTES_PER_DAY; pace = 30; maxExtra = 30 }
                            }, role = Role.RadioButton, modifier = Modifier.testTag("pace_minutes"))
                        }
                    }
                    FormSection(stringResource(R.string.plans_form_per_day)) {
                        val step = if (minutes) 5 else 1
                        val min = if (minutes) 5 else 1
                        val max = if (minutes) 480 else 20
                        Stepper(
                            valueText = if (minutes) pluralStringResource(R.plurals.plans_pace_minutes, pace, pace) else pluralStringResource(R.plurals.plans_pace_videos, pace, pace),
                            onDecrease = { pace = (pace - step).coerceAtLeast(min) },
                            onIncrease = { pace = (pace + step).coerceAtMost(max) },
                            canDecrease = pace > min, canIncrease = pace < max, testTag = "pace",
                        )
                    }
                    FormSection(stringResource(R.string.plans_form_days)) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(KidTubeDimens.gap8), verticalArrangement = Arrangement.spacedBy(KidTubeDimens.gap8)) {
                            names.forEachIndexed { index, name ->
                                val on = Weekdays.isSet(mask, index)
                                Chip(name, on, onClick = {
                                    val next = Weekdays.toggle(mask, index)
                                    if (next != 0) mask = next // at least one active day
                                }, modifier = Modifier.testTag("form_weekday_$index"))
                            }
                        }
                    }
                    FormSection(stringResource(R.string.plans_form_start)) {
                        SmallButton(formatDate(start), onClick = { pickingStart = true }, modifier = Modifier.testTag("pick_start"))
                    }
                    FormSection(stringResource(R.string.plans_form_end)) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(KidTubeDimens.gap8), verticalArrangement = Arrangement.spacedBy(KidTubeDimens.gap8)) {
                            val endDate = end
                            if (endDate == null) {
                                SmallButton(stringResource(R.string.plans_form_set_end), onClick = { pickingEnd = true }, modifier = Modifier.testTag("pick_end"))
                            } else {
                                SmallButton(formatDate(endDate), onClick = { pickingEnd = true }, modifier = Modifier.testTag("pick_end"))
                                SmallButton(stringResource(R.string.plans_form_clear_end), onClick = { end = null }, modifier = Modifier.testTag("clear_end"))
                            }
                        }
                    }
                    FormSection(stringResource(R.string.plans_catchup_label), hint = stringResource(if (minutes) R.string.plans_form_catchup_hint_minutes else R.string.plans_form_catchup_hint_videos)) {
                        val step = if (minutes) 5 else 1
                        val max = if (minutes) 240 else 20
                        Stepper(
                            valueText = when {
                                maxExtra == 0 -> stringResource(if (minutes) R.string.plans_catchup_none_minutes else R.string.plans_catchup_none)
                                minutes -> pluralStringResource(R.plurals.plans_catchup_minutes, maxExtra, maxExtra)
                                else -> pluralStringResource(R.plurals.plans_catchup_videos, maxExtra, maxExtra)
                            },
                            onDecrease = { maxExtra = (maxExtra - step).coerceAtLeast(0) },
                            onIncrease = { maxExtra = (maxExtra + step).coerceAtMost(max) },
                            canDecrease = maxExtra > 0, canIncrease = maxExtra < max, testTag = "catchup",
                        )
                    }
                    FormSection(stringResource(R.string.plans_limit_label), hint = stringResource(R.string.plans_form_limit_hint)) {
                        Stepper(
                            valueText = if (limit == 0) stringResource(R.string.plans_limit_none) else pluralStringResource(R.plurals.plans_limit_minutes, limit, limit),
                            onDecrease = { limit = if (limit <= 5) 0 else limit - 5 },
                            onIncrease = { limit = if (limit == 0) 30 else (limit + 5).coerceAtMost(480) },
                            canDecrease = limit > 0, canIncrease = limit < 480, testTag = "limit",
                        )
                    }
                }
                if (message != null) SettingsMessageBlock(message)
                PrimaryButton(
                    text = stringResource(R.string.plans_save),
                    onClick = { onSave(SettingsInput(paceType, pace, mask, maxExtra, start, end, limit.takeIf { it > 0 })) },
                    loading = saving,
                    modifier = Modifier.fillMaxWidth().testTag("form_save"),
                )
            }
        }
    }
    if (pickingStart) {
        DatePickDialog(initial = start, min = null, max = end, onPicked = { start = it; pickingStart = false }, onDismiss = { pickingStart = false })
    }
    if (pickingEnd) {
        DatePickDialog(initial = end ?: start, min = start, max = null, onPicked = { end = it; pickingEnd = false }, onDismiss = { pickingEnd = false })
    }
}

@Composable
private fun FormSection(label: String, hint: String? = null, content: @Composable () -> Unit) {
    val colors = KidTubeTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(KidTubeDimens.gap8)) {
        Text(label, style = MaterialTheme.typography.titleMedium, color = colors.ink)
        if (hint != null) MutedText(hint)
        content()
    }
}

/** Says exactly which part of the two-call save is stored and which is not. */
@Composable
internal fun SettingsMessageBlock(message: SettingsMessage) {
    val outcome = message.outcome
    Column(verticalArrangement = Arrangement.spacedBy(KidTubeDimens.gap8), modifier = Modifier.testTag("settings_message")) {
        when (val plan = outcome.plan) {
            SavePart.Saved -> MessageBanner(stringResource(R.string.plans_save_plan_saved), isError = false)
            is SavePart.Failed -> MessageBanner(stringResource(R.string.plans_save_plan_failed) + " " + planFailureText(plan.failure))
            SavePart.Unchanged, SavePart.NotTried -> Unit
        }
        when (val limit = outcome.limit) {
            SavePart.Saved -> MessageBanner(stringResource(R.string.plans_save_limit_saved), isError = false)
            is SavePart.Failed -> MessageBanner(stringResource(R.string.plans_save_limit_failed) + " " + planFailureText(limit.failure))
            SavePart.NotTried -> MessageBanner(stringResource(R.string.plans_save_limit_not_tried))
            SavePart.Unchanged -> Unit
        }
        MutedText(stringResource(R.string.plans_save_retry))
    }
}

/** Material date picker in a dialog; [min] and [max] bound the selectable days (inclusive). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DatePickDialog(initial: LocalDate, min: LocalDate?, max: LocalDate?, onPicked: (LocalDate) -> Unit, onDismiss: () -> Unit) {
    val state = rememberDatePickerState(
        initialSelectedDateMillis = initial.toUtcMillis(),
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long): Boolean {
                val day = utcTimeMillis.toUtcDate()
                return (min == null || !day.isBefore(min)) && (max == null || !day.isAfter(max))
            }
        },
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { state.selectedDateMillis?.let { onPicked(it.toUtcDate()) } ?: onDismiss() }) { Text(stringResource(R.string.action_ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    ) { DatePicker(state) }
}

internal fun LocalDate.toUtcMillis(): Long = atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

internal fun Long.toUtcDate(): LocalDate = Instant.ofEpochMilli(this).atZone(ZoneOffset.UTC).toLocalDate()

// ---- one item ---------------------------------------------------------------------------------------------------------

/** Required toggle, view target and daily cap for one plan item. Targets and caps use steppers. */
@Composable
fun ItemEditDialog(
    row: PlanItemRow,
    busy: Boolean,
    error: PlanFailure?,
    onSave: (required: Boolean, target: Int?, cap: Int?) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = KidTubeTheme.colors
    var required by remember { mutableStateOf(row.required) }
    var repeat by remember { mutableStateOf(!row.required && row.viewTarget == null) }
    var target by remember { mutableIntStateOf(row.viewTarget ?: 1) }
    var capOn by remember { mutableStateOf(row.dailyCap != null) }
    var cap by remember { mutableIntStateOf(row.dailyCap ?: 1) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = KidTubeShapes.card, color = colors.card, border = BorderStroke(KidTubeDimens.borderWidth, colors.line), modifier = Modifier.testTag("item_dialog")) {
            Column(Modifier.padding(KidTubeDimens.gap16), verticalArrangement = Arrangement.spacedBy(KidTubeDimens.gap16)) {
                Text(stringResource(R.string.plans_item_title), style = MaterialTheme.typography.titleLarge, color = colors.ink, modifier = Modifier.semantics { heading() })
                LatinAwareText(row.title, style = MaterialTheme.typography.bodyMedium, color = colors.muted, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                if (error != null) MessageBanner(planFailureText(error))

                Row(
                    Modifier.fillMaxWidth().toggleable(value = required, role = Role.Switch, onValueChange = { on ->
                        required = on
                        if (on) repeat = false // a required item counts once when no target is set, it never "repeats"
                    }).testTag("item_required"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(KidTubeDimens.gap12),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.plans_item_required), style = MaterialTheme.typography.titleMedium, color = colors.ink)
                        MutedText(stringResource(R.string.plans_item_required_hint))
                    }
                    Switch(
                        checked = required, onCheckedChange = null,
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = colors.primary, checkedThumbColor = colors.card, checkedBorderColor = colors.primary,
                            uncheckedTrackColor = colors.card, uncheckedThumbColor = colors.muted, uncheckedBorderColor = colors.muted,
                        ),
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(KidTubeDimens.gap8)) {
                    Text(stringResource(R.string.plans_item_target), style = MaterialTheme.typography.titleMedium, color = colors.ink)
                    if (!required) {
                        Chip(stringResource(R.string.plans_item_target_repeat), repeat, onClick = { repeat = !repeat }, modifier = Modifier.testTag("item_repeat"))
                    }
                    if (required || !repeat) {
                        Stepper(
                            valueText = pluralStringResource(R.plurals.plans_badge_times, target, target),
                            onDecrease = { target = (target - 1).coerceAtLeast(1) },
                            onIncrease = { target = (target + 1).coerceAtMost(MAX_TARGET) },
                            canDecrease = target > 1, canIncrease = target < MAX_TARGET, testTag = "target",
                        )
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(KidTubeDimens.gap8)) {
                    Text(stringResource(R.string.plans_item_cap), style = MaterialTheme.typography.titleMedium, color = colors.ink)
                    Chip(stringResource(R.string.plans_item_cap_none), !capOn, onClick = { capOn = !capOn }, modifier = Modifier.testTag("item_cap_none"))
                    if (capOn) {
                        Stepper(
                            valueText = pluralStringResource(R.plurals.plans_badge_times, cap, cap),
                            onDecrease = { cap = (cap - 1).coerceAtLeast(1) },
                            onIncrease = { cap = (cap + 1).coerceAtMost(MAX_CAP) },
                            canDecrease = cap > 1, canIncrease = cap < MAX_CAP, testTag = "cap",
                        )
                    }
                }

                PrimaryButton(
                    text = stringResource(R.string.plans_save),
                    onClick = { onSave(required, if (required || !repeat) target else null, if (capOn) cap else null) },
                    loading = busy,
                    modifier = Modifier.fillMaxWidth().testTag("item_save"),
                )
                SmallButton(stringResource(R.string.action_cancel), onClick = onDismiss, modifier = Modifier.fillMaxWidth().testTag("item_cancel"))
            }
        }
    }
}

@Composable
fun RemoveItemDialog(row: PlanItemRow, childName: String, busy: Boolean, error: PlanFailure?, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val colors = KidTubeTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.card,
        title = { Text(stringResource(R.string.plans_remove_title), color = colors.ink) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(KidTubeDimens.gap12)) {
                LatinAwareText(stringResource(R.string.plans_remove_body, row.title), style = MaterialTheme.typography.bodyLarge, color = colors.ink)
                if (error != null) MessageBanner(planFailureText(error))
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !busy, modifier = Modifier.testTag("remove_confirm")) {
                Text(stringResource(R.string.plans_remove_confirm), color = colors.errorText, style = MaterialTheme.typography.titleMedium)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("remove_cancel")) {
                Text(stringResource(R.string.action_cancel), color = colors.ink, style = MaterialTheme.typography.titleMedium)
            }
        },
    )
}

// ---- add video --------------------------------------------------------------------------------------------------------

/** Library videos that are not in this plan yet, with the same row look as the Library. Tapping one adds it. */
@Composable
fun AddVideoDialog(videos: List<PickableVideo>, busy: Boolean, error: PlanFailure?, onPick: (PickableVideo) -> Unit, onDismiss: () -> Unit) {
    val colors = KidTubeTheme.colors
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(color = colors.ground, modifier = Modifier.fillMaxSize().testTag("add_video_dialog")) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().padding(KidTubeDimens.screenPadding), verticalArrangement = Arrangement.spacedBy(KidTubeDimens.gap12)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(KidTubeDimens.gap8)) {
                    Text(stringResource(R.string.plans_pick_title), style = MaterialTheme.typography.headlineMedium, color = colors.ink, modifier = Modifier.weight(1f).semantics { heading() })
                    SmallButton(stringResource(R.string.action_cancel), onClick = onDismiss, modifier = Modifier.testTag("add_cancel"))
                }
                MutedText(stringResource(R.string.plans_pick_hint))
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = colors.primary, trackColor = colors.primarySoft)
                if (error != null) MessageBanner(planFailureText(error))
                if (videos.isEmpty()) {
                    MutedText(stringResource(R.string.plans_pick_empty))
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(KidTubeDimens.gap12)) {
                        items(videos, key = { it.id }) { video ->
                            Surface(
                                onClick = { onPick(video) },
                                enabled = !busy,
                                shape = KidTubeShapes.card,
                                color = colors.card,
                                modifier = Modifier.testTag("pick_${video.id}"),
                            ) {
                                VideoRow(
                                    title = video.title,
                                    status = video.status,
                                    thumbnailUrl = video.thumbnailUrl,
                                    seed = video.id,
                                    extraNote = if (video.facebook) stringResource(R.string.facebook_parents_only) else null,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
