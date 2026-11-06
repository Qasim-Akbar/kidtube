package com.bbro.kidtube.sync

import com.bbro.kidtube.data.entity.LocalEventEntity
import com.bbro.kidtube.data.entity.LocalEventState
import com.bbro.kidtube.data.entity.OutboxVideoState
import com.bbro.kidtube.data.remote.EventResultStatus
import com.bbro.kidtube.data.remote.KidTubeJson
import com.bbro.kidtube.data.remote.PushBody
import com.bbro.kidtube.data.remote.PushEventResult
import com.bbro.kidtube.data.remote.PushResult
import com.bbro.kidtube.testing.ContractFixtures
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Push, pull ordering, per-item result mapping, retries and the loop guard. */
class SyncEngineTest : SyncTestBase() {

    private suspend fun allOutbox() = db.openHelper.readableDatabase.query("SELECT id, state, serverVideoId, rejectReason, attempts FROM outbox_video ORDER BY createdAt, id")
        .use { c -> buildList { while (c.moveToNext()) add(listOf(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getInt(4).toString())) } }

    private suspend fun event(id: String): LocalEventEntity? = db.localEventDao().get(id)

    // ---- order ---------------------------------------------------------------------------------------------------------

    @Test
    fun pushHappensBeforePull() = runBlocking {
        loginAsParent()
        outboxRepo.enqueue("https://www.youtube.com/watch?v=vid00000001", emptyList())
        recordWatch()

        assertEquals(SyncOutcome.Success, engine.runCycle())

        assertEquals(listOf("POST /sync/push", "GET /sync/pull"), seen.map { "${it.method} ${it.path}" })
    }

    @Test
    fun anEventAckedByThePushIsPrunedByTheVeryNextPull() = runBlocking {
        loginAsParent()
        val e = recordView()

        engine.runCycle()

        // ackedCycle = (pull cycle that follows) and that pull stored a snapshot, so the overlay row is gone.
        assertNull(event(e.id))
        assertTrue(events.overlayEvents(CHILD_A).isEmpty())
    }

    // ---- mixed batch (contract fixture 69) ---------------------------------------------------------------------------------

    @Test
    fun mixedBatchResultsMapToTheRightStates() = runBlocking {
        loginAsParent()
        val fixture = ContractFixtures.load(69)
        val sentUrls = KidTubeJson.instance.decodeFromJsonElement(PushBody.serializer(), fixture.requestBody!!).videos.map { it.url }
        val ids = sentUrls.map { enqueueLink(it) }
        val evs = listOf(recordWatch(), recordWatch(), recordWatch())
        route("POST /sync/push") { req ->
            val body = KidTubeJson.instance.decodeFromString(PushBody.serializer(), req.body!!.utf8())
            val generated = fixture.decodeBody<PushResult>()
            // videos exactly as the real server answered; events with the ids this test generated
            val statuses = listOf(EventResultStatus.ACCEPTED, EventResultStatus.REJECTED, EventResultStatus.DUPLICATE)
            val answer = generated.copy(
                events = body.events.mapIndexed { i, e ->
                    PushEventResult(i, e.id, statuses[i], if (statuses[i] == EventResultStatus.REJECTED) "payload.seconds out of range" else null, false)
                },
            )
            json(200, KidTubeJson.instance.encodeToString(PushResult.serializer(), answer))
        }

        val outcome = engine.runCycle()

        assertEquals(SyncOutcome.PartialFailure(SyncCounts(rejectedVideos = 1, rejectedEvents = 1)), outcome)
        val rows = allOutbox().associateBy { it[0] }
        assertEquals(listOf("synced", "00000000-0000-4000-8000-000000000041"), rows.getValue(ids[0]).subList(1, 3))
        // "created" but still pending on the server (slow oEmbed, retryable=true): synced, NOT an error and NOT resent
        assertEquals(listOf("synced", "00000000-0000-4000-8000-000000000042"), rows.getValue(ids[1]).subList(1, 3))
        assertEquals(listOf("rejected", null, "Only YouTube and Facebook links are supported"), rows.getValue(ids[2]).subList(1, 4))
        assertEquals(listOf("synced", "00000000-0000-4000-8000-000000000008"), rows.getValue(ids[3]).subList(1, 3)) // existing
        assertEquals(listOf(LocalEventState.ACKED, LocalEventState.REJECTED, LocalEventState.ACKED), evs.map { event(it.id)?.state ?: LocalEventState.ACKED })
        assertEquals("payload.seconds out of range", event(evs[1].id)!!.rejectReason)

        // Nothing is retried: a second cycle sends no push at all.
        seen.clear()
        assertEquals(SyncOutcome.Success, engine.runCycle())
        assertTrue(requests("POST", "/sync/push").isEmpty())
    }

    @Test
    fun aKidRoleDeviceSendsEventsOnlyAndNeverTouchesParentOnlyEndpoints() = runBlocking {
        pairAsKid()
        makeYoutubeVideosPending()
        seedSnapshot()
        // A video can't be in a kid device's outbox in the real app; even if one is, it must not be sent.
        outboxRepo.enqueue("https://www.youtube.com/watch?v=vid00000001", listOf(com.bbro.kidtube.data.repo.PlacementSpec(CHILD_A)))
        val e = recordWatch()

        assertEquals(SyncOutcome.Success, engine.runCycle())

        val body = pushBodies().single()
        assertTrue(body.videos.isEmpty())
        assertEquals(listOf(e.id), body.events.map { it.id })
        assertEquals("Bearer device-token-1", seen.first().auth)
        assertTrue(requests("POST", "/children/").isEmpty())
        // Amendment: reverify is parent-only, so a kid-role device never calls it (and records no attempt).
        assertTrue(requests("POST", "/videos/").isEmpty())
        assertTrue(db.reverifyDao().all().isEmpty())
        assertEquals(OutboxVideoState.PENDING.wire, allOutbox().single()[1])
    }

    @Test
    fun kidDeviceVideoRejectedAsForbiddenIsPermanentButEventsStillProcessed() = runBlocking {
        // The server-side behaviour behind the rule above (fixture 70): the video is rejected, the event accepted.
        val result = ContractFixtures.load(70).decodeBody<PushResult>()
        assertEquals("forbidden", result.videos.single().reason)
        assertEquals(EventResultStatus.ACCEPTED, result.events.single().status)
    }

    // ---- batching and the loop guard (amendment 3) ---------------------------------------------------------------------------

    @Test
    fun largeOutboxesAreSentInCappedBatchesUntilDrained() = runBlocking {
        loginAsParent()
        repeat(120) { outboxRepo.enqueue("https://www.youtube.com/watch?v=v$it", emptyList()) }
        repeat(450) { recordWatch() }

        assertEquals(SyncOutcome.Success, engine.runCycle())

        val bodies = pushBodies()
        assertEquals(listOf(50 to 200, 50 to 200, 20 to 50), bodies.map { it.videos.size to it.events.size })
        assertTrue(events.pending().isEmpty())
        assertTrue(db.outboxDao().pendingVideos().isEmpty())
    }

    @Test
    fun aServerThatNeverAnswersForOneItemDoesNotBlockTheOthersOrSpin() = runBlocking {
        loginAsParent()
        val silent = recordWatch()
        val ok1 = recordWatch()
        val ok2 = recordWatch()
        val v1 = enqueueLink("https://www.youtube.com/watch?v=aaaaaaaaaaa")
        val vSilent = enqueueLink("https://www.youtube.com/watch?v=bbbbbbbbbbb")
        route("POST /sync/push") { req ->
            val body = KidTubeJson.instance.decodeFromString(PushBody.serializer(), req.body!!.utf8())
            val full = acceptAll(body)
            // drop every result for the silent event and the second video
            val answer = PushResult(
                videos = full.videos.filter { it.index != 1 },
                events = full.events.filter { body.events[it.index].id != silent.id },
            )
            json(200, KidTubeJson.instance.encodeToString(PushResult.serializer(), answer))
        }

        val outcome = engine.runCycle()

        assertEquals(SyncOutcome.Success, outcome)
        assertEquals("exactly one push: the silent items were not retried within the cycle", 1, pushBodies().size)
        assertEquals(LocalEventState.PENDING, event(silent.id)!!.state)
        assertNull(event(ok1.id)) // acked, then pruned by the pull
        assertNull(event(ok2.id))
        val rows = allOutbox().associateBy { it[0] }
        assertEquals("synced", rows.getValue(v1)[1])
        assertEquals("pending", rows.getValue(vSilent)[1])
        assertEquals("attempts bumped for the unanswered video", "1", rows.getValue(vSilent)[4])
        assertEquals(1, requests("GET", "/sync/pull").size) // the cycle still got to its pull
    }

    @Test
    fun theLoopStopsAfter20PassesEvenWhenEveryPassMakesProgress() = runBlocking {
        loginAsParent()
        val total = 20 * 200 + 123
        repeat(total) { i ->
            db.localEventDao().insert(
                LocalEventEntity(
                    id = "00000000-0000-4000-8000-%012d".format(i), childId = CHILD_A,
                    type = com.bbro.kidtube.data.remote.EventType.WATCH_PROGRESS, occurredAt = clock.now(),
                    localDate = java.time.LocalDate.of(2026, 11, 5), payload = "{}", state = LocalEventState.PENDING,
                    ackedCycle = null, rejectReason = null, createdAt = clock.now().plusMillis(i.toLong()),
                ),
            )
        }

        assertEquals(SyncOutcome.Success, engine.runCycle())

        assertEquals(SyncEngine.MAX_PUSH_PASSES, pushBodies().size)
        assertEquals(123, events.pending(5000).size) // the rest waits for the next cycle
        assertEquals(1, requests("GET", "/sync/pull").size)
    }

    // ---- crash safety --------------------------------------------------------------------------------------------------------

    @Test
    fun resendAfterACrashBetweenTheResponseAndTheLocalUpdateEndsInTheSameState() = runBlocking {
        loginAsParent()
        val e = recordWatch()
        val v = outboxRepo.enqueue("https://www.youtube.com/watch?v=vid00000001", emptyList())
        engine.runCycle()
        // Simulate "the process died after the server answered but before Room was updated": the local rows are back in
        // their pre-push state while the server already has the data.
        executeSql("UPDATE local_event SET state = 'pending', ackedCycle = NULL")
        executeSql("UPDATE outbox_video SET state = 'pending', serverVideoId = NULL")
        // (the event row may have been pruned by the pull: put it back)
        if (event(e.id) == null) db.localEventDao().insert(e)
        executeSql("UPDATE local_event SET state = 'pending', ackedCycle = NULL")
        route("POST /sync/push") { req ->
            val body = KidTubeJson.instance.decodeFromString(PushBody.serializer(), req.body!!.utf8())
            val answer = PushResult(
                videos = body.videos.mapIndexed { i, _ ->
                    com.bbro.kidtube.data.remote.PushVideoResult(i, com.bbro.kidtube.data.remote.PushVideoStatus.EXISTING, "srv-1", com.bbro.kidtube.data.remote.VideoStatus.VERIFIED, null, false)
                },
                events = body.events.mapIndexed { i, ev -> PushEventResult(i, ev.id, EventResultStatus.DUPLICATE, null, false) },
            )
            json(200, KidTubeJson.instance.encodeToString(PushResult.serializer(), answer))
        }

        assertEquals(SyncOutcome.Success, engine.runCycle())

        assertEquals("synced", allOutbox().single { it[0] == v }[1])
        assertTrue("the duplicate counted as acked and was pruned by the pull", events.pending().isEmpty())
        assertTrue(events.overlayEvents(CHILD_A).isEmpty())
    }

    // ---- failure outcomes ------------------------------------------------------------------------------------------------------

    @Test
    fun networkErrorIsRetryLaterAndNothingIsLost() = runBlocking {
        loginAsParent()
        val e = recordWatch()
        server.close()

        assertEquals(SyncOutcome.RetryLater, engine.runCycle())

        assertEquals(LocalEventState.PENDING, event(e.id)!!.state)
    }

    @Test
    fun serverErrorIsRetryLater() = runBlocking {
        loginAsParent()
        recordWatch()
        route("POST /sync/push") { json(503, """{"detail":"down"}""") }

        assertEquals(SyncOutcome.RetryLater, engine.runCycle())
        assertTrue(requests("GET", "/sync/pull").isEmpty()) // a failed push stops the cycle
    }

    @Test
    fun rateLimitedHonoursRetryAfterOnPush() = runBlocking {
        loginAsParent()
        recordWatch()
        val retryAfter = ContractFixtures.load(84).headers.getValue("retry-after")
        route("POST /sync/push") { json(429, """{"detail":"slow down"}""", "Retry-After" to retryAfter) }

        assertEquals(SyncOutcome.RateLimited(retryAfter.toLong()), engine.runCycle())
    }

    @Test
    fun rateLimitedHonoursRetryAfterOnPull() = runBlocking {
        loginAsParent()
        route("GET /sync/pull") { json(429, """{"detail":"slow down"}""", "Retry-After" to "489") }

        assertEquals(SyncOutcome.RateLimited(489), engine.runCycle())
    }

    @Test
    fun aRateLimitWithoutAReadableRetryAfterIsNull() = runBlocking {
        loginAsParent()
        route("GET /sync/pull") { json(429, "{}", "Retry-After" to "Wed, 21 Oct 2026 07:28:00 GMT") }

        assertEquals(SyncOutcome.RateLimited(null), engine.runCycle())
    }

    @Test
    fun a304AdvancesTheHistoryCycleAndPrunesAckedEvents() = runBlocking {
        loginAsParent()
        seedSnapshot() // cycle 1, ETag stored
        val e = events.record(com.bbro.kidtube.data.remote.EventType.PLAYBACK_ERROR, CHILD_A, com.bbro.kidtube.data.repo.EventPayloads.playbackError("v", 150))

        assertEquals(SyncOutcome.Success, engine.runCycle())

        val meta = db.snapshotDao().meta()!!
        assertEquals(2, meta.currentCycle)
        assertEquals("the 304 advanced history_cycle to the pull's cycle", 2, meta.historyCycle)
        assertEquals(serverEtag, seen.single { it.path == "/sync/pull" }.ifNoneMatch)
        assertNull(event(e.id))
    }

    // ---- sessions -----------------------------------------------------------------------------------------------------------------

    @Test
    fun a401IsRefreshedOnceInsideTheCycleAndThePushIsRetried() = runBlocking {
        session.store.saveParentLogin("old-access", "refresh-1", "fam-1")
        recordWatch()
        route("POST /auth/refresh") { json(200, """{"access_token":"new-access","token_type":"bearer"}""") }
        route("POST /sync/push") { req ->
            if (req.headers["Authorization"] == "Bearer old-access") {
                json(401, """{"detail":"Invalid or missing credentials"}""")
            } else {
                val body = KidTubeJson.instance.decodeFromString(PushBody.serializer(), req.body!!.utf8())
                json(200, KidTubeJson.instance.encodeToString(PushResult.serializer(), acceptAll(body)))
            }
        }

        assertEquals(SyncOutcome.Success, engine.runCycle())

        assertEquals(1, requests("POST", "/auth/refresh").size)
        assertEquals(listOf("Bearer old-access", "Bearer new-access"), requests("POST", "/sync/push").map { it.auth })
        assertEquals("Bearer new-access", requests("GET", "/sync/pull").single().auth)
    }

    @Test
    fun aRejectedRefreshMeansNeedsLoginAndNothingIsSentAfterwards() = runBlocking {
        session.store.saveParentLogin("old-access", "refresh-1", "fam-1")
        val e = recordWatch()
        route("POST /auth/refresh") { json(401, """{"detail":"Invalid refresh token"}""") }
        route("POST /sync/push") { json(401, """{"detail":"Invalid or missing credentials"}""") }

        assertEquals(SyncOutcome.NeedsLogin, engine.runCycle())
        assertEquals(LocalEventState.PENDING, event(e.id)!!.state) // data is kept

        seen.clear()
        assertEquals(SyncOutcome.NeedsLogin, engine.runCycle())
        assertTrue("no request while the session is dead", seen.isEmpty())
    }

    @Test
    fun aRevokedKidDeviceStopsSyncing() = runBlocking {
        pairAsKid()
        val e = recordWatch()
        route("POST /sync/push") { json(401, """{"detail":"Invalid or missing credentials"}""") }

        assertEquals(SyncOutcome.DeviceRevoked, engine.runCycle())
        assertEquals(LocalEventState.PENDING, event(e.id)!!.state)

        seen.clear()
        assertEquals(SyncOutcome.DeviceRevoked, engine.runCycle())
        assertTrue(seen.isEmpty())
    }

    @Test
    fun aFreshInstallDoesNothing() = runBlocking {
        assertEquals(SyncOutcome.NeedsLogin, engine.runCycle())
        assertTrue(seen.isEmpty())
    }

    // ---- single flight ---------------------------------------------------------------------------------------------------------------

    @Test
    fun concurrentCallsShareOneCycle() = runBlocking {
        loginAsParent()
        route("GET /sync/pull") { req ->
            Thread.sleep(400) // keep the first cycle busy while the second caller arrives
            pullResponse(req)
        }

        val outcomes = listOf(
            async(Dispatchers.Default) { engine.runCycle() },
            async(Dispatchers.Default) { Thread.sleep(100); engine.runCycle() },
        ).awaitAll()

        assertEquals(listOf(SyncOutcome.Success, SyncOutcome.Success), outcomes)
        assertEquals("one cycle, one pull", 1, requests("GET", "/sync/pull").size)
    }

    // ---- status ---------------------------------------------------------------------------------------------------------------------

    @Test
    fun statusReportsCountsAndLastSuccess() = runBlocking {
        loginAsParent()
        recordWatch()
        outboxRepo.enqueue("https://www.youtube.com/watch?v=vid00000001", emptyList())

        val before = statusProvider.status.first()
        assertEquals(1, before.pendingEvents)
        assertEquals(1, before.pendingVideos)
        assertNull(before.lastSuccessAt)

        engine.runCycle()

        val after = statusProvider.status.first()
        assertEquals(0, after.pendingEvents)
        assertEquals(0, after.pendingVideos)
        assertEquals(clock.now(), after.lastSuccessAt)
        assertNull(after.lastError)
        assertEquals(SyncStatus.Phase.IDLE, after.phase)
    }
}
