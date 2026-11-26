package dev.hermeskotlin.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.ShieldAlert
import com.composables.icons.lucide.X
import com.composeunstyled.Text
import com.composeunstyled.UnstyledIcon
import com.composeunstyled.theme.Theme
import dev.hermeskotlin.core.chat.ToolActivity
import dev.hermeskotlin.designsystem.*
import dev.hermeskotlin.designsystem.components.IconButton
import dev.hermeskotlin.designsystem.components.MinTouchTarget
import dev.hermeskotlin.designsystem.components.handCursor
import dev.hermeskotlin.designsystem.components.verticalScrollWithBar
import dev.hermeskotlin.ui.PlatformBackHandler
import dev.hermeskotlin.ui.clearOfWindowControls
import dev.hermeskotlin.ui.fullScreenDialogProperties
import dev.hermeskotlin.ui.resources.*
import kotlin.math.roundToLong
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

private const val INLINE_TOOL_LIMIT = 10

internal val LocalOpenToolDetails = staticCompositionLocalOf<((String, String) -> Unit)?> { null }

/** Completed tools read like the other disclosures in the reply. The list itself is a flat timeline. */
@Composable
internal fun Tools(tools: List<ToolActivity>, messageKey: String) {
    var expanded by rememberSaveable(messageKey) { mutableStateOf(false) }
    var selectedToolId by rememberSaveable(messageKey) { mutableStateOf<String?>(null) }
    val openToolDetails = LocalOpenToolDetails.current
    val icons = remember(tools) { tools.map { toolIcon(it.name) }.distinct().take(3) }
    val expandedState = stringResource(if (expanded) Res.string.chat_expanded else Res.string.chat_collapsed)
    val interaction = remember { MutableInteractionSource() }
    val total = tools.mapNotNull { it.durationSeconds?.takeIf(::validToolDuration) }.takeIf { it.isNotEmpty() }?.sum()
    Column {
        Row(
            Modifier.heightIn(min = MinTouchTarget)
                .clip(RoundedCornerShape(Theme[radii][radiusSmall]))
                .handCursor()
                .clickable(
                    interaction,
                    indication = rememberHoverIndication(Theme[colors][textSecondary]),
                    role = Role.Button,
                ) { expanded = !expanded }
                .semantics { stateDescription = expandedState }
                .padding(horizontal = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy((-6).dp)) {
                icons.forEach { ToolKindIcon(it) }
            }
            Text(
                pluralStringResource(Res.plurals.chat_tools_ran, tools.size, tools.size),
                style = Theme[typography][caption],
                color = Theme[colors][textSecondary],
            )
            total?.let {
                Text("· " + formatDuration(it), style = Theme[typography][caption], color = Theme[colors][textTertiary])
            }
            UnstyledIcon(
                if (expanded) Lucide.ChevronDown else Lucide.ChevronRight,
                contentDescription = null,
                tint = Theme[colors][textMuted],
                modifier = Modifier.size(13.dp),
            )
        }
        if (expanded) {
            val visibleCalls = tools.takeLast(INLINE_TOOL_LIMIT)
            // Keep each visible call's offset relative to the first call in the turn.
            val offsets = remember(tools) { toolTimelineOffsets(tools).takeLast(INLINE_TOOL_LIMIT) }
            val showOffsets = offsets.any { it != null }
            visibleCalls.forEachIndexed { index, tool ->
                key(tool.id) {
                    ToolTimelineRow(tool, offsets[index], showOffsets, index > 0, index < visibleCalls.lastIndex) {
                        if (openToolDetails != null) openToolDetails(messageKey, tool.id)
                        else selectedToolId = tool.id
                    }
                }
            }
        }
    }
    val selected = selectedToolId
    if (selected != null && openToolDetails == null) key(messageKey) {
        ToolDetailsViewer(tools, messageKey, selected) { selectedToolId = null }
    }
}

/** Keep the current calls and a short history visible while the reply is streaming. */
@Composable
internal fun LiveSteps(tools: List<ToolActivity>, messageKey: String) {
    if (tools.isEmpty()) return

    val firstRecentFinished = tools.indices.filter { !tools[it].running }.takeLast(4).firstOrNull() ?: 0
    val shown = tools.indices.filter { it >= firstRecentFinished || tools[it].running }.takeLast(INLINE_TOOL_LIMIT)
    val offsets = remember(tools) { toolTimelineOffsets(tools) }
    val showOffsets = shown.any { offsets[it] != null }
    val openToolDetails = LocalOpenToolDetails.current
    var selectedToolId by rememberSaveable(messageKey) { mutableStateOf<String?>(null) }

    Column {
        val earlier = tools.size - shown.size
        if (earlier > 0) {
            Text(
                pluralStringResource(Res.plurals.chat_earlier_steps, earlier, earlier),
                style = Theme[typography][caption],
                color = Theme[colors][textMuted],
                modifier = Modifier.padding(start = 26.dp, bottom = 4.dp),
            )
        }
        shown.forEachIndexed { row, index ->
            val tool = tools[index]
            key(tool.id) {
                var expanded by rememberSaveable { mutableStateOf(false) }
                val step = if (tool.running) null else toolDone(tool)
                val title = if (step != null) listOfNotNull(step.title.text(), step.detail).joinToString(" ") else tool.title()
                ToolTimelineRow(
                    tool, offsets[index], showOffsets, row > 0, row < shown.lastIndex,
                    title = title,
                    titleMaxLines = 1,
                    preview = if (expanded) liveToolPreview(tool, step?.detail ?: title) else null,
                    onCollapse = if (expanded) ({ expanded = false }) else null,
                    clickLabel = if (expanded) Res.string.chat_tool_details_page else Res.string.chat_show_details,
                ) {
                    if (!expanded) expanded = true
                    else if (openToolDetails != null) openToolDetails(messageKey, tool.id)
                    else selectedToolId = tool.id
                }
            }
        }
    }
    val selected = selectedToolId
    if (selected != null && openToolDetails == null) key(messageKey) {
        ToolDetailsViewer(tools, messageKey, selected) { selectedToolId = null }
    }
}

@Composable
private fun liveToolPreview(tool: ToolActivity, detail: String): String {
    if (tool.risk != null) return stringResource(Res.string.chat_tool_risk)
    return sequenceOf(tool.summary, tool.output, tool.diff)
        .mapNotNull { it?.lineSequence()?.firstOrNull(String::isNotBlank)?.trim()?.take(240) }
        .firstOrNull { it != detail }
        ?: stringResource(when {
            tool.failed -> Res.string.chat_tool_failed
            tool.running -> Res.string.chat_tool_still_running
            tool.completed -> Res.string.chat_tool_no_output
            else -> Res.string.chat_tool_completion_unknown
        })
}

/** One kind of tool, using the existing circles and overlap from the original Tools header. */
@Composable
internal fun ToolKindIcon(icon: ImageVector) {
    Box(
        Modifier
            .size(24.dp)
            .border(2.dp, Theme[colors][surface], CircleShape)
            .padding(1.dp)
            .background(Theme[colors][surface3], CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        UnstyledIcon(icon, contentDescription = null, tint = Theme[colors][textTertiary], modifier = Modifier.size(11.dp))
    }
}

private fun validToolDuration(value: Double) = value.isFinite() && value >= 0

private fun ToolActivity.title() = detail?.lineSequence()?.firstOrNull()?.trim().takeUnless { it.isNullOrEmpty() } ?: name

@Composable
private fun ToolActivity.statusColor() = when {
    running -> Theme[colors][accentText]
    failed -> Theme[colors][danger]
    completed -> Theme[colors][success]
    else -> Theme[colors][textTertiary]
}

@Composable
private fun ToolActivity.statusLabel() = stringResource(when {
    failed -> Res.string.chat_tool_failed
    running -> Res.string.chat_tool_still_running
    completed -> Res.string.chat_tool_completed
    else -> Res.string.chat_tool_completion_unknown
})

@Composable
private fun ToolTimelineRow(
    tool: ToolActivity,
    offset: String?,
    showOffsets: Boolean,
    hasPrevious: Boolean,
    hasNext: Boolean,
    title: String = tool.title(),
    titleMaxLines: Int = 2,
    preview: String? = null,
    onCollapse: (() -> Unit)? = null,
    clickLabel: StringResource = Res.string.chat_tool_details_page,
    onClick: () -> Unit,
) {
    val status = tool.statusColor()
    val statusLabel = tool.statusLabel()
    val rail = Theme[colors][textMuted].copy(alpha = 0.55f)
    Box {
        Canvas(Modifier.matchParentSize()) {
            val inset = 12.dp.toPx()
            val x = if (layoutDirection == LayoutDirection.Ltr) inset else size.width - inset
            if (hasPrevious) drawLine(rail, Offset(x, 0f), Offset(x, 6.dp.toPx()), 1.dp.toPx())
            if (hasNext) drawLine(rail, Offset(x, 25.dp.toPx()), Offset(x, size.height), 1.dp.toPx())
        }
        UnstyledIcon(
            toolIcon(tool.name),
            contentDescription = null,
            tint = status,
            modifier = Modifier.align(Alignment.TopStart).padding(start = 5.dp, top = 8.dp).size(14.dp),
        )
        Column(
            Modifier.fillMaxWidth()
                .heightIn(min = MinTouchTarget)
                .clip(RoundedCornerShape(Theme[radii][radiusSmall]))
                .handCursor()
                .clickable(onClickLabel = stringResource(clickLabel), role = Role.Button, onClick = onClick)
                .semantics(mergeDescendants = true) { stateDescription = statusLabel }
                .padding(start = 26.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Top,
            ) {
                if (showOffsets) Text(
                    offset.orEmpty(), Modifier.widthIn(min = 44.dp, max = 80.dp),
                    style = Theme[typography][caption], color = Theme[colors][textSecondary],
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(title, Modifier.weight(1f), style = Theme[typography][label], color = Theme[colors][text],
                    maxLines = titleMaxLines, overflow = TextOverflow.Ellipsis)
                AnimatedVisibility(
                    visible = onCollapse != null,
                    enter = EnterTransition.None,
                    exit = fadeOut(tween(durationMillis = 90, delayMillis = 130)),
                ) {
                    val rotation by transition.animateFloat(
                        transitionSpec = { tween(durationMillis = 130) },
                        label = "tool preview chevron",
                    ) { if (it == EnterExitState.Visible) 90f else 0f }
                    val collapseLabel = stringResource(Res.string.chat_hide_details)
                    Box(
                        Modifier.width(MinTouchTarget).height(20.dp).then(
                            if (onCollapse != null) Modifier
                                .handCursor()
                                .clickable(role = Role.Button, onClick = onCollapse)
                                .semantics { contentDescription = collapseLabel }
                            else Modifier,
                        ),
                        contentAlignment = Alignment.Center,
                    ) {
                        UnstyledIcon(
                            Lucide.ChevronRight, contentDescription = null, tint = Theme[colors][textSecondary],
                            modifier = Modifier.size(14.dp).graphicsLayer { rotationZ = rotation },
                        )
                    }
                }
                ToolDuration(tool)
                if (tool.risk != null) {
                    UnstyledIcon(Lucide.ShieldAlert, stringResource(Res.string.chat_tool_suspicious), tint = Theme[colors][warning], modifier = Modifier.size(14.dp))
                }
            }
            if (preview != null) Text(
                preview, Modifier.padding(top = 4.dp),
                style = Theme[typography][bodySmall], color = Theme[colors][textSecondary],
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ToolDuration(tool: ToolActivity) {
    tool.durationSeconds?.takeIf(::validToolDuration)?.let { duration ->
        val text = if (duration < 60) {
            val tenths = (duration * 10).roundToLong()
            "${tenths / 10}.${tenths % 10}s"
        } else formatDuration(duration)
        Text(text, style = Theme[typography][caption], color = Theme[colors][textSecondary])
    }
}

@Composable
internal fun ToolDetailsViewer(
    tools: List<ToolActivity>,
    messageKey: String,
    selectedToolId: String,
    docked: Boolean = false,
    onDismiss: () -> Unit,
) {
    var expandedIds by rememberSaveable { mutableStateOf(arrayOf(selectedToolId)) }
    val selectedRow = remember { BringIntoViewRequester() }
    val offsets = remember(tools) { toolTimelineOffsets(tools) }
    val showOffsets = offsets.any { it != null }
    LaunchedEffect(selectedToolId) {
        if (selectedToolId !in expandedIds) expandedIds = expandedIds + selectedToolId
        withFrameNanos { }
        selectedRow.bringIntoView()
    }
    val focus = remember { FocusRequester() }
    PlatformBackHandler(enabled = docked, onBack = onDismiss)
    LaunchedEffect(docked) { if (docked) focus.requestFocus() }
    val page = @Composable {
        Column(
            Modifier.fillMaxSize().background(Theme[colors][background])
                .then(if (docked) Modifier.focusRequester(focus).focusable() else Modifier.windowInsetsPadding(WindowInsets.safeDrawing)),
        ) {
            Row(
                Modifier.fillMaxWidth().clearOfWindowControls().padding(
                    start = if (docked) 16.dp else 4.dp, end = 16.dp, top = 6.dp, bottom = 10.dp,
                ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (!docked) IconButton(Lucide.ArrowLeft, stringResource(Res.string.common_back), onDismiss)
                Text(
                    stringResource(Res.string.chat_tool_details_page),
                    modifier = if (docked) Modifier.weight(1f) else Modifier,
                    style = Theme[typography][title], color = Theme[colors][text],
                )
                if (docked) IconButton(Lucide.X, stringResource(Res.string.chat_close), onDismiss)
            }
            Column(
                Modifier.fillMaxWidth().weight(1f).verticalScrollWithBar().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                tools.forEachIndexed { index, tool ->
                    if (index > 0) {
                        Box(Modifier.fillMaxWidth().size(1.dp).background(Theme[colors][textMuted].copy(alpha = 0.45f)))
                    }
                    key(tool.id) {
                        val expanded = tool.id in expandedIds
                        ToolComparisonRow(
                            tool, messageKey, expanded, offsets[index], showOffsets,
                            modifier = if (tool.id == selectedToolId) Modifier.bringIntoViewRequester(selectedRow) else Modifier,
                        ) {
                            expandedIds = if (expanded) expandedIds.filterNot { it == tool.id }.toTypedArray()
                            else expandedIds + tool.id
                        }
                    }
                }
            }
        }
    }
    if (docked) page() else Dialog(onDismissRequest = onDismiss, properties = fullScreenDialogProperties()) { page() }
}

@Composable
private fun ToolComparisonRow(
    tool: ToolActivity, messageKey: String, expanded: Boolean, offset: String?, showOffsets: Boolean,
    modifier: Modifier, onToggle: () -> Unit,
) {
    val status = tool.statusLabel()
    Column {
        Row(
            modifier.fillMaxWidth().heightIn(min = MinTouchTarget).handCursor().clickable(
                onClickLabel = stringResource(if (expanded) Res.string.chat_hide_details else Res.string.chat_show_details),
                role = Role.Button, onClick = onToggle,
            ).semantics { stateDescription = status },
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            UnstyledIcon(toolIcon(tool.name), null, tint = tool.statusColor(), modifier = Modifier.size(16.dp))
            if (showOffsets) Text(
                offset.orEmpty(), Modifier.widthIn(min = 44.dp),
                style = Theme[typography][caption], color = Theme[colors][textSecondary],
            )
            Text(
                tool.title(), Modifier.weight(1f), style = Theme[typography][label], color = Theme[colors][text],
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            ToolDuration(tool)
            UnstyledIcon(
                if (expanded) Lucide.ChevronDown else Lucide.ChevronRight,
                null, tint = Theme[colors][textMuted], modifier = Modifier.size(14.dp),
            )
        }
        if (expanded) {
            Column(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ToolDetailsContent(tool, messageKey)
                if (tool.output.isNullOrBlank() && tool.diff.isNullOrBlank()) {
                    Text(
                        tool.summary?.takeIf { it.isNotBlank() } ?: stringResource(Res.string.chat_tool_no_output),
                        style = Theme[typography][bodySmall], color = Theme[colors][textSecondary],
                    )
                }
            }
        }
    }
}

/** A relative offset needs the live receipt time of both this call and the first listed call. */
internal fun toolTimelineOffsets(tools: List<ToolActivity>): List<String?> {
    val origin = tools.firstOrNull()?.startedAt?.takeIf(Double::isFinite)
    return tools.map { tool ->
        val start = tool.startedAt?.takeIf(Double::isFinite)
        if (origin == null || start == null || start < origin) null else {
            val tenths = ((start - origin) * 10).roundToLong()
            val seconds = tenths / 10
            when {
                seconds >= 3600 -> "+${seconds / 3600}h ${seconds / 60 % 60}m"
                seconds >= 60 -> "+${seconds / 60}m ${seconds % 60}s"
                else -> "+$seconds.${tenths % 10}s"
            }
        }
    }
}
