package dev.hermeskotlin.ui.bots

import dev.hermeskotlin.core.cron.CronJob
import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.storage.KeyValueStore
import kotlinx.serialization.json.Json

/** The last failure the user opened for each routine, independent of later refreshes. */
internal class RoutineAcknowledgements(private val store: KeyValueStore) {
    suspend fun isSeen(gateway: GatewayUrl, profile: String, job: CronJob): Boolean =
        store.get(key(gateway, profile, job.id)) == routineFailureVersion(job)

    suspend fun markSeen(gateway: GatewayUrl, profile: String, job: CronJob) {
        store.put(key(gateway, profile, job.id), routineFailureVersion(job))
    }

    private fun key(gateway: GatewayUrl, profile: String, id: String): String =
        "sidebar.routine.seen.v1." + Json.encodeToString(listOf(gateway.toString(), profile, id))
}

internal fun routineFailureVersion(job: CronJob): String = Json.encodeToString(
    listOf(job.lastRunAt, job.lastStatus, job.lastError, job.lastDeliveryError, job.fireError?.toString(), job.pausedReason)
)
