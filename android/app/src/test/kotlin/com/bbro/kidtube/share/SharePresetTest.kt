package com.bbro.kidtube.share

import com.bbro.kidtube.data.RoomTestBase
import com.bbro.kidtube.data.entity.PlacementState
import com.bbro.kidtube.data.remote.OnMiss
import com.bbro.kidtube.data.repo.OutboxRepository
import com.bbro.kidtube.data.repo.PlacementSpec
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SharePresetTest : RoomTestBase() {
    // ---- the agreed mapping (SharePreset KDoc table) -------------------------------------------------------------

    @Test
    fun onceADay_isOptionalUnlimitedWithADailyCapOfOne() {
        assertEquals(PlacementSpec("c1", required = false, viewTarget = null, dailyCap = 1), SharePreset.ONCE_A_DAY.placementFor("c1"))
    }

    @Test
    fun threeTimes_isOptionalWithATargetOfThreeAndADailyCapOfOne() {
        assertEquals(PlacementSpec("c1", required = false, viewTarget = 3, dailyCap = 1), SharePreset.THREE_TIMES.placementFor("c1"))
    }

    @Test
    fun mustWatchFirst_isRequiredWithNoTargetAndNoCap() {
        assertEquals(PlacementSpec("c1", required = true, viewTarget = null, dailyCap = null), SharePreset.MUST_WATCH.placementFor("c1"))
    }

    @Test
    fun libraryOnly_hasNoPlacementAtAll() {
        assertNull(SharePreset.LIBRARY_ONLY.placementFor("c1"))
        assertFalse(SharePreset.LIBRARY_ONLY.addsPlanItem)
        assertTrue(SharePreset.entries.filter { it != SharePreset.LIBRARY_ONLY }.all { it.addsPlanItem })
    }

    @Test
    fun everyPlacementKeepsTheDefaultsForTheRest() {
        SharePreset.entries.mapNotNull { it.placementFor("c1") }.forEach {
            assertNull(it.anchorDate)
            assertEquals(OnMiss.SHIFT, it.onMiss)
        }
    }

    @Test
    fun placements_areOnePerChild_neverEveryChild() {
        val result = SharePreset.placements(SharePreset.ONCE_A_DAY, listOf("a", "b", "c"))
        assertEquals(listOf("a", "b", "c"), result.map { it.childId })
        assertTrue(result.none { it.childId == null })
        assertEquals(emptyList<PlacementSpec>(), SharePreset.placements(SharePreset.LIBRARY_ONLY, listOf("a", "b")))
    }

    @Test
    fun describe_readsStoredItemsBackAsPresets_orNullForCustomOnes() {
        SharePreset.entries.mapNotNull { p -> p.placementFor("c")?.let { p to it } }.forEach { (preset, spec) ->
            assertEquals(preset, SharePreset.describe(spec.required, spec.viewTarget, spec.dailyCap))
        }
        assertNull(SharePreset.describe(required = false, viewTarget = 5, dailyCap = 2))
        assertNull(SharePreset.describe(required = true, viewTarget = 2, dailyCap = null))
        assertNull(SharePreset.describe(required = false, viewTarget = null, dailyCap = null)) // optional, no cap: a custom item
    }

    // ---- preset -> what lands in the outbox ----------------------------------------------------------------------

    private fun outbox() = OutboxRepository(db.outboxDao(), clock)

    @Test
    fun savingWithAPreset_writesOneOutboxVideoWithOnePendingPlacementPerChild() = runBlocking {
        val id = outbox().enqueue("https://youtu.be/dQw4w9WgXcQ", SharePreset.placements(SharePreset.THREE_TIMES, listOf(CHILD_A, CHILD_B)))

        val rows = db.outboxDao().placementsFor(id)
        assertEquals(setOf(CHILD_A, CHILD_B), rows.map { it.childId }.toSet())
        rows.forEach {
            assertEquals(false, it.required)
            assertEquals(3, it.viewTarget)
            assertEquals(1, it.dailyCap)
            assertEquals(PlacementState.PENDING, it.state)
        }
        assertEquals(1, db.outboxDao().pendingVideos().size)
    }

    @Test
    fun savingLibraryOnly_writesTheVideoWithNoPlacements() = runBlocking {
        val id = outbox().enqueue("https://youtu.be/dQw4w9WgXcQ", SharePreset.placements(SharePreset.LIBRARY_ONLY, listOf(CHILD_A)))

        assertEquals(emptyList<Any>(), db.outboxDao().placementsFor(id))
        assertEquals(listOf(id), db.outboxDao().pendingVideos().map { it.id })
    }

    @Test
    fun mustWatch_isStoredAsRequired() = runBlocking {
        val id = outbox().enqueue("https://youtu.be/dQw4w9WgXcQ", SharePreset.placements(SharePreset.MUST_WATCH, listOf(CHILD_A)))
        val row = db.outboxDao().placementsFor(id).single()
        assertTrue(row.required)
        assertNull(row.viewTarget)
        assertNull(row.dailyCap)
    }
}
