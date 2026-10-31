package dev.hermeskotlin.core.storage

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest
import javax.crypto.SecretKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class SecureStorageException(val reason: Reason) : IOException(reason.guidance) {
    enum class Reason(val guidance: String) {
        Unavailable("Kyllene couldn't retrieve its storage key. Unlock your desktop keyring and check that its Secret portal is available, then retry."),
        Cancelled("Storage unlock was cancelled. Retry to unlock it; existing data has been preserved."),
        Corrupt("Kyllene couldn't open its encrypted settings. Restore a known-good backup or use the original application identity. Existing data has been preserved."),
        Version("This settings format needs a different version of Kyllene. Existing data has been preserved."),
        Read("Kyllene couldn't read its settings, or the file exceeds 16 MiB. Check file access before retrying."),
        Write("Kyllene couldn't finish saving or cleaning up its settings. Existing copies have been preserved; check disk space and file access before retrying."),
        Uncertain("Kyllene couldn't confirm its settings commit. Check file access, then retry startup or quit and restart before making further changes."),
        Changed("Settings changed outside this window. Quit and restart Kyllene to reload them before saving."),
        Legacy("Conflicting or unfinished legacy settings were found. Existing files were preserved. Quit and resolve the older copy before retrying."),
        Unsupported("Secure Linux storage requires the Flatpak build. No plaintext fallback is available."),
    }
}

/** The Flatpak's read-only metadata supplies the storage identity; no other profile is searched. */
internal fun desktopKeyValueStore(file: File): KeyValueStore {
    if (!System.getProperty("os.name").orEmpty().startsWith("Linux")) return FileKeyValueStore(file)
    val identity = try {
        File("/.flatpak-info").useLines { lines ->
            lines.dropWhile { it.trim() != "[Application]" }.drop(1).takeWhile { !it.startsWith("[") }
                .firstOrNull { it.startsWith("name=") }?.removePrefix("name=")?.takeIf { it.isNotBlank() }
        }
    } catch (_: IOException) { null }
    return LinuxKeyValueStore(File(file.absoluteFile.parentFile, "settings.secure"), identity, legacy = file)
}

/** Unlocks before desktop consumers start; other platform stores need no extra startup step. */
suspend fun prepareDesktopStorage(store: KeyValueStore) {
    if (store is LinuxKeyValueStore) store.prepare()
}

internal class LinuxKeyValueStore(
    file: File,
    private val appId: String?,
    private val legacy: File = File(file.absoluteFile.parentFile, "settings.json"),
    private val deleteLegacy: (Path) -> Unit = { Files.delete(it) },
    private val disk: AtomicSettingsFile = AtomicSettingsFile(file),
    private val retrieve: suspend (String?) -> PortalSecret = { LinuxSecretPortal().retrieve(it) },
) : KeyValueStore {
    private class KeyMaterial(val key: SecretKey, val token: String?)
    private class Snapshot(val values: Map<String, String>, val key: SecretKey, val token: String?,
        val digest: ByteArray?, val legacyDigest: ByteArray?)
    private val mutex = Mutex()
    private val legacyDisk = AtomicSettingsFile(legacy)
    private var state: Snapshot? = null
    private var ready = false
    private var blocked: SecureStorageException? = null
    private var pending: Snapshot? = null
    private var pendingBytes: ByteArray? = null

    suspend fun prepare() = mutex.withLock {
        if (blocked?.reason == SecureStorageException.Reason.Uncertain && pending != null) reconcileInitialCommit()
        load()
        Unit
    }
    override suspend fun get(key: String): String? = mutex.withLock { load().values[key] }
    override suspend fun put(key: String, value: String) = mutex.withLock {
        val previous = load()
        commit(Snapshot(previous.values + (key to value), previous.key, previous.token, previous.digest, previous.legacyDigest))
        Unit
    }
    override suspend fun remove(key: String) = mutex.withLock {
        val previous = load()
        if (key in previous.values) commit(Snapshot(previous.values - key, previous.key, previous.token, previous.digest, previous.legacyDigest))
        Unit
    }

    private suspend fun load(): Snapshot {
        blocked?.let { throw it }
        state?.let { return if (ready) it else activate(it) }
        pending?.let { return activate(commit(it)) }
        if (appId.isNullOrBlank()) throw SecureStorageException(SecureStorageException.Reason.Unsupported)
        if (Files.exists(File(legacy.parentFile, legacy.name + ".part").toPath(), LinkOption.NOFOLLOW_LINKS)) {
            throw SecureStorageException(SecureStorageException.Reason.Legacy)
        }
        val bytes = withContext(Dispatchers.IO) { disk.read() }
        try {
            val candidate: Snapshot
            if (bytes != null) {
                val root = SettingsEnvelope.parse(bytes)
                // An authoritative encrypted path can never become a legacy import, even if its JSON looks valid.
                if (!SettingsEnvelope.isEncrypted(root)) throw SecureStorageException(SecureStorageException.Reason.Corrupt)
                val envelope = SettingsEnvelope.read(root)
                val material = key(envelope.token)
                val payload = envelope.open(material.key, appId)
                candidate = Snapshot(payload.values, material.key, material.token ?: envelope.token,
                    AtomicSettingsFile.digest(bytes), payload.legacyDigest)
                if (candidate.token == envelope.token) {
                    state = candidate
                    return activate(candidate)
                }
            } else {
                val old = withContext(Dispatchers.IO) { legacyDisk.read() }
                try {
                    val values = old?.let { SettingsEnvelope.values(SettingsEnvelope.parse(it)) }.orEmpty()
                    val material = key(null)
                    candidate = Snapshot(values, material.key, material.token, null, AtomicSettingsFile.digest(old))
                } finally { old?.fill(0) }
            }
            pending = candidate
            return activate(commit(candidate))
        } finally { bytes?.fill(0) }
    }

    private suspend fun key(token: String?): KeyMaterial {
        val identity = appId ?: throw SecureStorageException(SecureStorageException.Reason.Unsupported)
        val secret = try { retrieve(token) }
            catch (_: TimeoutCancellationException) {
                currentCoroutineContext().ensureActive()
                throw SecureStorageException(SecureStorageException.Reason.Unavailable)
            }
            catch (failure: CancellationException) { throw failure }
            catch (failure: SecretPortalFailure) {
                throw SecureStorageException(if (failure.reason == SecretPortalFailure.Reason.Cancelled)
                    SecureStorageException.Reason.Cancelled else SecureStorageException.Reason.Unavailable)
            }
        return try { KeyMaterial(SettingsEnvelope.derive(secret.bytes, identity), secret.token) }
        finally { secret.bytes.fill(0) }
    }

    private suspend fun commit(candidate: Snapshot): Snapshot = withContext(Dispatchers.IO) {
        val identity = appId ?: throw SecureStorageException(SecureStorageException.Reason.Unsupported)
        val bytes = SettingsEnvelope.seal(candidate.values, candidate.key, identity, candidate.token, candidate.legacyDigest)
        if (pending === candidate) pendingBytes = bytes
        val digest = try {
            disk.commit(bytes, candidate.digest) { saved ->
                val opened = SettingsEnvelope.read(SettingsEnvelope.parse(saved)).open(candidate.key, identity)
                if (opened.values != candidate.values || !AtomicSettingsFile.same(opened.legacyDigest, candidate.legacyDigest)) {
                    throw SecureStorageException(SecureStorageException.Reason.Write)
                }
            }
        } catch (failure: SecureStorageException) {
            if (failure.reason in setOf(SecureStorageException.Reason.Uncertain, SecureStorageException.Reason.Changed)) blocked = failure
            throw failure
        }
        // Publish committed state before dispatching back, even if cancellation wins that return race.
        Snapshot(candidate.values, candidate.key, candidate.token, digest, candidate.legacyDigest)
            .also { state = it; pending = null; pendingBytes = null }
    }

    private suspend fun activate(snapshot: Snapshot): Snapshot = withContext(Dispatchers.IO) {
        if (Files.exists(File(legacy.parentFile, legacy.name + ".part").toPath(), LinkOption.NOFOLLOW_LINKS)) {
            throw SecureStorageException(SecureStorageException.Reason.Legacy)
        }
        // Durability of the encrypted name must be confirmed before the only legacy copy can be removed.
        disk.syncDirectory()
        val current = disk.read()
        try {
            if (!AtomicSettingsFile.same(snapshot.digest, AtomicSettingsFile.digest(current))) {
                throw SecureStorageException(SecureStorageException.Reason.Changed)
            }
        } finally { current?.fill(0) }
        val old = legacyDisk.read()
        if (old != null) {
            try {
                if (snapshot.legacyDigest == null || !MessageDigest.isEqual(snapshot.legacyDigest, AtomicSettingsFile.digest(old))) {
                    throw SecureStorageException(SecureStorageException.Reason.Legacy)
                }
            } finally { old.fill(0) }
            try { deleteLegacy(legacy.toPath()) } catch (_: IOException) {
                val after = legacyDisk.read()
                try {
                    if (after != null) throw SecureStorageException(SecureStorageException.Reason.Write)
                } finally { after?.fill(0) }
            }
            disk.syncDirectory()
        }
        Snapshot(snapshot.values, snapshot.key, snapshot.token, snapshot.digest, null).also { state = it; ready = true }
    }

    /** Only an explicit startup retry can reconcile an initial commit whose directory sync was uncertain. */
    private suspend fun reconcileInitialCommit() = withContext(Dispatchers.IO) {
        val candidate = pending ?: return@withContext
        val current = disk.read()
        try {
            val intended = pendingBytes
            if (current != null && intended != null && MessageDigest.isEqual(current, intended)) {
                disk.syncDirectory()
                state = Snapshot(candidate.values, candidate.key, candidate.token, AtomicSettingsFile.digest(current), candidate.legacyDigest)
                pending = null; pendingBytes = null; blocked = null
            } else if (AtomicSettingsFile.same(candidate.digest, AtomicSettingsFile.digest(current))) {
                blocked = null
            } else throw blocked ?: SecureStorageException(SecureStorageException.Reason.Uncertain)
        } finally { current?.fill(0) }
    }
}
