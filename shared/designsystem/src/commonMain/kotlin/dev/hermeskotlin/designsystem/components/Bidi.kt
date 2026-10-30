package dev.hermeskotlin.designsystem.components

/**
 * Content-local direction policy, adapted from lama's PR #267.
 * Deliberately style-only: never insert bidi controls into Markdown or selectable text.
 */
internal object Bidi {
    /**
     * Mostly Hebrew/Arabic text prefers RTL; everything else uses the platform's content direction.
     *
     * This is an approximate policy, inherited from #267 and left as it is: it counts code points in whole
     * script ranges — Hebrew, Arabic and its supplement/presentation blocks, Syriac, and Latin/Greek/Cyrillic —
     * so Arabic-Indic digits and combining marks count as right-to-left. It also returns as soon as RTL leads
     * by nine, so nine Hebrew letters followed by a hundred Latin ones still choose RTL. It is not a strict
     * letter majority of the whole string.
     */
    fun prefersRightToLeft(text: String): Boolean {
        var rtl = 0
        var ltr = 0
        for (c in text) {
            when {
                isRightToLeft(c) -> rtl++
                isLeftToRight(c) -> ltr++
            }
            if (rtl > ltr + 8) return true
        }
        return rtl > ltr
    }

    private fun isRightToLeft(c: Char): Boolean = when (c) {
        in '\u0590'..'\u05FF', // Hebrew
        in '\u0600'..'\u06FF', // Arabic
        in '\u0700'..'\u074F', // Syriac
        in '\u0750'..'\u077F', // Arabic Supplement
        in '\u08A0'..'\u08FF', // Arabic Extended-A
        in '\uFB50'..'\uFDFF', // Arabic Presentation Forms-A
        in '\uFE70'..'\uFEFF', // Arabic Presentation Forms-B
        -> true
        else -> false
    }

    private fun isLeftToRight(c: Char): Boolean =
        c in 'A'..'Z' || c in 'a'..'z' || c in '\u00C0'..'\u024F' || c in '\u0370'..'\u058F'
}
