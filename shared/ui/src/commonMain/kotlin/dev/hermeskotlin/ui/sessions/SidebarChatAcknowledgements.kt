package dev.hermeskotlin.ui.sessions

import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.storage.KeyValueStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** Shared by the main transcript and desktop chat windows. */
internal class SidebarChatAcknowledgements(private val storage: KeyValueStore) {
    private val lock = Mutex()
    private val saved = MutableStateFlow<Map<GatewayUrl, List<SidebarChatReceipt>>>(emptyMap())
    private val launchProfiles = MutableStateFlow<Map<GatewayUrl, String>>(emptyMap())
    private val _viewers = MutableStateFlow<Map<Any, SidebarChatViewer>>(emptyMap())
    val viewers = _viewers.asStateFlow()

    fun receipts(gateway: GatewayUrl) = saved
        .onStart { lock.withLock { load(gateway) } }
        .map { gateway to it[gateway].orEmpty() }
        .distinctUntilChanged()

    fun bindLaunchProfile(gateway: GatewayUrl, profile: String) {
        launchProfiles.update { it + (gateway to profile) }
    }

    suspend fun acknowledgeTranscript(viewer: SidebarChatViewer, unreadAt: Double?) {
        val profile = viewer.profile ?: launchProfiles.value[viewer.gateway] ?: return
        val legacyViewedAt = viewer.viewedAt.takeIf { viewer.requests.isNotEmpty() }
        acknowledge(viewer.gateway, viewer.ids.map { id ->
            SidebarChatReceipt(profile, id, viewer.requests, unreadAt, legacyViewedAt)
        })
    }

    fun show(window: Any, viewer: SidebarChatViewer) {
        _viewers.update { it + (window to viewer) }
    }

    fun hide(window: Any) {
        _viewers.update { it - window }
    }

    suspend fun acknowledge(gateway: GatewayUrl, receipt: SidebarChatReceipt) =
        acknowledge(gateway, listOf(receipt))

    suspend fun acknowledge(gateway: GatewayUrl, observed: List<SidebarChatReceipt>) {
        val updates = observed.filter { it.requestIds.isNotEmpty() || it.unreadAt != null || it.legacyViewedAt != null }
        if (updates.isEmpty()) return
        lock.withLock {
            val receipts = load(gateway)
            val updated = receipts.associateBy { it.profile to it.id }.toMutableMap()
            updates.forEach { receipt ->
                val key = receipt.profile to receipt.id
                val old = updated[key]
                val merged = if (old == null) receipt else old.copy(
                    requestIds = receipt.requestIds.ifEmpty { old.requestIds },
                    unreadAt = listOfNotNull(old.unreadAt, receipt.unreadAt).maxOrNull(),
                    legacyViewedAt = old.legacyViewedAt ?: receipt.legacyViewedAt,
                )
                if (merged != old) {
                    updated.remove(key)
                    updated[key] = merged
                }
            }
            val bounded = updated.values.toList().takeLast(512)
            if (bounded != receipts) write(gateway, bounded)
        }
    }

    suspend fun clearLegacyWait(gateway: GatewayUrl, profile: String, aliases: Set<String>, askedAt: Long) {
        lock.withLock {
            val receipts = load(gateway)
            val updated = receipts.map {
                if (it.profile == profile && it.id in aliases && it.legacyViewedAt != null && askedAt >= it.legacyViewedAt) {
                    it.copy(legacyViewedAt = null)
                } else it
            }
            if (updated != receipts) write(gateway, updated)
        }
    }

    private suspend fun load(gateway: GatewayUrl): List<SidebarChatReceipt> {
        saved.value[gateway]?.let { return it }
        val stored = storage.get(key(gateway))?.let {
            runCatching { Json.parseToJsonElement(it) as? JsonArray }.getOrNull()
                ?.mapNotNull { row -> (row as? JsonObject)?.let(::decodeReceipt) }
        }.orEmpty()
        saved.update { it + (gateway to stored) }
        return stored
    }

    private suspend fun write(gateway: GatewayUrl, receipts: List<SidebarChatReceipt>) {
        storage.put(key(gateway), JsonArray(receipts.map(::encodeReceipt)).toString())
        saved.update { it + (gateway to receipts) }
    }

    private fun key(gateway: GatewayUrl) = "sidebar.chat.seen.v1.${gateway.value}"
}

internal data class SidebarChatReceipt(
    val profile: String,
    val id: String,
    val requestIds: Set<String> = emptySet(),
    val unreadAt: Double? = null,
    val legacyViewedAt: Long? = null,
)

private fun encodeReceipt(receipt: SidebarChatReceipt): JsonObject = buildJsonObject {
    put("profile", receipt.profile)
    put("id", receipt.id)
    put("requests", JsonArray(receipt.requestIds.map(::JsonPrimitive)))
    receipt.unreadAt?.let { put("unreadAt", it) }
    receipt.legacyViewedAt?.let { put("legacyViewedAt", it) }
}

private fun decodeReceipt(value: JsonObject): SidebarChatReceipt? {
    val profile = (value["profile"] as? JsonPrimitive)?.contentOrNull ?: return null
    val id = (value["id"] as? JsonPrimitive)?.contentOrNull ?: return null
    return SidebarChatReceipt(
        profile = profile,
        id = id,
        requestIds = (value["requests"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.toSet(),
        unreadAt = (value["unreadAt"] as? JsonPrimitive)?.doubleOrNull,
        legacyViewedAt = (value["legacyViewedAt"] as? JsonPrimitive)?.longOrNull,
    )
}

internal data class SidebarChatViewer(
    val gateway: GatewayUrl,
    val profile: String?,
    val ids: Set<String>,
    val requests: Set<String>,
    val viewedAt: Long,
)
