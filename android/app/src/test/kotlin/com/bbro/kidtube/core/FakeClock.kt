package com.bbro.kidtube.core

import java.time.Duration
import java.time.Instant

class FakeClock(
    private var instant: Instant = Instant.parse("2026-01-01T00:00:00Z"),
    private var elapsedMs: Long = 0L,
) : Clock {
    override fun now(): Instant = instant

    override fun elapsedRealtimeMillis(): Long = elapsedMs

    /** Moves both wall-clock and monotonic time forward. */
    fun advance(duration: Duration) {
        instant = instant.plus(duration)
        elapsedMs += duration.toMillis()
    }

    /** Simulates the user changing the device clock: wall-clock jumps, monotonic time does not. */
    fun setWallClock(newInstant: Instant) {
        instant = newInstant
    }
}
