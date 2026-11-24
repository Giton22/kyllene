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
import dev.hermeskotlin.designsystem.components.MenuEmojiChoice
import dev.hermeskotlin.designsystem.components.MinTouchTarget
import dev.hermeskotlin.ui.resources.Res
import dev.hermeskotlin.ui.resources.chat_react_acknowledge
import dev.hermeskotlin.ui.resources.chat_react_continue
import dev.hermeskotlin.ui.resources.chat_react_explain
import dev.hermeskotlin.ui.resources.chat_react_verify
import dev.hermeskotlin.ui.sessions.RowMenuItem
import org.jetbrains.compose.resources.stringResource

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
                    val reactionLabel = stringResource(when (reaction) {
                        ReplyReaction.Acknowledge -> Res.string.chat_react_acknowledge
                        ReplyReaction.Continue -> Res.string.chat_react_continue
                        ReplyReaction.Verify -> Res.string.chat_react_verify
                        ReplyReaction.Explain -> Res.string.chat_react_explain
                    })
                    MenuEmojiChoice(reactionLabel, reaction.emoji, reaction.emoji == selectedReaction) {
                        onReact(reaction)
                    }
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
