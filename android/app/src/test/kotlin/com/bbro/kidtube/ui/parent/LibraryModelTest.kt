package com.bbro.kidtube.ui.parent

import com.bbro.kidtube.data.entity.OutboxVideoState
import com.bbro.kidtube.data.entity.PlacementState
import com.bbro.kidtube.data.remote.VideoStatus
import com.bbro.kidtube.share.SharePreset
import com.bbro.kidtube.sync.PlacementErrors
import com.bbro.kidtube.testing.Rows
import com.bbro.kidtube.ui.components.BadgeStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class LibraryModelTest {
    private val ali = Rows.child("ali", "Ali")
    private val sara = Rows.child("sara", "Sara")
    private val children = listOf(ali, sara)

    private fun map(
        videos: List<com.bbro.kidtube.data.entity.VideoEntity> = emptyList(),
        plans: List<com.bbro.kidtube.data.dao.PlanWithItems> = emptyList(),
        outbox: List<com.bbro.kidtube.share.OutboxItem> = emptyList(),
    ) = LibraryMapper.map(videos, plans, children, outbox)

    // ---- server videos: verified / pending / unavailable ---------------------------------------------------------

    @Test
    fun videoStatusesMapToBadges() {
        val items = map(
            videos = listOf(
                Rows.video("a", status = VideoStatus.VERIFIED),
                Rows.video("b", status = VideoStatus.PENDING),
                Rows.video("c", status = VideoStatus.UNAVAILABLE),
                Rows.video("d", status = VideoStatus.UNKNOWN), // a newer server's status: shown as pending, never a crash
            ),
        )
        assertEquals(
            mapOf("a" to BadgeStatus.VERIFIED, "b" to BadgeStatus.PENDING, "c" to BadgeStatus.UNAVAILABLE, "d" to BadgeStatus.PENDING),
            items.associate { it.id.removePrefix("video:") to it.status },
        )
        assertTrue(items.none { it.fromOutbox })
    }

    @Test
    fun aVideoWithoutATitleShowsItsLink() {
        val item = map(videos = listOf(Rows.video("a", title = null, url = "https://www.youtube.com/watch?v=abc"))).single()
        assertEquals("youtube.com/watch?v=abc", item.title)
    }

    @Test
    fun newestVideosComeFirst() {
        val items = map(
            videos = listOf(
                Rows.video("old", addedAt = Instant.parse("2026-10-01T00:00:00Z")),
                Rows.video("new", addedAt = Instant.parse("2026-11-01T00:00:00Z")),
            ),
        )
        assertEquals(listOf("video:new", "video:old"), items.map { it.id })
    }

    // ---- who it is for -------------------------------------------------------------------------------------------

    @Test
    fun planItemsBecomeAssignmentsWithTheirPreset() {
        val items = map(
            videos = listOf(Rows.video("v1")),
            plans = listOf(
                Rows.plan("ali", Rows.item("i1", "", "v1", dailyCap = 1)),
                Rows.plan("sara", Rows.item("i2", "", "v1", required = true), Rows.item("i3", "", "other")),
            ),
        )
        val v1 = items.single()
        assertEquals(
            listOf(Assignment("ali", "Ali", SharePreset.ONCE_A_DAY), Assignment("sara", "Sara", SharePreset.MUST_WATCH)),
            v1.assignments,
        )
        assertTrue(!v1.libraryOnly)
        assertNull(v1.note)
    }

    @Test
    fun aVideoInNoPlan_isLibraryOnly() {
        assertTrue(map(videos = listOf(Rows.video("v1")), plans = listOf(Rows.plan("ali"))).single().libraryOnly)
    }

    @Test
    fun aCustomPlanItem_hasNoPresetName() {
        val item = map(videos = listOf(Rows.video("v1")), plans = listOf(Rows.plan("ali", Rows.item("i", "", "v1", viewTarget = 7)))).single()
        assertNull(item.assignments.single().preset)
    }

    // ---- the outbox ----------------------------------------------------------------------------------------------

    @Test
    fun aLinkStillInTheOutbox_showsAsPendingWaitingToSend_withItsChildren() {
        val outbox = listOf(Rows.outbox("o1", "https://youtu.be/dQw4w9WgXcQ", placements = arrayOf(Rows.placement("ali", dailyCap = 1))))

        val item = map(outbox = outbox).single()

        assertEquals("youtu.be/dQw4w9WgXcQ", item.title)
        assertEquals(BadgeStatus.PENDING, item.status)
        assertEquals(LibraryNote.WAITING_TO_SEND, item.note)
        assertTrue(item.fromOutbox)
        assertEquals(listOf(Assignment("ali", "Ali", SharePreset.ONCE_A_DAY)), item.assignments)
    }

    @Test
    fun anEveryChildPlacement_isExpandedToEveryChild() {
        val outbox = listOf(Rows.outbox("o1", "https://youtu.be/dQw4w9WgXcQ", placements = arrayOf(Rows.placement(null, required = true))))
        assertEquals(listOf("ali", "sara"), map(outbox = outbox).single().assignments.map { it.childId })
    }

    @Test
    fun aLibraryOnlyLinkInTheOutbox_hasNoChildren() {
        val item = map(outbox = listOf(Rows.outbox("o1", "https://youtu.be/dQw4w9WgXcQ"))).single()
        assertTrue(item.libraryOnly)
        assertEquals(LibraryNote.WAITING_TO_SEND, item.note)
    }

    @Test
    fun aRejectedLink_isUnavailable_andSaysItWasNotAdded() {
        val item = map(outbox = listOf(Rows.outbox("o1", "https://youtu.be/dQw4w9WgXcQ", state = OutboxVideoState.REJECTED))).single()
        assertEquals(BadgeStatus.UNAVAILABLE, item.status)
        assertEquals(LibraryNote.NOT_ADDED, item.note)
    }

    @Test
    fun outboxLinksComeBeforeServerVideos_newestFirst() {
        val items = map(
            videos = listOf(Rows.video("v1")),
            outbox = listOf(
                Rows.outbox("o1", "https://youtu.be/AAAAAAAAAAA", createdAt = Instant.parse("2026-11-02T00:00:00Z")),
                Rows.outbox("o2", "https://youtu.be/BBBBBBBBBBB", createdAt = Instant.parse("2026-11-03T00:00:00Z")),
            ),
        )
        assertEquals(listOf("outbox:o2", "outbox:o1", "video:v1"), items.map { it.id })
    }

    @Test
    fun onceTheSyncedVideoIsInTheSnapshot_theOutboxRowIsNotShownTwice() {
        val outbox = listOf(
            Rows.outbox("o1", "https://youtu.be/vid", state = OutboxVideoState.SYNCED, serverVideoId = "v1", placements = arrayOf(Rows.placement("ali", state = PlacementState.DONE))),
        )
        val items = map(videos = listOf(Rows.video("v1")), outbox = outbox)
        assertEquals(listOf("video:v1"), items.map { it.id })
    }

    @Test
    fun aSyncedVideoNotYetPulled_stillShowsAsWaiting() {
        val outbox = listOf(Rows.outbox("o1", "https://youtu.be/vid", state = OutboxVideoState.SYNCED, serverVideoId = "v-new"))
        val item = map(outbox = outbox).single()
        assertTrue(item.fromOutbox)
        assertEquals(BadgeStatus.PENDING, item.status)
    }

    @Test
    fun aVideoWhosePlacementWaitsForAPlan_saysSo() {
        val outbox = listOf(
            Rows.outbox(
                "o1", "https://youtu.be/vid", state = OutboxVideoState.SYNCED, serverVideoId = "v1",
                placements = arrayOf(Rows.placement("sara", required = true, reason = PlacementErrors.REASON_NO_PLAN)),
            ),
        )
        val item = map(videos = listOf(Rows.video("v1")), plans = listOf(Rows.plan("ali")), outbox = outbox).single()
        assertEquals(LibraryNote.WAITING_FOR_PLAN, item.note)
        assertEquals(listOf(Assignment("sara", "Sara", SharePreset.MUST_WATCH)), item.assignments)
    }

    // ---- the child filter ----------------------------------------------------------------------------------------

    @Test
    fun filterKeepsOnlyThatChildsVideos_andAllShowsEverything() {
        val items = map(
            videos = listOf(Rows.video("v1"), Rows.video("v2"), Rows.video("v3")),
            plans = listOf(Rows.plan("ali", Rows.item("i1", "", "v1")), Rows.plan("sara", Rows.item("i2", "", "v2"))),
        )
        assertEquals(setOf("video:v1"), LibraryMapper.filter(items, "ali").map { it.id }.toSet())
        assertEquals(setOf("video:v2"), LibraryMapper.filter(items, "sara").map { it.id }.toSet())
        assertEquals(3, LibraryMapper.filter(items, null).size) // v3 is library only: it belongs to "All"
    }

    // ---- dropped placements ----------------------------------------------------------------------------------------

    private fun failedOutbox(reason: String?, serverVideoId: String = "v1", childId: String = "ali") = Rows.outbox(
        "o1", "https://youtu.be/vid", state = OutboxVideoState.SYNCED, serverVideoId = serverVideoId,
        placements = arrayOf(Rows.placement(childId, state = PlacementState.FAILED, reason = reason)),
    )

    @Test
    fun aPlacementRejectedBecauseTheVideoIsUnavailable_saysSo_keepsTheBadge_andCanBeRemoved() {
        val item = map(
            videos = listOf(Rows.video("v1", status = VideoStatus.UNAVAILABLE)),
            outbox = listOf(failedOutbox("This video is unavailable")),
        ).single()
        assertEquals(BadgeStatus.UNAVAILABLE, item.status)
        assertEquals(LibraryNote.NOT_ADDED, item.note)
        assertEquals(NotAddedReason.Unavailable, item.notAddedReason)
        assertEquals(listOf("o1"), item.dismissibleOutboxIds)
        assertTrue("a server video stays in the library", !item.removesRow)
        assertTrue(item.libraryOnly)
    }

    @Test
    fun anUnavailableVideo_isReportedAsUnavailable_evenWhenOnlyAStatusCodeWasStored() {
        val item = map(videos = listOf(Rows.video("v1", status = VideoStatus.UNAVAILABLE)), outbox = listOf(failedOutbox("http_422"))).single()
        assertEquals(NotAddedReason.Unavailable, item.notAddedReason)
    }

    @Test
    fun aPlacementRejectedWithAServerReason_showsTheServersWords() {
        val item = map(videos = listOf(Rows.video("v1")), outbox = listOf(failedOutbox("Plan is full"))).single()
        assertEquals(LibraryNote.NOT_ADDED, item.note)
        assertEquals(NotAddedReason.Server("Plan is full"), item.notAddedReason)
        assertEquals(BadgeStatus.VERIFIED, item.status)
    }

    @Test
    fun aPlacementThatFailedWithOnlyAStatusCode_hasNoReadableReason() {
        val item = map(videos = listOf(Rows.video("v1")), outbox = listOf(failedOutbox("http_500"))).single()
        assertEquals(LibraryNote.NOT_ADDED, item.note)
        assertEquals(NotAddedReason.Unknown, item.notAddedReason)
    }

    @Test
    fun aRejectedVideoCode_hasNoReadableReason_butStillSaysNotAdded() {
        val item = map(
            videos = listOf(Rows.video("v1")),
            outbox = listOf(failedOutbox(PlacementErrors.REASON_VIDEO_REJECTED)),
        ).single()
        assertEquals(LibraryNote.NOT_ADDED, item.note)
        assertEquals(NotAddedReason.Unknown, item.notAddedReason)
    }

    @Test
    fun aRejectedLinkInTheOutbox_showsTheServersReason_andRemoveDeletesTheRow() {
        val item = map(
            outbox = listOf(Rows.outbox("o1", "https://youtu.be/x", state = OutboxVideoState.REJECTED, rejectReason = "That link is not a video")),
        ).single()
        assertEquals(BadgeStatus.UNAVAILABLE, item.status)
        assertEquals(NotAddedReason.Server("That link is not a video"), item.notAddedReason)
        assertEquals(listOf("o1"), item.dismissibleOutboxIds)
        assertTrue(item.removesRow)
    }

    @Test
    fun aRejectedLinkWhoseReasonMentionsUnavailable_isUnavailable() {
        val item = map(outbox = listOf(Rows.outbox("o1", "https://youtu.be/x", state = OutboxVideoState.REJECTED, rejectReason = "Video unavailable"))).single()
        assertEquals(NotAddedReason.Unavailable, item.notAddedReason)
    }

    @Test
    fun aFailedPlacementForAChildWhoDoesHaveTheVideo_isNotReported() {
        val item = map(
            videos = listOf(Rows.video("v1")),
            plans = listOf(Rows.plan("ali", Rows.item("i1", "", "v1"))),
            outbox = listOf(failedOutbox("Plan is full")),
        ).single()
        assertNull(item.note)
        assertNull(item.notAddedReason)
        assertTrue(!item.canDismiss)
    }

    @Test
    fun pendingAndDonePlacementsAreNeverDismissible() {
        val outbox = Rows.outbox(
            "o1", "https://youtu.be/vid", state = OutboxVideoState.SYNCED, serverVideoId = "v1",
            placements = arrayOf(Rows.placement("ali", state = PlacementState.DONE)),
        )
        val item = map(videos = listOf(Rows.video("v1")), outbox = listOf(outbox)).single()
        assertTrue(!item.canDismiss)
    }

    @Test
    fun aWaitingPlacement_winsOverTheOldFailureNote() {
        val outbox = Rows.outbox(
            "o1", "https://youtu.be/vid", state = OutboxVideoState.SYNCED, serverVideoId = "v1",
            placements = arrayOf(
                Rows.placement("ali", state = PlacementState.FAILED, reason = "Plan is full"),
                Rows.placement("sara", state = PlacementState.PENDING),
            ),
        )
        val item = map(videos = listOf(Rows.video("v1")), outbox = listOf(outbox)).single()
        assertEquals(LibraryNote.WAITING_TO_SEND, item.note)
        assertTrue("nothing can be dismissed while something is still sending", !item.canDismiss)
    }
}
