package com.bbro.kidtube.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
    height: Dp = 54.dp, // 16:9
) {
    val pastels = KidTubeTheme.colors.thumbnailPastels
    Box(
        modifier = modifier
            .size(width, height)
            .clip(KidTubeShapes.thumbnail)
            .background(pastels[Math.floorMod(seed.hashCode(), pastels.size)]),
    ) {
        if (url != null) CroppedThumbnail(url, Modifier.matchParentSize())
    }
}

/**
 * The image is drawn in a fixed 16:9 box (Crop) that covers the parent and is centred in it, so the black letterbox bars YouTube
 * bakes into the 4:3 `hqdefault` picture are cut off whatever size the parent has (the parent clips the overflow).
 */
@Composable
fun CroppedThumbnail(url: String, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val boxWidth = maxOf(maxWidth, maxHeight * (16f / 9f))
        AsyncImage(
            model = url,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.requiredSize(boxWidth, boxWidth * (9f / 16f)),
        )
    }
}

/** One library row: 96x54 (16:9) thumbnail, title, status badge, then who it is for and how it appears. */
@Composable
fun VideoRow(
    title: String,
    status: BadgeStatus,
    thumbnailUrl: String?,
    seed: String,
    modifier: Modifier = Modifier,
    detail: String? = null,
    note: String? = null,
    /** A second, muted line (e.g. "Parents only for now"). */
    extraNote: String? = null,
    /** A small text button under the notes (e.g. "Remove"); shown only when both are given. */
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
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
                LatinAwareText(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.ink,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                StatusBadge(status)
                if (detail != null) {
                    LatinAwareText(text = detail, style = MaterialTheme.typography.bodySmall, color = colors.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                if (extraNote != null) {
                    LatinAwareText(text = extraNote, style = MaterialTheme.typography.labelMedium, color = colors.muted)
                }
                if (note != null) {
                    LatinAwareText(text = note, style = MaterialTheme.typography.labelMedium, color = colors.pendingText)
                }
                if (actionLabel != null && onAction != null) {
                    TextButton(onClick = onAction, contentPadding = PaddingValues(horizontal = 0.dp)) {
                        LatinAwareText(text = actionLabel, style = MaterialTheme.typography.labelLarge, color = colors.primary)
                    }
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
