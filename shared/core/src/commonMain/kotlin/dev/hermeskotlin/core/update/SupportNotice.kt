package dev.hermeskotlin.core.update

import dev.hermeskotlin.core.gateway.GatewayRepository
import dev.hermeskotlin.core.storage.KeyValueStore
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/**
 * Asks once for a star or a tip. Someone who already had a gateway saved when this first ran is asked at once; a
 * fresh install is asked after [wait]. Closing it, or tapping a star or giving link here or in Settings, ends it for good.
 */
class SupportNotice(
    private val store: KeyValueStore,
    private val gateways: GatewayRepository,
    private val clock: Clock = Clock.System,
    private val wait: Duration = 21.days,
) {

    /** Whether to show the card now. Call it at launch, before any sign-in can add a gateway. */
    suspend fun pending(): Boolean {
        val saved = store.get(KEY)
        return when {
            saved == DONE -> false
            saved != null -> saved.toLongOrNull()?.let { clock.now() - Instant.fromEpochMilliseconds(it) >= wait } ?: true
            gateways.all().gateways.isNotEmpty() -> {
                store.put(KEY, "0")
                true
            }
            else -> {
                store.put(KEY, clock.now().toEpochMilliseconds().toString())
                false
            }
        }
    }

    suspend fun dismiss() = store.put(KEY, DONE)

    private companion object {
        /** When the wait started, in epoch milliseconds, or [DONE]. */
        const val KEY = "notice.support.v1"
        const val DONE = "done"
    }
}
