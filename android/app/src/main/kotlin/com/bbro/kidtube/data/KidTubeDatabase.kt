package com.bbro.kidtube.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.bbro.kidtube.data.dao.ChildDao
import com.bbro.kidtube.data.dao.HistoryDao
import com.bbro.kidtube.data.dao.LocalEventDao
import com.bbro.kidtube.data.dao.OutboxDao
import com.bbro.kidtube.data.dao.PlanDao
import com.bbro.kidtube.data.dao.ReverifyDao
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
import com.bbro.kidtube.data.entity.ReverifyAttemptEntity
import com.bbro.kidtube.data.entity.SyncMetaEntity
import com.bbro.kidtube.data.entity.VideoEntity

/**
 * Schemas are committed in app/schemas/.../N.json (1 = first version, 2 = adds reverify_attempt). EVERY schema change
 * needs a version bump plus a Migration; there is no destructive fallback.
 */
@Database(
    entities = [
        ChildEntity::class, VideoEntity::class, PlanEntity::class, PlanItemEntity::class,
        HistoryViewEntity::class, HistorySecondsEntity::class, SyncMetaEntity::class,
        LocalEventEntity::class, OutboxVideoEntity::class, OutboxPlacementEntity::class,
        ReverifyAttemptEntity::class,
    ],
    version = 2,
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
    abstract fun reverifyDao(): ReverifyDao

    companion object {
        const val NAME = "kidtube.db"

        /** v1 -> v2: the sync engine's per-video re-verify timestamps. Purely additive; no existing row is touched. */
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `reverify_attempt` " +
                        "(`videoId` TEXT NOT NULL, `lastAttemptAt` INTEGER NOT NULL, PRIMARY KEY(`videoId`))",
                )
            }
        }

        val ALL_MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2)
    }
}
