package com.bbro.kidtube.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.bbro.kidtube.R
import com.bbro.kidtube.ui.theme.KidTubeDimens
import com.bbro.kidtube.ui.theme.KidTubeTheme

/**
 * A minus / value / plus control for small whole numbers (no free typing). Both buttons are 48dp round targets, always laid
 * out left-to-right order of "less, value, more" mirrored by the layout direction like any Row. [valueText] is already formatted.
 * Test tags: [testTag] + `_minus`, `_plus`, `_value`.
 */
@Composable
fun Stepper(
    valueText: String,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
    modifier: Modifier = Modifier,
    canDecrease: Boolean = true,
    canIncrease: Boolean = true,
    testTag: String = "stepper",
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(KidTubeDimens.gap8)) {
        StepButton("−", stringResource(R.string.plans_stepper_decrease), canDecrease, onDecrease, Modifier.testTag("${testTag}_minus"))
        LatinAwareText(
            text = valueText,
            style = MaterialTheme.typography.titleMedium,
            color = KidTubeTheme.colors.ink,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f, fill = false).defaultMinSize(minWidth = 96.dp).testTag("${testTag}_value"),
        )
        StepButton("+", stringResource(R.string.plans_stepper_increase), canIncrease, onIncrease, Modifier.testTag("${testTag}_plus"))
    }
}

@Composable
private fun StepButton(glyph: String, description: String, enabled: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val colors = KidTubeTheme.colors
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        color = if (enabled) colors.primarySoft else colors.card,
        contentColor = if (enabled) colors.ink else colors.muted,
        border = BorderStroke(KidTubeDimens.borderWidth, if (enabled) colors.primary else colors.line),
        modifier = modifier.size(KidTubeDimens.minTouchTarget).semantics { contentDescription = description; role = Role.Button },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(glyph, style = MaterialTheme.typography.headlineSmall)
        }
    }
}
