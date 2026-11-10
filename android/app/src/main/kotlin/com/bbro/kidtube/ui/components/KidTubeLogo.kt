package com.bbro.kidtube.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.bbro.kidtube.R
import com.bbro.kidtube.ui.theme.KidTubeDimens
import com.bbro.kidtube.ui.theme.KidTubeFonts
import com.bbro.kidtube.ui.theme.KidTubeTheme

/** Coral rounded square with an ink play triangle, plus the "KidTube" wordmark (always Latin, always LTR). */
@Composable
fun KidTubeLogo(modifier: Modifier = Modifier) {
    val colors = KidTubeTheme.colors
    val description = stringResource(R.string.logo_description)
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Row(
            modifier = modifier.semantics { contentDescription = description },
            horizontalArrangement = Arrangement.spacedBy(KidTubeDimens.gap12),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Canvas(Modifier.size(48.dp).clearAndSetSemantics { }) {
                drawRoundRect(color = colors.coral, cornerRadius = CornerRadius(size.minDimension * 0.3f))
                val w = size.width
                val h = size.height
                val triangle = Path().apply {
                    moveTo(w * 0.40f, h * 0.30f)
                    lineTo(w * 0.72f, h * 0.50f)
                    lineTo(w * 0.40f, h * 0.70f)
                    close()
                }
                drawPath(triangle, color = colors.ink)
            }
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineSmall.copy(fontFamily = KidTubeFonts.Latin),
                color = colors.ink,
                modifier = Modifier.clearAndSetSemantics { },
            )
        }
    }
}

@EnglishAndUrduPreviews
@Composable
private fun KidTubeLogoPreview() {
    PreviewSurface { KidTubeLogo() }
}
