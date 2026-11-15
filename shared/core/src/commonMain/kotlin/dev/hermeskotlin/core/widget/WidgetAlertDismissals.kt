package dev.hermeskotlin.core.widget

import dev.hermeskotlin.core.network.HermesJson
import dev.hermeskotlin.core.storage.KeyValueStore
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * The Now alerts the user has dismissed (docs/WIDGETS-SPEC.md D27), per gateway, over the platform
 * [KeyValueStore] like [WidgetSnapshotStore]. Keys are [NowAlertKeys] fingerprints: a dismissed
 * failed job stays hidden until a later run fails again, a dismissed task until its status changes.
 * The list is capped at [MAX_KEYS] (oldest dropped) so a long-lived gateway can't grow it unbounded.
 */
class WidgetAlertDismissals(private val store: KeyValueStore) {

    /** Every key dismissed on [gatewayUrl]; empty when none were. */
    suspend fun keys(gatewayUrl: String): Set<String> = read(gatewayUrl).toSet()

    /** Hides the alert [key] names on [gatewayUrl]; dismissing twice changes nothing. */
    suspend fun dismiss(gatewayUrl: String, key: String) {
        val known = read(gatewayUrl)
        if (key in known) return
        store.put(key(gatewayUrl), json.encodeToString(ListSerializer(String.serializer()), (known + key).takeLast(MAX_KEYS)))
    }

    /** Called where `WidgetSnapshotStore.forgetGateway` is (signing out, gateway removed). */
    suspend fun forgetGateway(gatewayUrl: String) = store.remove(key(gatewayUrl))

    private suspend fun read(gatewayUrl: String): List<String> =
        store.get(key(gatewayUrl))
            ?.let { runCatching { json.decodeFromString(ListSerializer(String.serializer()), it) }.getOrNull() }
            ?: emptyList()

    private fun key(gatewayUrl: String) = "widget.dismissed.v1.$gatewayUrl"

    companion object {
        /** Dismissals kept per gateway; the oldest fall off past this. */
        const val MAX_KEYS = 500

        private val json = Json(HermesJson) { coerceInputValues = true }
    }
}