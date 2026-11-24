package dev.hermeskotlin.core.chat

import dev.hermeskotlin.core.network.HermesJson
import dev.hermeskotlin.core.sessions.SessionMessage
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put

data class SubagentTranscriptPage(
    val rows: List<SessionMessage>,
    val nextCursor: String?,
    val reset: Boolean,
    val available: Boolean,
    val running: Boolean,
    val hasRows: Boolean,
)

internal fun decodeSubagentTranscript(
    result: JsonObject,
    parentId: String,
    subagentId: String,
): SubagentTranscriptPage? {
    if (result.string("session_id") != parentId || result.string("subagent_id") != subagentId) return null
    val available = result.boolean("available") ?: return null
    val rows = (result["rows"] as? JsonArray).orEmpty()
    return SubagentTranscriptPage(
        rows = if (available) rows.filterIsInstance<JsonObject>().flatMap(::decodeRow) else emptyList(),
        nextCursor = result.string("next_cursor"),
        reset = result.boolean("reset") == true,
        available = available,
        running = result.boolean("running") == true,
        hasRows = rows.isNotEmpty(),
    )
}

private fun decodeRow(row: JsonObject): List<SessionMessage> {
    if (row.string("row_kind") == "compaction") return emptyList()
    val role = row.string("role") ?: return emptyList()
    val message = runCatching {
        HermesJson.decodeFromJsonElement<SessionMessage>(buildJsonObject {
            row.forEach { (key, value) -> put(key, value) }
            row["row_id"]?.let { put("id", it) }
            if (role != "tool") row["text"]?.let { put("display_content", it) }
        })
    }.getOrNull() ?: return emptyList()
    if (role != "tool") return listOf(message)

    // The gateway projects tool results as standalone rows. Adapt those into the call/result pair
    // the regular transcript reducer uses so the same expandable tool UI can render them.
    val name = row.string("name") ?: return emptyList()
    val call = buildJsonObject {
        put("name", name)
        message.toolCallId?.let { put("id", it) }
        row["args"]?.let { put("arguments", it) }
    }
    return listOf(SessionMessage(role = "assistant", toolCalls = JsonArray(listOf(call))), message)
}
