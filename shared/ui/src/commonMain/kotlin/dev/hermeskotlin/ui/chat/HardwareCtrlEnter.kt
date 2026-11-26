package dev.hermeskotlin.ui.chat

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.Modifier

/**
 * Sends on hardware Ctrl+Enter on Android, including the active composing text (#482).
 * Ordinary Enter is left alone to insert a newline. On desktop, this is a no-op as desktop
 * uses [composerKey] where Enter sends and Ctrl+Enter queues.
 */
internal expect fun Modifier.sendOnHardwareCtrlEnter(
    state: TextFieldState,
    onSend: (() -> Unit)?,
): Modifier
