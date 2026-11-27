package dev.hermeskotlin.core.auth

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Keeps the app able to answer the browser while a [BrowserSignIn] waits for it to come back. On Android, a short
 * foreground service: with the browser in front, Android otherwise freezes the app, or (battery usage Restricted)
 * drops the browser's connection to it, and the sign-in never finishes (#555).
 */
interface SignInKeepAlive {
    /**
     * Android won't let the app answer the browser from the background at all (battery usage Restricted), so a
     * sign-in in the browser can't finish until that changes. Followed while the sign-in screen shows.
     */
    val restricted: StateFlow<Boolean>

    /** Holds the app up until [release]. False when the platform refused; the sign-in goes on regardless. */
    fun hold(): Boolean

    fun release()

    /** Where nothing needs holding up: the desktop app and tests. */
    object None : SignInKeepAlive {
        override val restricted: StateFlow<Boolean> = MutableStateFlow(false)

        override fun hold() = true

        override fun release() = Unit
    }
}
