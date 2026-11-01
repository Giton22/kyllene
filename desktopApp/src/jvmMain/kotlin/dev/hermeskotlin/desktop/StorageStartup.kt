package dev.hermeskotlin.desktop

import dev.hermeskotlin.core.storage.SecureStorageException
import javax.swing.JOptionPane
import javax.swing.SwingUtilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/** The same startup attempt waits for an explicit retry; eager consumers have not been created yet. */
internal fun unlockDesktopStorage(
    prepare: suspend () -> Unit,
    retry: (String) -> Boolean = ::askStorageRetry,
): Boolean {
    check(!SwingUtilities.isEventDispatchThread()) { "Storage unlock must not run on the AWT event thread." }
    while (true) {
        try {
            runBlocking(Dispatchers.IO) { prepare() }
            return true
        } catch (failure: SecureStorageException) {
            if (!retry(failure.reason.guidance)) return false
        }
    }
}

internal fun storageUnlockPane(guidance: String): JOptionPane {
    val text = guidance.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    return JOptionPane("<html><body style='width:200px'>$text</body></html>",
        JOptionPane.ERROR_MESSAGE, JOptionPane.DEFAULT_OPTION, null, arrayOf("Retry", "Quit"), "Retry")
}

private fun askStorageRetry(guidance: String): Boolean {
    var retry = false
    SwingUtilities.invokeAndWait {
        val pane = storageUnlockPane(guidance)
        val dialog = pane.createDialog(null, "Kyllene storage")
        try { dialog.isVisible = true; retry = pane.value == "Retry" }
        finally { dialog.dispose() }
    }
    return retry
}
