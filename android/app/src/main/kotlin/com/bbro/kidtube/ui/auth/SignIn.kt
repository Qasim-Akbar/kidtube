package com.bbro.kidtube.ui.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.bbro.kidtube.R
import com.bbro.kidtube.core.ConnectivityChecker
import com.bbro.kidtube.core.PinStore
import com.bbro.kidtube.core.ServerAddressCheck
import com.bbro.kidtube.core.ServerAddressStore
import com.bbro.kidtube.data.remote.ApiResult
import com.bbro.kidtube.data.repo.AuthFailure
import com.bbro.kidtube.data.repo.AuthFailures
import com.bbro.kidtube.data.repo.AuthRepository
import com.bbro.kidtube.ui.components.EnglishAndUrduPreviews
import com.bbro.kidtube.ui.components.KidTubeTextField
import com.bbro.kidtube.ui.components.MessageBanner
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

data class SignInUiState(
    val email: String = "",
    val password: String = "",
    val familyName: String = "",
    /** Create-account mode (register, then sign in). */
    val createMode: Boolean = false,
    /** Turned off for good once the server answered 403/404 to a registration. */
    val registrationAvailable: Boolean = true,
    val server: ServerAddressUiState = ServerAddressUiState(),
    val busy: Boolean = false,
    val error: AuthFailure? = null,
    val fieldsMissing: Boolean = false,
)

sealed interface SignInEvent {
    /** Signed in; the device is now a Parent device. [hasPin] says whether a PIN already exists. */
    data class SignedIn(val hasPin: Boolean) : SignInEvent
}

@HiltViewModel
class SignInViewModel @Inject constructor(
    private val auth: AuthRepository,
    private val serverAddress: ServerAddressStore,
    private val pinStore: PinStore,
    private val connectivity: ConnectivityChecker,
) : ViewModel() {
    private val _state = MutableStateFlow(
        SignInUiState(server = ServerAddressUiState(allowCleartext = serverAddress.policy.allowCleartext)),
    )
    val state: StateFlow<SignInUiState> = _state

    private val _events = Channel<SignInEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    init {
        viewModelScope.launch {
            val current = serverAddress.current()
            _state.update { it.copy(server = it.server.copy(text = current)) }
        }
    }

    fun onEmailChange(value: String) = _state.update { it.copy(email = value, error = null, fieldsMissing = false) }

    fun onPasswordChange(value: String) = _state.update { it.copy(password = value, error = null, fieldsMissing = false) }

    fun onFamilyNameChange(value: String) = _state.update { it.copy(familyName = value) }

    fun onServerTextChange(value: String) =
        _state.update { it.copy(server = it.server.copy(text = value, error = null), error = null) }

    fun onToggleAdvanced() = _state.update { it.copy(server = it.server.copy(expanded = !it.server.expanded)) }

    fun onSetCreateMode(create: Boolean) =
        _state.update { it.copy(createMode = create && it.registrationAvailable, error = null, fieldsMissing = false) }

    fun submit() {
        val s = _state.value
        if (s.busy) return
        if (s.email.isBlank() || s.password.isEmpty()) {
            _state.update { it.copy(fieldsMissing = true) }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null, fieldsMissing = false) }
            val check = serverAddress.save(s.server.text)
            if (check is ServerAddressCheck.Invalid) {
                _state.update {
                    it.copy(busy = false, server = it.server.copy(error = check.error, expanded = true))
                }
                return@launch
            }
            val online = { connectivity.isOnline() }

            if (s.createMode) {
                val registered = auth.register(s.email.trim(), s.password, s.familyName.trim().ifEmpty { DEFAULT_FAMILY_NAME })
                if (registered !is ApiResult.Success) {
                    val failure = AuthFailures.fromRegister(registered, online)
                    _state.update {
                        it.copy(
                            busy = false,
                            error = failure,
                            // 403 / 404: this server has no sign-ups. Hide the option and say so.
                            registrationAvailable = it.registrationAvailable && failure != AuthFailure.RegistrationClosed,
                            createMode = it.createMode && failure != AuthFailure.RegistrationClosed,
                        )
                    }
                    return@launch
                }
            }

            val login = auth.login(s.email.trim(), s.password)
            if (login is ApiResult.Success) {
                _state.update { it.copy(busy = false) }
                _events.send(SignInEvent.SignedIn(hasPin = pinStore.isSet()))
            } else {
                _state.update { it.copy(busy = false, error = AuthFailures.fromLogin(login, online)) }
            }
        }
    }

    private companion object {
        /** Same default the backend uses when the family name is omitted. */
        const val DEFAULT_FAMILY_NAME = "My Family"
    }
}

@Composable
fun SignInScreen(
    onSignedIn: (hasPin: Boolean) -> Unit,
    onBack: () -> Unit,
    viewModel: SignInViewModel = hiltViewModel(),
) {
    val state = viewModel.state.collectAsStateWithLifecycle().value
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is SignInEvent.SignedIn -> onSignedIn(event.hasPin)
            }
        }
    }
    SignInContent(
        state = state,
        onEmailChange = viewModel::onEmailChange,
        onPasswordChange = viewModel::onPasswordChange,
        onFamilyNameChange = viewModel::onFamilyNameChange,
        onServerTextChange = viewModel::onServerTextChange,
        onToggleAdvanced = viewModel::onToggleAdvanced,
        onSetCreateMode = viewModel::onSetCreateMode,
        onSubmit = viewModel::submit,
        onBack = onBack,
    )
}

@Composable
internal fun SignInContent(
    state: SignInUiState,
    onEmailChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onFamilyNameChange: (String) -> Unit,
    onServerTextChange: (String) -> Unit,
    onToggleAdvanced: () -> Unit,
    onSetCreateMode: (Boolean) -> Unit,
    onSubmit: () -> Unit,
    onBack: () -> Unit,
) {
    val colors = KidTubeTheme.colors
    AuthFrame(
        title = stringResource(if (state.createMode) R.string.create_account_title else R.string.signin_title),
        subtitle = stringResource(if (state.createMode) R.string.create_account_subtitle else R.string.signin_subtitle),
        onBack = onBack,
    ) {
        KidTubeTextField(
            label = stringResource(R.string.field_email),
            value = state.email,
            onValueChange = onEmailChange,
            keyboardType = KeyboardType.Email,
            enabled = !state.busy,
        )
        KidTubeTextField(
            label = stringResource(R.string.field_password),
            value = state.password,
            onValueChange = onPasswordChange,
            password = true,
            imeAction = if (state.createMode) ImeAction.Next else ImeAction.Done,
            onImeAction = { if (!state.createMode) onSubmit() },
            enabled = !state.busy,
        )
        if (state.createMode) {
            KidTubeTextField(
                label = stringResource(R.string.field_family_name),
                value = state.familyName,
                onValueChange = onFamilyNameChange,
                imeAction = ImeAction.Done,
                onImeAction = onSubmit,
                latinInput = false,
                enabled = !state.busy,
            )
        }
        if (state.fieldsMissing) MessageBanner(stringResource(R.string.error_fields_required))
        state.error?.takeIf { it != AuthFailure.RegistrationClosed }?.let { MessageBanner(authFailureMessage(it)) }
        // Plain, lasting message once the server said no (403/404) to registration; the Create account option is gone.
        if (!state.registrationAvailable) {
            MessageBanner(authFailureMessage(AuthFailure.RegistrationClosed), isError = false)
        }

        PrimaryButton(
            text = stringResource(if (state.createMode) R.string.action_create_account else R.string.action_sign_in),
            onClick = onSubmit,
            enabled = !state.busy,
        )
        if (state.busy) {
            CircularProgressIndicator(color = colors.primary, modifier = Modifier.heightIn(max = 32.dp))
        }
        if (state.registrationAvailable) {
            TextButton(
                onClick = { onSetCreateMode(!state.createMode) },
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = KidTubeDimens.minTouchTarget),
            ) {
                Text(
                    text = stringResource(if (state.createMode) R.string.link_have_account else R.string.link_create_account),
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.primary,
                )
            }
        }
        ServerAddressSection(state.server, onServerTextChange, onToggleAdvanced)
    }
}

@EnglishAndUrduPreviews
@Composable
private fun SignInPreview() {
    PreviewSurface {
        Column(verticalArrangement = Arrangement.spacedBy(KidTubeDimens.gap16)) {
            SignInContent(
                state = SignInUiState(email = "mum@example.com", error = AuthFailure.WrongCredentials),
                onEmailChange = {}, onPasswordChange = {}, onFamilyNameChange = {}, onServerTextChange = {},
                onToggleAdvanced = {}, onSetCreateMode = {}, onSubmit = {}, onBack = {},
            )
        }
    }
}
