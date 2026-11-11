package com.bbro.kidtube.share

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bbro.kidtube.R
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import com.bbro.kidtube.data.entity.ChildEntity
import com.bbro.kidtube.data.remote.Platform
import com.bbro.kidtube.ui.theme.KidTubeFonts
import com.bbro.kidtube.ui.children.AddChildDialog
import com.bbro.kidtube.ui.components.Chip
import com.bbro.kidtube.ui.components.EnglishAndUrduPreviews
import com.bbro.kidtube.ui.components.LatinAwareText
import com.bbro.kidtube.ui.components.MessageBanner
import com.bbro.kidtube.ui.components.PinField
import com.bbro.kidtube.ui.components.PreviewSurface
import com.bbro.kidtube.ui.components.PrimaryButton
import com.bbro.kidtube.ui.components.VideoThumbnail
import com.bbro.kidtube.ui.theme.KidTubeDimens
import com.bbro.kidtube.ui.theme.KidTubeShapes
import com.bbro.kidtube.ui.theme.KidTubeTheme
import kotlinx.coroutines.delay

/** How long the "Saved" confirmation stays before the share sheet closes. */
private const val SAVED_CLOSE_DELAY_MS = 1_400L

@Composable
fun ShareScreen(sharedText: String?, onClose: () -> Unit, viewModel: ShareViewModel = hiltViewModel()) {
    LaunchedEffect(viewModel, sharedText) { viewModel.start(sharedText) }
    val state = viewModel.state.collectAsStateWithLifecycle().value
    // While the PIN is locked, tick once a second so the banner counts down (and the lock lifts) by itself.
    LaunchedEffect(state.lockedSeconds > 0) {
        while (state.lockedSeconds > 0) {
            delay(1000)
            viewModel.refreshLock()
        }
    }
    LaunchedEffect(state.phase) {
        if (state.phase == SharePhase.SAVED) {
            delay(SAVED_CLOSE_DELAY_MS)
            onClose()
        }
    }
    ShareContent(
        state = state,
        onToggleChild = viewModel::onToggleChild,
        onToggleAll = viewModel::onToggleAll,
        onPreset = viewModel::onPreset,
        onPinChange = viewModel::onPinChange,
        onSave = viewModel::save,
        onCreatePlans = { viewModel.answerPlanOffer(true) },
        onSaveWithoutPlan = { viewModel.answerPlanOffer(false) },
        onClose = onClose,
        addChildDialog = { dismiss -> AddChildDialog(onDismiss = dismiss) },
    )
}

/** Stateless share sheet (screen 2 in docs/DESIGN.md). Every phase of [ShareUiState] is drawn here. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ShareContent(
    state: ShareUiState,
    onToggleChild: (String) -> Unit,
    onToggleAll: () -> Unit,
    onPreset: (SharePreset) -> Unit,
    onPinChange: (String) -> Unit,
    onSave: () -> Unit,
    onCreatePlans: () -> Unit,
    onSaveWithoutPlan: () -> Unit,
    onClose: () -> Unit,
    addChildDialog: @Composable (dismiss: () -> Unit) -> Unit = {},
) {
    val colors = KidTubeTheme.colors
    Surface(color = colors.ground, modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                // edge-to-edge: keep every phase of the sheet clear of the status bar, navigation bar, cutout and keyboard
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(KidTubeDimens.screenPadding),
            verticalArrangement = Arrangement.spacedBy(KidTubeDimens.gap16),
        ) {
            when (state.phase) {
                SharePhase.LOADING -> Unit
                SharePhase.NOT_PARENT -> Message(
                    title = stringResource(R.string.share_parent_only_title),
                    body = stringResource(R.string.share_parent_only_body),
                    onClose = onClose,
                )
                SharePhase.UNSUPPORTED -> Message(
                    title = stringResource(R.string.share_unsupported_title),
                    body = stringResource(R.string.share_unsupported_body),
                    onClose = onClose,
                )
                SharePhase.NO_CHILDREN -> {
                    var adding by remember { mutableStateOf(false) }
                    Message(
                        title = stringResource(R.string.share_add_child_first_title),
                        body = stringResource(R.string.share_add_child_first_body),
                        onClose = onClose,
                        actionText = stringResource(R.string.add_child_action),
                        onAction = { adding = true },
                    )
                    if (adding) addChildDialog { adding = false }
                }
                SharePhase.SAVED -> Saved(state.saved)
                SharePhase.FORM -> {
                    Text(
                        text = stringResource(R.string.share_title),
                        style = MaterialTheme.typography.headlineMedium,
                        color = colors.ink,
                        modifier = Modifier.semantics { heading() },
                    )
                    state.link?.let { LinkCard(it) }

                    SectionLabel(stringResource(R.string.share_who_label))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(KidTubeDimens.gap8), verticalArrangement = Arrangement.spacedBy(KidTubeDimens.gap8)) {
                        if (state.children.size > 1) {
                            Chip(text = stringResource(R.string.share_all_children), selected = state.allSelected, onClick = onToggleAll)
                        }
                        state.children.forEach { child ->
                            Chip(text = child.name, selected = child.id in state.selected, onClick = { onToggleChild(child.id) })
                        }
                    }

                    SectionLabel(stringResource(R.string.share_how_label))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(KidTubeDimens.gap8), verticalArrangement = Arrangement.spacedBy(KidTubeDimens.gap8)) {
                        SharePreset.entries.forEach { preset ->
                            Chip(
                                text = stringResource(preset.label),
                                selected = preset == state.preset,
                                onClick = { onPreset(preset) },
                                role = Role.RadioButton,
                            )
                        }
                    }

                    PinField(value = state.pin, onValueChange = onPinChange, label = stringResource(R.string.pin_label))
                    when {
                        state.lockedSeconds > 0 -> MessageBanner(pluralStringResource(R.plurals.pin_gate_locked, state.lockedSeconds.toInt(), state.lockedSeconds.toInt()))
                        state.attemptsLeft != null -> MessageBanner(pluralStringResource(R.plurals.pin_gate_wrong, state.attemptsLeft, state.attemptsLeft))
                    }
                    when (val note = state.note) {
                        is ShareNote.AlreadySaved -> MessageBanner(stringResource(R.string.share_already_saved, note.names.joinToString(", ")), isError = false)
                        ShareNote.AlreadyInLibrary -> MessageBanner(stringResource(R.string.share_already_in_library), isError = false)
                        null -> Unit
                    }
                    state.planOffer?.let { offer ->
                        PlanOfferCard(offer, busy = state.busy, offline = state.offline, onCreate = onCreatePlans, onSaveWithout = onSaveWithoutPlan)
                    }
                    if (state.offline) {
                        Text(stringResource(R.string.share_offline_note), style = MaterialTheme.typography.bodySmall, color = colors.muted)
                    }
                    if (state.planOffer == null) {
                        PrimaryButton(text = saveLabel(state), onClick = onSave, enabled = state.canSave, loading = state.busy)
                    }
                    TextButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.action_cancel), style = MaterialTheme.typography.titleMedium, color = colors.muted)
                    }
                }
            }
        }
    }
}

@Composable
private fun saveLabel(state: ShareUiState): String = when {
    !state.preset.addsPlanItem -> stringResource(R.string.share_save_library)
    state.selected.isEmpty() -> stringResource(R.string.share_save_generic)
    state.allSelected && state.children.size > 1 -> stringResource(R.string.share_save_all)
    else -> stringResource(R.string.share_save_for, state.selectedNames.joinToString(", "))
}

@Composable
private fun SectionLabel(text: String) {
    Text(text = text, style = MaterialTheme.typography.titleSmall, color = KidTubeTheme.colors.muted, modifier = Modifier.semantics { heading() })
}

@Composable
private fun LinkCard(link: ShareLink) {
    val colors = KidTubeTheme.colors
    Surface(
        shape = KidTubeShapes.card,
        color = colors.card,
        border = BorderStroke(KidTubeDimens.borderWidth, colors.line),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(KidTubeDimens.gap12),
            horizontalArrangement = Arrangement.spacedBy(KidTubeDimens.gap12),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            VideoThumbnail(url = link.thumbnailUrl, seed = link.key)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                LatinAwareText(
                    text = stringResource(if (link.platform == Platform.FACEBOOK) R.string.platform_facebook else R.string.platform_youtube),
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.muted,
                )
                LatinAwareText(
                    text = link.title ?: link.url.removePrefix("https://").removePrefix("http://"),
                    // a bare link is Latin and always reads left to right, also in the Urdu UI
                    style = MaterialTheme.typography.bodyMedium.let {
                        if (link.title == null) it.copy(fontFamily = KidTubeFonts.Latin, textDirection = TextDirection.Ltr) else it
                    },
                    color = colors.ink,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (link.platform == Platform.FACEBOOK) {
                    LatinAwareText(text = stringResource(R.string.facebook_parents_only), style = MaterialTheme.typography.labelMedium, color = colors.pendingText)
                }
            }
        }
    }
}

@Composable
private fun PlanOfferCard(offer: PlanOffer, busy: Boolean, offline: Boolean, onCreate: () -> Unit, onSaveWithout: () -> Unit) {
    val colors = KidTubeTheme.colors
    Surface(
        shape = KidTubeShapes.card,
        color = colors.card,
        border = BorderStroke(KidTubeDimens.selectedBorderWidth, colors.sun),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(KidTubeDimens.gap16), verticalArrangement = Arrangement.spacedBy(KidTubeDimens.gap12)) {
            LatinAwareText(stringResource(R.string.plan_offer_title, offer.childNames.joinToString(", ")), style = MaterialTheme.typography.titleMedium, color = colors.ink)
            Text(stringResource(R.string.plan_offer_body), style = MaterialTheme.typography.bodyMedium, color = colors.muted)
            if (offline) Text(stringResource(R.string.plan_offer_offline), style = MaterialTheme.typography.bodySmall, color = colors.pendingText)
            PrimaryButton(text = stringResource(R.string.plan_offer_create), onClick = onCreate, enabled = !busy)
            TextButton(onClick = onSaveWithout, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.plan_offer_without), style = MaterialTheme.typography.titleMedium, color = colors.ink)
            }
        }
    }
}

@Composable
private fun Message(title: String, body: String, onClose: () -> Unit, actionText: String? = null, onAction: () -> Unit = {}) {
    val colors = KidTubeTheme.colors
    LatinAwareText(title, style = MaterialTheme.typography.headlineMedium, color = colors.ink, modifier = Modifier.semantics { heading() })
    LatinAwareText(body, style = MaterialTheme.typography.bodyLarge, color = colors.muted)
    if (actionText != null) PrimaryButton(text = actionText, onClick = onAction)
    TextButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.share_close), style = MaterialTheme.typography.titleMedium, color = colors.ink)
    }
}

@Composable
private fun Saved(info: SavedInfo?) {
    val colors = KidTubeTheme.colors
    LatinAwareText(stringResource(R.string.share_saved_title), style = MaterialTheme.typography.headlineMedium, color = colors.ink, modifier = Modifier.semantics { heading() })
    if (info == null) return
    if (info.libraryOnly) LatinAwareText(stringResource(R.string.share_saved_library), style = MaterialTheme.typography.bodyLarge, color = colors.muted)
    if (info.skippedNames.isNotEmpty()) {
        LatinAwareText(stringResource(R.string.share_saved_skipped, info.skippedNames.joinToString(", ")), style = MaterialTheme.typography.bodyLarge, color = colors.muted)
    }
    if (info.waitingForPlanNames.isNotEmpty()) {
        MessageBanner(stringResource(R.string.share_saved_waiting_plan, info.waitingForPlanNames.joinToString(", ")), isError = false)
    }
}

@EnglishAndUrduPreviews
@Composable
private fun ShareContentPreview() {
    PreviewSurface {
        ShareContent(
            state = ShareUiState(
                phase = SharePhase.FORM,
                link = ShareLink("https://youtu.be/dQw4w9WgXcQ", "youtube:dQw4w9WgXcQ", Platform.YOUTUBE, null, null),
                children = listOf(ChildEntity("1", "Ali", null, null), ChildEntity("2", "Sara", null, null)),
                selected = setOf("1"),
                pin = "12",
                offline = true,
            ),
            onToggleChild = {}, onToggleAll = {}, onPreset = {}, onPinChange = {}, onSave = {},
            onCreatePlans = {}, onSaveWithoutPlan = {}, onClose = {},
        )
    }
}
