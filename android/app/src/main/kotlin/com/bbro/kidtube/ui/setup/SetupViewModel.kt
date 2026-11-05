package com.bbro.kidtube.ui.setup

import androidx.lifecycle.ViewModel
import com.bbro.kidtube.core.DeviceRole
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject

data class SetupUiState(val selectedRole: DeviceRole? = null) {
    val canContinue: Boolean get() = selectedRole != null
}

/** Placeholder: holds the chosen role in memory. Persisting it and pairing/login come later. */
@HiltViewModel
class SetupViewModel @Inject constructor() : ViewModel() {
    private val _uiState = MutableStateFlow(SetupUiState())
    val uiState: StateFlow<SetupUiState> = _uiState.asStateFlow()

    fun onRoleSelected(role: DeviceRole) {
        _uiState.update { it.copy(selectedRole = role) }
    }
}
