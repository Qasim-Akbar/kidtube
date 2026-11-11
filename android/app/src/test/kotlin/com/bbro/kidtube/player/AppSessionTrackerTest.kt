package com.bbro.kidtube.player

import com.bbro.kidtube.core.FakeClock
import com.bbro.kidtube.data.remote.EventType
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** App (foreground) time: chunks of at most a minute, whole seconds, no gaps or overlaps, split at the child's local midnight. */
class AppSessionTrackerTest {
    private val karachi = ZoneId.of("Asia/Karachi")
    private val clock = FakeClock(Instant.parse("2026-01-05T10:00:00Z"))
    private val events = mutableListOf<TrackedEvent>()
    private val tracker = AppSessionTracker(clock, { karachi }, { events += it })

    private fun TrackedEvent.start() = Instant.parse(payload.getValue("start").jsonPrimitive.content)
    private fun TrackedEvent.end() = Instant.parse(payload.getValue("end").jsonPrimitive.content)
    private fun TrackedEvent.seconds() = Duration.between(start(), end()).seconds
    private fun advance(seconds: Long, tickEvery: Long = 5) {
        var left = seconds
        while (left > 0) {
            val step = minOf(tickEvery, left)
            clock.advance(Duration.ofSeconds(step))
            tracker.tick()
            left -= step
        }
    }

    @Test
    fun writesAChunkAtLeastEveryMinuteAndTheRestOnStop() {
        tracker.start("child-1")
        advance(59)
        assertTrue(events.isEmpty())
        advance(1)
        assertEquals(listOf(60L), events.map { it.seconds() })
        advance(30)
        tracker.stop()
        assertEquals(listOf(60L, 30L), events.map { it.seconds() })
        assertTrue(events.all { it.type == EventType.APP_SESSION && it.childId == "child-1" })
    }

    @Test
    fun chunksTouchWithoutGapsOrOverlapsAndAreWholeSeconds() {
        tracker.start("child-1")
        advance(200, tickEvery = 7) // ticks do not line up with the minute
        tracker.stop()
        events.zipWithNext().forEach { (a, b) -> assertEquals(a.end(), b.start()) }
        assertEquals(200L, events.sumOf { it.seconds() })
        assertTrue(events.all { it.start().nano == 0 && it.end().nano == 0 })
    }

    @Test
    fun aKilledProcessLosesAtMostAMinute() {
        tracker.start("child-1")
        advance(179) // killed here: no stop()
        val written = events.sumOf { it.seconds() }
        assertTrue("written $written of 179", 179 - written <= APP_SESSION_FLUSH_SECONDS)
    }

    @Test
    fun aSessionAcrossLocalMidnightIsSplitIntoOneEventPerDate() {
        clock.setWallClock(Instant.parse("2026-01-05T18:59:30Z")) // 23:59:30 Monday in Karachi
        tracker.start("child-1")
        advance(90, tickEvery = 1)
        tracker.stop()
        val perDate = events.groupBy({ it.localDate }, { it.seconds() }).mapValues { it.value.sum() }
        assertEquals(mapOf(LocalDate.of(2026, 1, 5) to 30L, LocalDate.of(2026, 1, 6) to 60L), perDate)
        // The Monday part ends exactly at midnight and the Tuesday part starts there.
        val monday = events.first { it.localDate == LocalDate.of(2026, 1, 5) }
        val tuesday = events.first { it.localDate == LocalDate.of(2026, 1, 6) }
        assertEquals(Instant.parse("2026-01-05T19:00:00Z"), monday.end())
        assertEquals(monday.end(), tuesday.start())
    }

    @Test
    fun aChunkThatCrossesMidnightBetweenTicksStillSplitsExactly() {
        clock.setWallClock(Instant.parse("2026-01-05T18:59:50Z"))
        tracker.start("child-1")
        clock.advance(Duration.ofSeconds(40)) // one big step over midnight
        tracker.tick()
        val perDate = events.groupBy({ it.localDate }, { it.seconds() }).mapValues { it.value.sum() }
        assertEquals(mapOf(LocalDate.of(2026, 1, 5) to 10L, LocalDate.of(2026, 1, 6) to 30L), perDate)
    }

    @Test
    fun payloadMatchesTheBackendShape() {
        tracker.start("child-1")
        advance(60)
        val p = events.single().payload
        assertEquals(setOf("start", "end"), p.keys)
        assertTrue(events.single().end() >= events.single().start()) // the server rejects end < start
        assertTrue(events.single().seconds() <= 24 * 3600) // ... and sessions longer than 24 h
    }

    @Test
    fun unflushedSecondsFollowTheRunningChunk() {
        assertEquals(0, tracker.unflushedSeconds())
        tracker.start("child-1")
        advance(20)
        assertEquals(20, tracker.unflushedSeconds())
        tracker.flush()
        assertEquals(0, tracker.unflushedSeconds())
        advance(7)
        assertEquals(7, tracker.unflushedSeconds())
        tracker.stop()
        assertEquals(0, tracker.unflushedSeconds())
    }

    @Test
    fun startingForAnotherChildEndsThePreviousOne() {
        tracker.start("child-1")
        advance(10)
        tracker.start("child-2")
        advance(5)
        tracker.stop()
        assertEquals(listOf("child-1" to 10L, "child-2" to 5L), events.map { it.childId to it.seconds() })
    }

    @Test
    fun stoppingTwiceOrBeforeStartingWritesNothingExtra() {
        tracker.stop()
        tracker.start("child-1")
        advance(10)
        tracker.stop()
        tracker.stop()
        assertEquals(1, events.size)
    }
}
