package com.bbro.kidtube.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.bbro.kidtube.ui.theme.KidTubeTheme

/** Every component gets both previews: English, and Urdu (the `ur` locale also flips the layout to RTL). */
@Preview(name = "English", locale = "en", showBackground = true, backgroundColor = 0xFFFFF8EE)
@Preview(name = "Urdu RTL", locale = "ur", showBackground = true, backgroundColor = 0xFFFFF8EE)
annotation class EnglishAndUrduPreviews

@Composable
internal fun PreviewSurface(content: @Composable () -> Unit) {
    KidTubeTheme {
        Box(Modifier.background(KidTubeTheme.colors.ground).padding(16.dp)) { content() }
    }
}
