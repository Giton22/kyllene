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
        visible: Boolean = true,
        desktop: Boolean = false,
        windowed: Set<String> = emptySet(),
    ) = chatsInView(
        recent = recent,
        // Unpinned first, as the gateway can list them: the view puts the pinned on top.
        others = listOf(a, pinned, b),
        filtered = listOf(old, oldPinned),
        results = results,
        searchOpen = searchOpen,
        recentFilter = recentFilter,
        visible = visible,
        desktop = desktop,
        windowed = windowed,
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
    fun aChatInAWindowOfItsOwnIsLeftOutOnADesktop() {
        assertEquals(listOf("thread", "pinned", "b"), view(desktop = true, windowed = setOf("a")))
        assertEquals(listOf("b"), view(results = listOf(a, b), searchOpen = true, desktop = true, windowed = setOf("a")))
        // So the chat after a windowed neighbour opens, or a new chat when the rest all have windows.
        val shown = ShownChats()
        shown.rows = { chatsInView(recent, recent, emptyList(), null, searchOpen = false, recentFilter = true, visible = true, desktop = true, windowed = setOf("a")) }
        assertEquals(b, shown.after(pinned))
        shown.rows = { chatsInView(listOf(a, b), emptyList(), emptyList(), null, searchOpen = false, recentFilter = true, visible = true, desktop = true, windowed = setOf("b")) }
        assertNull(shown.after(a))
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
