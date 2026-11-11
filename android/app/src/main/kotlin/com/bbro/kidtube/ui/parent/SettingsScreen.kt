package com.bbro.kidtube.ui.parent

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.bbro.kidtube.BuildConfig
import com.bbro.kidtube.R
import com.bbro.kidtube.core.AppLanguage
import com.bbro.kidtube.sync.SyncStatus
import com.bbro.kidtube.ui.components.BackButton
import com.bbro.kidtube.ui.components.LanguageToggle
import com.bbro.kidtube.ui.components.PrimaryButton
import com.bbro.kidtube.ui.pin.PinGate
import com.bbro.kidtube.ui.setup.SetupViewModel
import com.bbro.kidtube.ui.theme.KidTubeDimens
import com.bbro.kidtube.ui.theme.KidTubeTheme
import com.bbro.kidtube.ui.theme.isUrduLocale

/** Settings: manage children, change PIN (asks the current PIN first), language, and the debug sync panel (debug builds). */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onManageChildren: () -> Unit,
    onChangePin: () -> Unit,
    languageViewModel: SetupViewModel = hiltViewModel(),
) {
    var askingPin by remember { mutableStateOf(false) }
    val colors = KidTubeTheme.colors
    Scaffold(containerColor = colors.ground) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(KidTubeDimens.screenPadding),
            verticalArrangement = Arrangement.spacedBy(KidTubeDimens.gap16),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(KidTubeDimens.gap8)) {
                BackButton(onClick = onBack)
                Text(
                    stringResource(R.string.settings_title),
                    style = MaterialTheme.typography.headlineMedium,
                    color = colors.ink,
                    modifier = Modifier.semantics { heading() },
                )
            }
            PrimaryButton(text = stringResource(R.string.settings_manage_children), onClick = onManageChildren)
            PrimaryButton(text = stringResource(R.string.settings_change_pin), onClick = { askingPin = true })

            Text(stringResource(R.string.settings_language), style = MaterialTheme.typography.titleSmall, color = colors.muted)
            LanguageToggle(
                selected = if (isUrduLocale()) AppLanguage.URDU else AppLanguage.ENGLISH,
                onSelected = languageViewModel::onLanguageSelected,
            )

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
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
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
