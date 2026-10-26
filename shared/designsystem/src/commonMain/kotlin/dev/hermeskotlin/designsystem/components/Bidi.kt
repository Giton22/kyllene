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
     * Isolates the left-to-right runs a mostly-Arabic line carries — dates, amounts, versions, identifiers,
     * paths and bare URLs — the same way [isolateInlineCode] does for backtick spans.
     *
     * Wrapping them matters because a run such as `2026-11-20` or `www-data` between Arabic words
     * otherwise drags the punctuation and the words around it across the line. Markdown structure is left
     * alone: code spans (already isolated by [isolateInlineCode]), fenced and indented code, link
     * destinations, autolinks and inline HTML.
     */
    fun isolateLtrRuns(markdown: String): String {
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
            out.append(if (codeLine) line else isolateRuns(line))
            if (end < markdown.length) out.append('\n')
            start = end + 1
        }
        return out.toString()
    }

    /** One line's left-to-right runs, each isolated on its own. */
    private fun isolateRuns(line: String): String {
        if (line.none { it.isAsciiLetter() || it in '0'..'9' }) return line
        val out = StringBuilder(line.length + 32)
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                // A code span: isolateInlineCode has already wrapped its content; copy it as it is.
                c == '`' -> i = copyCodeSpan(line, i, out)
                // A link destination `](url)`: bidi never sees it, and editing it would break the link.
                c == ']' && line.getOrNull(i + 1) == '(' -> i = copyUntil(line, i, ')', out)
                // Autolinks and inline HTML are markup, not prose.
                c == '<' -> i = copyUntil(line, i, '>', out)
                c.isAsciiLetter() || c in '0'..'9' || (c == '/' && line.getOrNull(i + 1)?.isAsciiLetter() == true) -> {
                    // One word, then any further words joined to it by single spaces: an English phrase
                    // inside an Arabic sentence is one run, or its words would come out in reverse.
                    var end = i
                    while (true) {
                        val tokenStart = end
                        while (end < line.length && line[end].isRunChar()) end++
                        if (end == tokenStart) break
                        var next = end
                        while (next < line.length && line[next] == ' ') next++
                        val continues = next > end && next < line.length &&
                            (line[next].isAsciiLetter() || line[next] in '0'..'9' || (line[next] == '/' && line.getOrNull(next + 1)?.isAsciiLetter() == true))
                        if (!continues) break
                        end = next
                    }
                    // Sentence punctuation that happens to sit next to the run stays in the prose.
                    while (end > i + 1 && line[end - 1] in RUN_TRAILING) end--
                    val alreadyIsolated = line.getOrNull(i - 1) == LRI && line.getOrNull(end) == PDI
                    if (end > i && !alreadyIsolated) {
                        out.append(LRI).append(line, i, end).append(PDI)
                        i = end
                    } else if (end > i) {
                        out.append(line, i, end)
                        i = end
                    } else {
                        out.append(c)
                        i++
                    }
                }
                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        return out.toString()
    }

    private fun copyCodeSpan(line: String, at: Int, out: StringBuilder): Int {
        var run = 0
        while (at + run < line.length && line[at + run] == '`') run++
        val ticks = "`".repeat(run)
        val close = line.indexOf(ticks, at + run)
        if (close < 0) {
            out.append(ticks)
            return at + run
        }
        out.append(ticks).append(line, at + run, close).append(ticks)
        return close + run
    }

    private fun copyUntil(line: String, at: Int, closing: Char, out: StringBuilder): Int {
        val close = line.indexOf(closing, at + 1)
        if (close < 0) {
            out.append(line[at])
            return at + 1
        }
        out.append(line, at, close + 1)
        return close + 1
    }

    private fun Char.isAsciiLetter(): Boolean = this in 'A'..'Z' || this in 'a'..'z'

    /** What a run may be made of: the characters of an identifier, a path, a URL, a date or an amount. */
    private fun Char.isRunChar(): Boolean = isAsciiLetter() || this in '0'..'9' || this in RUN_CHARS

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

    /** Inner characters of a run: what a path, URL, identifier, date, version or amount is made of. */
    private const val RUN_CHARS = "/._-:@#~+&=%\$\\"

    /** Punctuation that ends a sentence rather than a run, so it stays in the prose around it. */
    private const val RUN_TRAILING = "./:-_=+&?#~\\"
}
