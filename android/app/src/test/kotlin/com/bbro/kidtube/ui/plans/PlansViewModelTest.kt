package com.bbro.kidtube.ui.plans

import com.bbro.kidtube.core.ConnectivityChecker
import com.bbro.kidtube.data.remote.EventType
import com.bbro.kidtube.data.remote.PaceType
import com.bbro.kidtube.data.repo.ChildSetupRepository
import com.bbro.kidtube.data.repo.ChildrenRepository
import com.bbro.kidtube.data.repo.FeedRepository
import com.bbro.kidtube.data.repo.FeedState
import com.bbro.kidtube.data.repo.PlanEditRepository
import com.bbro.kidtube.data.repo.PlanFailureKind
import com.bbro.kidtube.data.repo.PlanRepository
import com.bbro.kidtube.data.repo.SavePart
import com.bbro.kidtube.data.repo.SnapshotRepository
import com.bbro.kidtube.data.repo.VideoRepository
import com.bbro.kidtube.engine.DayStatus
import com.bbro.kidtube.engine.FeedStatus
import com.bbro.kidtube.sync.SyncTestBase
import com.bbro.kidtube.sync.SyncTrigger
import com.bbro.kidtube.testing.ContractFixtures
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import com.bbro.kidtube.data.remote.KidTubeJson

/** The Plans tab view model on the production stack (Room, MockWebServer with the contract fixtures, FakeClock). */
@OptIn(ExperimentalCoroutinesApi::class)
class PlansViewModelTest : SyncTestBase() {
    private lateinit var vm: PlansViewModel
    private lateinit var feeds: FeedRepository
    private val item19 = "00000000-0000-4000-8000-000000000019"
    private val sara = "00000000-0000-4000-8000-000000000004"
    private val monday = LocalDate.parse("2026-01-05")
    private val scope = kotlinx.coroutines.CoroutineScope(Dispatchers.Unconfined)

    @Before
    fun build() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        clock.setWallClock(Instant.parse("2026-01-05T10:00:00Z")) // 15:00 Monday in Karachi: the day of the fixture
        feeds = FeedRepository(db, clock, session.store)
        val snapshots = SnapshotRepository(stack.remote, db.snapshotDao(), session.store, clock)
        val online = ConnectivityChecker { true }
        vm = PlansViewModel(
            ChildrenRepository(db.childDao()), PlanRepository(db.planDao()), VideoRepository(db.videoDao()), feeds,
            PlanEditRepository(stack.remote, snapshots, PlanRepository(db.planDao()), online, SyncTrigger.None),
            ChildSetupRepository(stack.remote, snapshots, session.store, clock, online, SyncTrigger.None),
            events,
        )
        route("PATCH /plan-items/*") { json(200, ContractFixtures.load(34).bodyText) }
        route("DELETE /plan-items/*") { json(204, "") }
        route("POST /children/$CHILD_A/plan/reorder") { json(200, ContractFixtures.load(35).bodyText) }
        route("PUT /children/$CHILD_A/plan") { json(200, ContractFixtures.load(26).bodyText) }
        route("PUT /children/$CHILD_B/plan") { json(201, ContractFixtures.load(25).bodyText) }
        route("PATCH /children/$CHILD_A") { json(200, ContractFixtures.load(14).bodyText) }
    }

    @After
    fun reset() {
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        Dispatchers.resetMain()
    }

    private suspend fun ready(): PlanScreenModel {
        loginAsParent()
        seedSnapshot()
        scope.launch { vm.state.collect { } } // keeps the state flow subscribed, like the screen does
        return withTimeout(10_000) { vm.state.first { it.model?.days?.isNotEmpty() == true } }.model!!
    }

    private suspend fun until(what: String, cond: () -> Boolean) {
        withTimeout(10_000) { while (!cond()) delay(10) }
    }

    // ---- what the screen shows ----------------------------------------------------------------------------------------

    @Test
    fun theFirstChildIsSelected_itemsAreInPlanOrder_andTheProjectionIsSevenDays() = runBlocking {
        val m = ready()
        assertEquals(CHILD_A, vm.state.value.selectedChildId)
        assertEquals(listOf(ITEM_1, ITEM_2, "00000000-0000-4000-8000-000000000018", item19), m.items.map { it.id })
        assertTrue(m.items.first().required)
        assertFalse(m.items.first().canMoveUp)
        assertTrue(m.items.last().let { !it.canMoveDown && it.canMoveUp })
        assertEquals(PaceType.VIDEOS_PER_DAY, m.plan!!.paceType)
        assertEquals(7, m.days.size)
        assertEquals(monday, m.days.first().date)
        assertEquals(listOf("The alphabet song"), m.days.first().entries.map { it.title }) // fixture 67: day 0
        assertEquals(DayStatus.DAY_OFF, m.days.first { it.date == LocalDate.parse("2026-01-10") }.status)
        assertTrue("unlimited items are named, not scheduled", m.unlimitedTitles.contains("Colours with Pip"))
    }

    @Test
    fun anchoredItemsShowTheirMissRule_othersDoNot() = runBlocking {
        val m = ready()
        val anchored = m.items.single { it.id == item19 }
        assertEquals(LocalDate.parse("2026-01-12"), anchored.anchorDate)
        assertEquals(com.bbro.kidtube.data.remote.OnMiss.DROP, anchored.onMiss)
        assertTrue(m.items.filter { it.id != item19 }.all { it.onMiss == null })
    }

    @Test
    fun selectingAnotherChildSwitchesThePlan_andAChildWithoutOneOffersTheDefault() = runBlocking {
        ready()
        vm.selectChild(CHILD_B)
        withTimeout(10_000) { vm.state.first { it.selectedChildId == CHILD_B && it.model != null } }
        assertEquals(null, vm.state.value.model!!.plan)
        vm.createDefaultPlan(CHILD_B)
        until("default plan sent") { requests("PUT", "/children/$CHILD_B/plan").isNotEmpty() }
        assertTrue(requests("PUT", "/children/$CHILD_B/plan").single().body.contains("\"pace_value\":3"))
    }

    @Test
    fun theVideoPickerOffersOnlyLibraryVideosNotInThePlan_andNotUnavailableOnes() = runBlocking {
        val m = ready()
        val inPlan = m.items.size
        val allVideos = db.videoDao().getAll()
        assertTrue(m.pickable.none { p -> db.planDao().getForChild(CHILD_A)!!.items.any { it.videoId == p.id } })
        assertTrue(m.pickable.none { p -> allVideos.first { it.id == p.id }.status == com.bbro.kidtube.data.remote.VideoStatus.UNAVAILABLE })
        assertTrue(m.pickable.size <= allVideos.size - inPlan)
    }

    // ---- actions ---------------------------------------------------------------------------------------------------------

    @Test
    fun moveRemoveAndEditCallTheEndpoints_andRemoveClosesItsDialog() = runBlocking {
        ready()
        vm.move(CHILD_A, ITEM_2, up = true)
        until("reorder") { requests("POST", "/children/$CHILD_A/plan/reorder").isNotEmpty() }
        until("idle") { !vm.state.value.busy }
        var closed = false
        vm.remove(item19) { closed = true }
        until("delete") { closed }
        until("idle again") { !vm.state.value.busy }
        vm.editItem(ITEM_2, required = false, viewTarget = 2, dailyCap = null)
        until("patch") { requests("PATCH", "/plan-items/$ITEM_2").isNotEmpty() }
        until("idle at the end") { !vm.state.value.busy }
        assertFalse(vm.state.value.busy)
        assertEquals(null, vm.error.value)
    }

    @Test
    fun aFailedActionSetsTheErrorAndFreesTheButtons() = runBlocking {
        ready()
        route("DELETE /plan-items/*") { json(404, """{"detail":"Plan item not found"}""") }
        var closed = false
        vm.remove(item19) { closed = true }
        until("error shown") { vm.error.value != null }
        assertEquals(PlanFailureKind.NOT_FOUND, vm.error.value!!.kind)
        assertFalse("the dialog stays open on failure", closed)
        until("not busy") { !vm.state.value.busy }
        vm.clearError()
        assertEquals(null, vm.error.value)
    }

    @Test
    fun duplicateVideoMapsTo409() = runBlocking {
        ready()
        route("POST /children/$CHILD_A/plan/items") { json(409, ContractFixtures.load(31).bodyText) }
        vm.addVideo(CHILD_A, "00000000-0000-4000-8000-000000000008")
        until("error") { vm.error.value != null }
        assertEquals(PlanFailureKind.ALREADY_IN_PLAN, vm.error.value!!.kind)
    }

    private fun input(pace: Int = 2, limit: Int? = null) = SettingsInput(
        PaceType.VIDEOS_PER_DAY, pace, 31, maxExtraPerDay = 2, startDate = monday, endDate = null, dailyAppMinutes = limit,
    )

    @Test
    fun settingsPartialFailure_keepsTheFormOpen_andARetrySendsOnlyTheMissingPart() = runBlocking {
        ready()
        // Ali's stored plan is 2/day, weekdays 31 (fixture 73): only the limit differs.
        route("PATCH /children/$CHILD_A") { json(500, """{"detail":"boom"}""") }
        var saved = false
        vm.saveSettings(CHILD_A, input(limit = 20)) { saved = true }
        until("message") { vm.settingsMessage.value != null }
        val outcome = vm.settingsMessage.value!!.outcome
        assertEquals(SavePart.Unchanged, outcome.plan)
        assertTrue(outcome.limit is SavePart.Failed)
        assertFalse(saved)
        assertTrue(requests("PUT", "/children/").isEmpty()) // the plan part matched what is stored: not sent

        route("PATCH /children/$CHILD_A") { json(200, ContractFixtures.load(14).bodyText) }
        vm.saveSettings(CHILD_A, input(limit = 20)) { saved = true }
        until("saved") { saved }
        assertEquals(null, vm.settingsMessage.value)
    }

    @Test
    fun settingsPlanChanged_putsWithTheCatchUpValue() = runBlocking {
        ready()
        var saved = false
        vm.saveSettings(CHILD_A, input(pace = 5)) { saved = true }
        until("saved") { saved }
        val body = requests("PUT", "/children/$CHILD_A/plan").single().body
        assertTrue(body, body.contains("\"pace_value\":5") && body.contains("\"max_extra_per_day\":2"))
        assertTrue(requests("PATCH", "/children/").isEmpty()) // the limit did not change
    }

    // ---- extra time ---------------------------------------------------------------------------------------------------------

    @Test
    fun extraTime_recordsAParentEvent_appliesAtOnceThroughTheOverlay_andTellsTheScreen() = runBlocking {
        ready()
        // Sara: 10 min limit, 660 s used today (fixture 73) -> time is up.
        val before = feeds.current(sara) as FeedState.Ready
        assertEquals(FeedStatus.TIME_UP, before.feed.status)
        assertEquals(600, before.appTimeLimitSeconds)

        vm.giveExtraTime(sara, 15)

        val event = db.localEventDao().pending(100).single { it.type == EventType.TIME_EXTENSION }
        assertEquals(sara, event.childId)
        assertEquals(monday, event.localDate) // the child's local day
        assertEquals(900.0, KidTubeJson.instance.parseToJsonElement(event.payload).jsonObject.getValue("seconds").jsonPrimitive.content.toDouble(), 0.0)
        val after = feeds.current(sara) as FeedState.Ready
        assertEquals(1500, after.appTimeLimitSeconds) // 10 min + 15 min
        assertTrue(after.feed.status != FeedStatus.TIME_UP)
        assertEquals(PlansEvent.ExtraTimeAdded(15), withTimeout(5_000) { vm.uiEvents.first() })
        assertNotNull(event.id)
    }
}
