package com.bbro.kidtube.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Placeholder outbox row (SPEC section 5/9) that proves the Room + KSP pipeline. The real
 * schema (videos, plans, events) arrives with the offline-sharing task; expect this to change.
 * [id] is a client-generated UUID, the idempotency key.
 */
@Entity(tableName = "outbox")
data class OutboxEntryEntity(
    @PrimaryKey val id: String,
    val kind: String,
    val payloadJson: String,
    val createdAtEpochMs: Long,
    val attempts: Int = 0,
)
