package com.bbro.kidtube.ui.children

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.bbro.kidtube.R
import com.bbro.kidtube.data.repo.ChildAction
import com.bbro.kidtube.data.repo.ChildFailure
import com.bbro.kidtube.data.repo.ChildSetupRepository
import com.bbro.kidtube.data.repo.ChildrenRepository
import com.bbro.kidtube.data.repo.PlanRepository
import com.bbro.kidtube.ui.components.BackButton
import com.bbro.kidtube.ui.components.EnglishAndUrduPreviews
import com.bbro.kidtube.ui.components.KidTubeTextField
import com.bbro.kidtube.ui.components.MessageBanner
import com.bbro.kidtube.ui.components.PreviewSurface
import com.bbro.kidtube.ui.components.PrimaryButton
import com.bbro.kidtube.ui.theme.KidTubeDimens
import com.bbro.kidtube.ui.theme.KidTubeShapes
import com.bbro.kidtube.ui.theme.KidTubeTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ChildRow(val id: String, val name: String, val avatar: AvatarColor?, val hasPlan: Boolean)

data class ManageChildrenUiState(
    val rows: List<ChildRow> = emptyList(),
    /** The child an action (rename, create plan) is running for. */
    val busyChildId: String? = null,
    val error: ChildFailure? = null,
)

@HiltViewModel
class ManageChildrenViewModel @Inject constructor(
    childrenRepo: ChildrenRepository,
    planRepo: PlanRepository,
    private val childSetup: ChildSetupRepository,
) : ViewModel() {
    private val action = MutableStateFlow(Pair<String?, ChildFailure?>(null, null))

    val state: StateFlow<ManageChildrenUiState> = combine(childrenRepo.observeChildren(), planRepo.observeAll(), action) { children, plans, act ->
        val withPlan = plans.map { it.plan.childId }.toSet()
        ManageChildrenUiState(
            rows = children.map { ChildRow(it.id, it.name, AvatarColor.fromKey(it.avatar), it.id in withPlan) },
            busyChildId = act.first,
            error = act.second,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ManageChildrenUiState())

    fun rename(childId: String, name: String, onDone: () -> Unit) = run(childId, onDone) { childSetup.renameChild(childId, name) }

    /** "Create default plan" for a child that has none (needs a connection). */
    fun createDefaultPlan(childId: String) = run(childId) { childSetup.createDefaultPlan(childId) }

    fun clearError() = action.update { it.first to null }

    private fun run(childId: String, onDone: () -> Unit = {}, block: suspend () -> ChildAction<Unit>) {
        if (action.value.first != null) return
        action.value = childId to null
        viewModelScope.launch {
            when (val result = block()) {
                is ChildAction.Success -> {
                    action.value = null to null
                    onDone()
                }
                is ChildAction.Failed -> action.value = null to result.reason
            }
        }
    }
}

@Composable
fun ManageChildrenScreen(onBack: () -> Unit, viewModel: ManageChildrenViewModel = hiltViewModel()) {
    val state = viewModel.state.collectAsStateWithLifecycle().value
    var adding by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<ChildRow?>(null) }
    ManageChildrenContent(
        state = state,
        onBack = onBack,
        onAdd = { adding = true },
        onRename = { renaming = it },
        onCreatePlan = viewModel::createDefaultPlan,
    )
    if (adding) AddChildDialog(onDismiss = { adding = false })
    renaming?.let { child ->
        RenameChildDialog(
            child = child,
            busy = state.busyChildId == child.id,
            error = state.error,
            onSave = { name -> viewModel.rename(child.id, name) { renaming = null } },
            onDismiss = {
                viewModel.clearError()
                renaming = null
            },
        )
    }
}

@Composable
internal fun ManageChildrenContent(
    state: ManageChildrenUiState,
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onRename: (ChildRow) -> Unit,
    onCreatePlan: (String) -> Unit,
) {
    val colors = KidTubeTheme.colors
    Scaffold(containerColor = colors.ground) { inner ->
        Column(modifier = Modifier.fillMaxSize().padding(inner).padding(KidTubeDimens.screenPadding), verticalArrangement = Arrangement.spacedBy(KidTubeDimens.gap16)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(KidTubeDimens.gap8)) {
                BackButton(onClick = onBack)
                Text(
                    text = stringResource(R.string.manage_children_title),
                    style = MaterialTheme.typography.headlineMedium,
                    color = colors.ink,
                    modifier = Modifier.semantics { heading() },
                )
            }
            if (state.error != null && state.busyChildId == null) MessageBanner(stringResource(childFailureText(state.error)))
            if (state.rows.isEmpty()) {
                Text(stringResource(R.string.manage_children_empty), style = MaterialTheme.typography.bodyLarge, color = colors.muted)
            }
            LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(KidTubeDimens.gap12)) {
                items(state.rows, key = { it.id }) { row ->
                    ChildRowCard(row, busy = state.busyChildId == row.id, onRename = { onRename(row) }, onCreatePlan = { onCreatePlan(row.id) })
                }
            }
            PrimaryButton(text = stringResource(R.string.add_child_action), onClick = onAdd)
        }
    }
}

@Composable
private fun ChildRowCard(row: ChildRow, busy: Boolean, onRename: () -> Unit, onCreatePlan: () -> Unit) {
    val colors = KidTubeTheme.colors
    Surface(
        shape = KidTubeShapes.card,
        color = colors.card,
        border = BorderStroke(KidTubeDimens.borderWidth, colors.line),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(KidTubeDimens.gap16), verticalArrangement = Arrangement.spacedBy(KidTubeDimens.gap8)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(KidTubeDimens.gap12)) {
                Box(Modifier.size(40.dp).clip(CircleShape).background(row.avatar?.color(colors) ?: colors.line))
                Text(row.name, style = MaterialTheme.typography.titleMedium, color = colors.ink, modifier = Modifier.weight(1f))
                IconButton(onClick = onRename, enabled = !busy, modifier = Modifier.size(KidTubeDimens.minTouchTarget)) {
                    Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.child_rename, row.name), tint = colors.ink)
                }
            }
            if (!row.hasPlan) {
                Text(stringResource(R.string.child_no_plan), style = MaterialTheme.typography.bodySmall, color = colors.pendingText)
                TextButton(onClick = onCreatePlan, enabled = !busy) {
                    Text(stringResource(R.string.child_create_default_plan), style = MaterialTheme.typography.titleMedium, color = colors.primary)
                }
            }
        }
    }
}

@Composable
private fun RenameChildDialog(child: ChildRow, busy: Boolean, error: ChildFailure?, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember(child.id) { mutableStateOf(child.name) }
    val colors = KidTubeTheme.colors
    Dialog(onDismissRequest = { if (!busy) onDismiss() }) {
        Column(
            modifier = Modifier
                .background(colors.ground, RoundedCornerShape(24.dp))
                .padding(KidTubeDimens.gap24),
            verticalArrangement = Arrangement.spacedBy(KidTubeDimens.gap16),
        ) {
            Text(stringResource(R.string.rename_child_title), style = MaterialTheme.typography.headlineSmall, color = colors.ink, modifier = Modifier.semantics { heading() })
            KidTubeTextField(label = stringResource(R.string.child_name_label), value = name, onValueChange = { name = it.take(AddChildViewModel.MAX_NAME) }, latinInput = false, enabled = !busy)
            Text(stringResource(R.string.add_child_needs_network), style = MaterialTheme.typography.bodySmall, color = colors.muted)
            error?.let { MessageBanner(stringResource(childFailureText(it))) }
            PrimaryButton(text = stringResource(R.string.rename_child_action), onClick = { onSave(name) }, enabled = name.isNotBlank() && !busy)
            TextButton(onClick = onDismiss, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.action_cancel), style = MaterialTheme.typography.titleMedium, color = colors.muted)
            }
        }
    }
}

@EnglishAndUrduPreviews
@Composable
private fun ManageChildrenPreview() {
    PreviewSurface {
        ManageChildrenContent(
            state = ManageChildrenUiState(rows = listOf(ChildRow("1", "Ali", AvatarColor.CORAL, true), ChildRow("2", "Sara", AvatarColor.SKY, false))),
            onBack = {}, onAdd = {}, onRename = {}, onCreatePlan = {},
        )
    }
}
