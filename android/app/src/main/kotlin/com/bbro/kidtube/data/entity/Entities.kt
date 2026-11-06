package com.bbro.kidtube.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.bbro.kidtube.data.remote.EventType
import com.bbro.kidtube.data.remote.OnMiss
import com.bbro.kidtube.data.remote.PaceType
import com.bbro.kidtube.data.remote.Platform
import com.bbro.kidtube.data.remote.PlanItemStatus
import com.bbro.kidtube.data.remote.VideoStatus
import com.bbro.kidtube.data.remote.WireEnum
import java.time.Instant
import java.time.LocalDate

// ---- local-only enums (their wire values are also used as literals in DAO queries) -------------

enum class LocalEventState(override val wire: String) : WireEnum {
    /** Not yet acknowledged by the server (still in the outbox). */
    PENDING("pending"),

    /** The server accepted it (or already had it). [LocalEventEntity.ackedCycle] says when. */
    ACKED("acked"),

    /** Permanently rejected by the server; never counted. */
    REJECTED("rejected"),
}

enum class OutboxVideoState(override val wire: String) : WireEnum {
    PENDING("pending"), SYNCED("synced"), REJECTED("rejected")
}

enum class PlacementState(override val wire: String) : WireEnum {
    PENDING("pending"), DONE("done"), FAILED("failed")
}

enum class HistoryKind(override val wire: String) : WireEnum {
    WATCH("watch"), APP("app"), EXTENSION("extension")
}

// ---- snapshot mirror (replaced as a whole on every pull) -----------------------------------------

@Entity(tableName = "child")
data class ChildEntity(
    @PrimaryKey val id: String,
    val name: String,
    val avatar: String?,
    val dailyAppMinutes: Int?,
)

@Entity(tableName = "video")
data class VideoEntity(
    @PrimaryKey val id: String,
    val platform: Platform,
    val platformVideoId: String,
    val url: String,
    val title: String?,
    val thumbnailUrl: String?,
    val durationSeconds: Int?,
    val status: VideoStatus,
    val addedBy: String?,
    val addedAt: Instant,
)

@Entity(
    tableName = "plan",
    foreignKeys = [ForeignKey(ChildEntity::class, parentColumns = ["id"], childColumns = ["childId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("childId", unique = true)],
)
data class PlanEntity(
    @PrimaryKey val id: String,
    val childId: String,
    val paceType: PaceType,
    val paceValue: Int,
    val activeWeekdays: Int,
    val maxExtraPerDay: Int,
    val startDate: LocalDate,
    val endDate: LocalDate?,
    val version: Int,
)

@Entity(
    tableName = "plan_item",
    foreignKeys = [ForeignKey(PlanEntity::class, parentColumns = ["id"], childColumns = ["planId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("planId"), Index("videoId")],
)
data class PlanItemEntity(
    @PrimaryKey val id: String,
    val planId: String,
    val videoId: String,
    val position: Int,
    val required: Boolean,
    val viewTarget: Int?,
    val dailyCap: Int?,
    val watchThreshold: Double,
    val anchorDate: LocalDate?,
    val onMiss: OnMiss,
    val status: PlanItemStatus,
    val addedOn: LocalDate,
)

// ---- server history (replaced with the snapshot) ---------------------------------------------------

/** All-time `view_complete` count for one plan item on one child-local day, as of the stored snapshot. */
@Entity(
    tableName = "history_view",
    primaryKeys = ["childId", "planItemId", "localDate"],
    foreignKeys = [ForeignKey(ChildEntity::class, parentColumns = ["id"], childColumns = ["childId"], onDelete = ForeignKey.CASCADE)],
)
data class HistoryViewEntity(
    val childId: String,
    val planItemId: String,
    val localDate: LocalDate,
    val count: Int,
)

/** Seconds for one child-local day; [kind] folds the snapshot's three lists (watch_seconds, app_seconds, time_extension_seconds). */
@Entity(
    tableName = "history_seconds",
    primaryKeys = ["childId", "kind", "localDate"],
    foreignKeys = [ForeignKey(ChildEntity::class, parentColumns = ["id"], childColumns = ["childId"], onDelete = ForeignKey.CASCADE)],
)
data class HistorySecondsEntity(
    val childId: String,
    val kind: HistoryKind,
    val localDate: LocalDate,
    val seconds: Int,
)

/**
 * Single row (id = 1). The cycle counters implement the overlay rule of SPEC section 9:
 *  - [currentCycle] is the number of the latest pull that STARTED (incremented at the start of every pull).
 *  - [historyCycle] is the number of the pull whose snapshot is stored now.
 *  - An event acknowledged by a push gets `ackedCycle = currentCycle + 1`: the first pull that can be sure to include it.
 *    The stored snapshot already counts the event iff `historyCycle >= ackedCycle`.
 */
@Entity(tableName = "sync_meta")
data class SyncMetaEntity(
    @PrimaryKey val id: Int = 1,
    val etag: String?,
    val lastPullStartedAt: Instant?,
    val historyCycle: Long,
    val currentCycle: Long,
)

// ---- local writes ----------------------------------------------------------------------------

@Entity(tableName = "local_event", indices = [Index("childId", "state"), Index("state", "createdAt")])
data class LocalEventEntity(
    @PrimaryKey val id: String,
    val childId: String,
    val type: EventType,
    val occurredAt: Instant,
    val localDate: LocalDate,
    /** The wire payload, JSON text. */
    val payload: String,
    val state: LocalEventState,
    val ackedCycle: Long?,
    val rejectReason: String?,
    val createdAt: Instant,
)

@Entity(tableName = "outbox_video")
data class OutboxVideoEntity(
    @PrimaryKey val id: String,
    val rawUrl: String,
    val state: OutboxVideoState,
    val serverVideoId: String?,
    val rejectReason: String?,
    val attempts: Int,
    val createdAt: Instant,
)

@Entity(
    tableName = "outbox_placement",
    foreignKeys = [ForeignKey(OutboxVideoEntity::class, parentColumns = ["id"], childColumns = ["outboxVideoId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("outboxVideoId")],
)
data class OutboxPlacementEntity(
    @PrimaryKey val id: String,
    val outboxVideoId: String,
    /** Null = every child in the stored snapshot (the sync engine expands it into one placement per child). */
    val childId: String?,
    val required: Boolean,
    val viewTarget: Int?,
    val dailyCap: Int?,
    val anchorDate: LocalDate?,
    val onMiss: OnMiss,
    val state: PlacementState,
    val reason: String?,
)

// ---- sync bookkeeping (schema v2) -------------------------------------------------------------------

/**
 * When the sync engine last asked the server to re-verify a still-`pending` YouTube video (at most once per hour per
 * video). No foreign key on purpose: the snapshot replace deletes and re-inserts `video` rows on every pull, and this
 * history must survive that. Rows for videos that left the snapshot are pruned by the engine.
 */
@Entity(tableName = "reverify_attempt")
data class ReverifyAttemptEntity(
    @PrimaryKey val videoId: String,
    val lastAttemptAt: Instant,
)
