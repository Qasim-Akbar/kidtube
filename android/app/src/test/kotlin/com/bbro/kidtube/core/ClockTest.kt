package com.bbro.kidtube.core

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class ClockTest {

    @Test
    fun today_followsTheChildsLocalDay_notUtc() {
        // 20:00 UTC on 5 Nov is already 01:00 on 6 Nov in Karachi (UTC+5).
        val clock = FakeClock(Instant.parse("2026-11-05T20:00:00Z"))

        assertEquals(LocalDate.of(2026, 11, 5), clock.today(ZoneOffset.UTC))
        assertEquals(LocalDate.of(2026, 11, 6), clock.today(FamilyTimeZone.DEFAULT))
    }

    @Test
    fun today_rollsOverWhenTheFakeClockAdvancesPastLocalMidnight() {
        val clock = FakeClock(Instant.parse("2026-11-05T18:59:00Z")) // 23:59 in Karachi
        assertEquals(LocalDate.of(2026, 11, 5), clock.today(FamilyTimeZone.DEFAULT))

        clock.advance(Duration.ofMinutes(2))

        assertEquals(LocalDate.of(2026, 11, 6), clock.today(FamilyTimeZone.DEFAULT))
    }

    @Test
    fun changingTheWallClock_doesNotMoveMonotonicTime() {
        val clock = FakeClock(elapsedMs = 1_000)

        clock.setWallClock(Instant.parse("2030-01-01T00:00:00Z"))

        assertEquals(1_000, clock.elapsedRealtimeMillis())
    }
}
