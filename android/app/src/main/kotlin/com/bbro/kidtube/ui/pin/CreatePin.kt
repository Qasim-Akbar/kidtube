package com.bbro.kidtube.ui.pin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.bbro.kidtube.R
import com.bbro.kidtube.core.PinStore
import com.bbro.kidtube.ui.components.BackButton
import com.bbro.kidtube.ui.components.EnglishAndUrduPreviews
import com.bbro.kidtube.ui.components.MessageBanner
import com.bbro.kidtube.ui.components.PinField
import com.bbro.kidtube.ui.components.PreviewSurface
import com.bbro.kidtube.ui.components.PrimaryButton
import com.bbro.kidtube.ui.theme.KidTubeDimens
import com.bbro.kidtube.ui.theme.KidTubeTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class CreatePinError { TOO_SHORT, MISMATCH }

data class CreatePinUiState(
    val pin: String = "",
    val confirm: String = "",
    val error: CreatePinError? = null,
    val saving: Boolean = false,
)

@HiltViewModel
class CreatePinViewModel @Inject constructor(private val pinStore: PinStore) : ViewModel() {
    private val _state = MutableStateFlow(CreatePinUiState())
    val state: StateFlow<CreatePinUiState> = _state

    private val _saved = Channel<Unit>(Channel.BUFFERED)
    val saved = _saved.receiveAsFlow()

    fun onPinChange(value: String) = _state.update { it.copy(pin = digits(value), error = null) }

    fun onConfirmChange(value: String) = _state.update { it.copy(confirm = digits(value), error = null) }

    fun save() {
        val s = _state.value
        if (s.saving) return
        val error = when {
            !PinStore.isValidPin(s.pin) -> CreatePinError.TOO_SHORT
            s.pin != s.confirm -> CreatePinError.MISMATCH
            else -> null
        }
        if (error != null) {
            _state.update { it.copy(error = error) }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(saving = true) }
            pinStore.setPin(s.pin)
            _state.update { it.copy(saving = false, pin = "", confirm = "") }
            _saved.send(Unit)
        }
    }

    private fun digits(value: String) = value.filter(Char::isDigit).take(PinStore.PIN_LENGTH)
}

/** First-time PIN creation, or [isChange] = choosing a new one (the old one was just verified by a PinGate). */
@Composable
fun CreatePinScreen(
    isChange: Boolean,
    onSaved: () -> Unit,
    onBack: (() -> Unit)? = null,
    viewModel: CreatePinViewModel = hiltViewModel(),
) {
    val state = viewModel.state.collectAsStateWithLifecycle().value
    LaunchedEffect(viewModel) { viewModel.saved.collect { onSaved() } }
    CreatePinContent(
        state = state,
        isChange = isChange,
        onPinChange = viewModel::onPinChange,
        onConfirmChange = viewModel::onConfirmChange,
        onSave = viewModel::save,
        onBack = onBack,
    )
}

@Composable
internal fun CreatePinContent(
    state: CreatePinUiState,
    isChange: Boolean,
    onPinChange: (String) -> Unit,
    onConfirmChange: (String) -> Unit,
    onSave: () -> Unit,
    onBack: (() -> Unit)?,
) {
    val colors = KidTubeTheme.colors
    Scaffold(containerColor = colors.ground) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(KidTubeDimens.screenPadding),
        ) {
            if (onBack != null) {
                Row { BackButton(onClick = onBack) }
                Spacer(Modifier.height(KidTubeDimens.gap16))
            }
            Text(
                text = stringResource(if (isChange) R.string.pin_change_title else R.string.pin_create_title),
                style = MaterialTheme.typography.headlineLarge,
                color = colors.ink,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.height(KidTubeDimens.gap8))
            Text(
                text = stringResource(R.string.pin_create_subtitle),
                style = MaterialTheme.typography.bodyLarge,
                color = colors.muted,
            )
            Spacer(Modifier.height(KidTubeDimens.gap24))
            Column(verticalArrangement = Arrangement.spacedBy(KidTubeDimens.gap16)) {
                PinField(value = state.pin, onValueChange = onPinChange, label = stringResource(R.string.pin_label))
                PinField(value = state.confirm, onValueChange = onConfirmChange, label = stringResource(R.string.pin_confirm_label))
                state.error?.let {
                    MessageBanner(
                        stringResource(
                            if (it == CreatePinError.MISMATCH) R.string.pin_error_mismatch else R.string.pin_error_length,
                        ),
                    )
                }
                PrimaryButton(text = stringResource(R.string.pin_save), onClick = onSave, enabled = !state.saving, loading = state.saving)
            }
        }
    }
}

@EnglishAndUrduPreviews
@Composable
private fun CreatePinPreview() {
    PreviewSurface {
        CreatePinContent(
            state = CreatePinUiState(pin = "12", confirm = "", error = CreatePinError.MISMATCH),
            isChange = false,
            onPinChange = {}, onConfirmChange = {}, onSave = {}, onBack = {},
        )
    }
}
