package com.bbro.kidtube.core

import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import android.os.SystemClock as AndroidSystemClock

/** The one place allowed to read the real clock (see ClockRuleTest). */
@Singleton
class DeviceClock @Inject constructor() : Clock {
    override fun now(): Instant = Instant.now()

    override fun elapsedRealtimeMillis(): Long = AndroidSystemClock.elapsedRealtime()
}
