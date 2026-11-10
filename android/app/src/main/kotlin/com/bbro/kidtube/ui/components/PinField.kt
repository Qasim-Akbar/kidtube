package com.bbro.kidtube.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bbro.kidtube.R
import com.bbro.kidtube.ui.theme.KidTubeDimens
import com.bbro.kidtube.ui.theme.KidTubeFonts
import com.bbro.kidtube.ui.theme.KidTubeShapes
import com.bbro.kidtube.ui.theme.KidTubeTheme

/**
 * Numeric PIN entry: digits only, masked, centred and widely spaced. The digits are always left-to-right Latin
 * numerals, even in the Urdu UI. [onValueChange] only receives digits, at most [length] of them.
 */
@Composable
fun PinField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    length: Int = DEFAULT_PIN_LENGTH,
    label: String? = null,
) {
    val colors = KidTubeTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val description = stringResource(R.string.pin_field_description, length)

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(KidTubeDimens.gap8)) {
        if (label != null) {
            Text(text = label, style = MaterialTheme.typography.titleSmall, color = colors.muted)
        }
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Surface(
                shape = KidTubeShapes.button,
                color = colors.card,
                border = if (focused) {
                    BorderStroke(KidTubeDimens.selectedBorderWidth, colors.primary)
                } else {
                    BorderStroke(KidTubeDimens.borderWidth, colors.line)
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                BasicTextField(
                    value = value,
                    onValueChange = { raw -> onValueChange(raw.filter(Char::isDigit).take(length)) },
                    singleLine = true,
                    interactionSource = interaction,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    textStyle = MaterialTheme.typography.headlineMedium.copy(
                        fontFamily = KidTubeFonts.Latin,
                        color = colors.ink,
                        textAlign = TextAlign.Center,
                        letterSpacing = 12.sp,
                    ),
                    cursorBrush = SolidColor(colors.primary),
                    modifier = Modifier.semantics { contentDescription = description },
                    decorationBox = { inner ->
                        Box(
                            modifier = Modifier
                                .heightIn(min = KidTubeDimens.buttonHeight)
                                .padding(horizontal = KidTubeDimens.gap16, vertical = KidTubeDimens.gap8),
                            contentAlignment = Alignment.Center,
                        ) { inner() }
                    },
                )
            }
        }
    }
}

const val DEFAULT_PIN_LENGTH = 4

@EnglishAndUrduPreviews
@Composable
private fun PinFieldPreview() {
    PreviewSurface {
        var pin by remember { mutableStateOf("12") }
        PinField(value = pin, onValueChange = { pin = it }, label = stringResource(R.string.pin_label))
    }
}
