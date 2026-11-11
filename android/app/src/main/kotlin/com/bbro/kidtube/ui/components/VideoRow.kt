package com.bbro.kidtube.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.bbro.kidtube.ui.theme.KidTubeDimens
import com.bbro.kidtube.ui.theme.KidTubeShapes
import com.bbro.kidtube.ui.theme.KidTubeTheme

/**
 * A video thumbnail. The coloured placeholder (one of the five pastels, picked from [seed] so a video keeps its colour)
 * is always drawn underneath; the image is only requested when a [url] is known, and covers it once loaded.
 */
@Composable
fun VideoThumbnail(
    url: String?,
    seed: String,
    modifier: Modifier = Modifier,
    width: Dp = 96.dp,
    height: Dp = 64.dp,
) {
    val pastels = KidTubeTheme.colors.thumbnailPastels
    Box(
        modifier = modifier
            .size(width, height)
            .clip(KidTubeShapes.thumbnail)
            .background(pastels[Math.floorMod(seed.hashCode(), pastels.size)]),
    ) {
        if (url != null) {
            AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
        }
    }
}

/** One library row: 96x64 thumbnail, title, status badge, then who it is for and how it appears. */
@Composable
fun VideoRow(
    title: String,
    status: BadgeStatus,
    thumbnailUrl: String?,
    seed: String,
    modifier: Modifier = Modifier,
    detail: String? = null,
    note: String? = null,
) {
    val colors = KidTubeTheme.colors
    Surface(
        shape = KidTubeShapes.card,
        color = colors.card,
        border = BorderStroke(KidTubeDimens.borderWidth, colors.line),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(KidTubeDimens.gap12),
            horizontalArrangement = Arrangement.spacedBy(KidTubeDimens.gap12),
            verticalAlignment = Alignment.Top,
        ) {
            VideoThumbnail(url = thumbnailUrl, seed = seed)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.ink,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                StatusBadge(status)
                if (detail != null) {
                    Text(text = detail, style = MaterialTheme.typography.bodySmall, color = colors.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                if (note != null) {
                    Text(text = note, style = MaterialTheme.typography.labelMedium, color = colors.pendingText)
                }
            }
        }
    }
}

@EnglishAndUrduPreviews
@Composable
private fun VideoRowPreview() {
    PreviewSurface {
        Column(verticalArrangement = Arrangement.spacedBy(KidTubeDimens.gap8)) {
            VideoRow(title = "Counting to ten", status = BadgeStatus.VERIFIED, thumbnailUrl = null, seed = "a", detail = "Ali · Once a day")
            VideoRow(title = "youtu.be/abc", status = BadgeStatus.PENDING, thumbnailUrl = null, seed = "b", detail = "Sara", note = "Waiting to send")
        }
    }
}
