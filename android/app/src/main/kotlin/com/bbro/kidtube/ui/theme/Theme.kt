package com.bbro.kidtube.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration

/** Access to the design tokens: `KidTubeTheme.colors.ground`. */
object KidTubeTheme {
    val colors: KidTubeColors
        @Composable
        @ReadOnlyComposable
        get() = LocalKidTubeColors.current
}

/** True when the current locale is Urdu (drives the font family, size and line height). */
@Composable
@ReadOnlyComposable
fun isUrduLocale(): Boolean = LocalConfiguration.current.locales.let { it.size() > 0 && it[0].language == "ur" }

/** Light theme only (docs/DESIGN.md: no dark mode in v1, no dynamic colour). Fonts switch with the locale. */
@Composable
fun KidTubeTheme(content: @Composable () -> Unit) {
    val colors = remember { KidTubeColors() }
    val isUrdu = isUrduLocale()
    val scheme = lightColorScheme(
        primary = colors.primary,
        onPrimary = colors.onPrimary,
        primaryContainer = colors.primarySoft,
        onPrimaryContainer = colors.verifiedText,
        secondary = colors.ink,
        onSecondary = colors.card,
        tertiary = colors.sun,
        onTertiary = colors.ink,
        background = colors.ground,
        onBackground = colors.ink,
        surface = colors.ground,
        onSurface = colors.ink,
        surfaceVariant = colors.card,
        onSurfaceVariant = colors.muted,
        surfaceContainerLowest = colors.card,
        surfaceContainerLow = colors.card,
        surfaceContainer = colors.card,
        surfaceContainerHigh = colors.card,
        surfaceContainerHighest = colors.card,
        outline = colors.line,
        outlineVariant = colors.line,
        error = colors.errorText,
        onError = colors.card,
        errorContainer = colors.errorBg,
        onErrorContainer = colors.errorText,
    )
    val typography = remember(isUrdu) { kidTubeTypography(isUrdu) }
    CompositionLocalProvider(LocalKidTubeColors provides colors) {
        MaterialTheme(
            colorScheme = scheme,
            typography = typography,
            shapes = KidTubeMaterialShapes,
            content = content,
        )
    }
}
