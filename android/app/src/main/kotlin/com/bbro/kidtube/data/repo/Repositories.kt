package com.bbro.kidtube.data.repo

import com.bbro.kidtube.core.Clock
import com.bbro.kidtube.core.FamilyTimeZone
import com.bbro.kidtube.core.JwtClaims
import com.bbro.kidtube.core.SessionStore
import com.bbro.kidtube.core.today
import com.bbro.kidtube.data.dao.ChildDao
import com.bbro.kidtube.data.dao.LocalEventDao
import com.bbro.kidtube.data.dao.OutboxDao
import com.bbro.kidtube.data.dao.PlanDao
import com.bbro.kidtube.data.dao.PlanWithItems
import com.bbro.kidtube.data.dao.SnapshotDao
import com.bbro.kidtube.data.dao.VideoDao
import com.bbro.kidtube.data.entity.ChildEntity
import com.bbro.kidtube.data.entity.LocalEventEntity
import com.bbro.kidtube.data.entity.LocalEventState
import com.bbro.kidtube.data.entity.OutboxPlacementEntity
import com.bbro.kidtube.data.entity.OutboxVideoEntity
import com.bbro.kidtube.data.entity.OutboxVideoState
import com.bbro.kidtube.data.entity.PlacementState
import com.bbro.kidtube.data.entity.VideoEntity
import com.bbro.kidtube.data.remote.ApiResult
import com.bbro.kidtube.data.remote.EventResultStatus
import com.bbro.kidtube.data.remote.EventType
import com.bbro.kidtube.data.remote.KidTubeJson
import com.bbro.kidtube.data.remote.KidTubeRemote
import com.bbro.kidtube.data.remote.OnMiss
import com.bbro.kidtube.data.remote.PairResponse
import com.bbro.kidtube.data.remote.PullOutcome
import com.bbro.kidtube.data.remote.RegisterRequest
import com.bbro.kidtube.data.remote.RegisterResponse
import com.bbro.kidtube.data.remote.map
import com.bbro.kidtube.data.remote.toWireString
import com.bbro.kidtube.data.toData
import com.bbro.kidtube.sync.SyncTrigger
import com.bbro.kidtube.share.OutboxItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

// ---- read side (Room is the app's source for the UI) -----------------------------------------------

@Singleton
class ChildrenRepository @Inject constructor(private val dao: ChildDao) {
    fun observeChildren(): Flow<List<ChildEntity>> = dao.observeAll()
    suspend fun children(): List<ChildEntity> = dao.getAll()
    suspend fun child(id: String): ChildEntity? = dao.get(id)
}

@Singleton
class VideoRepository @Inject constructor(private val dao: VideoDao) {
    fun observeVideos(): Flow<List<VideoEntity>> = dao.observeAll()
    suspend fun videos(): List<VideoEntity> = dao.getAll()
    suspend fun video(id: String): VideoEntity? = dao.get(id)
}

@Singleton
class PlanRepository @Inject constructor(private val dao: PlanDao) {
    /** The child's plan with items in queue order (position, then id), or null when the child has none. */
    fun observePlan(childId: String): Flow<PlanWithItems?> = dao.observeForChild(childId).map { it?.ordered() }

    suspend fun plan(childId: String): PlanWithItems? = dao.getForChild(childId)?.ordered()

    fun observeAll(): Flow<List<PlanWithItems>> = dao.observeAll().map { plans -> plans.map { it.ordered() } }

    private fun PlanWithItems.ordered() = copy(items = items.sortedWith(compareBy({ it.position }, { it.id })))
}

// ---- events ------------------------------------------------------------------------------------------

/** Payload builders; shapes match backend/app/services/events.py `_payload` exactly. */
object EventPayloads {
    fun viewComplete(planItemId: String, watchedPct: Double): JsonObject = buildJsonObject {
        put("plan_item_id", planItemId)
        put("watched_pct", watchedPct)
    }

    fun watchProgress(videoId: String, seconds: Double): JsonObject = buildJsonObject {
        put("video_id", videoId)
        put("seconds", seconds)
    }

    fun appSession(start: Instant, end: Instant): JsonObject = buildJsonObject {
        put("start", start.toWireString())
        put("end", end.toWireString())
    }

    fun playbackError(videoId: String, code: Int): JsonObject = buildJsonObject {
        put("video_id", videoId)
        put("code", code)
    }

    /** Parent-only on the server (a kid device's time_extension is rejected). */
    fun timeExtension(seconds: Double): JsonObject = buildJsonObject { put("seconds", seconds) }
}

/** What the server said about one event of a batch (from POST /events or /sync/push). */
data class EventAck(val id: String?, val index: Int, val status: EventResultStatus, val reason: String?)

@Singleton
class EventRepository @Inject constructor(
    private val dao: LocalEventDao,
    private val clock: Clock,
    private val session: SessionStore,
    private val syncTrigger: SyncTrigger = SyncTrigger.None,
) {
    /**
     * Writes a new event as `pending`: id = fresh UUID, occurred_at = now (UTC, millisecond precision),
     * local_date = now in the FAMILY timezone from the last snapshot (Asia/Karachi until one has been pulled).
     */
    suspend fun record(
        type: EventType,
        childId: String,
        payload: JsonObject,
        /** Explicit child-local date; null = today in the family timezone (used when an event belongs to an earlier day, e.g. a session split at midnight). */
        localDate: LocalDate? = null,
        /** Explicit `occurred_at`; null = now. */
        occurredAt: Instant? = null,
    ): LocalEventEntity {
        val now = clock.now().truncatedTo(ChronoUnit.MILLIS)
        val event = LocalEventEntity(
            id = UUID.randomUUID().toString(),
            childId = childId,
            type = type,
            occurredAt = occurredAt?.truncatedTo(ChronoUnit.MILLIS) ?: now,
            localDate = localDate ?: clock.today(familyZone()),
            payload = KidTubeJson.instance.encodeToString(JsonObject.serializer(), payload),
            state = LocalEventState.PENDING,
            ackedCycle = null,
            rejectReason = null,
            createdAt = now,
        )
        dao.insert(event)
        syncTrigger.syncSoon() // batched: a viewing session syncs about every 30 s, not once per event
        return event
    }

    /** Local events to count on top of the stored snapshot history for [childId] (SPEC section 9 merge rule). */
    suspend fun overlayEvents(childId: String): List<LocalEventEntity> = dao.overlay(childId)

    /** Oldest pending events first, at most [limit] (the server takes up to 200 per request). */
    suspend fun pending(limit: Int = MAX_BATCH): List<LocalEventEntity> = dao.pending(limit)

    /** Pending -> acked. The cycle stamp is taken inside the same SQL statement. Returns how many changed. */
    suspend fun markAcked(ids: List<String>): Int = if (ids.isEmpty()) 0 else dao.markAcked(ids)

    suspend fun markRejected(id: String, reason: String?): Int = dao.markRejected(id, reason)

    /**
     * Applies a server answer to the batch that was [sent]: accepted/duplicate -> acked, rejected -> rejected for good,
     * anything unrecognised (a newer server's status) -> left pending to retry. Matched by id, else by index.
     */
    suspend fun applyResults(sent: List<LocalEventEntity>, results: List<EventAck>) {
        val acked = mutableListOf<String>()
        for (result in results) {
            val event = sent.firstOrNull { it.id == result.id } ?: sent.getOrNull(result.index) ?: continue
            when (result.status) {
                EventResultStatus.ACCEPTED, EventResultStatus.DUPLICATE -> acked += event.id
                EventResultStatus.REJECTED -> dao.markRejected(event.id, result.reason)
                EventResultStatus.UNKNOWN -> Unit
            }
        }
        markAcked(acked)
    }

    private suspend fun familyZone(): ZoneId {
        val id = session.currentInfo().familyTimezone ?: return FamilyTimeZone.DEFAULT
        return runCatching { ZoneId.of(id) }.getOrDefault(FamilyTimeZone.DEFAULT)
    }

    companion object {
        const val MAX_BATCH = 200
    }
}

// ---- outbox (offline link sharing) ---------------------------------------------------------------------

/** Where a shared video should go once it exists on the server. [childId] null = EVERY child in the stored snapshot. */
data class PlacementSpec(
    val childId: String?,
    val required: Boolean = false,
    val viewTarget: Int? = null,
    val dailyCap: Int? = null,
    val anchorDate: LocalDate? = null,
    val onMiss: OnMiss = OnMiss.SHIFT,
)

@Singleton
class OutboxRepository @Inject constructor(
    private val dao: OutboxDao,
    private val clock: Clock,
    private val syncTrigger: SyncTrigger = SyncTrigger.None,
) {
    /** Saves a shared link (and what to do with it) locally, in one transaction. Returns the outbox video id. */
    suspend fun enqueue(rawUrl: String, placements: List<PlacementSpec>): String {
        val videoId = UUID.randomUUID().toString()
        val video = OutboxVideoEntity(
            id = videoId, rawUrl = rawUrl, state = OutboxVideoState.PENDING, serverVideoId = null,
            rejectReason = null, attempts = 0, createdAt = clock.now().truncatedTo(ChronoUnit.MILLIS),
        )
        val rows = placements.map {
            OutboxPlacementEntity(
                id = UUID.randomUUID().toString(), outboxVideoId = videoId, childId = it.childId, required = it.required,
                viewTarget = it.viewTarget, dailyCap = it.dailyCap, anchorDate = it.anchorDate, onMiss = it.onMiss,
                state = PlacementState.PENDING, reason = null,
            )
        }
        dao.enqueue(video, rows)
        syncTrigger.syncNow()
        return videoId
    }

    suspend fun pendingVideos(): List<OutboxVideoEntity> = dao.pendingVideos()
    fun observeVideos(): Flow<List<OutboxVideoEntity>> = dao.observeVideos()
    suspend fun placementsFor(outboxVideoId: String): List<OutboxPlacementEntity> = dao.placementsFor(outboxVideoId)

    /** Every outbox link with its placements, newest last (creation order). */
    fun observeItems(): Flow<List<OutboxItem>> =
        dao.observeWithPlacements().map { rows -> rows.map { OutboxItem(it.video, it.placements.sortedBy { p -> p.id }) } }
}

// ---- snapshot pull -------------------------------------------------------------------------------------

enum class PullResult { UPDATED, NOT_MODIFIED }

/**
 * One pull: bump the cycle BEFORE the request, send the stored ETag, then either replace the cache (200) or just
 * advance the history cycle (304: the ETag covers history, so nothing relevant changed since the stored snapshot).
 * Pulls are serialised so cycles cannot interleave.
 */
@Singleton
class SnapshotRepository @Inject constructor(
    private val remote: KidTubeRemote,
    private val dao: SnapshotDao,
    private val session: SessionStore,
    private val clock: Clock,
) {
    private val mutex = Mutex()

    suspend fun pull(): ApiResult<PullResult> = mutex.withLock {
        val cycle = dao.beginPull(clock.now())
        val etag = dao.meta()?.etag
        when (val result = remote.pull(etag)) {
            is ApiResult.Success -> when (val outcome = result.value) {
                PullOutcome.NotModified -> {
                    dao.markUnchanged(cycle)
                    ApiResult.Success(PullResult.NOT_MODIFIED)
                }
                is PullOutcome.Updated -> {
                    dao.replaceSnapshot(outcome.snapshot.toData(), outcome.etag, cycle)
                    session.setFamilyTimezone(outcome.snapshot.timezone)
                    ApiResult.Success(PullResult.UPDATED)
                }
            }
            is ApiResult.HttpError -> result
            is ApiResult.RateLimited -> result
            is ApiResult.NetworkError -> result
        }
    }
}

// ---- auth ------------------------------------------------------------------------------------------------

@Singleton
class AuthRepository @Inject constructor(private val remote: KidTubeRemote, private val session: SessionStore) {
    suspend fun register(
        email: String,
        password: String,
        familyName: String = "My Family",
        timezone: String? = null,
    ): ApiResult<RegisterResponse> = remote.register(RegisterRequest(email, password, familyName, timezone))

    /** On success this phone becomes a parent device; the family id comes from the JWT claims. */
    suspend fun login(email: String, password: String): ApiResult<Unit> =
        remote.login(email, password).map { tokens ->
            session.saveParentLogin(tokens.accessToken, tokens.refreshToken, JwtClaims.parse(tokens.accessToken)?.familyId)
        }

    /** On success this phone becomes a kid-only device holding the device token. */
    suspend fun pairDevice(code: String, deviceName: String): ApiResult<PairResponse> =
        remote.pair(code, deviceName).also { result ->
            if (result is ApiResult.Success) {
                session.saveDevicePairing(result.value.deviceToken, result.value.deviceId, result.value.familyId)
            }
        }

    /** Clears the secrets only; role, family, timezone, active mode and every Room row are kept. */
    suspend fun logout() = session.clearSecrets()
}
