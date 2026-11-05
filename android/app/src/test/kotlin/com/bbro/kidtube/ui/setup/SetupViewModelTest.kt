package com.bbro.kidtube.ui.setup

import app.cash.turbine.test
import com.bbro.kidtube.core.DeviceRole
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Plain JVM test: the ViewModel is built directly, no Hilt, no Android runtime. */
class SetupViewModelTest {

    @Test
    fun startsWithNoRoleAndCannotContinue() = runTest {
        val viewModel = SetupViewModel()

        viewModel.uiState.test {
            val initial = awaitItem()
            assertEquals(null, initial.selectedRole)
            assertFalse(initial.canContinue)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun selectingARole_emitsNewStateAndEnablesContinue() = runTest {
        val viewModel = SetupViewModel()

        viewModel.uiState.test {
            awaitItem() // initial

            viewModel.onRoleSelected(DeviceRole.KID)
            val afterKid = awaitItem()
            assertEquals(DeviceRole.KID, afterKid.selectedRole)
            assertTrue(afterKid.canContinue)

            viewModel.onRoleSelected(DeviceRole.PARENT)
            assertEquals(DeviceRole.PARENT, awaitItem().selectedRole)

            cancelAndIgnoreRemainingEvents()
        }
    }
}
