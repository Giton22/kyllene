package dev.hermeskotlin.core.chat

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

enum class ReplyReaction(val emoji: String, private val instruction: String? = null) {
    Acknowledge("👍", "I’ve read this. If we already agreed on a next step and it’s still pending, carry it out. Otherwise, end the turn silently with a no-op."),
    Continue("▶️", "Continue with the next step from this reply within the authority already granted. Ask if the next step is ambiguous."),
    Verify("🔍", "Verify the claims or results in this reply. Say what holds up and what is still uncertain."),
    Explain("❓", "Explain this reply in plain language.");

    fun prompt(rowId: Long, reply: String): String? = instruction?.let {
        val tail = reply.trim().takeLast(600)
        val quote = if (tail.firstOrNull()?.isLowSurrogate() == true) tail.drop(1) else tail
        "$it\n\nAssistant message #$rowId (excerpt): ${JsonPrimitive(quote)}"
    }
}

internal fun userReactionEmoji(value: JsonElement?): String? =
    (value as? JsonArray).orEmpty().asSequence().filterIsInstance<JsonObject>()
        .firstOrNull { it.string("author") == "user" }?.string("emoji")
