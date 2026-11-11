package com.bbro.kidtube.ui.parent

import com.bbro.kidtube.data.remote.Platform
import com.bbro.kidtube.data.remote.VideoDto
import com.bbro.kidtube.data.remote.VideoStatus
import com.bbro.kidtube.data.repo.ChildrenRepository
import com.bbro.kidtube.data.repo.PlanRepository
import com.bbro.kidtube.data.repo.VideoRepository
import com.bbro.kidtube.sync.SyncTestBase
import com.bbro.kidtube.sync.SyncTrigger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

/**
 * Regression for B3: the Library must show a newly shared item (also a Library-only one) and its "Waiting to send" row by
 * itself, through the real stack (Room + outbox + sync engine + snapshot replace), with no pull-to-refresh.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryLiveUpdateTest : SyncTestBase() {
    private val link = "https://youtu.be/zzzzzzzzzzz"
    private lateinit var vm: ParentHomeViewModel
    private val scope = CoroutineScope(Dispatchers.Default)

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        vm = ParentHomeViewModel(
            statusProvider, SyncTrigger.None, session.store,
            ChildrenRepository(db.childDao()), VideoRepository(db.videoDao()), PlanRepository(db.planDao()), outboxRepo,
        )
    }

    @After
    fun tearDown() {
        scope.cancel()
        Dispatchers.resetMain()
    }

    private suspend fun awaitLibrary(what: String, predicate: (LibraryUiState) -> Boolean): LibraryUiState =
        try {
            withTimeout(5_000) { vm.library.first(predicate) }
        } catch (e: Exception) {
            throw AssertionError("Library never showed: $what. Last state: ${vm.library.value.items.map { it.id to it.note }}", e)
        }

    /** Like the Library screen: collects the state for as long as the "screen" is on. */
    private fun startCollecting(): Job = scope.launch { vm.library.collect { } }

    private fun serverNowKnowsTheVideo() {
        val video = VideoDto(
            id = "srv-video-1", platform = Platform.YOUTUBE, platformVideoId = "zzzzzzzzzzz", url = link, title = "New video",
            thumbnailUrl = null, durationSeconds = null, status = VideoStatus.VERIFIED, addedBy = null,
            addedAt = Instant.parse("2026-11-05T10:00:05Z"),
        )
        serverSnapshot = serverSnapshot.copy(videos = serverSnapshot.videos + video)
        serverEtag = "\"after-library-only-share\""
    }

    private suspend fun ready() {
        loginAsParent()
        seedSnapshot()
    }

    @Test
    fun aLibraryOnlyShare_showsAsWaiting_thenAsAServerVideo_withoutAnyRefresh() = runBlocking {
        ready()
        val collecting = startCollecting()
        awaitLibrary("the stored videos") { it.loaded && it.items.isNotEmpty() }

        outboxRepo.enqueue(link, emptyList()) // "Library only": a video with NO placements
        val waiting = awaitLibrary("the waiting row") { s -> s.items.any { it.fromOutbox } }
        assertEquals(LibraryNote.WAITING_TO_SEND, waiting.items.first { it.fromOutbox }.note)

        serverNowKnowsTheVideo()
        engine.runCycle() // push -> pull (the snapshot now contains the video)

        val done = awaitLibrary("the synced video") { s -> s.items.any { it.id == "video:srv-video-1" } && s.items.none { it.fromOutbox } }
        assertTrue(done.items.first { it.id == "video:srv-video-1" }.libraryOnly)
        collecting.cancel()
    }

    @Test
    fun theSameHappensWhenTheLibraryWasNotOnScreenWhileTheShareHappened() = runBlocking {
        ready()
        var collecting = startCollecting()
        awaitLibrary("the stored videos") { it.loaded && it.items.isNotEmpty() }

        // the share sheet (another activity) comes to the front: the Library stops collecting
        collecting.cancel()
        kotlinx.coroutines.delay(6_500) // longer than WhileSubscribed(5000): the ViewModel's upstream is really stopped
        outboxRepo.enqueue(link, emptyList())
        serverNowKnowsTheVideo()
        engine.runCycle()
        // ... and the Library comes back
        collecting = startCollecting()

        awaitLibrary("the synced video after coming back") { s -> s.items.any { it.id == "video:srv-video-1" } }
        collecting.cancel()
    }

    @Test
    fun aShareWithAChild_showsUpWithoutRefresh_too() = runBlocking {
        ready()
        val collecting = startCollecting()
        awaitLibrary("the stored videos") { it.loaded && it.items.isNotEmpty() }

        outboxRepo.enqueue(link, listOf(com.bbro.kidtube.data.repo.PlacementSpec(CHILD_A, dailyCap = 1)))
        val waiting = awaitLibrary("the waiting row") { s -> s.items.any { it.fromOutbox } }
        assertEquals(listOf(CHILD_A), waiting.items.first { it.fromOutbox }.assignments.map { it.childId })
        collecting.cancel()
    }
}
