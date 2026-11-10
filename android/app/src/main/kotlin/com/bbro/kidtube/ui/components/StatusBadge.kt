package com.bbro.kidtube.ui.components

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.bbro.kidtube.R
import com.bbro.kidtube.ui.theme.KidTubeDimens
import com.bbro.kidtube.ui.theme.KidTubeTheme

enum class BadgeStatus(@StringRes val label: Int) {
    VERIFIED(R.string.badge_verified),
    PENDING(R.string.badge_pending),
    UNAVAILABLE(R.string.badge_unavailable),
}

/** Verified (primarySoft), Pending (sun-tinted), Unavailable (coral-tinted); text is always dark, never white. */
@Composable
fun StatusBadge(status: BadgeStatus, modifier: Modifier = Modifier) {
    val colors = KidTubeTheme.colors
    val (background: Color, text: Color) = when (status) {
        BadgeStatus.VERIFIED -> colors.primarySoft to colors.verifiedText
        BadgeStatus.PENDING -> colors.pendingBg to colors.pendingText
        BadgeStatus.UNAVAILABLE -> colors.errorBg to colors.errorText
    }
    Text(
        text = stringResource(status.label),
        style = MaterialTheme.typography.labelMedium,
        color = text,
        modifier = modifier
            .clip(CircleShape)
            .background(background)
            .padding(horizontal = 12.dp, vertical = 4.dp),
    )
}

@EnglishAndUrduPreviews
@Composable
private fun StatusBadgePreview() {
    PreviewSurface {
        Row(horizontalArrangement = Arrangement.spacedBy(KidTubeDimens.gap8)) {
            BadgeStatus.entries.forEach { StatusBadge(it) }
        }
    }
}
