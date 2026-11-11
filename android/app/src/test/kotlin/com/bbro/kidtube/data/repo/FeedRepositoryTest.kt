package com.bbro.kidtube.data.repo

import com.bbro.kidtube.data.RoomTestBase
import com.bbro.kidtube.data.dao.SnapshotData
import com.bbro.kidtube.data.entity.HistoryViewEntity
import com.bbro.kidtube.data.remote.EventType
import com.bbro.kidtube.data.remote.VideoStatus
import com.bbro.kidtube.data.toData
import com.bbro.kidtube.engine.FeedStatus
import com.bbro.kidtube.engine.PaceType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import kotlin.time.Duration.Companion.milliseconds

/**
 * FeedRepository on in-memory Room + FakeClock, based on backend contract fixture 73 (sync pull). On the fixture's day
 * (Mon 2026-01-05, Karachi) Ali (pace 2, max extra 2, weekdays Mon-Fri) has:
 *   ITEM_1 required, target 1, ONE server view (complete)      ITEM_2 optional, unlimited, video 12
 *   ITEM_18 optional, target 3, daily cap 1, video 11          ITEM_19 anchored 2026-01-12, on_miss drop, unlimited
 * so the feed is [ITEM_2] with 1 view today and 1 remaining.
 */
class FeedRepositoryTest : RoomTestBase() {
    private lateinit var feeds: FeedRepository
    private lateinit var events: EventRepository
    private val scope = CoroutineScope(Dispatchers.Default)

    private val monday = LocalDate.of(2026, 1, 5)

    @Before
    fun setUp() {
        clock.setWallClock(Instant.parse("2026-01-05T10:00:00Z")) // 15:00 in Karachi
        feeds = FeedRepository(db, clock, session.store)
        events = EventRepository(db.localEventDao(), clock, session.store)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    private suspend fun seed(data: SnapshotData = loadSnapshot().toData()): Long {
        val dao = db.snapshotDao()
        return dao.beginPull(clock.now()).also { dao.replaceSnapshot(data, "\"etag\"", it) }
    }

    private suspend fun ready(childId: String = CHILD_A): FeedState.Ready = feeds.current(childId) as FeedState.Ready

    private fun FeedState.Ready.offered() = entries.map { it.item.itemId }

    private suspend fun view(itemId: String, childId: String = CHILD_A) =
        events.record(EventType.VIEW_COMPLETE, childId, EventPayloads.viewComplete(itemId, 0.95))

    private fun atKarachi(date: String, time: String = "15:00:00") =
        clock.setWallClock(java.time.LocalDateTime.parse("${date}T$time").atZone(java.time.ZoneId.of("Asia/Karachi")).toInstant())

    private fun SnapshotData.withVideoStatus(videoId: String, status: VideoStatus) =
        copy(videos = videos.map { if (it.id == videoId) it.copy(status = status) else it })

    // ---- the base feed ------------------------------------------------------------------------------------------

    @Test
    fun feedFromTheSnapshotMatchesTheEngineRules() = runBlocking {
        seed()
        val s = ready()

        assertEquals(monday, s.today)
        assertEquals(PaceType.VIDEOS_PER_DAY, s.paceType)
        assertEquals(FeedStatus.OK, s.feed.status)
        assertEquals(listOf(ITEM_2), s.offered()) // ITEM_1 is complete; remaining quota is 1
        assertEquals(2, s.feed.quotaToday)
        assertEquals(1, s.feed.viewsToday)
        assertEquals(1, s.feed.remainingVideos)
        val entry = s.entries.single()
        assertEquals("Colours with Pip", entry.video.title)
        assertEquals(0.9, entry.watchThreshold, 0.0)
        // Projection: ITEM_18 (target 3) is the only finite item left; ITEM_2 and the anchored ITEM_19 are unlimited.
        assertEquals(3, s.projection.remainingFiniteViews)
        assertEquals(setOf(ITEM_2, ITEM_19), s.projection.excludedUnlimitedItemIds.toSet())
    }

    @Test
    fun childWithoutPlanAndUnknownChildAndEmptyPlan() = runBlocking {
        seed()
        assertEquals(FeedState.NoPlan(monday), feeds.current(CHILD_B)) // Pip has no plan: nothing is invented
        assertEquals(FeedState.NoChild, feeds.current("no-such-child"))
        assertEquals(FeedStatus.EMPTY, ready(NOOR).feed.status) // a plan with no items
    }

    @Test
    fun nothingBeforeASnapshotHasBeenPulled() = runBlocking {
        assertEquals(FeedState.NoChild, feeds.current(CHILD_A))
    }

    @Test
    fun weekendIsADayOffNotAMiss() = runBlocking {
        seed()
        atKarachi("2026-01-10") // Saturday; Ali is Mon-Fri
        val s = ready()
        assertEquals(FeedStatus.DAY_OFF, s.feed.status)
        assertTrue(s.entries.isEmpty())
    }

    // ---- local views change the feed at once ----------------------------------------------------------------------

    @Test
    fun aLocalViewUsesUpTheDailyQuotaImmediately() = runBlocking {
        seed()
        view(ITEM_2)
        val s = ready()
        assertEquals(2, s.feed.viewsToday)
        assertEquals(FeedStatus.DONE_TODAY, s.feed.status)
        assertTrue(s.entries.isEmpty())
    }

    @Test
    fun dailyCapAndViewTargetFollowLocalViews() = runBlocking {
        seed()
        atKarachi("2026-01-06") // Tuesday: one missed pace slot yesterday -> catch-up quota 3
        assertEquals(listOf(ITEM_2, ITEM_18), ready().offered())
        assertEquals(3, ready().feed.quotaToday)

        view(ITEM_18) // daily cap 1 is now reached today, although the target (3) is not
        val capped = ready()
        assertEquals(listOf(ITEM_2), capped.offered())
        assertEquals(2, capped.projection.remainingFiniteViews)

        atKarachi("2026-01-07") // a new day: the cap resets
        assertEquals(ITEM_18, ready().offered().last())
        view(ITEM_18)
        atKarachi("2026-01-08")
        view(ITEM_18) // third view: the target is reached
        atKarachi("2026-01-09")
        val done = ready()
        assertTrue(ITEM_18 !in done.offered())
        assertEquals(0, done.projection.remainingFiniteViews)
    }

    @Test
    fun aRejectedLocalEventIsNeverCounted() = runBlocking {
        seed()
        val e = view(ITEM_2)
        assertEquals(2, ready().feed.viewsToday)
        events.markRejected(e.id, "bad payload")
        assertEquals(1, ready().feed.viewsToday)
    }

    @Test
    fun aViewForAnotherChildsItemIsIgnored() = runBlocking {
        seed()
        view(ITEM_2, childId = SARA) // recorded for Sara, pointing at Ali's item: not in Sara's plan
        assertEquals(1, ready().feed.viewsToday)
    }

    // ---- sync: counted exactly once -------------------------------------------------------------------------------

    @Test
    fun aViewIsCountedOnceThroughAckAndPull() = runBlocking {
        val data = loadSnapshot().toData()
        seed(data)
        val e = view(ITEM_2)
        assertEquals(2, ready().feed.viewsToday) // pending overlay

        events.markAcked(listOf(e.id)) // pushed and acknowledged, the stored snapshot does not know it yet
        assertEquals(2, ready().feed.viewsToday)

        val dao = db.snapshotDao()
        val cycle = dao.beginPull(clock.now())
        val withView = data.copy(historyViews = data.historyViews + HistoryViewEntity(CHILD_A, ITEM_2, monday, 1))
        dao.replaceSnapshot(withView, "\"etag2\"", cycle) // the server now counts it; the overlay copy is pruned
        val after = ready()
        assertEquals(2, after.feed.viewsToday) // not 3
        assertEquals(FeedStatus.DONE_TODAY, after.feed.status)
        assertEquals(0, db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM local_event").use { it.moveToFirst(); it.getInt(0) })
    }

    @Test
    fun aPullThatStartedBeforeTheAckDoesNotDropTheView() = runBlocking {
        val data = loadSnapshot().toData()
        seed(data)
        val e = view(ITEM_2)
        val dao = db.snapshotDao()
        val staleCycle = dao.beginPull(clock.now()) // pull starts...
        events.markAcked(listOf(e.id)) // ...the push is acknowledged while it is in flight...
        dao.replaceSnapshot(data, "\"etag-stale\"", staleCycle) // ...and the (old) snapshot arrives without the event
        assertEquals(2, ready().feed.viewsToday) // the view must not vanish

        val next = dao.beginPull(clock.now())
        dao.replaceSnapshot(data.copy(historyViews = data.historyViews + HistoryViewEntity(CHILD_A, ITEM_2, monday, 1)), "\"etag3\"", next)
        assertEquals(2, ready().feed.viewsToday) // and is counted once when a snapshot that includes it arrives
    }

    // ---- time: watch seconds, app time, extensions -----------------------------------------------------------------

    @Test
    fun appTimeLimitWithLocalSessionsAndExtensions() = runBlocking {
        seed()
        // Sara: limit 10 min, 660 s of app time already on the server today.
        assertEquals(FeedStatus.TIME_UP, ready(SARA).feed.status)

        events.record(EventType.TIME_EXTENSION, SARA, EventPayloads.timeExtension(120.0)) // limit 720 s
        assertEquals(FeedStatus.DONE_TODAY, ready(SARA).feed.status) // no longer time's up (she also did her 2 views)

        val start = clock.now()
        events.record(EventType.APP_SESSION, SARA, EventPayloads.appSession(start, start.plusSeconds(90))) // 750 s
        assertEquals(FeedStatus.TIME_UP, ready(SARA).feed.status)
    }

    @Test
    fun overlayWatchSecondsRoundOncePerDay() = runBlocking {
        seed()
        events.record(EventType.WATCH_PROGRESS, CHILD_A, EventPayloads.watchProgress("v", 0.4))
        events.record(EventType.WATCH_PROGRESS, CHILD_A, EventPayloads.watchProgress("v", 0.4))
        val input = overlayInput()
        // 96 s on the server + (0.4 + 0.4 = 0.8 -> 1) locally. Two events do not round up one by one (that would be 2).
        assertEquals(97, input.seconds.single { it.localDate == monday }.seconds)
    }

    private suspend fun overlayInput(): EngineInput {
        val child = db.childDao().get(CHILD_A)!!
        return EngineInputs.build(
            child, db.planDao().getForChild(CHILD_A)!!, db.videoDao().getAll(),
            db.historyDao().views(CHILD_A), db.historyDao().seconds(CHILD_A), events.overlayEvents(CHILD_A), monday,
        )!!
    }

    // ---- day change ----------------------------------------------------------------------------------------------------

    @Test
    fun theLocalDayFollowsKarachiNotUtc() = runBlocking {
        seed()
        clock.setWallClock(Instant.parse("2026-01-05T18:59:59Z")) // 23:59:59 Mon in Karachi
        assertEquals(monday, ready().today)
        assertEquals(1, ready().feed.viewsToday)

        clock.setWallClock(Instant.parse("2026-01-05T19:00:00Z")) // 00:00 Tue
        val tuesday = ready()
        assertEquals(monday.plusDays(1), tuesday.today)
        assertEquals(0, tuesday.feed.viewsToday) // yesterday's view no longer counts toward today's quota
        assertEquals(3, tuesday.feed.quotaToday) // one missed slot yesterday -> pace 2 + 1 catch-up
    }

    // ---- video status --------------------------------------------------------------------------------------------------

    @Test
    fun unavailableVideoIsWithheldAndReportedNotCounted() = runBlocking {
        seed(loadSnapshot().toData().withVideoStatus(VIDEO_OF_ITEM_2, VideoStatus.UNAVAILABLE))
        val s = ready()
        assertTrue(ITEM_2 !in s.offered())
        assertEquals(listOf(ITEM_2), s.unavailableItemIds)
        assertEquals(listOf(ITEM_18), s.offered()) // the next eligible item takes the slot
    }

    @Test
    fun pendingVideoStaysInTheFeedWithItsStatusLikeTheBackend() = runBlocking {
        // The backend only withholds `unavailable` (SPEC section 11): a pending video (every Facebook one, or a YouTube one
        // whose oEmbed check could not finish) is still offered; the entry carries the status for the player.
        seed(loadSnapshot().toData().withVideoStatus(VIDEO_OF_ITEM_2, VideoStatus.PENDING))
        val s = ready()
        assertEquals(listOf(ITEM_2), s.offered())
        assertEquals(VideoStatus.PENDING, s.entries.single().video.status)
        assertTrue(s.unavailableItemIds.isEmpty())
    }

    @Test
    fun anItemWhoseVideoRowIsMissingIsSkippedLikeTheBackendsInnerJoin() = runBlocking {
        val data = loadSnapshot().toData()
        seed(data.copy(videos = data.videos.filter { it.id != VIDEO_OF_ITEM_2 }))
        val s = ready()
        assertTrue(ITEM_2 !in s.offered())
        assertTrue(s.unavailableItemIds.isEmpty())
    }

    // ---- the Flow ----------------------------------------------------------------------------------------------------------

    /** Collects every emission of the feed Flow (like a screen would) into a list. */
    private fun collect(childId: String = CHILD_A, tick: Int = 10): MutableStateFlow<List<FeedState>> {
        val seen = MutableStateFlow<List<FeedState>>(emptyList())
        scope.launch { feeds.observe(childId, dayCheckInterval = tick.milliseconds).collect { seen.value = seen.value + it } }
        return seen
    }

    private suspend fun MutableStateFlow<List<FeedState>>.await(what: String, p: (FeedState.Ready) -> Boolean) {
        try {
            withTimeout(5_000) { first { list -> list.any { it is FeedState.Ready && p(it) } } }
        } catch (e: Exception) {
            throw AssertionError("feed never reached: $what. Emitted: ${value.map { (it as? FeedState.Ready)?.let { r -> r.offered() to r.feed.viewsToday } ?: it }}", e)
        }
    }

    @Test
    fun flowEmitsForViewsAcksPullsAndNeverDoubleCounts() = runBlocking {
        val data = loadSnapshot().toData()
        seed(data)
        val seen = collect()
        seen.await("initial feed") { it.offered() == listOf(ITEM_2) }

        val e = view(ITEM_2)
        seen.await("done for today after a local view") { it.feed.status == FeedStatus.DONE_TODAY && it.feed.viewsToday == 2 }

        events.markAcked(listOf(e.id))
        val dao = db.snapshotDao()
        val cycle = dao.beginPull(clock.now())
        dao.replaceSnapshot(data.copy(historyViews = data.historyViews + HistoryViewEntity(CHILD_A, ITEM_2, monday, 1)), "\"e2\"", cycle)
        delay(400) // let the flow settle on the post-pull state

        val states = seen.value.filterIsInstance<FeedState.Ready>()
        assertTrue("a half-applied pull was visible: ${states.map { it.feed.viewsToday }}", states.none { it.feed.viewsToday > 2 })
        assertEquals(2, states.last().feed.viewsToday)
    }

    @Test
    fun flowRecomputesWhenTheLocalDayChanges() = runBlocking {
        seed()
        val seen = collect()
        seen.await("today is Monday") { it.today == monday }

        atKarachi("2026-01-06")
        seen.await("today is Tuesday") { it.today == monday.plusDays(1) && it.feed.viewsToday == 0 }
    }

    @Test
    fun flowFollowsPlanChangesFromAPull() = runBlocking {
        val data = loadSnapshot().toData()
        seed(data)
        val seen = collect()
        seen.await("initial") { it.offered() == listOf(ITEM_2) }

        val dao = db.snapshotDao()
        dao.replaceSnapshot(data.withVideoStatus(VIDEO_OF_ITEM_2, VideoStatus.UNAVAILABLE), "\"e2\"", dao.beginPull(clock.now()))
        seen.await("the video became unavailable") { it.unavailableItemIds == listOf(ITEM_2) && it.offered() == listOf(ITEM_18) }
    }

    @Test
    fun flowReportsNoPlanThenThePlanOnceItArrives() = runBlocking {
        val data = loadSnapshot().toData()
        val keptPlans = data.plans.filter { it.childId != CHILD_A }
        seed(data.copy(plans = keptPlans, items = data.items.filter { i -> keptPlans.any { it.id == i.planId } }))
        val seen = collect()
        withTimeout(5_000) { seen.first { l -> l.any { it is FeedState.NoPlan } } }

        val dao = db.snapshotDao()
        dao.replaceSnapshot(data, "\"e2\"", dao.beginPull(clock.now()))
        seen.await("the plan arrived") { it.offered() == listOf(ITEM_2) }
        Unit
    }

    companion object {
        const val SARA = "00000000-0000-4000-8000-000000000004"
        const val NOOR = "00000000-0000-4000-8000-000000000006"
        const val ITEM_18 = "00000000-0000-4000-8000-000000000018"
        const val ITEM_19 = "00000000-0000-4000-8000-000000000019"
        const val VIDEO_OF_ITEM_2 = "00000000-0000-4000-8000-000000000012"
    }
}
