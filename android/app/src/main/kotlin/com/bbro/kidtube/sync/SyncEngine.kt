package com.bbro.kidtube.sync

import com.bbro.kidtube.core.Clock
import com.bbro.kidtube.core.DeviceRole
import com.bbro.kidtube.core.SessionState
import com.bbro.kidtube.core.SessionStore
import com.bbro.kidtube.data.dao.ChildDao
import com.bbro.kidtube.data.dao.OutboxDao
import com.bbro.kidtube.data.dao.PlanDao
import com.bbro.kidtube.data.dao.ReverifyDao
import com.bbro.kidtube.data.dao.VideoDao
import com.bbro.kidtube.data.entity.LocalEventEntity
import com.bbro.kidtube.data.entity.OutboxPlacementEntity
import com.bbro.kidtube.data.entity.OutboxVideoEntity
import com.bbro.kidtube.data.entity.ReverifyAttemptEntity
import com.bbro.kidtube.data.remote.ApiResult
import com.bbro.kidtube.data.remote.EventResultStatus
import com.bbro.kidtube.data.remote.KidTubeRemote
import com.bbro.kidtube.data.remote.Platform
import com.bbro.kidtube.data.remote.PlanItemCreate
import com.bbro.kidtube.data.remote.PushBody
import com.bbro.kidtube.data.remote.PushEventResult
import com.bbro.kidtube.data.remote.PushVideoDto
import com.bbro.kidtube.data.remote.PushVideoResult
import com.bbro.kidtube.data.remote.PushVideoStatus
import com.bbro.kidtube.data.remote.VideoStatus
import com.bbro.kidtube.data.repo.EventAck
import com.bbro.kidtube.data.repo.EventRepository
import com.bbro.kidtube.data.repo.PullResult
import com.bbro.kidtube.data.repo.SnapshotRepository
import com.bbro.kidtube.data.toDto
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One sync cycle = PUSH THEN PULL (SPEC section 9):
 *  1. push pending outbox videos (parent devices only) and pending events in `POST /sync/push` batches,
 *  2. place synced videos into plans (`POST /children/{id}/plan/items`, parent devices only),
 *  3. pull the snapshot (ETag / 304), then re-run placements once if the pull changed anything,
 *  4. re-verify still-pending YouTube videos (parent devices only; at most 5 per cycle, once per hour per video).
 *
 * Why push before pull: an event acknowledged by the push gets `ackedCycle = currentCycle + 1`, which is exactly the
 * pull that starts next, so that pull's history already contains the event and the local overlay drops it in the same
 * transaction that stores the snapshot (see SnapshotDao.replaceSnapshot).
 *
 * Crash safety: every local state change happens AFTER the server's answer, and every server call is idempotent
 * (events by UUID -> `duplicate`, videos by URL -> `existing`, plan items -> 409). Killing the process at any point just
 * means the next cycle resends and lands in the same final state.
 */
@Singleton
class SyncEngine @Inject constructor(
    private val remote: KidTubeRemote,
    private val snapshots: SnapshotRepository,
    private val events: EventRepository,
    private val outbox: OutboxDao,
    private val children: ChildDao,
    private val plans: PlanDao,
    private val videos: VideoDao,
    private val reverifyAttempts: ReverifyDao,
    private val session: SessionStore,
    private val clock: Clock,
    private val status: SyncStatusProvider,
) {
    private val lock = Mutex()
    private var generation = 0L
    private var lastOutcome: SyncOutcome = SyncOutcome.Success

    /**
     * Single-flight: while a cycle runs, further callers wait for it and share its outcome instead of starting another
     * (events recorded meanwhile trigger their own debounced sync).
     */
    suspend fun runCycle(): SyncOutcome {
        val seen = generation
        return lock.withLock {
            if (generation != seen) {
                lastOutcome
            } else {
                status.started()
                val outcome = try {
                    cycle()
                } catch (e: Throwable) {
                    status.finished(SyncOutcome.RetryLater, clock.now())
                    throw e
                }
                lastOutcome = outcome
                generation++
                status.finished(outcome, clock.now())
                outcome
            }
        }
    }

    private class Tally {
        var rejectedVideos = 0
        var rejectedEvents = 0
        var failedPlacements = 0
    }

    private suspend fun cycle(): SyncOutcome {
        when (session.currentState()) {
            SessionState.NeedsLogin, SessionState.Unauthenticated -> return SyncOutcome.NeedsLogin
            SessionState.DeviceRevoked -> return SyncOutcome.DeviceRevoked
            is SessionState.ParentDevice, SessionState.KidDevice -> Unit
        }
        // A kid-role device only syncs events + pull: videos, placements and reverify are parent-only endpoints.
        val isParent = session.currentInfo().deviceRole == DeviceRole.PARENT
        val tally = Tally()

        pushAll(isParent, tally)?.let { return it }
        if (isParent) placeAll(tally)?.let { return it }

        val pull = snapshots.pull()
        val pulled = when (pull) {
            is ApiResult.Success -> pull.value
            else -> return abortFor(pull, otherClientErrorsAbort = true) ?: SyncOutcome.RetryLater
        }
        if (isParent) {
            // A pull that brought new data may have brought the plan a placement was waiting for.
            if (pulled == PullResult.UPDATED) placeAll(tally)?.let { return it }
            reverifyPendingYoutube()
        }

        val counts = SyncCounts(tally.rejectedVideos, tally.rejectedEvents, tally.failedPlacements)
        return if (counts.total > 0) SyncOutcome.PartialFailure(counts) else SyncOutcome.Success
    }

    // ---- failure mapping ---------------------------------------------------------------------------------

    /**
     * The outcome that ends the cycle for a failed call, or null when the failure is the caller's business.
     * 401 (after the single refresh-and-retry inside the HTTP stack) is decided by the session state it left behind.
     */
    private suspend fun abortFor(result: ApiResult<*>, otherClientErrorsAbort: Boolean): SyncOutcome? = when (result) {
        is ApiResult.Success -> null
        is ApiResult.RateLimited -> SyncOutcome.RateLimited(result.retryAfterSeconds)
        is ApiResult.NetworkError -> SyncOutcome.RetryLater
        is ApiResult.HttpError -> when {
            result.code == 401 -> when (session.currentState()) {
                SessionState.DeviceRevoked -> SyncOutcome.DeviceRevoked
                SessionState.NeedsLogin, SessionState.Unauthenticated -> SyncOutcome.NeedsLogin
                else -> SyncOutcome.RetryLater
            }
            result.code == 0 || result.code >= 500 -> SyncOutcome.RetryLater
            otherClientErrorsAbort -> SyncOutcome.RetryLater
            else -> null
        }
    }

    // ---- 1. push -------------------------------------------------------------------------------------------

    /**
     * Loops until nothing is left to send. Guards against a server that never answers for some item: at most
     * [MAX_PUSH_PASSES] passes, an item already tried this cycle is not sent again in a later pass, and a pass that
     * changed no state ends the loop.
     */
    private suspend fun pushAll(isParent: Boolean, tally: Tally): SyncOutcome? {
        val triedVideos = HashSet<String>()
        val triedEvents = HashSet<String>()
        repeat(MAX_PUSH_PASSES) {
            val videoBatch = if (isParent) {
                outbox.pendingVideos().filter { it.id !in triedVideos }.take(MAX_VIDEOS_PER_PUSH)
            } else {
                emptyList()
            }
            // Fetch enough rows to skip the ones already tried this cycle.
            val eventBatch = events.pending(triedEvents.size + MAX_EVENTS_PER_PUSH)
                .filter { it.id !in triedEvents }.take(MAX_EVENTS_PER_PUSH)
            if (videoBatch.isEmpty() && eventBatch.isEmpty()) return null
            triedVideos += videoBatch.map { it.id }
            triedEvents += eventBatch.map { it.id }

            val body = PushBody(videoBatch.map { PushVideoDto(it.rawUrl) }, eventBatch.map { it.toDto() })
            val result = remote.push(body)
            abortFor(result, otherClientErrorsAbort = true)?.let { return it }
            val answer = (result as ApiResult.Success).value

            val progress = applyVideoResults(videoBatch, answer.videos, tally) +
                applyEventResults(eventBatch, answer.events, tally)
            if (progress == 0) return null
        }
        return null
    }

    private suspend fun applyVideoResults(sent: List<OutboxVideoEntity>, results: List<PushVideoResult>, tally: Tally): Int {
        var progress = 0
        val answered = HashSet<String>()
        for (r in results) {
            val video = sent.getOrNull(r.index) ?: continue
            answered += video.id
            when (r.status) {
                // `created` includes a video whose oEmbed check was slow (video_status pending, retryable=true): it EXISTS on
                // the server, so it is synced and never resent; the reverify step settles its status later.
                PushVideoStatus.CREATED, PushVideoStatus.EXISTING -> {
                    val serverId = r.videoId
                    if (serverId == null) {
                        outbox.bumpAttempts(video.id)
                    } else {
                        outbox.markVideoSynced(video.id, serverId)
                        progress++
                    }
                }
                // Every rejection is permanent (invalid URL, forbidden, ...): resending the same item cannot succeed.
                PushVideoStatus.REJECTED -> {
                    outbox.markVideoRejected(video.id, r.reason ?: "rejected")
                    outbox.failPendingPlacements(video.id, PlacementErrors.REASON_VIDEO_REJECTED)
                    tally.rejectedVideos++
                    progress++
                }
                PushVideoStatus.UNKNOWN -> outbox.bumpAttempts(video.id)
            }
        }
        sent.filter { it.id !in answered }.forEach { outbox.bumpAttempts(it.id) }
        return progress
    }

    private suspend fun applyEventResults(sent: List<LocalEventEntity>, results: List<PushEventResult>, tally: Tally): Int {
        if (sent.isEmpty()) return 0
        events.applyResults(sent, results.map { EventAck(it.id, it.index, it.status, it.reason) })
        tally.rejectedEvents += results.count { it.status == EventResultStatus.REJECTED }
        return results.count { it.status != EventResultStatus.UNKNOWN }
    }

    // ---- 2. placements -----------------------------------------------------------------------------------------

    private suspend fun placeAll(tally: Tally): SyncOutcome? {
        for (video in outbox.syncedVideosWithPendingPlacements()) {
            val serverVideoId = video.serverVideoId ?: continue
            expandAllChildrenPlacements(video)
            for (placement in outbox.pendingPlacements(video.id)) {
                val childId = placement.childId ?: continue // "no_children": waits for a snapshot that has children
                // The stored snapshot knows this child and says it has no plan: do not call, and never create one.
                if (children.get(childId) != null && plans.getForChild(childId) == null) {
                    outbox.setPlacementReason(placement.id, PlacementErrors.REASON_NO_PLAN)
                    continue
                }
                val result = remote.addPlanItem(
                    childId,
                    PlanItemCreate(
                        videoId = serverVideoId,
                        required = placement.required,
                        viewTarget = placement.viewTarget,
                        dailyCap = placement.dailyCap,
                        anchorDate = placement.anchorDate,
                        onMiss = placement.onMiss,
                    ),
                )
                when (result) {
                    is ApiResult.Success -> outbox.markPlacementDone(placement.id)
                    is ApiResult.HttpError -> when {
                        result.code == 409 -> outbox.markPlacementDone(placement.id) // already in the plan
                        result.code == 404 && result.message == PlacementErrors.NO_PLAN_DETAIL ->
                            outbox.setPlacementReason(placement.id, PlacementErrors.REASON_NO_PLAN)
                        else -> {
                            abortFor(result, otherClientErrorsAbort = false)?.let { return it }
                            outbox.markPlacementFailed(placement.id, result.message ?: "http_${result.code}")
                            tally.failedPlacements++
                        }
                    }
                    else -> abortFor(result, otherClientErrorsAbort = false)?.let { return it }
                }
            }
        }
        return null
    }

    /** `childId == null` means every child in the stored snapshot: one concrete placement per child. */
    private suspend fun expandAllChildrenPlacements(video: OutboxVideoEntity) {
        for (placement in outbox.pendingPlacements(video.id).filter { it.childId == null }) {
            val all = children.getAll()
            if (all.isEmpty()) {
                outbox.setPlacementReason(placement.id, PlacementErrors.REASON_NO_CHILDREN)
                continue
            }
            val copies: List<OutboxPlacementEntity> = all.map {
                placement.copy(id = UUID.randomUUID().toString(), childId = it.id, reason = null)
            }
            outbox.expandPlacement(placement, copies)
        }
    }

    // ---- 4. reverify --------------------------------------------------------------------------------------------

    private suspend fun reverifyPendingYoutube() {
        val now = clock.now()
        val lastAttempt: Map<String, Instant> = reverifyAttempts.all().associate { it.videoId to it.lastAttemptAt }
        val due = videos.getAll()
            .filter { it.platform == Platform.YOUTUBE && it.status == VideoStatus.PENDING } // Facebook stays pending by design
            .filter { v -> lastAttempt[v.id]?.let { Duration.between(it, now) >= REVERIFY_MIN_INTERVAL } ?: true }
            .sortedBy { lastAttempt[it.id] ?: Instant.EPOCH } // never-tried first, then the longest-waiting
            .take(MAX_REVERIFY_PER_CYCLE)
        for (video in due) {
            val result = remote.reverifyVideo(video.id)
            // Recorded whatever the answer: network trouble also waits an hour. The new status arrives with the next pull.
            reverifyAttempts.upsert(ReverifyAttemptEntity(video.id, clock.now()))
            if (result is ApiResult.RateLimited) break
        }
        reverifyAttempts.pruneForgottenVideos()
    }

    companion object {
        const val MAX_VIDEOS_PER_PUSH = 50
        const val MAX_EVENTS_PER_PUSH = 200
        const val MAX_PUSH_PASSES = 20
        const val MAX_REVERIFY_PER_CYCLE = 5
        val REVERIFY_MIN_INTERVAL: Duration = Duration.ofHours(1)
    }
}
