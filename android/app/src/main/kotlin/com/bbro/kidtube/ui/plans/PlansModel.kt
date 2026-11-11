package com.bbro.kidtube.ui.plans

import com.bbro.kidtube.data.dao.PlanWithItems
import com.bbro.kidtube.data.entity.ChildEntity
import com.bbro.kidtube.data.entity.VideoEntity
import com.bbro.kidtube.data.remote.OnMiss
import com.bbro.kidtube.data.remote.PaceType
import com.bbro.kidtube.data.remote.Platform
import com.bbro.kidtube.data.remote.PlanItemStatus
import com.bbro.kidtube.data.remote.VideoStatus
import com.bbro.kidtube.engine.DayStatus
import com.bbro.kidtube.engine.Projection
import com.bbro.kidtube.ui.components.BadgeStatus
import java.time.LocalDate

/** Days shown in "What happens next". */
const val PROJECTION_DAYS = 7

/** The plan's own settings as the summary card and the edit form need them. */
data class PlanSummary(
    val paceType: PaceType,
    val paceValue: Int,
    /** Bitmask, Monday = bit 0 .. Sunday = bit 6. */
    val activeWeekdays: Int,
    val startDate: LocalDate,
    val endDate: LocalDate?,
    /** Catch-up cap: extra VIDEOS a day in videos mode, extra MINUTES a day in minutes mode. */
    val maxExtraPerDay: Int,
)

/** One ACTIVE plan item as a row. */
data class PlanItemRow(
    val id: String,
    val title: String,
    val thumbnailUrl: String?,
    val status: BadgeStatus,
    val facebook: Boolean,
    val required: Boolean,
    /** What the parent set; null = none (an optional item then repeats, a required one counts once). */
    val viewTarget: Int?,
    val dailyCap: Int?,
    /** Anchored items only: the date and what happens when it is missed (read-only). */
    val anchorDate: LocalDate?,
    val onMiss: OnMiss?,
    val canMoveUp: Boolean,
    val canMoveDown: Boolean,
)

/** A library video that can still be added to this plan (not an active item of it, not unavailable). */
data class PickableVideo(val id: String, val title: String, val thumbnailUrl: String?, val status: BadgeStatus, val facebook: Boolean)

data class DayEntry(val title: String, val required: Boolean)

data class DayRow(val date: LocalDate, val status: DayStatus, val entries: List<DayEntry>)

/** Everything the Plans tab shows for the selected child. */
data class PlanScreenModel(
    val child: ChildEntity,
    /** Null = the child has no plan yet ("Create default plan"). */
    val plan: PlanSummary?,
    val items: List<PlanItemRow>,
    val pickable: List<PickableVideo>,
    val days: List<DayRow>,
    /** Titles of the unlimited items the projection leaves out. */
    val unlimitedTitles: List<String>,
) {
    val dailyAppMinutes: Int? get() = child.dailyAppMinutes
}

/** Pure mapping: stored rows + the engine's projection -> what the screen draws. */
object PlansMapper {
    fun map(child: ChildEntity, planWithItems: PlanWithItems?, videos: List<VideoEntity>, projection: Projection?): PlanScreenModel {
        val plan = planWithItems?.plan
        val videoById = videos.associateBy { it.id }
        val items = planWithItems?.items.orEmpty()
        val active = items.filter { it.status == PlanItemStatus.ACTIVE && it.videoId in videoById }

        val rows = active.mapIndexed { index, item ->
            val video = videoById.getValue(item.videoId)
            PlanItemRow(
                id = item.id,
                title = titleOf(video),
                thumbnailUrl = video.thumbnailUrl,
                status = badge(video.status),
                facebook = video.platform == Platform.FACEBOOK,
                required = item.required,
                viewTarget = item.viewTarget,
                dailyCap = item.dailyCap,
                anchorDate = item.anchorDate,
                onMiss = if (item.anchorDate != null) item.onMiss else null,
                canMoveUp = index > 0,
                canMoveDown = index < active.lastIndex,
            )
        }

        val inPlan = active.mapTo(HashSet()) { it.videoId }
        val pickable = videos
            .filter { it.id !in inPlan && it.status != VideoStatus.UNAVAILABLE }
            .sortedByDescending { it.addedAt }
            .map { PickableVideo(it.id, titleOf(it), it.thumbnailUrl, badge(it.status), it.platform == Platform.FACEBOOK) }

        val itemById = items.associateBy { it.id }
        val days = projection?.days.orEmpty().take(PROJECTION_DAYS).map { day ->
            DayRow(
                date = day.date,
                status = day.status,
                entries = day.views.mapNotNull { v ->
                    val item = itemById[v.itemId] ?: return@mapNotNull null
                    DayEntry(titleOf(videoById[item.videoId]), item.required)
                },
            )
        }
        val unlimited = projection?.excludedUnlimitedItemIds.orEmpty().mapNotNull { id -> itemById[id]?.let { titleOf(videoById[it.videoId]) } }

        return PlanScreenModel(
            child = child,
            plan = plan?.let { PlanSummary(it.paceType, it.paceValue, it.activeWeekdays, it.startDate, it.endDate, it.maxExtraPerDay) },
            items = rows,
            pickable = pickable,
            days = days,
            unlimitedTitles = unlimited,
        )
    }

    /** A title, else the link without its scheme (the library shows the same). */
    private fun titleOf(video: VideoEntity?): String {
        if (video == null) return ""
        return video.title?.takeIf { it.isNotBlank() } ?: video.url.removePrefix("https://").removePrefix("http://").removePrefix("www.")
    }

    private fun badge(status: VideoStatus): BadgeStatus = when (status) {
        VideoStatus.VERIFIED -> BadgeStatus.VERIFIED
        VideoStatus.UNAVAILABLE -> BadgeStatus.UNAVAILABLE
        VideoStatus.PENDING, VideoStatus.UNKNOWN -> BadgeStatus.PENDING
    }
}

/** Weekday helpers for the Monday = bit 0 mask. */
object Weekdays {
    fun isSet(mask: Int, weekday: Int): Boolean = mask and (1 shl weekday) != 0
    fun toggle(mask: Int, weekday: Int): Int = mask xor (1 shl weekday)
}
