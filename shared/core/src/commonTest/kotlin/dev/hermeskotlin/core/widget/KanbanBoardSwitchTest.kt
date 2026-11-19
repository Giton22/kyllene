package dev.hermeskotlin.core.widget

import dev.hermeskotlin.core.kanban.KanbanBoardMeta
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** D28: the Tasks header's switch button cycles the gateway's boards in list order, wrapping. */
class KanbanBoardSwitchTest {

    private fun boards(vararg slugs: String) = slugs.map { KanbanBoardMeta(slug = it, name = it) }

    @Test
    fun theNextBoardFollowsListOrderAndWraps() {
        val all = boards("feedbucket", "waypoint", "herald")
        assertEquals("waypoint", KanbanWidgetAssembler.nextBoard(all, "feedbucket")?.slug)
        assertEquals("herald", KanbanWidgetAssembler.nextBoard(all, "waypoint")?.slug)
        assertEquals("feedbucket", KanbanWidgetAssembler.nextBoard(all, "herald")?.slug)
    }

    @Test
    fun nothingToSwitchToWithFewerThanTwoBoards() {
        assertNull(KanbanWidgetAssembler.nextBoard(boards("only"), "only"))
        assertNull(KanbanWidgetAssembler.nextBoard(emptyList(), "only"))
    }

    @Test
    fun anUnknownCurrentStartsTheCycleAtTheFirstBoard() {
        assertEquals("first", KanbanWidgetAssembler.nextBoard(boards("first", "second"), "gone")?.slug)
    }

    @Test
    fun theSnapshotCarriesTheBoardCountForTheButtonsVisibility() {
        val snapshot = KanbanWidgetAssembler.assemble(
            gatewayUrl = "https://gw.example.ts.net",
            boards = dev.hermeskotlin.core.kanban.KanbanBoardsResponse(boards = boards("a", "b"), current = "a"),
            board = null,
            boardSlug = "a",
            serverNowSeconds = 1,
            deviceNowSeconds = 2,
        )
        assertEquals(2, snapshot.boardCount)
    }
}