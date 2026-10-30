package dev.hermeskotlin.designsystem.components

import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.LayoutDirection

/** Style only. Text and selection offsets are always the original rendered block's, including authored controls. */
object BidiText {
    fun direction(text: String): TextDirection =
        if (Bidi.prefersRightToLeft(text)) TextDirection.Rtl else TextDirection.Content

    /**
     * Presentation only: which side a block is placed on. Ordering glyphs right to left does not move a narrow
     * text box to the right of a wide parent, so placement is decided separately from [direction].
     *
     * Hebrew/Arabic-majority text starts at the right, everything else at the left — an English paragraph
     * inside a Hebrew-heavy reply keeps the left. An empty block keeps [ambient] instead of a stale side.
     */
    fun placement(text: String, ambient: LayoutDirection): LayoutDirection = when {
        text.isBlank() -> ambient
        Bidi.prefersRightToLeft(text) -> LayoutDirection.Rtl
        else -> LayoutDirection.Ltr
    }
}

/** The existing math pipeline is the only source preparation. Bidi is exclusively a rendering style. */
internal fun prepareMarkdown(text: String): String = MathMarkup.prepare(text)
