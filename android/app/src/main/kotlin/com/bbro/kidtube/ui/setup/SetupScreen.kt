package com.bbro.kidtube.ui.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bbro.kidtube.R
import com.bbro.kidtube.core.DeviceRole

@Composable
fun SetupScreen(
    onContinue: (DeviceRole) -> Unit,
    viewModel: SetupViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    SetupContent(
        state = state,
        onRoleSelected = viewModel::onRoleSelected,
        onContinue = { state.selectedRole?.let(onContinue) },
    )
}

@Composable
private fun SetupContent(
    state: SetupUiState,
    onRoleSelected: (DeviceRole) -> Unit,
    onContinue: () -> Unit,
) {
    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.setup_title), style = MaterialTheme.typography.headlineMedium)
            Text(stringResource(R.string.setup_prompt), style = MaterialTheme.typography.bodyLarge)
            Column(
                modifier = Modifier.selectableGroup(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                RoleCard(
                    title = stringResource(R.string.setup_role_parent),
                    description = stringResource(R.string.setup_role_parent_desc),
                    selected = state.selectedRole == DeviceRole.PARENT,
                    onClick = { onRoleSelected(DeviceRole.PARENT) },
                )
                RoleCard(
                    title = stringResource(R.string.setup_role_kid),
                    description = stringResource(R.string.setup_role_kid_desc),
                    selected = state.selectedRole == DeviceRole.KID,
                    onClick = { onRoleSelected(DeviceRole.KID) },
                )
            }
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = onContinue,
                enabled = state.canContinue,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.setup_continue))
            }
        }
    }
}

@Composable
private fun RoleCard(
    title: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = if (selected) {
        CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    } else {
        CardDefaults.cardColors()
    }
    Card(
        colors = colors,
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(description, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
