package dev.hermeskotlin.designsystem.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextDirection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Style subset retained from PR #267; text isolation intentionally removed. */
class BidiStyleTest {
    @Test fun hebrewAndArabicPreferRtl() {
        assertTrue(Bidi.prefersRightToLeft("شكراً على الشرح"))
        assertTrue(Bidi.prefersRightToLeft("409 MB مكتوبة صراحةً، فالتحديث لن يغيّرها"))
        assertEquals(TextDirection.Rtl, BidiText.direction("בדוק כל יום אם auto_prune פעיל"))
        assertEquals(TextDirection.Rtl, BidiText.direction("شكراً على الشرح"))
    }

    @Test fun englishNumbersAndEmptyUseContentDirection() {
        for (text in listOf("Thanks for the explanation", "auto_prune is false", "2026-11-20 25$", "")) {
            assertFalse(Bidi.prefersRightToLeft(text))
            assertEquals(TextDirection.Content, BidiText.direction(text))
        }
    }

    @Test fun rawSavedHighlightsAndUnrelatedBlocksKeepTheirMeaning() {
        val source = "שלום 🙂 auto_prune ואז auto_prune סוף"
        val second = source.lastIndexOf("auto_prune")
        val text = AnnotatedString(source).withHighlights(
            listOf(TextHighlight(source, second, second + 10), TextHighlight("other block", 0, 4)), Color.Red)
        assertEquals(source, text.text)
        assertEquals(second, text.spanStyles.single().start)
        assertEquals(second + 10, text.spanStyles.single().end)
    }

    @Test fun emptyAndOutOfRangeHighlightsDoNotMutateText() {
        val source = "שלום עולם"
        for (ranges in listOf(emptyList(), listOf(TextHighlight(source, 5, 99)))) {
            val annotated = AnnotatedString(source).withHighlights(ranges, Color.Red)
            assertEquals(source, annotated.text)
            assertTrue(annotated.spanStyles.isEmpty())
        }
    }
}
