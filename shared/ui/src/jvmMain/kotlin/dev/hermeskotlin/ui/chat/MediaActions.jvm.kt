package dev.hermeskotlin.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import dev.hermeskotlin.ui.resources.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.getString
import java.awt.Desktop
import java.io.File
import java.nio.file.Files

private val ShareScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/** Extensions that [Desktop.open] could execute rather than display; defanged with `.txt` before opening. */
private val DANGEROUS_DESKTOP_EXTENSIONS = setOf(
    "exe", "bat", "cmd", "com", "msi", "msp", "mst",
    "vbs", "vbe", "js", "jse", "wsf", "wsh", "ps1", "psm1", "psd1",
    "scr", "lnk", "pif", "jar", "hta", "cpl", "reg", "inf",
    "sh", "bash", "zsh", "csh", "ksh", "app", "command", "tool", "workflow", "action",
    "desktop", "run", "bin", "dmg", "pkg", "deb", "rpm", "appimage",
)

/**
 * Resolves [name] to a safe file directly inside [dir]: path traversal and control characters are stripped,
 * dangerous executable extensions are defanged with `.txt`, and the normalized path is verified to stay in [dir].
 */
internal fun safeDesktopShareFile(dir: File, name: String): File? {
    val sanitized = sanitizeShareFileName(name)
    val ext = sanitized.substringAfterLast('.', "").lowercase()
    val safeName = if (ext in DANGEROUS_DESKTOP_EXTENSIONS) "$sanitized.txt" else sanitized
    val basePath = dir.toPath().toAbsolutePath().normalize()
    val targetPath = basePath.resolve(safeName).normalize()
    if (!targetPath.startsWith(basePath) || targetPath == basePath || targetPath.parent != basePath) return null
    return targetPath.toFile()
}

/** Save is the system's Save dialog; with no share sheet, Share opens the file in its default app. */
@Composable
actual fun rememberMediaActions(): MediaActions = remember {
    object : MediaActions {
        override suspend fun save(bytes: ByteArray, name: String): String =
            saveOutcome(bytes, name)?.text ?: getString(Res.string.desktop_save_cancelled)

        override suspend fun saveOutcome(bytes: ByteArray, name: String): SaveOutcome? {
            val title = getString(Res.string.desktop_save_title)
            val file = fromDialog { saveFile(title, name) }
                .getOrElse { return SaveOutcome(getString(Res.string.desktop_save_dialog_failed), saved = false) }
                ?: return null
            val written = withContext(Dispatchers.IO) { runCatching { file.writeBytes(bytes) } }
            return written.fold(
                { SaveOutcome(getString(Res.string.desktop_saved_to, file.path), saved = true) },
                { SaveOutcome(getString(Res.string.desktop_save_failed, it.message.toString()), saved = false) },
            )
        }

        override fun share(bytes: ByteArray, name: String) {
            ShareScope.launch {
                runCatching {
                    val dir = Files.createTempDirectory("herald").toFile().apply { deleteOnExit() }
                    val file = safeDesktopShareFile(dir, name) ?: return@runCatching
                    file.writeBytes(bytes)
                    file.deleteOnExit()
                    if (Desktop.isDesktopSupported()) Desktop.getDesktop().open(file)
                }
            }
        }
    }
}
