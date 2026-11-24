package dev.hermeskotlin.ui.chat

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.ui.text.TextRange

/** What a line break after a list item does: [start] until [end] becomes [text], with the cursor after it. */
internal data class ListEdit(val start: Int, val end: Int, val text: String)

// A Markdown list item: indent, then a number with `.` or `)` or a `-`, `*` or `+` bullet, the space after it,
// an optional task box, and the item's text. The number is capped so a long one can't overflow.
private val listItem = Regex("""([ \t]*)(?:(\d{1,9})([.)])|([-*+]))([ \t]+)(\[[ xX]](?:[ \t]+|$))?(.*)""")
// A code fence: three or more backticks or tildes, then the rest of the line.
private val fence = Regex("""[ \t]*(`{3,}|~{3,})([\s\S]*)""")
// A thematic break like `* * *` or `- - -`, which reads as a bullet but isn't one.
private val thematicBreak = Regex("""[ \t]*([-*_])(?:[ \t]*\1){2,}[ \t]*""")

/** [line] as a list item, or null when it isn't one. */
private fun listItemOf(line: CharSequence): MatchResult? =
    if (thematicBreak.matches(line)) null else listItem.matchEntire(line)

/**
 * Carries a Markdown list on after the line break at [newLine] in [text]. After `1. buy milk` it puts `2. `
 * on the new line; after `- ` or `* ` the same bullet; after `- [x] done` an empty `- [ ] `; all at the item's
 * indent. A line break after an item with nothing in it ends the list instead: the marker goes, and so does
 * the new line. Null when the line isn't a list item, or the line break is inside a code block.
 */
internal fun listContinuation(text: CharSequence, newLine: Int): ListEdit? {
    if (newLine !in text.indices || text[newLine] != '\n') return null
    val lineStart = text.lastIndexOf('\n', newLine - 1) + 1
    val line = text.substring(lineStart, newLine).removeSuffix("\r")
    val item = listItemOf(line) ?: return null
    if (insideFence(text, lineStart)) return null
    val (indent, number, delimiter, bullet, space, box, content) = item.destructured
    if (content.isBlank()) {
        // Enter on an empty item ends the list, but not when the cursor was before text on that line.
        val restEnd = text.indexOf('\n', newLine + 1).let { if (it < 0) text.length else it }
        if (text.substring(newLine + 1, restEnd).isNotBlank()) return null
        return ListEdit(lineStart, newLine + 1, "")
    }
    // `09.` goes on as `10.`, `01.` as `02.`.
    val marker = if (number.isNotEmpty()) "${(number.toLong() + 1).toString().padStart(number.length, '0')}$delimiter" else bullet
    val nextBox = if (box.isNotEmpty()) "[ ] " else ""
    // Splitting `1. foo| bar` moves `bar` without its space, so the new item doesn't start with two.
    var restStart = newLine + 1
    while (restStart < text.length && (text[restStart] == ' ' || text[restStart] == '\t')) restStart++
    return ListEdit(newLine + 1, restStart, indent + marker + space + nextBox)
}

/**
 * Whether [lineStart] is inside a ``` or ~~~ code block opened above it. As in Markdown, only a fence of the same
 * character, at least as long as the opening one and with nothing after it closes the block, and a backtick fence
 * can't open with a backtick after it.
 */
private fun insideFence(text: CharSequence, lineStart: Int): Boolean {
    var open: String? = null
    var start = 0
    while (start < lineStart) {
        val end = text.indexOf('\n', start).let { if (it < 0 || it > lineStart) lineStart else it }
        val match = fence.matchEntire(text.subSequence(start, end).toString().removeSuffix("\r"))
        if (match != null) {
            val (marker, rest) = match.destructured
            val opener = open
            if (opener == null) {
                if (marker[0] == '~' || '`' !in rest) open = marker
            } else if (marker[0] == opener[0] && marker.length >= opener.length && rest.isBlank()) {
                open = null
            }
        }
        start = end + 1
    }
    return open != null
}

/**
 * Carries a list on when the edit just made typed a single line break at the cursor, as Enter or Shift+Enter
 * does, with or without the keyboard finishing a word in the same edit. A paste of several lines is left alone.
 */
@OptIn(ExperimentalFoundationApi::class)
internal fun TextFieldBuffer.continueList() {
    if (!selection.collapsed) return
    val cursor = selection.start
    val newLine = cursor - 1
    if (newLine < 0 || charAt(newLine) != '\n') return
    val typed = (0 until changes.changeCount).map { changes.getRange(it) }.firstOrNull { it.end == cursor } ?: return
    if ((typed.start until typed.end).count { charAt(it) == '\n' } != 1) return
    val edit = listContinuation(asCharSequence(), newLine) ?: return
    replace(edit.start, edit.end, edit.text)
    selection = TextRange(edit.start + edit.text.length)
}

/**
 * [text] to send, without a last line that is only a list marker under another list item, as a line break
 * after the last item leaves it, so the message doesn't go out with an empty item. It leaves the composer as
 * it is, so a send that doesn't go ahead changes nothing. A marker alone, or under other text, stays.
 */
internal fun withoutEmptyListItem(text: CharSequence): String {
    val lineStart = text.lastIndexOf('\n') + 1
    if (lineStart == 0) return text.toString()
    val item = listItemOf(text.subSequence(lineStart, text.length)) ?: return text.toString()
    if (item.groupValues[7].isNotBlank() || insideFence(text, lineStart)) return text.toString()
    val cut = (lineStart - 1).let { if (it > 0 && text[it - 1] == '\r') it - 1 else it }
    val above = text.subSequence(text.lastIndexOf('\n', cut - 1) + 1, cut)
    if (listItemOf(above) == null) return text.toString()
    return text.substring(0, cut)
}

/** The composers' list continuation, for a field's `inputTransformation`. See [continueList]. */
internal object ContinueLists : InputTransformation {
    override fun TextFieldBuffer.transformInput() = continueList()
}
