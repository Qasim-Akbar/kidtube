package com.bbro.kidtube.data.repo

import com.bbro.kidtube.data.dao.PlanWithItems
import com.bbro.kidtube.data.entity.ChildEntity
import com.bbro.kidtube.data.entity.HistoryKind
import com.bbro.kidtube.data.entity.HistorySecondsEntity
import com.bbro.kidtube.data.entity.HistoryViewEntity
import com.bbro.kidtube.data.entity.LocalEventEntity
import com.bbro.kidtube.data.entity.VideoEntity
import com.bbro.kidtube.data.remote.EventType
import com.bbro.kidtube.data.remote.KidTubeJson
import com.bbro.kidtube.data.remote.VideoStatus
import com.bbro.kidtube.engine.AppSecondsRecord
import com.bbro.kidtube.engine.CATCH_UP_WINDOW_DAYS
import com.bbro.kidtube.engine.ChildLimits
import com.bbro.kidtube.engine.ItemState
import com.bbro.kidtube.engine.ItemStatus
import com.bbro.kidtube.engine.PlanConfig
import com.bbro.kidtube.engine.SecondsRecord
import com.bbro.kidtube.engine.ViewRecord
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import com.bbro.kidtube.data.remote.OnMiss as WireOnMiss
import com.bbro.kidtube.data.remote.PaceType as WirePaceType
import com.bbro.kidtube.data.remote.PlanItemStatus as WireItemStatus
import com.bbro.kidtube.engine.OnMiss as EngineOnMiss
import com.bbro.kidtube.engine.PaceType as EnginePaceType

/**
 * Everything the pure engine needs for one child on one day, built from Room rows. Kotlin mirror of
 * `backend/app/services/engine_input.py` (`EngineInput` + `load_engine_input`): same filtering, same merge of event-derived
 * numbers, so the phone and the server feed the engine identical input.
 */
data class EngineInput(
    val today: LocalDate,
    val plan: PlanConfig,
    val items: List<ItemState>,
    val views: List<ViewRecord>,
    val seconds: List<SecondsRecord>,
    val appSeconds: List<AppSecondsRecord>,
    val limits: ChildLimits,
    /** Active items whose video is `unavailable`: never offered, never counted; the parent is told instead. */
    val unavailableItemIds: List<String>,
    /** By plan item id: the item's video and its watch threshold, for the items passed to the engine. */
    val videosByItem: Map<String, VideoEntity>,
    val watchThresholdByItem: Map<String, Double>,
)

object EngineInputs {
    /**
     * Returns null when the plan cannot be evaluated by this app version (a pace type from a newer server).
     *
     * [overlay] is `EventRepository.overlayEvents(childId)`: local events the stored snapshot history does not count yet
     * (SPEC section 9 merge rule). The caller is responsible for reading it in the same transaction as the snapshot rows, so an
     * event is never counted both in the history and in the overlay.
     */
    fun build(
        child: ChildEntity,
        plan: PlanWithItems,
        videos: List<VideoEntity>,
        historyViews: List<HistoryViewEntity>,
        historySeconds: List<HistorySecondsEntity>,
        overlay: List<LocalEventEntity>,
        today: LocalDate,
    ): EngineInput? {
        val p = plan.plan
        val paceType = when (p.paceType) {
            WirePaceType.VIDEOS_PER_DAY -> EnginePaceType.VIDEOS_PER_DAY
            WirePaceType.MINUTES_PER_DAY -> EnginePaceType.MINUTES_PER_DAY
            WirePaceType.UNKNOWN -> return null
        }
        val config = PlanConfig(paceType, p.paceValue, p.activeWeekdays, p.maxExtraPerDay, p.startDate, p.endDate)

        val videoById = videos.associateBy { it.id }
        val items = mutableListOf<ItemState>()
        val unavailable = mutableListOf<String>()
        val videosByItem = HashMap<String, VideoEntity>()
        val thresholds = HashMap<String, Double>()
        for (item in plan.items.sortedWith(compareBy({ it.position }, { it.id }))) {
            // Backend uses an inner join: an item whose video row is missing is skipped. Same here.
            val video = videoById[item.videoId] ?: continue
            val status = when (item.status) {
                WireItemStatus.ACTIVE -> ItemStatus.ACTIVE
                WireItemStatus.COMPLETED -> ItemStatus.COMPLETED
                WireItemStatus.DROPPED -> ItemStatus.DROPPED
                WireItemStatus.UNKNOWN -> continue // a status from a newer server: never offered, never counted
            }
            if (video.status == VideoStatus.UNAVAILABLE && status == ItemStatus.ACTIVE) {
                unavailable += item.id
                continue
            }
            items += ItemState(
                id = item.id, videoId = video.id, position = item.position, required = item.required,
                viewTarget = item.viewTarget, dailyCap = item.dailyCap, anchorDate = item.anchorDate,
                // An on_miss from a newer server behaves like the default (an ordinary queue item).
                onMiss = when (item.onMiss) {
                    WireOnMiss.SKIP -> EngineOnMiss.SKIP
                    WireOnMiss.DROP -> EngineOnMiss.DROP
                    WireOnMiss.SHIFT, WireOnMiss.UNKNOWN -> EngineOnMiss.SHIFT
                },
                status = status, durationSeconds = video.durationSeconds, addedOn = item.addedOn,
            )
            videosByItem[item.id] = video
            thresholds[item.id] = item.watchThreshold
        }
        val known = items.mapTo(HashSet()) { it.id }

        val local = parseOverlay(overlay, today)

        // Views: all-time server counts (one record per count) + local events. Server and engine both ignore future dates.
        val views = buildList {
            for (h in historyViews) {
                if (h.planItemId in known && !h.localDate.isAfter(today)) repeat(h.count) { add(ViewRecord(h.planItemId, h.localDate)) }
            }
            for (v in local.views) if (v.itemId in known) add(v)
        }

        // Watch seconds: only the catch-up window matters to the engine (backend: d >= today - 14).
        val catchUpFrom = today.minusDays(CATCH_UP_WINDOW_DAYS.toLong())
        val watch = mergeByDate(
            historySeconds.filter { it.kind == HistoryKind.WATCH }.map { it.localDate to it.seconds },
            local.watchSeconds,
        ).filter { (d, _) -> !d.isBefore(catchUpFrom) && !d.isAfter(today) }
        val seconds = watch.map { (d, s) -> SecondsRecord(d, s) }

        // App seconds and extensions: only today's value is used.
        val appToday = historySeconds.filter { it.kind == HistoryKind.APP && it.localDate == today }.sumOf { it.seconds } +
            local.appSeconds.filter { it.first == today }.sumOf { it.second }
        val extensionToday = historySeconds.filter { it.kind == HistoryKind.EXTENSION && it.localDate == today }.sumOf { it.seconds } +
            local.extensionSeconds.filter { it.first == today }.sumOf { it.second }

        return EngineInput(
            today = today,
            plan = config,
            items = items,
            views = views,
            seconds = seconds,
            appSeconds = if (appToday != 0) listOf(AppSecondsRecord(today, appToday)) else emptyList(),
            limits = ChildLimits(child.dailyAppMinutes, extensionToday),
            unavailableItemIds = unavailable,
            videosByItem = videosByItem,
            watchThresholdByItem = thresholds,
        )
    }

    /** Sums two (date, seconds) lists per date, sorted by date (like the server's grouped history). */
    private fun mergeByDate(a: List<Pair<LocalDate, Int>>, b: List<Pair<LocalDate, Int>>): List<Pair<LocalDate, Int>> =
        (a + b).groupBy({ it.first }, { it.second }).map { (d, v) -> d to v.sum() }.sortedBy { it.first }

    private class LocalNumbers(
        val views: List<ViewRecord>,
        val watchSeconds: List<Pair<LocalDate, Int>>,
        val appSeconds: List<Pair<LocalDate, Int>>,
        val extensionSeconds: List<Pair<LocalDate, Int>>,
    )

    /**
     * Local events -> the same numbers the server derives (events.py payloads; load_histories):
     *  - view_complete: one view for plan_item_id on the event's local_date;
     *  - watch_progress / time_extension: `seconds`, summed per day and rounded once per day (the server rounds the
     *    per-day sum too, so two small events do not each round up);
     *  - app_session: whole seconds between start and end (truncated, never negative) per event, like the server.
     * An event whose payload cannot be read is ignored (it is also what the server would have rejected).
     */
    private fun parseOverlay(events: List<LocalEventEntity>, today: LocalDate): LocalNumbers {
        val views = mutableListOf<ViewRecord>()
        val watch = HashMap<LocalDate, Double>()
        val extension = HashMap<LocalDate, Double>()
        val app = HashMap<LocalDate, Int>()
        for (e in events) {
            if (e.localDate.isAfter(today)) continue
            val payload = runCatching { KidTubeJson.instance.parseToJsonElement(e.payload).jsonObject }.getOrNull() ?: continue
            when (e.type) {
                EventType.VIEW_COMPLETE -> payload.string("plan_item_id")?.let { views += ViewRecord(it, e.localDate) }
                EventType.WATCH_PROGRESS -> payload.seconds()?.let { watch.merge(e.localDate, it, Double::plus) }
                EventType.TIME_EXTENSION -> payload.seconds()?.let { extension.merge(e.localDate, it, Double::plus) }
                EventType.APP_SESSION -> {
                    val start = payload.string("start")?.let { runCatching { Instant.parse(it) }.getOrNull() }
                    val end = payload.string("end")?.let { runCatching { Instant.parse(it) }.getOrNull() }
                    if (start != null && end != null) {
                        app.merge(e.localDate, maxOf(0L, Duration.between(start, end).seconds).toInt(), Int::plus)
                    }
                }
                EventType.PLAYBACK_ERROR, EventType.UNKNOWN -> Unit
            }
        }
        fun Map<LocalDate, Double>.rounded() = map { (d, s) -> d to Math.round(s).toInt() }
        return LocalNumbers(views, watch.rounded(), app.map { (d, s) -> d to s }, extension.rounded())
    }

    private fun JsonObject.string(key: String): String? = this[key]?.runCatching { jsonPrimitive.content }?.getOrNull()
    private fun JsonObject.seconds(): Double? = this["seconds"]?.runCatching { jsonPrimitive.doubleOrNull }?.getOrNull()?.takeIf { it >= 0 }
}
