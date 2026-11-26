package dev.hermeskotlin.desktop

import dev.hermeskotlin.core.bots.BotsApi
import dev.hermeskotlin.core.storage.KeyValueStore
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException

/** Keep only the hidden bot names, per gateway, for pushes that arrive while its socket is down. */
internal class DesktopPushBots(private val store: KeyValueStore) {
    private val cached = mutableMapOf<String, Set<String>>()

    suspend fun refresh(url: String, api: BotsApi, current: () -> Boolean) {
        try {
            val hidden = api.roster().bots.filter { it.meta.hidden }.map { it.name }.toSet()
            if (!current()) return
            if (cached.put(url, hidden) != hidden) store.put(key(url), hidden.joinToString("\n"))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // The roster may be unavailable; keep the last successful result.
        }
    }

    suspend fun names(url: String): Set<String>? = cached[url] ?: try {
        val saved = store.get(key(url))
        if (saved != null) cached.putIfAbsent(url, saved.lineSequence().filter { it.isNotBlank() }.toSet())
        cached[url]
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        cached[url]
    }

    private fun key(url: String): String = "desktop.push.hidden." +
        MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
}
