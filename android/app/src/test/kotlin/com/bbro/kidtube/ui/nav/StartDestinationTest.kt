package com.bbro.kidtube.ui.nav

import com.bbro.kidtube.core.ActiveMode
import com.bbro.kidtube.core.DeviceRole
import com.bbro.kidtube.core.SessionInfo
import com.bbro.kidtube.core.SessionState
import org.junit.Assert.assertEquals
import org.junit.Test

class StartDestinationTest {
    private fun info(role: DeviceRole?, mode: ActiveMode = ActiveMode.PARENT) =
        SessionInfo(deviceRole = role, familyId = null, familyTimezone = null, deviceId = null, activeMode = mode)

    private fun resolve(state: SessionState, role: DeviceRole?, hasPin: Boolean, mode: ActiveMode = ActiveMode.PARENT) =
        StartDestination.resolve(state, info(role, mode), hasPin)

    @Test
    fun noRole_goesToSetup() {
        assertEquals(Routes.SETUP, resolve(SessionState.Unauthenticated, null, hasPin = false))
        // a stray PIN without a role (e.g. after data was partly cleared) still starts at Setup
        assertEquals(Routes.SETUP, resolve(SessionState.Unauthenticated, null, hasPin = true))
    }

    @Test
    fun parentWithoutPin_mustCreateOne() {
        assertEquals(Routes.CREATE_PIN, resolve(SessionState.ParentDevice(ActiveMode.PARENT), DeviceRole.PARENT, hasPin = false))
    }

    @Test
    fun parentWithoutPin_inKidMode_stillCreatesOneFirst() {
        assertEquals(
            Routes.CREATE_PIN,
            resolve(SessionState.ParentDevice(ActiveMode.KID), DeviceRole.PARENT, hasPin = false, mode = ActiveMode.KID),
        )
    }

    @Test
    fun parentWithPin_goesToParentHome() {
        assertEquals(Routes.PARENT_HOME, resolve(SessionState.ParentDevice(ActiveMode.PARENT), DeviceRole.PARENT, hasPin = true))
    }

    @Test
    fun parentDeviceKeepsItsActiveMode_kidModeOpensTheKidPicker() {
        assertEquals(
            Routes.KID_PICKER,
            resolve(SessionState.ParentDevice(ActiveMode.KID), DeviceRole.PARENT, hasPin = true, mode = ActiveMode.KID),
        )
    }

    @Test
    fun kidDevice_goesToKidPicker_whateverThePinState() {
        assertEquals(Routes.KID_PICKER, resolve(SessionState.KidDevice, DeviceRole.KID, hasPin = false))
        assertEquals(Routes.KID_PICKER, resolve(SessionState.KidDevice, DeviceRole.KID, hasPin = true))
    }

    @Test
    fun parentWhoseLoginExpired_signsInAgain() {
        assertEquals(Routes.SIGN_IN, resolve(SessionState.NeedsLogin, DeviceRole.PARENT, hasPin = true))
    }

    @Test
    fun kidDeviceThatLostItsToken_pairsAgain() {
        assertEquals(Routes.PAIRING, resolve(SessionState.DeviceRevoked, DeviceRole.KID, hasPin = false))
        assertEquals(Routes.PAIRING, resolve(SessionState.Unauthenticated, DeviceRole.KID, hasPin = false))
    }
}
