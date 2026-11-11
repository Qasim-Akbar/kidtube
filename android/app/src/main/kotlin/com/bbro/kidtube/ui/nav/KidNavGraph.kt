package com.bbro.kidtube.ui.nav

import androidx.compose.runtime.Composable
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.bbro.kidtube.ui.kid.KidGridScreen
import com.bbro.kidtube.ui.kid.KidPlayerPlaceholder
import com.bbro.kidtube.ui.kid.KidProfilePickerScreen

/** The three kid screens. The real ones are the default; tests swap in fakes to check the navigation alone. */
class KidScreens(
    val picker: @Composable (onChildPicked: (String) -> Unit, onLeaveKidMode: () -> Unit) -> Unit = { pick, leave ->
        KidProfilePickerScreen(onChildPicked = pick, onLeaveKidMode = leave)
    },
    val grid: @Composable (childId: String, onOpenVideo: (String) -> Unit, onSwitchKid: () -> Unit) -> Unit = { _, open, switch ->
        KidGridScreen(onOpenVideo = open, onSwitchKid = switch)
    },
    val player: @Composable (childId: String, itemId: String, onAllVideos: () -> Unit) -> Unit = { _, _, back ->
        KidPlayerPlaceholder(onAllVideos = back)
    },
)

/** Every route a kid screen can reach. Nothing in this graph navigates anywhere else. */
val KID_ROUTES = setOf(Routes.KID_PICKER, Routes.KID_GRID, Routes.KID_PLAYER)

/**
 * picker -> grid -> player, and back the same way (system back pops one step). The ONLY way out of the kid screens is
 * [onLeaveKidMode], which the picker calls after the parent PIN was verified (and which only exists on a parent phone);
 * the caller decides where it leads. Switching kid returns to the picker that is already on the back stack.
 */
fun NavGraphBuilder.kidGraph(navController: NavController, onLeaveKidMode: () -> Unit, screens: KidScreens = KidScreens()) {
    composable(Routes.KID_PICKER) {
        screens.picker({ childId -> navController.navigate(Routes.kidGrid(childId)) }, onLeaveKidMode)
    }
    composable(
        Routes.KID_GRID,
        arguments = listOf(navArgument("childId") { type = NavType.StringType }),
    ) { entry ->
        val childId = entry.arguments?.getString("childId").orEmpty()
        screens.grid(
            childId,
            { itemId -> navController.navigate(Routes.kidPlayer(childId, itemId)) },
            { if (!navController.popBackStack(Routes.KID_PICKER, inclusive = false)) navController.navigate(Routes.KID_PICKER) },
        )
    }
    composable(
        Routes.KID_PLAYER,
        arguments = listOf(navArgument("childId") { type = NavType.StringType }, navArgument("itemId") { type = NavType.StringType }),
    ) { entry ->
        screens.player(
            entry.arguments?.getString("childId").orEmpty(),
            entry.arguments?.getString("itemId").orEmpty(),
        ) { navController.popBackStack() }
    }
}
