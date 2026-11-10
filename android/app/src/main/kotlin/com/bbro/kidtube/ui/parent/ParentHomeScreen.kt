package com.bbro.kidtube.ui.parent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.bbro.kidtube.BuildConfig
import com.bbro.kidtube.R
import com.bbro.kidtube.sync.SyncStatus
import com.bbro.kidtube.ui.components.PrimaryButton
import com.bbro.kidtube.ui.pin.PinGate

@Composable
fun ParentHomeScreen(onChangePin: () -> Unit = {}) {
    var askingPin by remember { mutableStateOf(false) }
    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.parent_home_title), style = MaterialTheme.typography.headlineMedium)
            Text(stringResource(R.string.parent_home_placeholder), style = MaterialTheme.typography.bodyLarge)

            // Settings placeholder: only "Change PIN" for now. The current PIN is asked for first.
            Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.titleSmall)
            PrimaryButton(text = stringResource(R.string.settings_change_pin), onClick = { askingPin = true })

            // Developer aid only: shown in debug builds (BuildConfig.DEBUG), never in release.
            if (BuildConfig.DEBUG) DebugSyncPanel()
        }
    }
    if (askingPin) {
        PinGate(
            onVerified = {
                askingPin = false
                onChangePin()
            },
            onDismiss = { askingPin = false },
        )
    }
}

/** Debug-only: a "Sync now" button and the [SyncStatus] fields as plain text. */
@Composable
private fun DebugSyncPanel(viewModel: ParentHomeViewModel = hiltViewModel()) {
    val status by viewModel.status.collectAsState(initial = SyncStatus())
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Button(onClick = viewModel::syncNow) { Text("Sync now") }
        Text(describe(status), style = MaterialTheme.typography.bodySmall)
    }
}

internal fun describe(s: SyncStatus): String = listOf(
    "phase: ${s.phase}",
    "lastSuccessAt: ${s.lastSuccessAt}",
    "lastError: ${s.lastError}",
    "pendingVideos: ${s.pendingVideos}",
    "pendingEvents: ${s.pendingEvents}",
    "rejectedVideos: ${s.rejectedVideos}",
    "rejectedEvents: ${s.rejectedEvents}",
    "placementsWaitingForPlan: ${s.placementsWaitingForPlan}",
    "failedPlacements: ${s.failedPlacements}",
).joinToString("\n")
