package dev.hermeskotlin.ui

import android.content.res.Configuration
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.window.DialogProperties

@OptIn(ExperimentalLayoutApi::class)
internal actual val imeVisible: Boolean
    @Composable get() = WindowInsets.isImeVisible

// The configuration changes as a keyboard is connected or taken away, and LocalConfiguration follows it.
internal actual val hardwareKeyboard: Boolean
    @Composable get() = LocalConfiguration.current.let {
        it.keyboard != Configuration.KEYBOARD_NOKEYS && it.hardKeyboardHidden == Configuration.HARDKEYBOARDHIDDEN_NO
    }

internal actual fun fullScreenDialogProperties() = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)

// A phone's Enter starts a new line; Send is the button.
internal actual val enterSends: Boolean = false

internal actual val macKeys: Boolean = false

@Composable
internal actual fun PlatformTextContextMenu(content: @Composable () -> Unit) = content()

internal actual val hasVoice: Boolean = true

internal actual val hasSpeechPlayback: Boolean = true
