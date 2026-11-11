package com.bbro.kidtube.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.bbro.kidtube.R
import com.bbro.kidtube.ui.theme.KidTubeDimens
import com.bbro.kidtube.ui.theme.KidTubeShapes
import com.bbro.kidtube.ui.theme.KidTubeTheme

/**
 * The one big button: 60dp, 20dp corners, white on primary. Disabled = line fill with muted text.
 * While [loading] the label is replaced by a spinner INSIDE the button: it keeps its size and colour and cannot be pressed,
 * and nothing around it moves.
 */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
) {
    val colors = KidTubeTheme.colors
    val busyDescription = stringResource(R.string.loading_description)
    Button(
        onClick = onClick,
        enabled = enabled && !loading,
        shape = KidTubeShapes.button,
        colors = ButtonDefaults.buttonColors(
            containerColor = colors.primary,
            contentColor = colors.onPrimary,
            disabledContainerColor = if (loading) colors.primary else colors.line,
            disabledContentColor = if (loading) colors.onPrimary else colors.muted,
        ),
        modifier = modifier.fillMaxWidth().heightIn(min = KidTubeDimens.buttonHeight),
    ) {
        if (loading) {
            CircularProgressIndicator(
                color = colors.onPrimary,
                strokeWidth = 3.dp,
                modifier = Modifier.size(28.dp).semantics { contentDescription = busyDescription },
            )
        } else {
            LatinAwareText(text = text, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@EnglishAndUrduPreviews
@Composable
private fun PrimaryButtonPreview() {
    PreviewSurface {
        PrimaryButton(text = stringResource(R.string.setup_parent_title), onClick = {})
    }
}
