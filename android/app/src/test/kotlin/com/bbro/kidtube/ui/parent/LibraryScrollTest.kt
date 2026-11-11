package com.bbro.kidtube.ui.parent

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.onNodeWithTag
import com.bbro.kidtube.ui.components.BadgeStatus
import com.bbro.kidtube.ui.theme.KidTubeTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression for B3. The list state was always right, but a LazyColumn with stable keys keeps the row that was first
 * anchored in place, so a row inserted ABOVE it (every new share, and its "Waiting to send" row) ended up scrolled out of
 * view above the viewport until the parent pulled down.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-w411dp-h900dp")
class LibraryScrollTest {
    @get:Rule
    val compose = createComposeRule()

    private fun item(id: String, title: String, outbox: Boolean = false) = LibraryItem(
        id, title, null, if (outbox) BadgeStatus.PENDING else BadgeStatus.VERIFIED, emptyList(),
        note = if (outbox) LibraryNote.WAITING_TO_SEND else null, fromOutbox = outbox,
    )

    private fun state(vararg items: LibraryItem) = LibraryUiState(loaded = true, items = items.toList(), totalItems = items.size)

    private var current by mutableStateOf(state())

    private fun show() = compose.setContent {
        KidTubeTheme { LibraryContent(current, {}, {}, {}, {}, {}) }
    }

    private val older = (1..6).map { item("video:$it", "Old video $it") }

    @Test
    fun aNewRowAtTheTop_isVisibleWithoutPullingDown() {
        current = state(*older.toTypedArray())
        show()
        compose.onNodeWithText("Old video 1").assertIsDisplayed()

        current = state(item("outbox:new", "New shared video", outbox = true), *older.toTypedArray())
        compose.waitForIdle()

        compose.onNodeWithText("New shared video").assertIsDisplayed()
        compose.onNodeWithText("Waiting to send").assertIsDisplayed()
    }

    @Test
    fun severalNewRows_allShowAtTheTop() {
        current = state(*older.toTypedArray())
        show()

        current = state(item("video:n2", "Newest"), item("outbox:n1", "Newer", outbox = true), *older.toTypedArray())
        compose.waitForIdle()

        compose.onNodeWithText("Newest").assertIsDisplayed()
        compose.onNodeWithText("Newer").assertIsDisplayed()
    }

    @Test
    fun ifTheParentScrolledDown_newRowsDoNotYankTheListBackToTheTop() {
        current = state(*(1..30).map { item("video:$it", "Old video $it") }.toTypedArray())
        show()
        compose.onNodeWithTag(LIBRARY_LIST_TAG).performScrollToIndex(20)
        compose.onNodeWithText("Old video 21").assertIsDisplayed()

        current = state(item("outbox:new", "New shared video", outbox = true), *(1..30).map { item("video:$it", "Old video $it") }.toTypedArray())
        compose.waitForIdle()

        compose.onNodeWithText("Old video 21").assertIsDisplayed() // still where the parent was reading
    }
}
