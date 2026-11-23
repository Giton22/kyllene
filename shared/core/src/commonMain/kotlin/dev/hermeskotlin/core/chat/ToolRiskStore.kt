package dev.hermeskotlin.core.chat

import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.network.HermesJson
import dev.hermeskotlin.core.storage.KeyValueStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

/**
 * Tool calls whose output was flagged, by call id. The gateway only says so live (`tool.output_risk`);
 * the transcript doesn't keep it, so this device remembers what it saw to show it again on reload.
 *
 * Scoped by gateway and profile ([scope]) so flags from one gateway or profile never leak into another.
 */
class ToolRiskStore(private val store: KeyValueStore) {

    private val lock = Mutex()
    private val caches = mutableMapOf<String, LinkedHashMap<String, ToolRisk>>()

    suspend fun remember(callId: String, risk: ToolRisk, scope: String? = null) = lock.withLock {
        val key = storageKey(scope)
        val all = load(key)
        all.remove(callId)
        all[callId] = risk
        // Oldest first: past the cap, the flags least likely to be looked at again go.
        while (all.size > MAX_ENTRIES) all.remove(all.keys.first())
        store.put(key, HermesJson.encodeToString(SERIALIZER, all))
    }

    suspend fun all(scope: String? = null): Map<String, ToolRisk> = lock.withLock {
        load(storageKey(scope)).toMap()
    }

    suspend fun clear(scope: String? = null) = lock.withLock {
        val key = storageKey(scope)
        caches.remove(key)
        store.remove(key)
    }

    private suspend fun load(key: String): LinkedHashMap<String, ToolRisk> = caches.getOrPut(key) {
        LinkedHashMap(
            store.get(key)?.let { runCatching { HermesJson.decodeFromString(SERIALIZER, it) }.getOrNull() }.orEmpty(),
        )
    }

    private fun storageKey(scope: String?): String = if (scope.isNullOrEmpty()) KEY else "$KEY.$scope"

    companion object {
        /** Scopes remembered flags to one gateway and profile (null: the launch profile). */
        fun scope(gateway: GatewayUrl, profile: String? = null): String =
            "$gateway" + (profile?.let { "#$it" } ?: "")

        private const val KEY = "chat.toolRisk.v1"
        private const val MAX_ENTRIES = 500
        private val SERIALIZER = MapSerializer(String.serializer(), ToolRisk.serializer())
    }
}

/** Puts remembered flags back on the tool calls they belong to. */
internal fun List<ChatMessage>.withRisks(risks: Map<String, ToolRisk>): List<ChatMessage> {
    if (risks.isEmpty()) return this
    return map { message ->
        if (message is ChatMessage.Assistant && message.tools.any { it.risk == null && it.id in risks }) {
            message.copy(tools = message.tools.map { it.copy(risk = it.risk ?: risks[it.id]) })
        } else {
            message
        }
    }
}
