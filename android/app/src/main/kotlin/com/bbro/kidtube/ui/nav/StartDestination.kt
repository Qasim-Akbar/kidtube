package com.bbro.kidtube.ui.nav

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bbro.kidtube.core.ActiveMode
import com.bbro.kidtube.core.DeviceRole
import com.bbro.kidtube.core.PinStore
import com.bbro.kidtube.core.SessionInfo
import com.bbro.kidtube.core.SessionState
import com.bbro.kidtube.core.SessionStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

object Routes {
    const val SETUP = "setup"
    const val SIGN_IN = "sign_in"
    const val PAIRING = "pairing"
    const val CREATE_PIN = "create_pin"
    const val CHANGE_PIN = "change_pin"
    const val PARENT_HOME = "parent_home"
    const val KID_PICKER = "kid_picker"
    const val SETTINGS = "settings"
    const val MANAGE_CHILDREN = "manage_children"
    const val KID_GRID = "kid_grid/{childId}"
    const val KID_PLAYER = "kid_player/{childId}/{itemId}"

    fun kidGrid(childId: String) = "kid_grid/${android.net.Uri.encode(childId)}"
    fun kidPlayer(childId: String, itemId: String) = "kid_player/${android.net.Uri.encode(childId)}/${android.net.Uri.encode(itemId)}"
}

/** Which screen the app opens on, from what is stored. Pure, so every case is unit-tested. */
object StartDestination {
    fun resolve(state: SessionState, info: SessionInfo, hasPin: Boolean): String = when (state) {
        // Fresh install -> Setup. A kid role whose pairing secret is gone has to pair again.
        SessionState.Unauthenticated -> if (info.deviceRole == DeviceRole.KID) Routes.PAIRING else Routes.SETUP
        // Parent device whose refresh token was rejected: sign in again (all local data is kept).
        SessionState.NeedsLogin -> Routes.SIGN_IN
        // Kid device whose token was revoked: pair again.
        SessionState.DeviceRevoked -> Routes.PAIRING
        SessionState.KidDevice -> Routes.KID_PICKER
        is SessionState.ParentDevice -> when {
            !hasPin -> Routes.CREATE_PIN // leaving kid mode needs a PIN, so one comes first in either mode
            state.activeMode == ActiveMode.KID -> Routes.KID_PICKER
            else -> Routes.PARENT_HOME
        }
    }
}

/** Decides the start destination once, from the persisted session and PIN. `null` while it is being read. */
@HiltViewModel
class AppStartViewModel @Inject constructor(
    session: SessionStore,
    pinStore: PinStore,
) : ViewModel() {
    private val _start = MutableStateFlow<String?>(null)
    val start: StateFlow<String?> = _start

    init {
        viewModelScope.launch {
            _start.value = StartDestination.resolve(session.currentState(), session.currentInfo(), pinStore.isSet())
        }
    }
}
