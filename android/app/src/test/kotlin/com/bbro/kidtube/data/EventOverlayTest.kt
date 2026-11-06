package com.bbro.kidtube.data

import com.bbro.kidtube.data.entity.LocalEventEntity
import com.bbro.kidtube.data.entity.LocalEventState
import com.bbro.kidtube.data.remote.EventResultStatus
import com.bbro.kidtube.data.remote.EventType
import com.bbro.kidtube.data.remote.KidTubeJson
import com.bbro.kidtube.data.repo.EventAck
import com.bbro.kidtube.data.repo.EventPayloads
import com.bbro.kidtube.data.repo.EventRepository
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

class EventOverlayTest : RoomTestBase() {
    private lateinit var events: EventRepository
    private val snapshots get() = db.snapshotDao()

    @Before
    fun createRepository() {
        events = EventRepository(db.localEventDao(), clock, session.store)
    }

    // ---- local_date / occurred_at --------------------------------------------------------------------

    @Test
    fun recordStampsUuidUtcTimeAndPendingState() = runBlocking {
        clock.setWallClock(Instant.parse("2026-11-05T12:00:00.123456789Z"))
        val payload = EventPayloads.viewComplete(ITEM_1, 0.95)

        val event = events.record(EventType.VIEW_COMPLETE, CHILD_A, payload)

        UUID.fromString(event.id) // is a UUID
        assertEquals(CHILD_A, event.childId)
        assertEquals(EventType.VIEW_COMPLETE, event.type)
        assertEquals(Instant.parse("2026-11-05T12:00:00.123Z"), event.occurredAt) // millisecond precision
        assertEquals(LocalEventState.PENDING, event.state)
        assertNull(event.ackedCycle)
        assertEquals(event, db.localEventDao().get(event.id))
        assertEquals(ITEM_1, KidTubeJson.instance.parseToJsonElement(event.payload).jsonObject["plan_item_id"]!!.jsonPrimitive.content)
    }

    @Test
    fun localDateRollsOverAtKarachiMidnightNotUtcMidnight() = runBlocking {
        // 2026-11-05 20:00 UTC is already 01:00 on 6 Nov in Karachi (UTC+5), and no snapshot has been pulled yet.
        clock.setWallClock(Instant.parse("2026-11-05T20:00:00Z"))
        assertEquals(LocalDate.of(2026, 11, 6), events.record(EventType.WATCH_PROGRESS, CHILD_A, EventPayloads.watchProgress("v", 5.0)).localDate)
    }

    @Test
    fun localDateBoundaryIsExactToTheMillisecond() = runBlocking {
        session.store.setFamilyTimezone("Asia/Karachi")
        clock.setWallClock(Instant.parse("2026-11-05T18:59:59.999Z")) // 23:59:59.999 in Karachi
        val last = events.record(EventType.WATCH_PROGRESS, CHILD_A, EventPayloads.watchProgress("v", 1.0))
        clock.setWallClock(Instant.parse("2026-11-05T19:00:00.000Z")) // 00:00:00.000
        val first = events.record(EventType.WATCH_PROGRESS, CHILD_A, EventPayloads.watchProgress("v", 1.0))

        assertEquals(LocalDate.of(2026, 11, 5), last.localDate)
        assertEquals(LocalDate.of(2026, 11, 6), first.localDate)
    }

    @Test
    fun localDateUsesTheFamilyTimezoneFromTheSnapshot() = runBlocking {
        clock.setWallClock(Instant.parse("2026-11-05T20:00:00Z"))
        session.store.setFamilyTimezone("America/New_York") // 15:00 on 5 Nov
        assertEquals(LocalDate.of(2026, 11, 5), events.record(EventType.WATCH_PROGRESS, CHILD_A, EventPayloads.watchProgress("v", 1.0)).localDate)
    }

    @Test
    fun anUnknownStoredTimezoneFallsBackToKarachi() = runBlocking {
        clock.setWallClock(Instant.parse("2026-11-05T20:00:00Z"))
        session.store.setFamilyTimezone("Mars/Olympus_Mons")
        assertEquals(LocalDate.of(2026, 11, 6), events.record(EventType.WATCH_PROGRESS, CHILD_A, EventPayloads.watchProgress("v", 1.0)).localDate)
    }

    // ---- the overlay rule (SPEC section 9) -----------------------------------------------------------------

    /** Stored snapshot is from pull 3. */
    private suspend fun historyAtCycle3() {
        repeat(3) { snapshots.beginPull(Instant.EPOCH) }
        snapshots.markUnchanged(3)
    }

    private suspend fun insert(state: LocalEventState, ackedCycle: Long?, childId: String = CHILD_A): String {
        val id = UUID.randomUUID().toString()
        db.localEventDao().insert(
            LocalEventEntity(
                id = id, childId = childId, type = EventType.VIEW_COMPLETE, occurredAt = clock.now(),
                localDate = LocalDate.of(2026, 11, 5), payload = "{}", state = state, ackedCycle = ackedCycle,
                rejectReason = null, createdAt = clock.now(),
            ),
        )
        return id
    }

    @Test
    fun pendingEventsAreOverlaid() = runBlocking {
        historyAtCycle3()
        val id = insert(LocalEventState.PENDING, null)
        assertEquals(listOf(id), events.overlayEvents(CHILD_A).map { it.id })
    }

    @Test
    fun eventsAckedInAnOlderOrTheSameCycleAsTheHistoryAreNotOverlaid() = runBlocking {
        historyAtCycle3()
        insert(LocalEventState.ACKED, 2) // older cycle: the snapshot already counts it
        insert(LocalEventState.ACKED, 3) // same cycle: the snapshot of pull 3 started after the ack
        assertTrue(events.overlayEvents(CHILD_A).isEmpty())
    }

    @Test
    fun eventsAckedInANewerCycleThanTheHistoryAreOverlaid() = runBlocking {
        historyAtCycle3()
        val id = insert(LocalEventState.ACKED, 4)
        assertEquals(listOf(id), events.overlayEvents(CHILD_A).map { it.id })
    }

    @Test
    fun rejectedEventsAreNeverOverlaid() = runBlocking {
        historyAtCycle3()
        insert(LocalEventState.REJECTED, null)
        insert(LocalEventState.REJECTED, 99)
        assertTrue(events.overlayEvents(CHILD_A).isEmpty())
    }

    @Test
    fun overlayIsPerChild() = runBlocking {
        historyAtCycle3()
        val a = insert(LocalEventState.PENDING, null, CHILD_A)
        val b = insert(LocalEventState.PENDING, null, CHILD_B)
        assertEquals(listOf(a), events.overlayEvents(CHILD_A).map { it.id })
        assertEquals(listOf(b), events.overlayEvents(CHILD_B).map { it.id })
    }

    @Test
    fun beforeTheFirstPullEveryAckedEventIsOverlaid() = runBlocking {
        val pending = events.record(EventType.VIEW_COMPLETE, CHILD_A, EventPayloads.viewComplete(ITEM_1, 1.0))
        val acked = events.record(EventType.VIEW_COMPLETE, CHILD_A, EventPayloads.viewComplete(ITEM_1, 1.0))
        events.markAcked(listOf(acked.id)) // no sync_meta row yet: ackedCycle = 0 + 1

        assertEquals(setOf(pending.id, acked.id), events.overlayEvents(CHILD_A).map { it.id }.toSet())
        assertEquals(1L, db.localEventDao().get(acked.id)!!.ackedCycle)
    }

    // ---- push and pull interleavings: nothing double counted, nothing missing -----------------------------------

    private suspend fun mergedViews(childId: String, itemId: String): Int {
        val fromServer = db.historyDao().views(childId).filter { it.planItemId == itemId }.sumOf { it.count }
        val fromLocal = events.overlayEvents(childId).count {
            it.type == EventType.VIEW_COMPLETE &&
                KidTubeJson.instance.parseToJsonElement(it.payload).jsonObject["plan_item_id"]?.jsonPrimitive?.content == itemId
        }
        return fromServer + fromLocal
    }

    /** The fixture snapshot (1 server view of ITEM_1), with [extraViews] more views dated today. */
    private fun snapshotWithServerViews(extraViews: Int) = loadSnapshot().let { s ->
        val amna = s.children[0]
        val today = com.bbro.kidtube.data.remote.HistoryViewsDto(ITEM_1, LocalDate.of(2026, 11, 5), extraViews)
        s.copy(children = listOf(amna.copy(history = amna.history.copy(views = amna.history.views + (if (extraViews > 0) listOf(today) else emptyList()))), s.children[1]))
    }

    private suspend fun pullStores(snapshot: com.bbro.kidtube.data.remote.SnapshotDto): Long {
        val cycle = snapshots.beginPull(clock.now())
        snapshots.replaceSnapshot(snapshot.toData(), "\"e$cycle\"", cycle)
        return cycle
    }

    @Test
    fun ackBeforeThePullStartsIsCountedOnceByTheSnapshotAndThenDropped() = runBlocking {
        pullStores(snapshotWithServerViews(0)) // cycle 1
        assertEquals(1, mergedViews(CHILD_A, ITEM_1))

        val event = events.record(EventType.VIEW_COMPLETE, CHILD_A, EventPayloads.viewComplete(ITEM_1, 0.95))
        assertEquals(2, mergedViews(CHILD_A, ITEM_1)) // pending, overlaid

        events.markAcked(listOf(event.id)) // push answered while no pull is running: ackedCycle = 1 + 1
        assertEquals(2L, db.localEventDao().get(event.id)!!.ackedCycle)
        assertEquals(2, mergedViews(CHILD_A, ITEM_1)) // acked after the stored snapshot: still overlaid, no gap

        pullStores(snapshotWithServerViews(1)) // cycle 2 started after the ack, so its history includes the event
        assertEquals(2, mergedViews(CHILD_A, ITEM_1)) // history 4 + no overlay: not double counted
        assertNull(db.localEventDao().get(event.id)) // and the row is gone
    }

    @Test
    fun ackWhileAPullIsInFlightKeepsTheOverlayUntilTheNextPull() = runBlocking {
        pullStores(snapshotWithServerViews(0)) // cycle 1
        val event = events.record(EventType.VIEW_COMPLETE, CHILD_A, EventPayloads.viewComplete(ITEM_1, 0.95))

        val inFlight = snapshots.beginPull(clock.now()) // pull 2 starts ...
        events.markAcked(listOf(event.id)) // ... then the push is answered: ackedCycle = 2 + 1
        assertEquals(3L, db.localEventDao().get(event.id)!!.ackedCycle)
        // pull 2's response was computed before the event was counted: it does not include it
        snapshots.replaceSnapshot(snapshotWithServerViews(0).toData(), "\"e2\"", inFlight)

        assertEquals("not missing: the event must still be counted", 2, mergedViews(CHILD_A, ITEM_1))
        assertEquals(1, events.overlayEvents(CHILD_A).size)

        pullStores(snapshotWithServerViews(1)) // pull 3 includes it
        assertEquals("not double counted", 2, mergedViews(CHILD_A, ITEM_1))
        assertTrue(events.overlayEvents(CHILD_A).isEmpty())
    }

    @Test
    fun a304ForAPullThatStartedAfterTheAckDropsTheOverlay() = runBlocking {
        pullStores(snapshotWithServerViews(0)) // cycle 1
        // an acked event that changes nothing in history (e.g. a playback_error): ack, then a conditional pull -> 304
        val event = events.record(EventType.PLAYBACK_ERROR, CHILD_A, EventPayloads.playbackError("v", 150))
        events.markAcked(listOf(event.id)) // ackedCycle 2
        val cycle = snapshots.beginPull(clock.now()) // pull 2 starts after the ack
        snapshots.markUnchanged(cycle) // 304

        assertEquals(2L, snapshots.meta()!!.historyCycle)
        assertTrue(events.overlayEvents(CHILD_A).isEmpty())
    }

    @Test
    fun a304ForAPullThatStartedBeforeTheAckKeepsTheOverlay() = runBlocking {
        pullStores(snapshotWithServerViews(0)) // cycle 1
        val event = events.record(EventType.VIEW_COMPLETE, CHILD_A, EventPayloads.viewComplete(ITEM_1, 1.0))
        val cycle = snapshots.beginPull(clock.now()) // pull 2 starts ...
        events.markAcked(listOf(event.id)) // ... ack lands during it: ackedCycle 3
        snapshots.markUnchanged(cycle) // 304 for pull 2

        assertEquals(1, events.overlayEvents(CHILD_A).size)
        assertEquals(2, mergedViews(CHILD_A, ITEM_1))
    }

    @Test
    fun rejectedEventsStopCountingImmediately() = runBlocking {
        pullStores(snapshotWithServerViews(0))
        val event = events.record(EventType.VIEW_COMPLETE, CHILD_A, EventPayloads.viewComplete(ITEM_1, 1.0))
        assertEquals(2, mergedViews(CHILD_A, ITEM_1))

        events.markRejected(event.id, "unknown child")

        assertEquals(1, mergedViews(CHILD_A, ITEM_1))
        assertEquals("unknown child", db.localEventDao().get(event.id)!!.rejectReason)
    }

    // ---- applying a push answer ------------------------------------------------------------------------------------

    @Test
    fun applyResultsAcksAcceptedAndDuplicateRejectsRejectedAndLeavesUnknownPending() = runBlocking {
        pullStores(snapshotWithServerViews(0))
        val sent = (1..4).map { events.record(EventType.VIEW_COMPLETE, CHILD_A, EventPayloads.viewComplete(ITEM_1, 1.0)) }

        events.applyResults(
            sent,
            listOf(
                EventAck(sent[0].id, 0, EventResultStatus.ACCEPTED, null),
                EventAck(sent[1].id, 1, EventResultStatus.DUPLICATE, null),
                EventAck(null, 2, EventResultStatus.REJECTED, "unknown child"), // matched by index when the id is missing
                EventAck(sent[3].id, 3, EventResultStatus.UNKNOWN, null),
            ),
        )

        val states = sent.map { db.localEventDao().get(it.id)!!.state }
        assertEquals(
            listOf(LocalEventState.ACKED, LocalEventState.ACKED, LocalEventState.REJECTED, LocalEventState.PENDING),
            states,
        )
        assertEquals("unknown child", db.localEventDao().get(sent[2].id)!!.rejectReason)
        assertEquals(listOf(sent[3].id), events.pending().map { it.id })
    }

    @Test
    fun ackingTwiceDoesNotMoveTheCycleStamp() = runBlocking {
        pullStores(snapshotWithServerViews(0))
        val event = events.record(EventType.VIEW_COMPLETE, CHILD_A, EventPayloads.viewComplete(ITEM_1, 1.0))
        events.markAcked(listOf(event.id))
        val stamp = db.localEventDao().get(event.id)!!.ackedCycle

        snapshots.beginPull(clock.now())
        assertEquals(0, events.markAcked(listOf(event.id)))
        assertEquals(stamp, db.localEventDao().get(event.id)!!.ackedCycle)
    }

    @Test
    fun pendingBatchIsOldestFirstAndCapped() = runBlocking {
        val first = events.record(EventType.VIEW_COMPLETE, CHILD_A, EventPayloads.viewComplete(ITEM_1, 1.0))
        clock.advance(Duration.ofSeconds(1))
        val second = events.record(EventType.VIEW_COMPLETE, CHILD_A, EventPayloads.viewComplete(ITEM_1, 1.0))

        assertEquals(listOf(first.id, second.id), events.pending().map { it.id })
        assertEquals(listOf(first.id), events.pending(limit = 1).map { it.id })
    }

    @Test
    fun payloadsMatchTheServerValidator() {
        val view = EventPayloads.viewComplete(ITEM_1, 0.9)
        assertEquals(setOf("plan_item_id", "watched_pct"), view.keys)
        assertEquals(setOf("video_id", "seconds"), EventPayloads.watchProgress("v", 3.0).keys)
        val session = EventPayloads.appSession(Instant.parse("2026-11-05T10:00:00Z"), Instant.parse("2026-11-05T10:05:00.5Z"))
        assertEquals("2026-11-05T10:00:00Z", session["start"]!!.jsonPrimitive.content)
        assertEquals("2026-11-05T10:05:00.500Z", session["end"]!!.jsonPrimitive.content)
        assertEquals(setOf("video_id", "code"), EventPayloads.playbackError("v", 150).keys)
        assertEquals(setOf("seconds"), EventPayloads.timeExtension(900.0).keys)
    }
}
