package com.bbro.kidtube.ui.nav

import androidx.compose.runtime.Composable
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.bbro.kidtube.core.DeviceRole
import com.bbro.kidtube.ui.auth.PairingScreen
import com.bbro.kidtube.ui.auth.SignInScreen
import com.bbro.kidtube.ui.kid.KidProfilePickerScreen
import com.bbro.kidtube.ui.children.ManageChildrenScreen
import com.bbro.kidtube.ui.parent.ParentHomeScreen
import com.bbro.kidtube.ui.parent.SettingsScreen
import com.bbro.kidtube.ui.pin.CreatePinScreen
import com.bbro.kidtube.ui.setup.SetupScreen

@Composable
fun KidTubeNavHost(startDestination: String) {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = startDestination) {
        composable(Routes.SETUP) {
            // The role is only stored once sign-in / pairing succeeds, so backing out of either returns here.
            SetupScreen(
                onContinue = { role ->
                    navController.navigate(if (role == DeviceRole.PARENT) Routes.SIGN_IN else Routes.PAIRING)
                },
            )
        }
        composable(Routes.SIGN_IN) {
            SignInScreen(
                onSignedIn = { hasPin -> navController.clearAndGo(if (hasPin) Routes.PARENT_HOME else Routes.CREATE_PIN) },
                onBack = { if (!navController.popBackStack()) navController.navigate(Routes.SETUP) },
            )
        }
        composable(Routes.PAIRING) {
            PairingScreen(
                onPaired = { navController.clearAndGo(Routes.KID_PICKER) },
                onBack = { if (!navController.popBackStack()) navController.navigate(Routes.SETUP) },
            )
        }
        composable(Routes.CREATE_PIN) {
            CreatePinScreen(isChange = false, onSaved = { navController.clearAndGo(Routes.PARENT_HOME) })
        }
        composable(Routes.CHANGE_PIN) {
            CreatePinScreen(
                isChange = true,
                onSaved = { navController.popBackStack() },
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.PARENT_HOME) {
            ParentHomeScreen(
                onKidMode = { navController.clearAndGo(Routes.KID_PICKER) },
                onSettings = { navController.navigate(Routes.SETTINGS) },
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onManageChildren = { navController.navigate(Routes.MANAGE_CHILDREN) },
                onChangePin = { navController.navigate(Routes.CHANGE_PIN) },
            )
        }
        composable(Routes.MANAGE_CHILDREN) { ManageChildrenScreen(onBack = { navController.popBackStack() }) }
        composable(Routes.KID_PICKER) {
            // Only a parent phone shows the "Parents" button; it asks for the PIN before this runs.
            KidProfilePickerScreen(onLeaveKidMode = { navController.clearAndGo(Routes.PARENT_HOME) })
        }
    }
}

/** Go to [route] and drop everything behind it (after sign-in / pairing / PIN creation there is no way "back"). */
private fun NavController.clearAndGo(route: String) {
    navigate(route) { popUpTo(0) { inclusive = true } }
}
