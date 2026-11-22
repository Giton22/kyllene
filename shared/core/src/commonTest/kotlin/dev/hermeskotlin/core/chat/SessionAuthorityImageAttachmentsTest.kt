package dev.hermeskotlin.core.chat

import dev.hermeskotlin.core.rpc.FakeGateway
import dev.hermeskotlin.core.rpc.isCall
import dev.hermeskotlin.core.rpc.json
import dev.hermeskotlin.core.sessions.SessionsApi
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

class SessionAuthorityImageAttachmentsTest {

    private val url = FakeGateway.URL

    @Test
    fun sessionAuthorityPromptSubmitIncludesUploadedImageAttachments() = runTest(timeout = 2.seconds) {
        var uploadCount = 0
        val gw = FakeGateway(backgroundScope, reconnects = false).apply {
            ready = { """{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready","payload":{"session_authority":true}}}""" }
            answer = { call ->
                when (call.method) {
                    "session.create" -> """{"session_id":"rt-img","stored_session_id":"stored-img"}"""
                    "image.attach_bytes" -> {
                        uploadCount++
                        """{"attached":true,"path":"/staged/img_$uploadCount.png","mime":"image/png"}"""
                    }
                    "prompt.submit" -> """{"status":"streaming"}"""
                    else -> "{}"
                }
            }
            http = { json("""{"session_id":"stored-img","messages":[]}""") }
            start(awaitConnected = true)
        }
        val chat = ChatSession(url, null, "Images", gw.connection, SessionsApi(gw.client), backgroundScope)
        chat.start()

        val img1 = OutgoingAttachment("i1", "one.png", "image/png", byteArrayOf(1, 2, 3, 4))
        val img2 = OutgoingAttachment("i2", "two.png", "image/png", byteArrayOf(5, 6, 7, 8))
        assertEquals(SendOutcome.Sent, chat.submit("Compare these", listOf(img1, img2)))

        val submit = gw.socket.sent.value.first { it.isCall("prompt.submit") }
        val attachments = submit["params"]?.jsonObject?.get("attachments")?.jsonArray
        assertEquals(2, attachments?.size)
        assertEquals("/staged/img_1.png", attachments!![0].jsonObject["path"]?.jsonPrimitive?.contentOrNull)
        assertEquals("image/png", attachments[0].jsonObject["mime"]?.jsonPrimitive?.contentOrNull)
        assertEquals("/staged/img_2.png", attachments[1].jsonObject["path"]?.jsonPrimitive?.contentOrNull)
        assertEquals("image/png", attachments[1].jsonObject["mime"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun legacyGatewayPromptSubmitOmitsImageAttachmentsArray() = runTest(timeout = 2.seconds) {
        val gw = FakeGateway(backgroundScope, reconnects = false).apply {
            ready = { FakeGateway.QUIET_READY }
            answer = { call ->
                when (call.method) {
                    "session.create" -> """{"session_id":"rt-legacy","stored_session_id":"stored-legacy"}"""
                    "image.attach_bytes" -> """{"attached":true,"path":"/tmp/upload_1.png","mime":"image/png"}"""
                    "prompt.submit" -> """{"status":"streaming"}"""
                    else -> "{}"
                }
            }
            http = { json("""{"session_id":"stored-legacy","messages":[]}""") }
            start(awaitConnected = true)
        }
        val chat = ChatSession(url, null, "Legacy", gw.connection, SessionsApi(gw.client), backgroundScope)
        chat.start()

        val img = OutgoingAttachment("i1", "one.png", "image/png", byteArrayOf(1, 2, 3, 4))
        assertEquals(SendOutcome.Sent, chat.submit("Inspect", listOf(img)))

        val submit = gw.socket.sent.value.first { it.isCall("prompt.submit") }
        assertNull(submit["params"]?.jsonObject?.get("attachments"))
    }
}
