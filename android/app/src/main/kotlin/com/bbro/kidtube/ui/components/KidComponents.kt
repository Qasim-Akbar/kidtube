package com.bbro.kidtube.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.bbro.kidtube.R
import com.bbro.kidtube.ui.children.AvatarColor
import com.bbro.kidtube.ui.theme.KidTubeDimens
import com.bbro.kidtube.ui.theme.KidTubeShapes
import com.bbro.kidtube.ui.theme.KidTubeTheme

// Kid-mode components from docs/DESIGN.md: KidAvatar, KidTile, LeftTodayDots, TimeLeftBar (+ the face used by status screens).
// Flat colour, big rounded shapes, no gradients, no emoji, no text on pastels except ink.

/** A simple face (two eyes and a smile) on a filled circle: the child's avatar, or the yellow "all done" face. */
@Composable
fun FaceCircle(color: Color, modifier: Modifier = Modifier, size: Dp = 72.dp, happy: Boolean = true) {
    val ink = KidTubeTheme.colors.ink
    Canvas(modifier.size(size)) {
        val w = this.size.width
        drawCircle(color)
        drawCircle(ink, radius = w * 0.06f, center = Offset(w * 0.34f, w * 0.42f))
        drawCircle(ink, radius = w * 0.06f, center = Offset(w * 0.66f, w * 0.42f))
        val mouth = Size(w * 0.36f, w * 0.26f)
        drawArc(
            color = ink, startAngle = if (happy) 20f else 200f, sweepAngle = 140f, useCenter = false,
            topLeft = Offset(w * 0.32f, if (happy) w * 0.50f else w * 0.58f), size = mouth,
            style = Stroke(width = w * 0.055f, cap = StrokeCap.Round),
        )
    }
}

/** The child's avatar. [contentDescription] is the child's name (null when the name is shown next to it). */
@Composable
fun KidAvatar(color: AvatarColor?, modifier: Modifier = Modifier, size: Dp = 72.dp, contentDescription: String? = null) {
    val fill = (color ?: AvatarColor.CORAL).color(KidTubeTheme.colors)
    FaceCircle(
        fill,
        modifier.then(if (contentDescription != null) Modifier.semantics { this.contentDescription = contentDescription } else Modifier.clearAndSetSemantics { }),
        size,
    )
}

/** A clock face for the "Time's up" screen: white dial, ink hands. */
@Composable
fun ClockIcon(modifier: Modifier = Modifier, size: Dp = 140.dp) {
    val ink = KidTubeTheme.colors.ink
    Canvas(modifier.size(size).clearAndSetSemantics { }) {
        val w = this.size.width
        val c = Offset(w / 2, w / 2)
        drawCircle(Color.White)
        drawCircle(ink, radius = w / 2 - w * 0.03f, center = c, style = Stroke(width = w * 0.06f))
        val stroke = Stroke(width = w * 0.06f, cap = StrokeCap.Round)
        drawLine(ink, c, Offset(c.x, c.y - w * 0.28f), strokeWidth = stroke.width, cap = StrokeCap.Round)
        drawLine(ink, c, Offset(c.x + w * 0.20f, c.y + w * 0.10f), strokeWidth = stroke.width, cap = StrokeCap.Round)
    }
}

/**
 * One video: pastel placeholder + thumbnail, a white play circle, an optional "First" tag (top-start) and a check mark
 * (top-end) once the video was viewed today. The accessibility description is the video title.
 */
@Composable
fun KidTile(
    title: String?,
    thumbnailUrl: String?,
    seed: String,
    viewedToday: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isFirst: Boolean = false,
) {
    val colors = KidTubeTheme.colors
    val description = title ?: stringResource(R.string.kid_video_untitled)
    val watched = stringResource(R.string.kid_watched_today)
    Box(
        modifier = modifier
            .clip(KidTubeShapes.kidTile)
            .background(kidTileColor(seed, colors.thumbnailPastels, colors.kidGround))
            .clickable(onClick = onClick)
            .semantics(mergeDescendants = true) {
                contentDescription = description
                role = Role.Button
                if (viewedToday) stateDescription = watched
            },
    ) {
        if (thumbnailUrl != null) {
            AsyncImage(model = thumbnailUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
        }
        PlayCircle(Modifier.align(Alignment.Center))
        if (isFirst) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(KidTubeDimens.gap12)
                    .clip(CircleShape)
                    .background(colors.sun)
                    .padding(horizontal = KidTubeDimens.gap12, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                StarShape(colors.ink)
                Text(stringResource(R.string.kid_first_tag), style = MaterialTheme.typography.labelLarge, color = colors.ink)
            }
        }
        if (viewedToday) {
            CheckBadge(Modifier.align(Alignment.TopEnd).padding(KidTubeDimens.gap12))
        }
    }
}

@Composable
private fun PlayCircle(modifier: Modifier = Modifier) {
    val ink = KidTubeTheme.colors.ink
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Canvas(modifier.size(64.dp).clearAndSetSemantics { }) {
        val w = size.width
        drawCircle(Color.White)
        // The play triangle is mirrored in the Urdu (RTL) layouts, as in the mockups.
        scale(if (rtl) -1f else 1f, 1f, pivot = center) {
            val path = Path().apply {
                moveTo(w * 0.40f, w * 0.30f)
                lineTo(w * 0.72f, w * 0.50f)
                lineTo(w * 0.40f, w * 0.70f)
                close()
            }
            drawPath(path, ink)
        }
    }
}

@Composable
private fun StarShape(color: Color) {
    Canvas(Modifier.size(16.dp)) {
        val r = size.width / 2
        val path = Path()
        for (i in 0 until 10) {
            val radius = if (i % 2 == 0) r else r * 0.45f
            val angle = Math.toRadians((-90 + i * 36).toDouble())
            val p = Offset(center.x + (radius * Math.cos(angle)).toFloat(), center.y + (radius * Math.sin(angle)).toFloat())
            if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
        }
        path.close()
        drawPath(path, color)
    }
}

@Composable
private fun CheckBadge(modifier: Modifier = Modifier) {
    val colors = KidTubeTheme.colors
    Canvas(modifier.size(36.dp).clearAndSetSemantics { }) {
        val w = size.width
        drawCircle(colors.primary)
        val path = Path().apply {
            moveTo(w * 0.28f, w * 0.52f)
            lineTo(w * 0.44f, w * 0.68f)
            lineTo(w * 0.74f, w * 0.34f)
        }
        drawPath(path, Color.White, style = Stroke(width = w * 0.09f, cap = StrokeCap.Round))
    }
}

/** Placeholder colour for a kid tile: like [thumbnailColor] but never the pastel that equals the kid background (the tile would vanish). */
fun kidTileColor(seed: String, pastels: List<Color>, background: Color): Color = thumbnailColor(seed, pastels.filter { it != background })

/** Placeholder colour for [seed]; same choice as [VideoThumbnail] so a video keeps its colour everywhere. */
fun thumbnailColor(seed: String, pastels: List<Color>): Color = pastels[Math.floorMod(seed.hashCode(), pastels.size)]

const val DOT_FILLED_TAG = "left_today_dot_filled"
const val DOT_EMPTY_TAG = "left_today_dot_empty"

/** "Left today" dots: [left] filled dots (still allowed today) out of [total]. */
@Composable
fun LeftTodayDots(total: Int, left: Int, modifier: Modifier = Modifier) {
    val colors = KidTubeTheme.colors
    val description = pluralStringResource(R.plurals.kid_left_today, left, left)
    Row(
        modifier = modifier.semantics(mergeDescendants = true) { contentDescription = description },
        horizontalArrangement = Arrangement.spacedBy(KidTubeDimens.gap8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(total) { i ->
            Box(
                Modifier.size(20.dp).clip(CircleShape)
                    .testTag(if (i < left) DOT_FILLED_TAG else DOT_EMPTY_TAG)
                    .then(if (i < left) Modifier.background(colors.primary) else Modifier.border(3.dp, colors.primary, CircleShape)),
            )
        }
    }
}

/** Minutes left of the daily app-time limit: a bar (fraction left) and a short label. */
@Composable
fun TimeLeftBar(secondsLeft: Int, limitSeconds: Int, modifier: Modifier = Modifier) {
    val colors = KidTubeTheme.colors
    val minutes = (secondsLeft + 59) / 60
    val label = pluralStringResource(R.plurals.kid_time_left, minutes, minutes)
    val fraction = if (limitSeconds <= 0) 0f else (secondsLeft.toFloat() / limitSeconds).coerceIn(0f, 1f)
    Column(
        modifier = modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = label },
        verticalArrangement = Arrangement.spacedBy(KidTubeDimens.gap8),
    ) {
        LatinAwareText(label, style = MaterialTheme.typography.labelLarge, color = colors.ink)
        Box(Modifier.fillMaxWidth().height(14.dp).clip(CircleShape).background(colors.card)) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(fraction).clip(CircleShape).background(colors.primary))
        }
    }
}

@EnglishAndUrduPreviews
@Composable
private fun KidComponentsPreview() {
    PreviewSurface {
        Column(verticalArrangement = Arrangement.spacedBy(KidTubeDimens.gap12)) {
            Row(horizontalArrangement = Arrangement.spacedBy(KidTubeDimens.gap12), verticalAlignment = Alignment.CenterVertically) {
                KidAvatar(AvatarColor.CORAL, contentDescription = "Ali")
                LeftTodayDots(total = 3, left = 2)
            }
            KidTile("Counting to ten", null, "a", viewedToday = true, onClick = {}, isFirst = true, modifier = Modifier.fillMaxWidth().height(180.dp))
            TimeLeftBar(secondsLeft = 780, limitSeconds = 1800)
        }
    }
}
