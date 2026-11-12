@file:UseSerializers(LocalDateSerializer::class, InstantSerializer::class)

package com.bbro.kidtube.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.time.LocalDate

// DTOs mirror backend/app/schemas/*.py field for field. UUIDs are kept as Strings (they are opaque ids here).

// ---- auth (schemas/auth.py) -----------------------------------------------------------------

@Serializable
data class RegisterRequest(
    val email: String,
    val password: String,
    @SerialName("family_name") val familyName: String = "My Family",
    val timezone: String? = null,
)

@Serializable
data class RegisterResponse(
    @SerialName("parent_id") val parentId: String,
    @SerialName("family_id") val familyId: String,
    val email: String,
)

@Serializable
data class LoginRequest(val email: String, val password: String)

@Serializable
data class TokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("token_type") val tokenType: String = "bearer",
)

@Serializable
data class RefreshRequest(@SerialName("refresh_token") val refreshToken: String)

@Serializable
data class AccessTokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("token_type") val tokenType: String = "bearer",
)

// ---- children (schemas/child.py) ------------------------------------------------------------

@Serializable
data class ChildCreate(
    val name: String,
    val avatar: String? = null,
    @SerialName("daily_app_minutes") val dailyAppMinutes: Int? = null,
)

@Serializable
data class ChildDto(
    val id: String,
    val name: String,
    val avatar: String? = null,
    @SerialName("daily_app_minutes") val dailyAppMinutes: Int? = null,
)

// ---- devices (schemas/device.py) ------------------------------------------------------------

@Serializable
data class PairCodeResponse(val code: String, @SerialName("expires_at") val expiresAt: Instant)

@Serializable
data class PairRequest(val code: String, @SerialName("device_name") val deviceName: String)

@Serializable
data class PairResponse(
    @SerialName("device_id") val deviceId: String,
    @SerialName("family_id") val familyId: String,
    @SerialName("device_token") val deviceToken: String,
)

@Serializable
data class DeviceDto(
    val id: String,
    val name: String,
    val role: ServerDeviceRole,
    @SerialName("paired_at") val pairedAt: Instant? = null,
    @SerialName("revoked_at") val revokedAt: Instant? = null,
)

// ---- videos (schemas/video.py) --------------------------------------------------------------

@Serializable
data class VideoCreate(val url: String)

@Serializable
data class VideoDto(
    val id: String,
    val platform: Platform,
    @SerialName("platform_video_id") val platformVideoId: String,
    val url: String,
    val title: String? = null,
    @SerialName("thumbnail_url") val thumbnailUrl: String? = null,
    @SerialName("duration_seconds") val durationSeconds: Int? = null,
    val status: VideoStatus,
    @SerialName("added_by") val addedBy: String? = null,
    @SerialName("added_at") val addedAt: Instant,
)

// ---- plans (schemas/plan.py) ----------------------------------------------------------------

@Serializable
data class PlanWrite(
    @SerialName("pace_type") val paceType: PaceType,
    @SerialName("pace_value") val paceValue: Int,
    /** Bitmask, Monday = bit 0 .. Sunday = bit 6 (1..127). */
    @SerialName("active_weekdays") val activeWeekdays: Int,
    /** Omitted = server default (1 in videos mode, pace_value in minutes mode). Counts MINUTES in minutes mode. */
    @SerialName("max_extra_per_day") val maxExtraPerDay: Int? = null,
    @SerialName("start_date") val startDate: LocalDate,
    @SerialName("end_date") val endDate: LocalDate? = null,
)

@Serializable
data class VideoSummaryDto(
    val id: String,
    val title: String? = null,
    @SerialName("thumbnail_url") val thumbnailUrl: String? = null,
    val platform: Platform,
    @SerialName("platform_video_id") val platformVideoId: String,
    val status: VideoStatus,
    @SerialName("duration_seconds") val durationSeconds: Int? = null,
)

@Serializable
data class PlanItemDto(
    val id: String,
    @SerialName("video_id") val videoId: String,
    val position: Int,
    val required: Boolean,
    @SerialName("view_target") val viewTarget: Int? = null,
    @SerialName("daily_cap") val dailyCap: Int? = null,
    @SerialName("watch_threshold") val watchThreshold: Double,
    @SerialName("anchor_date") val anchorDate: LocalDate? = null,
    @SerialName("on_miss") val onMiss: OnMiss,
    val status: PlanItemStatus,
    @SerialName("added_on") val addedOn: LocalDate,
    val video: VideoSummaryDto,
)

@Serializable
data class PlanDto(
    val id: String,
    @SerialName("child_id") val childId: String,
    @SerialName("pace_type") val paceType: PaceType,
    @SerialName("pace_value") val paceValue: Int,
    @SerialName("active_weekdays") val activeWeekdays: Int,
    @SerialName("max_extra_per_day") val maxExtraPerDay: Int,
    @SerialName("start_date") val startDate: LocalDate,
    @SerialName("end_date") val endDate: LocalDate? = null,
    val version: Int,
    val items: List<PlanItemDto>,
)

@Serializable
data class PlanItemCreate(
    @SerialName("video_id") val videoId: String,
    val required: Boolean = false,
    @SerialName("view_target") val viewTarget: Int? = null,
    @SerialName("daily_cap") val dailyCap: Int? = null,
    @SerialName("anchor_date") val anchorDate: LocalDate? = null,
    @SerialName("on_miss") val onMiss: OnMiss = OnMiss.SHIFT,
    @SerialName("watch_threshold") val watchThreshold: Double = 0.9,
    /** Zero-based index; null = append. */
    val position: Int? = null,
)

@Serializable
data class ReorderRequest(@SerialName("item_ids") val itemIds: List<String>)

// ---- events (schemas/event.py, services/events.py) ------------------------------------------

/** One event as POST /events and /sync/push accept it. `occurred_at` is UTC ISO-8601; `local_date` the child's day. */
@Serializable
data class EventDto(
    val id: String,
    @SerialName("child_id") val childId: String,
    val type: EventType,
    @SerialName("occurred_at") val occurredAt: Instant,
    @SerialName("local_date") val localDate: LocalDate,
    val payload: JsonObject,
)

@Serializable
data class EventBatch(val events: List<EventDto>)

@Serializable
data class EventResultDto(
    val index: Int,
    val id: String? = null,
    val status: EventResultStatus,
    val reason: String? = null,
)

@Serializable
data class EventBatchResult(val results: List<EventResultDto>)

// ---- sync (schemas/sync.py) -----------------------------------------------------------------

@Serializable
data class HistoryViewsDto(
    @SerialName("plan_item_id") val planItemId: String,
    @SerialName("local_date") val localDate: LocalDate,
    val count: Int,
)

@Serializable
data class HistorySecondsDto(
    @SerialName("local_date") val localDate: LocalDate,
    val seconds: Int,
)

@Serializable
data class ChildHistoryDto(
    val views: List<HistoryViewsDto>,
    @SerialName("watch_seconds") val watchSeconds: List<HistorySecondsDto>,
    @SerialName("app_seconds") val appSeconds: List<HistorySecondsDto>,
    @SerialName("time_extension_seconds") val timeExtensionSeconds: List<HistorySecondsDto>,
)

@Serializable
data class SnapshotChildDto(
    val id: String,
    val name: String,
    val avatar: String? = null,
    @SerialName("daily_app_minutes") val dailyAppMinutes: Int? = null,
    val plan: PlanDto? = null,
    val history: ChildHistoryDto,
)

@Serializable
data class SnapshotDto(
    @SerialName("server_time") val serverTime: Instant,
    val timezone: String,
    @SerialName("snapshot_version") val snapshotVersion: String,
    val children: List<SnapshotChildDto>,
    val videos: List<VideoDto>,
)

@Serializable
data class PushVideoDto(val url: String)

@Serializable
data class PushBody(
    val videos: List<PushVideoDto> = emptyList(),
    val events: List<EventDto> = emptyList(),
)

@Serializable
data class PushVideoResult(
    val index: Int,
    val status: PushVideoStatus,
    @SerialName("video_id") val videoId: String? = null,
    @SerialName("video_status") val videoStatus: VideoStatus? = null,
    val reason: String? = null,
    val retryable: Boolean = false,
)

@Serializable
data class PushEventResult(
    val index: Int,
    val id: String? = null,
    val status: EventResultStatus,
    val reason: String? = null,
    val retryable: Boolean = false,
)

@Serializable
data class PushResult(
    val videos: List<PushVideoResult>,
    val events: List<PushEventResult>,
)

@Serializable
data class HealthDto(val status: String)

// ---- PATCH bodies ---------------------------------------------------------------------------
// The backend distinguishes "key absent" (leave unchanged) from "key = null" (clear it). With explicitNulls = false a
// data class cannot send an explicit null, so patches are built as JsonObject.

sealed interface PatchField<out T> {
    data object Absent : PatchField<Nothing>
    data class Value<T>(val value: T?) : PatchField<T>
}

private fun patchJson(fields: Map<String, PatchField<*>>): JsonObject {
    val out = LinkedHashMap<String, JsonElement>()
    for ((key, field) in fields) {
        if (field !is PatchField.Value<*>) continue
        out[key] = when (val v = field.value) {
            null -> JsonNull
            is String -> JsonPrimitive(v)
            is Boolean -> JsonPrimitive(v)
            is Number -> JsonPrimitive(v)
            is LocalDate -> JsonPrimitive(v.toString())
            is Enum<*> -> JsonPrimitive((v as WireEnum).wire)
            else -> error("Unsupported patch value type ${v::class}")
        }
    }
    return JsonObject(out)
}

/** PATCH /children/{id}: only the fields that are set are sent; set `avatar`/`dailyAppMinutes` to Value(null) to clear. */
data class ChildPatch(
    val name: PatchField<String> = PatchField.Absent,
    val avatar: PatchField<String> = PatchField.Absent,
    val dailyAppMinutes: PatchField<Int> = PatchField.Absent,
) {
    fun toJson(): JsonObject = patchJson(
        mapOf("name" to name, "avatar" to avatar, "daily_app_minutes" to dailyAppMinutes),
    )
}

/** PATCH /plan-items/{id}: `viewTarget`, `dailyCap`, `anchorDate` may be cleared with Value(null). */
data class PlanItemPatch(
    val required: PatchField<Boolean> = PatchField.Absent,
    val viewTarget: PatchField<Int> = PatchField.Absent,
    val dailyCap: PatchField<Int> = PatchField.Absent,
    val anchorDate: PatchField<LocalDate> = PatchField.Absent,
    val onMiss: PatchField<OnMiss> = PatchField.Absent,
    val watchThreshold: PatchField<Double> = PatchField.Absent,
    val status: PatchField<PlanItemStatus> = PatchField.Absent,
) {
    fun toJson(): JsonObject = patchJson(
        mapOf(
            "required" to required, "view_target" to viewTarget, "daily_cap" to dailyCap,
            "anchor_date" to anchorDate, "on_miss" to onMiss, "watch_threshold" to watchThreshold, "status" to status,
        ),
    )
}

// ---- reports (schemas/report.py) ------------------------------------------------------------

@Serializable
data class ReportDayDto(
    val date: LocalDate,
    @SerialName("watch_seconds") val watchSeconds: Int,
    @SerialName("app_seconds") val appSeconds: Int,
    @SerialName("browsing_seconds") val browsingSeconds: Int = 0,
    @SerialName("views_completed") val viewsCompleted: Int,
)

@Serializable
data class ReportTotalsDto(
    @SerialName("watch_seconds") val watchSeconds: Int,
    @SerialName("app_seconds") val appSeconds: Int,
    @SerialName("browsing_seconds") val browsingSeconds: Int = 0,
    @SerialName("views_completed") val viewsCompleted: Int,
)

@Serializable
data class TopVideoDto(
    @SerialName("video_id") val videoId: String,
    val title: String? = null,
    @SerialName("thumbnail_url") val thumbnailUrl: String? = null,
    val views: Int,
    @SerialName("watch_seconds") val watchSeconds: Int,
)

@Serializable
data class ItemCompletionDto(
    @SerialName("item_id") val itemId: String,
    @SerialName("video_id") val videoId: String,
    val title: String? = null,
    val required: Boolean,
    val status: PlanItemStatus = PlanItemStatus.UNKNOWN,
    @SerialName("view_target") val viewTarget: Int? = null,
    @SerialName("views_total") val viewsTotal: Int,
    @SerialName("views_in_range") val viewsInRange: Int,
    val completed: Boolean,
)

@Serializable
data class PlaybackErrorDto(
    @SerialName("video_id") val videoId: String,
    val title: String? = null,
    val code: String,
    val count: Int,
)

@Serializable
data class PlannedVsWatchedDto(
    @SerialName("pace_type") val paceType: PaceType = PaceType.UNKNOWN,
    /** "videos" (videos mode) or "seconds" (minutes mode). */
    val unit: String,
    @SerialName("window_start") val windowStart: LocalDate? = null,
    @SerialName("window_end") val windowEnd: LocalDate? = null,
    @SerialName("active_days") val activeDays: Int,
    val expected: Int,
    val actual: Int,
)

@Serializable
data class PlanProgressDto(
    @SerialName("as_of") val asOf: LocalDate,
    @SerialName("finish_date") val finishDate: LocalDate? = null,
    @SerialName("remaining_finite_views") val remainingFiniteViews: Int,
)

/** GET /reports/children/{id}?range=&date= (parent only). Every number is used exactly as the server sends it. */
@Serializable
data class ReportDto(
    @SerialName("child_id") val childId: String,
    /** "day" | "week" | "month". */
    val range: String,
    val date: LocalDate,
    val start: LocalDate,
    val end: LocalDate,
    val days: List<ReportDayDto>,
    val totals: ReportTotalsDto,
    @SerialName("top_videos") val topVideos: List<TopVideoDto> = emptyList(),
    @SerialName("per_video") val perVideo: List<ItemCompletionDto> = emptyList(),
    @SerialName("playback_errors") val playbackErrors: List<PlaybackErrorDto> = emptyList(),
    @SerialName("planned_vs_watched") val plannedVsWatched: PlannedVsWatchedDto? = null,
    @SerialName("plan_progress") val planProgress: PlanProgressDto? = null,
)
