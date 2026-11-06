package com.bbro.kidtube.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.bbro.kidtube.data.dao.ChildDao
import com.bbro.kidtube.data.dao.HistoryDao
import com.bbro.kidtube.data.dao.LocalEventDao
import com.bbro.kidtube.data.dao.OutboxDao
import com.bbro.kidtube.data.dao.PlanDao
import com.bbro.kidtube.data.dao.SnapshotDao
import com.bbro.kidtube.data.dao.VideoDao
import com.bbro.kidtube.data.entity.ChildEntity
import com.bbro.kidtube.data.entity.Converters
import com.bbro.kidtube.data.entity.HistorySecondsEntity
import com.bbro.kidtube.data.entity.HistoryViewEntity
import com.bbro.kidtube.data.entity.LocalEventEntity
import com.bbro.kidtube.data.entity.OutboxPlacementEntity
import com.bbro.kidtube.data.entity.OutboxVideoEntity
import com.bbro.kidtube.data.entity.PlanEntity
import com.bbro.kidtube.data.entity.PlanItemEntity
import com.bbro.kidtube.data.entity.SyncMetaEntity
import com.bbro.kidtube.data.entity.VideoEntity

/**
 * Schema version 1 is the first one that is committed (app/schemas/.../1.json). From here on EVERY schema change
 * needs a version bump plus a Migration; there is no destructive fallback.
 */
@Database(
    entities = [
        ChildEntity::class, VideoEntity::class, PlanEntity::class, PlanItemEntity::class,
        HistoryViewEntity::class, HistorySecondsEntity::class, SyncMetaEntity::class,
        LocalEventEntity::class, OutboxVideoEntity::class, OutboxPlacementEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class KidTubeDatabase : RoomDatabase() {
    abstract fun childDao(): ChildDao
    abstract fun videoDao(): VideoDao
    abstract fun planDao(): PlanDao
    abstract fun historyDao(): HistoryDao
    abstract fun localEventDao(): LocalEventDao
    abstract fun outboxDao(): OutboxDao
    abstract fun snapshotDao(): SnapshotDao

    companion object {
        const val NAME = "kidtube.db"
    }
}
