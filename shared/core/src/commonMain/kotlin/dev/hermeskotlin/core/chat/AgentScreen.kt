package dev.hermeskotlin.core.chat

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/** The herald-push plugin's tool that draws an A2UI screen in the chat (experiment, Android only). */
const val SHOW_UI_TOOL = "phone_show_ui"

/**
 * A screen the agent drew with [SHOW_UI_TOOL]: A2UI messages (`createSurface`, `updateComponents`, …), one JSON
 * object each, in the order they apply. [surfaceIds] are the surfaces they make or change; [wait] says whether the
 * tool waits for the user's tap, so the screen takes taps only while it does.
 */
data class AgentScreen(val messages: List<String>, val surfaceIds: Set<String>, val wait: Boolean) {
    companion object {
        /** At most this many messages a call: a screen, not a stream. */
        const val MAX_MESSAGES = 100

        /**
         * The screen a call of tool [name] with [arguments] draws, or null when it isn't [SHOW_UI_TOOL] or its
         * messages aren't a list of objects. Takes the call through the deferred-tools bridge (`tool_call`) too,
         * and messages the model wrote as one JSON string (a list, or one object a line).
         */
        fun from(name: String, arguments: JsonElement?): AgentScreen? {
            val args = objectOf(arguments)
            val call = if (name == SHOW_UI_TOOL) args else bridged(name, args) ?: return null
            if (call == null) return null
            val messages = when (val raw = call["messages"]) {
                is JsonArray -> raw.toList()
                is JsonPrimitive -> raw.contentOrNull?.let(::parseList)
                else -> null
            } ?: return null
            if (messages.isEmpty() || messages.size > MAX_MESSAGES || messages.any { it !is JsonObject }) return null
            val surfaces = messages.mapNotNullTo(LinkedHashSet()) { message ->
                (message as JsonObject).values.firstNotNullOfOrNull { body ->
                    ((body as? JsonObject)?.get("surfaceId") as? JsonPrimitive)?.takeIf { it.isString }?.content
                }
            }
            if (surfaces.isEmpty()) return null
            val wait = (call["wait"] as? JsonPrimitive)?.booleanOrNull ?: true
            return AgentScreen(messages.map { it.toString() }, surfaces, wait)
        }

        /** The arguments of a [SHOW_UI_TOOL] call made through the `tool_call` bridge, else null. */
        private fun bridged(name: String, args: JsonObject?): JsonObject? {
            if (name != "tool_call" || args == null) return null
            val entry = (args["calls"] as? JsonArray)?.singleOrNull() as? JsonObject ?: args
            if ((entry["name"] as? JsonPrimitive)?.contentOrNull != SHOW_UI_TOOL) return null
            return objectOf(entry["arguments"]) ?: objectOf(entry["args"]) ?: entry
        }

        private fun objectOf(element: JsonElement?): JsonObject? = when (element) {
            is JsonObject -> element
            is JsonPrimitive -> element.contentOrNull?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() } as? JsonObject
            else -> null
        }

        private fun parseList(text: String): List<JsonElement>? {
            val trimmed = text.trim()
            if (trimmed.startsWith("[")) return runCatching { Json.parseToJsonElement(trimmed) as? JsonArray }.getOrNull()
            return runCatching { trimmed.lines().filter { it.isNotBlank() }.map(Json::parseToJsonElement) }.getOrNull()
        }
    }
}
