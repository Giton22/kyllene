package dev.hermeskotlin.designsystem.components

import androidx.compose.ui.platform.ClipEntry

/** A plain-text clipboard entry; building one is platform specific. */
expect fun plainTextClipEntry(text: String): ClipEntry

/** Reads plain text from the clipboard; platform specific. */
expect fun clipboardText(): String?
