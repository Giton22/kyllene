package dev.hermeskotlin.core.storage

import dev.hermeskotlin.core.settings.BlobFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** A [BlobFile] in the app's private files. Writes land in a temporary file first, so a crash never leaves half an image. */
class FileBlobFile(private val file: File) : BlobFile {

    override suspend fun read(): ByteArray? = withContext(Dispatchers.IO) {
        file.takeIf { it.isFile }?.readBytes()
    }

    override suspend fun write(bytes: ByteArray) = withContext(Dispatchers.IO) {
        val parent = file.absoluteFile.parentFile.toPath()
        Files.createDirectories(parent)
        val partial = Files.createTempFile(parent, "${file.name}.", ".part")
        try {
            Files.write(partial, bytes)
            val target = file.toPath()
            try {
                Files.move(partial, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: Exception) {
            runCatching { Files.deleteIfExists(partial) }
            throw e
        }
        Unit
    }

    override suspend fun delete() = withContext(Dispatchers.IO) {
        file.delete()
        Unit
    }
}
