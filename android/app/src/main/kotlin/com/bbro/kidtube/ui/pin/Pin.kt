package com.bbro.kidtube.ui.pin

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
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
import com.bbro.kidtube.core.PinResult
import com.bbro.kidtube.core.PinStore
import com.bbro.kidtube.ui.components.EnglishAndUrduPreviews
import com.bbro.kidtube.ui.components.MessageBanner
import com.bbro.kidtube.ui.components.PinField
import com.bbro.kidtube.ui.components.PreviewSurface
import com.bbro.kidtube.ui.theme.KidTubeDimens
import com.bbro.kidtube.ui.theme.KidTubeTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

// ---- PinGate: the one PIN prompt used everywhere a parent PIN is needed -----------------------------------------------

data class PinGateUiState(
    val pin: String = "",
    /** Set after a wrong PIN: how many tries are left before the lock. */
    val attemptsLeft: Int? = null,
    /** > 0 while locked. */
    val lockedSeconds: Long = 0,
    val checking: Boolean = false,
    /** True once the PIN was right; the host reacts and removes the gate. */
    val verified: Boolean = false,
) {
    val locked: Boolean get() = lockedSeconds > 0
}

/**
 * Logic behind every PIN prompt: auto-checks when the 4th digit is typed, shows tries left, and while the store says
 * "locked" shows the countdown. The counting and the lock itself live in [PinStore] (injectable Clock, persisted).
 */
@HiltViewModel
class PinGateViewModel @Inject constructor(private val pinStore: PinStore) : ViewModel() {
    private val _state = MutableStateFlow(PinGateUiState())
    val state: StateFlow<PinGateUiState> = _state

    /** Called each time the gate is shown: forget a previous entry, pick up an existing lock. */
    fun reset() {
        _state.value = PinGateUiState()
        refreshLock()
    }

    fun onPinChange(value: String) {
        val s = _state.value
        if (s.locked || s.checking || s.verified) return
        val pin = value.filter(Char::isDigit).take(PinStore.PIN_LENGTH)
        _state.update { it.copy(pin = pin) }
        if (pin.length == PinStore.PIN_LENGTH) check(pin)
    }

    /** Re-reads the remaining lock time (the screen calls this once a second while locked). */
    fun refreshLock() {
        viewModelScope.launch {
            val left = pinStore.lockRemainingSeconds()
            _state.update { it.copy(lockedSeconds = left, pin = if (left > 0) "" else it.pin) }
        }
    }

    private fun check(pin: String) {
        viewModelScope.launch {
            _state.update { it.copy(checking = true) }
            when (val result = pinStore.verify(pin)) {
                PinResult.Success -> _state.update { it.copy(checking = false, verified = true, attemptsLeft = null) }
                is PinResult.Wrong -> _state.update { it.copy(checking = false, pin = "", attemptsLeft = result.attemptsLeft) }
                is PinResult.Locked ->
                    _state.update { it.copy(checking = false, pin = "", attemptsLeft = null, lockedSeconds = result.remainingSeconds) }
                PinResult.NotSet -> _state.update { it.copy(checking = false, pin = "") }
            }
        }
    }
}

/**
 * A PIN prompt as a dialog. Show it when a PIN is needed; [onVerified] runs once after the right PIN, [onDismiss] when
 * the parent cancels. Five wrong tries lock it for 30 seconds (see [PinStore]).
 */
@Composable
fun PinGate(
    onVerified: () -> Unit,
    onDismiss: () -> Unit,
    title: String = stringResource(R.string.pin_gate_title),
    viewModel: PinGateViewModel = hiltViewModel(),
) {
    val state = viewModel.state.collectAsStateWithLifecycle().value
    LaunchedEffect(viewModel) { viewModel.reset() }
    LaunchedEffect(state.lockedSeconds > 0) {
        while (state.lockedSeconds > 0) {
            delay(1000)
            viewModel.refreshLock()
        }
    }
    LaunchedEffect(state.verified) { if (state.verified) onVerified() }

    Dialog(onDismissRequest = onDismiss) {
        PinGateContent(
            state = state,
            title = title,
            onPinChange = viewModel::onPinChange,
            onCancel = onDismiss,
            modifier = Modifier
                .background(KidTubeTheme.colors.ground, RoundedCornerShape(24.dp))
                .padding(KidTubeDimens.gap24),
        )
    }
}

@Composable
internal fun PinGateContent(
    state: PinGateUiState,
    title: String,
    onPinChange: (String) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = KidTubeTheme.colors
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(KidTubeDimens.gap16)) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            color = colors.ink,
            modifier = Modifier.semantics { heading() },
        )
        PinField(value = state.pin, onValueChange = onPinChange, label = stringResource(R.string.pin_label))
        when {
            state.locked -> MessageBanner(pluralStringResource(R.plurals.pin_gate_locked, state.lockedSeconds.toInt(), state.lockedSeconds.toInt()))
            state.attemptsLeft != null -> MessageBanner(pluralStringResource(R.plurals.pin_gate_wrong, state.attemptsLeft, state.attemptsLeft))
        }
        Spacer(Modifier.height(KidTubeDimens.gap8))
        TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.action_cancel), style = MaterialTheme.typography.titleMedium, color = colors.muted)
        }
    }
}

@EnglishAndUrduPreviews
@Composable
private fun PinGatePreview() {
    PreviewSurface {
        PinGateContent(
            state = PinGateUiState(pin = "12", attemptsLeft = 3),
            title = stringResource(R.string.pin_gate_title),
            onPinChange = {},
            onCancel = {},
        )
    }
}
