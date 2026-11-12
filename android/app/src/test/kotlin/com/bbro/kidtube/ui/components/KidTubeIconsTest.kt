package com.bbro.kidtube.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The bottom tabs: Library = play, Plans = calendar, Reports = a bar chart (no longer the info "i"). */
class KidTubeIconsTest {
    @Test
    fun barChartIsItsOwn24dpIcon_notTheInfoIcon() {
        val icon = KidTubeIcons.BarChart
        assertEquals(24f, icon.viewportWidth, 0f)
        assertEquals(24f, icon.viewportHeight, 0f)
        assertNotEquals(Icons.Filled.Info, icon)
        assertNotEquals(Icons.Filled.PlayArrow, icon)
        assertNotEquals(Icons.Filled.DateRange, icon)
        assertEquals("three bars", 3, countSubPaths(icon))
    }

    private fun countSubPaths(icon: androidx.compose.ui.graphics.vector.ImageVector): Int {
        var moves = 0
        fun walk(group: androidx.compose.ui.graphics.vector.VectorGroup) {
            for (node in group) {
                when (node) {
                    is androidx.compose.ui.graphics.vector.VectorPath ->
                        moves += node.pathData.count { it is androidx.compose.ui.graphics.vector.PathNode.MoveTo }
                    is androidx.compose.ui.graphics.vector.VectorGroup -> walk(node)
                }
            }
        }
        walk(icon.root)
        assertTrue(moves > 0)
        return moves
    }
}
