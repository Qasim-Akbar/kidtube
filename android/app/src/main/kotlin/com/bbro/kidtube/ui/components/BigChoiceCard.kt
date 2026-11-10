package com.bbro.kidtube.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bbro.kidtube.R
import com.bbro.kidtube.ui.theme.KidTubeDimens
import com.bbro.kidtube.ui.theme.KidTubeShapes
import com.bbro.kidtube.ui.theme.KidTubeTheme

/**
 * A big tappable card with an icon tile, a title and a description (Setup: "Parent phone" / "Kid's phone").
 * The icon tile sits at the start, so the whole row mirrors in RTL.
 */
@Composable
fun BigChoiceCard(
    title: String,
    description: String,
    icon: ImageVector,
    iconBackground: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    minHeight: Dp = BigChoiceCardMinHeight,
) {
    val colors = KidTubeTheme.colors
    Surface(
        shape = KidTubeShapes.card,
        color = colors.card,
        contentColor = colors.ink,
        border = if (selected) {
            BorderStroke(KidTubeDimens.selectedBorderWidth, colors.primary)
        } else {
            BorderStroke(KidTubeDimens.borderWidth, colors.line)
        },
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = minHeight)
            .clip(KidTubeShapes.card)
            .clickable(role = Role.Button, onClick = onClick),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(KidTubeDimens.gap20),
            horizontalArrangement = Arrangement.spacedBy(KidTubeDimens.gap16),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(KidTubeShapes.button)
                    .background(iconBackground),
                contentAlignment = Alignment.Center,
            ) {
                Icon(imageVector = icon, contentDescription = null, tint = colors.ink, modifier = Modifier.size(40.dp))
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(text = title, style = MaterialTheme.typography.titleLarge)
                Text(text = description, style = MaterialTheme.typography.bodyMedium, color = colors.muted)
            }
        }
    }
}

@EnglishAndUrduPreviews
@Composable
private fun BigChoiceCardPreview() {
    PreviewSurface {
        Column(verticalArrangement = Arrangement.spacedBy(KidTubeDimens.gap16)) {
            BigChoiceCard(
                title = stringResource(R.string.setup_parent_title),
                description = stringResource(R.string.setup_parent_desc),
                icon = Icons.Filled.Person,
                iconBackground = KidTubeTheme.colors.primarySoft,
                onClick = {},
            )
            BigChoiceCard(
                title = stringResource(R.string.setup_kid_title),
                description = stringResource(R.string.setup_kid_desc),
                icon = Icons.Filled.Face,
                iconBackground = KidTubeTheme.colors.sun,
                onClick = {},
                selected = true,
            )
        }
    }
}

/** Every BigChoiceCard is at least this tall, so cards in a list match whatever their text length. */
val BigChoiceCardMinHeight: Dp = 120.dp
