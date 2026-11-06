package com.bbro.kidtube.sync

import com.bbro.kidtube.data.RoomTestBase
import com.bbro.kidtube.data.entity.LocalEventEntity
import com.bbro.kidtube.data.remote.ApiStack
import com.bbro.kidtube.data.remote.ApiWiring
import com.bbro.kidtube.data.remote.EventResultStatus
import com.bbro.kidtube.data.remote.EventType
import com.bbro.kidtube.data.remote.KidTubeJson
import com.bbro.kidtube.data.remote.Platform
import com.bbro.kidtube.data.remote.PushBody
import com.bbro.kidtube.data.remote.PushEventResult
import com.bbro.kidtube.data.remote.PushResult
import com.bbro.kidtube.data.remote.PushVideoResult
import com.bbro.kidtube.data.remote.PushVideoStatus
import com.bbro.kidtube.data.remote.SnapshotDto
import com.bbro.kidtube.data.remote.VideoStatus
import com.bbro.kidtube.data.repo.EventPayloads
import com.bbro.kidtube.data.repo.EventRepository
import com.bbro.kidtube.data.repo.OutboxRepository
import com.bbro.kidtube.data.repo.SnapshotRepository
import com.bbro.kidtube.data.toData
import com.bbro.kidtube.testing.ContractFixtures
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Before
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Real production wiring (ApiWiring, repositories, SyncEngine) against a MockWebServer, an in-memory Room database and
 * the fake clock. The default server behaves like a healthy backend built from the generated contract fixtures;
 * a test overrides single routes with [route].
 */
abstract class SyncTestBase : RoomTestBase() {
    data class Seen(val method: String, val path: String, val auth: String?, val ifNoneMatch: String?, val body: String)

    protected lateinit var server: MockWebServer
    protected lateinit var stack: ApiStack
    protected lateinit var events: EventRepository
    protected lateinit var outboxRepo: OutboxRepository
    protected lateinit var statusProvider: SyncStatusProvider
    protected lateinit var engine: SyncEngine

    protected val seen = ConcurrentLinkedQueue<Seen>()

    /** The snapshot the fake server hands out and its ETag. Replace to simulate server-side change. */
    @Volatile protected var serverSnapshot: SnapshotDto = ContractFixtures.load(73).decodeBody()

    @Volatile protected var serverEtag: String = ContractFixtures.load(73).headers.getValue("etag")

    private val routes = mutableMapOf<String, (RecordedRequest) -> MockResponse>()

    /** Overrides a route. [key] is "METHOD path", e.g. "POST /sync/push"; a key ending in a star matches any path with that prefix. */
    protected fun route(key: String, handler: (RecordedRequest) -> MockResponse) {
        routes[key] = handler
    }

    @Before
    fun startSyncHarness() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.url.encodedPath
                seen += Seen(
                    request.method.orEmpty(), path, request.headers["Authorization"],
                    request.headers["If-None-Match"], request.body?.utf8().orEmpty(),
                )
                val key = "${request.method} $path"
                routes[key]?.let { return it(request) }
                routes.entries.firstOrNull { (k, _) -> k.endsWith("*") && key.startsWith(k.removeSuffix("*")) }
                    ?.let { return it.value(request) }
                return defaultResponse(request)
            }
        }
        server.start()
        stack = ApiWiring.create(server.url("/").toString(), KidTubeJson.instance, session.store, clock)
        events = EventRepository(db.localEventDao(), clock, session.store)
        outboxRepo = OutboxRepository(db.outboxDao(), clock)
        statusProvider = SyncStatusProvider(db.outboxDao(), db.localEventDao())
        engine = buildEngine()
    }

    protected fun buildEngine() = SyncEngine(
        remote = stack.remote,
        snapshots = SnapshotRepository(stack.remote, db.snapshotDao(), session.store, clock),
        events = events,
        outbox = db.outboxDao(),
        children = db.childDao(),
        plans = db.planDao(),
        videos = db.videoDao(),
        reverifyAttempts = db.reverifyDao(),
        session = session.store,
        clock = clock,
        status = statusProvider,
    )

    @After
    fun stopSyncHarness() {
        runCatching { server.close() }
    }

    // ---- default (healthy) backend -----------------------------------------------------------------------------

    private fun defaultResponse(request: RecordedRequest): MockResponse {
        val path = request.url.encodedPath
        return when {
            request.method == "POST" && path == "/sync/push" -> {
                val body = KidTubeJson.instance.decodeFromString(PushBody.serializer(), request.body!!.utf8())
                json(200, KidTubeJson.instance.encodeToString(PushResult.serializer(), acceptAll(body)))
            }
            request.method == "GET" && path == "/sync/pull" -> pullResponse(request)
            request.method == "POST" && path.startsWith("/children/") && path.endsWith("/plan/items") ->
                json(201, ContractFixtures.load(28).bodyText)
            request.method == "POST" && path.startsWith("/videos/") && path.endsWith("/reverify") ->
                json(200, ContractFixtures.load(72).bodyText)
            else -> json(404, """{"detail":"Not Found"}""")
        }
    }

    protected fun pullResponse(request: RecordedRequest): MockResponse =
        if (request.headers["If-None-Match"] == serverEtag) {
            MockResponse.Builder().code(304).addHeader("ETag", serverEtag).build()
        } else {
            json(200, KidTubeJson.instance.encodeToString(SnapshotDto.serializer(), serverSnapshot), "ETag" to serverEtag)
        }

    /** What a healthy server answers: every video created (verified), every event accepted. */
    protected fun acceptAll(body: PushBody): PushResult = PushResult(
        videos = body.videos.mapIndexed { i, _ ->
            PushVideoResult(i, PushVideoStatus.CREATED, "srv-video-${seenVideoSeq++}", VideoStatus.VERIFIED, null, false)
        },
        events = body.events.mapIndexed { i, e -> PushEventResult(i, e.id, EventResultStatus.ACCEPTED, null, false) },
    )

    private var seenVideoSeq = 1

    protected fun json(code: Int, body: String, vararg headers: Pair<String, String>): MockResponse =
        MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body)
            .apply { headers.forEach { (k, v) -> addHeader(k, v) } }.build()

    // ---- test helpers -------------------------------------------------------------------------------------------------

    protected suspend fun loginAsParent() = session.store.saveParentLogin("access-1", "refresh-1", "fam-1")

    protected suspend fun pairAsKid() = session.store.saveDevicePairing("device-token-1", "dev-1", "fam-1")

    /** Stores [serverSnapshot] as if a pull had already happened (history cycle 1, ETag [serverEtag]). */
    protected suspend fun seedSnapshot() {
        val dao = db.snapshotDao()
        dao.replaceSnapshot(serverSnapshot.toData(), serverEtag, dao.beginPull(clock.now()))
    }

    protected fun requests(method: String, pathPrefix: String) = seen.filter { it.method == method && it.path.startsWith(pathPrefix) }

    protected fun pushBodies(): List<PushBody> = requests("POST", "/sync/push")
        .map { KidTubeJson.instance.decodeFromString(PushBody.serializer(), it.body) }

    // Pending rows are sent oldest first (createdAt, then id): tick the clock after each write so the order is deterministic.
    protected suspend fun recordView(childId: String = CHILD_A, itemId: String = ITEM_1): LocalEventEntity =
        events.record(EventType.VIEW_COMPLETE, childId, EventPayloads.viewComplete(itemId, 0.95)).also { tick() }

    protected suspend fun recordWatch(childId: String = CHILD_A): LocalEventEntity =
        events.record(EventType.WATCH_PROGRESS, childId, EventPayloads.watchProgress("v", 5.0)).also { tick() }

    protected suspend fun enqueueLink(url: String, vararg placements: com.bbro.kidtube.data.repo.PlacementSpec): String =
        outboxRepo.enqueue(url, placements.toList()).also { tick() }

    protected fun tick() = clock.advance(java.time.Duration.ofMillis(10))

    /** Marks every YouTube video of [serverSnapshot] pending (so the engine has something to re-verify). */
    protected fun makeYoutubeVideosPending() {
        serverSnapshot = serverSnapshot.copy(
            videos = serverSnapshot.videos.map { if (it.platform == Platform.YOUTUBE) it.copy(status = VideoStatus.PENDING) else it },
        )
    }

    protected fun executeSql(sql: String) = db.openHelper.writableDatabase.execSQL(sql)
}
