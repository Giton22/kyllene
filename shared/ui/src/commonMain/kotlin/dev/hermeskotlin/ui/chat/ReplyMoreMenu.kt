package dev.hermeskotlin.ui.chat

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
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
    DropdownMenu(
        expanded = open,
        onExpandedChange = onOpenChange,
        above = true,
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
            if (labelled) {
                Button(
                    text = label,
                    onClick = { onOpenChange(true) },
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
                    onClick = { onOpenChange(true) },
                    modifier = Modifier.size(if (desktop) 32.dp else MinTouchTarget),
                    iconSize = 16.dp,
                )
            }
        },
    )
}
