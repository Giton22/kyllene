package dev.hermeskotlin.core.storage

import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileKeyValueStoreTest {

    @Test
    fun putsOverwritesAndRemovesValuesAtomically() = runBlocking {
        val dir = Files.createTempDirectory("kyllene-kv-").toFile()
        try {
            val file = File(dir, "settings.json")
            val store = FileKeyValueStore(file)
            assertNull(store.get("a"))

            store.put("a", "first")
            assertEquals("first", store.get("a"))

            // Overwriting an existing file must succeed on Windows without delete-before-rename.
            store.put("a", "second")
            store.put("b", "two")
            assertEquals("second", FileKeyValueStore(file).get("a"))
            assertEquals("two", FileKeyValueStore(file).get("b"))
            assertTrue(dir.listFiles().orEmpty().none { it.name.endsWith(".part") })

            val isWindows = System.getProperty("os.name").orEmpty().lowercase().startsWith("windows")
            if (!isWindows) {
                runCatching { Files.getPosixFilePermissions(file.toPath()) }.getOrNull()?.let { perms ->
                    assertEquals(PosixFilePermissions.fromString("rw-------"), perms)
                }
            }

            store.remove("a")
            assertNull(FileKeyValueStore(file).get("a"))
            assertEquals("two", FileKeyValueStore(file).get("b"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun transientReadFailureDoesNotCacheEmptyMapAndCorruptFileIsNotSilentlyWiped() = runBlocking {
        val dir = Files.createTempDirectory("kyllene-kv-io-").toFile()
        try {
            val file = File(dir, "settings.json")
            // A directory at the target path causes an IOException (not NoSuchFileException) on read.
            check(file.mkdir())
            val store = FileKeyValueStore(file)
            assertNull(store.get("k"))

            // Once the path is a real valid JSON file, a subsequent get() reads it (was not cached empty).
            check(file.delete())
            FileKeyValueStore(file).put("k", "persisted")
            assertEquals("persisted", store.get("k"))

            // Corrupt JSON fails put() instead of wiping the file.
            file.writeText("{not-json")
            val corrupt = FileKeyValueStore(file)
            assertNull(corrupt.get("k"))
            assertFailsWith<IOException> { corrupt.put("new", "value") }
            assertEquals("{not-json", file.readText())
        } finally {
            dir.deleteRecursively()
        }
    }
}
