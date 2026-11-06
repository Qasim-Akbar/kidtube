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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bbro.kidtube.BuildConfig
import com.bbro.kidtube.R
import com.bbro.kidtube.sync.SyncStatus

@Composable
fun ParentHomeScreen() {
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
            // Developer aid only: shown in debug builds (BuildConfig.DEBUG), never in release.
            if (BuildConfig.DEBUG) DebugSyncPanel()
        }
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
