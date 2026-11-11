package com.bbro.kidtube.player

import com.bbro.kidtube.core.FakeClock
import com.bbro.kidtube.data.remote.EventType
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** PlaybackTracker with a FakeClock and a fake player: playing time only, no gaps or overlaps, one view, errors, midnight. */
class PlaybackTrackerTest {
    private val karachi = ZoneId.of("Asia/Karachi")
    private val clock = FakeClock(Instant.parse("2026-01-05T10:00:00Z")) // 15:00 Monday in Karachi
    private val events = mutableListOf<TrackedEvent>()

    private fun tracker(rule: ViewRule = ViewRule.Threshold(0.9)) =
        PlaybackTracker(clock, { karachi }, "child-1", "video-1", "item-1", rule, { events += it })

    private fun PlaybackTracker.playFor(seconds: Int, tickEvery: Int = 1) {
        repeat(seconds / tickEvery) {
            clock.advance(Duration.ofSeconds(tickEvery.toLong()))
            tick()
        }
    }

    private fun watch() = events.filter { it.type == EventType.WATCH_PROGRESS }
    private fun views() = events.filter { it.type == EventType.VIEW_COMPLETE }
    private fun errors() = events.filter { it.type == EventType.PLAYBACK_ERROR }
    private fun JsonObject.seconds() = getValue("seconds").jsonPrimitive.doubleOrNull
    private fun List<TrackedEvent>.totalSeconds() = sumOf { it.payload.seconds()!! }

    // ---- watch_progress ---------------------------------------------------------------------------------------------

    @Test
    fun playingTimeIsWrittenOnPause() {
        val t = tracker()
        t.onPlaying()
        clock.advance(Duration.ofSeconds(10))
        t.onPaused()
        assertEquals(listOf(10.0), watch().map { it.payload.seconds() })
        assertEquals("video-1", watch().single().payload.getValue("video_id").jsonPrimitive.content)
    }

    @Test
    fun flushesEveryThirtySecondsOfPlayingWithNoGapsOrOverlaps() {
        val t = tracker()
        t.onPlaying()
        t.playFor(75)
        t.onPaused()
        assertEquals(listOf(30.0, 30.0, 15.0), watch().map { it.payload.seconds() })
        assertEquals(75.0, watch().totalSeconds(), 0.0)
        assertTrue(watch().all { it.payload.seconds()!! % 1.0 == 0.0 }) // whole seconds
    }

    @Test
    fun theSubSecondRemainderIsCarriedSoNothingIsLostOrCountedTwice() {
        val t = tracker()
        t.onPlaying(); clock.advance(Duration.ofMillis(10_600)); t.onPaused() // 10.6 -> writes 10, carries 0.6
        t.onPlaying(); clock.advance(Duration.ofMillis(10_600)); t.onPaused() // 21.2 total -> writes 11 more
        assertEquals(listOf(10.0, 11.0), watch().map { it.payload.seconds() })
        t.leave() // final flush rounds 21.2 to 21: already written
        assertEquals(21.0, watch().totalSeconds(), 0.0)
    }

    @Test
    fun leaveRoundsTheLastFractionToTheNearestSecond() {
        val t = tracker()
        t.onPlaying(); clock.advance(Duration.ofMillis(4_700)); t.leave()
        assertEquals(listOf(5.0), watch().map { it.payload.seconds() })
    }

    @Test
    fun seekingForwardAddsNoWatchTime() {
        val t = tracker()
        t.onDuration(600.0)
        t.onPlaying()
        t.onPosition(5.0)
        clock.advance(Duration.ofSeconds(5))
        t.onPosition(300.0) // jumped 295 s ahead
        clock.advance(Duration.ofSeconds(5))
        t.onPaused()
        assertEquals(10.0, watch().totalSeconds(), 0.0)
    }

    @Test
    fun bufferingAndPausedTimeDoNotCount() {
        val t = tracker()
        t.onPlaying(); clock.advance(Duration.ofSeconds(4))
        t.onBuffering(); clock.advance(Duration.ofSeconds(20))
        t.onPlaying(); clock.advance(Duration.ofSeconds(3))
        t.onPaused(); clock.advance(Duration.ofSeconds(60))
        assertEquals(7.0, watch().totalSeconds(), 0.0)
    }

    @Test
    fun backgroundStopsCountingAndWritesWhatWasPlayed() {
        val t = tracker()
        t.onPlaying(); clock.advance(Duration.ofSeconds(8))
        t.onBackground()
        assertEquals(listOf(8.0), watch().map { it.payload.seconds() })
        clock.advance(Duration.ofSeconds(100)) // still in the background
        t.tick()
        assertEquals(8.0, watch().totalSeconds(), 0.0)
        assertFalse(t.isPlaying)
    }

    @Test
    fun aKilledProcessLosesAtMostThirtySecondsOfPlaying() {
        val t = tracker()
        t.onPlaying()
        t.playFor(59) // never paused, never left: the process is killed here
        val written = watch().totalSeconds()
        assertTrue("written $written of 59", 59 - written <= WATCH_FLUSH_SECONDS)
        assertEquals(30.0, written, 0.0)
    }

    // ---- view_complete --------------------------------------------------------------------------------------------------

    @Test
    fun oneViewAtTheThresholdAndNeverASecond() {
        val t = tracker(ViewRule.Threshold(0.9))
        t.onDuration(100.0)
        t.onPlaying()
        t.onPosition(89.0)
        assertTrue(views().isEmpty())
        t.onPosition(90.0)
        t.onPosition(95.0)
        t.onPosition(99.0)
        t.onEnded()
        t.onEnded() // double finish
        assertEquals(1, views().size)
        assertEquals("item-1", views().single().payload.getValue("plan_item_id").jsonPrimitive.content)
        assertEquals(0.9, views().single().payload.getValue("watched_pct").jsonPrimitive.doubleOrNull!!, 1e-9)
        assertTrue(t.viewRecorded)
    }

    @Test
    fun theItemsOwnThresholdIsUsed() {
        val t = tracker(ViewRule.Threshold(0.5))
        t.onDuration(100.0); t.onPlaying(); t.onPosition(50.0)
        assertEquals(1, views().size)
    }

    @Test
    fun aVideoThatEndsCountsEvenIfThePositionNeverReachedTheThreshold() {
        val t = tracker()
        t.onDuration(100.0)
        t.onPlaying(); clock.advance(Duration.ofSeconds(40))
        t.onPosition(60.0)
        t.onEnded()
        assertEquals(1, views().size)
        assertEquals(1.0, views().single().payload.getValue("watched_pct").jsonPrimitive.doubleOrNull!!, 0.0)
    }

    @Test
    fun anEndWithNothingPlayedIsNotAView() {
        val t = tracker()
        t.onDuration(100.0)
        t.onEnded()
        assertTrue(views().isEmpty())
    }

    @Test
    fun noViewWithoutAKnownDuration() {
        val t = tracker()
        t.onPlaying(); t.onPosition(500.0)
        assertTrue(views().isEmpty())
    }

    // ---- playback_error ---------------------------------------------------------------------------------------------------

    @Test
    fun anEmbedErrorIsRecordedOnceWithItsCodeAndNeverCountsAView() {
        val t = tracker()
        t.onDuration(100.0)
        t.onPlaying(); clock.advance(Duration.ofSeconds(2))
        t.onError(150)
        t.onError(150)
        t.onPosition(100.0); t.onEnded()
        assertEquals(1, errors().size)
        assertEquals(150.0, errors().single().payload.getValue("code").jsonPrimitive.doubleOrNull!!, 0.0)
        assertEquals("video-1", errors().single().payload.getValue("video_id").jsonPrimitive.content)
        assertTrue(views().isEmpty())
        assertEquals(2.0, watch().totalSeconds(), 0.0) // the 2 s played before the error are still written
    }

    @Test
    fun leaveIsFinalAndIdempotent() {
        val t = tracker()
        t.onPlaying(); clock.advance(Duration.ofSeconds(3))
        t.leave(); t.leave()
        t.onPlaying(); clock.advance(Duration.ofSeconds(50)); t.tick(); t.leave()
        assertEquals(3.0, watch().totalSeconds(), 0.0)
    }

    // ---- Facebook: time on screen ------------------------------------------------------------------------------------------

    @Test
    fun facebookViewCountsAtTheTimeOnScreenOnceAndOnlyWhilePlayingState() {
        val t = tracker(ViewRule.TimeOnScreen(FACEBOOK_VIEW_SECONDS))
        t.onPlaying()
        t.playFor(FACEBOOK_VIEW_SECONDS - 1)
        assertTrue(views().isEmpty())
        t.onBackground() // the app was backgrounded for a long time: not counted
        clock.advance(Duration.ofSeconds(600)); t.tick()
        assertTrue(views().isEmpty())
        t.onPlaying()
        t.playFor(1)
        assertEquals(1, views().size)
        assertEquals(1.0, views().single().payload.getValue("watched_pct").jsonPrimitive.doubleOrNull!!, 0.0)
        t.playFor(60)
        assertEquals(1, views().size) // one view per session
    }

    @Test
    fun facebookDefaultIsThreeMinutes() = assertEquals(180, FACEBOOK_VIEW_SECONDS)

    // ---- the child's day ---------------------------------------------------------------------------------------------------

    @Test
    fun secondsPlayedAcrossLocalMidnightAreWrittenUnderTheirOwnDate() {
        clock.setWallClock(Instant.parse("2026-01-05T18:59:50Z")) // 23:59:50 Mon in Karachi
        val t = tracker()
        t.onPlaying()
        t.playFor(20) // crosses 00:00:00 after 10 s
        t.onPaused()
        val byDate = watch().groupBy({ it.localDate }, { it.payload.seconds()!! }).mapValues { it.value.sum() }
        assertEquals(mapOf(LocalDate.of(2026, 1, 5) to 10.0, LocalDate.of(2026, 1, 6) to 10.0), byDate)
    }

    @Test
    fun eventsCarryTheChildAndLocalDate() {
        val t = tracker()
        t.onPlaying(); clock.advance(Duration.ofSeconds(2)); t.onPaused()
        val e = events.single()
        assertEquals("child-1", e.childId)
        assertEquals(LocalDate.of(2026, 1, 5), e.localDate)
        assertEquals(Instant.parse("2026-01-05T10:00:02Z"), e.occurredAt)
    }
}
