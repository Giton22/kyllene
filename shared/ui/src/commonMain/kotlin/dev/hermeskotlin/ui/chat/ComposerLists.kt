package dev.hermeskotlin.ui.chat

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.text.TextRange

/** What a line break after a list item does: [start] until [end] becomes [text], with the cursor after it. */
internal data class ListEdit(val start: Int, val end: Int, val text: String)

// A Markdown list item: indent, then a number with `.` or `)` or a `-`, `*` or `+` bullet, the space after it,
// an optional task box, and the item's text. The number is capped so a long one can't overflow.
private val listItem = Regex("""([ \t]*)(?:(\d{1,9})([.)])|([-*+]))([ \t]+)(\[[ xX]](?:[ \t]+|$))?(.*)""")
private val fence = Regex("""[ \t]*(```|~~~).*""")

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
    val item = listItem.matchEntire(line) ?: return null
    if (insideFence(text, lineStart)) return null
    val (indent, number, delimiter, bullet, space, box, content) = item.destructured
    if (content.isBlank()) {
        // Enter on an empty item ends the list, but not when the cursor was before text on that line.
        val restEnd = text.indexOf('\n', newLine + 1).let { if (it < 0) text.length else it }
        if (text.substring(newLine + 1, restEnd).isNotBlank()) return null
        return ListEdit(lineStart, newLine + 1, "")
    }
    val marker = if (number.isNotEmpty()) "${number.toLong() + 1}$delimiter" else bullet
    val nextBox = if (box.isNotEmpty()) "[ ] " else ""
    return ListEdit(newLine + 1, newLine + 1, indent + marker + space + nextBox)
}

/** Whether [lineStart] is inside a ``` or ~~~ code block opened above it. */
private fun insideFence(text: CharSequence, lineStart: Int): Boolean {
    var open = false
    var start = 0
    while (start < lineStart) {
        val end = text.indexOf('\n', start).let { if (it < 0 || it > lineStart) lineStart else it }
        if (fence.matches(text.subSequence(start, end))) open = !open
        start = end + 1
    }
    return open
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
 * Takes off a last line that is only a list marker, as a line break after the last item leaves it, so the
 * message doesn't go out with an empty item. Call before reading the text to send. A marker alone stays.
 */
internal fun TextFieldState.dropEmptyListItem() {
    val text = text
    val lineStart = text.lastIndexOf('\n') + 1
    if (lineStart == 0) return
    val item = listItem.matchEntire(text.subSequence(lineStart, text.length)) ?: return
    if (item.groupValues[7].isNotBlank() || insideFence(text, lineStart)) return
    val cut = (lineStart - 1).let { if (it > 0 && text[it - 1] == '\r') it - 1 else it }
    edit { replace(cut, length, "") }
}

/** The composers' list continuation, for a field's `inputTransformation`. See [continueList]. */
internal object ContinueLists : InputTransformation {
    override fun TextFieldBuffer.transformInput() = continueList()
}
