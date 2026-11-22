package dev.hermeskotlin.ui.voice

import dev.hermeskotlin.core.wear.WatchCallLine
import dev.hermeskotlin.core.wear.WatchCallWords
import dev.hermeskotlin.ui.resources.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString

/** What the watch's call screen is told, from the app's string catalogue: in the language the app is in. */
object CatalogueWatchCallWords : WatchCallWords {
    override suspend fun say(line: WatchCallLine): String = getString(resourceOf(line))

    internal fun resourceOf(line: WatchCallLine): StringResource = when (line) {
        WatchCallLine.SignedOut -> Res.string.watch_call_signed_out
        WatchCallLine.OnAnotherCall -> Res.string.watch_call_on_another_call
        WatchCallLine.ConversationChanged -> Res.string.watch_call_conversation_changed
        WatchCallLine.TargetChanged -> Res.string.watch_call_target_changed
        WatchCallLine.Unreachable -> Res.string.watch_call_unreachable
        WatchCallLine.BotUnavailable -> Res.string.watch_call_bot_unavailable
        WatchCallLine.BotChatFailed -> Res.string.watch_call_bot_chat_failed
        WatchCallLine.TargetInvalid -> Res.string.watch_call_target_invalid
        WatchCallLine.TargetBotGone -> Res.string.watch_call_target_bot_gone
        WatchCallLine.TargetUnopened -> Res.string.watch_call_target_unopened
        WatchCallLine.TargetReplaced -> Res.string.watch_call_target_replaced
        WatchCallLine.TargetUnreached -> Res.string.watch_call_target_unreached
        WatchCallLine.TargetFailed -> Res.string.watch_call_target_failed
    }
}
