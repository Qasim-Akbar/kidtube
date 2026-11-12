package com.bbro.kidtube.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** Icons that material-icons-core does not ship (the extended set is a big dependency we do not want for one glyph). */
object KidTubeIcons {
    /** Material "bar chart": three vertical bars of different heights (24dp viewport). */
    val BarChart: ImageVector by lazy {
        ImageVector.Builder(
            name = "BarChart", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                // left bar, middle (tallest) bar, right bar
                moveTo(5f, 9.2f); horizontalLineToRelative(3.8f); verticalLineTo(19f); horizontalLineTo(5f); close()
                moveTo(10.1f, 5f); horizontalLineToRelative(3.8f); verticalLineTo(19f); horizontalLineToRelative(-3.8f); close()
                moveTo(15.2f, 12.8f); horizontalLineTo(19f); verticalLineTo(19f); horizontalLineToRelative(-3.8f); close()
            }
        }.build()
    }
}
