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
        // The space before the rest goes, so the new item doesn't start with two.
        assertTyped("1. foo\n2. |bar", "1. foo| bar")
        assertTyped("- foo\n- |bar\n- baz", "- foo|  bar\n- baz")
    }

    @Test
    fun zeroPaddedNumbersKeepTheirWidth() {
        assertTyped("01. a\n02. |", "01. a|")
        assertTyped("09) a\n10) |", "09) a|")
        assertTyped("99. a\n100. |", "99. a|")
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
        // A thematic break reads as a bullet but isn't one.
        assertTyped("Intro\n* * *\n|", "Intro\n* * *|")
        assertTyped("- - -\n|", "- - -|")
        assertTyped("  _ _ _\n|", "  _ _ _|")
    }

    @Test
    fun insideACodeBlockNothingGoesOn() {
        assertTyped("```\n- a\n|", "```\n- a|")
        assertTyped("~~~md\n1. a\n|", "~~~md\n1. a|")
        assertTyped("```\ncode\n```\n- a\n- |", "```\ncode\n```\n- a|")
    }

    @Test
    fun aCodeBlockClosesOnlyAsMarkdownCloses() {
        // CRLF lines still open a block.
        assertTyped("```\r\n- a\r\n|", "```\r\n- a\r|")
        // A shorter fence, the other character or a fence with text after it is code inside the block.
        assertTyped("````\n```\n- a\n|", "````\n```\n- a|")
        assertTyped("```\n~~~\n- a\n|", "```\n~~~\n- a|")
        assertTyped("```\n```js\n- a\n|", "```\n```js\n- a|")
        // A longer fence, or one with spaces after it, closes it.
        assertTyped("```\ncode\n`````\n- a\n- |", "```\ncode\n`````\n- a|")
        assertTyped("~~~\r\ncode\r\n~~~ \r\n- a\r\n- |", "~~~\r\ncode\r\n~~~ \r\n- a\r|")
        // Backticks after three backticks aren't a fence.
        assertTyped("``` a`b\n- a\n- |", "``` a`b\n- a|")
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

    private fun dropped(text: String): String = withoutEmptyListItem(text)

    @Test
    fun anEmptyLastItemIsDroppedBeforeSending() {
        assertEquals("1. a\n2. b", dropped("1. a\n2. b\n3. "))
        assertEquals("- a", dropped("- a\n- "))
        assertEquals("- [ ] a", dropped("- [ ] a\n- [ ] "))
        assertEquals("1. a", dropped("1. a\r\n2. "))
    }

    @Test
    fun anythingElseAtTheEndIsSentAsTyped() {
        assertEquals("- ", dropped("- "))
        assertEquals("- a\n- b", dropped("- a\n- b"))
        assertEquals("hello\n", dropped("hello\n"))
        assertEquals("```\n- a\n- ", dropped("```\n- a\n- "))
        assertEquals("```\r\n- a\r\n- ", dropped("```\r\n- a\r\n- "))
        assertEquals("````\n```\n- a\n- ", dropped("````\n```\n- a\n- "))
        assertEquals("```\n~~~\n- a\n- ", dropped("```\n~~~\n- a\n- "))
        // Only Enter after an item leaves an empty one; a marker under other text was typed on purpose.
        assertEquals("Which option do you want?\n2. ", dropped("Which option do you want?\n2. "))
        assertEquals("* * *\n- ", dropped("* * *\n- "))
        assertEquals("a\n\n- ", dropped("a\n\n- "))
    }
}
