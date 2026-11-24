package dev.hermeskotlin.core.chat

import dev.hermeskotlin.core.network.HermesJson
import dev.hermeskotlin.core.sessions.SessionMessage
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
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
        rows = if (available) rows.filterIsInstance<JsonObject>().mapNotNull(::decodeRow) else emptyList(),
        nextCursor = result.string("next_cursor"),
        reset = result.boolean("reset") == true,
        available = available,
        running = result.boolean("running") == true,
        hasRows = rows.isNotEmpty(),
    )
}

private fun decodeRow(row: JsonObject): SessionMessage? {
    if (row.string("row_kind") == "compaction") return null
    val role = row.string("role") ?: return null
    val message = runCatching {
        HermesJson.decodeFromJsonElement<SessionMessage>(buildJsonObject {
            row.forEach { (key, value) -> put(key, value) }
            row["row_id"]?.let { put("id", it) }
            if (role == "tool") {
                row["name"]?.let { put("tool_name", it) }
                // Keep the projected arguments with this occurrence until all pages are assembled.
                row["args"]?.let { put("tool_calls", it) }
                if (row["content"] == null || row["content"] == JsonNull) row["text"]?.let { put("content", it) }
            } else {
                row["text"]?.let { put("display_content", it) }
            }
        })
    }.getOrNull() ?: return null
    return message.takeIf { role != "tool" || it.toolName != null }
}

/** Adapt each projected tool occurrence to one call/result pair, with IDs scoped to the assembled transcript. */
fun subagentTranscriptMessages(rows: List<SessionMessage>): List<ChatMessage> = historyToMessages(
    rows.flatMapIndexed { index, row ->
        val name = row.toolName
        if (row.role != "tool" || name == null) return@flatMapIndexed listOf(row)
        val id = "subagent-tool-$index"
        val call = buildJsonObject {
            put("id", id)
            put("name", name)
            row.toolCalls?.let { put("arguments", it) }
        }
        listOf(
            SessionMessage(role = "assistant", timestamp = row.timestamp, toolCalls = JsonArray(listOf(call))),
            row.copy(toolCallId = id),
        )
    },
)
