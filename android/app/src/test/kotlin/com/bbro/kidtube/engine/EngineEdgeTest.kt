package com.bbro.kidtube.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Rules the shared vectors do not pin down and that are Kotlin-specific risks (java.time numbering, output truncation).
 * The vectors stay the contract; these only add cases.
 */
class EngineEdgeTest {
    private val monday = LocalDate.of(2026, 1, 5) // a Monday

    private fun plan(mask: Int, days: LocalDate = monday) =
        PlanConfig(PaceType.VIDEOS_PER_DAY, 1, mask, 1, days.minusDays(30))

    private fun item(id: String = "a") = ItemState(
        id, "v$id", 1, false, null, null, null, OnMiss.SHIFT, ItemStatus.ACTIVE, null, monday.minusDays(30),
    )

    @Test
    fun weekdayBitsAreMondayZeroThroughSundaySix() {
        for (bit in 0..6) {
            val p = plan(1 shl bit)
            for (offset in 0..6) {
                val day = monday.plusDays(offset.toLong()) // offset 0 = Monday ... 6 = Sunday
                assertEquals("bit $bit on ${day.dayOfWeek}", offset == bit, isActiveDay(p, day))
                val status = buildFeed(p, listOf(item()), emptyList(), emptyList(), emptyList(), ChildLimits(), day).status
                assertEquals(if (offset == bit) FeedStatus.OK else FeedStatus.DAY_OFF, status)
            }
        }
    }

    @Test
    fun projectionDaysOnlyTruncatesAndIsCappedAt91() {
        val p = plan(127)
        val items = listOf(item().copy(viewTarget = 2))
        assertEquals(91, project(p, items, emptyList(), emptyList(), monday, 500).days.size)
        assertEquals(0, project(p, items, emptyList(), emptyList(), monday, -3).days.size)
        // Truncation never changes the numbers.
        val full = project(p, items, emptyList(), emptyList(), monday, 91)
        val short = project(p, items, emptyList(), emptyList(), monday, 3)
        assertEquals(full.finishDate, short.finishDate)
        assertEquals(full.days.take(3), short.days)
    }

    @Test
    fun planConfigRejectsBadMaskAndNegativeNumbers() {
        assertThrows(IllegalArgumentException::class.java) { plan(128) }
        assertThrows(IllegalArgumentException::class.java) { plan(-1) }
        assertThrows(IllegalArgumentException::class.java) { plan(127).copy(maxExtraPerDay = -1) }
    }

    @Test
    fun unknownWireValuesAreRejectedLikePython() {
        assertThrows(IllegalArgumentException::class.java) { PaceType.fromWire("weekly") }
        assertThrows(IllegalArgumentException::class.java) { OnMiss.fromWire("later") }
        assertThrows(IllegalArgumentException::class.java) { ItemStatus.fromWire("paused") }
    }

    @Test
    fun feedIsDeterministicAndDoesNotMutateItsInputs() {
        val items = listOf(item("b").copy(position = 2), item("a").copy(position = 2), item("c").copy(position = 1))
        val views = listOf(ViewRecord("c", monday.minusDays(1)))
        val first = buildFeed(plan(127).copy(paceValue = 3), items, views, emptyList(), emptyList(), ChildLimits(), monday)
        val second = buildFeed(plan(127).copy(paceValue = 3), items, views, emptyList(), emptyList(), ChildLimits(), monday)
        assertEquals(first, second)
        assertEquals(listOf("c", "a", "b"), first.items.map { it.itemId }) // position, then id
        assertEquals(listOf("b", "a", "c"), items.map { it.id }) // caller's list untouched
        assertTrue(first.items.none { it.locked })
        assertFalse(first.items.isEmpty())
    }
}
