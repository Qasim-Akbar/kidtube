package com.bbro.kidtube.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.bbro.kidtube.R
import com.bbro.kidtube.ui.theme.KidTubeDimens
import com.bbro.kidtube.ui.theme.KidTubeShapes
import com.bbro.kidtube.ui.theme.KidTubeTheme

/** The one big button: 60dp, 20dp corners, white on primary. Disabled = line fill with muted text. */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = KidTubeTheme.colors
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = KidTubeShapes.button,
        colors = ButtonDefaults.buttonColors(
            containerColor = colors.primary,
            contentColor = colors.onPrimary,
            disabledContainerColor = colors.line,
            disabledContentColor = colors.muted,
        ),
        modifier = modifier.fillMaxWidth().heightIn(min = KidTubeDimens.buttonHeight),
    ) {
        Text(text = text, style = MaterialTheme.typography.labelLarge)
    }
}

@EnglishAndUrduPreviews
@Composable
private fun PrimaryButtonPreview() {
    PreviewSurface {
        PrimaryButton(text = stringResource(R.string.setup_parent_title), onClick = {})
    }
}
