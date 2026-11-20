package dev.hermeskotlin.core.push

import dev.hermeskotlin.core.network.HermesJson
import dev.hermeskotlin.core.storage.KeyValueStore
import dev.hermeskotlin.core.storage.LinuxKeyValueStore
import java.security.Signature
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Only the encrypted Flatpak store may hold this identity; other desktop stores retain their existing behavior. */
fun linuxPushKeys(store: KeyValueStore): PushKeys? = if (store is LinuxKeyValueStore) LinuxPushKeys(store) else null

/** One identity and its pending revocations, committed together through the unlocked secure store. */
internal class LinuxPushKeys(private val store: KeyValueStore) : PushKeys, PushInbox {
    @Serializable
    private data class Device(val id: String, val encPrivate: String, val sigPrivate: String,
        val encPublic: String, val sigPublic: String, val pushTopic: String, val replyTopic: String)

    @Serializable
    private data class State(val version: Int, val device: Device?, val gatewayUrl: String?,
        val gateway: PushGateway?, val retired: Map<String, Set<String>>)

    override val deviceId: String get() = registration("").deviceId

    override fun registration(name: String): PushRegistration = locked {
        val state = read()
        val device = state.device ?: create().also { save(state.copy(device = it)) }
        PushRegistration(device.id, name, NtfyClient.DEFAULT_SERVER, device.pushTopic, device.replyTopic,
            device.encPublic, device.sigPublic)
    }

    override fun pinned(gatewayUrl: String): PushGateway? = locked {
        read().takeIf { it.gatewayUrl == gatewayUrl }?.gateway
    }

    override fun pinnedGatewayUrl(): String? = locked { read().gatewayUrl }

    override fun accepts(keys: PushGateway): Boolean = keys.version == 1 && runCatching {
        PushCrypto.decodePublic(keys.encPub)
        PushCrypto.decodePublic(keys.sigPub)
    }.isSuccess

    override fun pin(gatewayUrl: String, keys: PushGateway) = locked {
        require(gatewayUrl.isNotBlank() && accepts(keys)) { "The gateway's plugin sent unusable keys." }
        val state = read()
        check(state.device != null) { "Create a push registration before pinning its gateway." }
        check(state.gatewayUrl == null || state.gatewayUrl == gatewayUrl) { "Retire the previous gateway's identity first." }
        if (state.gatewayUrl != gatewayUrl || state.gateway != keys) save(state.copy(gatewayUrl = gatewayUrl, gateway = keys))
    }

    override fun retire(gatewayUrl: String) = locked {
        val state = read()
        val device = state.device ?: return@locked
        if (state.gatewayUrl != null && state.gatewayUrl != gatewayUrl) return@locked
        require(gatewayUrl.isNotBlank())
        save(state.copy(device = null, gatewayUrl = null, gateway = null,
            retired = state.retired + (gatewayUrl to (state.retired[gatewayUrl].orEmpty() + device.id))))
    }

    override fun retired(gatewayUrl: String): Set<String> = locked { read().retired[gatewayUrl].orEmpty() }

    override fun forgetRetired(gatewayUrl: String, deviceId: String) = locked {
        val state = read()
        val previous = state.retired[gatewayUrl].orEmpty()
        if (deviceId in previous) {
            val left = previous - deviceId
            save(state.copy(retired = if (left.isEmpty()) state.retired - gatewayUrl else state.retired + (gatewayUrl to left)))
        }
    }

    // The stored text last checked, and what it held. Checking both key pairs signs and verifies twice, too much for
    // every lookup; the text is still read through the store each time, so a change made elsewhere is checked again.
    private var checkedText: String? = null
    private var checkedState: State? = null

    @Serializable
    private data class Replay(val version: Int, val deviceId: String, val gatewayUrl: String,
        val topic: String, val gateway: PushGateway, val seen: Map<String, Long>, val since: Long?)

    override fun subscription(gatewayUrl: String): PushSubscription? = locked {
        val state = read()
        val device = state.device ?: return@locked null
        val gateway = state.gateway?.takeIf { state.gatewayUrl == gatewayUrl } ?: return@locked null
        val subscription = PushSubscription(gatewayUrl, device.id, NtfyClient.DEFAULT_SERVER, device.pushTopic, gateway, null)
        subscription.copy(sinceSeconds = replay(subscription).since)
    }

    override fun current(subscription: PushSubscription): Boolean = locked { matches(subscription, read()) }

    override fun receive(subscription: PushSubscription, event: NtfyEvent): PushMessage? = locked {
        val state = read()
        if (!matches(subscription, state) || event.event != "message" || event.topic != subscription.topic) return@locked null
        val body = event.message?.takeIf { it.length <= NtfyClient.MAX_BODY } ?: return@locked null
        val message = runCatching {
            val envelope = PushCrypto.decodeEnvelope(body)
            val encoded = PushCrypto.unb64(checkNotNull(state.device).encPrivate)
            val key = try { PushCrypto.decodePrivate(encoded) } finally { encoded.fill(0) }
            val plain = PushCrypto.open(envelope, subscription.topic, PushDirection.GatewayToPhone, key,
                PushCrypto.decodePublic(subscription.gateway.sigPub))
            try { HermesJson.decodeFromString(PushMessage.serializer(), plain.decodeToString()) }
            finally { plain.fill(0) }
        }.getOrNull() ?: return@locked null
        if (message.ts < 0) return@locked null
        val cached = replay(subscription)
        val now = System.currentTimeMillis() / 1000
        val guard = PushGuard(PushMessage.MAX_AGE_G2P_SECONDS) { now }
        guard.remember(cached.seen)
        if (!guard.accept(message)) return@locked null
        // Neither a broker's forged timestamp nor a gateway clock ahead may skip later valid messages.
        val cursor = minOf(event.time.takeIf { it > 0 } ?: message.ts, message.ts, now)
        val next = cached.copy(seen = guard.snapshot(), since = maxOf(cached.since ?: 0, cursor))
        runBlocking(Dispatchers.IO) { store.put(REPLAY_KEY, stateJson.encodeToString(Replay.serializer(), next)) }
        message
    }

    private fun matches(subscription: PushSubscription, state: State): Boolean =
        state.gatewayUrl == subscription.gatewayUrl && state.gateway == subscription.gateway &&
            state.device?.id == subscription.deviceId && state.device?.pushTopic == subscription.topic &&
            subscription.server == NtfyClient.DEFAULT_SERVER

    private fun replay(subscription: PushSubscription): Replay {
        val text = runBlocking(Dispatchers.IO) { store.get(REPLAY_KEY) }
        val cached = text?.let {
            try {
                stateJson.decodeFromString(Replay.serializer(), it).also { record ->
                    check(record.version == 1 && record.gatewayUrl.isNotBlank() && accepts(record.gateway))
                    check(UUID.fromString(record.deviceId).toString() == record.deviceId && TOPIC.matches(record.topic))
                    check(record.seen.size <= 2000 && record.seen.all { (id, ts) -> id.isNotBlank() && ts >= 0 })
                    check(record.since == null || record.since >= 0)
                }
            } catch (_: Exception) {
                throw PushInboxException()
            }
        }
        return cached?.takeIf { it.deviceId == subscription.deviceId && it.gatewayUrl == subscription.gatewayUrl &&
            it.topic == subscription.topic && it.gateway == subscription.gateway }
            ?: Replay(1, subscription.deviceId, subscription.gatewayUrl, subscription.topic, subscription.gateway, emptyMap(), null)
    }

    // PushKeys is synchronous. Startup unlocks the store first; disk commits run on IO before callers publish success.
    private fun read(): State {
        val text = runBlocking(Dispatchers.IO) {
            if (store is LinuxKeyValueStore) store.getChecked(KEY) else store.get(KEY)
        } ?: return State(1, null, null, null, emptyMap())
        if (text == checkedText) checkedState?.let { return it }
        return try {
            stateJson.decodeFromString(State.serializer(), text).also { state ->
                check(state.version == 1)
                check((state.gatewayUrl == null) == (state.gateway == null))
                state.gateway?.let { check(state.device != null && !state.gatewayUrl.isNullOrBlank() && accepts(it)) }
                state.device?.let { device ->
                    check(UUID.fromString(device.id).toString() == device.id)
                    check(TOPIC.matches(device.pushTopic) && TOPIC.matches(device.replyTopic) && device.pushTopic != device.replyTopic)
                    checkPair(device.encPrivate, device.encPublic)
                    checkPair(device.sigPrivate, device.sigPublic)
                }
                state.retired.forEach { (url, ids) ->
                    check(url.isNotBlank())
                    ids.forEach { check(UUID.fromString(it).toString() == it) }
                }
                checkedText = text
                checkedState = state
            }
        } catch (_: Exception) {
            throw IllegalStateException("Kyllene couldn't open its push identity. Existing data has been preserved; restore a known-good backup before retrying.")
        }
    }

    private fun save(state: State) {
        val text = stateJson.encodeToString(State.serializer(), state)
        runBlocking(Dispatchers.IO) { store.put(KEY, text) }
        // Only after the commit: a failed write leaves the last checked text as it was.
        checkedText = text
        checkedState = state
    }

    private fun create(): Device {
        val enc = PushCrypto.generateKeyPair()
        val sig = PushCrypto.generateKeyPair()
        fun encode(key: java.security.PrivateKey): String {
            val bytes = PushCrypto.encodePrivate(key)
            return try { PushCrypto.b64(bytes) } finally { bytes.fill(0) }
        }
        return Device(UUID.randomUUID().toString(), encode(enc.private), encode(sig.private),
            PushCrypto.encodePublic(enc.public), PushCrypto.encodePublic(sig.public), PushCrypto.newTopic(), PushCrypto.newTopic())
    }

    private fun checkPair(encodedPrivate: String, encodedPublic: String) {
        val bytes = PushCrypto.unb64(encodedPrivate)
        val private = try { PushCrypto.decodePrivate(bytes) } finally { bytes.fill(0) }
        val public = PushCrypto.decodePublic(encodedPublic)
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(private)
            update(PAIR_CHECK)
            sign()
        }
        check(Signature.getInstance("SHA256withECDSA").run {
            initVerify(public)
            update(PAIR_CHECK)
            verify(signature)
        })
    }

    // Serializes read/modify/commit even if a second adapter is created for this same store.
    private fun <T> locked(action: () -> T): T = synchronized(store, action)

    private companion object {
        const val KEY = "linux.push.identity.v1"
        const val REPLAY_KEY = "linux.push.replay.v1"
        // Missing fields must not become an intentionally retired identity.
        val stateJson = Json(HermesJson) { explicitNulls = true }
        val TOPIC = Regex("hp-[0-9a-f]{32}")
        val PAIR_CHECK = "herald-push/identity-pair".encodeToByteArray()
    }
}
