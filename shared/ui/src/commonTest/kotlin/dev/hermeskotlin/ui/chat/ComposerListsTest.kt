package dev.hermeskotlin.ui.chat

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.text.TextRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ComposerListsTest {

    /** Types [typed] at the `|` in [before] as one edit, as the keyboard does, then lets lists go on. */
    private fun type(before: String, typed: String = "\n", replacing: Int = 0): Pair<String, Int> {
        val at = before.indexOf('|')
        val state = TextFieldState(before.removeRange(at, at + 1), TextRange(at))
        state.edit {
            replace(at - replacing, at, typed)
            selection = TextRange(at - replacing + typed.length)
            continueList()
        }
        return state.text.toString() to state.selection.start
    }

    private fun assertTyped(expected: String, before: String, typed: String = "\n", replacing: Int = 0) {
        val (text, cursor) = type(before, typed, replacing)
        assertEquals(expected, StringBuilder(text).insert(cursor, '|').toString())
    }

    @Test
    fun aNumberedItemGetsTheNextNumber() {
        assertTyped("1. blah blah\n2. |", "1. blah blah|")
        assertTyped("1. a\n2. b\n3. |", "1. a\n2. b|")
        assertTyped("9) nine\n10) |", "9) nine|")
    }

    @Test
    fun bulletsKeepTheirMarkAndIndent() {
        assertTyped("- milk\n- |", "- milk|")
        assertTyped("* milk\n* |", "* milk|")
        assertTyped("+ milk\n+ |", "+ milk|")
        assertTyped("- a\n  - nested\n  - |", "- a\n  - nested|")
        assertTyped("\t1.  wide\n\t2.  |", "\t1.  wide|")
    }

    @Test
    fun aTaskGoesOnUnticked() {
        assertTyped("- [x] done\n- [ ] |", "- [x] done|")
        assertTyped("- [ ] todo\n- [ ] |", "- [ ] todo|")
    }

    @Test
    fun enterOnAnEmptyItemEndsTheList() {
        assertTyped("1. a\n|", "1. a\n2. |")
        assertTyped("- a\n|", "- a\n- |")
        assertTyped("- [ ] a\n|", "- [ ] a\n- [ ] |")
        assertTyped("- a\n|\nafter", "- a\n- |\nafter")
    }

    @Test
    fun splittingAnItemStartsTheNextWithItsRest() {
        assertTyped("1. foo\n2. |bar", "1. foo|bar")
    }

    @Test
    fun aBreakBeforeAnItemsTextLeavesItAlone() {
        assertTyped("1. \n|foo", "1. |foo")
        assertTyped("1.\n| foo", "1.| foo")
        assertTyped("\n|1. foo", "|1. foo")
    }

    @Test
    fun textThatIsntAListIsLeftAlone() {
        assertTyped("hello\n|", "hello|")
        assertTyped("*bold* text\n|", "*bold* text|")
        assertTyped("-1 degrees\n|", "-1 degrees|")
        assertTyped("1.5 kg\n|", "1.5 kg|")
        assertTyped("\n|", "|")
    }

    @Test
    fun insideACodeBlockNothingGoesOn() {
        assertTyped("```\n- a\n|", "```\n- a|")
        assertTyped("~~~md\n1. a\n|", "~~~md\n1. a|")
        assertTyped("```\ncode\n```\n- a\n- |", "```\ncode\n```\n- a|")
    }

    @Test
    fun crlfTextAndHugeNumbersDontBreakIt() {
        assertTyped("1. a\r\n2. |", "1. a\r|")
        assertTyped("999999999. a\n1000000000. |", "999999999. a|")
        assertTyped("12345678901. a\n|", "12345678901. a|")
    }

    @Test
    fun aWordFinishedWithTheBreakStillCountsAsTyping() {
        // Some keyboards commit the word being typed and the line break in one edit.
        assertTyped("1. blah balh\n2. |", "1. blah bal|", typed = "balh\n", replacing = 3)
    }

    @Test
    fun aPasteOfSeveralLinesIsLeftAlone() {
        assertTyped("1. a\n2. b\n|", "1. a|", typed = "\n2. b\n")
    }

    @Test
    fun anythingButALineBreakIsLeftAlone() {
        assertTyped("1. ab|", "1. a|", typed = "b")
        assertNull(listContinuation("1. a", 2))
    }

}
