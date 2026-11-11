package com.bbro.kidtube.player

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
 * (screen on, in the foreground). The kid screens report `screenEntered/screenLeft` (composition) and
 * `onForeground/onBackground` (lifecycle); leaving kid mode removes the screens, so the session ends there too. The picker has no
 * child and therefore no session. A ticker writes a chunk at least every minute while running.
 */
@Singleton
class KidSessionManager internal constructor(
    private val clock: Clock,
    private val session: SessionStore,
    private val writer: KidEventWriter,
    private val scope: CoroutineScope,
    private val tickMs: Long,
) {
    @Inject
    constructor(clock: Clock, session: SessionStore, writer: KidEventWriter) :
        this(clock, session, writer, CoroutineScope(SupervisorJob() + Dispatchers.Default), TICK_MS)

    private val lock = Any()
    @Volatile private var zone: ZoneId = FamilyTimeZone.DEFAULT
    private val tracker = AppSessionTracker(clock, { zone }, writer)
    private var screens = 0
    private var childId: String? = null
    private var resumed = false
    private var ticker: Job? = null

    /** A kid screen for [childId] (grid, player, status screen) is now shown. */
    fun screenEntered(childId: String) = synchronized(lock) {
        if (this.childId != null && this.childId != childId) {
            // Switching child: the previous child's time ends here. (Cannot normally happen without leaving first.)
            screens = 0
        }
        this.childId = childId
        screens++
        reconcile()
    }

    fun screenLeft(childId: String) = synchronized(lock) {
        if (this.childId != childId) return@synchronized
        screens = (screens - 1).coerceAtLeast(0)
        if (screens == 0) this.childId = null
        reconcile()
    }

    fun onForeground() = synchronized(lock) {
        resumed = true
        reconcile()
    }

    fun onBackground() = synchronized(lock) {
        resumed = false
        reconcile()
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
        val shouldRun = child != null && screens > 0 && resumed
        if (shouldRun && (!tracker.isRunning || runningChild != child)) {
            tracker.start(child!!)
            runningChild = child
            startTicker()
        } else if (!shouldRun && tracker.isRunning) {
            tracker.stop()
            runningChild = null
            ticker?.cancel()
            ticker = null
        }
    }

    private var runningChild: String? = null

    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive) {
                delay(tickMs)
                refreshZone()
                synchronized(lock) { tracker.tick() }
            }
        }
    }

    private suspend fun refreshZone() {
        val id = session.currentInfo().familyTimezone ?: return
        zone = runCatching { ZoneId.of(id) }.getOrDefault(FamilyTimeZone.DEFAULT)
    }

    companion object {
        const val TICK_MS = 5_000L
    }
}
