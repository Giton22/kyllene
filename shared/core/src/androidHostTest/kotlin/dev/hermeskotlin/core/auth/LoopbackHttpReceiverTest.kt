package dev.hermeskotlin.core.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LoopbackHttpReceiverTest {

    @Test
    fun errorCallbackShowsEscapedErrorInsteadOfSignedIn() = runBlocking {
        LoopbackHttpReceiver(returnUri = null).start().use { listener ->
            val callback = async(Dispatchers.IO) { listener.awaitCallback() }
            val (status, body) = withContext(Dispatchers.IO) {
                val conn = URI("${listener.redirectUri}?error=access_denied&error_description=Bad+%3Cb%3Einput%3C/b%3E&state=s1")
                    .toURL().openConnection() as HttpURLConnection
                try {
                    conn.connectTimeout = 5_000
                    conn.readTimeout = 5_000
                    conn.responseCode to conn.inputStream.bufferedReader().readText()
                } finally {
                    conn.disconnect()
                }
            }

            val query = callback.await()
            assertEquals(200, status)
            assertEquals("access_denied", query["error"])
            assertEquals("Bad <b>input</b>", query["error_description"])
            assertTrue("Sign-in failed" in body, body)
            assertTrue("Bad &lt;b&gt;input&lt;/b&gt;" in body, body)
            assertFalse("Signed in." in body, body)
            assertFalse("<b>" in body, body)
        }
    }

    @Test
    fun successCallbackShowsSignedIn() = runBlocking {
        LoopbackHttpReceiver(returnUri = null).start().use { listener ->
            val callback = async(Dispatchers.IO) { listener.awaitCallback() }
            val (status, body) = withContext(Dispatchers.IO) {
                val conn = URI("${listener.redirectUri}?code=abc&state=s1")
                    .toURL().openConnection() as HttpURLConnection
                try {
                    conn.connectTimeout = 5_000
                    conn.readTimeout = 5_000
                    conn.responseCode to conn.inputStream.bufferedReader().readText()
                } finally {
                    conn.disconnect()
                }
            }

            val query = callback.await()
            assertEquals(200, status)
            assertEquals("abc", query["code"])
            assertTrue("Signed in." in body, body)
        }
    }
}
