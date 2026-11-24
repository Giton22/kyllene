package dev.hermeskotlin.ui.sessions

import dev.hermeskotlin.core.sessions.SessionSummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ChatsInViewTest {

    private val thread = SessionSummary("thread", pinned = true)
    private val pinned = SessionSummary("pinned", pinned = true)
    private val a = SessionSummary("a")
    private val b = SessionSummary("b")
    private val old = SessionSummary("old")
    private val oldPinned = SessionSummary("old-pinned", pinned = true)

    /** The Recent list as sectioned: the thread, the pinned chat, then the rest. */
    private val recent = listOf(thread, pinned, a, b)

    private fun view(
        results: List<SessionSummary>? = null,
        searchOpen: Boolean = false,
        recentFilter: Boolean = true,
        botsSide: Boolean = false,
        visible: Boolean = true,
        desktop: Boolean = false,
    ) = chatsInView(
        recent = recent,
        // Unpinned first, as the gateway can list them: the view puts the pinned on top.
        others = listOf(a, pinned, b),
        filtered = listOf(old, oldPinned),
        results = results,
        searchOpen = searchOpen,
        recentFilter = recentFilter,
        botsSide = botsSide,
        visible = visible,
        desktop = desktop,
    ).map { it.id }

    @Test
    fun theRecentListIsTheSectionedOne() {
        assertEquals(listOf("thread", "pinned", "a", "b"), view())
    }

    @Test
    fun anotherFilterListsItsChatsPinnedFirst() {
        assertEquals(listOf("old-pinned", "old"), view(recentFilter = false))
    }

    @Test
    fun aSearchListsItsResultsPinnedFirst() {
        assertEquals(listOf("pinned", "b"), view(results = listOf(b, pinned), searchOpen = true))
        // Over the Archived view too.
        assertEquals(listOf("b"), view(results = listOf(b), searchOpen = true, recentFilter = false))
    }

    @Test
    fun aSearchWithNoWordTypedListsTheChatsOnADesktopOnly() {
        // A desktop lists the chats, unsectioned, under the empty field; a phone lists none, so the recent order holds.
        assertEquals(listOf("pinned", "a", "b"), view(searchOpen = true, desktop = true))
        assertEquals(listOf("thread", "pinned", "a", "b"), view(searchOpen = true))
    }

    @Test
    fun aSearchLeftOpenInAClosedSidebarDoesNotCount() {
        assertEquals(listOf("thread", "pinned", "a", "b"), view(results = listOf(b, a), searchOpen = true, visible = false))
        assertEquals(listOf("thread", "pinned", "a", "b"), view(searchOpen = true, visible = false, desktop = true))
        assertEquals(listOf("old-pinned", "old"), view(results = listOf(b), searchOpen = true, recentFilter = false, visible = false))
    }

    @Test
    fun theBotsSideKeepsTheRecentOrder() {
        assertEquals(listOf("thread", "pinned", "a", "b"), view(botsSide = true))
        // Results left behind by a search that has closed: the roster shows, not them.
        assertEquals(listOf("thread", "pinned", "a", "b"), view(results = listOf(b), botsSide = true))
        // A search opened from there lists its results as anywhere.
        assertEquals(listOf("b"), view(results = listOf(b), searchOpen = true, botsSide = true))
    }

    @Test
    fun aChatInAWindowOfItsOwnDoesNotTakeTheDeletedChatsPlace() {
        val shown = ShownChats()
        shown.rows = { listOf(pinned, a, b) }
        shown.windowed = { setOf("a") }
        // The chat after the windowed neighbour opens.
        assertEquals(b, shown.after(pinned))
        // A new chat when the rest all have windows.
        shown.windowed = { setOf("a", "b") }
        assertNull(shown.after(pinned))
        // The deleted chat having a window too doesn't lose its place in the list.
        shown.windowed = { setOf("a") }
        assertEquals(b, shown.after(a))
    }

    @Test
    fun aThreadRowWithALaterIdIsStillTheOpenChat() {
        // The thread's chat was compressed: its row carries the new id, the open chat the one the thread keeps.
        val tip = SessionSummary("tip", pinned = true, lineageIds = listOf("kept", "tip"))
        val shown = ShownChats()
        shown.rows = { listOf(tip, a, b) }
        assertEquals(a, shown.after(SessionSummary("kept")))
        assertEquals(a, shown.after(tip))
    }
}
