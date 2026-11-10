package com.bbro.kidtube.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Sizes from docs/DESIGN.md: kid title 38, screen title 28-34, section label 15-17 bold muted, body 16-17,
 * badge/caption 13-14. Titles and buttons are 800 in Latin, 700 in Urdu. Urdu gets +2sp and a 1.7 line height
 * (Naskh needs the room); Latin uses 1.3.
 */
fun kidTubeTypography(isUrdu: Boolean): Typography {
    val family: FontFamily = KidTubeFonts.forLanguage(isUrdu)
    val heavy = if (isUrdu) FontWeight.Bold else FontWeight.ExtraBold
    val bump = if (isUrdu) 2 else 0
    val lineHeightFactor = if (isUrdu) 1.7f else 1.3f

    fun style(size: Int, weight: FontWeight): TextStyle = TextStyle(
        fontFamily = family,
        fontWeight = weight,
        fontSize = (size + bump).sp,
        lineHeight = ((size + bump) * lineHeightFactor).sp,
    )

    return Typography(
        displayLarge = style(38, heavy), // kid title
        displayMedium = style(34, heavy),
        displaySmall = style(30, heavy),
        headlineLarge = style(34, heavy), // screen title
        headlineMedium = style(28, heavy),
        headlineSmall = style(24, heavy),
        titleLarge = style(22, heavy),
        titleMedium = style(17, FontWeight.Bold),
        titleSmall = style(15, FontWeight.Bold), // section label (colour it muted)
        bodyLarge = style(17, FontWeight.Medium),
        bodyMedium = style(16, FontWeight.Medium),
        bodySmall = style(14, FontWeight.Medium),
        labelLarge = style(18, heavy), // buttons
        labelMedium = style(14, FontWeight.Bold), // badge / caption
        labelSmall = style(13, FontWeight.Bold),
    )
}
