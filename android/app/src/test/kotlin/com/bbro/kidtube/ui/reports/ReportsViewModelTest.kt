package com.bbro.kidtube.ui.reports

import com.bbro.kidtube.core.ConnectivityChecker
import com.bbro.kidtube.data.remote.ApiWiring
import com.bbro.kidtube.data.remote.BaseUrlSource
import com.bbro.kidtube.data.remote.KidTubeJson
import com.bbro.kidtube.data.repo.ChildrenRepository
import com.bbro.kidtube.data.repo.ReportFailureKind
import com.bbro.kidtube.data.repo.ReportKey
import com.bbro.kidtube.data.repo.ReportRange
import com.bbro.kidtube.data.repo.ReportRepository
import com.bbro.kidtube.sync.SyncTestBase
import com.bbro.kidtube.testing.ContractFixtures
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.CopyOnWriteArrayList

/** The Reports view model on the production stack: real Retrofit/OkHttp against MockWebServer serving the backend contract fixtures. */
@OptIn(ExperimentalCoroutinesApi::class)
class ReportsViewModelTest : SyncTestBase() {
    private val asked = CopyOnWriteArrayList<String>()

    @Volatile private var online = true

    /** Where the app currently "connects": the fake server, or a closed port (a dead network). */
    @Volatile private var address = ""

    @Volatile private var respond: (RecordedRequest) -> MockResponse = { byRange(it) }

    private lateinit var repo: ReportRepository

    private fun byRange(request: RecordedRequest): MockResponse = when (request.url.queryParameter("range")) {
        "day" -> json(200, ContractFixtures.load(76).bodyText)
        "week" -> json(200, ContractFixtures.load(77).bodyText)
        "month" -> json(200, ContractFixtures.load(78).bodyText)
        else -> json(422, ContractFixtures.load(80).bodyText)
    }

    @Before
    fun build() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        clock.setWallClock(Instant.parse("2026-01-05T10:00:00Z")) // 15:00 Monday in Karachi
        address = server.url("/").toString()
        val stack = ApiWiring.create(address, KidTubeJson.instance, session.store, clock, BaseUrlSource { address })
        repo = ReportRepository(stack.remote, ConnectivityChecker { online })
        route("GET /reports/children/*") { request ->
            asked += request.url.encodedPath + "?" + request.url.query
            respond(request)
        }
    }

    @After
    fun reset() {
        Dispatchers.resetMain()
    }

    private suspend fun start(): ReportsViewModel {
        loginAsParent()
        seedSnapshot()
        return ReportsViewModel(ChildrenRepository(db.childDao()), repo, session.store, clock)
    }

    private suspend fun ReportsViewModel.await(what: String = "state", cond: (ReportsUiState) -> Boolean): ReportsUiState =
        withTimeout(10_000) { state.first { cond(it) } }

    private suspend fun ReportsViewModel.settled(): ReportsUiState = await("settled") { it.loaded && it.anchor != null && !it.refreshing }

    private fun d(text: String) = LocalDate.parse(text)

    private val path = "/reports/children/$CHILD_A"

    // ---- opening -------------------------------------------------------------------------------------------------------------

    @Test
    fun opensOnTheCurrentWeekOfTheFirstChild_withTheDayFromTheChildsTimezone() = runBlocking {
        val vm = start()
        val s = vm.settled()
        assertEquals(CHILD_A, s.selectedChildId)
        assertEquals(ReportRange.WEEK, s.range)
        assertEquals(d("2026-01-05"), s.anchor)
        assertEquals(d("2026-01-05"), s.today)
        assertEquals(listOf("$path?range=week&date=2026-01-05"), asked.toList())
        assertTrue("parent token sent", seen.first { it.path.startsWith("/reports") }.auth!!.startsWith("Bearer "))
        val report = s.report!!
        assertEquals("week", report.range)
        assertEquals(7, report.days.size)
        assertEquals(1, report.totals.viewsCompleted)
        assertEquals(96, report.totals.watchSeconds)
        assertEquals(1200, report.totals.appSeconds)
        assertEquals(1, report.playbackErrors.single().count)
        assertNull(s.failure)
        assertFalse(s.canGoNext)
    }

    @Test
    fun todayIsTheFamilyLocalDate_notUtc() = runBlocking {
        clock.setWallClock(Instant.parse("2026-03-31T20:30:00Z")) // 01:30 on 1 April in Karachi (UTC+5)
        val vm = start()
        val s = vm.settled()
        assertEquals(d("2026-04-01"), s.today)
        assertTrue(asked.single().endsWith("date=2026-04-01"))
    }

    @Test
    fun dayAndMonthRanges() = runBlocking {
        val vm = start()
        vm.settled()
        vm.selectRange(ReportRange.DAY)
        val day = vm.await { it.report?.range == "day" && !it.refreshing }
        assertEquals("$path?range=day&date=2026-01-05", asked.last())
        assertEquals(210, day.report!!.totals.watchSeconds)
        assertEquals(2, day.report!!.topVideos.size)
        vm.selectRange(ReportRange.MONTH)
        val month = vm.await { it.report?.range == "month" && !it.refreshing }
        assertEquals("$path?range=month&date=2026-01-05", asked.last())
        assertEquals(31, month.report!!.days.size)
        assertEquals(d("2026-01-05"), month.anchor) // switching the range keeps the day
    }

    // ---- navigation ----------------------------------------------------------------------------------------------------------

    @Test
    fun previousAndNextWalkThroughWeeks_andNeverIntoTheFuture() = runBlocking {
        val vm = start()
        vm.settled()
        assertFalse(vm.state.value.canGoNext)
        vm.previous()
        val back = vm.await { it.anchor == d("2025-12-29") && !it.refreshing }
        assertEquals("$path?range=week&date=2025-12-29", asked.last())
        assertTrue(back.canGoNext)
        vm.next()
        val forward = vm.await { it.anchor == d("2026-01-05") && !it.refreshing }
        assertFalse(forward.canGoNext)
        val before = asked.size
        vm.next() // not allowed: no request
        delay(200)
        assertEquals(before, asked.size)
        assertEquals(d("2026-01-05"), vm.state.value.anchor)
    }

    @Test
    fun monthNavigationAcrossAShorterMonthAndTheYear() = runBlocking {
        clock.setWallClock(Instant.parse("2026-03-31T10:00:00Z"))
        val vm = start()
        vm.settled()
        vm.selectRange(ReportRange.MONTH)
        vm.await { it.range == ReportRange.MONTH && !it.refreshing && it.report?.range == "month" }
        vm.previous()
        vm.await { it.anchor == d("2026-02-28") && !it.refreshing }
        assertEquals("$path?range=month&date=2026-02-28", asked.last())
        vm.previous()
        vm.await { it.anchor == d("2026-01-28") && !it.refreshing }
        assertEquals("$path?range=month&date=2026-01-28", asked.last())
        vm.previous()
        vm.previous()
        val november = vm.await { it.anchor == d("2025-11-28") && !it.refreshing }
        assertTrue(november.canGoNext)
    }

    @Test
    fun theDayAfterMidnightMovesTodayOnRefresh() = runBlocking {
        val vm = start()
        vm.settled()
        vm.selectRange(ReportRange.DAY)
        vm.await { it.range == ReportRange.DAY && it.report?.range == "day" && !it.refreshing }
        clock.advance(java.time.Duration.ofDays(1))
        vm.refresh()
        val s = vm.await { it.today == d("2026-01-06") && !it.refreshing }
        assertEquals(d("2026-01-05"), s.anchor) // the period the parent was looking at stays
        assertTrue("the new day can now be reached", s.canGoNext)
    }

    @Test
    fun selectingAnotherChildAsksForThatChild_andKeepsRangeAndDate() = runBlocking {
        val vm = start()
        vm.settled()
        vm.selectChild(CHILD_B)
        vm.await { it.selectedChildId == CHILD_B && !it.refreshing && asked.size == 2 }
        assertEquals("/reports/children/$CHILD_B?range=week&date=2026-01-05", asked.last())
        assertEquals(ReportRange.WEEK, vm.state.value.range)
    }

    // ---- states --------------------------------------------------------------------------------------------------------------

    @Test
    fun anEmptyPeriodIsReportedAsEmpty() = runBlocking {
        respond = { json(200, ContractFixtures.load(79).bodyText) }
        val vm = start()
        val s = vm.settled()
        assertTrue(s.report!!.isEmptyPeriod())
        assertNull("no plan: nothing to show about progress", s.report!!.plannedVsWatched)
        assertNull(s.report!!.planProgress)
    }

    @Test
    fun aServerErrorShowsAFailureAndNoReport() = runBlocking {
        respond = { json(422, ContractFixtures.load(80).bodyText) }
        val vm = start()
        val s = vm.settled()
        assertNull(s.report)
        assertEquals(ReportFailureKind.UNKNOWN, s.failure)
    }

    @Test
    fun tooManyRequestsIsRateLimited() = runBlocking {
        respond = { MockResponse.Builder().code(429).addHeader("Retry-After", "30").build() }
        val vm = start()
        assertEquals(ReportFailureKind.RATE_LIMITED, vm.settled().failure)
    }

    @Test
    fun noNetworkMeansNeedsInternet_andRetryRecovers() = runBlocking {
        online = false
        address = "http://127.0.0.1:1/" // nothing listens here
        val vm = start()
        val s = vm.settled()
        assertNull(s.report)
        assertEquals(ReportFailureKind.NO_CONNECTION, s.failure)
        assertTrue(asked.isEmpty())

        online = true
        address = server.url("/").toString()
        vm.refresh()
        val ok = vm.await { it.report != null && !it.refreshing }
        assertNull(ok.failure)
        assertEquals(1, ok.report!!.totals.viewsCompleted)
    }

    @Test
    fun aReachableNetworkButDeadServerIsNotCalledOffline() = runBlocking {
        address = "http://127.0.0.1:1/"
        val vm = start()
        assertEquals(ReportFailureKind.SERVER_UNREACHABLE, vm.settled().failure)
    }

    @Test
    fun theLastReportStaysOnScreenWhileRefreshing_andAfterAFailedRefresh() = runBlocking {
        val vm = start()
        val first = vm.settled().report!!
        assertNotNull(repo.cached(ReportKey(CHILD_A, ReportRange.WEEK, d("2026-01-05"))))

        online = false
        address = "http://127.0.0.1:1/"
        vm.refresh()
        val failed = vm.await { it.failure != null && !it.refreshing }
        assertEquals("the old report is still there", first, failed.report)
        assertEquals(ReportFailureKind.NO_CONNECTION, failed.failure)

        // going to another period that was never loaded has nothing to show: the full state
        vm.previous()
        val other = vm.await { it.anchor == d("2025-12-29") && !it.refreshing }
        assertNull(other.report)
        assertEquals(ReportFailureKind.NO_CONNECTION, other.failure)

        // back to the period that was loaded: shown from memory straight away, even though the network is still down
        online = true
        address = server.url("/").toString()
        vm.next()
        val back = vm.await { it.anchor == d("2026-01-05") }
        assertEquals(first, back.report)
    }

    @Test
    fun aChildlessFamilyHasNothingToLoad() = runBlocking {
        loginAsParent() // no snapshot: no children
        val vm = ReportsViewModel(ChildrenRepository(db.childDao()), repo, session.store, clock)
        val s = vm.await { it.loaded }
        assertNull(s.selectedChildId)
        assertNull(s.report)
        assertTrue(asked.isEmpty())
    }
}
