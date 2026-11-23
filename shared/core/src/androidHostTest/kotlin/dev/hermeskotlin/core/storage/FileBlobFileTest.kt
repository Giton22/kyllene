package dev.hermeskotlin.core.storage

import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileBlobFileTest {

    @Test
    fun writeOverwritesExistingFileWithoutLeavingTempFiles() = runBlocking {
        val dir = Files.createTempDirectory("kyllene-blob-").toFile()
        try {
            val target = File(dir, "wallpaper.jpg")
            val blob = FileBlobFile(target)
            assertNull(blob.read())

            blob.write(byteArrayOf(1, 2, 3))
            assertContentEquals(byteArrayOf(1, 2, 3), blob.read())

            // Overwriting an existing file must succeed on Windows as well as POSIX.
            blob.write(byteArrayOf(4, 5, 6, 7))
            assertContentEquals(byteArrayOf(4, 5, 6, 7), blob.read())
            assertTrue(dir.listFiles().orEmpty().none { it.name.endsWith(".part") })

            blob.delete()
            assertNull(blob.read())
        } finally {
            dir.deleteRecursively()
        }
    }
}
