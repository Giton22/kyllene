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

private fun askStorageRetry(guidance: String): Boolean {
    val text = guidance.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    var choice = JOptionPane.CLOSED_OPTION
    SwingUtilities.invokeAndWait {
        choice = JOptionPane.showOptionDialog(null, "<html><body style='width:240px'>$text</body></html>",
            "Kyllene storage", JOptionPane.DEFAULT_OPTION, JOptionPane.ERROR_MESSAGE,
            null, arrayOf("Retry", "Quit"), "Retry")
    }
    return choice == 0
}
