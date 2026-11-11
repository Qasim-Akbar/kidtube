package com.bbro.kidtube.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.bbro.kidtube.R
import com.bbro.kidtube.ui.theme.KidTubeDimens
import com.bbro.kidtube.ui.theme.KidTubeShapes
import com.bbro.kidtube.ui.theme.KidTubeTheme

/** Fully rounded chip. Selected: primarySoft fill and a 3dp primary border; unselected: white with a 2dp line border. */
@Composable
fun Chip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    role: Role = Role.Checkbox,
) {
    val colors = KidTubeTheme.colors
    Surface(
        shape = KidTubeShapes.chip,
        color = if (selected) colors.primarySoft else colors.card,
        contentColor = colors.ink,
        border = if (selected) {
            BorderStroke(KidTubeDimens.selectedBorderWidth, colors.primary)
        } else {
            BorderStroke(KidTubeDimens.borderWidth, colors.line)
        },
        modifier = modifier.toggleable(value = selected, onValueChange = { onClick() }, role = role),
    ) {
        LatinAwareText(
            text = text,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier
                .defaultMinSize(minHeight = KidTubeDimens.minTouchTarget)
                .padding(horizontal = 18.dp, vertical = 10.dp),
        )
    }
}

@EnglishAndUrduPreviews
@Composable
private fun ChipPreview() {
    PreviewSurface {
        Row(horizontalArrangement = Arrangement.spacedBy(KidTubeDimens.gap8), verticalAlignment = Alignment.CenterVertically) {
            Chip(text = stringResource(R.string.preset_once_a_day), selected = true, onClick = {})
            Chip(text = stringResource(R.string.preset_three_times), selected = false, onClick = {})
        }
    }
}
