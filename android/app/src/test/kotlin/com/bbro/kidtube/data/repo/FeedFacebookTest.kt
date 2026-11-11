package com.bbro.kidtube.data.repo

import com.bbro.kidtube.data.RoomTestBase
import com.bbro.kidtube.data.dao.SnapshotData
import com.bbro.kidtube.data.remote.EventType
import com.bbro.kidtube.data.remote.Platform
import com.bbro.kidtube.data.toData
import com.bbro.kidtube.engine.FeedStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

/**
 * Facebook is off for kids (KIDS_FACEBOOK_ENABLED = false): the Facebook items are taken out of the engine INPUT, so they never
 * lock the feed behind a required item and never count toward the quota. The flag is injected, so each case also runs with it on
 * to prove the old behaviour comes back. Same snapshot as FeedRepositoryTest: on Mon 2026-01-05 the feed is [ITEM_2] (video 12).
 */
class FeedFacebookTest : RoomTestBase() {
    private val off get() = FeedRepository(db, clock, session.store, kidsFacebookEnabled = false)
    private val on get() = FeedRepository(db, clock, session.store, kidsFacebookEnabled = true)
    private lateinit var events: EventRepository

    @Before
    fun setUp() {
        clock.setWallClock(Instant.parse("2026-01-05T10:00:00Z"))
        events = EventRepository(db.localEventDao(), clock, session.store)
    }

    private suspend fun seed(change: (SnapshotData) -> SnapshotData) {
        val dao = db.snapshotDao()
        dao.beginPull(clock.now()).also { dao.replaceSnapshot(change(loadSnapshot().toData()), "\"etag\"", it) }
    }

    private fun SnapshotData.videoOfItem2IsFacebook() =
        copy(videos = videos.map { if (it.id == VIDEO_OF_ITEM_2) it.copy(platform = Platform.FACEBOOK) else it })

    private fun SnapshotData.item2Required() =
        copy(items = items.map { if (it.id == ITEM_2) it.copy(required = true) else it })

    private suspend fun FeedRepository.ready() = current(CHILD_A) as FeedState.Ready

    @Test
    fun aRequiredFacebookItemDoesNotLockTheKidFeed() = runBlocking {
        seed { it.videoOfItem2IsFacebook().item2Required() }

        val locked = on.ready()
        assertEquals(listOf(ITEM_2), locked.entries.map { it.item.itemId }) // old behaviour: the required item is shown (and locks)

        val kid = off.ready()
        assertFalse(ITEM_2 in kid.entries.map { it.item.itemId })
        assertTrue(kid.entries.none { it.video.platform == Platform.FACEBOOK })
        assertEquals(1, kid.feed.viewsToday) // only ITEM_1's server view; nothing of the Facebook item
    }

    @Test
    fun facebookViewsAndCapsAreIgnored() = runBlocking {
        seed { it.videoOfItem2IsFacebook() }
        events.record(EventType.VIEW_COMPLETE, CHILD_A, EventPayloads.viewComplete(ITEM_2, 0.95))

        assertEquals(FeedStatus.DONE_TODAY, on.ready().feed.status) // counted: the daily quota (2) is used up

        val kid = off.ready()
        assertEquals(1, kid.feed.viewsToday) // not counted
        assertEquals(1, kid.feed.remainingVideos)
        assertTrue(kid.feed.status != FeedStatus.DONE_TODAY)
        assertFalse(ITEM_2 in kid.viewedTodayItemIds)
    }

    @Test
    fun nonFacebookFeedsAreUnchangedAndTheParentProjectionKeepsFacebook() = runBlocking {
        seed { it.videoOfItem2IsFacebook() }
        val kid = off.ready()
        val parent = on.ready()
        assertEquals(parent.projection, kid.projection) // the projection is not filtered
        assertTrue(ITEM_2 in kid.projection.excludedUnlimitedItemIds)

        // With no Facebook video at all the flag changes nothing.
        db.clearAllTables()
        seed { it }
        assertEquals(on.ready().entries.map { it.item.itemId }, off.ready().entries.map { it.item.itemId })
    }

    private companion object {
        const val VIDEO_OF_ITEM_2 = "00000000-0000-4000-8000-000000000012"
    }
}
