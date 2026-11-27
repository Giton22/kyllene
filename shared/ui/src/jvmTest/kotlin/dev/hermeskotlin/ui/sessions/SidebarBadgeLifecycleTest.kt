package dev.hermeskotlin.ui.sessions

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.hermeskotlin.core.bots.Bot
import dev.hermeskotlin.core.bots.BotSession
import dev.hermeskotlin.core.chat.InputRequest
import dev.hermeskotlin.core.chat.Waiting
import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.sessions.LiveSessions
import dev.hermeskotlin.core.sessions.SessionSummary
import dev.hermeskotlin.core.storage.InMemoryKeyValueStore
import dev.hermeskotlin.designsystem.HermesTheme
import dev.hermeskotlin.ui.bots.BotsUiState
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class SidebarBadgeLifecycleTest {
    @Test
    fun aVisibleChatWindowAcknowledgesBothTabsAndRecreationDoesNotBringTheBadgeBack() = runComposeUiTest {
        val gateway = GatewayUrl.parse("https://fixture.example")
        val storage = InMemoryKeyValueStore()
        val original = SidebarChatAcknowledgements(storage)
        val acknowledgements = mutableStateOf(original)
        val mounted = mutableStateOf(true)
        val requestId = mutableStateOf("approval-1")
        val sessions = listOf(
            SessionSummary("bot-tip", "Atlas", lineageIds = listOf("bot-root")),
            SessionSummary("ordinary", "Travel notes", lastActive = 10.0),
        )
        val bots = BotsUiState(bots = listOf(Bot("atlas", canonicalSession = BotSession("bot-root", "bot-tip"))))
        val window = Any()

        setContent {
            if (mounted.value) {
                val (chats, botsCount) = sidebarTabCounts(
                    gateway, "default", "default", sessions, mapOf("ordinary" to RowStatus(unread = true)), bots,
                    mapOf("bot-tip" to Waiting.Approval), mapOf("bot-tip" to approval(requestId.value)),
                    LiveSessions(), emptySet(), acknowledgements.value,
                )
                BasicText("counts $chats/$botsCount")
            }
        }

        onNodeWithText("counts 2/1").assertIsDisplayed()
        runOnIdle { original.show(window, SidebarChatViewer(gateway, null, setOf("ordinary"), emptySet(), 1L)) }
        onNodeWithText("counts 1/1").assertIsDisplayed()
        waitForIdle()
        runOnIdle { original.show(window, SidebarChatViewer(gateway, "atlas", setOf("bot-tip"), setOf("approval-1"), 2L)) }
        onNodeWithText("counts 0/0").assertIsDisplayed()
        waitForIdle()
        runOnIdle {
            original.hide(window)
            mounted.value = false
            acknowledgements.value = SidebarChatAcknowledgements(storage)
        }
        waitForIdle()
        runOnIdle { mounted.value = true }
        waitForIdle()
        onNodeWithText("counts 0/0").assertIsDisplayed()

        // The previously opened chat is covered, so there is no viewer to acknowledge a new request.
        runOnIdle { requestId.value = "approval-2" }
        onNodeWithText("counts 1/1").assertIsDisplayed()
    }

    @Test
    fun filterControlsAreAbovePinnedChats() = runComposeUiTest {
        setContent {
            HermesTheme {
                Box(Modifier.size(360.dp, 760.dp)) {
                    SessionsSidebarSample(
                        sessions = listOf(SessionSummary("pinned", "Pinned sample", pinned = true)),
                        selectedId = null,
                        userLabel = "Demo",
                    )
                }
            }
        }
        val all = onNodeWithText("All chats").assertIsDisplayed().fetchSemanticsNode()
        val pinned = onNodeWithText("Pinned sample").assertIsDisplayed().fetchSemanticsNode()
        assertTrue(all.boundsInRoot.bottom <= pinned.boundsInRoot.top)
    }

    private fun approval(id: String) = InputRequest.Approval(id, "fixture", "", null, emptyList())
}
