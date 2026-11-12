package com.bbro.kidtube.testing

import com.bbro.kidtube.data.dao.PlanWithItems
import com.bbro.kidtube.data.entity.ChildEntity
import com.bbro.kidtube.data.entity.OutboxPlacementEntity
import com.bbro.kidtube.data.entity.OutboxVideoEntity
import com.bbro.kidtube.data.entity.OutboxVideoState
import com.bbro.kidtube.data.entity.PlacementState
import com.bbro.kidtube.data.entity.PlanEntity
import com.bbro.kidtube.data.entity.PlanItemEntity
import com.bbro.kidtube.data.entity.VideoEntity
import com.bbro.kidtube.data.remote.OnMiss
import com.bbro.kidtube.data.remote.PaceType
import com.bbro.kidtube.data.remote.Platform
import com.bbro.kidtube.data.remote.PlanItemStatus
import com.bbro.kidtube.data.remote.VideoStatus
import com.bbro.kidtube.share.OutboxItem
import java.time.Instant
import java.time.LocalDate

/** Small hand-built rows for the pure (no Room) tests of the share and library logic. */
object Rows {
    fun child(id: String, name: String = id) = ChildEntity(id, name, avatar = null, dailyAppMinutes = null)

    fun video(
        id: String,
        platformVideoId: String = id,
        title: String? = "Title $id",
        status: VideoStatus = VideoStatus.VERIFIED,
        platform: Platform = Platform.YOUTUBE,
        url: String = "https://youtu.be/$platformVideoId",
        addedAt: Instant = Instant.parse("2026-11-01T00:00:00Z"),
        thumbnailUrl: String? = null,
    ) = VideoEntity(id, platform, platformVideoId, url, title, thumbnailUrl, null, status, null, addedAt)

    fun item(
        id: String,
        planId: String,
        videoId: String,
        required: Boolean = false,
        viewTarget: Int? = null,
        dailyCap: Int? = null,
        status: PlanItemStatus = PlanItemStatus.ACTIVE,
    ) = PlanItemEntity(
        id, planId, videoId, position = 0, required = required, viewTarget = viewTarget, dailyCap = dailyCap,
        watchThreshold = 0.9, anchorDate = null, onMiss = OnMiss.SHIFT, status = status, addedOn = LocalDate.parse("2026-11-01"),
    )

    fun plan(childId: String, vararg items: PlanItemEntity): PlanWithItems {
        val id = "plan-$childId"
        return PlanWithItems(
            PlanEntity(id, childId, PaceType.VIDEOS_PER_DAY, 3, 127, 1, LocalDate.parse("2026-11-01"), null, 1),
            items.map { it.copy(planId = id) },
        )
    }

    fun outbox(
        id: String,
        url: String,
        state: OutboxVideoState = OutboxVideoState.PENDING,
        serverVideoId: String? = null,
        createdAt: Instant = Instant.parse("2026-11-02T00:00:00Z"),
        rejectReason: String? = null,
        vararg placements: OutboxPlacementEntity,
    ) = OutboxItem(
        OutboxVideoEntity(id, url, state, serverVideoId, rejectReason, 0, createdAt),
        placements.map { it.copy(outboxVideoId = id) },
    )

    fun placement(
        childId: String?,
        required: Boolean = false,
        viewTarget: Int? = null,
        dailyCap: Int? = null,
        state: PlacementState = PlacementState.PENDING,
        reason: String? = null,
        id: String = "pl-${childId ?: "all"}-${state.wire}",
    ) = OutboxPlacementEntity(id, "", childId, required, viewTarget, dailyCap, null, OnMiss.SHIFT, state, reason)
}
