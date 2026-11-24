package dev.hermeskotlin.ui.chat

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.ArrowRight
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
import dev.hermeskotlin.ui.clearOfWindowControls
import dev.hermeskotlin.ui.fullScreenDialogProperties
import dev.hermeskotlin.ui.resources.*
import kotlin.math.roundToLong
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/** Completed tools read like the other disclosures in the reply. The list itself is a flat timeline. */
@Composable
internal fun Tools(tools: List<ToolActivity>, messageKey: String) {
    var expanded by rememberSaveable(messageKey) { mutableStateOf(false) }
    var detailsVisible by rememberSaveable(messageKey) { mutableStateOf(false) }
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
            val offsets = remember(tools) { toolTimelineOffsets(tools) }
            val showOffsets = offsets.any { it != null }
            tools.forEachIndexed { index, tool ->
                key(tool.id) {
                    ToolTimelineRow(tool, offsets[index], showOffsets, index > 0, index < tools.lastIndex)
                }
            }
            if (tools.any { it.hasToolDetails() }) {
                ToolDetailsLink(stringResource(Res.string.chat_view_tool_details), Modifier.padding(start = 26.dp)) {
                    detailsVisible = true
                }
            }
        }
    }
    if (detailsVisible) key(messageKey) {
        ToolDetailsViewer(tools, messageKey) { detailsVisible = false }
    }
}

/** One kind of tool, using the existing circles and overlap from the original Tools header. */
@Composable
private fun ToolKindIcon(icon: ImageVector) {
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

private fun ToolActivity.hasToolDetails() =
    !input.isNullOrBlank() || !output.isNullOrBlank() || !diff.isNullOrBlank() || risk != null || !summary.isNullOrBlank()

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
private fun ToolTimelineRow(tool: ToolActivity, offset: String?, showOffsets: Boolean, hasPrevious: Boolean, hasNext: Boolean) {
    val status = tool.statusColor()
    val statusLabel = tool.statusLabel()
    val rail = Theme[colors][strokeStrong]
    Box {
        Canvas(Modifier.matchParentSize()) {
            val inset = 12.dp.toPx()
            val x = if (layoutDirection == LayoutDirection.Ltr) inset else size.width - inset
            if (hasPrevious) drawLine(rail, Offset(x, 0f), Offset(x, 6.dp.toPx()), 1.dp.toPx())
            if (hasNext) drawLine(rail, Offset(x, 24.dp.toPx()), Offset(x, size.height), 1.dp.toPx())
        }
        UnstyledIcon(
            toolIcon(tool.name),
            contentDescription = null,
            tint = status,
            modifier = Modifier.align(Alignment.TopStart).padding(start = 5.dp, top = 8.dp).size(14.dp),
        )
        Row(
            Modifier.fillMaxWidth()
                .heightIn(min = 32.dp)
                .semantics(mergeDescendants = true) { stateDescription = statusLabel }
                .padding(start = 26.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Top,
        ) {
            if (showOffsets) Text(
                offset.orEmpty(), Modifier.widthIn(min = 44.dp, max = 80.dp),
                style = Theme[typography][caption], color = Theme[colors][textSecondary],
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(tool.title(), Modifier.weight(1f), style = Theme[typography][label], color = Theme[colors][text],
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            ToolDuration(tool)
            if (tool.failed) {
                UnstyledIcon(Lucide.X, null, tint = status, modifier = Modifier.size(14.dp))
            }
            if (tool.risk != null) {
                UnstyledIcon(Lucide.ShieldAlert, stringResource(Res.string.chat_tool_suspicious), tint = Theme[colors][warning], modifier = Modifier.size(14.dp))
            }
        }
    }
}

@Composable
private fun ToolDuration(tool: ToolActivity) {
    tool.durationSeconds?.takeIf(::validToolDuration)?.let {
        Text(formatDuration(it), style = Theme[typography][caption], color = Theme[colors][textSecondary])
    }
}

@Composable
private fun ToolDetailsLink(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val tint = Theme[colors][accentText]
    Row(
        modifier.heightIn(min = MinTouchTarget)
            .clip(RoundedCornerShape(Theme[radii][radiusSmall]))
            .handCursor()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(end = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = Theme[typography][caption], color = tint)
        UnstyledIcon(Lucide.ArrowRight, null, tint = tint, modifier = Modifier.size(14.dp))
    }
}

@Composable
private fun ToolDetailsViewer(tools: List<ToolActivity>, messageKey: String, onDismiss: () -> Unit) {
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var expandedIds by rememberSaveable { mutableStateOf(emptyArray<String>()) }
    val pages = rememberSaveableStateHolder()
    val selected = tools.firstOrNull { it.id == selectedId }
    fun back() {
        if (selected == null) onDismiss() else {
            pages.removeState("output:" + selected.id)
            selectedId = null
        }
    }
    Dialog(onDismissRequest = ::back, properties = fullScreenDialogProperties()) {
        Column(Modifier.fillMaxSize().background(Theme[colors][background]).windowInsetsPadding(WindowInsets.safeDrawing)) {
            Row(
                Modifier.fillMaxWidth().clearOfWindowControls().padding(start = 4.dp, end = 16.dp, top = 6.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                IconButton(Lucide.ArrowLeft, stringResource(Res.string.common_back), ::back)
                Text(
                    stringResource(if (selected == null) Res.string.chat_tool_details_page else Res.string.chat_tool_output_page),
                    style = Theme[typography][title], color = Theme[colors][text],
                )
            }
            pages.SaveableStateProvider(if (selected == null) "details" else "output:" + selected.id) {
                Column(
                    Modifier.fillMaxWidth().weight(1f).verticalScrollWithBar().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (selected == null) {
                        tools.forEach { tool ->
                            key(tool.id) {
                                val expanded = tool.id in expandedIds && tool.hasToolDetails()
                                ToolComparisonRow(tool, expanded, onToggle = {
                                    expandedIds = if (expanded) expandedIds.filterNot { it == tool.id }.toTypedArray()
                                    else expandedIds + tool.id
                                }, onOpenOutput = { selectedId = tool.id })
                            }
                        }
                    } else {
                        Text(selected.title(), style = Theme[typography][title], color = Theme[colors][text])
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(selected.statusLabel(), style = Theme[typography][caption], color = selected.statusColor())
                            ToolDuration(selected)
                        }
                        if (selected.output.isNullOrBlank() && selected.diff.isNullOrBlank()) {
                            Text(
                                selected.summary?.takeIf { it.isNotBlank() } ?: stringResource(Res.string.chat_tool_no_output),
                                style = Theme[typography][bodySmall], color = Theme[colors][textSecondary],
                            )
                        }
                        ToolDetailsContent(selected, messageKey)
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolComparisonRow(tool: ToolActivity, expanded: Boolean, onToggle: () -> Unit, onOpenOutput: () -> Unit) {
    val status = tool.statusLabel()
    val hasDetails = tool.hasToolDetails()
    val modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget)
    val toggle = if (hasDetails) modifier.handCursor().clickable(
        onClickLabel = stringResource(if (expanded) Res.string.chat_hide_details else Res.string.chat_show_details),
        role = Role.Button, onClick = onToggle,
    ) else modifier
    Column {
        Row(
            toggle.semantics(mergeDescendants = true) { stateDescription = status },
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            UnstyledIcon(toolIcon(tool.name), null, tint = tool.statusColor(), modifier = Modifier.size(16.dp))
            Text(tool.title(), Modifier.weight(1f), style = Theme[typography][label], color = Theme[colors][text])
            ToolDuration(tool)
            if (hasDetails) UnstyledIcon(
                if (expanded) Lucide.ChevronDown else Lucide.ChevronRight,
                null, tint = Theme[colors][textMuted], modifier = Modifier.size(14.dp),
            )
        }
        if (expanded) {
            val preview = if (tool.risk != null) stringResource(Res.string.chat_tool_risk) else
                sequenceOf(tool.output, tool.summary, tool.diff, tool.input).firstOrNull { !it.isNullOrBlank() }
            preview?.let {
                val shape = RoundedCornerShape(Theme[radii][radiusSmall])
                Text(
                    it.take(360), Modifier.fillMaxWidth().border(1.dp, Theme[colors][stroke], shape).padding(10.dp),
                    maxLines = 3, overflow = TextOverflow.Ellipsis,
                    style = Theme[typography][code], color = Theme[colors][text],
                )
            }
            ToolDetailsLink(stringResource(Res.string.chat_view_full_tool_output), onClick = onOpenOutput)
        }
        Box(Modifier.fillMaxWidth().size(1.dp).background(Theme[colors][stroke]))
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
