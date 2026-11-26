package dev.hermeskotlin.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Ellipsis
import com.composables.icons.lucide.Lucide
import dev.hermeskotlin.core.chat.ReplyReaction
import dev.hermeskotlin.designsystem.LocalDesktopLayout
import dev.hermeskotlin.designsystem.components.Button
import dev.hermeskotlin.designsystem.components.ButtonSize
import dev.hermeskotlin.designsystem.components.ButtonVariant
import dev.hermeskotlin.designsystem.components.DropdownMenu
import dev.hermeskotlin.designsystem.components.IconButton
import dev.hermeskotlin.designsystem.components.MenuAction
import dev.hermeskotlin.designsystem.components.MenuChoice
import dev.hermeskotlin.designsystem.components.MinTouchTarget
import dev.hermeskotlin.ui.sessions.RowMenuItem

@Composable
internal fun ReplyMoreMenu(
    actions: List<RowMenuItem>,
    label: String,
    labelled: Boolean,
    open: Boolean,
    onOpenChange: (Boolean) -> Unit,
    onReact: ((ReplyReaction) -> Unit)?,
    selectedReaction: String?,
) {
    val desktop = LocalDesktopLayout.current
    // Above the button, as it's usually low on the screen, or below it when there's more room there, as near the
    // top or in a short landscape window; no taller than that room, scrolling past it, so no row ends up under the
    // status bar, where it would take no taps. Read when it's tapped, not kept as state, so scrolling the
    // transcript doesn't recompose every reply.
    val density = LocalDensity.current
    val windowHeight = LocalWindowInfo.current.containerSize.height
    val insets = WindowInsets.safeDrawing
    val button = remember { arrayOfNulls<LayoutCoordinates>(1) }
    var above by remember { mutableStateOf(true) }
    var room by remember { mutableStateOf(Dp.Unspecified) }
    DropdownMenu(
        expanded = open,
        onExpandedChange = onOpenChange,
        above = above,
        maxHeight = room,
        items = {
            if (onReact != null) {
                for (reaction in ReplyReaction.entries) {
                    // A line icon like the menu's others; the gateway still stores the reaction's emoji.
                    MenuChoice(reaction.label(), reaction.icon, reaction.emoji == selectedReaction, onClick = {
                        onReact(reaction)
                    })
                }
            }
            for (action in actions) MenuAction(action.label, action.icon, action.run)
        },
        anchor = {
            Box(Modifier.onGloballyPositioned { button[0] = it }) {
                MoreButton(label, labelled, desktop) {
                    val bounds = button[0]?.takeIf { it.isAttached }?.boundsInWindow()
                    if (bounds == null || windowHeight <= 0) {
                        above = true
                        room = Dp.Unspecified
                    } else {
                        val roomAbove = bounds.top - insets.getTop(density)
                        val roomBelow = windowHeight - insets.getBottom(density) - bounds.bottom
                        above = roomAbove >= roomBelow
                        room = with(density) { maxOf(roomAbove, roomBelow).toDp() } - MenuEdgeMargin
                    }
                    onOpenChange(true)
                }
            }
        },
    )
}

/** Kept clear between the menu and the button, and between the menu and the system bars. */
private val MenuEdgeMargin = 16.dp

@Composable
private fun MoreButton(label: String, labelled: Boolean, desktop: Boolean, onClick: () -> Unit) {
    if (labelled) {
        Button(
            text = label,
            onClick = onClick,
            modifier = if (desktop) Modifier else Modifier.heightIn(min = MinTouchTarget),
            variant = ButtonVariant.Ghost,
            size = ButtonSize.Small,
            leadingIcon = Lucide.Ellipsis,
            accessibleLabel = label,
        )
    } else {
        IconButton(
            Lucide.Ellipsis,
            contentDescription = label,
            onClick = onClick,
            modifier = Modifier.size(if (desktop) 32.dp else MinTouchTarget),
            iconSize = 16.dp,
        )
    }
}
