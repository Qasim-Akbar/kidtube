package com.bbro.kidtube.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * The only way app code may ask "what time is it". Never call System.currentTimeMillis,
 * Instant.now, LocalDate.now (etc.) directly; inject a [Clock] instead. The one real
 * implementation is [DeviceClock]; tests use a fake. ClockRuleTest enforces this.
 */
interface Clock {
    /** Wall-clock time. Can jump if the user changes the device clock. */
    fun now(): Instant

    /** Monotonic time since boot (includes deep sleep); for clock-tamper checks and durations. */
    fun elapsedRealtimeMillis(): Long
}

/** The calendar date "today" in [zone]; day boundaries follow the child's local day, not UTC. */
fun Clock.today(zone: ZoneId): LocalDate = now().atZone(zone).toLocalDate()
