package com.bbro.kidtube.data

import com.bbro.kidtube.data.dao.SnapshotData
import com.bbro.kidtube.data.entity.ChildEntity
import com.bbro.kidtube.data.entity.HistoryKind
import com.bbro.kidtube.data.entity.HistorySecondsEntity
import com.bbro.kidtube.data.entity.HistoryViewEntity
import com.bbro.kidtube.data.entity.LocalEventEntity
import com.bbro.kidtube.data.entity.PlanEntity
import com.bbro.kidtube.data.entity.PlanItemEntity
import com.bbro.kidtube.data.entity.VideoEntity
import com.bbro.kidtube.data.remote.ChildHistoryDto
import com.bbro.kidtube.data.remote.EventDto
import com.bbro.kidtube.data.remote.HistorySecondsDto
import com.bbro.kidtube.data.remote.KidTubeJson
import com.bbro.kidtube.data.remote.PlanDto
import com.bbro.kidtube.data.remote.SnapshotDto
import com.bbro.kidtube.data.remote.VideoDto
import kotlinx.serialization.json.JsonObject

fun VideoDto.toEntity() = VideoEntity(
    id = id, platform = platform, platformVideoId = platformVideoId, url = url, title = title,
    thumbnailUrl = thumbnailUrl, durationSeconds = durationSeconds, status = status, addedBy = addedBy, addedAt = addedAt,
)

fun PlanDto.toEntity() = PlanEntity(
    id = id, childId = childId, paceType = paceType, paceValue = paceValue, activeWeekdays = activeWeekdays,
    maxExtraPerDay = maxExtraPerDay, startDate = startDate, endDate = endDate, version = version,
)

fun PlanDto.itemEntities(): List<PlanItemEntity> = items.map {
    PlanItemEntity(
        id = it.id, planId = id, videoId = it.videoId, position = it.position, required = it.required,
        viewTarget = it.viewTarget, dailyCap = it.dailyCap, watchThreshold = it.watchThreshold,
        anchorDate = it.anchorDate, onMiss = it.onMiss, status = it.status, addedOn = it.addedOn,
    )
}

private fun List<HistorySecondsDto>.toEntities(childId: String, kind: HistoryKind) =
    map { HistorySecondsEntity(childId, kind, it.localDate, it.seconds) }

private fun ChildHistoryDto.secondsEntities(childId: String): List<HistorySecondsEntity> =
    watchSeconds.toEntities(childId, HistoryKind.WATCH) +
        appSeconds.toEntities(childId, HistoryKind.APP) +
        timeExtensionSeconds.toEntities(childId, HistoryKind.EXTENSION)

/** Folds a snapshot into the rows [com.bbro.kidtube.data.dao.SnapshotDao.replaceSnapshot] writes. */
fun SnapshotDto.toData(): SnapshotData = SnapshotData(
    children = children.map { ChildEntity(it.id, it.name, it.avatar, it.dailyAppMinutes) },
    videos = videos.map { it.toEntity() },
    plans = children.mapNotNull { it.plan?.toEntity() },
    items = children.flatMap { it.plan?.itemEntities().orEmpty() },
    historyViews = children.flatMap { child ->
        child.history.views.map { HistoryViewEntity(child.id, it.planItemId, it.localDate, it.count) }
    },
    historySeconds = children.flatMap { it.history.secondsEntities(it.id) },
)

/** The wire form of a locally recorded event (payload text is the JSON that was validated when recording). */
fun LocalEventEntity.toDto() = EventDto(
    id = id, childId = childId, type = type, occurredAt = occurredAt, localDate = localDate,
    payload = KidTubeJson.instance.parseToJsonElement(payload) as JsonObject,
)
