package com.bbro.kidtube.data

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [OutboxEntryEntity::class], version = 1, exportSchema = true)
abstract class KidTubeDatabase : RoomDatabase() {
    abstract fun outboxDao(): OutboxDao

    companion object {
        const val NAME = "kidtube.db"
    }
}
