package com.bbro.kidtube.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface OutboxDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entry: OutboxEntryEntity)

    @Query("SELECT * FROM outbox ORDER BY createdAtEpochMs, id")
    fun observeAll(): Flow<List<OutboxEntryEntity>>

    @Query("DELETE FROM outbox WHERE id = :id")
    suspend fun delete(id: String)
}
