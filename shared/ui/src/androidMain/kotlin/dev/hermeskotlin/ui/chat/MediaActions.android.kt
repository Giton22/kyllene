package dev.hermeskotlin.ui.chat

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import dev.hermeskotlin.ui.resources.*
import org.jetbrains.compose.resources.getString
import java.io.File

private val ShareScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

@Composable
actual fun rememberMediaActions(): MediaActions {
    val context = LocalContext.current
    return remember(context) { AndroidMediaActions(context.applicationContext) }
}

private class AndroidMediaActions(private val context: Context) : MediaActions {

    override suspend fun save(bytes: ByteArray, name: String): String = withContext(Dispatchers.IO) {
        // MediaStore renames a file whose extension doesn't match its type (todo.md as text/plain became
        // todo.md.txt), so take the type from Android's own table; unknown types keep the name as is.
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
            ?: "application/octet-stream"
        val image = mime.startsWith("image/")
        // Before Android 10 this needs a storage permission; sharing to Files covers those phones.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return@withContext getString(Res.string.android_save_needs_android_10)
        val collection = if (image) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }
        val folder = (if (image) Environment.DIRECTORY_PICTURES else Environment.DIRECTORY_DOWNLOADS) + "/Kyllene"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, folder)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(collection, values) ?: return@withContext getString(Res.string.android_save_failed, name)
        val saved = try {
            resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("no stream")
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            true
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            false
        }
        if (saved) getString(Res.string.android_saved_to, folder) else getString(Res.string.android_save_failed, name)
    }

    override fun share(bytes: ByteArray, name: String) {
        ShareScope.launch {
            runCatching {
                val shareRoot = File(context.cacheDir, SHARE_DIR)
                cleanOldShares(shareRoot)
                val safeName = sanitizeShareFileName(name)
                // A fresh folder per share keeps the original file name, which the receiving app shows.
                val dir = File(shareRoot, System.currentTimeMillis().toString()).apply { mkdirs() }
                val file = File(dir, safeName)
                if (file.canonicalFile.parentFile != dir.canonicalFile) return@runCatching
                file.writeBytes(bytes)
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.attachments", file)
                val send = Intent(Intent.ACTION_SEND)
                    .setType(mimeTypeOf(safeName))
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                val chooser = Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                withContext(Dispatchers.Main) {
                    context.startActivity(chooser)
                }
            }
        }
    }

    private fun cleanOldShares(shareRoot: File) {
        val entries = shareRoot.listFiles() ?: return
        val cutoff = System.currentTimeMillis() - SHARE_MAX_AGE_MS
        val recent = entries.filter { it.lastModified() >= cutoff }.sortedByDescending { it.lastModified() }
        val keep = recent.take(MAX_SHARED_FOLDERS).toSet()
        for (entry in entries) {
            if (entry !in keep) entry.deleteRecursively()
        }
    }

    private companion object {
        const val SHARE_DIR = "shared"
        const val SHARE_MAX_AGE_MS = 15 * 60_000L
        const val MAX_SHARED_FOLDERS = 5
    }
}
