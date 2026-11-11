package com.bbro.kidtube.data.repo

import com.bbro.kidtube.core.ConnectivityChecker
import com.bbro.kidtube.data.remote.KidTubeJson
import com.bbro.kidtube.data.remote.PaceType
import com.bbro.kidtube.data.remote.PlanWrite
import com.bbro.kidtube.sync.SyncTestBase
import com.bbro.kidtube.testing.ContractFixtures
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

/** Adding a child creates its default plan; "Create default plan" does the same for a child that has none. */
class ChildSetupTest : SyncTestBase() {
    private var online = true
    private lateinit var setup: ChildSetupRepository

    private val newChildId = "00000000-0000-4000-8000-000000000003" // the id inside contract fixture 11

    @Before
    fun buildSetup() {
        setup = ChildSetupRepository(
            remote = stack.remote,
            snapshots = SnapshotRepository(stack.remote, db.snapshotDao(), session.store, clock),
            session = session.store,
            clock = clock,
            connectivity = ConnectivityChecker { online },
            syncTrigger = com.bbro.kidtube.sync.SyncTrigger.None,
        )
        route("POST /children") { json(ContractFixtures.load(11).status, ContractFixtures.load(11).bodyText) }
        route("PUT /children/*") { json(ContractFixtures.load(25).status, ContractFixtures.load(25).bodyText) }
        route("PATCH /children/*") { json(200, ContractFixtures.load(14).bodyText) }
    }

    private fun sentPlans(): List<PlanWrite> = requests("PUT", "/children/").map {
        KidTubeJson.instance.decodeFromString(PlanWrite.serializer(), it.body)
    }

    // ---- DEFAULT_PLAN ---------------------------------------------------------------------------------------------

    @Test
    fun defaultPlan_isThreeVideosADay_everyWeekday_noEndDate() {
        assertEquals(PaceType.VIDEOS_PER_DAY, DEFAULT_PLAN.paceType)
        assertEquals(3, DEFAULT_PLAN.paceValue)
        assertEquals(0b1111111, DEFAULT_PLAN.activeWeekdays) // Monday .. Sunday
        val write = DEFAULT_PLAN.toWrite(LocalDate.parse("2026-11-05"))
        assertEquals(LocalDate.parse("2026-11-05"), write.startDate)
        assertNull(write.endDate)
    }

    // ---- add child ------------------------------------------------------------------------------------------------

    @Test
    fun addChild_createsTheChild_thenItsDefaultPlan_startingOnTheChildsLocalDate() = runBlocking {
        loginAsParent()

        val result = setup.addChild("  Ali ", "coral")

        assertEquals(ChildAction.Success(AddedChild(newChildId, planCreated = true)), result)
        val order = seen.map { "${it.method} ${it.path}" }
        assertEquals(listOf("POST /children", "PUT /children/$newChildId/plan"), order.take(2))
        // 10:00 UTC on 2026-11-05 is the evening of the same day in Asia/Karachi (the default family zone)
        assertEquals(listOf(DEFAULT_PLAN.toWrite(LocalDate.parse("2026-11-05"))), sentPlans())
        val created = requests("POST", "/children").single().body
        assertTrue(created, created.contains("\"name\":\"Ali\"") && created.contains("\"avatar\":\"coral\""))
        assertTrue("the local copy is refreshed afterwards", requests("GET", "/sync/pull").isNotEmpty())
    }

    @Test
    fun addChild_usesTheFamilyTimezoneForTheStartDate() = runBlocking {
        loginAsParent()
        session.store.setFamilyTimezone("Pacific/Kiritimati") // UTC+14: already the next day

        setup.addChild("Ali", null)

        assertEquals(LocalDate.parse("2026-11-06"), sentPlans().single().startDate)
    }

    @Test
    fun addChild_ifOnlyThePlanFails_theChildIsStillAdded_andSaysSo() = runBlocking {
        loginAsParent()
        route("PUT /children/*") { json(500, """{"detail":"boom"}""") }

        val result = setup.addChild("Ali", null)

        assertEquals(ChildAction.Success(AddedChild(newChildId, planCreated = false)), result)
    }

    @Test
    fun addChild_validationError_sendsNoPlan() = runBlocking {
        loginAsParent()
        route("POST /children") { json(ContractFixtures.load(15).status, ContractFixtures.load(15).bodyText) }

        assertEquals(ChildAction.Failed(ChildFailure.INVALID_INPUT), setup.addChild("", null))
        assertTrue(requests("PUT", "/children/").isEmpty())
    }

    @Test
    fun addChild_offline_isNoConnection_andServerDownIsDifferent() = runBlocking {
        loginAsParent()
        server.close()

        online = false
        assertEquals(ChildAction.Failed(ChildFailure.NO_CONNECTION), setup.addChild("Ali", null))
        online = true
        assertEquals(ChildAction.Failed(ChildFailure.SERVER_UNREACHABLE), setup.addChild("Ali", null))
    }

    // ---- create default plan for an existing child -------------------------------------------------------------------

    @Test
    fun createDefaultPlan_putsTheDefaultPlanForThatChild() = runBlocking {
        loginAsParent()

        assertEquals(ChildAction.Success(Unit), setup.createDefaultPlan(CHILD_B))

        assertEquals(listOf("PUT /children/$CHILD_B/plan"), seen.map { "${it.method} ${it.path}" }.filter { it.startsWith("PUT") })
        assertEquals(listOf(DEFAULT_PLAN.toWrite(LocalDate.parse("2026-11-05"))), sentPlans())
    }

    @Test
    fun createDefaultPlan_failureIsReported_notThrown() = runBlocking {
        loginAsParent()
        route("PUT /children/*") { json(503, """{"detail":"down"}""") }
        assertEquals(ChildAction.Failed(ChildFailure.SERVER_UNREACHABLE), setup.createDefaultPlan(CHILD_B))
    }

    @Test
    fun createDefaultPlan_offline_isNoConnection() = runBlocking {
        loginAsParent()
        server.close()
        online = false
        assertEquals(ChildAction.Failed(ChildFailure.NO_CONNECTION), setup.createDefaultPlan(CHILD_B))
    }

    // ---- rename ---------------------------------------------------------------------------------------------------

    @Test
    fun rename_patchesOnlyTheName() = runBlocking {
        loginAsParent()

        assertEquals(ChildAction.Success(Unit), setup.renameChild(CHILD_A, " Ali B "))

        val patch = requests("PATCH", "/children/$CHILD_A").single().body
        assertEquals("""{"name":"Ali B"}""", patch)
    }
}
