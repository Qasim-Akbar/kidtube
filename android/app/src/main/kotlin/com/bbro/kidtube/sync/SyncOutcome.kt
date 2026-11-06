package com.bbro.kidtube.sync

/** What one [SyncEngine.runCycle] ended with. The worker maps this to WorkManager results. */
sealed interface SyncOutcome {
    /** Push, placements, pull and reverify all finished without anything permanent going wrong. */
    data object Success : SyncOutcome

    /** Offline, timeout or a 5xx: nothing is lost, try again later (WorkManager backoff). */
    data object RetryLater : SyncOutcome

    /** HTTP 429. [retryAfterSeconds] is the server's `Retry-After`, or null when it sent none we could read. */
    data class RateLimited(val retryAfterSeconds: Long?) : SyncOutcome

    /** The parent session is gone (or the device was never set up). Stop; nothing retries until the session changes. */
    data object NeedsLogin : SyncOutcome

    /** The kid device's token was revoked. Stop; nothing retries until the device is paired again. */
    data object DeviceRevoked : SyncOutcome

    /** The cycle completed, but some items were permanently rejected / failed. Nothing is retried for them. */
    data class PartialFailure(val counts: SyncCounts) : SyncOutcome
}

/** What went permanently wrong during one cycle (new rejections / failures only, not the running totals). */
data class SyncCounts(
    val rejectedVideos: Int = 0,
    val rejectedEvents: Int = 0,
    val failedPlacements: Int = 0,
) {
    val total: Int get() = rejectedVideos + rejectedEvents + failedPlacements
}

/**
 * The backend's own words for a placement (POST /children/{id}/plan/items) that cannot be done yet.
 * Pinned by ContractTest against the generated fixtures; change both together.
 */
object PlacementErrors {
    /** 404 detail when the child exists but has no plan. We never invent a plan: the placement waits. */
    const val NO_PLAN_DETAIL = "This child has no plan yet"

    /** Placement `reason` values stored while a placement is still pending. */
    const val REASON_NO_PLAN = "no_plan"
    const val REASON_NO_CHILDREN = "no_children"

    /** Placement `reason` stored when the library video itself was rejected. */
    const val REASON_VIDEO_REJECTED = "video_rejected"
}
