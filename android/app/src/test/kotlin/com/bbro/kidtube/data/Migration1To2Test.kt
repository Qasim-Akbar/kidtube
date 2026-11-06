package com.bbro.kidtube.data

import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import com.bbro.kidtube.data.entity.LocalEventState
import com.bbro.kidtube.data.entity.OutboxVideoState
import com.bbro.kidtube.data.remote.KidTubeJson
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

/**
 * Room migration 1 -> 2, checked against the COMMITTED schema files (app/schemas/.../1.json and 2.json, located through
 * the `room.schemas.dir` system property set in app/build.gradle.kts).
 *
 * The version-1 database is built from 1.json itself (its CREATE statements), filled with user data, then opened with
 * the production Room builder and the registered migrations. Room validates the migrated schema against its v2
 * expectation and throws on any difference, so a wrong or missing migration fails here.
 */
@RunWith(RobolectricTestRunner::class)
class Migration1To2Test {
    private val context get() = RuntimeEnvironment.getApplication()
    private val dbName = "migration-test.db"

    @After
    fun deleteDb() {
        context.deleteDatabase(dbName)
    }

    private fun schema(version: Int): JsonObject {
        val dir = File(checkNotNull(System.getProperty("room.schemas.dir")) { "room.schemas.dir is not set" })
        val file = File(dir, "com.bbro.kidtube.data.KidTubeDatabase/$version.json")
        check(file.isFile) { "committed schema missing: $file" }
        return KidTubeJson.instance.parseToJsonElement(file.readText()).jsonObject.getValue("database").jsonObject
    }

    private fun entityNames(version: Int): Set<String> =
        schema(version).getValue("entities").jsonArray.map { it.jsonObject.getValue("tableName").jsonPrimitive.content }.toSet()

    /** Creates the version-1 database exactly as Room version 1 would have, from the committed 1.json. */
    private fun createVersion1(): SupportSQLiteDatabase {
        val v1 = schema(1)
        val statements = buildList {
            for (entity in v1.getValue("entities").jsonArray.map { it.jsonObject }) {
                val table = entity.getValue("tableName").jsonPrimitive.content
                add(entity.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                (entity["indices"] as? JsonArray)?.forEach {
                    add(it.jsonObject.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                }
            }
            (v1["setupQueries"] as? JsonArray)?.forEach { add(it.jsonPrimitive.content) }
        }
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name(dbName).callback(
                object : SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: SupportSQLiteDatabase) = statements.forEach { db.execSQL(it) }
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                },
            ).build(),
        )
        return helper.writableDatabase
    }

    private fun seedUserData(db: SupportSQLiteDatabase) {
        db.execSQL("INSERT INTO sync_meta (id, etag, lastPullStartedAt, historyCycle, currentCycle) VALUES (1, '\"etag-1\"', 1000, 4, 5)")
        db.execSQL(
            "INSERT INTO local_event (id, childId, type, occurredAt, localDate, payload, state, ackedCycle, rejectReason, createdAt) " +
                "VALUES ('e-pending', 'c1', 'view_complete', 2000, '2026-11-05', '{}', 'pending', NULL, NULL, 2000)",
        )
        db.execSQL(
            "INSERT INTO local_event (id, childId, type, occurredAt, localDate, payload, state, ackedCycle, rejectReason, createdAt) " +
                "VALUES ('e-acked', 'c1', 'watch_progress', 3000, '2026-11-05', '{}', 'acked', 6, NULL, 3000)",
        )
        db.execSQL(
            "INSERT INTO outbox_video (id, rawUrl, state, serverVideoId, rejectReason, attempts, createdAt) " +
                "VALUES ('o1', 'https://youtu.be/dQw4w9WgXcQ', 'pending', NULL, NULL, 2, 4000)",
        )
        db.execSQL(
            "INSERT INTO outbox_placement (id, outboxVideoId, childId, required, viewTarget, dailyCap, anchorDate, onMiss, state, reason) " +
                "VALUES ('p1', 'o1', NULL, 1, 3, NULL, NULL, 'shift', 'pending', NULL)",
        )
    }

    @Test
    fun theCommittedSchemasDifferOnlyByTheReverifyTable() {
        assertEquals(setOf("reverify_attempt"), entityNames(2) - entityNames(1))
        assertEquals(emptySet<String>(), entityNames(1) - entityNames(2))
        assertEquals(1, schema(1).getValue("version").jsonPrimitive.content.toInt())
        assertEquals(2, schema(2).getValue("version").jsonPrimitive.content.toInt())
    }

    @Test
    fun roomMigratesAVersion1FileKeepingEveryRowAndAddingAnEmptyReverifyTable() = runBlocking {
        createVersion1().use { db ->
            seedUserData(db)
            assertEquals(1, db.version)
        }

        val room = Room.databaseBuilder(context, KidTubeDatabase::class.java, dbName)
            .addMigrations(*KidTubeDatabase.ALL_MIGRATIONS)
            .allowMainThreadQueries()
            .build()
        try {
            // Opening validates the migrated schema against the v2 expectation (it throws on any mismatch).
            assertEquals(listOf("e-pending"), room.localEventDao().pending(10).map { it.id })
            assertEquals(LocalEventState.PENDING, room.localEventDao().get("e-pending")!!.state)
            assertEquals(LocalEventState.ACKED, room.localEventDao().get("e-acked")!!.state)
            assertEquals(6L, room.localEventDao().get("e-acked")!!.ackedCycle)
            val video = room.outboxDao().pendingVideos().single()
            assertEquals("o1", video.id)
            assertEquals(OutboxVideoState.PENDING, video.state)
            assertEquals(2, video.attempts)
            assertEquals(1, room.outboxDao().placementsFor("o1").size)
            val meta = room.snapshotDao().meta()
            assertNotNull(meta)
            assertEquals("\"etag-1\"", meta!!.etag)
            assertEquals(4L, meta.historyCycle)
            assertEquals(5L, meta.currentCycle)
            assertTrue(room.reverifyDao().all().isEmpty())
            room.reverifyDao().upsert(com.bbro.kidtube.data.entity.ReverifyAttemptEntity("v1", java.time.Instant.ofEpochMilli(123)))
            assertEquals(listOf("v1"), room.reverifyDao().all().map { it.videoId })
        } finally {
            room.close()
        }
    }

    @Test
    fun anEmptyVersion1FileMigratesToo() = runBlocking {
        createVersion1().close()
        val room = Room.databaseBuilder(context, KidTubeDatabase::class.java, dbName)
            .addMigrations(*KidTubeDatabase.ALL_MIGRATIONS)
            .allowMainThreadQueries()
            .build()
        try {
            assertTrue(room.reverifyDao().all().isEmpty())
            assertTrue(room.localEventDao().pending(10).isEmpty())
        } finally {
            room.close()
        }
    }
}
