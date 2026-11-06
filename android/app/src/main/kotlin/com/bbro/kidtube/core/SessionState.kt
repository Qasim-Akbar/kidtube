package com.bbro.kidtube.core

/** Which mode a parent-role device is currently showing. Only meaningful when the device role is [DeviceRole.PARENT]. */
enum class ActiveMode { PARENT, KID }

/** What the rest of the app should show, derived from the persisted session. */
sealed interface SessionState {
    /** Fresh install: no device role chosen yet (or a kid device whose pairing secret is gone). */
    data object Unauthenticated : SessionState

    /** A parent's phone. API calls use the parent tokens in BOTH modes; the UI hides parent screens in kid mode. */
    data class ParentDevice(val activeMode: ActiveMode) : SessionState

    /** A kid-only device paired by code. Always kid mode. */
    data object KidDevice : SessionState

    /** Parent device whose refresh token was rejected or is missing. All local data is kept; log in again. */
    data object NeedsLogin : SessionState

    /** The kid device's token was rejected (revoked by a parent). Local data is kept; it must be re-paired. */
    data object DeviceRevoked : SessionState
}

/** Non-secret session info. */
data class SessionInfo(
    val deviceRole: DeviceRole?,
    val familyId: String?,
    val familyTimezone: String?,
    val deviceId: String?,
    val activeMode: ActiveMode,
)
