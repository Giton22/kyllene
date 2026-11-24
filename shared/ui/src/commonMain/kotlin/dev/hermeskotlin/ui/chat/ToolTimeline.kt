package dev.hermeskotlin.ui.chat

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
import dev.hermeskotlin.designsystem.components.BottomSheet
import dev.hermeskotlin.designsystem.components.MinTouchTarget
import dev.hermeskotlin.designsystem.components.SheetHeader
import dev.hermeskotlin.designsystem.components.handCursor
import dev.hermeskotlin.designsystem.components.verticalScrollWithBar
import dev.hermeskotlin.ui.resources.*
import kotlin.math.roundToLong
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/** Completed tools read like the other disclosures in the reply. The list itself is a flat timeline. */
@Composable
internal fun Tools(tools: List<ToolActivity>, messageKey: String) {
    var expanded by rememberSaveable(messageKey) { mutableStateOf(false) }
    var selectedId by rememberSaveable(messageKey) { mutableStateOf<String?>(null) }
    var detailsVisible by rememberSaveable(messageKey) { mutableStateOf(false) }
    val icons = remember(tools) { tools.map { toolIcon(it.name) }.distinct().take(3) }
    val expandedState = stringResource(if (expanded) Res.string.chat_expanded else Res.string.chat_collapsed)
    val interaction = remember { MutableInteractionSource() }
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
            UnstyledIcon(
                if (expanded) Lucide.ChevronDown else Lucide.ChevronRight,
                contentDescription = null,
                tint = Theme[colors][textMuted],
                modifier = Modifier.size(13.dp),
            )
        }
        if (expanded) {
            val offsets = remember(tools) { toolTimelineOffsets(tools) }
            tools.forEachIndexed { index, tool ->
                key(tool.id) {
                    ToolTimelineRow(tool, offsets[index], index > 0, index < tools.lastIndex) {
                        selectedId = tool.id
                        detailsVisible = true
                    }
                }
            }
        }
    }
    tools.firstOrNull { it.id == selectedId }?.let { tool ->
        ToolResultsSheet(tool, messageKey, detailsVisible) { detailsVisible = false }
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

@Composable
private fun ToolTimelineRow(tool: ToolActivity, offset: String?, hasPrevious: Boolean, hasNext: Boolean, onClick: () -> Unit) {
    val status = when {
        tool.running -> Theme[colors][accentText]
        tool.failed -> Theme[colors][danger]
        else -> Theme[colors][success]
    }
    val rail = Theme[colors][strokeStrong]
    val title = tool.detail?.lineSequence()?.firstOrNull()?.trim().takeIf { it?.isNotEmpty() == true } ?: tool.name
    val summary = tool.summary?.trim()?.takeIf { it.isNotEmpty() && it != title }
    Box {
        Canvas(Modifier.matchParentSize().padding(start = 2.dp)) {
            val dot = Offset(10.dp.toPx(), 15.dp.toPx())
            if (hasPrevious) drawLine(rail, Offset(dot.x, 0f), dot, 1.dp.toPx())
            if (hasNext) drawLine(rail, dot, Offset(dot.x, size.height), 1.dp.toPx())
            drawCircle(status, radius = 3.5.dp.toPx(), center = dot)
        }
        Row(
            Modifier.fillMaxWidth()
                .heightIn(min = MinTouchTarget)
                .clip(RoundedCornerShape(Theme[radii][radiusSmall]))
                .handCursor()
                .clickable(onClickLabel = stringResource(Res.string.chat_show_details), role = Role.Button, onClick = onClick)
                .padding(start = 26.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Text(offset.orEmpty(), Modifier.widthIn(min = 44.dp), style = Theme[typography][caption], color = Theme[colors][textSecondary])
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = Theme[typography][label], color = Theme[colors][text], maxLines = 2, overflow = TextOverflow.Ellipsis)
                summary?.let {
                    Text(it, style = Theme[typography][caption], color = Theme[colors][textTertiary], maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            if (tool.failed) {
                UnstyledIcon(Lucide.X, stringResource(Res.string.chat_tool_failed), tint = status, modifier = Modifier.size(14.dp))
            }
            if (tool.risk != null) {
                UnstyledIcon(Lucide.ShieldAlert, stringResource(Res.string.chat_tool_suspicious), tint = Theme[colors][warning], modifier = Modifier.size(14.dp))
            }
            UnstyledIcon(Lucide.ChevronRight, null, tint = Theme[colors][textMuted], modifier = Modifier.size(14.dp))
        }
    }
}

@Composable
internal fun ToolResultsSheet(tool: ToolActivity, messageKey: String, visible: Boolean, onDismiss: () -> Unit) {
    BottomSheet(visible = visible, onDismiss = onDismiss, fromBottom = true) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { SheetHeader(tool.name, tool.detail) }
            Box(
                Modifier.size(MinTouchTarget)
                    .handCursor()
                    .clickable(role = Role.Button, onClick = onDismiss),
                contentAlignment = Alignment.Center,
            ) {
                UnstyledIcon(Lucide.X, stringResource(Res.string.chat_close), tint = Theme[colors][textSecondary], modifier = Modifier.size(18.dp))
            }
        }
        key(tool.id) {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 480.dp).verticalScrollWithBar().padding(horizontal = 18.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (tool.output == null && !tool.running) {
                    Text(
                        tool.summary?.takeIf { it.isNotBlank() } ?: stringResource(Res.string.chat_tool_no_output),
                        style = Theme[typography][bodySmall],
                        color = Theme[colors][textSecondary],
                    )
                }
                ToolDetailsContent(tool, messageKey)
            }
        }
    }
}

/** Use recorded start times so a gap or concurrent calls do not create fictional durations. */
internal fun toolTimelineOffsets(tools: List<ToolActivity>): List<String?> {
    val origin = tools.mapNotNull { it.startedAt?.takeIf(Double::isFinite) }.minOrNull()
    return tools.map { tool ->
        val start = tool.startedAt?.takeIf(Double::isFinite)
        if (origin == null || start == null) null else {
            val tenths = ((start - origin) * 10).roundToLong()
            "+${tenths / 10}.${tenths % 10}s"
        }
    }
}
