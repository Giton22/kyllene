package dev.hermeskotlin.android.wear

import android.content.Context
import com.google.android.gms.wearable.Wearable
import dev.hermeskotlin.core.wear.WatchOutbox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/** [WatchOutbox] over the Wear OS Data Layer: messages for updates, a channel for each voice clip. */
class PhoneOutbox(context: Context) : WatchOutbox {

    private val messages = Wearable.getMessageClient(context)
    private val channels = Wearable.getChannelClient(context)

    override suspend fun send(node: String, path: String, data: ByteArray): Boolean {
        messages.sendMessage(node, path, data).await()
        return true
    }

    override suspend fun stream(node: String, path: String, data: ByteArray): Boolean {
        val channel = channels.openChannel(node, path).await()
        try {
            val output = channels.getOutputStream(channel).await()
            withContext(Dispatchers.IO) { output.use { it.write(data) } }
        } finally {
            channels.close(channel)
        }
        return true
    }
}
