package com.bbro.kidtube.data

import com.bbro.kidtube.core.ActiveMode
import com.bbro.kidtube.data.entity.OutboxVideoState
import com.bbro.kidtube.data.entity.PlacementState
import com.bbro.kidtube.data.remote.ApiResult
import com.bbro.kidtube.data.remote.ApiWiring
import com.bbro.kidtube.data.remote.KidTubeJson
import com.bbro.kidtube.data.remote.OnMiss
import com.bbro.kidtube.data.repo.EventPayloads
import com.bbro.kidtube.data.repo.EventRepository
import com.bbro.kidtube.data.repo.OutboxRepository
import com.bbro.kidtube.data.repo.PlacementSpec
import com.bbro.kidtube.data.repo.PullResult
import com.bbro.kidtube.data.repo.SnapshotRepository
import com.bbro.kidtube.data.remote.EventType
import com.bbro.kidtube.testing.ContractFixtures
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue

class OutboxAndPullTest : RoomTestBase() {
    private var server: MockWebServer? = null

    @After
    fun stopServer() {
        server?.close()
    }

    // ---- outbox -------------------------------------------------------------------------------------------

    @Test
    fun enqueueSavesTheLinkAndItsPlacementsTogether() = runBlocking {
        val repo = OutboxRepository(db.outboxDao(), clock)

        val id = repo.enqueue(
            "https://youtu.be/dQw4w9WgXcQ",
            listOf(
                PlacementSpec(CHILD_A, required = true, viewTarget = 3, dailyCap = 1),
                PlacementSpec(CHILD_B, anchorDate = LocalDate.of(2026, 11, 9), onMiss = OnMiss.SKIP),
                PlacementSpec(childId = null),
            ),
        )

        UUID.fromString(id)
        val video = repo.pendingVideos().single()
        assertEquals(id, video.id)
        assertEquals("https://youtu.be/dQw4w9WgXcQ", video.rawUrl)
        assertEquals(OutboxVideoState.PENDING, video.state)
        assertNull(video.serverVideoId)
        assertEquals(0, video.attempts)
        assertEquals(Instant.parse("2026-11-05T10:00:00Z"), video.createdAt)

        val placements = repo.placementsFor(id)
        assertEquals(3, placements.size)
        assertTrue(placements.all { it.state == PlacementState.PENDING && it.outboxVideoId == id })
        assertEquals(3, placements.map { it.id }.toSet().size)
        val required = placements.single { it.childId == CHILD_A }
        assertTrue(required.required)
        assertEquals(3, required.viewTarget)
        assertEquals(1, required.dailyCap)
        val anchored = placements.single { it.childId == CHILD_B }
        assertEquals(OnMiss.SKIP, anchored.onMiss)
        assertEquals(LocalDate.of(2026, 11, 9), anchored.anchorDate)
        assertEquals(1, placements.count { it.childId == null })
    }

    @Test
    fun theSameLinkSharedTwiceKeepsTwoIndependentRows() = runBlocking {
        val repo = OutboxRepository(db.outboxDao(), clock)
        val a = repo.enqueue("https://youtu.be/dQw4w9WgXcQ", emptyList())
        val b = repo.enqueue("https://youtu.be/dQw4w9WgXcQ", emptyList())
        assertNotEquals(a, b)
        assertEquals(2, repo.pendingVideos().size)
    }

    // ---- pull -------------------------------------------------------------------------------------------------

    private fun startServer(): ConcurrentLinkedQueue<Pair<String?, String?>> {
        val requests = ConcurrentLinkedQueue<Pair<String?, String?>>() // (If-None-Match, Authorization)
        val snapshot = ContractFixtures.load(73).bodyText
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests += request.headers["If-None-Match"] to request.headers["Authorization"]
                    return if (request.headers["If-None-Match"] == ETAG) {
                        MockResponse.Builder().code(304).addHeader("ETag", ETAG).build()
                    } else {
                        MockResponse.Builder().code(200).addHeader("Content-Type", "application/json")
                            .addHeader("ETag", ETAG).body(snapshot).build()
                    }
                }
            }
            start()
        }
        return requests
    }

    @Test
    fun pullStoresTheSnapshotThenAConditionalPullGets304AndAdvancesTheHistoryCycle() = runBlocking {
        val requests = startServer()
        session.store.saveParentLogin("access-1", "refresh-1", "fam-1")
        session.store.setActiveMode(ActiveMode.KID)
        val stack = ApiWiring.create(server!!.url("/").toString(), KidTubeJson.instance, session.store, clock)
        val repo = SnapshotRepository(stack.remote, db.snapshotDao(), session.store, clock)
        val events = EventRepository(db.localEventDao(), clock, session.store)

        assertEquals(ApiResult.Success(PullResult.UPDATED), repo.pull())

        assertEquals(listOf("Ali", "Sara", "Omar", "Noor", "Pip"), db.childDao().getAll().map { it.name })
        assertEquals("Asia/Karachi", session.store.currentInfo().familyTimezone) // timezone comes from the snapshot
        var meta = db.snapshotDao().meta()!!
        assertEquals(ETAG, meta.etag)
        assertEquals(1, meta.historyCycle)
        assertEquals(Instant.parse("2026-11-05T10:00:00Z"), meta.lastPullStartedAt)

        // An event acknowledged during this period is overlaid until a pull that started after the ack has landed.
        val event = events.record(EventType.VIEW_COMPLETE, CHILD_A, EventPayloads.viewComplete(ITEM_1, 0.95))
        events.markAcked(listOf(event.id)) // ackedCycle 2
        assertEquals(1, events.overlayEvents(CHILD_A).size)

        assertEquals(ApiResult.Success(PullResult.NOT_MODIFIED), repo.pull())

        meta = db.snapshotDao().meta()!!
        assertEquals(2, meta.historyCycle) // 304 advanced it
        assertEquals(2, meta.currentCycle)
        assertTrue("the 304 pull started after the ack, so the ETag already covers the event", events.overlayEvents(CHILD_A).isEmpty())
        assertEquals(listOf(null, ETAG), requests.map { it.first })
        assertTrue(requests.all { it.second == "Bearer access-1" }) // parent tokens, even in kid mode
    }

    @Test
    fun aFailedPullChangesNothingButTheCycleCounter() = runBlocking {
        val requests = startServer()
        val baseUrl = server!!.url("/").toString()
        server!!.close() // unreachable
        session.store.saveParentLogin("access-1", "refresh-1", "fam-1")
        val stack = ApiWiring.create(baseUrl, KidTubeJson.instance, session.store, clock)
        val repo = SnapshotRepository(stack.remote, db.snapshotDao(), session.store, clock)

        assertTrue(repo.pull() is ApiResult.NetworkError)

        assertTrue(db.childDao().getAll().isEmpty())
        val meta = db.snapshotDao().meta()!!
        assertNull(meta.etag)
        assertEquals(0, meta.historyCycle)
        assertTrue(requests.isEmpty())
    }

    private companion object {
        /** The ETag the backend generated for the pull fixture (73/74). */
        val ETAG: String = ContractFixtures.load(73).headers.getValue("etag")
    }
}
