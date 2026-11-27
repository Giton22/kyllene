package dev.hermeskotlin.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.window.DialogProperties

internal actual val imeVisible: Boolean
    @Composable get() = WindowInsets.ime.getBottom(LocalDensity.current) > 0

// Not detected yet (it would take GameController's GCKeyboard). With a keyboard attached, iOS still shows its
// shortcut bar as the keyboard, so the composer keeps its focus while one is in use.
internal actual val hardwareKeyboard: Boolean
    @Composable get() = false

internal actual fun fullScreenDialogProperties() = DialogProperties(usePlatformDefaultWidth = false, usePlatformInsets = false)

// A phone's Return starts a new line; Send is the button.
internal actual val enterSends: Boolean = false

internal actual val macKeys: Boolean = false

@Composable
internal actual fun PlatformTextContextMenu(content: @Composable () -> Unit) = content()

// No recording, playback or live calls on iOS yet.
internal actual val hasVoice: Boolean = false

internal actual val hasSpeechPlayback: Boolean = false
