package com.bbro.kidtube.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.bbro.kidtube.R
import com.bbro.kidtube.ui.theme.KidTubeDimens
import com.bbro.kidtube.ui.theme.KidTubeFonts
import com.bbro.kidtube.ui.theme.KidTubeShapes
import com.bbro.kidtube.ui.theme.KidTubeTheme

/**
 * Labelled single-line text field in the KidTube style (white card, 2dp line border, 3dp primary when focused,
 * coral-tinted border on error). Emails, passwords and URLs are always typed left-to-right ([latinInput]).
 */
@Composable
fun KidTubeTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Next,
    onImeAction: () -> Unit = {},
    password: Boolean = false,
    latinInput: Boolean = true,
    error: String? = null,
    placeholder: String? = null,
    enabled: Boolean = true,
) {
    val colors = KidTubeTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val borderColor = when {
        error != null -> colors.errorText
        focused -> colors.primary
        else -> colors.line
    }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(KidTubeDimens.gap8)) {
        Text(text = label, style = MaterialTheme.typography.titleSmall, color = colors.muted)
        Surface(
            shape = KidTubeShapes.button,
            color = colors.card,
            border = BorderStroke(if (focused || error != null) KidTubeDimens.selectedBorderWidth else KidTubeDimens.borderWidth, borderColor),
            modifier = Modifier.fillMaxWidth(),
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                enabled = enabled,
                singleLine = true,
                interactionSource = interaction,
                visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (password) KeyboardType.Password else keyboardType,
                    imeAction = imeAction,
                    autoCorrectEnabled = false,
                ),
                keyboardActions = KeyboardActions(onAny = { onImeAction() }),
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    color = colors.ink,
                    textDirection = if (latinInput) TextDirection.Ltr else TextDirection.Content,
                ),
                cursorBrush = SolidColor(colors.primary),
                decorationBox = { inner ->
                    Box(
                        modifier = Modifier
                            .heightIn(min = KidTubeDimens.buttonHeight)
                            .padding(horizontal = KidTubeDimens.gap16, vertical = KidTubeDimens.gap8),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        if (value.isEmpty() && placeholder != null) {
                            Text(
                                text = placeholder,
                                style = MaterialTheme.typography.bodyLarge.copy(
                                    textDirection = if (latinInput) TextDirection.Ltr else TextDirection.Content,
                                ),
                                color = colors.muted.copy(alpha = 0.7f),
                            )
                        }
                        inner()
                    }
                },
            )
        }
        if (error != null) {
            Text(text = error, style = MaterialTheme.typography.labelMedium, color = colors.errorText)
        }
    }
}

/**
 * One box per digit (default 6) for pairing codes: large, numeric keyboard, digits always left-to-right.
 * [onValueChange] only receives digits, at most [length] of them.
 */
@Composable
fun CodeField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    length: Int = 6,
    label: String? = null,
    description: String? = null,
    autoFocus: Boolean = false,
    onDone: () -> Unit = {},
) {
    val colors = KidTubeTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(autoFocus) { if (autoFocus) focusRequester.requestFocus() }

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
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onDone() }),
                textStyle = MaterialTheme.typography.headlineMedium.copy(color = Color.Transparent),
                cursorBrush = SolidColor(Color.Transparent),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .semantics { if (description != null) contentDescription = description },
                decorationBox = {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                                focusRequester.requestFocus()
                                keyboard?.show()
                            },
                        horizontalArrangement = Arrangement.spacedBy(KidTubeDimens.gap8),
                    ) {
                        repeat(length) { index ->
                            val active = focused && index == value.length.coerceAtMost(length - 1)
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 72.dp)
                                    .clip(KidTubeShapes.button)
                                    .background(colors.card)
                                    .border(
                                        if (active) KidTubeDimens.selectedBorderWidth else KidTubeDimens.borderWidth,
                                        if (active) colors.primary else colors.line,
                                        KidTubeShapes.button,
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = value.getOrNull(index)?.toString().orEmpty(),
                                    style = MaterialTheme.typography.headlineMedium.copy(
                                        fontFamily = KidTubeFonts.Latin,
                                        textAlign = TextAlign.Center,
                                    ),
                                    color = colors.ink,
                                )
                            }
                        }
                    }
                },
            )
        }
    }
}

/** A short message in a tinted box. [isError] uses the coral error tint; otherwise the soft primary tint. */
@Composable
fun MessageBanner(text: String, modifier: Modifier = Modifier, isError: Boolean = true) {
    val colors = KidTubeTheme.colors
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (isError) colors.errorText else colors.verifiedText,
        modifier = modifier
            .fillMaxWidth()
            .clip(KidTubeShapes.button)
            .background(if (isError) colors.errorBg else colors.primarySoft)
            .padding(KidTubeDimens.gap16)
            .semantics { liveRegion = LiveRegionMode.Polite },
    )
}

/** Back arrow that mirrors in RTL. */
@Composable
fun BackButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(onClick = onClick, modifier = modifier.size(KidTubeDimens.minTouchTarget)) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = stringResource(R.string.action_back),
            tint = KidTubeTheme.colors.ink,
        )
    }
}

@EnglishAndUrduPreviews
@Composable
private fun FormComponentsPreview() {
    PreviewSurface {
        Column(verticalArrangement = Arrangement.spacedBy(KidTubeDimens.gap16)) {
            KidTubeTextField(
                label = stringResource(R.string.field_email),
                value = "mum@example.com",
                onValueChange = {},
            )
            KidTubeTextField(
                label = stringResource(R.string.field_password),
                value = "secret123",
                onValueChange = {},
                password = true,
                error = stringResource(R.string.error_wrong_password),
            )
            CodeField(value = "123", onValueChange = {}, label = stringResource(R.string.pairing_code_label))
            MessageBanner(stringResource(R.string.error_no_connection))
            BackButton(onClick = {})
        }
    }
}
