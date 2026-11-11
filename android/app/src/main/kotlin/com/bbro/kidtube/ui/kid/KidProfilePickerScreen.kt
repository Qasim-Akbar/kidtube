package com.bbro.kidtube.ui.kid

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.bbro.kidtube.R
import com.bbro.kidtube.core.ActiveMode
import com.bbro.kidtube.core.DeviceRole
import com.bbro.kidtube.core.KidProfileStore
import com.bbro.kidtube.core.SessionStore
import com.bbro.kidtube.data.repo.ChildrenRepository
import com.bbro.kidtube.ui.components.EnglishAndUrduPreviews
import com.bbro.kidtube.ui.components.KidAvatar
import com.bbro.kidtube.ui.components.LatinAwareText
import com.bbro.kidtube.ui.components.PreviewSurface
import com.bbro.kidtube.ui.children.AvatarColor
import com.bbro.kidtube.ui.pin.PinGate
import com.bbro.kidtube.ui.theme.KidTubeDimens
import com.bbro.kidtube.ui.theme.KidTubeShapes
import com.bbro.kidtube.ui.theme.KidTubeTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class KidPickerUiState(
    val children: List<KidChild> = emptyList(),
    /** Highlighted tile only; the picker never skips ahead to this child. */
    val lastChildId: String? = null,
    /** Only a parent phone can leave kid mode (a kid-only device has no "Parents" button at all). */
    val canLeaveKidMode: Boolean = false,
)

/**
 * "Who is watching?": the children of the stored snapshot, the last active child (highlight only) and whether this phone
 * may show the "Parents" lock. Leaving kid mode: the PIN is checked by [PinGate] first; only then is the persisted active
 * mode switched back to PARENT.
 */
@HiltViewModel
class KidPickerViewModel @Inject constructor(
    private val session: SessionStore,
    childrenRepository: ChildrenRepository,
    private val profiles: KidProfileStore,
) : ViewModel() {
    val state: StateFlow<KidPickerUiState> = combine(
        childrenRepository.observeChildren(),
        profiles.lastChildId,
        session.info.map { it.deviceRole == DeviceRole.PARENT },
    ) { children, last, canLeave -> KidPickerUiState(children.map { it.toKidChild() }, last, canLeave) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), KidPickerUiState())

    fun childPicked(childId: String) {
        viewModelScope.launch { profiles.setLastChild(childId) }
    }

    fun leaveKidMode(onDone: () -> Unit) {
        viewModelScope.launch { if (session.setActiveMode(ActiveMode.PARENT)) onDone() }
    }
}

@Composable
fun KidProfilePickerScreen(
    onChildPicked: (String) -> Unit,
    onLeaveKidMode: () -> Unit = {},
    viewModel: KidPickerViewModel = hiltViewModel(),
) {
    LockPortrait()
    val state by viewModel.state.collectAsStateWithLifecycle()
    var askingPin by remember { mutableStateOf(false) }
    KidPickerContent(
        state = state,
        onPick = {
            viewModel.childPicked(it)
            onChildPicked(it)
        },
        onParents = { askingPin = true },
    )
    if (askingPin) {
        PinGate(
            onVerified = {
                askingPin = false
                viewModel.leaveKidMode(onLeaveKidMode)
            },
            onDismiss = { askingPin = false },
        )
    }
}

@Composable
fun KidPickerContent(state: KidPickerUiState, onPick: (String) -> Unit, onParents: () -> Unit) {
    val colors = KidTubeTheme.colors
    Scaffold(containerColor = colors.kidGround) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(KidTubeDimens.screenPadding),
            verticalArrangement = Arrangement.spacedBy(KidTubeDimens.gap20),
        ) {
            Text(stringResource(R.string.kid_picker_title), style = MaterialTheme.typography.displayLarge, color = colors.ink)
            if (state.children.isEmpty()) {
                Column(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(KidTubeDimens.gap12, Alignment.CenterVertically),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    KidAvatar(AvatarColor.SUN, size = 120.dp)
                    Text(stringResource(R.string.kid_picker_empty_title), style = MaterialTheme.typography.headlineMedium, color = colors.ink, textAlign = TextAlign.Center)
                    Text(stringResource(R.string.kid_picker_empty_subtitle), style = MaterialTheme.typography.bodyLarge, color = colors.ink, textAlign = TextAlign.Center)
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(KidTubeDimens.gap16),
                    verticalArrangement = Arrangement.spacedBy(KidTubeDimens.gap16),
                ) {
                    items(state.children, key = { it.id }) { child ->
                        ChildTile(child, highlighted = child.id == state.lastChildId, onClick = { onPick(child.id) })
                    }
                }
            }
            if (state.canLeaveKidMode) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onParents) {
                        Icon(Icons.Filled.Lock, contentDescription = null, tint = colors.ink)
                        Text(stringResource(R.string.kid_picker_parents), style = MaterialTheme.typography.titleMedium, color = colors.ink, modifier = Modifier.padding(start = KidTubeDimens.gap8))
                    }
                }
            }
        }
    }
}

@Composable
private fun ChildTile(child: KidChild, highlighted: Boolean, onClick: () -> Unit) {
    val colors = KidTubeTheme.colors
    Surface(
        shape = KidTubeShapes.kidTile,
        color = colors.card,
        border = if (highlighted) BorderStroke(KidTubeDimens.selectedBorderWidth, colors.primary) else BorderStroke(KidTubeDimens.borderWidth, colors.line),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 190.dp)
            .clickable(onClick = onClick)
            .semantics { role = Role.Button },
    ) {
        Column(
            modifier = Modifier.padding(KidTubeDimens.gap16),
            verticalArrangement = Arrangement.spacedBy(KidTubeDimens.gap12, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            KidAvatar(child.avatar, size = 110.dp)
            LatinAwareText(child.name, style = MaterialTheme.typography.titleLarge, color = colors.ink, textAlign = TextAlign.Center)
        }
    }
}

@EnglishAndUrduPreviews
@Composable
private fun KidPickerPreview() {
    PreviewSurface {
        Box(Modifier.heightIn(max = 520.dp)) {
            KidPickerContent(
                KidPickerUiState(listOf(KidChild("1", "Ali", AvatarColor.CORAL), KidChild("2", "Sara", AvatarColor.SKY)), "1", true),
                onPick = {}, onParents = {},
            )
        }
    }
}
