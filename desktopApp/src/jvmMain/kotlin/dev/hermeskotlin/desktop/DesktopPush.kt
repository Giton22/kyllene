package dev.hermeskotlin.desktop

import dev.hermeskotlin.core.push.NtfyEvent
import dev.hermeskotlin.core.push.PushInbox
import dev.hermeskotlin.core.push.PushInboxException
import dev.hermeskotlin.core.push.PushMessage
import dev.hermeskotlin.core.push.PushSubscription
import dev.hermeskotlin.core.storage.SecureStorageException
import dev.hermeskotlin.desktop.resources.*
import org.jetbrains.compose.resources.getString
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

internal data class PushWatch(val gatewayUrl: String, val ownerVersion: Long, val registration: Int)

/** One subscription owned by the app's UI scope. No job survives that scope or an explicit Quit. */
internal class DesktopPush(
    private val watches: StateFlow<PushWatch?>,
    private val inbox: PushInbox,
    private val subscribe: (PushSubscription) -> Flow<NtfyEvent>,
    private val post: suspend (PushWatch, PushSubscription, PushMessage) -> Unit,
    private val status: (String?) -> Unit = {},
    private val work: CoroutineContext = Dispatchers.IO,
) {
    private var job: Job? = null

    /** Called on the UI dispatcher; repeated starts share its existing collector. */
    fun start(scope: CoroutineScope): Job = job?.takeIf { it.isActive } ?: scope.launch {
        val running = currentCoroutineContext().job
        try {
            watches.collectLatest { watch ->
                status(null)
                if (watch == null) return@collectLatest
                var failures = 0
                while (watches.value == watch) {
                    val started = System.nanoTime()
                    try {
                        // Re-read on each reconnect, so the cursor and key pin are never a stale cache.
                        val subscription = withContext(work) { inbox.subscription(watch.gatewayUrl) }
                            ?: return@collectLatest
                        subscribe(subscription).collect { event ->
                            val message = withContext(work) { inbox.receive(subscription, event) }
                            currentCoroutineContext().ensureActive()
                            if (message != null) {
                                val current = withContext(work) { inbox.current(subscription) }
                                currentCoroutineContext().ensureActive()
                                if (current && watches.value == watch) {
                                    status(null)
                                    post(watch, subscription, message)
                                }
                            }
                        }
                        val detail = getString(Res.string.push_reconnecting)
                        currentCoroutineContext().ensureActive()
                        if (watches.value == watch) status(detail)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // HTTP exceptions may contain a secret topic URL: only our own safe guidance is shown.
                        val detail = when (e) {
                            is PushInboxException -> getString(Res.string.push_replay_unavailable)
                            is SecureStorageException -> getString(storageReasonText(e.reason))
                            else -> getString(Res.string.push_reconnecting)
                        }
                        currentCoroutineContext().ensureActive()
                        if (watches.value == watch) status(detail)
                    }
                    failures = if (System.nanoTime() - started >= HEALTHY_NANOS) 0 else failures + 1
                    delay(BACKOFF_MS[(failures - 1).coerceIn(BACKOFF_MS.indices)])
                }
            }
        } finally {
            if (job === running) { job = null; status(null) }
        }
    }.also { job = it }

    private companion object {
        const val HEALTHY_NANOS = 60_000_000_000L
        val BACKOFF_MS = longArrayOf(2_000, 5_000, 10_000, 30_000, 60_000)
    }
}
