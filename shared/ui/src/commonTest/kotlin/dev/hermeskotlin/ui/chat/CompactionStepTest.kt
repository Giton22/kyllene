package dev.hermeskotlin.ui.chat

import dev.hermeskotlin.core.chat.ChatMessage
import dev.hermeskotlin.core.chat.ChatState
import dev.hermeskotlin.core.chat.reduce
import dev.hermeskotlin.core.network.HermesJson
import dev.hermeskotlin.core.rpc.GatewayEvent
import kotlin.test.Test
import kotlin.test.assertEquals

/** Bug #565: what the live task card says once auto-compaction has finished and the turn goes on. */
class CompactionStepTest {

    private fun event(type: String, payload: String = "{}") =
        GatewayEvent(type, HermesJson.parseToJsonElement(payload), sessionId = "rt", seq = null)

    private fun ChatState.apply(vararg events: GatewayEvent) = events.fold(this) { state, e -> state.reduce(e) }

    private val compactedMidTurn = ChatState(messages = listOf(ChatMessage.User("u", "go on"))).apply(
        event("message.start"),
        event("status.update", """{"kind":"compacting","text":"🗜️ Compacting context — summarizing earlier conversation so I can continue..."}"""),
        event("status.update", """{"kind":"compacted","text":"✓ Context compaction complete — continuing turn..."}"""),
    )

    @Test
    fun theCardFollowsTheThinkingFrameAfterCompaction() {
        val state = compactedMidTurn.apply(event("thinking.delta", """{"text":"(>∀<☆)☆ musing..."}"""))
        assertEquals(LiveStep(Words.Said("(>∀<☆)☆ musing...")), currentStep(state))
    }
}
