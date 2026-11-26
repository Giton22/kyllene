package dev.hermeskotlin.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.BadgeAlert
import com.composables.icons.lucide.CircleHelp
import com.composables.icons.lucide.Heart
import com.composables.icons.lucide.Laugh
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.SearchCheck
import com.composables.icons.lucide.StepForward
import com.composables.icons.lucide.ThumbsDown
import com.composables.icons.lucide.ThumbsUp
import com.composeunstyled.Text
import com.composeunstyled.UnstyledIcon
import com.composeunstyled.theme.Theme
import dev.hermeskotlin.core.chat.ReplyReaction
import dev.hermeskotlin.designsystem.accent
import dev.hermeskotlin.designsystem.body
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.typography
import dev.hermeskotlin.ui.resources.Res
import dev.hermeskotlin.ui.resources.chat_react_acknowledge
import dev.hermeskotlin.ui.resources.chat_react_continue
import dev.hermeskotlin.ui.resources.chat_react_explain
import dev.hermeskotlin.ui.resources.chat_react_verify
import org.jetbrains.compose.resources.stringResource

/** What a reaction is called, spoken and in its tooltip. */
@Composable
internal fun ReplyReaction.label(): String = stringResource(
    when (this) {
        ReplyReaction.Acknowledge -> Res.string.chat_react_acknowledge
        ReplyReaction.Continue -> Res.string.chat_react_continue
        ReplyReaction.Verify -> Res.string.chat_react_verify
        ReplyReaction.Explain -> Res.string.chat_react_explain
    },
)

/** The line icon one of the four shows as, in the More menu and beside Copy once chosen. */
internal val ReplyReaction.icon: ImageVector
    get() = when (this) {
        ReplyReaction.Acknowledge -> Lucide.ThumbsUp
        ReplyReaction.Continue -> Lucide.StepForward
        ReplyReaction.Verify -> Lucide.SearchCheck
        ReplyReaction.Explain -> Lucide.CircleHelp
    }

/**
 * The line icon a reaction shows as, in the style of the reply's other icons. The gateway stores the emoji, so
 * other clients see the same reaction; Desktop's own set gets icons too. Null: shown as the emoji itself.
 */
internal fun reactionIcon(emoji: String): ImageVector? = ReplyReaction.entries.firstOrNull { it.emoji == emoji }?.icon ?: when (emoji) {
    "❤️", "❤" -> Lucide.Heart
    "👎" -> Lucide.ThumbsDown
    "😂" -> Lucide.Laugh
    "‼️", "‼" -> Lucide.BadgeAlert
    else -> null
}

/**
 * The reaction the user chose, beside Copy: its icon in the accent, read out by its name. One set on another
 * client from outside these four shows as its icon, or as its emoji when there's none to match it.
 */
@Composable
internal fun ReplyReactionBadge(emoji: String, modifier: Modifier = Modifier) {
    val icon = reactionIcon(emoji)
    val spoken = ReplyReaction.entries.firstOrNull { it.emoji == emoji }?.label() ?: emoji
    Box(
        modifier.padding(horizontal = ReplyReactionBadgePadding).semantics(mergeDescendants = true) {
            contentDescription = spoken
            selected = true
        },
        contentAlignment = Alignment.Center,
    ) {
        if (icon != null) {
            UnstyledIcon(icon, contentDescription = null, tint = Theme[colors][accent], modifier = Modifier.size(ReplyReactionBadgeIcon))
        } else {
            Text(emoji, style = Theme[typography][body], singleLine = true, modifier = Modifier.clearAndSetSemantics {})
        }
    }
}

internal val ReplyReactionBadgeIcon = 16.dp
internal val ReplyReactionBadgePadding = 6.dp
