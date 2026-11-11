package com.bbro.kidtube.share

import androidx.compose.ui.text.font.FontFamily
import com.bbro.kidtube.ui.components.latinRunRanges
import com.bbro.kidtube.ui.components.latinRunsAnnotated
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which parts of a (mixed Urdu / Latin) string are drawn in the Latin font. */
class LatinRunsTest {
    private fun runs(text: String) = latinRunRanges(text).map { text.substring(it) }

    @Test
    fun aNameInsideUrduText_isALatinRun() {
        assertEquals(listOf("Ali"), runs("Ali کے لیے محفوظ کریں"))
    }

    @Test
    fun brandsNumbersAndLinks_areLatinRuns() {
        assertEquals(listOf("YouTube"), runs("YouTube"))
        assertEquals(listOf("3", "489"), runs("3 بار اور 489 سیکنڈ"))
        assertEquals(listOf("www.youtube.com/watch?v=aqz-KE-bpKQ"), runs("لنک www.youtube.com/watch?v=aqz-KE-bpKQ کھولیں"))
    }

    @Test
    fun severalNames_areSeparateRuns() {
        assertEquals(listOf("Test", "Sara", "Ali"), runs("Test · Sara، Ali"))
    }

    @Test
    fun pureUrduHasNoLatinRuns() {
        assertTrue(runs("دن میں ایک بار").isEmpty())
        assertTrue(runs("").isEmpty())
    }

    @Test
    fun theAnnotatedStringCarriesTheLatinFontOnExactlyThoseRuns() {
        val text = "Ali کے لیے 3 بار"
        val annotated = latinRunsAnnotated(text, latin = FontFamily.Monospace)

        assertEquals(text, annotated.text)
        val spans = annotated.spanStyles
        assertEquals(listOf("Ali", "3"), spans.map { annotated.text.substring(it.start, it.end) })
        assertTrue(spans.all { it.item.fontFamily == FontFamily.Monospace })
    }
}
