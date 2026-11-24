package dev.hermeskotlin.ui.chat

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.GitFork
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.X
import com.composeunstyled.Text
import com.composeunstyled.UnstyledIcon
import com.composeunstyled.theme.Theme
import dev.hermeskotlin.core.chat.ChatMessage
import dev.hermeskotlin.core.chat.Subagent
import dev.hermeskotlin.core.chat.SubagentRow
import dev.hermeskotlin.core.chat.SubagentStatus
import dev.hermeskotlin.core.chat.SubagentTranscriptPage
import dev.hermeskotlin.core.chat.ToolActivity
import dev.hermeskotlin.core.chat.historyToMessages
import dev.hermeskotlin.core.chat.subagentRows
import dev.hermeskotlin.core.sessions.SessionMessage
import dev.hermeskotlin.designsystem.accentText
import dev.hermeskotlin.designsystem.bodySmall
import dev.hermeskotlin.designsystem.caption
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.components.IconButton
import dev.hermeskotlin.designsystem.components.MarkdownText
import dev.hermeskotlin.designsystem.components.MinTouchTarget
import dev.hermeskotlin.designsystem.components.Spinner
import dev.hermeskotlin.designsystem.components.handCursor
import dev.hermeskotlin.designsystem.stroke
import dev.hermeskotlin.designsystem.surface
import dev.hermeskotlin.designsystem.text
import dev.hermeskotlin.designsystem.textMuted
import dev.hermeskotlin.designsystem.textSecondary
import dev.hermeskotlin.designsystem.typography
import dev.hermeskotlin.ui.LocalAppSettings
import dev.hermeskotlin.ui.resources.*
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

internal class SubagentContext(val subagents: List<Subagent>, val onOpen: (List<ToolActivity>, String) -> Unit)

internal val LocalSubagents = compositionLocalOf { SubagentContext(emptyList()) { _, _ -> } }

internal data class InspectorRow(val key: String, val task: SubagentRow, val depth: Int = 0)

internal fun delegationRows(calls: List<ToolActivity>, subagents: List<Subagent>): List<InspectorRow> =
    calls.flatMap { call ->
        subagentRows(call, subagents).flatMapIndexed { index, row ->
            val slot = if (index < call.tasks.size) "${call.id}:$index" else row.key
            InspectorRow(slot, row).withChildren()
        }
    }.distinctBy { it.task.transcriptId ?: it.key }

private fun InspectorRow.withChildren(): List<InspectorRow> =
    listOf(this) + task.children.flatMap { InspectorRow(it.key, it, depth + 1).withChildren() }

@Composable
internal fun DelegationActivity(calls: List<ToolActivity>, messageKey: String) {
    val context = LocalSubagents.current
    val rows = remember(calls, context.subagents) { delegationRows(calls, context.subagents) }
    if (rows.isEmpty()) return
    var expanded by rememberSaveable(messageKey) { mutableStateOf(false) }
    val running = rows.any { it.task.status == SubagentStatus.Running }
    val expandedState = stringResource(if (expanded) Res.string.chat_expanded else Res.string.chat_collapsed)
    val state = if (running) "$expandedState, ${stringResource(Res.string.chat_subagent_running)}" else expandedState
    Column {
        Row(
            Modifier.heightIn(min = MinTouchTarget)
                .clip(RoundedCornerShape(8.dp))
                .handCursor().clickable(role = Role.Button) { expanded = !expanded }
                .semantics { stateDescription = state }
                .padding(horizontal = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            UnstyledIcon(Lucide.GitFork, null, tint = Theme[colors][accentText], modifier = Modifier.size(16.dp))
            Text(
                pluralStringResource(Res.plurals.chat_subagent_count, rows.size, rows.size),
                style = Theme[typography][caption], color = Theme[colors][textSecondary],
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (running) Spinner(Modifier.size(12.dp).clearAndSetSemantics {}, color = Theme[colors][accentText])
            UnstyledIcon(if (expanded) Lucide.ChevronDown else Lucide.ChevronRight, null,
                tint = Theme[colors][textMuted], modifier = Modifier.size(13.dp))
        }
        if (expanded) {
            rows.forEachIndexed { index, row ->
                key(row.key) {
                    SubagentListRow(row, index > 0, index < rows.lastIndex) { context.onOpen(calls, row.key) }
                }
            }
        }
    }
}

@Composable
internal fun SubagentInspector(
    selected: InspectorRow,
    runtimeId: String?,
    load: suspend (String, String?) -> SubagentTranscriptPage?,
    onStop: (String) -> Unit,
    onDismiss: () -> Unit,
    desktop: Boolean,
    modifier: Modifier,
) {
    val focus = remember { FocusRequester() }
    val keyboard = if (desktop) {
        Modifier.onPreviewKeyEvent { event ->
            if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                onDismiss()
                true
            } else false
        }.focusRequester(focus).focusable()
    } else Modifier
    Column(modifier.then(keyboard).padding(horizontal = 14.dp)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = MinTouchTarget),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                selected.task.goal, Modifier.weight(1f),
                color = Theme[colors][text], style = Theme[typography][bodySmall],
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            IconButton(Lucide.X, stringResource(Res.string.chat_close), onDismiss)
        }
        key(selected.key) {
            SubagentDetail(selected.task, runtimeId, load, onStop, Modifier.weight(1f))
        }
    }
    if (desktop) LaunchedEffect(Unit) { focus.requestFocus() }
}

@Composable
private fun SubagentListRow(row: InspectorRow, previous: Boolean, next: Boolean, onClick: () -> Unit) {
    val task = row.task
    val (icon, statusText) = statusIcon(task.status)
    val status = stringResource(statusText)
    val summary = (if (task.status.live) task.thinking ?: task.activity.lastOrNull() else task.summary ?: task.activity.lastOrNull())
        ?.lineSequence()?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
    val tint = subagentDotColor(task.status)
    val rail = Theme[colors][stroke]
    Box {
        Canvas(Modifier.matchParentSize()) {
            val offset = 12.dp.toPx()
            val x = if (layoutDirection == LayoutDirection.Ltr) offset else size.width - offset
            if (previous) drawLine(rail, Offset(x, 0f), Offset(x, 6.dp.toPx()), 1.dp.toPx())
            if (next) drawLine(rail, Offset(x, 25.dp.toPx()), Offset(x, size.height), 1.dp.toPx())
        }
        val marker = Modifier.align(Alignment.TopStart).padding(start = 5.dp, top = 8.dp).size(14.dp)
        if (icon == null) Spinner(marker.clearAndSetSemantics {}, color = tint)
        else UnstyledIcon(icon, null, tint = tint, modifier = marker)
        Row(
            Modifier.fillMaxWidth().heightIn(min = MinTouchTarget)
                .clip(RoundedCornerShape(8.dp))
                .handCursor().clickable(role = Role.Button, onClick = onClick)
                .semantics { stateDescription = status }
                .padding(start = 26.dp + (row.depth.coerceAtMost(4) * 10).dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(task.goal, style = Theme[typography][bodySmall], color = Theme[colors][text], maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(summary ?: status, style = Theme[typography][caption],
                    color = Theme[colors][textSecondary], maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            UnstyledIcon(Lucide.ChevronRight, null, tint = Theme[colors][textMuted], modifier = Modifier.size(14.dp))
        }
    }
}

@Composable
private fun SubagentStatusLine(task: SubagentRow) {
    val status = stringResource(statusIcon(task.status).second)
    val meta = subagentMeta(task, includeStatus = false)
    Text(
        if (meta.isEmpty()) status else "$status · $meta",
        style = Theme[typography][caption], color = Theme[colors][textSecondary],
    )
}

@Composable
private fun SubagentDetail(
    task: SubagentRow,
    runtimeId: String?,
    load: suspend (String, String?) -> SubagentTranscriptPage?,
    onStop: (String) -> Unit,
    modifier: Modifier,
) {
    val id = task.transcriptId
    val fetch by rememberUpdatedState(load)
    var rows by remember(id, runtimeId) { mutableStateOf(emptyList<SessionMessage>()) }
    var loading by remember(id, runtimeId) { mutableStateOf(id != null && runtimeId != null) }
    var available by remember(id, runtimeId) { mutableStateOf(false) }
    var taskExpanded by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(id, runtimeId) {
        if (id == null || runtimeId == null) return@LaunchedEffect
        var cursor: String? = null
        while (true) {
            val page = fetch(id, cursor)
            loading = false
            if (page == null) break
            available = page.available
            if (!page.available) {
                if (!page.running) break
                delay(2_000)
                continue
            }
            rows = if (page.reset || cursor == null) page.rows else rows + page.rows
            val next = page.nextCursor ?: break
            val advancing = page.hasRows && next != cursor
            cursor = next
            if (!advancing) {
                if (!page.running) break
                delay(2_000)
            }
        }
    }
    val showReasoning = LocalAppSettings.current.showReasoning
    val messages = remember(rows) {
        historyToMessages(rows).filter { it is ChatMessage.User || it is ChatMessage.Assistant }
    }
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item("task") {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.weight(1f)) { SubagentStatusLine(task) }
                    if (runtimeId != null && task.status.live) task.subagentId?.let { childId -> StopButton { onStop(childId) } }
                }
                Text(
                    stringResource(Res.string.chat_subagent_task_details),
                    Modifier.clip(RoundedCornerShape(8.dp)).handCursor()
                        .clickable(role = Role.Button) { taskExpanded = !taskExpanded }.padding(vertical = 10.dp, horizontal = 4.dp),
                    style = Theme[typography][bodySmall], color = Theme[colors][accentText],
                )
                if (taskExpanded) {
                    SelectionContainer { MarkdownText(task.goal) }
                    SubagentDetails(task)
                }
            }
        }
        if (messages.isEmpty()) {
            item("transcript-status") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(when {
                            loading -> Res.string.sessions_loading
                            available -> Res.string.chat_subagent_transcript_empty
                            else -> Res.string.chat_subagent_transcript_unavailable
                        }),
                        style = Theme[typography][caption], color = Theme[colors][textMuted],
                    )
                    if (!loading) {
                        task.activity.forEach {
                            Text(it, style = Theme[typography][bodySmall], color = Theme[colors][textSecondary])
                        }
                        task.thinking?.let { Text(it, style = Theme[typography][bodySmall], color = Theme[colors][textMuted]) }
                        task.summary?.let {
                            CommentableSelection(CommentSource(task.key, "the result of the subagent working on “${task.goal.take(80)}”")) {
                                MarkdownText(it)
                            }
                        }
                    }
                }
            }
        }
        items(messages, key = { it.key }) { message ->
            when (message) {
                is ChatMessage.User -> SelectionContainer {
                    MarkdownText(message.text, Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .background(Theme[colors][surface]).padding(12.dp))
                }
                is ChatMessage.Assistant -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (showReasoning && message.reasoning.isNotBlank()) {
                        Reasoning(message.reasoning, streaming = false)
                    }
                    if (message.tools.isNotEmpty()) Tools(message.tools, message.key)
                    if (message.text.isNotBlank()) SelectionContainer { MarkdownText(message.text) }
                }
                else -> Unit
            }
        }
    }
}
