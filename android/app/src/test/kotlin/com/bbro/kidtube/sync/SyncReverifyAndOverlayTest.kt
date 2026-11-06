package com.bbro.kidtube.sync

import com.bbro.kidtube.data.entity.LocalEventState
import com.bbro.kidtube.data.remote.EventType
import com.bbro.kidtube.data.remote.HistoryViewsDto
import com.bbro.kidtube.data.remote.KidTubeJson
import com.bbro.kidtube.data.remote.Platform
import com.bbro.kidtube.data.remote.VideoStatus
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.LocalDate

class SyncReverifyAndOverlayTest : SyncTestBase() {

    // ---- reverify (step d) -----------------------------------------------------------------------------------------

    private fun reverifyIds() = requests("POST", "/videos/").map { it.path.removePrefix("/videos/").removeSuffix("/reverify") }

    @Test
    fun reverifyIsLimitedToFivePerCycleAndOncePerHourPerVideo() = runBlocking {
        loginAsParent()
        makeYoutubeVideosPending() // all 8 YouTube videos of the fixture become pending
        seedSnapshot()
        val youtube = serverSnapshot.videos.filter { it.platform == Platform.YOUTUBE && it.status == VideoStatus.PENDING }.map { it.id }.toSet()
        assertEquals(8, youtube.size) // 6 verified + 1 unavailable + ... all YouTube ones were forced to pending

        engine.runCycle()
        assertEquals("at most 5 per cycle", 5, reverifyIds().size)
        val first = reverifyIds().toSet()

        seen.clear()
        engine.runCycle()
        val second = reverifyIds().toSet()
        assertTrue("the already-tried ones wait an hour; only the rest are tried", second.intersect(first).isEmpty())
        assertEquals(youtube.size - 5, second.size)

        seen.clear()
        engine.runCycle()
        assertTrue("everything was tried within the hour", reverifyIds().isEmpty())

        clock.advance(Duration.ofMinutes(59))
        seen.clear()
        engine.runCycle()
        assertTrue(reverifyIds().isEmpty())

        clock.advance(Duration.ofMinutes(2)) // 61 minutes since the first attempts
        seen.clear()
        engine.runCycle()
        assertEquals("the first five are due again, longest-waiting first", first, reverifyIds().toSet())
    }

    @Test
    fun facebookIsNeverReverified() = runBlocking {
        loginAsParent()
        seedSnapshot() // fixture 73 has a pending Facebook video and no pending YouTube ones
        assertTrue(serverSnapshot.videos.any { it.platform == Platform.FACEBOOK && it.status == VideoStatus.PENDING })

        engine.runCycle()

        assertTrue(reverifyIds().isEmpty())
        assertTrue(db.reverifyDao().all().isEmpty())
    }

    @Test
    fun aFailedReverifyStillWaitsAnHour() = runBlocking {
        loginAsParent()
        makeYoutubeVideosPending()
        serverSnapshot = serverSnapshot.copy(videos = serverSnapshot.videos.take(1))
        seedSnapshot()
        route("POST /videos/*") { json(503, "{}") }

        assertEquals(SyncOutcome.Success, engine.runCycle()) // reverify trouble is not a cycle failure
        assertEquals(1, reverifyIds().size)
        seen.clear()
        engine.runCycle()
        assertTrue(reverifyIds().isEmpty())
    }

    @Test
    fun attemptsForVideosThatLeftTheSnapshotArePruned() = runBlocking {
        loginAsParent()
        makeYoutubeVideosPending()
        seedSnapshot()
        engine.runCycle()
        assertEquals(5, db.reverifyDao().all().size)

        serverSnapshot = serverSnapshot.copy(videos = emptyList(), children = serverSnapshot.children.map { it.copy(plan = null) })
        serverEtag = "\"gone\""
        engine.runCycle()

        assertTrue(db.reverifyDao().all().isEmpty())
    }

    // ---- the overlay across a full cycle ---------------------------------------------------------------------------------

    private suspend fun mergedViews(itemId: String = ITEM_1): Int {
        val fromServer = db.historyDao().views(CHILD_A).filter { it.planItemId == itemId }.sumOf { it.count }
        val fromLocal = events.overlayEvents(CHILD_A).count {
            it.type == EventType.VIEW_COMPLETE &&
                KidTubeJson.instance.parseToJsonElement(it.payload).jsonObject["plan_item_id"]?.jsonPrimitive?.content == itemId
        }
        return fromServer + fromLocal
    }

    @Test
    fun anEventIsCountedExactlyOnceBeforeDuringAndAfterAFullCycle() = runBlocking {
        loginAsParent()
        seedSnapshot()
        val before = mergedViews()
        assertEquals(1, before) // the fixture's one server view
        val e = recordView()
        assertEquals("pending, overlaid", before + 1, mergedViews())

        // What the server's NEXT snapshot holds once it has received the event: one more view today.
        val today = LocalDate.of(2026, 11, 5)
        serverSnapshot = serverSnapshot.copy(
            children = serverSnapshot.children.map {
                if (it.id == CHILD_A) it.copy(history = it.history.copy(views = it.history.views + HistoryViewsDto(ITEM_1, today, 1))) else it
            },
        )
        serverEtag = "\"after-push\""
        val duringPull = mutableListOf<Int>()
        route("GET /sync/pull") { req ->
            // Between the ack and the stored snapshot the event must still be counted (never lost).
            duringPull += runBlocking { mergedViews() }
            pullResponse(req)
        }

        assertEquals(SyncOutcome.Success, engine.runCycle())

        assertEquals("acked but the snapshot not stored yet: still counted once", listOf(before + 1), duringPull)
        assertEquals("history now includes it and the overlay row is gone: not double counted", before + 1, mergedViews())
        assertNull(db.localEventDao().get(e.id))
        assertEquals(1, db.historyDao().views(CHILD_A).filter { it.planItemId == ITEM_1 && it.localDate == today }.sumOf { it.count })
    }

    @Test
    fun aRejectedEventStopsCountingImmediately() = runBlocking {
        loginAsParent()
        seedSnapshot()
        val e = recordView()
        assertEquals(2, mergedViews())
        route("POST /sync/push") { req ->
            val body = KidTubeJson.instance.decodeFromString(com.bbro.kidtube.data.remote.PushBody.serializer(), req.body!!.utf8())
            val answer = com.bbro.kidtube.data.remote.PushResult(
                videos = emptyList(),
                events = body.events.mapIndexed { i, ev ->
                    com.bbro.kidtube.data.remote.PushEventResult(i, ev.id, com.bbro.kidtube.data.remote.EventResultStatus.REJECTED, "unknown child", false)
                },
            )
            json(200, KidTubeJson.instance.encodeToString(com.bbro.kidtube.data.remote.PushResult.serializer(), answer))
        }

        val outcome = engine.runCycle()

        assertEquals(SyncOutcome.PartialFailure(SyncCounts(rejectedEvents = 1)), outcome)
        assertEquals(1, mergedViews())
        assertEquals(LocalEventState.REJECTED, db.localEventDao().get(e.id)!!.state)
    }

    @Test
    fun anEventRecordedWhileThePullIsInFlightIsNeverLostAndNeverCountedTwice() = runBlocking {
        loginAsParent()
        seedSnapshot()
        val first = recordView()
        // Once the server has received "first" its snapshot holds one more view (and therefore a new ETag).
        serverSnapshot = serverSnapshot.copy(
            children = serverSnapshot.children.map {
                if (it.id == CHILD_A) it.copy(history = it.history.copy(views = it.history.views + HistoryViewsDto(ITEM_1, LocalDate.of(2026, 11, 5), 1))) else it
            },
        )
        serverEtag = "\"has-first\""
        var late: com.bbro.kidtube.data.entity.LocalEventEntity? = null
        route("GET /sync/pull") { req ->
            // A view finishes while the pull is on the wire: it is pending, so it is overlaid (not in the snapshot).
            late = runBlocking { recordView() }
            pullResponse(req)
        }

        engine.runCycle()

        assertNull("pushed before the pull, so the pull's snapshot covers it", db.localEventDao().get(first.id))
        assertEquals(LocalEventState.PENDING, db.localEventDao().get(late!!.id)!!.state)
        assertEquals("1 old view + first (now in history) + the late pending event", 3, mergedViews())

        // next cycle pushes the late event; once a later snapshot has it, it is dropped
        serverSnapshot = serverSnapshot.copy(
            children = serverSnapshot.children.map {
                if (it.id == CHILD_A) it.copy(history = it.history.copy(views = it.history.views.dropLast(1) + HistoryViewsDto(ITEM_1, LocalDate.of(2026, 11, 5), 2))) else it
            },
        )
        serverEtag = "\"later\""
        route("GET /sync/pull") { pullResponse(it) }
        engine.runCycle()
        assertNull(db.localEventDao().get(late!!.id))
        assertEquals(3, mergedViews())
    }
}
