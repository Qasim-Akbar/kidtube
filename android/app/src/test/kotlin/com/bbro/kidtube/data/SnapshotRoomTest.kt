package com.bbro.kidtube.data

import app.cash.turbine.test
import com.bbro.kidtube.data.dao.SnapshotData
import com.bbro.kidtube.data.entity.HistoryKind
import com.bbro.kidtube.data.remote.OnMiss
import com.bbro.kidtube.data.remote.Platform
import com.bbro.kidtube.data.remote.VideoStatus
import com.bbro.kidtube.data.repo.ChildrenRepository
import com.bbro.kidtube.data.repo.PlanRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class SnapshotRoomTest : RoomTestBase() {
    private val dao get() = db.snapshotDao()

    private suspend fun store(snapshot: com.bbro.kidtube.data.remote.SnapshotDto, etag: String) {
        dao.replaceSnapshot(snapshot.toData(), etag, dao.beginPull(Instant.EPOCH))
    }

    @Test
    fun replaceSnapshotStoresChildrenVideosPlansAndHistory() = runBlocking {
        store(loadSnapshot(), "\"v1\"")

        val children = db.childDao().getAll()
        assertEquals(listOf("Amna", "Bilal"), children.map { it.name })
        assertEquals(45, children[0].dailyAppMinutes)
        assertNull(children[1].avatar)

        val videos = db.videoDao().getAll()
        assertEquals(listOf(Platform.YOUTUBE, Platform.FACEBOOK), videos.map { it.platform })
        assertEquals(VideoStatus.PENDING, videos[1].status)
        assertEquals(Instant.parse("2026-11-02T09:15:00Z"), videos[1].addedAt)

        val plan = db.planDao().getForChild(CHILD_A)!!
        assertEquals(4, plan.plan.version)
        assertEquals(LocalDate.of(2026, 12, 31), plan.plan.endDate)
        assertEquals(setOf(ITEM_1, ITEM_2), plan.items.map { it.id }.toSet())
        val skipItem = plan.items.single { it.id == ITEM_2 }
        assertEquals(OnMiss.SKIP, skipItem.onMiss)
        assertEquals(LocalDate.of(2026, 11, 9), skipItem.anchorDate)
        assertEquals(0.75, skipItem.watchThreshold, 0.0)
        assertNull(db.planDao().getForChild(CHILD_B))

        val views = db.historyDao().views(CHILD_A)
        assertEquals(listOf(LocalDate.of(2026, 11, 3) to 1, LocalDate.of(2026, 11, 4) to 2), views.map { it.localDate to it.count })
        val seconds = db.historyDao().seconds(CHILD_A).groupBy { it.kind }
        assertEquals(listOf(640, 212), seconds.getValue(HistoryKind.WATCH).map { it.seconds })
        assertEquals(listOf(900), seconds.getValue(HistoryKind.APP).map { it.seconds })
        assertEquals(listOf(600), seconds.getValue(HistoryKind.EXTENSION).map { it.seconds })
        assertTrue(db.historyDao().views(CHILD_B).isEmpty())

        val meta = dao.meta()!!
        assertEquals("\"v1\"", meta.etag)
        assertEquals(1, meta.historyCycle)
        assertEquals(1, meta.currentCycle)
    }

    @Test
    fun replaceDropsRowsTheNewSnapshotNoLongerHas() = runBlocking {
        store(loadSnapshot(), "\"v1\"")

        // Next snapshot: Bilal gone, the facebook video gone, plan item 2 gone, and all history gone.
        val shrunk = loadSnapshot().let { s ->
            val amna = s.children[0]
            s.copy(
                children = listOf(
                    amna.copy(
                        plan = amna.plan!!.copy(items = amna.plan!!.items.filter { it.id == ITEM_1 }),
                        history = amna.history.copy(views = emptyList(), watchSeconds = emptyList()),
                    ),
                ),
                videos = s.videos.take(1),
            )
        }
        store(shrunk, "\"v2\"")

        assertEquals(listOf("Amna"), db.childDao().getAll().map { it.name })
        assertEquals(1, db.videoDao().getAll().size)
        assertEquals(listOf(ITEM_1), db.planDao().getForChild(CHILD_A)!!.items.map { it.id })
        assertTrue(db.historyDao().views(CHILD_A).isEmpty())
        assertEquals(setOf(HistoryKind.APP, HistoryKind.EXTENSION), db.historyDao().seconds(CHILD_A).map { it.kind }.toSet())
        assertEquals("\"v2\"", dao.meta()!!.etag)
        assertEquals(2, dao.meta()!!.historyCycle)
    }

    @Test
    fun aFailureHalfwayThroughReplaceRollsEverythingBack() = runBlocking {
        store(loadSnapshot(), "\"v1\"")
        val before = Triple(db.childDao().getAll(), db.planDao().getForChild(CHILD_A)!!, db.historyDao().views(CHILD_A))
        val metaBefore = dao.meta()!!

        // A snapshot that deletes everything and inserts some rows before hitting a duplicate primary key.
        val good = loadSnapshot().toData()
        val poisoned: SnapshotData = good.copy(items = good.items + good.items.first())
        val cycle = dao.beginPull(Instant.EPOCH)
        try {
            dao.replaceSnapshot(poisoned, "\"v2\"", cycle)
            fail("the duplicate plan item id must make the transaction fail")
        } catch (expected: Exception) {
            // rolled back
        }

        assertEquals(before.first, db.childDao().getAll())
        assertEquals(before.second.plan, db.planDao().getForChild(CHILD_A)!!.plan)
        assertEquals(before.second.items.toSet(), db.planDao().getForChild(CHILD_A)!!.items.toSet())
        assertEquals(before.third, db.historyDao().views(CHILD_A))
        val metaAfter = dao.meta()!!
        assertEquals("etag and history cycle must not move", metaBefore.etag to metaBefore.historyCycle, metaAfter.etag to metaAfter.historyCycle)
        assertEquals("only beginPull advanced currentCycle", metaBefore.currentCycle + 1, metaAfter.currentCycle)
    }

    @Test
    fun beginPullNumbersPullsAndMarkUnchangedOnlyMovesTheHistoryCycleForward() = runBlocking {
        assertNull(dao.meta())
        assertEquals(1, dao.beginPull(Instant.parse("2026-11-05T10:00:00Z")))
        assertEquals(2, dao.beginPull(Instant.parse("2026-11-05T10:05:00Z")))
        assertEquals(Instant.parse("2026-11-05T10:05:00Z"), dao.meta()!!.lastPullStartedAt)
        assertEquals(0, dao.meta()!!.historyCycle)

        dao.markUnchanged(2)
        assertEquals(2, dao.meta()!!.historyCycle)
        dao.markUnchanged(1) // a slow older pull finishing late must not move it back
        assertEquals(2, dao.meta()!!.historyCycle)
        assertEquals(2, dao.meta()!!.currentCycle)
    }

    @Test
    fun childrenFlowEmitsWhenASnapshotIsStored() = runBlocking {
        val repo = ChildrenRepository(db.childDao())
        repo.observeChildren().test {
            assertTrue(awaitItem().isEmpty())
            store(loadSnapshot(), "\"v1\"")
            assertEquals(listOf("Amna", "Bilal"), awaitItem().map { it.name })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun planRepositoryReturnsItemsInQueueOrder() = runBlocking {
        store(loadSnapshot(), "\"v1\"") // the fixture lists position 1 before position 0 on purpose
        val repo = PlanRepository(db.planDao())

        assertEquals(listOf(ITEM_1, ITEM_2), repo.plan(CHILD_A)!!.items.map { it.id })
        repo.observePlan(CHILD_A).test {
            assertEquals(listOf(0, 1), awaitItem()!!.items.map { it.position })
            cancelAndIgnoreRemainingEvents()
        }
        assertNull(repo.plan(CHILD_B))
    }
}
