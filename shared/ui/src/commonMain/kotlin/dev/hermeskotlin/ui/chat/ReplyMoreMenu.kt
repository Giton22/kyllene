package dev.hermeskotlin.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import com.composables.icons.lucide.Ellipsis
import com.composables.icons.lucide.Lucide
import dev.hermeskotlin.core.chat.ReplyReaction
import dev.hermeskotlin.designsystem.components.Button
import dev.hermeskotlin.designsystem.components.ButtonSize
import dev.hermeskotlin.designsystem.components.ButtonVariant
import dev.hermeskotlin.designsystem.components.ContextMenu
import dev.hermeskotlin.designsystem.components.MenuRow
import dev.hermeskotlin.designsystem.components.MinTouchTarget
import dev.hermeskotlin.ui.resources.Res
import dev.hermeskotlin.ui.resources.chat_react_acknowledge
import dev.hermeskotlin.ui.resources.chat_react_continue
import dev.hermeskotlin.ui.resources.chat_react_explain
import dev.hermeskotlin.ui.resources.chat_react_verify
import dev.hermeskotlin.ui.resources.chat_reply_more
import dev.hermeskotlin.ui.sessions.RowMenuItem
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun ReplyMoreMenu(
    actions: List<RowMenuItem>,
    buttonModifier: Modifier = Modifier,
    onReact: ((ReplyReaction) -> Unit)? = null,
) {
    var open by remember { mutableStateOf(false) }
    var anchor by remember { mutableStateOf<Rect?>(null) }
    Box(Modifier.onGloballyPositioned { anchor = it.boundsInWindow() }) {
        Button(
            text = stringResource(Res.string.chat_reply_more),
            onClick = { open = true },
            modifier = (if (open) Modifier else buttonModifier).heightIn(min = MinTouchTarget),
            variant = ButtonVariant.Ghost,
            size = ButtonSize.Small,
            leadingIcon = Lucide.Ellipsis,
        )
    }
    ContextMenu(open, onDismiss = { open = false }, anchor = anchor) {
        if (onReact != null) {
            for (reaction in ReplyReaction.entries) {
                val label = stringResource(when (reaction) {
                    ReplyReaction.Acknowledge -> Res.string.chat_react_acknowledge
                    ReplyReaction.Continue -> Res.string.chat_react_continue
                    ReplyReaction.Verify -> Res.string.chat_react_verify
                    ReplyReaction.Explain -> Res.string.chat_react_explain
                })
                MenuRow(label, icon = null, leadingEmoji = reaction.emoji, onClick = {
                    open = false
                    onReact(reaction)
                })
            }
        }
        for (action in actions) {
            MenuRow(action.label, action.icon, onClick = {
                open = false
                action.run()
            })
        }
    }
}
