package com.bbro.kidtube.ui.kid

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.bbro.kidtube.R
import com.bbro.kidtube.core.ActiveMode
import com.bbro.kidtube.core.DeviceRole
import com.bbro.kidtube.core.SessionStore
import com.bbro.kidtube.ui.pin.PinGate
import com.bbro.kidtube.ui.theme.KidTubeTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Leaving kid mode. Only a parent phone can: a kid-only device has no way back, whatever the screen shows.
 * The PIN is checked by [PinGate] first; only then is the persisted active mode switched back to PARENT.
 */
@HiltViewModel
class KidPickerViewModel @Inject constructor(private val session: SessionStore) : ViewModel() {
    val canLeaveKidMode: StateFlow<Boolean> = session.info.map { it.deviceRole == DeviceRole.PARENT }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun leaveKidMode(onDone: () -> Unit) {
        viewModelScope.launch { if (session.setActiveMode(ActiveMode.PARENT)) onDone() }
    }
}

@Composable
fun KidProfilePickerScreen(onLeaveKidMode: () -> Unit = {}, viewModel: KidPickerViewModel = hiltViewModel()) {
    val canLeave = viewModel.canLeaveKidMode.collectAsStateWithLifecycle().value
    var askingPin by remember { mutableStateOf(false) }
    val colors = KidTubeTheme.colors
    Scaffold(containerColor = colors.kidGround) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.kid_picker_title), style = MaterialTheme.typography.headlineMedium, color = colors.ink)
            Text(stringResource(R.string.kid_picker_placeholder), style = MaterialTheme.typography.bodyLarge, color = colors.ink, modifier = Modifier.weight(1f))
            if (canLeave) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { askingPin = true }) {
                        Icon(Icons.Filled.Lock, contentDescription = null, tint = colors.ink)
                        Text(stringResource(R.string.kid_picker_parents), style = MaterialTheme.typography.titleMedium, color = colors.ink, modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        }
    }
    if (askingPin) {
        PinGate(
            onVerified = {
                askingPin = false
                viewModel.leaveKidMode(onLeaveKidMode)
            },
            onDismiss = { askingPin = false },
        )
    }
}
