package com.bbro.kidtube.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.bbro.kidtube.ui.theme.KidTubeDimens
import com.bbro.kidtube.ui.theme.KidTubeShapes
import com.bbro.kidtube.ui.theme.KidTubeTheme

/**
 * Two to four equal segments in one rounded frame (Day / Week / Month). The selected segment is filled with the primary colour
 * and white text; each segment is at least 48dp high. Radio-button semantics. Mirrors in RTL because it is a plain Row.
 */
@Composable
fun SegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    tagPrefix: String = "segment",
) {
    val colors = KidTubeTheme.colors
    Surface(
        shape = KidTubeShapes.chip,
        color = colors.card,
        border = BorderStroke(KidTubeDimens.borderWidth, colors.line),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row {
            options.forEachIndexed { index, text ->
                val selected = index == selectedIndex
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = KidTubeDimens.minTouchTarget)
                        .background(if (selected) colors.primary else colors.card)
                        .selectable(selected = selected, role = Role.RadioButton, onClick = { onSelect(index) })
                        .testTag("${tagPrefix}_$index"),
                ) {
                    LatinAwareText(
                        text = text,
                        style = MaterialTheme.typography.titleMedium,
                        color = if (selected) colors.onPrimary else colors.ink,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
                    )
                }
            }
        }
    }
}

@EnglishAndUrduPreviews
@Composable
private fun SegmentedControlPreview() {
    PreviewSurface {
        SegmentedControl(options = listOf("Day", "Week", "Month"), selectedIndex = 1, onSelect = {})
    }
}
