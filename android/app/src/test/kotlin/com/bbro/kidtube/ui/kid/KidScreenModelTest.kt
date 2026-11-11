package com.bbro.kidtube.ui.kid

import com.bbro.kidtube.data.entity.ChildEntity
import com.bbro.kidtube.data.remote.VideoStatus
import com.bbro.kidtube.data.repo.FeedEntry
import com.bbro.kidtube.data.repo.FeedState
import com.bbro.kidtube.engine.FeedItem
import com.bbro.kidtube.engine.FeedResult
import com.bbro.kidtube.engine.FeedStatus
import com.bbro.kidtube.engine.PaceType
import com.bbro.kidtube.engine.Projection
import com.bbro.kidtube.testing.Rows
import com.bbro.kidtube.ui.children.AvatarColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** FeedState -> what the kid sees, for every feed status. Pure (no Android). */
class KidScreenModelTest {
    private val child = ChildEntity("c1", "Ali", "sky", null)
    private val today = LocalDate.of(2026, 1, 5)

    private fun entry(id: String, locked: Boolean = false, required: Boolean = locked, status: VideoStatus = VideoStatus.VERIFIED, title: String? = "Title $id") =
        FeedEntry(FeedItem(id, "v-$id", locked, required), Rows.video("v-$id", title = title, status = status), 0.9)

    fun ready(
        status: FeedStatus = FeedStatus.OK,
        entries: List<FeedEntry> = emptyList(),
        pace: PaceType = PaceType.VIDEOS_PER_DAY,
        quota: Int = 3,
        remaining: Int? = 2,
        viewed: Set<String> = emptySet(),
        limit: Int? = null,
        used: Int = 0,
    ) = FeedState.Ready(
        "c1", today, pace,
        FeedResult(status, entries.map { it.item }, quota, 0, remaining, null, 0, emptyList(), emptyList()),
        entries, emptyList(), Projection(emptyList(), null, 0, emptyList(), emptyList()), viewed, limit, used,
    )

    @Test
    fun okFeedBecomesTwoColumnTilesWithDotsAndChecks() {
        val s = KidScreenModel.map(child, ready(entries = listOf(entry("a"), entry("b")), viewed = setOf("b"))) as KidScreenState.Grid
        assertEquals(listOf("a", "b"), s.tiles.map { it.itemId })
        assertEquals(listOf(false, true), s.tiles.map { it.viewedToday })
        assertNull(s.first)
        assertEquals(3, s.dotsTotal)
        assertEquals(2, s.dotsLeft)
        assertNull(s.timeLeft)
        assertEquals(AvatarColor.SKY, s.child.avatar)
    }

    @Test
    fun firstTileOnlyWhenTheFeedIsLockedToARequiredItem() {
        val locked = KidScreenModel.map(child, ready(entries = listOf(entry("r", locked = true)))) as KidScreenState.Grid
        assertEquals("r", locked.first?.itemId)
        assertTrue(locked.tiles.isEmpty())

        val free = KidScreenModel.map(child, ready(entries = listOf(entry("a"), entry("b")))) as KidScreenState.Grid
        assertNull(free.first)
    }

    @Test
    fun dotsAreCappedClampedAndAbsentInMinutesMode() {
        val big = KidScreenModel.map(child, ready(entries = listOf(entry("a")), quota = 20, remaining = 15)) as KidScreenState.Grid
        assertEquals(KidScreenModel.MAX_DOTS, big.dotsTotal)
        assertEquals(KidScreenModel.MAX_DOTS, big.dotsLeft)

        val none = KidScreenModel.map(child, ready(entries = listOf(entry("a")), remaining = 0)) as KidScreenState.Grid
        assertEquals(0, none.dotsLeft)

        val minutes = KidScreenModel.map(child, ready(entries = listOf(entry("a")), pace = PaceType.MINUTES_PER_DAY, quota = 1800, remaining = null)) as KidScreenState.Grid
        assertNull(minutes.dotsTotal)
    }

    @Test
    fun timeLeftOnlyWithAnAppTimeLimit() {
        val s = KidScreenModel.map(child, ready(entries = listOf(entry("a")), limit = 1800, used = 1000)) as KidScreenState.Grid
        assertEquals(TimeLeft(800, 1800), s.timeLeft)
        val over = KidScreenModel.map(child, ready(entries = listOf(entry("a")), limit = 600, used = 900)) as KidScreenState.Grid
        assertEquals(0, over.timeLeft?.secondsLeft)
    }

    @Test
    fun pendingVideosAreTilesLikeAnyOther() {
        val s = KidScreenModel.map(child, ready(entries = listOf(entry("a", status = VideoStatus.PENDING)))) as KidScreenState.Grid
        assertEquals(listOf("a"), s.tiles.map { it.itemId })
    }

    @Test
    fun everyFeedStatusMapsToItsScreen() {
        fun kind(status: FeedStatus) = (KidScreenModel.map(child, ready(status = status)) as KidScreenState.Status).kind
        assertEquals(StatusKind.DONE, kind(FeedStatus.DONE_TODAY))
        assertEquals(StatusKind.DONE, kind(FeedStatus.ALL_DONE))
        assertEquals(StatusKind.TIME_UP, kind(FeedStatus.TIME_UP))
        assertEquals(StatusKind.DAY_OFF, kind(FeedStatus.DAY_OFF))
        assertEquals(StatusKind.DAY_OFF, kind(FeedStatus.OUTSIDE_DATES))
        assertEquals(StatusKind.NOTHING, kind(FeedStatus.EMPTY))
    }

    @Test
    fun noPlanUnsupportedPlanAndMissingChild() {
        assertEquals(StatusKind.NOTHING, (KidScreenModel.map(child, FeedState.NoPlan(today)) as KidScreenState.Status).kind)
        assertEquals(StatusKind.NOTHING, (KidScreenModel.map(child, FeedState.UnsupportedPlan(today)) as KidScreenState.Status).kind)
        assertEquals(KidScreenState.Gone, KidScreenModel.map(child, FeedState.NoChild))
        assertEquals(KidScreenState.Gone, KidScreenModel.map(null, ready()))
    }
}
