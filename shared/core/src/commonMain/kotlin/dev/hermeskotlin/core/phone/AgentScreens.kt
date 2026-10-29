package dev.hermeskotlin.core.phone

import dev.hermeskotlin.core.chat.AgentScreen
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonElement

/**
 * The agent's screens in the chat that a `phone_show_ui` call waits on. The tool [await]s the user's answer; the
 * chat draws the screen from the call's arguments and hands a tap to [answer]. A screen is matched by its
 * surface ids, so the same screen in the chat's history takes taps only while its call still waits.
 */
class AgentScreens {

    private class Waiting(val screen: AgentScreen, val answer: CompletableDeferred<Result<JsonElement>>)

    private val waiting = MutableStateFlow<List<Waiting>>(emptyList())

    /** The surface ids that take a tap now. */
    val live: StateFlow<Set<String>> get() = _live.asStateFlow()
    private val _live = MutableStateFlow<Set<String>>(emptySet())

    /**
     * Waits until the user answers [screen] in the chat, and returns what they did, for the agent. Throws
     * [PhoneToolException] when the screen couldn't be shown, or a newer call took over its surface.
     */
    suspend fun await(screen: AgentScreen): JsonElement {
        val entry = Waiting(screen, CompletableDeferred())
        var replaced = emptyList<Waiting>()
        waiting.update { list ->
            val (old, kept) = list.partition { it.screen.surfaceIds.any(screen.surfaceIds::contains) }
            replaced = old
            kept + entry
        }
        replaced.forEach { it.answer.complete(Result.failure(PhoneToolException(REPLACED))) }
        publish()
        try {
            return entry.answer.await().getOrThrow()
        } finally {
            waiting.update { list -> list - entry }
            publish()
        }
    }

    /** Hands the user's [result] on [surfaceId] to the call waiting on it; false when none waits. */
    fun answer(surfaceId: String, result: JsonElement): Boolean = complete(surfaceId, Result.success(result))

    /** Tells the call waiting on [surfaceId] that its screen failed ([message], for the agent to fix it). */
    fun fail(surfaceId: String, message: String): Boolean = complete(surfaceId, Result.failure(PhoneToolException(message)))

    private fun complete(surfaceId: String, result: Result<JsonElement>): Boolean {
        val entry = waiting.value.lastOrNull { surfaceId in it.screen.surfaceIds } ?: return false
        return entry.answer.complete(result)
    }

    private fun publish() = _live.update { waiting.value.flatMapTo(HashSet()) { it.screen.surfaceIds } }

    private companion object {
        const val REPLACED = "A newer screen with the same surface id took its place."
    }
}
