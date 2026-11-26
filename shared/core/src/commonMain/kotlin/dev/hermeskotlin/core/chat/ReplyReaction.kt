package dev.hermeskotlin.core.chat

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import dev.hermeskotlin.core.network.HermesJson

enum class ReplyReaction(val emoji: String, private val instruction: String? = null) {
    Acknowledge("👍", "I’ve read this. If we already agreed on a next step and it’s still pending, carry it out. Otherwise, end the turn silently with a no-op."),
    Continue("▶️", "Continue with the next step from this reply within the authority already granted. Ask if the next step is ambiguous."),
    Verify("🔍", "Verify the claims or results in this reply. Say what holds up and what is still uncertain."),
    Explain("❓", "Explain this reply in plain language.");

    /**
     * The follow-up sent once it's set: what to do, and the end of the reply it's about, by which the agent finds
     * it. Not the reply's row id: the agent can't look a row up, and the user sees this text as their prompt.
     */
    fun prompt(reply: String): String? = instruction?.let {
        val tail = reply.trim().takeLast(600)
        val quote = (if (tail.firstOrNull()?.isLowSurrogate() == true) tail.drop(1) else tail)
            // The gateway expands `@file:`, `@diff` and the like anywhere in a prompt: a word joiner after each `@`
            // keeps a quoted one from pulling in files the user never asked for.
            .replace("@", "@\u2060")
        "$it\n\nThe reply ends: ${JsonPrimitive(quote)}"
    }
}

/** A row's `display_metadata`: an object, or a string holding one on older backends. */
internal fun displayMetadataObject(value: JsonElement?): JsonObject? = when (value) {
    is JsonObject -> value
    is JsonPrimitive -> value.contentOrNull?.trim()?.takeIf { it.startsWith("{") }
        ?.let { runCatching { HermesJson.parseToJsonElement(it) as? JsonObject }.getOrNull() }
    else -> null
}

internal fun userReactionEmoji(value: JsonElement?): String? =
    (value as? JsonArray).orEmpty().asSequence().filterIsInstance<JsonObject>()
        .firstOrNull { it.string("author") == "user" }?.string("emoji")
