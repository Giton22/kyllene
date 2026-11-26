package dev.hermeskotlin.ui.chat

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.FileText
import com.composables.icons.lucide.GitFork
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.X
import com.composables.icons.lucide.Square
import com.composeunstyled.Text
import com.composeunstyled.UnstyledIcon
import com.composeunstyled.theme.Theme
import dev.hermeskotlin.core.chat.ChatMessage
import dev.hermeskotlin.core.chat.Subagent
import dev.hermeskotlin.core.chat.SubagentRow
import dev.hermeskotlin.core.chat.SubagentStatus
import dev.hermeskotlin.core.chat.SubagentTranscriptRead
import dev.hermeskotlin.core.chat.ToolActivity
import dev.hermeskotlin.core.chat.subagentTranscriptMessages
import dev.hermeskotlin.core.chat.subagentRows
import dev.hermeskotlin.core.chat.pollSubagentTranscript
import dev.hermeskotlin.core.media.GatewayFile
import dev.hermeskotlin.core.sessions.SessionMessage
import dev.hermeskotlin.designsystem.accentText
import dev.hermeskotlin.designsystem.bodySmall
import dev.hermeskotlin.designsystem.title
import dev.hermeskotlin.designsystem.caption
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.components.Button
import dev.hermeskotlin.designsystem.components.ButtonSize
import dev.hermeskotlin.designsystem.components.ButtonVariant
import dev.hermeskotlin.designsystem.components.IconButton
import dev.hermeskotlin.designsystem.components.LocalFileLinks
import dev.hermeskotlin.designsystem.components.MarkdownText
import dev.hermeskotlin.designsystem.components.MinTouchTarget
import dev.hermeskotlin.designsystem.components.Spinner
import dev.hermeskotlin.designsystem.components.Tooltip
import dev.hermeskotlin.designsystem.components.handCursor
import dev.hermeskotlin.designsystem.stroke
import dev.hermeskotlin.designsystem.surface
import dev.hermeskotlin.designsystem.text
import dev.hermeskotlin.designsystem.textMuted
import dev.hermeskotlin.designsystem.textSecondary
import dev.hermeskotlin.designsystem.typography
import dev.hermeskotlin.ui.LocalAppSettings
import dev.hermeskotlin.ui.resources.*
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

internal class SubagentContext(
    val subagents: List<Subagent>,
    val onStop: ((String) -> Unit)? = null,
    val onOpen: (String, String) -> Unit,
)

internal val LocalSubagents = compositionLocalOf { SubagentContext(emptyList()) { _, _ -> } }

internal data class InspectorRow(val key: String, val task: SubagentRow, val depth: Int = 0) {
    val selectionKey: String get() = task.transcriptId?.let { "child:$it" } ?: key
}

internal fun selectedDelegationRow(
    messages: List<ChatMessage>,
    subagents: List<Subagent>,
    replyKey: String,
    selection: String,
): InspectorRow? {
    val reply = messages.filterIsInstance<ChatMessage.Assistant>().firstOrNull { it.key == replyKey } ?: return null
    val rows = delegationRows(reply.tools.filter { it.name == "delegate_task" }, subagents)
    return rows.firstOrNull { it.selectionKey == selection || it.key == selection }
}

internal fun delegationRows(calls: List<ToolActivity>, subagents: List<Subagent>): List<InspectorRow> {
    val owners = calls.flatMapIndexed { index, call -> call.tasks.mapNotNull { task -> task.transcriptId?.let { it to index } } }.toMap()
    return calls.flatMapIndexed { callIndex, call ->
        val candidates = subagents.filter { owners[it.id]?.let { owner -> owner == callIndex } != false }
        subagentRows(call, candidates).flatMapIndexed { index, row ->
            val slot = if (index < call.tasks.size) "call:$callIndex:$index" else row.key
            InspectorRow(slot, row).withChildren()
        }
    }.distinctBy { it.selectionKey }
}

private fun InspectorRow.withChildren(): List<InspectorRow> =
    listOf(this) + task.children.flatMap { InspectorRow(it.key, it, depth + 1).withChildren() }

@Composable
internal fun DelegationActivity(calls: List<ToolActivity>, messageKey: String) {
    val context = LocalSubagents.current
    val rows = remember(calls, context.subagents) { delegationRows(calls, context.subagents) }
    if (rows.isEmpty()) return
    var expanded by rememberSaveable(messageKey) { mutableStateOf(false) }
    var expandedRow by rememberSaveable(messageKey) { mutableStateOf<String?>(null) }
    val count = rows.count { !it.task.refused }
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
                pluralStringResource(Res.plurals.chat_subagent_count, count, count),
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
                    val rowExpanded = expandedRow == row.key
                    val stopId = row.task.subagentId?.takeIf { rowExpanded && row.task.status == SubagentStatus.Running }
                    val stop = context.onStop
                    SubagentListRow(
                        row, index > 0, index < rows.lastIndex, rowExpanded,
                        onToggle = { expandedRow = if (rowExpanded) null else row.key },
                        onOpen = { context.onOpen(messageKey, row.selectionKey) },
                        onStop = if (stopId != null && stop != null) ({ stop(stopId) }) else null,
                    )
                }
            }
        }
    }
}

@Composable
internal fun SubagentInspector(
    selected: InspectorRow,
    runtimeId: String?,
    load: suspend (String, String?) -> SubagentTranscriptRead?,
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
            Column(Modifier.weight(1f).padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(Res.string.chat_subagent_transcript), style = Theme[typography][title], color = Theme[colors][text])
                Text(
                    selected.task.goal, color = Theme[colors][textSecondary], style = Theme[typography][bodySmall],
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(Lucide.X, stringResource(Res.string.chat_close), onDismiss)
        }
        val task = selected.task
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { SubagentStatusLine(task) }
            val stopId = task.subagentId?.takeIf { runtimeId != null && task.status.live }
            if (stopId != null) {
                val stopDescription = stringResource(Res.string.chat_subagent_stop)
                Button(
                    text = stringResource(Res.string.chat_stop),
                    onClick = { onStop(stopId) },
                    modifier = Modifier.heightIn(min = MinTouchTarget).semantics { contentDescription = stopDescription },
                    size = ButtonSize.Small, variant = ButtonVariant.Ghost, leadingIcon = Lucide.Square,
                )
            }
        }
        SubagentDetail(task, runtimeId, load, Modifier.weight(1f))
    }
    if (desktop) LaunchedEffect(Unit) { focus.requestFocus() }
}

@Composable
private fun SubagentListRow(
    row: InspectorRow,
    previous: Boolean,
    next: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
    onStop: (() -> Unit)?,
) {
    val task = row.task
    val (icon, statusText) = statusIcon(task.status)
    val status = stringResource(statusText)
    val disclosure = stringResource(if (expanded) Res.string.chat_expanded else Res.string.chat_collapsed)
    val tint = subagentDotColor(task.status)
    val rail = Theme[colors][stroke]
    val targetSize = MinTouchTarget
    val markerCenter = targetSize / 2
    val nestedIndent = (row.depth.coerceAtMost(4) * 10).dp
    val disclosureAction = Modifier.clip(RoundedCornerShape(8.dp))
        .handCursor().clickable(role = Role.Button, onClick = onToggle)
        .semantics { stateDescription = "$status, $disclosure" }
    Box {
        Canvas(Modifier.matchParentSize()) {
            val offset = markerCenter.toPx()
            val x = if (layoutDirection == LayoutDirection.Ltr) offset else size.width - offset
            if (previous) drawLine(rail, Offset(x, 0f), Offset(x, (markerCenter - 11.dp).toPx()), 1.dp.toPx())
            if (next) drawLine(rail, Offset(x, (markerCenter + 11.dp).toPx()), Offset(x, size.height), 1.dp.toPx())
        }
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().then(if (onStop == null) disclosureAction else Modifier),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (onStop == null) {
                    Box(Modifier.size(targetSize), contentAlignment = Alignment.Center) {
                        if (icon == null) Spinner(Modifier.size(14.dp).clearAndSetSemantics {}, color = tint)
                        else UnstyledIcon(icon, null, tint = tint, modifier = Modifier.size(14.dp))
                    }
                } else {
                    val label = stringResource(Res.string.chat_subagent_stop)
                    Tooltip(label) {
                        Box(
                            Modifier.size(targetSize).clip(RoundedCornerShape(8.dp))
                                .handCursor().clickable(role = Role.Button, onClick = onStop)
                                .semantics { contentDescription = label },
                            contentAlignment = Alignment.Center,
                        ) {
                            Spinner(Modifier.size(20.dp).clearAndSetSemantics {}, color = tint)
                            UnstyledIcon(Lucide.Square, null, tint = Theme[colors][text], modifier = Modifier.size(10.dp))
                        }
                    }
                }
                Row(
                    Modifier.weight(1f).heightIn(min = targetSize)
                        .then(if (onStop != null) disclosureAction else Modifier)
                        .padding(start = nestedIndent, end = 6.dp, top = 6.dp, bottom = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        task.goal, Modifier.weight(1f), style = Theme[typography][bodySmall],
                        color = Theme[colors][text], maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    UnstyledIcon(if (expanded) Lucide.ChevronDown else Lucide.ChevronRight, null,
                        tint = Theme[colors][textMuted], modifier = Modifier.size(14.dp))
                }
            }
            if (expanded) {
                val activity = (task.activity.takeLast(2) + listOfNotNull(task.thinking, task.summary.takeUnless { task.status.live }))
                    .mapNotNull { it.lineSequence().firstOrNull()?.trim()?.takeIf(String::isNotEmpty) }
                    .distinct().takeLast(2).ifEmpty { listOf(status) }
                Row(
                    Modifier.fillMaxWidth().padding(start = targetSize + nestedIndent),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f).align(Alignment.Top), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        activity.forEach { line ->
                            Text(line, style = Theme[typography][caption], color = Theme[colors][textSecondary],
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    IconButton(
                        Lucide.FileText, stringResource(Res.string.chat_subagent_open_transcript), onOpen,
                        tint = Theme[colors][accentText], iconSize = 17.dp,
                    )
                }
            }
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
    load: suspend (String, String?) -> SubagentTranscriptRead?,
    modifier: Modifier,
) {
    val id = task.transcriptId
    val fetch by rememberUpdatedState(load)
    val childLive by rememberUpdatedState(task.status.live)
    val list = rememberLazyListState()
    var followTail by remember(id, runtimeId) { mutableIntStateOf(0) }
    var rows by remember(id, runtimeId) { mutableStateOf(emptyList<SessionMessage>()) }
    var loading by remember(id, runtimeId) { mutableStateOf(id != null && runtimeId != null) }
    var available by remember(id, runtimeId) { mutableStateOf(false) }
    LaunchedEffect(id, runtimeId) {
        if (id == null || runtimeId == null) return@LaunchedEffect
        pollSubagentTranscript(load = { fetch(id, it) }, isChildLive = { childLive }) { ready, updated ->
            if (rows.isNotEmpty() && updated != rows && !list.canScrollForward && !list.isScrollInProgress) {
                followTail++
            }
            rows = updated
            available = ready
            loading = false
        }
    }
    LaunchedEffect(id, runtimeId, followTail) {
        if (followTail == 0) return@LaunchedEffect
        // Let the appended text remeasure before following the bottom, including a growing last item.
        withFrameNanos {}
        withFrameNanos {}
        val last = list.layoutInfo.totalItemsCount - 1
        if (last >= 0 && !list.isScrollInProgress) {
            list.scrollToItem(last, scrollOffset = Int.MAX_VALUE / 2)
        }
    }
    val showReasoning = LocalAppSettings.current.showReasoning
    val messages = remember(rows) {
        subagentTranscriptMessages(rows).filter { it is ChatMessage.User || it is ChatMessage.Assistant }
    }
    // Prefer the stored instruction over the short goal used as the header.
    val firstPrompt = messages.firstOrNull() as? ChatMessage.User
    val instructions = firstPrompt?.text?.takeIf { it.isNotBlank() } ?: task.goal
    val transcript = if (firstPrompt == null) messages else messages.drop(1)
    val finalText = (transcript.lastOrNull() as? ChatMessage.Assistant)?.text?.trim()
    val result = task.summary?.trim()?.takeIf { it.isNotEmpty() && it != finalText }
    val openFile = LocalFileLinks.current
    LazyColumn(
        modifier.fillMaxWidth(), state = list, contentPadding = PaddingValues(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item("task") {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(Res.string.chat_subagent_instructions), style = Theme[typography][caption], color = Theme[colors][textSecondary])
                SubagentPrompt(instructions)
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
                    }
                }
            }
        }
        items(transcript, key = { it.key }) { message ->
            when (message) {
                is ChatMessage.User -> SubagentPrompt(message.text)
                is ChatMessage.Assistant -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (showReasoning && message.reasoning.isNotBlank()) {
                        Reasoning(message.reasoning, streaming = false)
                    }
                    if (message.tools.isNotEmpty()) SubagentTranscriptTools(message.tools, message.key)
                    if (message.text.isNotBlank()) SelectionContainer { MarkdownText(message.text) }
                }
                else -> Unit
            }
        }
        if (result != null && (!task.status.live || messages.isEmpty())) {
            item("result") {
                CommentableSelection(CommentSource(task.key, "the result of the subagent working on “${task.goal.take(80)}”")) {
                    MarkdownText(result)
                }
            }
        }
        items(task.filesWritten.distinct(), key = { "file:$it" }) { path ->
            val file = GatewayFile.fromLink(path)
            val canOpen = file != null && openFile != null
            Row(
                Modifier.fillMaxWidth().heightIn(min = MinTouchTarget)
                    .then(if (canOpen) Modifier.handCursor().clickable(role = Role.Button) { openFile?.invoke(path) } else Modifier),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                UnstyledIcon(Lucide.FileText, null, tint = Theme[colors][textSecondary], modifier = Modifier.size(16.dp))
                Text(file?.name ?: path, Modifier.weight(1f), style = Theme[typography][bodySmall],
                    color = Theme[colors][text], maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                if (canOpen) UnstyledIcon(Lucide.ChevronRight, null, tint = Theme[colors][textMuted], modifier = Modifier.size(14.dp))
            }
        }
    }
}

@Composable
private fun SubagentPrompt(text: String) = SelectionContainer {
    val shape = RoundedCornerShape(12.dp)
    MarkdownText(text, Modifier.fillMaxWidth().clip(shape)
        .background(Theme[colors][surface]).border(1.dp, Theme[colors][stroke], shape).padding(12.dp))
}
