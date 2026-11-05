package com.bbro.kidtube.ui.nav

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.bbro.kidtube.core.DeviceRole
import com.bbro.kidtube.ui.kid.KidProfilePickerScreen
import com.bbro.kidtube.ui.parent.ParentHomeScreen
import com.bbro.kidtube.ui.setup.SetupScreen

object Routes {
    const val SETUP = "setup"
    const val PARENT_HOME = "parent_home"
    const val KID_PICKER = "kid_picker"
}

@Composable
fun KidTubeNavHost() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = Routes.SETUP) {
        composable(Routes.SETUP) {
            SetupScreen(
                onContinue = { role ->
                    val destination = when (role) {
                        DeviceRole.PARENT -> Routes.PARENT_HOME
                        DeviceRole.KID -> Routes.KID_PICKER
                    }
                    navController.navigate(destination) {
                        popUpTo(Routes.SETUP) { inclusive = true }
                    }
                },
            )
        }
        composable(Routes.PARENT_HOME) { ParentHomeScreen() }
        composable(Routes.KID_PICKER) { KidProfilePickerScreen() }
    }
}
