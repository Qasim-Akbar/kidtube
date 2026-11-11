package com.bbro.kidtube.player

import com.bbro.kidtube.data.RoomTestBase
import com.bbro.kidtube.data.remote.EventType
import com.bbro.kidtube.data.remote.KidTubeJson
import com.bbro.kidtube.data.repo.EventRepository
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Duration
import java.time.Instant

/** App time while kid screens are shown and the app is resumed: starts, pauses on background, ends when the screens leave. */
class KidSessionManagerTest : RoomTestBase() {
    private val scope = CoroutineScope(Dispatchers.Default)
    private lateinit var writer: KidEventWriter
    private lateinit var manager: KidSessionManager

    @Before
    fun setUp() {
        clock.setWallClock(Instant.parse("2026-01-05T10:00:00Z"))
        writer = KidEventWriter(EventRepository(db.localEventDao(), clock, session.store), scope)
        manager = KidSessionManager(clock, session.store, writer, scope, tickMs = 10, graceMs = 0)
    }

    @After
    fun tearDown() = scope.cancel()

    private suspend fun sessionSeconds(child: String? = null): Long {
        writer.awaitIdle()
        return db.localEventDao().pending(100).filter { it.type == EventType.APP_SESSION && (child == null || it.childId == child) }.sumOf {
            val p = KidTubeJson.instance.parseToJsonElement(it.payload).jsonObject
            Duration.between(Instant.parse(p.getValue("start").jsonPrimitive.content), Instant.parse(p.getValue("end").jsonPrimitive.content)).seconds
        }
    }

    @Test
    fun countsOnlyWhileAKidScreenIsShownAndTheAppIsResumed() = runBlocking {
        manager.screenEntered(CHILD_A)
        clock.advance(Duration.ofSeconds(30)) // shown but not resumed: nothing yet
        assertEquals(0, manager.unflushedSeconds())

        manager.onForeground()
        clock.advance(Duration.ofSeconds(20))
        assertEquals(20, manager.unflushedSeconds())

        manager.onBackground() // screen off / app in the background: the time so far is written, counting stops
        clock.advance(Duration.ofSeconds(500))
        assertEquals(20L, sessionSeconds())

        manager.onForeground()
        clock.advance(Duration.ofSeconds(5))
        manager.screenLeft(CHILD_A) // leaving the kid screens (or kid mode) ends it
        assertEquals(25L, sessionSeconds())
        clock.advance(Duration.ofSeconds(100))
        assertEquals(25L, sessionSeconds())
    }

    @Test
    fun gridToPlayerHandoverDoesNotCutOrDoubleTheSession() = runBlocking {
        manager.onForeground()
        manager.screenEntered(CHILD_A) // grid
        clock.advance(Duration.ofSeconds(10))
        manager.screenEntered(CHILD_A) // the player appears before the grid leaves
        manager.screenLeft(CHILD_A)
        clock.advance(Duration.ofSeconds(10))
        manager.screenLeft(CHILD_A)
        assertEquals(20L, sessionSeconds())
    }

    @Test
    fun theTickerWritesAChunkEveryMinuteWhileRunning() = runBlocking {
        manager.onForeground()
        manager.screenEntered(CHILD_A)
        clock.advance(Duration.ofSeconds(61))
        delay(300) // the 10 ms ticker notices
        assertEquals(61L, sessionSeconds()) // written without any stop: a kill now would lose nothing
        manager.screenLeft(CHILD_A)
    }

    @Test
    fun flushNowWritesTheRunningChunkAndKeepsRunning() = runBlocking {
        manager.onForeground()
        manager.screenEntered(CHILD_A)
        clock.advance(Duration.ofSeconds(12))
        manager.flushNow()
        assertEquals(12L, sessionSeconds())
        clock.advance(Duration.ofSeconds(3))
        manager.screenLeft(CHILD_A)
        assertEquals(15L, sessionSeconds())
    }

    @Test
    fun theSessionBelongsToTheChildOfTheShownScreen() = runBlocking {
        manager.onForeground()
        manager.screenEntered(CHILD_A)
        clock.advance(Duration.ofSeconds(10))
        manager.screenLeft(CHILD_A)
        manager.screenEntered(CHILD_B)
        clock.advance(Duration.ofSeconds(4))
        manager.screenLeft(CHILD_B)
        assertEquals(10L, sessionSeconds(CHILD_A))
        assertEquals(4L, sessionSeconds(CHILD_B))
        assertTrue(db.localEventDao().pending(100).all { it.type == EventType.APP_SESSION })
    }

    // ---- one continuous session across navigation; foreground = the process lifecycle ----------------------------

    private class FakeOwner : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry
    }

    private fun appSessions() = runBlocking {
        writer.awaitIdle()
        db.localEventDao().pending(100).filter { it.type == EventType.APP_SESSION }.map {
            val p = KidTubeJson.instance.parseToJsonElement(it.payload).jsonObject
            Instant.parse(p.getValue("start").jsonPrimitive.content) to Instant.parse(p.getValue("end").jsonPrimitive.content)
        }.sortedBy { it.first }
    }

    private fun withGrace(): Pair<KidSessionManager, FakeOwner> {
        val m = KidSessionManager(clock, session.store, writer, scope, tickMs = 10, graceMs = 300)
        val owner = FakeOwner().also { m.bindForeground(it.lifecycle) }
        owner.registry.currentState = Lifecycle.State.RESUMED
        return m to owner
    }

    @Test
    fun gridToPlayerToGridIsOneContinuousSessionEvenWhenNoScreenIsShownForAMoment() = runBlocking {
        val (m, _) = withGrace()
        m.screenEntered(CHILD_A) // grid
        clock.advance(Duration.ofSeconds(10))
        m.screenEntered(CHILD_A) // player opens ...
        m.screenLeft(CHILD_A) // ... grid disposed
        clock.advance(Duration.ofSeconds(10))
        m.screenLeft(CHILD_A) // player closes first (worst case) ...
        clock.advance(Duration.ofSeconds(1)) // ... nothing shown for a second ...
        m.screenEntered(CHILD_A) // ... then the grid is back
        clock.advance(Duration.ofSeconds(5))
        m.screenLeft(CHILD_A) // leaving kid mode
        clock.advance(Duration.ofSeconds(50)) // time passing after the last screen left is not counted
        delay(700) // the grace runs out and the session ends where the last screen left
        val s = appSessions()
        assertEquals(1, s.size) // one session: no gap, no restart
        assertEquals(26L, Duration.between(s.single().first, s.single().second).seconds)
    }

    @Test
    fun realBackgroundAndScreenOffStillEndTheSession() = runBlocking {
        val (m, owner) = withGrace()
        m.screenEntered(CHILD_A)
        clock.advance(Duration.ofSeconds(8))
        owner.registry.currentState = Lifecycle.State.STARTED // process paused: screen off / another app
        clock.advance(Duration.ofSeconds(100))
        assertEquals(8L, sessionSeconds())
        owner.registry.currentState = Lifecycle.State.RESUMED
        clock.advance(Duration.ofSeconds(4))
        m.screenLeft(CHILD_A)
        delay(700)
        assertEquals(12L, sessionSeconds())
    }

    @Test
    fun leavingKidModeEndsItAtTheMomentTheLastScreenLeft() = runBlocking {
        val (m, _) = withGrace()
        m.screenEntered(CHILD_A)
        clock.advance(Duration.ofSeconds(7))
        m.screenLeft(CHILD_A)
        clock.advance(Duration.ofSeconds(30))
        delay(700)
        assertEquals(7L, sessionSeconds())
        clock.advance(Duration.ofSeconds(30))
        assertEquals(7L, sessionSeconds()) // nothing keeps counting
    }
}
