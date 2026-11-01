package dev.hermeskotlin.designsystem.components

import android.content.ClipData
import androidx.compose.ui.platform.ClipEntry

actual fun plainTextClipEntry(text: String): ClipEntry = ClipEntry(ClipData.newPlainText("text", text))

/** Android pastes through the system text toolbar; the composer's right-click menu is desktop only. */
actual fun clipboardText(): String? = null
