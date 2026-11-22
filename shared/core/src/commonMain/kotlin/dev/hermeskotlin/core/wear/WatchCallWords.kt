package dev.hermeskotlin.core.wear

/** A line the phone sends to the watch's call screen when a call can't start. [english] is its text in English. */
enum class WatchCallLine(val english: String) {
    SignedOut("Sign in to Kyllene on your phone first."),
    OnAnotherCall("You're already on a call with Hermes. Hang up there first."),
    ConversationChanged("Conversation changed. Choose it again on your watch."),
    TargetChanged("Default Call Target changed. Tap Call again."),
    Unreachable("Kyllene can't reach your gateway right now."),
    BotUnavailable("This bot is no longer available. Choose another conversation."),
    BotChatFailed("Couldn't open the bot's conversation."),
    TargetInvalid("Invalid Default Call Target. Choose it again on your phone."),
    TargetBotGone("That bot no longer exists. Choose another target on your phone."),
    TargetUnopened("Couldn't open Default Call Target. Check it on your phone."),
    TargetReplaced("A newer call replaced this request."),
    TargetUnreached("Couldn't reach Default Call Target. Try again."),
    TargetFailed("Couldn't open Default Call Target."),
}

/**
 * The text of a [WatchCallLine] in the app's language, read each time it's said, so a language picked meanwhile is
 * used. Core has no string catalogue: the app gives one backed by its screens' strings.
 */
fun interface WatchCallWords {
    suspend fun say(line: WatchCallLine): String

    companion object {
        val English = WatchCallWords { it.english }
    }
}
