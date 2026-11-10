package com.bbro.kidtube.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontFamily
import com.bbro.kidtube.R
import com.bbro.kidtube.core.AppLanguage
import com.bbro.kidtube.ui.theme.KidTubeDimens
import com.bbro.kidtube.ui.theme.KidTubeFonts
import com.bbro.kidtube.ui.theme.KidTubeTheme

/**
 * "English | اردو". Always laid out left-to-right (even in the Urdu UI) so the two options never swap places when the
 * language changes; each name is drawn in its own font.
 */
@Composable
fun LanguageToggle(
    selected: AppLanguage,
    onSelected: (AppLanguage) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = KidTubeTheme.colors
    val description = stringResource(R.string.language_toggle_description)
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Row(
            modifier = modifier
                .clip(CircleShape)
                .background(colors.card)
                .border(KidTubeDimens.borderWidth, colors.line, CircleShape)
                .padding(4.dp)
                .selectableGroup()
                .semantics { contentDescription = description },
        ) {
            LanguageSegment(
                label = stringResource(R.string.language_english),
                font = KidTubeFonts.Latin,
                selected = selected == AppLanguage.ENGLISH,
                onClick = { onSelected(AppLanguage.ENGLISH) },
            )
            LanguageSegment(
                label = stringResource(R.string.language_urdu),
                font = KidTubeFonts.Urdu,
                selected = selected == AppLanguage.URDU,
                onClick = { onSelected(AppLanguage.URDU) },
            )
        }
    }
}

@Composable
private fun LanguageSegment(label: String, font: FontFamily, selected: Boolean, onClick: () -> Unit) {
    val colors = KidTubeTheme.colors
    Box(
        modifier = Modifier
            .defaultMinSize(minHeight = KidTubeDimens.minTouchTarget, minWidth = 80.dp)
            .clip(CircleShape)
            .background(if (selected) colors.primary else colors.card)
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium.copy(fontFamily = font),
            color = if (selected) colors.onPrimary else colors.ink,
        )
    }
}

@EnglishAndUrduPreviews
@Composable
private fun LanguageTogglePreview() {
    PreviewSurface {
        var language by remember { mutableStateOf(AppLanguage.ENGLISH) }
        LanguageToggle(selected = language, onSelected = { language = it })
    }
}
