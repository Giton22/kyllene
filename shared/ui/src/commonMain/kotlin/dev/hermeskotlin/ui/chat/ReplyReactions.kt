package dev.hermeskotlin.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import com.composeunstyled.UnstyledButton
import com.composeunstyled.UnstyledIcon
import com.composeunstyled.theme.Theme
import dev.hermeskotlin.core.chat.ReplyReaction
import dev.hermeskotlin.designsystem.LocalDesktopLayout
import dev.hermeskotlin.designsystem.accent
import dev.hermeskotlin.designsystem.accentSoft
import dev.hermeskotlin.designsystem.body
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.components.MinTouchTarget
import dev.hermeskotlin.designsystem.components.Tooltip
import dev.hermeskotlin.designsystem.components.handCursor
import dev.hermeskotlin.designsystem.rememberHoverIndication
import dev.hermeskotlin.designsystem.textTertiary
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

/**
 * The line icon a reaction shows as, in the style of the reply's other icons. The gateway stores the emoji, so
 * other clients see the same reaction; Desktop's own set gets icons too. Null: shown as the emoji itself.
 */
internal fun reactionIcon(emoji: String): ImageVector? = when (emoji) {
    ReplyReaction.Acknowledge.emoji -> Lucide.ThumbsUp
    ReplyReaction.Continue.emoji -> Lucide.StepForward
    ReplyReaction.Verify.emoji -> Lucide.SearchCheck
    ReplyReaction.Explain.emoji -> Lucide.CircleHelp
    "❤️", "❤" -> Lucide.Heart
    "👎" -> Lucide.ThumbsDown
    "😂" -> Lucide.Laugh
    "‼️", "‼" -> Lucide.BadgeAlert
    else -> null
}

/**
 * One reaction under a reply: its icon in a ring when it's the one chosen, at the full touch size. [onClick]
 * null shows it without taking taps, as while offline. [alpha] fades an unchosen one, as the desktop's other
 * reply icons fade on an older reply; the chosen one always shows.
 */
@Composable
internal fun ReplyReactionButton(
    reaction: ReplyReaction,
    selected: Boolean,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    alpha: Float = 1f,
) {
    val desktop = LocalDesktopLayout.current
    val label = reaction.label()
    // Square like the reply's other icons: 32dp under a mouse, the full 48dp under a thumb.
    val target = if (desktop) 32.dp else MinTouchTarget
    val hidden = onClick == null && !selected
    val button = @Composable { outer: Modifier ->
        UnstyledButton(
            onClick = { onClick?.invoke() },
            enabled = onClick != null,
            modifier = outer.handCursor(onClick != null).size(target).clip(CircleShape).alpha(if (selected) 1f else alpha)
                // One faded out and taking no taps isn't read out either.
                .then(if (hidden) Modifier.clearAndSetSemantics {} else Modifier.semantics {
                    contentDescription = label
                    this.selected = selected
                    role = Role.Button
                }),
            indication = rememberHoverIndication(Theme[colors][textTertiary], CircleShape),
        ) {
            ReactionGlyph(reaction.emoji, selected)
        }
    }
    if (desktop) Tooltip(label, modifier) { button(Modifier) } else button(modifier)
}

/** A reaction the user set on another client from outside these four: shown as chosen, read out by its emoji. */
@Composable
internal fun OtherReactionBadge(emoji: String, modifier: Modifier = Modifier) {
    val target = if (LocalDesktopLayout.current) 32.dp else MinTouchTarget
    Box(
        modifier.size(target).semantics(mergeDescendants = true) {
            contentDescription = emoji
            selected = true
        },
        contentAlignment = Alignment.Center,
    ) {
        ReactionGlyph(emoji, selected = true)
    }
}

/** The reaction's icon, grey like the reply's others, or in the accent inside a ring when it's the one chosen. */
@Composable
private fun ReactionGlyph(emoji: String, selected: Boolean) {
    val desktop = LocalDesktopLayout.current
    val ring = if (desktop) 28.dp else 34.dp
    val icon = reactionIcon(emoji)
    Box(
        Modifier.size(ring)
            .background(if (selected) Theme[colors][accentSoft] else Color.Transparent, CircleShape)
            .border(1.dp, if (selected) Theme[colors][accent] else Color.Transparent, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (icon != null) {
            val tint = if (selected) Theme[colors][accent] else Theme[colors][textTertiary]
            UnstyledIcon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
        } else {
            // A reaction from some other client's set: its emoji, as there's no icon to match it.
            Text(
                emoji,
                style = Theme[typography][body].copy(fontSize = if (desktop) 14.sp else 16.sp, lineHeight = if (desktop) 16.sp else 20.sp),
                singleLine = true,
                modifier = Modifier.clearAndSetSemantics {},
            )
        }
    }
}
