package dev.hermeskotlin.core.chat

import dev.hermeskotlin.core.network.HermesJson
import dev.hermeskotlin.core.sessions.SessionMessage
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put

sealed interface SubagentTranscriptRead {
    data object Unsupported : SubagentTranscriptRead
    data object Retryable : SubagentTranscriptRead
}

data class SubagentTranscriptPage(
    val rows: List<SessionMessage>,
    val nextCursor: String?,
    val reset: Boolean,
    val available: Boolean,
    val running: Boolean,
    val hasRows: Boolean,
) : SubagentTranscriptRead

/** Poll until a finished child is caught up, or until the attachment or method is unavailable. */
suspend fun pollSubagentTranscript(
    load: suspend (String?) -> SubagentTranscriptRead?,
    isChildLive: () -> Boolean,
    onUpdate: (available: Boolean, rows: List<SessionMessage>) -> Unit,
) {
    var cursor: String? = null
    var rows = emptyList<SessionMessage>()
    var running = false
    while (true) {
        when (val read = load(cursor)) {
            null, SubagentTranscriptRead.Unsupported -> {
                onUpdate(false, rows)
                return
            }
            SubagentTranscriptRead.Retryable -> {
                if (!running && !isChildLive()) {
                    onUpdate(false, rows)
                    return
                }
            }
            is SubagentTranscriptPage -> {
                running = read.running
                val next = read.nextCursor
                val advancing = next != null && next != cursor && read.hasRows
                if (read.available) {
                    val replace = read.reset || (cursor == null && read.hasRows)
                    rows = mergeSubagentRows(if (replace) emptyList() else rows, read.rows, advancing || replace)
                    cursor = next ?: cursor
                }
                onUpdate(read.available, rows)
                if (read.available && advancing) continue
                if (!running) return
            }
        }
        delay(2_000)
    }
}

private fun mergeSubagentRows(
    earlier: List<SessionMessage>,
    incoming: List<SessionMessage>,
    advancing: Boolean,
): List<SessionMessage> {
    if (incoming.isEmpty()) return earlier
    val rows = earlier.toMutableList()
    val byId = mutableMapOf<Long, Int>()
    rows.forEachIndexed { index, row -> row.id?.let { byId[it] = index } }
    // Some projected tool rows have no row ID. Treat repeated rows as replay only on a stalled page.
    val replayed = if (advancing) emptySet() else earlier.filter { it.id == null }.toHashSet()
    for (row in incoming) {
        val id = row.id
        if (id == null) {
            if (row !in replayed) rows += row
        } else {
            val index = byId[id]
            if (index == null) {
                byId[id] = rows.size
                rows += row
            } else {
                rows[index] = row
            }
        }
    }
    return rows
}

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
