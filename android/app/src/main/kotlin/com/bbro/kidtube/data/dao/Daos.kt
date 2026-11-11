package com.bbro.kidtube.data.dao

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Transaction
import com.bbro.kidtube.data.entity.ChildEntity
import com.bbro.kidtube.data.entity.HistorySecondsEntity
import com.bbro.kidtube.data.entity.HistoryViewEntity
import com.bbro.kidtube.data.entity.LocalEventEntity
import com.bbro.kidtube.data.entity.OutboxPlacementEntity
import com.bbro.kidtube.data.entity.OutboxVideoEntity
import com.bbro.kidtube.data.entity.PlanEntity
import com.bbro.kidtube.data.entity.PlanItemEntity
import com.bbro.kidtube.data.entity.ReverifyAttemptEntity
import com.bbro.kidtube.data.entity.SyncMetaEntity
import com.bbro.kidtube.data.entity.VideoEntity
import kotlinx.coroutines.flow.Flow
import java.time.Instant

// Flows are for the UI; suspend functions are for sync and repositories.

@Dao
interface ChildDao {
    @Query("SELECT * FROM child ORDER BY rowid")
    fun observeAll(): Flow<List<ChildEntity>>

    @Query("SELECT * FROM child ORDER BY rowid")
    suspend fun getAll(): List<ChildEntity>

    @Query("SELECT * FROM child WHERE id = :id")
    suspend fun get(id: String): ChildEntity?
}

@Dao
interface VideoDao {
    @Query("SELECT * FROM video ORDER BY rowid")
    fun observeAll(): Flow<List<VideoEntity>>

    @Query("SELECT * FROM video ORDER BY rowid")
    suspend fun getAll(): List<VideoEntity>

    @Query("SELECT * FROM video WHERE id = :id")
    suspend fun get(id: String): VideoEntity?
}

/** A plan with its items (unordered: callers sort by position). */
data class PlanWithItems(
    @Embedded val plan: PlanEntity,
    @Relation(parentColumn = "id", entityColumn = "planId") val items: List<PlanItemEntity>,
)

@Dao
interface PlanDao {
    @Transaction
    @Query("SELECT * FROM plan WHERE childId = :childId")
    fun observeForChild(childId: String): Flow<PlanWithItems?>

    @Transaction
    @Query("SELECT * FROM plan WHERE childId = :childId")
    suspend fun getForChild(childId: String): PlanWithItems?

    @Transaction
    @Query("SELECT * FROM plan")
    fun observeAll(): Flow<List<PlanWithItems>>
}

@Dao
interface HistoryDao {
    @Query("SELECT * FROM history_view WHERE childId = :childId ORDER BY localDate, planItemId")
    suspend fun views(childId: String): List<HistoryViewEntity>

    @Query("SELECT * FROM history_view WHERE childId = :childId ORDER BY localDate, planItemId")
    fun observeViews(childId: String): Flow<List<HistoryViewEntity>>

    @Query("SELECT * FROM history_seconds WHERE childId = :childId ORDER BY kind, localDate")
    suspend fun seconds(childId: String): List<HistorySecondsEntity>

    @Query("SELECT * FROM history_seconds WHERE childId = :childId ORDER BY kind, localDate")
    fun observeSeconds(childId: String): Flow<List<HistorySecondsEntity>>
}

@Dao
interface LocalEventDao {
    @Insert
    suspend fun insert(event: LocalEventEntity)

    @Query("SELECT * FROM local_event WHERE id = :id")
    suspend fun get(id: String): LocalEventEntity?

    @Query("SELECT * FROM local_event WHERE state = 'pending' ORDER BY createdAt, id LIMIT :limit")
    suspend fun pending(limit: Int): List<LocalEventEntity>

    /**
     * Events to lay on top of the stored snapshot history (SPEC section 9, merge rule): still pending, or
     * acknowledged in a cycle NEWER than the stored history's cycle. Rejected events never count.
     * One statement, so history_cycle and the events are read consistently.
     */
    @Query(
        """
        SELECT * FROM local_event
        WHERE childId = :childId
          AND (state = 'pending'
               OR (state = 'acked'
                   AND ackedCycle > COALESCE((SELECT historyCycle FROM sync_meta WHERE id = 1), 0)))
        ORDER BY occurredAt, id
        """,
    )
    suspend fun overlay(childId: String): List<LocalEventEntity>

    /** Acknowledge pending events; ackedCycle is read in the same statement, so it cannot race a pull starting. */
    @Query(
        """
        UPDATE local_event
        SET state = 'acked',
            ackedCycle = COALESCE((SELECT currentCycle FROM sync_meta WHERE id = 1), 0) + 1
        WHERE id IN (:ids) AND state = 'pending'
        """,
    )
    suspend fun markAcked(ids: List<String>): Int

    @Query("UPDATE local_event SET state = 'rejected', rejectReason = :reason WHERE id = :id AND state = 'pending'")
    suspend fun markRejected(id: String, reason: String?): Int

    @Query("SELECT COUNT(*) FROM local_event WHERE state = 'pending'")
    fun observePendingCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM local_event WHERE state = 'rejected'")
    fun observeRejectedCount(): Flow<Int>
}

data class OutboxVideoWithPlacements(
    @Embedded val video: OutboxVideoEntity,
    @Relation(parentColumn = "id", entityColumn = "outboxVideoId") val placements: List<OutboxPlacementEntity>,
)

@Dao
abstract class OutboxDao {
    @Insert
    abstract suspend fun insertVideo(video: OutboxVideoEntity)

    @Insert
    abstract suspend fun insertPlacements(placements: List<OutboxPlacementEntity>)

    @Transaction
    open suspend fun enqueue(video: OutboxVideoEntity, placements: List<OutboxPlacementEntity>) {
        insertVideo(video)
        insertPlacements(placements)
    }

    @Query("SELECT * FROM outbox_video WHERE state = 'pending' ORDER BY createdAt, id")
    abstract suspend fun pendingVideos(): List<OutboxVideoEntity>

    @Query("SELECT * FROM outbox_video ORDER BY createdAt, id")
    abstract fun observeVideos(): Flow<List<OutboxVideoEntity>>

    @Query("SELECT * FROM outbox_placement WHERE outboxVideoId = :outboxVideoId ORDER BY id")
    abstract suspend fun placementsFor(outboxVideoId: String): List<OutboxPlacementEntity>

    /**
     * Every outbox link WITH its placements from ONE query, so the UI never sees a link without the placements that were
     * saved in the same transaction (two separate flows could emit one before the other).
     */
    @Transaction
    @Query("SELECT * FROM outbox_video ORDER BY createdAt, id")
    abstract fun observeWithPlacements(): Flow<List<OutboxVideoWithPlacements>>

    // ---- state transitions used by the sync engine ----

    @Query("UPDATE outbox_video SET state = 'synced', serverVideoId = :serverVideoId, rejectReason = NULL WHERE id = :id AND state = 'pending'")
    abstract suspend fun markVideoSynced(id: String, serverVideoId: String): Int

    @Query("UPDATE outbox_video SET state = 'rejected', rejectReason = :reason WHERE id = :id AND state = 'pending'")
    abstract suspend fun markVideoRejected(id: String, reason: String?): Int

    @Query("UPDATE outbox_video SET attempts = attempts + 1 WHERE id = :id")
    abstract suspend fun bumpAttempts(id: String)

    /** A rejected video can never reach a plan: its still-pending placements fail for good. */
    @Query("UPDATE outbox_placement SET state = 'failed', reason = :reason WHERE outboxVideoId = :outboxVideoId AND state = 'pending'")
    abstract suspend fun failPendingPlacements(outboxVideoId: String, reason: String): Int

    @Query(
        """
        SELECT * FROM outbox_video
        WHERE state = 'synced'
          AND EXISTS (SELECT 1 FROM outbox_placement p WHERE p.outboxVideoId = outbox_video.id AND p.state = 'pending')
        ORDER BY createdAt, id
        """,
    )
    abstract suspend fun syncedVideosWithPendingPlacements(): List<OutboxVideoEntity>

    @Query("SELECT * FROM outbox_placement WHERE outboxVideoId = :outboxVideoId AND state = 'pending' ORDER BY id")
    abstract suspend fun pendingPlacements(outboxVideoId: String): List<OutboxPlacementEntity>

    @Query("UPDATE outbox_placement SET state = 'done', reason = NULL WHERE id = :id")
    abstract suspend fun markPlacementDone(id: String): Int

    @Query("UPDATE outbox_placement SET state = 'failed', reason = :reason WHERE id = :id")
    abstract suspend fun markPlacementFailed(id: String, reason: String): Int

    /** Keeps the placement pending but records why it is waiting (e.g. "no_plan"). */
    @Query("UPDATE outbox_placement SET reason = :reason WHERE id = :id AND state = 'pending'")
    abstract suspend fun setPlacementReason(id: String, reason: String?): Int

    /**
     * "Every child" becomes one concrete placement per child, so each child succeeds or fails on its own. The original
     * row is finished in the same transaction: a crash leaves either the original or the copies, never both.
     */
    @Transaction
    open suspend fun expandPlacement(original: OutboxPlacementEntity, copies: List<OutboxPlacementEntity>) {
        insertPlacements(copies)
        markPlacementDone(original.id)
    }

    // ---- counts for SyncStatus ----

    @Query("SELECT COUNT(*) FROM outbox_video WHERE state = 'pending'")
    abstract fun observePendingVideoCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM outbox_video WHERE state = 'rejected'")
    abstract fun observeRejectedVideoCount(): Flow<Int>

    /** Placements waiting for a plan to exist (reason no_plan), as opposed to failed ones. */
    @Query("SELECT COUNT(*) FROM outbox_placement WHERE state = 'pending' AND reason = 'no_plan'")
    abstract fun observeWaitingForPlanCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM outbox_placement WHERE state = 'failed'")
    abstract fun observeFailedPlacementCount(): Flow<Int>
}

/** Everything one pull replaces. */
data class SnapshotData(
    val children: List<ChildEntity>,
    val videos: List<VideoEntity>,
    val plans: List<PlanEntity>,
    val items: List<PlanItemEntity>,
    val historyViews: List<HistoryViewEntity>,
    val historySeconds: List<HistorySecondsEntity>,
)

@Dao
abstract class SnapshotDao {
    // ---- sync_meta ----
    @Query("SELECT * FROM sync_meta WHERE id = 1")
    abstract suspend fun meta(): SyncMetaEntity?

    @Query("SELECT * FROM sync_meta WHERE id = 1")
    abstract fun observeMeta(): Flow<SyncMetaEntity?>

    @Query("INSERT OR IGNORE INTO sync_meta (id, etag, lastPullStartedAt, historyCycle, currentCycle) VALUES (1, NULL, NULL, 0, 0)")
    protected abstract suspend fun ensureMeta()

    @Query("UPDATE sync_meta SET currentCycle = currentCycle + 1, lastPullStartedAt = :at WHERE id = 1")
    protected abstract suspend fun bumpCycle(at: Instant)

    @Query("SELECT currentCycle FROM sync_meta WHERE id = 1")
    protected abstract suspend fun currentCycle(): Long

    /** MAX: a slow older pull can never move the stored cycle backwards. */
    @Query("UPDATE sync_meta SET etag = :etag, historyCycle = MAX(historyCycle, :cycle) WHERE id = 1")
    protected abstract suspend fun storeSnapshotMeta(etag: String?, cycle: Long)

    @Query("UPDATE sync_meta SET historyCycle = MAX(historyCycle, :cycle) WHERE id = 1")
    protected abstract suspend fun storeHistoryCycle(cycle: Long)

    @Query("SELECT historyCycle FROM sync_meta WHERE id = 1")
    protected abstract suspend fun historyCycle(): Long

    /** Events the stored snapshot now counts are no longer needed locally. */
    @Query("DELETE FROM local_event WHERE state = 'acked' AND ackedCycle <= :historyCycle")
    protected abstract suspend fun pruneAcked(historyCycle: Long)

    /** Starts a pull: returns its cycle number. Call BEFORE the HTTP request. */
    @Transaction
    open suspend fun beginPull(at: Instant): Long {
        ensureMeta()
        bumpCycle(at)
        return currentCycle()
    }

    /** 304: the stored snapshot is still current as of the start of pull [cycle]. */
    @Transaction
    open suspend fun markUnchanged(cycle: Long) {
        ensureMeta()
        storeHistoryCycle(cycle)
        pruneAcked(historyCycle())
    }

    /**
     * Replaces children, videos, plans, items and history and records the new history cycle in ONE transaction:
     * the overlay events that the new snapshot now includes disappear in the same instant its history appears,
     * so nothing is double counted and nothing is missing in between. Any failure rolls everything back.
     */
    @Transaction
    open suspend fun replaceSnapshot(data: SnapshotData, etag: String?, cycle: Long) {
        deleteHistorySeconds()
        deleteHistoryViews()
        deletePlanItems()
        deletePlans()
        deleteChildren()
        deleteVideos()
        insertChildren(data.children)
        insertVideos(data.videos)
        insertPlans(data.plans)
        insertItems(data.items)
        insertHistoryViews(data.historyViews)
        insertHistorySeconds(data.historySeconds)
        ensureMeta()
        storeSnapshotMeta(etag, cycle)
        pruneAcked(historyCycle())
    }

    @Query("DELETE FROM history_seconds") protected abstract suspend fun deleteHistorySeconds()
    @Query("DELETE FROM history_view") protected abstract suspend fun deleteHistoryViews()
    @Query("DELETE FROM plan_item") protected abstract suspend fun deletePlanItems()
    @Query("DELETE FROM plan") protected abstract suspend fun deletePlans()
    @Query("DELETE FROM child") protected abstract suspend fun deleteChildren()
    @Query("DELETE FROM video") protected abstract suspend fun deleteVideos()

    @Insert protected abstract suspend fun insertChildren(rows: List<ChildEntity>)
    @Insert protected abstract suspend fun insertVideos(rows: List<VideoEntity>)
    @Insert protected abstract suspend fun insertPlans(rows: List<PlanEntity>)
    @Insert protected abstract suspend fun insertItems(rows: List<PlanItemEntity>)
    @Insert protected abstract suspend fun insertHistoryViews(rows: List<HistoryViewEntity>)
    @Insert protected abstract suspend fun insertHistorySeconds(rows: List<HistorySecondsEntity>)
}

@Dao
interface ReverifyDao {
    @Query("SELECT * FROM reverify_attempt")
    suspend fun all(): List<ReverifyAttemptEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: ReverifyAttemptEntity)

    @Query("DELETE FROM reverify_attempt WHERE videoId NOT IN (SELECT id FROM video)")
    suspend fun pruneForgottenVideos(): Int
}
