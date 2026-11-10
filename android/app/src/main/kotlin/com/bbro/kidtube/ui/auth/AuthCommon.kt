package com.bbro.kidtube.ui.auth

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.bbro.kidtube.R
import com.bbro.kidtube.core.ServerAddressError
import com.bbro.kidtube.data.repo.AuthFailure
import com.bbro.kidtube.ui.components.BackButton
import com.bbro.kidtube.ui.components.KidTubeTextField
import com.bbro.kidtube.ui.theme.KidTubeDimens
import com.bbro.kidtube.ui.theme.KidTubeTheme
import androidx.compose.foundation.layout.imePadding

/** The "Server address" part of a screen's state. [text] is what is in the field; [error] is set by a failed check. */
data class ServerAddressUiState(
    val text: String = "",
    val error: ServerAddressError? = null,
    val expanded: Boolean = false,
    val allowCleartext: Boolean = false,
)

/** Screen frame shared by sign-in and pairing: back arrow, title, subtitle, then [content]. */
@Composable
internal fun AuthFrame(
    title: String,
    subtitle: String?,
    onBack: () -> Unit,
    content: @Composable () -> Unit,
) {
    val colors = KidTubeTheme.colors
    Scaffold(containerColor = colors.ground) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(KidTubeDimens.screenPadding),
        ) {
            BackButton(onClick = onBack)
            Spacer(Modifier.height(KidTubeDimens.gap16))
            Text(
                text = title,
                style = MaterialTheme.typography.headlineLarge,
                color = colors.ink,
                modifier = Modifier.semantics { heading() },
            )
            if (subtitle != null) {
                Spacer(Modifier.height(KidTubeDimens.gap8))
                Text(text = subtitle, style = MaterialTheme.typography.bodyLarge, color = colors.muted)
            }
            Spacer(Modifier.height(KidTubeDimens.gap24))
            Column(verticalArrangement = Arrangement.spacedBy(KidTubeDimens.gap16)) { content() }
        }
    }
}

/** "Advanced" header that expands to the server address field. */
@Composable
internal fun ServerAddressSection(
    state: ServerAddressUiState,
    onTextChange: (String) -> Unit,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = KidTubeTheme.colors
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(KidTubeDimens.gap12)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = KidTubeDimens.minTouchTarget)
                .clickable(role = Role.Button, onClick = onToggle),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(KidTubeDimens.gap8),
        ) {
            Text(
                text = stringResource(R.string.advanced),
                style = MaterialTheme.typography.titleMedium,
                color = colors.muted,
            )
            Icon(
                imageVector = if (state.expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                contentDescription = null,
                tint = colors.muted,
            )
        }
        if (state.expanded) {
            KidTubeTextField(
                label = stringResource(R.string.server_address_label),
                value = state.text,
                onValueChange = onTextChange,
                keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri,
                imeAction = androidx.compose.ui.text.input.ImeAction.Done,
                placeholder = stringResource(R.string.server_address_placeholder),
                error = state.error?.let { serverAddressMessage(it, state.allowCleartext) },
            )
        }
    }
}

@Composable
internal fun serverAddressMessage(error: ServerAddressError, allowCleartext: Boolean): String = stringResource(
    when (error) {
        ServerAddressError.EMPTY -> R.string.server_error_empty
        ServerAddressError.SCHEME ->
            if (allowCleartext) R.string.server_error_scheme_any else R.string.server_error_scheme_https
        ServerAddressError.NOT_A_URL -> R.string.server_error_invalid
    },
)

@Composable
internal fun authFailureMessage(failure: AuthFailure): String = when (failure) {
    AuthFailure.WrongCredentials -> stringResource(R.string.error_wrong_password)
    AuthFailure.WrongCode -> stringResource(R.string.pairing_error_wrong_code)
    is AuthFailure.RateLimited ->
        if (failure.retryAfterSeconds != null) {
            stringResource(R.string.error_rate_limited, failure.retryAfterSeconds.toInt())
        } else {
            stringResource(R.string.error_rate_limited_unknown)
        }
    AuthFailure.NoConnection -> stringResource(R.string.error_no_connection)
    AuthFailure.ServerUnreachable -> stringResource(R.string.error_server_unreachable)
    AuthFailure.EmailTaken -> stringResource(R.string.error_email_taken)
    AuthFailure.InvalidInput -> stringResource(R.string.error_invalid_input)
    AuthFailure.RegistrationClosed -> stringResource(R.string.error_registration_closed)
    is AuthFailure.Unknown -> stringResource(R.string.error_unknown, failure.code)
}
