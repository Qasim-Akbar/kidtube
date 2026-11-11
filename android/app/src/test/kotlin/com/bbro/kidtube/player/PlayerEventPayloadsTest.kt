package com.bbro.kidtube.player

import com.bbro.kidtube.core.FakeClock
import com.bbro.kidtube.data.RoomTestBase
import com.bbro.kidtube.data.remote.EventType
import com.bbro.kidtube.data.repo.EventRepository
import com.bbro.kidtube.testing.ContractFixtures
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/**
 * What the player writes is what the backend accepts: payload keys equal those of the backend's own event fixtures
 * (58_events_batch_all_types: the request body is what `POST /events` was tested with), values parse as the server requires, and the
 * events written through KidEventWriter / EventRepository carry the child, the date and a client UUID.
 */
class PlayerEventPayloadsTest : RoomTestBase() {
    private val scope = CoroutineScope(Dispatchers.Default)
    private lateinit var writer: KidEventWriter
    private lateinit var events: EventRepository

    @Before
    fun setUp() {
        clock.setWallClock(Instant.parse("2026-01-05T10:00:00Z"))
        events = EventRepository(db.localEventDao(), clock, session.store)
        writer = KidEventWriter(events, scope)
    }

    @After
    fun tearDown() = scope.cancel()

    private fun fixtureEvents(): List<JsonObject> =
        ContractFixtures.load(58).requestBody!!.jsonObject.getValue("events").jsonArray.map { it.jsonObject }

    private fun fixturePayloadKeys(type: String): Set<String> =
        fixtureEvents().first { it.getValue("type").jsonPrimitive.content == type }.getValue("payload").jsonObject.keys

    @Test
    fun payloadKeysEqualTheBackendFixtureShapes() = runBlocking {
        val tracker = PlaybackTracker(clock, { java.time.ZoneId.of("Asia/Karachi") }, CHILD_A, "video-uuid", ITEM_1, ViewRule.Threshold(0.9), writer)
        tracker.onDuration(100.0); tracker.onPlaying(); clock.advance(Duration.ofSeconds(5)); tracker.onPosition(95.0); tracker.onError(150)
        val app = AppSessionTracker(clock, { java.time.ZoneId.of("Asia/Karachi") }, writer)
        app.start(CHILD_A); clock.advance(Duration.ofSeconds(60)); app.stop()
        tracker.leave()
        writer.awaitIdle()

        val written = db.localEventDao().pending(100)
        fun keys(type: EventType) = written.first { it.type == type }.let {
            com.bbro.kidtube.data.remote.KidTubeJson.instance.parseToJsonElement(it.payload).jsonObject.keys
        }
        assertEquals(fixturePayloadKeys("view_complete"), keys(EventType.VIEW_COMPLETE))
        assertEquals(fixturePayloadKeys("watch_progress"), keys(EventType.WATCH_PROGRESS))
        assertEquals(fixturePayloadKeys("app_session"), keys(EventType.APP_SESSION))
        assertEquals(fixturePayloadKeys("playback_error"), keys(EventType.PLAYBACK_ERROR))
    }

    @Test
    fun valuesSatisfyTheServerValidation() = runBlocking {
        val zone = java.time.ZoneId.of("Asia/Karachi")
        val tracker = PlaybackTracker(clock, { zone }, CHILD_A, "00000000-0000-4000-8000-000000000008", ITEM_1, ViewRule.Threshold(0.9), writer)
        tracker.onDuration(100.0); tracker.onPlaying(); clock.advance(Duration.ofSeconds(12)); tracker.onPosition(91.5); tracker.leave()
        val app = AppSessionTracker(clock, { zone }, writer)
        app.start(CHILD_A); clock.advance(Duration.ofSeconds(75)); app.stop()
        writer.awaitIdle()

        for (e in db.localEventDao().pending(100)) {
            assertEquals(CHILD_A, e.childId)
            assertEquals(LocalDate.of(2026, 1, 5), e.localDate) // within [today - 30, today + 1]
            assertTrue(java.util.UUID.fromString(e.id).toString() == e.id)
            val p = com.bbro.kidtube.data.remote.KidTubeJson.instance.parseToJsonElement(e.payload).jsonObject
            when (e.type) {
                EventType.VIEW_COMPLETE -> assertTrue(p.getValue("watched_pct").jsonPrimitive.content.toDouble() in 0.0..1.0)
                EventType.WATCH_PROGRESS -> {
                    assertTrue(p.getValue("seconds").jsonPrimitive.content.toDouble() >= 0.0)
                    java.util.UUID.fromString(p.getValue("video_id").jsonPrimitive.content) // the server validates a UUID
                }
                EventType.APP_SESSION -> {
                    val start = Instant.parse(p.getValue("start").jsonPrimitive.content)
                    val end = Instant.parse(p.getValue("end").jsonPrimitive.content)
                    assertTrue(!end.isBefore(start) && Duration.between(start, end) <= Duration.ofHours(24))
                    assertTrue(p.getValue("start").jsonPrimitive.contentOrNull!!.endsWith("Z")) // ISO-8601 with an offset
                }
                else -> Unit
            }
        }
    }

    @Test
    fun theWriterKeepsOrderAndAwaitIdleMeansEverythingIsInRoom() = runBlocking {
        val sink: EventSink = writer
        repeat(20) { i ->
            sink.emit(TrackedEvent(EventType.WATCH_PROGRESS, CHILD_A, com.bbro.kidtube.data.repo.EventPayloads.watchProgress("v", i.toDouble()), LocalDate.of(2026, 1, 5), clock.now()))
            clock.advance(Duration.ofMillis(10)) // pending rows are ordered by createdAt
        }
        writer.awaitIdle()
        // rowid = insertion order (createdAt would race with the writer thread).
        val seconds = mutableListOf<Int>()
        db.openHelper.readableDatabase.query("SELECT payload FROM local_event ORDER BY rowid").use { c ->
            while (c.moveToNext()) {
                seconds += com.bbro.kidtube.data.remote.KidTubeJson.instance.parseToJsonElement(c.getString(0)).jsonObject.getValue("seconds").jsonPrimitive.content.toDouble().toInt()
            }
        }
        assertEquals((0 until 20).toList(), seconds)
    }

    @Test
    fun anEventForAnEarlierDayKeepsItsDateAndTime() = runBlocking {
        val e = events.record(
            EventType.APP_SESSION, CHILD_A, com.bbro.kidtube.data.repo.EventPayloads.appSession(Instant.parse("2026-01-04T18:00:00Z"), Instant.parse("2026-01-04T18:10:00Z")),
            localDate = LocalDate.of(2026, 1, 4), occurredAt = Instant.parse("2026-01-04T18:10:00Z"),
        )
        assertEquals(LocalDate.of(2026, 1, 4), e.localDate)
        assertEquals(Instant.parse("2026-01-04T18:10:00Z"), e.occurredAt)
    }
}
