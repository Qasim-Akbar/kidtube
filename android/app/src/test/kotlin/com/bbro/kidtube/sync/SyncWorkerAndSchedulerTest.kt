package com.bbro.kidtube.sync

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.robolectric.RuntimeEnvironment
import java.util.concurrent.TimeUnit

private class FakeNetworkMonitor : NetworkMonitor {
    var registered = 0
    var unregistered = 0
    var callback: (() -> Unit)? = null
    val isWatching get() = callback != null

    override fun register(onRegained: () -> Unit) {
        registered++
        callback = onRegained
    }

    override fun unregister() {
        unregistered++
        callback = null
    }
}

/** SyncWorker (TestListenableWorkerBuilder) and SyncScheduler (WorkManager test driver). */
class SyncWorkerAndSchedulerTest : SyncTestBase() {
    private lateinit var context: Context
    private lateinit var workManager: WorkManager
    private lateinit var monitor: FakeNetworkMonitor
    private lateinit var scheduler: SyncScheduler
    private val scopes = mutableListOf<CoroutineScope>()

    @Before
    fun initWorkManager() {
        context = RuntimeEnvironment.getApplication()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        workManager = WorkManager.getInstance(context)
        monitor = FakeNetworkMonitor()
        scheduler = SyncScheduler(workManager, session.store, monitor)
    }

    @After
    fun stopScopes() {
        scopes.forEach { it.cancel() }
    }

    /** Runs the scheduler's session collector inline (Unconfined) so state changes apply immediately. */
    private fun startScheduler(): Job {
        val scope = CoroutineScope(Dispatchers.Unconfined).also { scopes += it }
        return scheduler.start(scope)
    }

    private fun infos(name: String): List<WorkInfo> = workManager.getWorkInfosForUniqueWork(name).get()
    private fun live(name: String) = infos(name).filter { !it.state.isFinished }

    private fun worker(): SyncWorker {
        val factory = object : WorkerFactory() {
            override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker =
                SyncWorker(appContext, workerParameters, engine, scheduler)
        }
        return TestListenableWorkerBuilder<SyncWorker>(context).setWorkerFactory(factory).build()
    }

    // ---- worker ---------------------------------------------------------------------------------------------------------

    @Test
    fun workerRunsACycleAndSucceeds() = runBlocking {
        loginAsParent()
        recordWatch()

        assertEquals(ListenableWorker.Result.success(), worker().doWork())
        assertEquals(listOf("POST /sync/push", "GET /sync/pull"), seen.map { "${it.method} ${it.path}" })
    }

    @Test
    fun workerRetriesOnNetworkTrouble() = runBlocking {
        loginAsParent()
        server.close()

        assertEquals(ListenableWorker.Result.retry(), worker().doWork())
    }

    @Test
    fun workerSucceedsWithoutRetryWhenTheSessionIsDead() = runBlocking {
        pairAsKid()
        route("GET /sync/pull") { json(401, """{"detail":"Invalid or missing credentials"}""") }
        assertEquals(SyncOutcome.DeviceRevoked, engine.runCycle().also { seen.clear() })

        assertEquals(ListenableWorker.Result.success(), worker().doWork())
        assertTrue("no request while revoked", seen.isEmpty())
    }

    @Test
    fun rateLimitedWorkerReturnsSuccessAndSchedulesTheDelayedRunInsteadOfRetrying() = runBlocking {
        loginAsParent()
        startScheduler()
        route("GET /sync/pull") { json(429, """{"detail":"slow"}""", "Retry-After" to "120") }

        val result = worker().doWork()

        assertEquals("must not also ask WorkManager to retry", ListenableWorker.Result.success(), result)
        val delayed = live(SyncScheduler.DELAYED).single()
        assertEquals(TimeUnit.SECONDS.toMillis(120), delayed.initialDelayMillis)
    }

    @Test
    fun rateLimitWithoutRetryAfterUsesTheDefaultDelay() = runBlocking {
        loginAsParent()
        startScheduler()
        route("GET /sync/pull") { json(429, "{}") }

        worker().doWork()

        assertEquals(TimeUnit.SECONDS.toMillis(SyncScheduler.DEFAULT_RETRY_AFTER_SECONDS), live(SyncScheduler.DELAYED).single().initialDelayMillis)
    }

    // ---- scheduler -----------------------------------------------------------------------------------------------------------

    @Test
    fun anActiveSessionSchedulesPeriodicAndNowWorkAndWatchesTheNetwork() = runBlocking {
        loginAsParent()
        startScheduler()

        val periodic = live(SyncScheduler.PERIODIC).single()
        assertEquals(TimeUnit.MINUTES.toMillis(15), periodic.periodicityInfo!!.repeatIntervalMillis)
        assertEquals(androidx.work.NetworkType.CONNECTED, periodic.constraints.requiredNetworkType)
        val now = live(SyncScheduler.NOW).single()
        assertEquals(androidx.work.NetworkType.CONNECTED, now.constraints.requiredNetworkType)
        assertTrue(monitor.isWatching)
    }

    @Test
    fun aKidDeviceSyncsToo() = runBlocking {
        pairAsKid()
        startScheduler()

        assertEquals(1, live(SyncScheduler.PERIODIC).size)
    }

    @Test
    fun noSessionMeansNoWork() = runBlocking {
        startScheduler()
        scheduler.syncNow()
        scheduler.syncSoon()

        assertTrue(listOf(SyncScheduler.PERIODIC, SyncScheduler.NOW, SyncScheduler.DEBOUNCED).all { live(it).isEmpty() })
        assertFalse(monitor.isWatching)
    }

    @Test
    fun syncNowKeepsTheRequestAlreadyQueued() = runBlocking {
        loginAsParent()
        startScheduler()
        val first = live(SyncScheduler.NOW).single().id

        scheduler.syncNow()
        scheduler.syncNow()

        assertEquals(first, live(SyncScheduler.NOW).single().id)
    }

    @Test
    fun theDebouncedSyncIsDelayedBy30sAndLaterEventsDoNotPushItBack() = runBlocking {
        loginAsParent()
        startScheduler()

        scheduler.syncSoon()
        val first = live(SyncScheduler.DEBOUNCED).single()
        assertEquals(TimeUnit.SECONDS.toMillis(30), first.initialDelayMillis)

        scheduler.syncSoon()
        scheduler.syncSoon()

        assertEquals("KEEP: the first event's schedule stands", first.id, live(SyncScheduler.DEBOUNCED).single().id)
    }

    @Test
    fun recordingAnEventAndEnqueueingALinkTriggerTheRightSync() = runBlocking {
        loginAsParent()
        startScheduler()
        live(SyncScheduler.NOW).forEach { workManager.cancelWorkById(it.id).result.get() }
        val triggered = TriggerRecorder()
        val eventRepo = com.bbro.kidtube.data.repo.EventRepository(db.localEventDao(), clock, session.store, triggered)
        val outbox = com.bbro.kidtube.data.repo.OutboxRepository(db.outboxDao(), clock, triggered)

        eventRepo.record(com.bbro.kidtube.data.remote.EventType.WATCH_PROGRESS, CHILD_A, com.bbro.kidtube.data.repo.EventPayloads.watchProgress("v", 1.0))
        outbox.enqueue("https://youtu.be/dQw4w9WgXcQ", emptyList())

        assertEquals(listOf("soon", "now"), triggered.calls)
    }

    @Test
    fun networkRegainedTriggersASyncNowButTheInitialCallbackDoesNot() {
        val gate = RegainGate()
        assertFalse("the callback delivered right at registration is not a regain", gate.onAvailable())
        assertTrue(gate.onAvailable())
        assertTrue(gate.onAvailable())
    }

    @Test
    fun theSchedulerReactsToANetworkRegainBySyncingNow() = runBlocking {
        loginAsParent()
        startScheduler()
        val queued = live(SyncScheduler.NOW).single()
        workManager.cancelWorkById(queued.id).result.get()

        monitor.callback!!.invoke()

        assertEquals(1, live(SyncScheduler.NOW).size)
        assertNotEquals(queued.id, live(SyncScheduler.NOW).single().id)
    }

    @Test
    fun aDeadSessionCancelsEverythingAndARestoredOneRestartsIt() = runBlocking {
        loginAsParent()
        startScheduler()
        scheduler.syncSoon()
        assertTrue(live(SyncScheduler.PERIODIC).isNotEmpty() && live(SyncScheduler.DEBOUNCED).isNotEmpty())

        session.store.markNeedsLogin()

        listOf(SyncScheduler.PERIODIC, SyncScheduler.NOW, SyncScheduler.DEBOUNCED, SyncScheduler.DELAYED).forEach {
            assertTrue("$it should be cancelled", live(it).isEmpty())
        }
        assertFalse("network watching stops too", monitor.isWatching)
        scheduler.syncNow()
        assertTrue("and triggers are ignored while the session is dead", live(SyncScheduler.NOW).isEmpty())

        loginAsParent() // logs in again

        assertEquals(1, live(SyncScheduler.PERIODIC).size)
        assertEquals(1, live(SyncScheduler.NOW).size)
        assertTrue(monitor.isWatching)
    }

    @Test
    fun aRevokedKidDeviceStopsSyncWork() = runBlocking {
        pairAsKid()
        startScheduler()
        assertTrue(live(SyncScheduler.PERIODIC).isNotEmpty())

        session.store.markDeviceRevoked()

        assertTrue(live(SyncScheduler.PERIODIC).isEmpty())
    }

    private class TriggerRecorder : SyncTrigger {
        val calls = mutableListOf<String>()
        override fun syncNow() { calls += "now" }
        override fun syncSoon() { calls += "soon" }
    }
}
