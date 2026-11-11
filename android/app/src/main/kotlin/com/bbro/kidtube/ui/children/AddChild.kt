package com.bbro.kidtube.ui.children

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
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
import com.bbro.kidtube.ui.components.EnglishAndUrduPreviews
import com.bbro.kidtube.ui.components.KidTubeTextField
import com.bbro.kidtube.ui.components.MessageBanner
import com.bbro.kidtube.ui.components.PreviewSurface
import com.bbro.kidtube.ui.components.PrimaryButton
import com.bbro.kidtube.ui.theme.KidTubeColors
import com.bbro.kidtube.ui.theme.KidTubeDimens
import com.bbro.kidtube.ui.theme.KidTubeTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The five avatar colours a child can have. The key is what is stored in the child's `avatar` field. */
enum class AvatarColor(val key: String, @StringRes val label: Int) {
    CORAL("coral", R.string.avatar_coral),
    SUN("sun", R.string.avatar_sun),
    SKY("sky", R.string.avatar_sky),
    MINT("mint", R.string.avatar_mint),
    LILAC("lilac", R.string.avatar_lilac);

    fun color(colors: KidTubeColors): Color = when (this) {
        CORAL -> colors.coral
        SUN -> colors.sun
        SKY -> colors.thumbnailPastels[1]
        MINT -> colors.thumbnailPastels[2]
        LILAC -> colors.thumbnailPastels[3]
    }

    companion object {
        fun fromKey(key: String?): AvatarColor? = entries.firstOrNull { it.key == key }
    }
}

@StringRes
fun childFailureText(reason: ChildFailure): Int = when (reason) {
    ChildFailure.NO_CONNECTION -> R.string.child_error_no_connection
    ChildFailure.SERVER_UNREACHABLE -> R.string.child_error_server
    ChildFailure.INVALID_INPUT -> R.string.child_error_invalid
    ChildFailure.RATE_LIMITED -> R.string.error_rate_limited_unknown
    ChildFailure.UNKNOWN -> R.string.child_error_unknown
}

data class AddChildUiState(
    val name: String = "",
    val color: AvatarColor = AvatarColor.CORAL,
    val busy: Boolean = false,
    val error: ChildFailure? = null,
    val done: Boolean = false,
) {
    val canSubmit: Boolean get() = name.isNotBlank() && !busy
}

@HiltViewModel
class AddChildViewModel @Inject constructor(private val childSetup: ChildSetupRepository) : ViewModel() {
    private val _state = MutableStateFlow(AddChildUiState())
    val state: StateFlow<AddChildUiState> = _state

    fun reset() {
        _state.value = AddChildUiState()
    }

    fun onNameChange(value: String) = _state.update { it.copy(name = value.take(MAX_NAME), error = null) }

    fun onColor(color: AvatarColor) = _state.update { it.copy(color = color) }

    /** Needs the network: creates the child, then its default plan, then refreshes the local copy. */
    fun submit() {
        val s = _state.value
        if (!s.canSubmit) return
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            when (val result = childSetup.addChild(s.name, s.color.key)) {
                is ChildAction.Success -> _state.update { it.copy(busy = false, done = true) }
                is ChildAction.Failed -> _state.update { it.copy(busy = false, error = result.reason) }
            }
        }
    }

    companion object {
        const val MAX_NAME = 60
    }
}

/** "Add a child" as a dialog. Used from the empty library, the share sheet and Manage children. */
@Composable
fun AddChildDialog(onDismiss: () -> Unit, viewModel: AddChildViewModel = hiltViewModel()) {
    val state = viewModel.state.collectAsStateWithLifecycle().value
    LaunchedEffect(viewModel) { viewModel.reset() }
    LaunchedEffect(state.done) { if (state.done) onDismiss() }
    Dialog(onDismissRequest = { if (!state.busy) onDismiss() }) {
        AddChildContent(
            state = state,
            onNameChange = viewModel::onNameChange,
            onColor = viewModel::onColor,
            onSubmit = viewModel::submit,
            onCancel = onDismiss,
            modifier = Modifier
                .background(KidTubeTheme.colors.ground, RoundedCornerShape(24.dp))
                .padding(KidTubeDimens.gap24),
        )
    }
}

@Composable
internal fun AddChildContent(
    state: AddChildUiState,
    onNameChange: (String) -> Unit,
    onColor: (AvatarColor) -> Unit,
    onSubmit: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = KidTubeTheme.colors
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(KidTubeDimens.gap16)) {
        Text(
            text = stringResource(R.string.add_child_title),
            style = MaterialTheme.typography.headlineSmall,
            color = colors.ink,
            modifier = Modifier.semantics { heading() },
        )
        KidTubeTextField(
            label = stringResource(R.string.child_name_label),
            value = state.name,
            onValueChange = onNameChange,
            latinInput = false,
            enabled = !state.busy,
        )
        Text(stringResource(R.string.child_color_label), style = MaterialTheme.typography.titleSmall, color = colors.muted)
        Row(horizontalArrangement = Arrangement.spacedBy(KidTubeDimens.gap12)) {
            AvatarColor.entries.forEach { option ->
                val selected = option == state.color
                val label = stringResource(option.label)
                Box(
                    modifier = Modifier
                        .size(KidTubeDimens.minTouchTarget)
                        .clip(CircleShape)
                        .background(option.color(colors))
                        .border(
                            if (selected) KidTubeDimens.selectedBorderWidth else KidTubeDimens.borderWidth,
                            if (selected) colors.primary else colors.line,
                            CircleShape,
                        )
                        .selectable(selected = selected, role = Role.RadioButton, onClick = { onColor(option) })
                        .semantics { contentDescription = label },
                )
            }
        }
        Text(stringResource(R.string.add_child_needs_network), style = MaterialTheme.typography.bodySmall, color = colors.muted)
        state.error?.let { MessageBanner(stringResource(childFailureText(it))) }
        PrimaryButton(text = stringResource(R.string.add_child_action), onClick = onSubmit, enabled = state.canSubmit)
        TextButton(onClick = onCancel, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.action_cancel), style = MaterialTheme.typography.titleMedium, color = colors.muted)
        }
    }
}

@EnglishAndUrduPreviews
@Composable
private fun AddChildPreview() {
    PreviewSurface {
        AddChildContent(
            state = AddChildUiState(name = "Ali", color = AvatarColor.SKY, error = ChildFailure.NO_CONNECTION),
            onNameChange = {}, onColor = {}, onSubmit = {}, onCancel = {},
        )
    }
}
