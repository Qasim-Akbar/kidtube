package com.bbro.kidtube.sync

import com.bbro.kidtube.data.dao.LocalEventDao
import com.bbro.kidtube.data.dao.OutboxDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/** A snapshot of how sync is doing, for the UI (and the debug panel). */
data class SyncStatus(
    val phase: Phase = Phase.IDLE,
    val lastSuccessAt: Instant? = null,
    val lastError: String? = null,
    val pendingVideos: Int = 0,
    val pendingEvents: Int = 0,
    val rejectedVideos: Int = 0,
    val rejectedEvents: Int = 0,
    /** Placements waiting for a plan to exist (reason no_plan). Not an error: they finish after a plan appears. */
    val placementsWaitingForPlan: Int = 0,
    /** Placements that failed for good. Separate from the ones waiting for a plan. */
    val failedPlacements: Int = 0,
) {
    enum class Phase { IDLE, SYNCING }

    val rejectedItems: Int get() = rejectedVideos + rejectedEvents
}

private data class RunState(val syncing: Boolean = false, val lastSuccessAt: Instant? = null, val lastError: String? = null)

private data class Counts(
    val pendingVideos: Int, val pendingEvents: Int, val rejectedVideos: Int, val rejectedEvents: Int,
    val waiting: Int, val failedPlacements: Int,
)

@Singleton
class SyncStatusProvider @Inject constructor(outbox: OutboxDao, events: LocalEventDao) {
    private val run = MutableStateFlow(RunState())

    private val counts: Flow<Counts> = combine(
        outbox.observePendingVideoCount(),
        events.observePendingCount(),
        outbox.observeRejectedVideoCount(),
        events.observeRejectedCount(),
        combine(outbox.observeWaitingForPlanCount(), outbox.observeFailedPlacementCount()) { w, f -> w to f },
    ) { pv, pe, rv, re, wf -> Counts(pv, pe, rv, re, wf.first, wf.second) }

    val status: Flow<SyncStatus> = combine(run, counts) { r, c ->
        SyncStatus(
            phase = if (r.syncing) SyncStatus.Phase.SYNCING else SyncStatus.Phase.IDLE,
            lastSuccessAt = r.lastSuccessAt,
            lastError = r.lastError,
            pendingVideos = c.pendingVideos,
            pendingEvents = c.pendingEvents,
            rejectedVideos = c.rejectedVideos,
            rejectedEvents = c.rejectedEvents,
            placementsWaitingForPlan = c.waiting,
            failedPlacements = c.failedPlacements,
        )
    }

    internal fun started() = run.update { it.copy(syncing = true) }

    internal fun finished(outcome: SyncOutcome, now: Instant) = run.update { r ->
        when (outcome) {
            SyncOutcome.Success -> r.copy(syncing = false, lastSuccessAt = now, lastError = null)
            is SyncOutcome.PartialFailure ->
                r.copy(syncing = false, lastSuccessAt = now, lastError = "${outcome.counts.total} item(s) rejected or failed")
            SyncOutcome.RetryLater -> r.copy(syncing = false, lastError = "Network problem, will retry")
            is SyncOutcome.RateLimited -> r.copy(syncing = false, lastError = "Too many requests, will retry")
            SyncOutcome.NeedsLogin -> r.copy(syncing = false, lastError = "Login required")
            SyncOutcome.DeviceRevoked -> r.copy(syncing = false, lastError = "This device was revoked")
        }
    }
}
