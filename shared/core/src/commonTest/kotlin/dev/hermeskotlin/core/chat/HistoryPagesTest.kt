package dev.hermeskotlin.core.chat

import dev.hermeskotlin.core.sessions.SessionMessage
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HistoryPagesTest {

    private fun row(id: Long, role: String = if (id % 2 == 1L) "user" else "assistant") = SessionMessage(id = id, role = role)

    private fun ids(rows: List<SessionMessage>?) = rows?.map { it.id }

    @Test
    fun aFullPageStartsAtItsFirstPrompt() {
        val page = listOf(row(4, "tool"), row(5, "assistant"), row(6, "user"), row(7, "assistant"))

        assertEquals(listOf(6L, 7L), ids(page.fromFirstTurn()))
    }

    @Test
    fun aPageWithoutAPromptIsKeptWhole() {
        val page = listOf(row(4, "assistant"), row(5, "tool"), row(6, "assistant"))

        assertEquals(listOf(4L, 5L, 6L), ids(page.fromFirstTurn()))
    }

    @Test
    fun aHiddenPromptIsNoTurnStart() {
        val page = listOf(row(4, "assistant"), SessionMessage(id = 5, role = "user", displayKind = "hidden"), row(6, "assistant"), row(7, "user"))

        assertEquals(listOf(7L), ids(page.fromFirstTurn()))
    }

    @Test
    fun aCompactionReminderIsNoTurnStart() {
        val taskList = SessionMessage(id = 5, role = "user", content = JsonPrimitive(
            "[Your active task list was preserved across context compression]\n- [>] 1. Draft (in_progress)"))
        val page = listOf(row(4, "assistant"), taskList, row(6, "assistant"), row(7, "user"))

        assertEquals(listOf(7L), ids(page.fromFirstTurn()))
    }

    @Test
    fun theNewestPageReplacesTheTailItOverlaps() {
        val loaded = (1L..6L).map { row(it) }

        assertEquals((1L..8L).toList(), ids(loaded.withNewest((5L..8L).map { row(it) })))
    }

    @Test
    fun rowsAnUndoTookAreDropped() {
        val loaded = (1L..6L).map { row(it) }

        // Rows 5 and 6 were undone; the page reaches back to 3 and ends there.
        assertEquals((1L..4L).toList(), ids(loaded.withNewest((3L..4L).map { row(it) })))
    }

    @Test
    fun aPageThatDoesntReachTheLoadedRowsCantBeJoined() {
        assertNull((1L..6L).map { row(it) }.withNewest((9L..12L).map { row(it) }))
        assertEquals(listOf(9L), ids(emptyList<SessionMessage>().withNewest(listOf(row(9)))))
    }

    @Test
    fun aShortNewestPageIsTheWholeTranscript() {
        val loaded = (1L..100L).map { row(it) }

        assertEquals(listOf(1L, 2L), ids(loaded.afterNewestPage(listOf(row(1), row(2)))))
    }

    @Test
    fun aFullNewestPageJoinsWhenOverlapPrecedesItsFirstPrompt() {
        val loaded = (1L..6L).map { row(it) }
        val page = listOf(row(6, "assistant"), row(7, "assistant"), row(8, "user"), row(9, "assistant"))

        assertEquals((1L..9L).toList(), ids(loaded.afterNewestPage(page, limit = 4)))
        assertEquals(listOf(8L, 9L), ids(emptyList<SessionMessage>().afterNewestPage(page, limit = 4)))
    }

    @Test
    fun aFullNewestPageJoinsFromItsFirstPromptWhenTheLoadedRowsWereTrimmed() {
        // The loaded rows are a page trimmed to its first prompt (5), so the turn's tail before it isn't loaded.
        // One row came since: the newest page now starts in that tail (3), and still meets the rows at 5.
        val loaded = listOf(row(5), row(6), row(7), row(8))
        val page = listOf(row(3, "assistant"), row(4, "tool"), row(5), row(6), row(7), row(8), row(9, "assistant"))

        assertEquals((5L..9L).toList(), ids(loaded.afterNewestPage(page, limit = 7)))
    }
}
