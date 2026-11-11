package com.bbro.kidtube.engine

import java.time.LocalDate

// Kotlin port of backend/app/engine/types.py. PURE: no Android, Room, Clock or timezone. All times come in as arguments
// (the child's local `today` as a LocalDate). Names mirror the Python so the two can be diffed side by side.
//
// Deliberate differences from Python:
//  - Enum-like strings ("videos_per_day", "ok", ...) are Kotlin enums with a `wire` string (the JSON vectors use the wire form).
//  - No to_dict/from_dict here: the JSON codec for the parity vectors lives in test code (engine/EngineVectorJson.kt).
//  - Python validates in __post_init__; Kotlin validates in `init` with require() (IllegalArgumentException instead of ValueError).
//  - Sequences are immutable Lists (Python tuples).

enum class PaceType(val wire: String) {
    VIDEOS_PER_DAY("videos_per_day"), MINUTES_PER_DAY("minutes_per_day");

    companion object {
        fun fromWire(raw: String): PaceType = entries.firstOrNull { it.wire == raw }
            ?: throw IllegalArgumentException("pace_type: unknown value '$raw'")
    }
}

enum class OnMiss(val wire: String) {
    SHIFT("shift"), SKIP("skip"), DROP("drop");

    companion object {
        fun fromWire(raw: String): OnMiss = entries.firstOrNull { it.wire == raw }
            ?: throw IllegalArgumentException("on_miss: unknown value '$raw'")
    }
}

enum class ItemStatus(val wire: String) {
    ACTIVE("active"), COMPLETED("completed"), DROPPED("dropped");

    companion object {
        fun fromWire(raw: String): ItemStatus = entries.firstOrNull { it.wire == raw }
            ?: throw IllegalArgumentException("status: unknown value '$raw'")
    }
}

enum class DayStatus(val wire: String) {
    SCHEDULED("scheduled"), DAY_OFF("day_off"), OUTSIDE_DATES("outside_dates");

    companion object {
        fun fromWire(raw: String): DayStatus = entries.firstOrNull { it.wire == raw }
            ?: throw IllegalArgumentException("status: unknown value '$raw'")
    }
}

enum class FeedStatus(val wire: String) {
    OK("ok"), OUTSIDE_DATES("outside_dates"), DAY_OFF("day_off"), EMPTY("empty"),
    ALL_DONE("all_done"), TIME_UP("time_up"), DONE_TODAY("done_today");

    companion object {
        fun fromWire(raw: String): FeedStatus = entries.firstOrNull { it.wire == raw }
            ?: throw IllegalArgumentException("status: unknown value '$raw'")
    }
}

// ---------------------------------------------------------------- inputs

data class PlanConfig(
    val paceType: PaceType,
    val paceValue: Int,
    /** Bit `1 shl weekday`, Monday = 0 .. Sunday = 6. */
    val activeWeekdays: Int,
    /** Videos in videos mode, MINUTES in minutes mode. */
    val maxExtraPerDay: Int,
    val startDate: LocalDate,
    val endDate: LocalDate? = null,
) {
    init {
        require(paceValue >= 0 && maxExtraPerDay >= 0) { "pace_value and max_extra_per_day must be >= 0" }
        require(activeWeekdays in 0..127) { "active_weekdays must be a 7-bit mask" }
    }
}

data class ItemState(
    val id: String,
    val videoId: String,
    val position: Int,
    val required: Boolean,
    val viewTarget: Int?,
    val dailyCap: Int?,
    val anchorDate: LocalDate?,
    val onMiss: OnMiss,
    val status: ItemStatus,
    val durationSeconds: Int?,
    val addedOn: LocalDate,
)

/** One `view_complete` (one record per view). */
data class ViewRecord(val itemId: String, val localDate: LocalDate)

/** watch_progress seconds (used by the minutes pace). */
data class SecondsRecord(val localDate: LocalDate, val seconds: Int)

/** app_session foreground seconds (used by the daily app-time limit). */
data class AppSecondsRecord(val localDate: LocalDate, val seconds: Int)

data class ChildLimits(
    val dailyAppMinutes: Int? = null,
    val appTimeExtraSeconds: Int = 0,
)

// ---------------------------------------------------------------- outputs

data class FeedItem(
    val itemId: String,
    val videoId: String,
    val locked: Boolean,
    val required: Boolean,
)

data class FeedResult(
    val status: FeedStatus,
    val items: List<FeedItem>,
    /** Videos (videos mode) or seconds (minutes mode). */
    val quotaToday: Int,
    /** All views today, across items. */
    val viewsToday: Int,
    /** Videos mode only. */
    val remainingVideos: Int?,
    /** Minutes mode only. */
    val remainingSeconds: Int?,
    /** Same unit as [quotaToday]; the raw (uncapped) catch-up backlog. */
    val backlog: Int,
    val skippedItemIds: List<String>,
    val droppedItemIds: List<String>,
)

data class ProjectedView(val itemId: String, val videoId: String)

data class ProjectedDay(
    val date: LocalDate,
    val status: DayStatus,
    val views: List<ProjectedView>,
)

data class Projection(
    val days: List<ProjectedDay>,
    val finishDate: LocalDate?,
    val remainingFiniteViews: Int,
    val unscheduledItemIds: List<String>,
    val excludedUnlimitedItemIds: List<String>,
)
