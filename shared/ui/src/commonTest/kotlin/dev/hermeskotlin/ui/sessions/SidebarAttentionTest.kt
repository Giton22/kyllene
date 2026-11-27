package dev.hermeskotlin.ui.sessions

import dev.hermeskotlin.core.chat.Waiting
import dev.hermeskotlin.core.cron.CronJob
import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.sessions.SessionSummary
import dev.hermeskotlin.core.storage.InMemoryKeyValueStore
import dev.hermeskotlin.core.threads.ChatThread
import dev.hermeskotlin.ui.bots.RoutineAcknowledgements
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SidebarAttentionTest {
    @Test
    fun openingOneChatOnlyClearsItsOwnItemOnBothTabs() {
        val bot = SidebarChatSignal(waiting = Waiting.Approval, requestId = "approval-1")
        val ordinary = SidebarChatSignal(unreadAt = 10.0)
        val chats = mapOf("ordinary" to ordinary, "bot" to bot)
        val bots = mapOf("bot" to bot)

        fun counts(seen: Map<String, SidebarChatSignal>) =
            listOf(chats, bots).map { entries -> entries.count { (id, item) ->
                val receipt = seen[id]?.let {
                    SidebarChatReceipt("atlas", id, setOfNotNull(it.requestId), it.unreadAt, 1L.takeIf { _ -> it.waiting != null })
                }
                item.isUnseen(listOfNotNull(receipt))
            } }

        assertEquals(listOf(2, 1), counts(emptyMap()))
        assertEquals(listOf(1, 1), counts(mapOf("ordinary" to ordinary)))
        assertEquals(listOf(1, 0), counts(mapOf("bot" to bot)))
        assertEquals(listOf(0, 0), counts(chats))
        val receipt = SidebarChatReceipt("atlas", "bot", setOf("approval-1"), legacyViewedAt = 1L)
        assertFalse(bot.copy(requestId = null, waiting = Waiting.Unknown).isUnseen(listOf(receipt)))
        assertTrue(bot.copy(requestId = "approval-2").isUnseen(listOf(receipt)))
        assertTrue(ordinary.copy(unreadAt = 11.0).isUnseen(listOf(receipt.copy(unreadAt = 10.0))))
    }

    @Test
    fun waitingThenUnreadThenOtherAndStableInsideEachGroup() {
        val original = listOf(
            "older" to RowStatus(),
            "unread" to RowStatus(unread = true, running = true),
            "running" to RowStatus(running = true),
            "approval" to RowStatus(waiting = Waiting.Approval, running = true),
            "question" to RowStatus(waiting = Waiting.Question),
        )
        fun ordered(rows: List<Pair<String, RowStatus>>) = rows.sortedBy { sidebarAttentionOrder(it.second) }.map { it.first }
        assertEquals(listOf("approval", "question", "unread", "older", "running"), ordered(original))
        val running = original.filter { AttentionFilter.Running.keeps(it.second) }
        assertEquals(listOf("approval", "unread", "running"), ordered(running))
        val resolved = original.map { (id, status) -> id to if (id == "approval") status.copy(waiting = null) else status }
        assertEquals(listOf("question", "unread", "older", "running", "approval"), ordered(resolved))
    }

    @Test
    fun attentionLeavesManuallyOrderedThreadsInPlace() {
        val sections = ThreadSections(
            listOf("b", "c", "a").map { id -> ThreadRow(ChatThread(id, id, id), SessionSummary(id)) },
            listOf(SessionSummary("recent"), SessionSummary("needs-input")),
        )
        val statuses = mapOf(
            "b" to RowStatus(running = true),
            "c" to RowStatus(running = true, unread = true),
            "a" to RowStatus(running = true, waiting = Waiting.Approval),
            "needs-input" to RowStatus(waiting = Waiting.Question),
        )

        val all = sections.filteredForAttention(AttentionFilter.All, statuses)
        assertEquals(listOf("b", "c", "a"), all.threads.map { it.session.id })
        assertEquals(listOf("needs-input", "recent"), all.others.map { it.id })
        val running = sections.filteredForAttention(AttentionFilter.Running, statuses)
        assertEquals(listOf("b", "c", "a"), running.threads.map { it.session.id })
        val waiting = sections.filteredForAttention(AttentionFilter.NeedsAttention, statuses)
        assertEquals(listOf("a"), waiting.threads.map { it.session.id })
        val afterReply = sections.filteredForAttention(
            AttentionFilter.NeedsAttention, statuses + ("c" to RowStatus(unread = true)),
        )
        assertEquals(listOf("c", "a"), afterReply.threads.map { it.session.id })
    }

    @Test
    fun chatReceiptsSurviveReloadAndOldPollsCannotClearThem() = runTest {
        val values = InMemoryKeyValueStore()
        val first = GatewayUrl.parse("https://first.example")
        val second = GatewayUrl.parse("https://second.example")
        val receipt = SidebarChatReceipt("atlas", "bot", setOf("approval-1"), 10.0, legacyViewedAt = 100L)
        SidebarChatAcknowledgements(values).acknowledge(first, receipt)

        val reloaded = SidebarChatAcknowledgements(values)
        assertEquals(listOf(receipt), reloaded.receipts(first).first().second)
        assertTrue(reloaded.receipts(second).first().second.isEmpty())
        reloaded.clearLegacyWait(first, "atlas", setOf("bot"), 99L)
        reloaded.clearLegacyWait(first, "nova", setOf("bot"), 101L)
        assertEquals(listOf(receipt), reloaded.receipts(first).first().second)

        reloaded.clearLegacyWait(first, "atlas", setOf("bot"), 101L)
        val after = reloaded.receipts(first).first().second
        assertEquals(listOf(receipt.copy(legacyViewedAt = null)), after)
        assertFalse(SidebarChatSignal(Waiting.Approval, "approval-1").isUnseen(after))
        assertTrue(SidebarChatSignal(Waiting.Unknown).isUnseen(after))
    }

    @Test
    fun aSeparateTranscriptCanAcknowledgeWithoutAMountedSidebar() = runTest {
        val values = InMemoryKeyValueStore()
        val gateway = GatewayUrl.parse("https://first.example")
        val store = SidebarChatAcknowledgements(values)
        store.acknowledgeTranscript(
            SidebarChatViewer(gateway, "atlas", setOf("root", "tip"), setOf("approval"), 100L),
            unreadAt = 10.0,
        )

        val reloaded = SidebarChatAcknowledgements(values).receipts(gateway).first().second
        assertEquals(setOf("root", "tip"), reloaded.map { it.id }.toSet())
        assertTrue(reloaded.all { it.profile == "atlas" && it.requestIds == setOf("approval") && it.unreadAt == 10.0 })
    }

    @Test
    fun oldUnreadWatermarksDoNotHideNewGatewayActivity() = runTest {
        val values = InMemoryKeyValueStore()
        val gateway = GatewayUrl.parse("https://first.example")
        values.put("sidebar.chat.seen.v1.${gateway.value}", """
            [{"profile":"atlas","id":"bot","requests":["approval"],"unreadAt":300,"legacyViewedAt":100}]
        """.trimIndent())

        val receipt = SidebarChatAcknowledgements(values).receipts(gateway).first().second.single()
        assertEquals(SidebarChatReceipt("atlas", "bot", setOf("approval"), legacyViewedAt = 100L), receipt)
        assertTrue(SidebarChatSignal(unreadAt = 120.0).isUnseen(listOf(receipt)))
    }

    @Test
    fun seeingAFailureSurvivesReloadAndIsScopedToThatRun() = runTest {
        val values = InMemoryKeyValueStore()
        val first = GatewayUrl.parse("https://first.example")
        val second = GatewayUrl.parse("https://second.example")
        val failed = CronJob("job", lastRunAt = "2026-11-24T08:00:00Z", lastStatus = "error", lastError = "Offline")
        RoutineAcknowledgements(values).markSeen(first, "atlas", failed)

        val reloaded = RoutineAcknowledgements(values)
        assertTrue(reloaded.isSeen(first, "atlas", failed.copy(name = "Renamed")))
        assertFalse(reloaded.isSeen(first, "atlas", failed.copy(lastRunAt = "2026-11-25T08:00:00Z")))
        assertFalse(reloaded.isSeen(first, "atlas", failed.copy(id = "other")))
        assertFalse(reloaded.isSeen(first, "nova", failed))
        assertFalse(reloaded.isSeen(second, "atlas", failed))
    }
}
