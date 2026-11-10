package com.bbro.kidtube.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Colour tokens from docs/DESIGN.md. Rule: white text only on [primary] or [ink]; never on coral, sun or pastels. */
@Immutable
data class KidTubeColors(
    val ground: Color = Color(0xFFFFF8EE),
    val kidGround: Color = Color(0xFFFFE9A8),
    val ink: Color = Color(0xFF12343B),
    val muted: Color = Color(0xFF4A6670),
    val card: Color = Color(0xFFFFFFFF),
    val line: Color = Color(0xFFEADFCB),
    val primary: Color = Color(0xFF0F766E),
    val primarySoft: Color = Color(0xFFCFEDE9),
    val verifiedText: Color = Color(0xFF0B5B54),
    val coral: Color = Color(0xFFFF7A59),
    val sun: Color = Color(0xFFFFC93C),
    val pendingBg: Color = Color(0xFFFFE9A8),
    val pendingText: Color = Color(0xFF6B4A00),
    val errorBg: Color = Color(0xFFF6B8A5),
    val errorText: Color = Color(0xFF7A2410),
    val thumbnailPastels: List<Color> = listOf(
        Color(0xFFF6B8A5), Color(0xFFBFD9F2), Color(0xFFCFEDE9), Color(0xFFE9D4F5), Color(0xFFFFE9A8),
    ),
) {
    val onPrimary: Color get() = Color.White
}

internal val LocalKidTubeColors = staticCompositionLocalOf { KidTubeColors() }
