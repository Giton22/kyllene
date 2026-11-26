package dev.hermeskotlin.ui.rooms

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.composeunstyled.theme.ColorScheme
import dev.hermeskotlin.core.rooms.Room
import dev.hermeskotlin.core.rooms.RoomLine
import dev.hermeskotlin.core.rooms.RoomPendingAction
import dev.hermeskotlin.designsystem.HermesTheme
import dev.hermeskotlin.ui.bots.BotFaces
import androidx.compose.foundation.text.BasicText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Opening a hosted room from the sidebar lands on its newest line, not its oldest (#512): the room reads its
 * log after it is on screen, and the list it then draws has to start at the end, not scroll there from the top.
 */
@OptIn(ExperimentalTestApi::class)
class RoomOpeningPositionTest {
    private fun lines(roomTag: String, count: Int) = lines(roomTag, 1..count)

    private fun lines(roomTag: String, seqs: IntRange) = seqs.map { seq ->
        RoomLine.Message(
            seq = seq,
            fromUser = seq % 2 == 1,
            speaker = if (seq % 2 == 1) null else "Bot",
            text = "$roomTag line $seq: a line long enough to wrap onto a second row at phone width.",
            eventId = "$roomTag-e$seq",
            profile = if (seq % 2 == 1) null else "default",
        )
    }

    private class Actions : RoomActions {
        override val composer = TextFieldState()
        override fun send() = Unit
        override fun stop() = Unit
        var earlierAsked = 0
        override fun loadEarlier() {
            earlierAsked++
        }
        override fun approve(action: RoomPendingAction, choice: String) = Unit
        override fun retry(action: RoomPendingAction) = Unit
        override fun renameRoom(room: Room, name: String) = Unit
        override fun deleteRoom(room: Room) = Unit
        override fun dismissRoomNotice() = Unit
    }

    /** What App.kt shows: the bot chat while no room is open, the room once one is. */
    private class Screen {
        var open by mutableStateOf<OpenRoom?>(null)
        val actions = Actions()
    }

    private fun ComposeUiTest.show(screen: Screen) {
        setContent {
            HermesTheme(ColorScheme.Light) {
                Box(Modifier.size(360.dp, 640.dp)) {
                    val room = screen.open
                    if (room == null) BasicText("Bot chat") else RoomView(room, screen.actions, BotFaces(), onOpenSidebar = {}, onBack = {})
                }
            }
        }
        waitForIdle()
    }

    private fun room(id: String) = Room(roomId = id, name = "Room $id", members = emptyList(), latestSeq = 40)

    /**
     * Frames until the newest line is on screen after the log came in, and whether the oldest line was on
     * screen in any of them on the way.
     */
    private fun ComposeUiTest.followOpening(newest: String, oldest: String): Pair<Int, Boolean> {
        var sawOldest = false
        var frames = 0
        while (frames < 600) {
            if (onAllNodes(hasText(oldest, substring = true)).fetchSemanticsNodes().isNotEmpty() &&
                runCatching { onNodeWithText(oldest, substring = true).assertIsDisplayed() }.isSuccess
            ) sawOldest = true
            if (runCatching { onNodeWithText(newest, substring = true).assertIsDisplayed() }.isSuccess) break
            mainClock.advanceTimeByFrame()
            frames++
        }
        return frames to sawOldest
    }

    /** Opens [id] as RoomsViewModel.open does: loading and empty first, then the first read's lines. */
    private fun ComposeUiTest.open(screen: Screen, id: String, count: Int = 40): Pair<Int, Boolean> {
        runOnIdle { screen.open = OpenRoom(room = room(id), loading = true) }
        mainClock.advanceTimeByFrame()
        mainClock.advanceTimeByFrame()
        runOnIdle { screen.open = OpenRoom(room = room(id), loading = false, lines = lines(id, count)) }
        mainClock.advanceTimeByFrame()
        return followOpening(newest = "$id line $count:", oldest = "$id line 1:")
    }

    @Test
    fun aRoomOpenedFromABotChatLandsOnItsNewestLine() = runComposeUiTest {
        val screen = Screen()
        show(screen)
        onNodeWithText("Bot chat").assertIsDisplayed()
        mainClock.autoAdvance = false

        val (frames, sawOldest) = open(screen, "a")

        println("bot -> room: newest shown after $frames frames, oldest seen on the way: $sawOldest")
        assertTrue(!sawOldest, "the room opened at its oldest line and scrolled down from there ($frames frames to the newest)")
        assertTrue(frames <= 1, "the newest line took $frames frames to come into view")
    }

    @Test
    fun aRoomOpenedFromAnotherRoomLandsOnItsNewestLine() = runComposeUiTest {
        val screen = Screen()
        show(screen)
        mainClock.autoAdvance = false
        open(screen, "a")
        // Settle at the first room's end, then switch rooms without App leaving the room branch.
        mainClock.advanceTimeBy(5_000)

        val (frames, sawOldest) = open(screen, "b", count = 120)

        println("room -> room: newest shown after $frames frames, oldest seen on the way: $sawOldest")
        assertTrue(!sawOldest, "the second room opened at its oldest line and scrolled down from there ($frames frames to the newest)")
        assertTrue(frames <= 1, "the newest line took $frames frames to come into view")
    }

    @Test
    fun aLongRoomOpenedFromABotChatLandsOnItsNewestLine() = runComposeUiTest {
        val screen = Screen()
        show(screen)
        mainClock.autoAdvance = false

        // About what a first read of 200 log events shows.
        val (frames, sawOldest) = open(screen, "a", count = 190)

        println("bot -> long room: newest shown after $frames frames, oldest seen on the way: $sawOldest")
        assertTrue(!sawOldest, "the long room opened at its oldest line and scrolled down from there ($frames frames to the newest)")
        assertTrue(frames <= 1, "the newest line took $frames frames to come into view")
    }

    @Test
    fun aRoomReopenedAfterABotChatLandsOnItsNewestLine() = runComposeUiTest {
        val screen = Screen()
        show(screen)
        mainClock.autoAdvance = false
        open(screen, "a")
        mainClock.advanceTimeBy(5_000)
        // Back to a bot chat (rooms.close()), then the room again.
        runOnIdle { screen.open = null }
        mainClock.advanceTimeByFrame()

        val (frames, sawOldest) = open(screen, "a")

        println("room -> bot -> room: newest shown after $frames frames, oldest seen on the way: $sawOldest")
        assertTrue(!sawOldest, "the room reopened at its oldest line and scrolled down from there ($frames frames to the newest)")
        assertTrue(frames <= 1, "the newest line took $frames frames to come into view")
    }

    @Test
    fun aRoomWhoseNewestReplyIsTallerThanTheScreenOpensAtItsEnd() = runComposeUiTest {
        val screen = Screen()
        show(screen)
        mainClock.autoAdvance = false
        // A bot's long reply last, taller than the 640 dp screen, with its last words marked.
        val tall = lines("a", 39) + RoomLine.Message(
            seq = 40, fromUser = false, speaker = "Bot", eventId = "a-e40", profile = "default",
            text = (1..120).joinToString(" ") { "word$it" } + "\n\n" + "a long paragraph. ".repeat(80) + "\n\nthe reply's last words",
        )
        runOnIdle { screen.open = OpenRoom(room = room("a"), loading = true) }
        mainClock.advanceTimeByFrame()
        runOnIdle { screen.open = OpenRoom(room = room("a"), loading = false, lines = tall) }
        mainClock.advanceTimeBy(5_000)

        // The end of the room, as the chat opens at, not the top of its last reply.
        val bottom = onNodeWithText("the reply's last words", substring = true).getUnclippedBoundsInRoot().bottom
        assertTrue(bottom <= 640.dp, "the room opened at the top of its newest reply; its end is at $bottom, below the screen")
    }

    @Test
    fun aLineThatComesInLaterStillScrollsIntoView() = runComposeUiTest {
        val screen = Screen()
        show(screen)
        mainClock.autoAdvance = false
        open(screen, "a")
        mainClock.advanceTimeBy(5_000)

        // The next poll brings one more line.
        runOnIdle { screen.open = screen.open!!.copy(lines = lines("a", 41)) }
        mainClock.advanceTimeByFrame()
        val (frames, _) = followOpening(newest = "a line 41:", oldest = "a line 1:")

        println("new line: shown after $frames frames")
        assertTrue(frames < 600, "a line that came in later never came into view")
        onNodeWithText("a line 40:", substring = true).assertIsDisplayed()
    }

    @Test
    fun olderLinesAreReadOnlyAtTheTopAndKeepTheReadersLine() = runComposeUiTest {
        val screen = Screen()
        show(screen)
        mainClock.autoAdvance = false
        // The first read is the log's newest page; there's more before it.
        runOnIdle { screen.open = OpenRoom(room = room("a"), loading = true) }
        mainClock.advanceTimeByFrame()
        runOnIdle { screen.open = OpenRoom(room = room("a"), loading = false, lines = lines("a", 41..80), canLoadEarlier = true) }
        mainClock.advanceTimeBy(5_000)
        onNodeWithText("a line 80:", substring = true).assertIsDisplayed()
        assertEquals(0, screen.actions.earlierAsked, "opening at the newest line read older lines before the reader went up")

        // Up to the top: that asks for the page before.
        onNode(hasScrollToIndexAction()).performScrollToIndex(0)
        mainClock.advanceTimeBy(1_000)
        assertEquals(1, screen.actions.earlierAsked, "reaching the top didn't read older lines")
        runOnIdle { screen.open = screen.open!!.copy(loadingEarlier = true) }
        mainClock.advanceTimeByFrame()
        runOnIdle { screen.open = screen.open!!.copy(lines = lines("a", 1..80), loadingEarlier = false, canLoadEarlier = false) }
        mainClock.advanceTimeBy(1_000)

        // The reader stays on the line they were on, with the older ones above it, not at the new top or the end.
        onNodeWithText("a line 41:", substring = true).assertIsDisplayed()
        assertTrue(runCatching { onNodeWithText("a line 1:", substring = true).assertIsDisplayed() }.isFailure, "the reader was moved to the oldest line")
        assertTrue(runCatching { onNodeWithText("a line 80:", substring = true).assertIsDisplayed() }.isFailure, "the reader was moved to the newest line")
    }
}
