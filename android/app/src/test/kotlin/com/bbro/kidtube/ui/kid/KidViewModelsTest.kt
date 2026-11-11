package com.bbro.kidtube.ui.kid

import androidx.lifecycle.SavedStateHandle
import com.bbro.kidtube.core.KidProfileStore
import com.bbro.kidtube.data.RoomTestBase
import com.bbro.kidtube.data.dao.SnapshotData
import com.bbro.kidtube.data.remote.EventType
import com.bbro.kidtube.data.remote.VideoStatus
import com.bbro.kidtube.data.repo.ChildrenRepository
import com.bbro.kidtube.data.repo.EventPayloads
import com.bbro.kidtube.data.repo.EventRepository
import com.bbro.kidtube.data.repo.FeedRepository
import com.bbro.kidtube.data.toData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import kotlin.time.Duration.Companion.milliseconds

/**
 * The kid grid and picker view models on the production stack: in-memory Room (contract fixture 73), the real FeedRepository,
 * and the fake clock. Ali on Mon 2026-01-05: pace 2, one view already -> feed [ITEM_2], 1 dot left of 2.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class KidViewModelsTest : RoomTestBase() {
    private lateinit var feeds: FeedRepository
    private lateinit var events: EventRepository
    private val scope = CoroutineScope(Dispatchers.Default)

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined) // real-time delays: the feed Flow polls the clock with delay()
        clock.setWallClock(Instant.parse("2026-01-05T10:00:00Z"))
        feeds = FeedRepository(db, clock, session.store)
        events = EventRepository(db.localEventDao(), clock, session.store)
    }

    @After
    fun tearDown() {
        scope.cancel()
        Dispatchers.resetMain()
    }

    private suspend fun seed(data: SnapshotData = loadSnapshot().toData()) {
        val dao = db.snapshotDao()
        dao.replaceSnapshot(data, "\"etag\"", dao.beginPull(clock.now()))
    }

    private fun gridVm(childId: String = CHILD_A) = KidGridViewModel(
        SavedStateHandle(mapOf(KidGridViewModel.ARG_CHILD_ID to childId)), feeds, ChildrenRepository(db.childDao()), 10.milliseconds,
    )

    private suspend fun KidGridViewModel.awaitState(what: String, p: (KidScreenState) -> Boolean): KidScreenState {
        val job = scope.launch { state.collect { } } // like the screen: keeps the state flow active
        try {
            return withTimeout(5_000) { state.first(p) }
        } catch (e: Exception) {
            throw AssertionError("grid never reached: $what. Last: ${state.value}", e)
        } finally {
            job.cancel()
        }
    }

    private fun SnapshotData.withVideoStatus(videoId: String, status: VideoStatus) =
        copy(videos = videos.map { if (it.id == videoId) it.copy(status = status) else it })

    private fun KidScreenState.gridTiles() = (this as KidScreenState.Grid).tiles.map { it.itemId }

    // ---- grid ------------------------------------------------------------------------------------------------------

    @Test
    fun gridStartsLoadingThenShowsTheChildsFeed() = runBlocking {
        seed()
        val vm = gridVm()
        assertEquals(KidScreenState.Loading, vm.state.value) // nothing collected yet
        val s = vm.awaitState("grid") { it is KidScreenState.Grid } as KidScreenState.Grid
        assertEquals("Ali", s.child.name)
        assertEquals(listOf(ITEM_2), s.tiles.map { it.itemId })
        assertEquals(2, s.dotsTotal)
        assertEquals(1, s.dotsLeft)
        assertNull(s.first)
        assertEquals("Colours with Pip", s.tiles.single().title)
    }

    @Test
    fun aLocalViewMovesTheScreenToDoneForToday() = runBlocking {
        seed()
        val vm = gridVm()
        vm.awaitState("grid") { it is KidScreenState.Grid }
        events.record(EventType.VIEW_COMPLETE, CHILD_A, EventPayloads.viewComplete(ITEM_2, 0.95))
        val s = vm.awaitState("done") { it is KidScreenState.Status } as KidScreenState.Status
        assertEquals(StatusKind.DONE, s.kind)
    }

    @Test
    fun withheldVideoNeverAppearsAndPendingVideoDoes() = runBlocking {
        seed(loadSnapshot().toData().withVideoStatus(VIDEO_OF_ITEM_2, VideoStatus.UNAVAILABLE))
        val unavailable = gridVm().awaitState("grid") { it is KidScreenState.Grid }
        assertEquals(listOf(ITEM_18), unavailable.gridTiles()) // ITEM_2 withheld, the next item takes the slot

        seed(loadSnapshot().toData().withVideoStatus(VIDEO_OF_ITEM_2, VideoStatus.PENDING))
        val pending = gridVm().awaitState("grid with pending") { it is KidScreenState.Grid && it.tiles.any { t -> t.itemId == ITEM_2 } }
        assertEquals(listOf(ITEM_2), pending.gridTiles())
    }

    @Test
    fun viewedTodayItemsGetTheCheck() = runBlocking {
        seed()
        clock.setWallClock(Instant.parse("2026-01-06T10:00:00Z")) // Tue: catch-up quota 3, ITEM_2 stays eligible after a view
        events.record(EventType.VIEW_COMPLETE, CHILD_A, EventPayloads.viewComplete(ITEM_2, 0.95))
        val s = gridVm().awaitState("grid with a check") { it is KidScreenState.Grid && it.tiles.any { t -> t.viewedToday } } as KidScreenState.Grid
        assertEquals(mapOf(ITEM_2 to true, ITEM_18 to false), s.tiles.associate { it.itemId to it.viewedToday })
    }

    @Test
    fun theGridReevaluatesAtLocalMidnightFromTheInjectedClock() = runBlocking {
        seed()
        val vm = gridVm()
        events.record(EventType.VIEW_COMPLETE, CHILD_A, EventPayloads.viewComplete(ITEM_2, 0.95))
        vm.awaitState("done for today (Monday)") { it is KidScreenState.Status && it.kind == StatusKind.DONE }

        // 23:59:59 Monday in Karachi: still done. One second later it is Tuesday 00:00 there (19:00 UTC).
        clock.setWallClock(Instant.parse("2026-01-05T18:59:59Z"))
        kotlinx.coroutines.delay(100)
        assertTrue(vm.awaitState("still Monday") { true } is KidScreenState.Status)
        clock.setWallClock(Instant.parse("2026-01-05T19:00:00Z"))
        val tuesday = vm.awaitState("grid again on Tuesday") { it is KidScreenState.Grid } as KidScreenState.Grid
        assertEquals(listOf(ITEM_2, ITEM_18), tuesday.tiles.map { it.itemId })
        assertEquals(2, tuesday.dotsTotal) // pace 2; no backlog (2 views on Monday)
        assertEquals(2, tuesday.dotsLeft)
    }

    @Test
    fun appTimeLimitShowsTheBarThenTimesUp() = runBlocking {
        seed()
        // Sara (limit 10 min, 660 s used on the server today) is already over: time's up.
        val timeUp = gridVm(SARA).awaitState("time up") { it is KidScreenState.Status } as KidScreenState.Status
        assertEquals(StatusKind.TIME_UP, timeUp.kind)
        // A parent extension of 15 minutes brings the bar back with time left (and Sara is also done: 2 views today).
        events.record(EventType.TIME_EXTENSION, SARA, EventPayloads.timeExtension(900.0))
        val after = gridVm(SARA).awaitState("not time up") { it is KidScreenState.Status && it.kind != StatusKind.TIME_UP } as KidScreenState.Status
        assertEquals(StatusKind.DONE, after.kind)
    }

    @Test
    fun childWithoutAPlanAndAChildThatDisappears() = runBlocking {
        seed()
        val pip = gridVm(CHILD_B).awaitState("nothing here yet") { it is KidScreenState.Status } as KidScreenState.Status
        assertEquals(StatusKind.NOTHING, pip.kind)
        assertEquals(KidScreenState.Gone, gridVm("no-such-child").awaitState("gone") { it == KidScreenState.Gone })
    }

    // ---- picker --------------------------------------------------------------------------------------------------------

    private fun pickerVm() = KidPickerViewModel(session.store, ChildrenRepository(db.childDao()), KidProfileStore(session.backing))

    private suspend fun KidPickerViewModel.awaitState(p: (KidPickerUiState) -> Boolean): KidPickerUiState {
        val job = scope.launch { state.collect { } }
        try {
            return withTimeout(5_000) { state.first(p) }
        } finally {
            job.cancel()
        }
    }

    @Test
    fun parentPhoneCanLeaveKidModeKidDeviceCannot() = runBlocking {
        seed()
        session.store.saveParentLogin("a", "r", "fam-1")
        assertTrue(pickerVm().awaitState { it.children.isNotEmpty() && it.canLeaveKidMode }.canLeaveKidMode)
    }

    @Test
    fun kidOnlyDeviceHasNoWayOut() = runBlocking {
        seed()
        session.store.saveDevicePairing("tok", "dev-1", "fam-1")
        val s = pickerVm().awaitState { it.children.isNotEmpty() }
        assertFalse(s.canLeaveKidMode)
    }

    @Test
    fun pickerListsSnapshotChildrenAndRemembersTheLastOneForHighlightingOnly() = runBlocking {
        seed()
        val vm = pickerVm()
        val first = vm.awaitState { it.children.size == 5 }
        assertEquals(listOf("Ali", "Sara", "Omar", "Noor", "Pip"), first.children.map { it.name })
        assertNull(first.lastChildId) // nothing remembered yet: nobody is highlighted, nobody is auto-entered
        vm.childPicked(CHILD_B)
        assertEquals(CHILD_B, vm.awaitState { it.lastChildId != null }.lastChildId)
        // A new picker (app restart) reads it back; it is only data for highlighting.
        assertEquals(CHILD_B, pickerVm().awaitState { it.lastChildId != null }.lastChildId)
    }

    companion object {
        const val SARA = "00000000-0000-4000-8000-000000000004"
        const val ITEM_18 = "00000000-0000-4000-8000-000000000018"
        const val VIDEO_OF_ITEM_2 = "00000000-0000-4000-8000-000000000012"
    }
}
