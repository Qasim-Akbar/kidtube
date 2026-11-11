package com.bbro.kidtube.ui.kid

import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.bbro.kidtube.ui.nav.KID_ROUTES
import com.bbro.kidtube.ui.nav.KidScreens
import com.bbro.kidtube.ui.nav.Routes
import com.bbro.kidtube.ui.nav.kidGraph
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Back stack and reachability of the kid graph, with fake screens that expose every callback as a button. The parent screen
 * is a sentinel: it may only be reached through the picker's leave callback (which the real picker shows behind the PIN).
 */
@RunWith(RobolectricTestRunner::class)
class KidNavigationTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var nav: NavHostController

    private val fakes = KidScreens(
        picker = { pick, leave ->
            Column {
                Text("picker")
                Button(onClick = { pick("c1") }) { Text("pick") }
                Button(onClick = leave) { Text("leave") }
            }
        },
        grid = { childId, open, switch ->
            Column {
                Text("grid $childId")
                Button(onClick = { open("i1") }) { Text("open") }
                Button(onClick = switch) { Text("switch") }
            }
        },
        player = { childId, itemId, back ->
            Column {
                Text("player $childId $itemId")
                Button(onClick = back) { Text("all videos") }
            }
        },
    )

    private fun start() {
        compose.setContent {
            nav = rememberNavController()
            NavHost(nav, startDestination = Routes.KID_PICKER) {
                kidGraph(nav, onLeaveKidMode = { nav.navigate(Routes.PARENT_HOME) }, screens = fakes)
                composable(Routes.PARENT_HOME) { Text("PARENT HOME") }
            }
        }
        compose.waitForIdle()
    }

    private fun route(): String? = compose.runOnIdle { nav.currentBackStackEntry?.destination?.route }

    private fun click(text: String) {
        compose.onNodeWithText(text).performClick()
        compose.waitForIdle()
    }

    private fun systemBack() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    @Test
    fun pickerToGridToPlayerAndSystemBackGoesBackOneStepAtATime() {
        start()
        click("pick")
        assertEquals(Routes.KID_GRID, route())
        compose.onNodeWithText("grid c1").assertExists()
        click("open")
        assertEquals(Routes.KID_PLAYER, route())
        compose.onNodeWithText("player c1 i1").assertExists()

        systemBack()
        assertEquals(Routes.KID_GRID, route())
        systemBack()
        assertEquals(Routes.KID_PICKER, route())
    }

    @Test
    fun allVideosReturnsToTheGridAndSwitchKidToThePicker() {
        start()
        click("pick")
        click("open")
        click("all videos")
        assertEquals(Routes.KID_GRID, route())
        click("switch")
        assertEquals(Routes.KID_PICKER, route())
        // The picker is the entry that was already on the stack: nothing piled up behind it.
        assertNull(compose.runOnIdle { nav.previousBackStackEntry })
    }

    @Test
    fun noKidScreenButtonLeadsToAParentScreen() {
        start()
        val visited = mutableListOf<String>()
        // Press every button of every kid screen except the picker's explicit "leave"; the parent sentinel must never show up.
        click("pick")
        visited += route().orEmpty()
        click("open")
        visited += route().orEmpty()
        click("all videos")
        visited += route().orEmpty()
        click("switch")
        visited += route().orEmpty()
        assertFalse("a kid screen reached a parent route: $visited", visited.contains(Routes.PARENT_HOME))
        assertTrue(visited.all { it in KID_ROUTES })
    }

    @Test
    fun theLeaveCallbackIsOnlyOnThePickerAndIsTheOnlyWayToTheParentScreen() {
        start()
        click("leave")
        assertEquals(Routes.PARENT_HOME, route())
    }

    @Test
    fun kidGraphContainsExactlyThePickerGridAndPlayerRoutes() {
        compose.setContent {
            nav = rememberNavController()
            NavHost(nav, startDestination = Routes.KID_PICKER) { kidGraph(nav, onLeaveKidMode = {}, screens = fakes) }
        }
        compose.waitForIdle()
        val routes = compose.runOnIdle { nav.graph.mapNotNull { it.route }.toSet() }
        assertEquals(KID_ROUTES, routes)
    }

    // ---- portrait lock -------------------------------------------------------------------------------------------

    @Test
    fun kidScreensLockPortraitAndRestoreItWhenTheyLeave() {
        var inKidMode by mutableStateOf(true)
        compose.setContent { if (inKidMode) LockPortrait() }
        compose.waitForIdle()
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, compose.activity.requestedOrientation)
        inKidMode = false
        compose.waitForIdle()
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, compose.activity.requestedOrientation)
    }

    @Test
    fun overlappingKidScreensKeepThePortraitLockUntilTheLastOneLeaves() {
        var first by mutableStateOf(true)
        var second by mutableStateOf(false)
        compose.setContent {
            if (first) LockPortrait()
            if (second) LockPortrait()
        }
        compose.waitForIdle()
        second = true
        compose.waitForIdle()
        first = false // a transition: the old screen leaves while the new one stays
        compose.waitForIdle()
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, compose.activity.requestedOrientation)
        second = false
        compose.waitForIdle()
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, compose.activity.requestedOrientation)
    }
}
