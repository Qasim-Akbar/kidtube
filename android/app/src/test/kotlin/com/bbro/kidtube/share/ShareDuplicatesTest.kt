package com.bbro.kidtube.share

import com.bbro.kidtube.data.entity.OutboxVideoState
import com.bbro.kidtube.data.entity.PlacementState
import com.bbro.kidtube.data.remote.Platform
import com.bbro.kidtube.data.remote.PlanItemStatus
import com.bbro.kidtube.testing.Rows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareDuplicatesTest {
    private val key = "youtube:dQw4w9WgXcQ"
    private val url = "https://youtu.be/dQw4w9WgXcQ?si=1"
    private val video = Rows.video("v1", platformVideoId = "dQw4w9WgXcQ")
    private val kids = listOf("ali", "sara", "omar")

    private fun have(
        library: List<com.bbro.kidtube.data.entity.VideoEntity> = emptyList(),
        plans: List<com.bbro.kidtube.data.dao.PlanWithItems> = emptyList(),
        outbox: List<OutboxItem> = emptyList(),
    ) = ShareDuplicates.childrenWithVideo(key, kids, library, plans, outbox)

    @Test
    fun nothingSavedYet_noDuplicates() {
        assertEquals(emptySet<String>(), have())
    }

    @Test
    fun inAChildsPlan_isADuplicateForThatChildOnly() {
        val plans = listOf(Rows.plan("ali", Rows.item("i1", "", "v1")), Rows.plan("sara"))
        assertEquals(setOf("ali"), have(library = listOf(video), plans = plans))
    }

    @Test
    fun aDroppedPlanItem_doesNotCount() {
        val plans = listOf(Rows.plan("ali", Rows.item("i1", "", "v1", status = PlanItemStatus.DROPPED)))
        assertEquals(emptySet<String>(), have(library = listOf(video), plans = plans))
    }

    @Test
    fun inTheLibraryButInNobodysPlan_isNotADuplicateForAChild() {
        assertEquals(emptySet<String>(), have(library = listOf(video), plans = listOf(Rows.plan("ali"))))
    }

    @Test
    fun waitingInTheOutboxForAChild_isADuplicateForThatChild() {
        val outbox = listOf(Rows.outbox("o1", url, placements = arrayOf(Rows.placement("sara"))))
        assertEquals(setOf("sara"), have(outbox = outbox))
    }

    @Test
    fun anEveryChildPlacementInTheOutbox_countsForEveryone() {
        val outbox = listOf(Rows.outbox("o1", url, placements = arrayOf(Rows.placement(null))))
        assertEquals(kids.toSet(), have(outbox = outbox))
    }

    @Test
    fun aFailedPlacementOrARejectedLink_doesNotBlockANewAttempt() {
        val failed = Rows.outbox("o1", url, placements = arrayOf(Rows.placement("ali", state = PlacementState.FAILED)))
        val rejected = Rows.outbox("o2", url, state = OutboxVideoState.REJECTED, placements = arrayOf(Rows.placement("sara")))
        assertEquals(emptySet<String>(), have(outbox = listOf(failed, rejected)))
    }

    @Test
    fun aDifferentLinkInTheOutbox_isIgnored() {
        val other = Rows.outbox("o1", "https://youtu.be/AAAAAAAAAAA", placements = arrayOf(Rows.placement("ali")))
        assertEquals(emptySet<String>(), have(outbox = listOf(other)))
    }

    @Test
    fun theSameVideoInADifferentUrlShape_isStillTheSameVideo() {
        val outbox = listOf(Rows.outbox("o1", "https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=5", placements = arrayOf(Rows.placement("omar"))))
        assertEquals(setOf("omar"), have(outbox = outbox))
    }

    @Test
    fun facebookVideosAreMatchedByTheirNormalisedLink() {
        val fbUrl = "https://www.facebook.com/watch?v=1234567890"
        val fbKey = (SharedLinks.check(fbUrl) as LinkCheck.Supported).key
        val fb = Rows.video("f1", platformVideoId = "1234567890", platform = Platform.FACEBOOK, url = "https://m.facebook.com/watch/?v=1234567890")
        val result = ShareDuplicates.childrenWithVideo(fbKey, kids, listOf(fb), listOf(Rows.plan("ali", Rows.item("i1", "", "f1"))), emptyList())
        assertEquals(setOf("ali"), result)
    }

    // ---- library only --------------------------------------------------------------------------------------------

    @Test
    fun libraryOnly_isADuplicateWhenTheVideoIsInTheLibraryOrTheOutbox() {
        assertFalse(ShareDuplicates.inLibraryOrOutbox(key, emptyList(), emptyList()))
        assertTrue(ShareDuplicates.inLibraryOrOutbox(key, listOf(video), emptyList()))
        assertTrue(ShareDuplicates.inLibraryOrOutbox(key, emptyList(), listOf(Rows.outbox("o1", url))))
        assertFalse(ShareDuplicates.inLibraryOrOutbox(key, emptyList(), listOf(Rows.outbox("o1", url, state = OutboxVideoState.REJECTED))))
    }
}
