package com.bbro.kidtube.sync

import com.bbro.kidtube.data.entity.PlacementState
import com.bbro.kidtube.data.remote.KidTubeJson
import com.bbro.kidtube.data.remote.PlanItemCreate
import com.bbro.kidtube.data.remote.PushBody
import com.bbro.kidtube.data.remote.PushResult
import com.bbro.kidtube.data.remote.OnMiss
import com.bbro.kidtube.data.remote.PushVideoResult
import com.bbro.kidtube.data.remote.PushVideoStatus
import com.bbro.kidtube.data.remote.VideoStatus
import com.bbro.kidtube.data.repo.PlacementSpec
import com.bbro.kidtube.testing.ContractFixtures
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** Step b: placing synced videos into plans. */
class SyncPlacementTest : SyncTestBase() {
    private val noPlan get() = ContractFixtures.load(24)
    private val alreadyInPlan get() = ContractFixtures.load(31)
    private val unavailable get() = ContractFixtures.load(32)

    private val ALI = CHILD_A
    private val SARA = "00000000-0000-4000-8000-000000000004"
    private val OMAR = "00000000-0000-4000-8000-000000000005"
    private val NOOR = "00000000-0000-4000-8000-000000000006"
    private val PIP = CHILD_B

    private suspend fun placements(): List<Triple<String?, PlacementState, String?>> =
        db.openHelper.readableDatabase.query("SELECT childId, state, reason FROM outbox_placement ORDER BY id").use { c ->
            buildList { while (c.moveToNext()) add(Triple(c.getString(0), PlacementState.entries.first { it.wire == c.getString(1) }, c.getString(2))) }
        }

    private fun planItemPosts() = requests("POST", "/children/").filter { it.path.endsWith("/plan/items") }

    private suspend fun enqueue(vararg specs: PlacementSpec) =
        outboxRepo.enqueue("https://www.youtube.com/watch?v=vid00000001", specs.toList())

    @Test
    fun aSyncedVideoIsPlacedWithTheOptionsTheParentChose() = runBlocking {
        loginAsParent()
        seedSnapshot()
        enqueue(PlacementSpec(ALI, required = true, viewTarget = 3, dailyCap = 1, anchorDate = LocalDate.of(2026, 1, 12), onMiss = OnMiss.SKIP))

        assertEquals(SyncOutcome.Success, engine.runCycle())

        val post = planItemPosts().single()
        assertEquals("/children/$ALI/plan/items", post.path)
        val sent = KidTubeJson.instance.decodeFromString(PlanItemCreate.serializer(), post.body)
        assertEquals("srv-video-1", sent.videoId) // the SERVER video id from the push result
        assertTrue(sent.required)
        assertEquals(3, sent.viewTarget)
        assertEquals(1, sent.dailyCap)
        assertEquals(LocalDate.of(2026, 1, 12), sent.anchorDate)
        assertEquals(OnMiss.SKIP, sent.onMiss)
        assertEquals(listOf(Triple<String?, PlacementState, String?>(ALI, PlacementState.DONE, null)), placements())
        assertEquals(listOf("POST /sync/push", "POST /children/$ALI/plan/items", "GET /sync/pull"), seen.map { "${it.method} ${it.path}" })
    }

    @Test
    fun a409AlreadyInThePlanCountsAsDone() = runBlocking {
        loginAsParent()
        seedSnapshot()
        enqueue(PlacementSpec(ALI))
        route("POST /children/*") { json(alreadyInPlan.status, alreadyInPlan.bodyText) }

        assertEquals(SyncOutcome.Success, engine.runCycle())

        assertEquals(PlacementState.DONE, placements().single().second)
    }

    @Test
    fun aPlacementForEveryChildBecomesOneRowPerChildAndPipWaitsForAPlan() = runBlocking {
        loginAsParent()
        seedSnapshot() // five children; Pip has no plan, Noor has an empty plan
        enqueue(PlacementSpec(childId = null, required = true))

        assertEquals(SyncOutcome.Success, engine.runCycle())

        assertEquals(setOf(ALI, SARA, OMAR, NOOR).map { "/children/$it/plan/items" }.toSet(), planItemPosts().map { it.path }.toSet())
        val rows = placements()
        assertEquals(6, rows.size) // the original (finished) + five copies
        assertEquals(4, rows.count { it.second == PlacementState.DONE && it.first != null })
        assertEquals(Triple<String?, PlacementState, String?>(PIP, PlacementState.PENDING, PlacementErrors.REASON_NO_PLAN), rows.single { it.first == PIP })
        assertTrue("no plan was invented for Pip", requests("PUT", "/children/").isEmpty())
        // every copy keeps the parent's options
        planItemPosts().forEach { assertTrue(KidTubeJson.instance.decodeFromString(PlanItemCreate.serializer(), it.body).required) }
        assertEquals(1, statusProvider.status.first().placementsWaitingForPlan)
        assertEquals(0, statusProvider.status.first().failedPlacements)
    }

    @Test
    fun noPlanStaysPendingAndSucceedsOnceAPullBringsAPlan() = runBlocking {
        loginAsParent()
        seedSnapshot() // Pip: no plan
        enqueue(PlacementSpec(PIP))

        assertEquals(SyncOutcome.Success, engine.runCycle())
        assertTrue(planItemPosts().isEmpty())
        assertEquals(PlacementErrors.REASON_NO_PLAN, placements().single().third)

        // The parent creates a plan for Pip elsewhere; the next pull brings it and the same cycle places the video.
        val planOfNoor = serverSnapshot.children.single { it.id == NOOR }.plan!!
        serverSnapshot = serverSnapshot.copy(
            children = serverSnapshot.children.map { if (it.id == PIP) it.copy(plan = planOfNoor.copy(id = "00000000-0000-4000-8000-0000000000aa", childId = PIP)) else it },
        )
        serverEtag = "\"changed\""
        seen.clear()

        assertEquals(SyncOutcome.Success, engine.runCycle())

        assertEquals(listOf("GET /sync/pull", "POST /children/$PIP/plan/items"), seen.map { "${it.method} ${it.path}" })
        assertEquals(PlacementState.DONE, placements().single().second)
    }

    @Test
    fun aStaleSnapshotThatStillShowsAPlanGetsTheBackendsNoPlan404AndKeepsWaiting() = runBlocking {
        loginAsParent()
        seedSnapshot() // Ali has a plan locally, but the server says otherwise (plan deleted meanwhile)
        enqueue(PlacementSpec(ALI))
        route("POST /children/*") { json(noPlan.status, noPlan.bodyText) }

        assertEquals(SyncOutcome.Success, engine.runCycle())

        assertEquals(1, planItemPosts().size)
        assertEquals(Triple<String?, PlacementState, String?>(ALI, PlacementState.PENDING, PlacementErrors.REASON_NO_PLAN), placements().single())
    }

    @Test
    fun anUnavailableVideoFailsThePlacementWithTheReasonAndIsCounted() = runBlocking {
        loginAsParent()
        seedSnapshot()
        enqueue(PlacementSpec(ALI))
        route("POST /children/*") { json(unavailable.status, unavailable.bodyText) }

        val outcome = engine.runCycle()

        assertEquals(SyncOutcome.PartialFailure(SyncCounts(failedPlacements = 1)), outcome)
        assertEquals(Triple<String?, PlacementState, String?>(ALI, PlacementState.FAILED, "This video is unavailable"), placements().single())
        assertEquals(1, statusProvider.status.first().failedPlacements)
        assertEquals(0, statusProvider.status.first().placementsWaitingForPlan) // failed and waiting are counted separately

        seen.clear()
        engine.runCycle()
        assertTrue("a failed placement is never retried", planItemPosts().isEmpty())
    }

    @Test
    fun aValidationErrorFailsThePlacement() = runBlocking {
        loginAsParent()
        seedSnapshot()
        enqueue(PlacementSpec(ALI, viewTarget = 0))
        val f = ContractFixtures.load(33)
        route("POST /children/*") { json(f.status, f.bodyText) }

        engine.runCycle()

        assertEquals(PlacementState.FAILED, placements().single().second)
        assertTrue(placements().single().third!!.contains("view_target"))
    }

    @Test
    fun aServerErrorOnAPlacementIsRetryLaterAndTheRowStaysPending() = runBlocking {
        loginAsParent()
        seedSnapshot()
        enqueue(PlacementSpec(ALI))
        route("POST /children/*") { json(502, "{}") }

        assertEquals(SyncOutcome.RetryLater, engine.runCycle())
        assertEquals(PlacementState.PENDING, placements().single().second)
    }

    @Test
    fun aPlacementWhoseVideoWasRejectedFailsForGood() = runBlocking {
        loginAsParent()
        seedSnapshot()
        enqueue(PlacementSpec(ALI))
        route("POST /sync/push") { req ->
            val body = KidTubeJson.instance.decodeFromString(PushBody.serializer(), req.body!!.utf8())
            val answer = PushResult(
                videos = body.videos.mapIndexed { i, _ -> PushVideoResult(i, PushVideoStatus.REJECTED, null, null, "Only YouTube and Facebook links are supported", false) },
                events = emptyList(),
            )
            json(200, KidTubeJson.instance.encodeToString(PushResult.serializer(), answer))
        }

        assertEquals(SyncOutcome.PartialFailure(SyncCounts(rejectedVideos = 1)), engine.runCycle())

        assertEquals(Triple<String?, PlacementState, String?>(ALI, PlacementState.FAILED, PlacementErrors.REASON_VIDEO_REJECTED), placements().single())
        assertTrue(planItemPosts().isEmpty())
        assertEquals(1, statusProvider.status.first().rejectedVideos)
    }

    @Test
    fun everyChildPlacementBeforeTheFirstPullWaitsForChildrenThenExpands() = runBlocking {
        loginAsParent() // no snapshot stored yet: no children known
        enqueue(PlacementSpec(childId = null))

        assertEquals(SyncOutcome.Success, engine.runCycle())

        // the pull in this cycle brought the children, and the post-pull placement pass expanded and placed the video
        assertEquals(setOf(ALI, SARA, OMAR, NOOR), planItemPosts().map { it.path.split("/")[2] }.toSet())
        assertEquals(PlacementState.PENDING, placements().single { it.first == PIP }.second)
    }

    @Test
    fun aCrashAfterThe201ButBeforeMarkPlacementDoneResolvesTo409AndDone() = runBlocking {
        loginAsParent()
        seedSnapshot()
        enqueue(PlacementSpec(ALI))
        assertEquals(SyncOutcome.Success, engine.runCycle()) // server: 201, the plan item now exists
        assertEquals(1, planItemPosts().size)
        // "The process died before markPlacementDone": the row is pending again while the server already has the item.
        executeSql("UPDATE outbox_placement SET state = 'pending', reason = NULL")
        route("POST /children/*") { json(alreadyInPlan.status, alreadyInPlan.bodyText) } // what the real server now answers

        assertEquals(SyncOutcome.Success, engine.runCycle())

        assertEquals("the resend hit the server again", 2, planItemPosts().size)
        assertEquals(PlacementState.DONE, placements().single().second)
    }

    @Test
    fun theVideoIdSentForAPlacementIsTheOneTheServerAssignedEvenWhenStillPending() = runBlocking {
        loginAsParent()
        seedSnapshot()
        enqueue(PlacementSpec(ALI))
        route("POST /sync/push") { req ->
            val body = KidTubeJson.instance.decodeFromString(PushBody.serializer(), req.body!!.utf8())
            val answer = PushResult(
                videos = body.videos.mapIndexed { i, _ -> PushVideoResult(i, PushVideoStatus.CREATED, "srv-pending", VideoStatus.PENDING, null, true) },
                events = emptyList(),
            )
            json(200, KidTubeJson.instance.encodeToString(PushResult.serializer(), answer))
        }

        assertEquals(SyncOutcome.Success, engine.runCycle()) // created+pending+retryable is NOT an error
        assertEquals("srv-pending", KidTubeJson.instance.decodeFromString(PlanItemCreate.serializer(), planItemPosts().single().body).videoId)
    }
}
