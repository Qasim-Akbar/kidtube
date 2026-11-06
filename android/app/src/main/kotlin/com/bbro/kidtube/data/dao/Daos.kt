package com.bbro.kidtube.data.dao

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
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
}

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
