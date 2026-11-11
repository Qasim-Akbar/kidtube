package com.bbro.kidtube.ui.theme

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Shapes and spacing from docs/DESIGN.md. Gaps: 8 / 10 / 12 / 16 / 20 / 24. */
object KidTubeShapes {
    val button = RoundedCornerShape(20.dp)
    val card = RoundedCornerShape(24.dp)
    val kidTile = RoundedCornerShape(32.dp)
    val chip = CircleShape
    val thumbnail = RoundedCornerShape(14.dp)
}

object KidTubeDimens {
    val screenPadding: Dp = 24.dp
    val borderWidth: Dp = 2.dp
    val selectedBorderWidth: Dp = 3.dp
    val minTouchTarget: Dp = 48.dp
    val buttonHeight: Dp = 60.dp
    val kidTileMinHeight: Dp = 150.dp
    val gap8: Dp = 8.dp
    val gap10: Dp = 10.dp
    val gap12: Dp = 12.dp
    val gap16: Dp = 16.dp
    val gap20: Dp = 20.dp
    val gap24: Dp = 24.dp
}

internal val KidTubeMaterialShapes = Shapes(
    extraSmall = RoundedCornerShape(12.dp),
    small = RoundedCornerShape(16.dp),
    medium = KidTubeShapes.button,
    large = KidTubeShapes.card,
    extraLarge = KidTubeShapes.kidTile,
)
