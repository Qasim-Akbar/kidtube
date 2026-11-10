package com.bbro.kidtube.ui.auth

import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.heightIn
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.bbro.kidtube.R
import com.bbro.kidtube.core.ConnectivityChecker
import com.bbro.kidtube.core.DeviceNameSource
import com.bbro.kidtube.core.ServerAddressCheck
import com.bbro.kidtube.core.ServerAddressStore
import com.bbro.kidtube.data.remote.ApiResult
import com.bbro.kidtube.data.repo.AuthFailure
import com.bbro.kidtube.data.repo.AuthFailures
import com.bbro.kidtube.data.repo.AuthRepository
import com.bbro.kidtube.ui.components.CodeField
import com.bbro.kidtube.ui.components.EnglishAndUrduPreviews
import com.bbro.kidtube.ui.components.MessageBanner
import com.bbro.kidtube.ui.components.PreviewSurface
import com.bbro.kidtube.ui.components.PrimaryButton
import com.bbro.kidtube.ui.theme.KidTubeTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

const val PAIRING_CODE_LENGTH = 6

data class PairingUiState(
    val code: String = "",
    val server: ServerAddressUiState = ServerAddressUiState(),
    val busy: Boolean = false,
    val error: AuthFailure? = null,
) {
    val canConnect: Boolean get() = code.length == PAIRING_CODE_LENGTH && !busy
}

sealed interface PairingEvent {
    /** Paired; the device token is saved and the device role is now Kid. */
    data object Paired : PairingEvent
}

@HiltViewModel
class PairingViewModel @Inject constructor(
    private val auth: AuthRepository,
    private val serverAddress: ServerAddressStore,
    private val connectivity: ConnectivityChecker,
    private val deviceName: DeviceNameSource,
) : ViewModel() {
    private val _state = MutableStateFlow(
        PairingUiState(server = ServerAddressUiState(allowCleartext = serverAddress.policy.allowCleartext)),
    )
    val state: StateFlow<PairingUiState> = _state

    private val _events = Channel<PairingEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    init {
        viewModelScope.launch {
            val current = serverAddress.current()
            _state.update { it.copy(server = it.server.copy(text = current)) }
        }
    }

    fun onCodeChange(value: String) =
        _state.update { it.copy(code = value.filter(Char::isDigit).take(PAIRING_CODE_LENGTH), error = null) }

    fun onServerTextChange(value: String) =
        _state.update { it.copy(server = it.server.copy(text = value, error = null), error = null) }

    fun onToggleAdvanced() = _state.update { it.copy(server = it.server.copy(expanded = !it.server.expanded)) }

    fun connect() {
        val s = _state.value
        if (!s.canConnect) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            val check = serverAddress.save(s.server.text)
            if (check is ServerAddressCheck.Invalid) {
                _state.update { it.copy(busy = false, server = it.server.copy(error = check.error, expanded = true)) }
                return@launch
            }
            val result = auth.pairDevice(s.code, deviceName.name())
            if (result is ApiResult.Success) {
                _state.update { it.copy(busy = false) }
                _events.send(PairingEvent.Paired)
            } else {
                _state.update { it.copy(busy = false, error = AuthFailures.fromPair(result, { connectivity.isOnline() })) }
            }
        }
    }
}

@Composable
fun PairingScreen(
    onPaired: () -> Unit,
    onBack: () -> Unit,
    viewModel: PairingViewModel = hiltViewModel(),
) {
    val state = viewModel.state.collectAsStateWithLifecycle().value
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                PairingEvent.Paired -> onPaired()
            }
        }
    }
    PairingContent(
        state = state,
        onCodeChange = viewModel::onCodeChange,
        onServerTextChange = viewModel::onServerTextChange,
        onToggleAdvanced = viewModel::onToggleAdvanced,
        onConnect = viewModel::connect,
        onBack = onBack,
    )
}

@Composable
internal fun PairingContent(
    state: PairingUiState,
    onCodeChange: (String) -> Unit,
    onServerTextChange: (String) -> Unit,
    onToggleAdvanced: () -> Unit,
    onConnect: () -> Unit,
    onBack: () -> Unit,
) {
    AuthFrame(
        title = stringResource(R.string.pairing_title),
        subtitle = stringResource(R.string.pairing_subtitle),
        onBack = onBack,
    ) {
        CodeField(
            value = state.code,
            onValueChange = onCodeChange,
            length = PAIRING_CODE_LENGTH,
            label = stringResource(R.string.pairing_code_label),
            description = stringResource(R.string.pairing_code_description, PAIRING_CODE_LENGTH),
            autoFocus = false,
            onDone = onConnect,
        )
        state.error?.let { MessageBanner(authFailureMessage(it)) }
        PrimaryButton(text = stringResource(R.string.action_connect), onClick = onConnect, enabled = state.canConnect)
        if (state.busy) {
            CircularProgressIndicator(color = KidTubeTheme.colors.primary, modifier = Modifier.heightIn(max = 32.dp))
        }
        ServerAddressSection(state.server, onServerTextChange, onToggleAdvanced)
    }
}

@EnglishAndUrduPreviews
@Composable
private fun PairingPreview() {
    PreviewSurface {
        PairingContent(
            state = PairingUiState(code = "1004", error = AuthFailure.WrongCode),
            onCodeChange = {}, onServerTextChange = {}, onToggleAdvanced = {}, onConnect = {}, onBack = {},
        )
    }
}
