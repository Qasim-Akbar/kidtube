package com.bbro.kidtube.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.bbro.kidtube.ui.theme.KidTubeFonts
import com.bbro.kidtube.ui.theme.isUrduLocale

/**
 * A run of Latin text inside a string: starts with a Latin letter or digit and continues over printable ASCII up to the
 * next space or non-ASCII character. That covers names (Ali, Sara), brands (YouTube), numbers (3, 489) and links.
 */
private val LATIN_RUN = Regex("""[A-Za-z0-9][!-~]*""")

/**
 * [text] as an [AnnotatedString] in which every Latin run is drawn in [latin] (Nunito). Noto Naskh Arabic also has Latin
 * glyphs, but a serif-looking set that clashes with the rest of the app, so inside Urdu text they are overridden.
 */
fun latinRunsAnnotated(text: String, latin: FontFamily = KidTubeFonts.Latin): AnnotatedString = buildAnnotatedString {
    append(text)
    LATIN_RUN.findAll(text).forEach { run ->
        addStyle(SpanStyle(fontFamily = latin), run.range.first, run.range.last + 1)
    }
}

/** Where the Latin runs of [text] are (start inclusive, end exclusive). Exposed for tests. */
fun latinRunRanges(text: String): List<IntRange> = LATIN_RUN.findAll(text).map { it.range.first until it.range.last + 1 }.toList()

/**
 * Text that keeps Latin runs (names, YouTube, numbers, links) in the Latin theme font when the UI is Urdu. In English it
 * is a plain Text. Use it wherever user data (child names, link text, video titles) is shown inside translated strings.
 */
@Composable
fun LatinAwareText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    textAlign: TextAlign? = null,
) {
    val urdu = isUrduLocale()
    val annotated = remember(text, urdu) { if (urdu) latinRunsAnnotated(text) else AnnotatedString(text) }
    Text(
        text = annotated,
        modifier = modifier,
        style = style,
        color = color,
        maxLines = maxLines,
        overflow = overflow,
        textAlign = textAlign,
    )
}

@EnglishAndUrduPreviews
@Composable
private fun LatinAwareTextPreview() {
    PreviewSurface {
        Column {
            LatinAwareText("Ali کے لیے محفوظ کریں")
            LatinAwareText("www.youtube.com/watch?v=abc کا لنک، 3 بار")
        }
    }
}
