package com.bbro.kidtube.share

import com.bbro.kidtube.data.dao.PlanWithItems
import com.bbro.kidtube.data.entity.OutboxPlacementEntity
import com.bbro.kidtube.data.entity.OutboxVideoEntity
import com.bbro.kidtube.data.entity.OutboxVideoState
import com.bbro.kidtube.data.entity.PlacementState
import com.bbro.kidtube.data.entity.VideoEntity
import com.bbro.kidtube.data.remote.PlanItemStatus

/** A link waiting in the local outbox with the placements saved for it. */
data class OutboxItem(val video: OutboxVideoEntity, val placements: List<OutboxPlacementEntity>)

/** "Already added" detection for the share sheet. Pure: everything it needs is passed in. */
object ShareDuplicates {
    private fun libraryIds(key: String, library: List<VideoEntity>): Set<String> =
        library.filter { SharedLinks.keyOf(it.platform, it.platformVideoId, it.url) == key }.map { it.id }.toSet()

    private fun liveOutbox(key: String, outbox: List<OutboxItem>): List<OutboxItem> =
        outbox.filter { it.video.state != OutboxVideoState.REJECTED && (SharedLinks.check(it.video.rawUrl) as? LinkCheck.Supported)?.key == key }

    /**
     * The children (of [childIds]) that already have this video: it is an active item of their plan, or the outbox holds
     * a placement for them that has not failed. A "every child" placement (childId null) counts for everyone.
     */
    fun childrenWithVideo(
        key: String,
        childIds: Collection<String>,
        library: List<VideoEntity>,
        plans: List<PlanWithItems>,
        outbox: List<OutboxItem>,
    ): Set<String> {
        val videoIds = libraryIds(key, library)
        val queued = liveOutbox(key, outbox)
        return childIds.filterTo(mutableSetOf()) { child ->
            val inPlan = plans.firstOrNull { it.plan.childId == child }
                ?.items?.any { it.videoId in videoIds && it.status != PlanItemStatus.DROPPED } == true
            val inOutbox = queued.any { item ->
                item.placements.any { (it.childId == child || it.childId == null) && it.state != PlacementState.FAILED }
            }
            inPlan || inOutbox
        }
    }

    /** For "Library only": the video is already in the library or the outbox (for anyone). */
    fun inLibraryOrOutbox(key: String, library: List<VideoEntity>, outbox: List<OutboxItem>): Boolean =
        libraryIds(key, library).isNotEmpty() || liveOutbox(key, outbox).isNotEmpty()
}
