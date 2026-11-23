package dev.hermeskotlin.desktop

import dev.hermeskotlin.core.update.AppUpdate
import dev.hermeskotlin.desktop.web.Jcef
import dev.hermeskotlin.ui.update.UpdateInstaller
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.time.Duration
import kotlin.system.exitProcess

/**
 * Installs a release from the update banner. The installer is downloaded first, then:
 * - Windows: the MSI installs over this copy with only its progress bar showing, and Herald opens again after it.
 *   Herald quits first, as the installer can't replace the files of a running app.
 * - macOS and Linux: the DMG or DEB opens in the system's own installer (Finder, the software centre), which asks
 *   the user to finish there.
 *
 * Only an installed Herald does this; one run from a build opens the download in the browser instead.
 */
internal object DesktopUpdater : UpdateInstaller {

    /** Where jpackage's launcher put this copy of Herald; null when it wasn't installed. */
    private val launcher: String? = System.getProperty("jpackage.app-path")?.takeIf { File(it).isFile }

    override suspend fun install(update: AppUpdate): Boolean {
        if (inFlatpak) return false
        val url = update.downloadUrl ?: return false
        if (launcher == null) return false
        val file = try {
            download(url)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return false
        }
        return if (windows) {
            runMsi(file, launcher)
        } else {
            runCatching { Desktop.getDesktop().open(file) }.isSuccess
        }
    }

    private suspend fun download(url: String): File = withContext(Dispatchers.IO) {
        val client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(20))
            .build()
        val name = sanitizeUpdateFileName(url)
        val dir = createUpdateDir()
        val dirPath = dir.toPath().normalize()
        val partPath = dirPath.resolve("$name.part").normalize()
        val targetPath = dirPath.resolve(name).normalize()
        check(partPath.parent == dirPath && targetPath.parent == dirPath) { "Invalid update file name" }
        val part = partPath.toFile()
        val response = try {
            client.send(HttpRequest.newBuilder(URI(url)).GET().build(), HttpResponse.BodyHandlers.ofFile(partPath))
        } catch (e: Exception) {
            part.delete()
            dir.delete()
            throw e
        }
        if (response.statusCode() !in 200..299) {
            part.delete()
            dir.delete()
            error("HTTP ${response.statusCode()}")
        }
        targetPath.toFile().also { Files.move(partPath, it.toPath(), StandardCopyOption.REPLACE_EXISTING) }
    }

    /**
     * Hands the MSI to a hidden PowerShell that waits for Herald to quit, installs it (a per-user install, so no
     * admin prompt) and opens Herald again; then quits.
     */
    private fun runMsi(msi: File, launcher: String): Boolean {
        fun quoted(path: String) = "'" + path.replace("'", "''") + "'"
        val script = "Start-Sleep -Seconds 2; " +
            "Start-Process msiexec.exe -ArgumentList '/i', ${quoted("\"${msi.absolutePath}\"")}, '/passive', '/norestart' -Wait; " +
            "Start-Process ${quoted(launcher)}"
        val started = runCatching {
            ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden", "-Command", script).start()
        }.isSuccess
        if (started) {
            // Chromium's helpers hold its files, which the installer replaces.
            Jcef.shutdown()
            exitProcess(0)
        }
        return false
    }

    private val windows = System.getProperty("os.name").orEmpty().startsWith("Windows")
}

private const val ILLEGAL_FILENAME_CHARS = "/\\:*?\"<>|"

/** Extracts a safe single filename from [url], stripping path separators, traversal segments and control chars. */
internal fun sanitizeUpdateFileName(url: String): String {
    val raw = url.substringBefore('#').substringBefore('?')
        .substringAfterLast('/')
        .substringAfterLast('\\')
        .trim()
    val cleaned = buildString(raw.length) {
        for (ch in raw) {
            if (ch.code >= 0x20 && ch !in ILLEGAL_FILENAME_CHARS) append(ch)
        }
    }.replace("..", "").trim('.', ' ')
    return cleaned.ifBlank { "herald-update" }
}

/** Creates an unpredictable temporary directory for a downloaded installer, owner-only (0700) on POSIX. */
internal fun createUpdateDir(): File {
    val path = if ("posix" in FileSystems.getDefault().supportedFileAttributeViews()) {
        Files.createTempDirectory(
            "herald-update-",
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")),
        )
    } else {
        Files.createTempDirectory("herald-update-")
    }
    return path.toFile()
}
