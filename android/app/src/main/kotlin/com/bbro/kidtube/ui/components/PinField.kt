package com.bbro.kidtube.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.bbro.kidtube.R
import com.bbro.kidtube.ui.theme.KidTubeDimens
import com.bbro.kidtube.ui.theme.KidTubeTheme

/** Size of one PIN dot. */
private val PIN_DOT = 26.dp

/**
 * Numeric PIN entry drawn as [length] outlined circles that fill in as digits are typed (no digit is ever shown). The
 * circles always run left to right, also in the Urdu UI. A hidden text field underneath takes the numeric keyboard input;
 * tapping the circles focuses it. [onValueChange] only receives digits, at most [length] of them.
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
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val description = stringResource(R.string.pin_field_description, length)

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(KidTubeDimens.gap8)) {
        if (label != null) {
            Text(text = label, style = MaterialTheme.typography.titleSmall, color = colors.muted)
        }
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            BasicTextField(
                value = value,
                onValueChange = { raw -> onValueChange(raw.filter(Char::isDigit).take(length)) },
                singleLine = true,
                interactionSource = interaction,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                textStyle = MaterialTheme.typography.headlineMedium.copy(color = Color.Transparent),
                cursorBrush = SolidColor(Color.Transparent),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .semantics { contentDescription = description },
                decorationBox = {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = KidTubeDimens.buttonHeight)
                            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                                focusRequester.requestFocus()
                                keyboard?.show()
                            },
                        horizontalArrangement = Arrangement.spacedBy(KidTubeDimens.gap20, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        repeat(length) { index ->
                            val filled = index < value.length
                            val active = focused && index == value.length.coerceAtMost(length - 1)
                            Row(
                                modifier = Modifier
                                    .size(PIN_DOT)
                                    .clip(CircleShape)
                                    .background(if (filled) colors.ink else Color.Transparent)
                                    .border(
                                        if (active) KidTubeDimens.selectedBorderWidth else KidTubeDimens.borderWidth,
                                        if (active) colors.primary else colors.ink,
                                        CircleShape,
                                    ),
                            ) {}
                        }
                    }
                },
            )
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
