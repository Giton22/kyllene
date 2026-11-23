package dev.hermeskotlin.core.storage

import com.sun.jna.platform.win32.Crypt32Util
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.IOException
import java.nio.charset.CharacterCodingException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.util.Base64

/**
 * The desktop's [KeyValueStore]: one JSON file of values. On Windows each value is sealed with DPAPI, so only
 * this Windows user can open it, as the Keystore does on Android. Elsewhere (an experiment for now) the file
 * is only kept private to the user.
 *
 * A file that can't be parsed (cut short by a crash or a full disk, or damaged) is moved aside as
 * `<name>.corrupt` and the store starts empty, so the app still starts and saves.
 */
class FileKeyValueStore(private val file: File) : KeyValueStore {
    private val mutex = Mutex()
    private var values: MutableMap<String, String>? = null
    private val sealed = System.getProperty("os.name").orEmpty().lowercase().startsWith("windows")

    override suspend fun get(key: String): String? = mutex.withLock {
        val current = try {
            load()
        } catch (_: IOException) {
            return@withLock null
        }
        current[key]
    }?.let { stored ->
        runCatching { open(stored) }.getOrNull()
    }

    override suspend fun put(key: String, value: String) = mutex.withLock {
        val next = load().toMutableMap()
        next[key] = seal(value)
        save(next)
        values = next
    }

    override suspend fun remove(key: String) = mutex.withLock {
        val current = load()
        if (key !in current) return@withLock
        val next = current.toMutableMap()
        next.remove(key)
        save(next)
        values = next
    }

    private suspend fun load(): MutableMap<String, String> = values ?: withContext(Dispatchers.IO) {
        val text = try {
            Files.readString(file.toPath(), Charsets.UTF_8)
        } catch (_: NoSuchFileException) {
            return@withContext mutableMapOf<String, String>().also { values = it }
        } catch (_: CharacterCodingException) {
            null
        }
        val read = text?.let {
            runCatching {
                (Json.parseToJsonElement(it) as JsonObject)
                    .mapNotNull { (k, v) -> v.jsonPrimitive.contentOrNull?.let { content -> k to content } }
                    .toMap(mutableMapOf())
            }.getOrNull()
        }
        if (read == null) setAside()
        (read ?: mutableMapOf()).also { values = it }
    }

    /**
     * Moves the unreadable file to `<name>.corrupt`, in place of an older one, so the next save doesn't write
     * over the only copy. A file that can't be moved fails the read, and the next one tries again.
     */
    private fun setAside() {
        val kept = file.toPath().resolveSibling("${file.name}.corrupt")
        Files.move(file.toPath(), kept, StandardCopyOption.REPLACE_EXISTING)
    }

    private suspend fun save(entries: Map<String, String>) = withContext(Dispatchers.IO) {
        val text = JsonObject(entries.mapValues { JsonPrimitive(it.value) }).toString()
        val parent = file.absoluteFile.parentFile.toPath()
        Files.createDirectories(parent)
        val partial = if (!sealed) {
            try {
                Files.createTempFile(
                    parent,
                    "${file.name}.",
                    ".part",
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")),
                )
            } catch (_: UnsupportedOperationException) {
                Files.createTempFile(parent, "${file.name}.", ".part")
            }
        } else {
            Files.createTempFile(parent, "${file.name}.", ".part")
        }
        try {
            Files.writeString(partial, text, Charsets.UTF_8)
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
    }

    private fun seal(value: String): String =
        if (sealed) Base64.getEncoder().encodeToString(Crypt32Util.cryptProtectData(value.encodeToByteArray())) else value

    private fun open(stored: String): String =
        if (sealed) Crypt32Util.cryptUnprotectData(Base64.getDecoder().decode(stored)).decodeToString() else stored
}
