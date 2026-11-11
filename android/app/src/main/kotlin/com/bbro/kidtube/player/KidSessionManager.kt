package com.bbro.kidtube.player

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.bbro.kidtube.core.Clock
import com.bbro.kidtube.core.FamilyTimeZone
import com.bbro.kidtube.core.SessionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the one [AppSessionTracker]: app time counts while at least one kid screen of a child is shown AND the app is resumed
 * (screen on, in the foreground). The kid screens report `screenEntered/screenLeft` (composition); foreground comes from the
 * PROCESS lifecycle ([bindForeground]); leaving kid mode removes the screens, so the session ends there too. The picker has no
 * child and therefore no session. A ticker writes a chunk at least every minute while running.
 *
 * Moving between kid screens (grid -> player -> grid) is one continuous stretch: a short [graceMs] after the last screen left
 * keeps the session open, and if no screen returns it ends at the moment that screen left (not when the grace ran out).
 */
@Singleton
class KidSessionManager internal constructor(
    private val clock: Clock,
    private val session: SessionStore,
    private val writer: KidEventWriter,
    private val scope: CoroutineScope,
    private val tickMs: Long,
    private val graceMs: Long,
) {
    @Inject
    constructor(clock: Clock, session: SessionStore, writer: KidEventWriter) :
        this(clock, session, writer, CoroutineScope(SupervisorJob() + Dispatchers.Default), TICK_MS, HANDOVER_GRACE_MS)

    private val lock = Any()
    @Volatile private var zone: ZoneId = FamilyTimeZone.DEFAULT
    private val tracker = AppSessionTracker(clock, { zone }, writer)
    private var screens = 0
    private var childId: String? = null
    private var resumed = false
    private var ticker: Job? = null
    private var runningChild: String? = null

    /** Monotonic time the last kid screen left; non-null only during the handover grace. */
    private var leftAtElapsed: Long? = null
    private var graceJob: Job? = null

    /**
     * Follows the PROCESS lifecycle (ProcessLifecycleOwner in production, a fake in tests): resumed = the app is in the
     * foreground with the screen on. A navigation entry's own lifecycle must not be used: moving grid -> player pauses the
     * grid's entry a moment before the player's resumes, which would end and restart the session at every navigation.
     */
    fun bindForeground(lifecycle: Lifecycle) {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onResume(owner: LifecycleOwner) = onForeground()
            override fun onPause(owner: LifecycleOwner) = onBackground()
        })
    }

    /** A kid screen for [childId] (grid, player, status screen) is now shown. */
    fun screenEntered(childId: String) = synchronized(lock) {
        if (this.childId != null && this.childId != childId) {
            // Switching child: the previous child's time ends where its last screen left.
            screens = 0
            endSession()
        }
        cancelGrace()
        this.childId = childId
        screens++
        reconcile()
    }

    fun screenLeft(childId: String) = synchronized(lock) {
        if (this.childId != childId) return@synchronized
        screens = (screens - 1).coerceAtLeast(0)
        if (screens > 0) return@synchronized
        if (graceMs <= 0) {
            endSession()
        } else {
            leftAtElapsed = clock.elapsedRealtimeMillis()
            graceJob?.cancel()
            graceJob = scope.launch {
                delay(graceMs)
                synchronized(lock) { if (screens == 0) endSession() }
            }
        }
    }

    fun onForeground() = synchronized(lock) {
        resumed = true
        reconcile()
    }

    fun onBackground() = synchronized(lock) {
        resumed = false
        if (screens == 0) endSession() else reconcile()
    }

    /** Whole seconds of the running session not yet written (live app-time limit check). */
    fun unflushedSeconds(): Int = synchronized(lock) { tracker.unflushedSeconds() }

    /** Writes the running chunk and returns when it (and everything submitted before) is in Room. */
    suspend fun flushNow() {
        synchronized(lock) { tracker.flush(final = false) }
        writer.awaitIdle()
    }

    private fun reconcile() {
        val child = childId
        val shouldRun = child != null && resumed && (screens > 0 || leftAtElapsed != null)
        if (shouldRun && (!tracker.isRunning || runningChild != child)) {
            tracker.start(child)
            runningChild = child
            startTicker()
        } else if (!shouldRun && tracker.isRunning) {
            stopTracking(leftAtElapsed ?: clock.elapsedRealtimeMillis())
        }
    }

    private fun cancelGrace() {
        graceJob?.cancel()
        graceJob = null
        leftAtElapsed = null
    }

    /** Ends the session where the last screen left (or now) and forgets the child. */
    private fun endSession() {
        stopTracking(leftAtElapsed ?: clock.elapsedRealtimeMillis())
        cancelGrace()
        if (screens == 0) childId = null
    }

    private fun stopTracking(atElapsed: Long) {
        if (tracker.isRunning) tracker.stop(atElapsed)
        runningChild = null
        ticker?.cancel()
        ticker = null
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive) {
                delay(tickMs)
                refreshZone()
                synchronized(lock) { if (leftAtElapsed == null) tracker.tick() } // no tick past the moment the last screen left
            }
        }
    }

    private suspend fun refreshZone() {
        val id = session.currentInfo().familyTimezone ?: return
        zone = runCatching { ZoneId.of(id) }.getOrDefault(FamilyTimeZone.DEFAULT)
    }

    companion object {
        const val TICK_MS = 5_000L

        /** How long with no kid screen still counts as the same stretch (grid -> player -> grid handovers). */
        const val HANDOVER_GRACE_MS = 1_500L
    }
}
