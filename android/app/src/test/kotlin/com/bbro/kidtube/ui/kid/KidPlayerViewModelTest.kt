package com.bbro.kidtube.ui.kid

import androidx.lifecycle.SavedStateHandle
import com.bbro.kidtube.data.RoomTestBase
import com.bbro.kidtube.data.dao.SnapshotData
import com.bbro.kidtube.data.remote.EventType
import com.bbro.kidtube.data.remote.KidTubeJson
import com.bbro.kidtube.data.remote.Platform
import com.bbro.kidtube.data.remote.VideoStatus
import com.bbro.kidtube.data.repo.EventRepository
import com.bbro.kidtube.data.repo.FeedRepository
import com.bbro.kidtube.data.repo.FeedState
import com.bbro.kidtube.data.repo.PlanRepository
import com.bbro.kidtube.data.repo.VideoRepository
import com.bbro.kidtube.data.toData
import com.bbro.kidtube.engine.FeedStatus
import com.bbro.kidtube.player.KidEventWriter
import com.bbro.kidtube.player.KidSessionManager
import com.bbro.kidtube.player.PlayerBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.milliseconds

/**
 * The player view model on the production stack (in-memory Room from contract fixture 73, real FeedRepository, event writer and
 * app-session manager) with a fake player. Ali on Mon 2026-01-05: pace 2, one view done -> ITEM_2 (YouTube, threshold 0.9) is offered
 * and one more view finishes the day.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class KidPlayerViewModelTest : RoomTestBase() {
    private val scope = CoroutineScope(Dispatchers.Default)
    private lateinit var feeds: FeedRepository
    private lateinit var events: EventRepository
    private lateinit var writer: KidEventWriter
    private lateinit var sessions: KidSessionManager

    private class FakeBridge : PlayerBridge {
        val plays = java.util.concurrent.atomic.AtomicInteger()
        val pauses = java.util.concurrent.atomic.AtomicInteger()
        override fun play() { plays.incrementAndGet() }
        override fun pause() { pauses.incrementAndGet() }
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined) // real-time delays: the VM ticks and the feed polls the clock
        clock.setWallClock(Instant.parse("2026-01-05T10:00:00Z"))
        feeds = FeedRepository(db, clock, session.store, kidsFacebookEnabled = true) // the Facebook player code stays tested, switched on here
        events = EventRepository(db.localEventDao(), clock, session.store)
        writer = KidEventWriter(events, scope)
        sessions = KidSessionManager(clock, session.store, writer, scope, tickMs = 10, graceMs = 0)
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

    private fun SnapshotData.asFacebook() = copy(videos = videos.map {
        if (it.id == VIDEO_OF_ITEM_2) it.copy(platform = Platform.FACEBOOK, url = "https://www.facebook.com/watch/?v=1234567890123") else it
    })

    private fun SnapshotData.withVideoStatus(status: VideoStatus) =
        copy(videos = videos.map { if (it.id == VIDEO_OF_ITEM_2) it.copy(status = status) else it })

    private fun newVm(itemId: String = ITEM_2, childId: String = CHILD_A) = KidPlayerViewModel(
        SavedStateHandle(mapOf(KidPlayerViewModel.ARG_CHILD_ID to childId, KidPlayerViewModel.ARG_ITEM_ID to itemId)),
        PlanRepository(db.planDao()), VideoRepository(db.videoDao()), feeds, session.store, clock, writer, sessions,
        PlayerTiming(tickMs = 10, loadTimeoutSeconds = 5, feedCheckInterval = 10.milliseconds),
    )

    private suspend fun KidPlayerViewModel.awaitState(what: String, p: (PlayerUiState) -> Boolean): PlayerUiState =
        try {
            withTimeout(5_000) { state.first(p) }
        } catch (e: Exception) {
            throw AssertionError("player never reached: $what. Last: ${state.value}", e)
        }

    private suspend fun KidPlayerViewModel.awaitExit() = try {
        withTimeout(5_000) { exits.first() }
    } catch (e: Exception) {
        throw AssertionError("the player never asked to leave. State: ${state.value}", e)
    }

    /** Waits until the background collector of `exits` has seen the exit (a channel has one consumer: do not also call awaitExit). */
    private suspend fun awaitExitIn(exits: List<Unit>) {
        try {
            withTimeout(5_000) { while (exits.isEmpty()) delay(10) }
        } catch (e: Exception) {
            throw AssertionError("the player never asked to leave", e)
        }
    }

    private suspend fun stored(type: EventType) = db.localEventDao().pending(100).filter { it.type == type }

    private fun com.bbro.kidtube.data.entity.LocalEventEntity.json() = KidTubeJson.instance.parseToJsonElement(payload).jsonObject

    private suspend fun ready(vm: KidPlayerViewModel, bridge: FakeBridge = FakeBridge()): PlayerUiState.Ready {
        vm.attachBridge(bridge)
        return vm.awaitState("ready") { it is PlayerUiState.Ready } as PlayerUiState.Ready
    }

    // ---- loading ---------------------------------------------------------------------------------------------------------

    @Test
    fun youTubeVideoOpensWithItsPlatformIdAndTitle() = runBlocking {
        seed()
        val s = ready(newVm())
        assertEquals(PlayerSurface.YouTube("vid00000003"), s.surface)
        assertEquals("Colours with Pip", s.title)
        assertTrue(s.canTogglePlay)
    }

    @Test
    fun aPendingVideoPlaysNormally() = runBlocking {
        seed(loadSnapshot().toData().withVideoStatus(VideoStatus.PENDING))
        assertTrue(ready(newVm()).surface is PlayerSurface.YouTube)
    }

    @Test
    fun facebookVideoUsesThePluginSurfaceWithoutOurPlayButton() = runBlocking {
        seed(loadSnapshot().toData().asFacebook())
        val s = ready(newVm())
        assertTrue((s.surface as PlayerSurface.Facebook).pluginUrl.startsWith("https://www.facebook.com/plugins/video.php?href="))
        assertEquals(false, s.canTogglePlay)
    }

    @Test
    fun anUnavailableVideoShowsTheErrorTileAndWritesNothing() = runBlocking {
        seed(loadSnapshot().toData().withVideoStatus(VideoStatus.UNAVAILABLE))
        val vm = newVm()
        vm.awaitState("error tile") { it is PlayerUiState.Error }
        writer.awaitIdle()
        assertTrue(db.localEventDao().pending(100).isEmpty())
    }

    @Test
    fun aMissingItemMeansGone() = runBlocking {
        seed()
        newVm(itemId = "no-such-item").awaitState("gone") { it == PlayerUiState.Gone }
        Unit
    }

    // ---- tracking through the view model ------------------------------------------------------------------------------------
    @Test
    fun theYouTubeCoverStaysUntilTheFirstPlayingStateAndReturnsWhenItEnds() = runBlocking {
        seed()
        val vm = newVm()
        ready(vm)
        assertTrue((vm.state.value as PlayerUiState.Ready).covered) // loading: the embed's chrome is hidden
        vm.onBuffering()
        assertTrue((vm.state.value as PlayerUiState.Ready).covered)
        vm.onPlaying()
        assertFalse((vm.state.value as PlayerUiState.Ready).covered)
        vm.onPaused()
        assertFalse((vm.state.value as PlayerUiState.Ready).covered)
        vm.onEnded()
        assertTrue((vm.state.value as PlayerUiState.Ready).covered)
    }


    @Test
    fun aViewIsRecordedOnceAtTheThresholdAndTheVideoIsNotCutShort() = runBlocking {
        seed()
        val vm = newVm()
        ready(vm)
        val exits = CopyOnWriteArrayList<Unit>()
        scope.launch { vm.exits.collect { exits += it } }

        vm.onDuration(100.0)
        vm.onPlaying()
        clock.advance(Duration.ofSeconds(5))
        vm.onPosition(89.0)
        writer.awaitIdle()
        assertTrue(stored(EventType.VIEW_COMPLETE).isEmpty())
        vm.onPosition(90.0)
        vm.onPosition(95.0)
        writer.awaitIdle()
        val views = stored(EventType.VIEW_COMPLETE)
        assertEquals(1, views.size)
        assertEquals(ITEM_2, views.single().json().getValue("plan_item_id").jsonPrimitive.content)
        delay(200)
        assertTrue("the last 10% of the video must still play", exits.isEmpty())

        vm.onEnded() // the video ends: back to the grid, and no second view
        awaitExitIn(exits)
        assertEquals(1, stored(EventType.VIEW_COMPLETE).size)
    }

    @Test
    fun theDailyCapReachedByAFinishedViewReturnsToTheGridWhichShowsDone() = runBlocking {
        seed()
        val vm = newVm()
        ready(vm)
        vm.onDuration(100.0); vm.onPlaying(); clock.advance(Duration.ofSeconds(5)); vm.onPosition(92.0)
        vm.onEnded()
        vm.awaitExit()
        // The exit came after everything was written, so the very next feed evaluation already includes the view.
        val grid = feeds.current(CHILD_A) as FeedState.Ready
        assertEquals(FeedStatus.DONE_TODAY, grid.feed.status)
        assertEquals(2, grid.feed.viewsToday)
    }

    @Test
    fun facebookCountsByTimeAndLeavesWhenTheViewMakesTheItemUnavailableToday() = runBlocking {
        seed(loadSnapshot().toData().asFacebook())
        val vm = newVm()
        ready(vm)
        vm.onForeground() // resumed: time on screen counts
        clock.advance(Duration.ofSeconds(181))
        vm.awaitExit() // tick -> view_complete -> Room -> feed says done for today -> leave
        assertEquals(1, stored(EventType.VIEW_COMPLETE).size)
        assertEquals(FeedStatus.DONE_TODAY, (feeds.current(CHILD_A) as FeedState.Ready).feed.status)
    }

    @Test
    fun facebookTimeInTheBackgroundDoesNotCount() = runBlocking {
        seed(loadSnapshot().toData().asFacebook())
        val vm = newVm()
        ready(vm)
        vm.onForeground()
        clock.advance(Duration.ofSeconds(60))
        vm.onBackground()
        clock.advance(Duration.ofSeconds(600))
        delay(200)
        writer.awaitIdle()
        assertTrue(stored(EventType.VIEW_COMPLETE).isEmpty())
        assertEquals(60.0, stored(EventType.WATCH_PROGRESS).sumOf { it.json().getValue("seconds").jsonPrimitive.content.toDouble() }, 0.5)
    }

    @Test
    fun anEmbedErrorShowsTheTileRecordsTheCodeAndNeverAView() = runBlocking {
        seed()
        val vm = newVm()
        val bridge = FakeBridge()
        ready(vm, bridge)
        vm.onDuration(100.0); vm.onPlaying(); clock.advance(Duration.ofSeconds(2))
        vm.onError(150)
        vm.awaitState("error tile") { it is PlayerUiState.Error }
        vm.onPosition(100.0) // late callbacks after the error change nothing
        vm.onEnded()
        writer.awaitIdle()
        assertTrue(bridge.pauses.get() >= 1)
        assertEquals(150, stored(EventType.PLAYBACK_ERROR).single().json().getValue("code").jsonPrimitive.content.toInt())
        assertTrue(stored(EventType.VIEW_COMPLETE).isEmpty())
    }

    @Test
    fun aVideoThatNeverStartsEndsInTheTileWithoutBlamingTheVideo() = runBlocking {
        seed()
        val vm = newVm()
        ready(vm)
        clock.advance(Duration.ofSeconds(6)) // loadTimeoutSeconds = 5, no callback ever came (no network)
        vm.awaitState("error tile after the timeout") { it is PlayerUiState.Error }
        writer.awaitIdle()
        assertTrue(stored(EventType.PLAYBACK_ERROR).isEmpty())
    }

    // ---- All videos / back / background ----------------------------------------------------------------------------------------

    @Test
    fun allVideosPausesFlushesEverythingAndOnlyThenLeaves() = runBlocking {
        seed()
        sessions.screenEntered(CHILD_A); sessions.onForeground()
        val vm = newVm()
        val bridge = FakeBridge()
        ready(vm, bridge)
        vm.onPlaying()
        clock.advance(Duration.ofSeconds(10))

        vm.leave()
        vm.leave() // a second press does nothing
        vm.awaitExit()

        assertTrue(bridge.pauses.get() >= 1)
        // At the moment the exit arrives the playing seconds AND the app time are already in Room.
        assertEquals(10.0, stored(EventType.WATCH_PROGRESS).sumOf { it.json().getValue("seconds").jsonPrimitive.content.toDouble() }, 0.0)
        assertTrue(stored(EventType.APP_SESSION).isNotEmpty())
        delay(100)
        assertEquals(10.0, stored(EventType.WATCH_PROGRESS).sumOf { it.json().getValue("seconds").jsonPrimitive.content.toDouble() }, 0.0)
    }

    @Test
    fun goingToTheBackgroundPausesAndWritesThePlayedSeconds() = runBlocking {
        seed()
        val vm = newVm()
        val bridge = FakeBridge()
        ready(vm, bridge)
        vm.onPlaying(); clock.advance(Duration.ofSeconds(7))
        vm.onBackground()
        writer.awaitIdle()
        assertTrue(bridge.pauses.get() >= 1)
        assertEquals(7.0, stored(EventType.WATCH_PROGRESS).single().json().getValue("seconds").jsonPrimitive.content.toDouble(), 0.0)
        assertEquals(false, (vm.state.value as PlayerUiState.Ready).isPlaying)
    }

    @Test
    fun ourPlayPauseButtonDrivesThePlayer() = runBlocking {
        seed()
        val vm = newVm()
        val bridge = FakeBridge()
        ready(vm, bridge)
        vm.onTogglePlay() // paused -> play
        vm.onPlaying()
        vm.onTogglePlay() // playing -> pause
        assertEquals(1, bridge.plays.get())
        assertEquals(1, bridge.pauses.get())
    }

    // ---- live app-time limit ---------------------------------------------------------------------------------------------------

    @Test
    fun theAppTimeLimitReachedDuringPlaybackStopsTheVideoAndTheGridShowsTimesUp() = runBlocking {
        // Ali: limit 10 min + 15 min parent extension = 1500 s, 1200 s already used today -> 300 s left.
        val data = loadSnapshot().toData()
        seed(data.copy(children = data.children.map { if (it.id == CHILD_A) it.copy(dailyAppMinutes = 10) else it }))
        sessions.screenEntered(CHILD_A); sessions.onForeground() // the kid screens are shown and resumed
        val vm = newVm()
        val bridge = FakeBridge()
        ready(vm, bridge)
        vm.onPlaying()
        delay(300) // the first feed (with the limit) has arrived
        val exits = CopyOnWriteArrayList<Unit>()
        scope.launch { vm.exits.collect { exits += it } }

        clock.advance(Duration.ofSeconds(299)) // 1499 of 1500 used
        delay(300)
        assertTrue("still allowed at 1499 s", exits.isEmpty())

        clock.advance(Duration.ofSeconds(2)) // limit reached
        awaitExitIn(exits)
        assertTrue(bridge.pauses.get() >= 1) // stopped
        // Everything was flushed before the exit, so the grid's very next evaluation says "time's up".
        assertEquals(FeedStatus.TIME_UP, (feeds.current(CHILD_A) as FeedState.Ready).feed.status)
    }

    // ---- sessions ---------------------------------------------------------------------------------------------------------------

    @Test
    fun leavingTheViewModelWritesWhatWasPlayedEvenWithoutAllVideos() = runBlocking {
        seed()
        val vm = newVm()
        ready(vm)
        vm.onPlaying(); clock.advance(Duration.ofSeconds(4))
        val onCleared = KidPlayerViewModel::class.java.getDeclaredMethod("onCleared").apply { isAccessible = true }
        onCleared.invoke(vm)
        writer.awaitIdle()
        assertEquals(4.0, stored(EventType.WATCH_PROGRESS).single().json().getValue("seconds").jsonPrimitive.content.toDouble(), 0.0)
    }

    companion object {
        const val VIDEO_OF_ITEM_2 = "00000000-0000-4000-8000-000000000012"
    }
}
