package dev.hermeskotlin.designsystem.components

/**
 * Unicode Bidirectional isolation for text that mixes scripts.
 *
 * Arabic (and Hebrew) paragraphs are laid out right to left, but the runs inside them — inline code,
 * commands, file paths, version numbers — are left to right. When such a run sits between Arabic words,
 * the bidi algorithm reorders the neutrals around it (the full stop, a colon, a bracket) and the line
 * reads as if the punctuation and the clauses had swapped places.
 *
 * Isolating the run with LRI…PDI (U+2066 … U+2069) makes the whole run one object with its own
 * direction, so the Arabic around it keeps its own order and the run stays intact and legible. The
 * characters are zero width: they change nothing about what the text says, only how it is ordered.
 */
internal object Bidi {

    /** LEFT-TO-RIGHT ISOLATE: opens a run that is ordered left to right inside any paragraph. */
    const val LRI: Char = '\u2066'

    /** POP DIRECTIONAL ISOLATE: closes the run opened by [LRI] (or [FSI]). */
    const val PDI: Char = '\u2069'

    /**
     * Isolates every inline code span in [markdown]: `` `hermes update` `` becomes `` `\u2066hermes update\u2069` ``
     * so the span stays one left-to-right unit inside an Arabic sentence.
     *
     * Fenced blocks (``` / ~~~), indented code lines and four-space indented lines are left alone — a code
     * block is already its own left-to-right paragraph. A span whose text is right to left (Arabic inside
     * backticks) is left alone too: isolating it to the left would force the wrong order. Text that already
     * carries an isolate character is not wrapped again, so the pass is idempotent.
     */
    fun isolateInlineCode(markdown: String): String {
        if (markdown.indexOf('`') < 0) return markdown
        var fence: String? = null
        val out = StringBuilder(markdown.length + 32)
        var start = 0
        while (start <= markdown.length) {
            val end = markdown.indexOf('\n', start).let { if (it < 0) markdown.length else it }
            val line = markdown.substring(start, end)
            val trimmed = line.trimStart()
            val opener = when {
                trimmed.startsWith("```") -> "```"
                trimmed.startsWith("~~~") -> "~~~"
                else -> null
            }
            val codeLine = fence != null || opener != null || line.startsWith("    ") || line.startsWith("\t")
            if (opener != null) fence = if (fence == opener) null else opener
            out.append(if (codeLine) line else isolateSpans(line))
            if (end < markdown.length) out.append('\n')
            start = end + 1
        }
        return out.toString()
    }

    /** One line's backtick spans, each isolated on its own. Handles runs of two or more backticks. */
    private fun isolateSpans(line: String): String {
        if (line.indexOf('`') < 0) return line
        val out = StringBuilder(line.length + 16)
        var i = 0
        while (i < line.length) {
            if (line[i] != '`') {
                out.append(line[i])
                i++
                continue
            }
            var run = 0
            while (i + run < line.length && line[i + run] == '`') run++
            val ticks = "`".repeat(run)
            val close = line.indexOf(ticks, i + run)
            if (close < 0) {
                out.append(ticks)
                i += run
                continue
            }
            val content = line.substring(i + run, close)
            out.append(ticks)
            out.append(if (needsIsolation(content)) "$LRI$content$PDI" else content)
            out.append(ticks)
            i = close + run
        }
        return out.toString()
    }

    /** A span needs isolation when it has something to show and its first strong character is left to right. */
    private fun needsIsolation(content: String): Boolean {
        if (content.isEmpty()) return false
        if (content.any { it == LRI || it == PDI }) return false
        for (c in content) {
            when {
                isRightToLeft(c) -> return false
                isLeftToRight(c) -> return true
            }
        }
        // Digits, punctuation or symbols alone: a number is already ordered left to right by bidi.
        return false
    }

    /**
     * Whether [text] should be laid out right to left: true when most of its letters are Arabic or
     * Hebrew, false when they are Latin or when it has no letters at all (digits, punctuation).
     *
     * Counting letters rather than taking the first strong character matters for real replies: a
     * sentence such as "409 MB مكتوبة صراحةً…" would otherwise be laid out left to right because it opens
     * with a number and a unit, and its Arabic clauses would read in the wrong order.
     */
    fun prefersRightToLeft(text: String): Boolean {
        var rtl = 0
        var ltr = 0
        for (c in text) {
            when {
                isRightToLeft(c) -> rtl++
                isLeftToRight(c) -> ltr++
            }
            if (rtl > ltr + 8) return true // decided early on a long Arabic run
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
