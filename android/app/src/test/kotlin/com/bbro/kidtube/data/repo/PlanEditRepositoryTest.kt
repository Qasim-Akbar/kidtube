package com.bbro.kidtube.data.repo

import com.bbro.kidtube.core.ConnectivityChecker
import com.bbro.kidtube.data.dao.SnapshotData
import com.bbro.kidtube.data.remote.PaceType
import com.bbro.kidtube.data.remote.PlanItemStatus
import com.bbro.kidtube.data.remote.PlanWrite
import com.bbro.kidtube.data.toData
import com.bbro.kidtube.sync.SyncTestBase
import com.bbro.kidtube.sync.SyncTrigger
import com.bbro.kidtube.testing.ContractFixtures
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

/** Plan editing over MockWebServer with the backend contract fixtures: every call, every error mapping. */
class PlanEditRepositoryTest : SyncTestBase() {
    private var online = true
    private lateinit var edits: PlanEditRepository

    private val item18 = "00000000-0000-4000-8000-000000000018"
    private val item19 = "00000000-0000-4000-8000-000000000019"
    private val video = "00000000-0000-4000-8000-000000000014"

    @Before
    fun build() {
        edits = PlanEditRepository(
            remote = stack.remote,
            snapshots = SnapshotRepository(stack.remote, db.snapshotDao(), session.store, clock),
            plans = PlanRepository(db.planDao()),
            connectivity = ConnectivityChecker { online },
            syncTrigger = SyncTrigger.None,
        )
        route("PATCH /plan-items/*") { json(200, ContractFixtures.load(34).bodyText) }
        route("DELETE /plan-items/*") { json(204, "") }
        route("POST /children/$CHILD_A/plan/reorder") { json(200, ContractFixtures.load(35).bodyText) }
        route("PUT /children/$CHILD_A/plan") { json(200, ContractFixtures.load(26).bodyText) }
        route("PATCH /children/$CHILD_A") { json(200, ContractFixtures.load(14).bodyText) }
    }

    private suspend fun seed(change: (SnapshotData) -> SnapshotData = { it }) {
        loginAsParent()
        val dao = db.snapshotDao()
        dao.replaceSnapshot(change(serverSnapshot.toData()), serverEtag, dao.beginPull(clock.now()))
    }

    private fun failed(r: PlanAction<*>) = (r as PlanAction.Failed).failure

    // ---- items ------------------------------------------------------------------------------------------------------

    @Test
    fun addItem_sendsTheVideoAsOptionalWatchOnce_andRefreshesTheLocalCopy() = runBlocking {
        seed()
        route("POST /children/$CHILD_A/plan/items") { json(201, ContractFixtures.load(29).bodyText) }
        assertEquals(PlanAction.Success(Unit), edits.addItem(CHILD_A, video))
        val body = requests("POST", "/children/$CHILD_A/plan/items").single().body
        assertTrue(body, body.contains(video) && body.contains("\"view_target\":1") && body.contains("\"required\":false"))
        assertTrue(requests("GET", "/sync/pull").isNotEmpty())
    }

    @Test
    fun addItem_errorsMapToWhatTheParentCanUnderstand() = runBlocking {
        seed()
        route("POST /children/$CHILD_A/plan/items") { json(409, ContractFixtures.load(31).bodyText) }
        assertEquals(PlanFailureKind.ALREADY_IN_PLAN, failed(edits.addItem(CHILD_A, video)).kind)

        route("POST /children/$CHILD_A/plan/items") { json(422, ContractFixtures.load(32).bodyText) }
        failed(edits.addItem(CHILD_A, video)).let {
            assertEquals(PlanFailureKind.INVALID, it.kind)
            assertEquals("This video is unavailable", it.serverMessage) // the server's own words
        }

        route("POST /children/$CHILD_A/plan/items") { json(422, ContractFixtures.load(33).bodyText) }
        assertEquals("Input should be greater than or equal to 1", failed(edits.addItem(CHILD_A, video)).serverMessage)

        route("POST /children/$CHILD_A/plan/items") { json(404, """{"detail":"Video not found"}""") }
        assertEquals(PlanFailureKind.NOT_FOUND, failed(edits.addItem(CHILD_A, video)).kind)

        route("POST /children/$CHILD_A/plan/items") { json(429, """{"detail":"slow down"}""", "Retry-After" to "5") }
        assertEquals(PlanFailureKind.RATE_LIMITED, failed(edits.addItem(CHILD_A, video)).kind)
    }

    @Test
    fun editItem_sendsAllThreeFields_andNullClearsTargetAndCap() = runBlocking {
        seed()
        assertEquals(PlanAction.Success(Unit), edits.editItem(item18, required = true, viewTarget = null, dailyCap = null))
        val body = requests("PATCH", "/plan-items/$item18").single().body
        assertTrue(body, body.contains("\"required\":true") && body.contains("\"view_target\":null") && body.contains("\"daily_cap\":null"))

        edits.editItem(item18, required = false, viewTarget = 3, dailyCap = 2)
        val second = requests("PATCH", "/plan-items/$item18").last().body
        assertTrue(second, second.contains("\"view_target\":3") && second.contains("\"daily_cap\":2"))
    }

    @Test
    fun removeItem_deletes_andAnUnknownItemIsNotFound() = runBlocking {
        seed()
        assertEquals(PlanAction.Success(Unit), edits.removeItem(item19))
        assertEquals(1, requests("DELETE", "/plan-items/$item19").size)
        route("DELETE /plan-items/*") { json(404, """{"detail":"Plan item not found"}""") }
        assertEquals(PlanFailureKind.NOT_FOUND, failed(edits.removeItem(item19)).kind)
    }

    @Test
    fun move_swapsWithTheActiveNeighbour_andSendsEveryItemIncludingHiddenOnes() = runBlocking {
        seed { data -> data.copy(items = data.items.map { if (it.id == ITEM_2) it.copy(status = PlanItemStatus.COMPLETED) else it }) }
        assertEquals(PlanAction.Success(Unit), edits.move(CHILD_A, item18, up = true))
        val ids = Regex("\"([0-9a-f-]{36})\"").findAll(requests("POST", "/children/$CHILD_A/plan/reorder").single().body).map { it.groupValues[1] }.toList()
        // order was [ITEM_1, ITEM_2 (completed, hidden), 18, 19]: 18 swaps with ITEM_1, the hidden one stays where it was
        assertEquals(listOf(item18, ITEM_2, ITEM_1, item19), ids)
    }

    @Test
    fun move_atTheEdgeSendsNothing() = runBlocking {
        seed()
        assertEquals(PlanAction.Success(Unit), edits.move(CHILD_A, ITEM_1, up = true))
        assertTrue(requests("POST", "/children/$CHILD_A/plan/reorder").isEmpty())
    }

    @Test
    fun offlineAndServerDownAreDifferentFailures() = runBlocking {
        seed()
        server.close()
        online = false
        assertEquals(PlanFailureKind.NO_CONNECTION, failed(edits.removeItem(item19)).kind)
        online = true
        assertEquals(PlanFailureKind.SERVER_UNREACHABLE, failed(edits.removeItem(item19)).kind)
    }

    // ---- settings: PUT plan, then PATCH the child ----------------------------------------------------------------------

    private val write = PlanWrite(PaceType.VIDEOS_PER_DAY, 2, 31, maxExtraPerDay = 4, startDate = LocalDate.parse("2026-01-05"))

    @Test
    fun settings_putThenPatch_andAlwaysSendTheCatchUpValue() = runBlocking {
        seed()
        val outcome = edits.saveSettings(CHILD_A, write, planChanged = true, dailyMinutes = 25, limitChanged = true)
        assertTrue(outcome.allSaved)
        assertEquals(listOf("PUT /children/$CHILD_A/plan", "PATCH /children/$CHILD_A"), seen.map { "${it.method} ${it.path}" }.filter { it.startsWith("PUT") || it.startsWith("PATCH") })
        assertTrue(requests("PUT", "/children/").single().body.contains("\"max_extra_per_day\":4"))
        assertTrue(requests("PATCH", "/children/").single().body.contains("\"daily_app_minutes\":25"))
    }

    @Test
    fun settings_unchangedPartsAreNotSent() = runBlocking {
        seed()
        val outcome = edits.saveSettings(CHILD_A, write, planChanged = false, dailyMinutes = null, limitChanged = true)
        assertEquals(SavePart.Unchanged, outcome.plan)
        assertTrue(requests("PUT", "/children/").isEmpty())
        assertTrue(requests("PATCH", "/children/").single().body.contains("\"daily_app_minutes\":null")) // clears the limit
    }

    @Test
    fun settings_planSavedButLimitFailed_saysExactlyThat() = runBlocking {
        seed()
        route("PATCH /children/$CHILD_A") { json(500, """{"detail":"boom"}""") }
        val outcome = edits.saveSettings(CHILD_A, write, true, 25, true)
        assertEquals(SavePart.Saved, outcome.plan)
        assertEquals(PlanFailureKind.UNKNOWN, (outcome.limit as SavePart.Failed).failure.kind)
        assertTrue(!outcome.allSaved)
        assertTrue("the saved part is pulled into Room", requests("GET", "/sync/pull").isNotEmpty())
    }

    @Test
    fun settings_planFailed_limitIsNotTried_andNothingIsPulled() = runBlocking {
        seed()
        route("PUT /children/$CHILD_A/plan") { json(422, ContractFixtures.load(27).bodyText) }
        val outcome = edits.saveSettings(CHILD_A, write, true, 25, true)
        assertEquals("Input should be greater than or equal to 1", (outcome.plan as SavePart.Failed).failure.serverMessage)
        assertEquals(SavePart.NotTried, outcome.limit)
        assertTrue(requests("PATCH", "/children/").isEmpty())
        assertNull(requests("GET", "/sync/pull").firstOrNull())
    }

    // ---- pure order ---------------------------------------------------------------------------------------------------

    @Test
    fun planOrder_movesAmongActiveItemsOnly() = runBlocking {
        seed()
        val items = PlanRepository(db.planDao()).plan(CHILD_A)!!.items
        assertEquals(listOf(ITEM_2, ITEM_1, item18, item19), PlanOrder.moved(items, ITEM_1, up = false))
        assertNull(PlanOrder.moved(items, ITEM_1, up = true))
        assertNull(PlanOrder.moved(items, item19, up = false))
        assertNull(PlanOrder.moved(items, "nope", up = true))
    }
}
