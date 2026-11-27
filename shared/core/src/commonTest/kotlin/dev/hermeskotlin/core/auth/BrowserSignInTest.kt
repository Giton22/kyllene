package dev.hermeskotlin.core.auth

import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.network.ApiResult
import dev.hermeskotlin.core.network.createHttpClient
import dev.hermeskotlin.core.storage.InMemoryKeyValueStore
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BrowserSignInTest {

    private val url = GatewayUrl.parse("https://hermes.example.com/hermes")
    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private val store = InMemoryKeyValueStore()
    private val cookies = PersistentCookiesStorage(store)
    private val tokens = NativeTokens(store)

    private class FakeLoopback(private val answer: (authorizeUrl: Url) -> Map<String, String>) : LoopbackReceiver {
        var closed = false
        var opened: Url? = null
        override suspend fun start() = object : LoopbackListener {
            override val redirectUri = "http://127.0.0.1:43210/callback"
            override suspend fun awaitCallback(): Map<String, String> = answer(opened!!)
            override fun close() {
                closed = true
            }
        }
    }

    /** What happened, in order: the app held up, the browser opened, the app let go. */
    private val events = mutableListOf<String>()

    private inner class FakeKeepAlive(private val grants: Boolean = true) : SignInKeepAlive {
        var held = false
        override val restricted = MutableStateFlow(false)
        override fun hold(): Boolean {
            events += "hold"
            held = grants
            return grants
        }
        override fun release() {
            events += "release"
            held = false
        }
    }

    private val keepAlive = FakeKeepAlive()

    private fun signIn(
        tokenBody: (String) -> Unit = {},
        tokenStatus: HttpStatusCode = HttpStatusCode.OK,
        keepAlive: SignInKeepAlive = this.keepAlive,
        answer: (Url) -> Map<String, String>,
    ): Pair<BrowserSignIn, FakeLoopback> {
        val engine = MockEngine { request ->
            assertEquals("/hermes/auth/native/token", request.url.encodedPath)
            tokenBody((request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString())
            if (tokenStatus != HttpStatusCode.OK) {
                respond("""{"detail":"Invalid or expired authorization code."}""", tokenStatus, json)
            } else {
                respond(
                    """{"access_token":"AT1","refresh_token":"RT1","token_type":"Bearer","expires_at":1900000000,"provider":"self-hosted","user_id":"u1"}""",
                    HttpStatusCode.OK, json,
                )
            }
        }
        val auth = AuthApi(createHttpClient(engine, cookies, bearer = tokens), cookies, tokens)
        val loopback = FakeLoopback(answer)
        return BrowserSignIn(auth, loopback, keepAlive) to loopback
    }

    private fun waitsForever(closed: () -> Unit = {}) = object : LoopbackReceiver {
        override suspend fun start() = object : LoopbackListener {
            override val redirectUri = "http://127.0.0.1:1/callback"
            override suspend fun awaitCallback(): Map<String, String> = CompletableDeferred<Map<String, String>>().await()
            override fun close() = closed()
        }
    }

    @Test
    fun opensTheAuthorizePageAndRedeemsTheCodeWithTheVerifier() = runTest {
        var body = ""
        val (browser, loopback) = signIn(tokenBody = { body = it }) { opened ->
            mapOf("code" to "GW-CODE", "state" to opened.parameters["state"]!!)
        }

        val result = browser.signIn(url, provider = null) { loopback.opened = Url(it) }

        assertIs<ApiResult.Success<Unit>>(result)
        val opened = loopback.opened!!
        assertEquals("/hermes/auth/native/authorize", opened.encodedPath)
        assertEquals("S256", opened.parameters["code_challenge_method"])
        assertEquals("http://127.0.0.1:43210/callback", opened.parameters["redirect_uri"])
        // A blank provider lets the gateway pick, or show its chooser.
        assertNull(opened.parameters["provider"])
        val verifier = Regex("\"code_verifier\":\"([^\"]+)\"").find(body)!!.groupValues[1]
        assertEquals(opened.parameters["code_challenge"], pkceChallenge(verifier))
        assertTrue("\"code\":\"GW-CODE\"" in body)

        val session = assertNotNull(tokens.get(url))
        assertEquals("AT1", session.accessToken)
        assertEquals("RT1", session.refreshToken)
        assertEquals(url.value, session.baseUrl)
        assertTrue(loopback.closed)
    }

    @Test
    fun strayCallbacksArePassedOverUntilTheRealOne() = runTest {
        var calls = 0
        var redeemed = 0
        val (browser, loopback) = signIn(tokenBody = { redeemed++; assertTrue("\"code\":\"REAL\"" in it) }) { opened ->
            when (calls++) {
                0 -> mapOf("code" to "STRAY", "state" to "someone-else")
                1 -> mapOf("state" to opened.parameters["state"]!!)
                else -> mapOf("code" to "REAL", "state" to opened.parameters["state"]!!)
            }
        }

        val result = browser.signIn(url, provider = "self-hosted") { loopback.opened = Url(it) }

        assertIs<ApiResult.Success<Unit>>(result)
        assertEquals("self-hosted", loopback.opened!!.parameters["provider"])
        assertEquals(1, redeemed)
    }

    @Test
    fun givesUpWhenTheBrowserNeverComesBack() = runTest {
        val loopback = object : LoopbackReceiver {
            override suspend fun start() = object : LoopbackListener {
                override val redirectUri = "http://127.0.0.1:1/callback"
                override suspend fun awaitCallback(): Map<String, String> = CompletableDeferred<Map<String, String>>().await()
                override fun close() = Unit
            }
        }
        val browser = BrowserSignIn(AuthApi(createHttpClient(MockEngine { error("no calls") }), cookies, tokens), loopback)
        assertIs<ApiResult.Failed>(browser.signIn(url, null) {})
        assertNull(tokens.get(url))
    }

    @Test
    fun redeemingForgetsAnOldPasswordSession() = runTest {
        cookies.addCookie(Url(url.value), io.ktor.http.Cookie("hermes_session_rt", "RT", maxAge = 100, path = "/"))
        val (browser, loopback) = signIn { mapOf("code" to "C", "state" to it.parameters["state"]!!) }
        assertIs<ApiResult.Success<Unit>>(browser.signIn(url, null) { loopback.opened = Url(it) })
        assertTrue(cookies.get(Url(url.value)).isEmpty())
    }

    @Test
    fun cancellingStopsListening() = runTest {
        val waiting = CompletableDeferred<Unit>()
        val loopback = object : LoopbackReceiver {
            var closed = false
            override suspend fun start() = object : LoopbackListener {
                override val redirectUri = "http://127.0.0.1:1/callback"
                override suspend fun awaitCallback(): Map<String, String> {
                    waiting.complete(Unit)
                    CompletableDeferred<Unit>().await()
                    error("unreachable")
                }
                override fun close() {
                    closed = true
                }
            }
        }
        val browser = BrowserSignIn(AuthApi(createHttpClient(MockEngine { error("no calls") }), cookies, tokens), loopback)
        val job = backgroundScope.launch { browser.signIn(url, null) {} }
        waiting.await()
        job.cancel()
        job.join()
        assertTrue(loopback.closed)
    }

    @Test
    fun holdsTheAppUpFromBeforeTheBrowserOpensUntilTheCodeIsRedeemed() = runTest {
        var heldWhileWaiting = false
        val (browser, loopback) = signIn { opened ->
            heldWhileWaiting = keepAlive.held
            mapOf("code" to "C", "state" to opened.parameters["state"]!!)
        }
        assertIs<ApiResult.Success<Unit>>(browser.signIn(url, null) { events += "open"; loopback.opened = Url(it) })
        assertTrue(heldWhileWaiting)
        assertEquals(listOf("hold", "open", "release"), events)
    }

    @Test
    fun letsGoWhenTheGatewayTurnsTheCodeDown() = runTest {
        val (browser, loopback) = signIn(tokenStatus = HttpStatusCode.BadRequest) { mapOf("code" to "C", "state" to it.parameters["state"]!!) }
        assertIs<ApiResult.Failed>(browser.signIn(url, null) { loopback.opened = Url(it) })
        assertEquals(listOf("hold", "release"), events)
        assertNull(tokens.get(url))
    }

    @Test
    fun letsGoWhenTheBrowserNeverComesBack() = runTest {
        val browser = BrowserSignIn(AuthApi(createHttpClient(MockEngine { error("no calls") }), cookies, tokens), waitsForever(), keepAlive)
        assertIs<ApiResult.Failed>(browser.signIn(url, null) {})
        assertEquals(listOf("hold", "release"), events)
    }

    @Test
    fun letsGoWhenTheSignInIsCancelled() = runTest {
        val browser = BrowserSignIn(AuthApi(createHttpClient(MockEngine { error("no calls") }), cookies, tokens), waitsForever(), keepAlive)
        val job = backgroundScope.launch { browser.signIn(url, null) { events += "open" } }
        testScheduler.runCurrent()
        assertTrue(keepAlive.held)
        job.cancel()
        job.join()
        assertEquals(listOf("hold", "open", "release"), events)
    }

    @Test
    fun aCancelledSignInLettingGoLateKeepsTheNextOneHeld() = runTest {
        val browser = BrowserSignIn(AuthApi(createHttpClient(MockEngine { error("no calls") }), cookies, tokens), waitsForever(), keepAlive)
        val first = backgroundScope.launch { browser.signIn(url, null) { events += "open" } }
        testScheduler.runCurrent()
        // Tapped again before the cancelled one has stopped listening.
        val second = backgroundScope.launch { browser.signIn(url, null) { events += "open" } }
        testScheduler.runCurrent()
        first.cancel()
        first.join()
        assertTrue(keepAlive.held)
        assertEquals(listOf("hold", "open", "open"), events)
        second.cancel()
        second.join()
        assertEquals(listOf("hold", "open", "open", "release"), events)
    }

    @Test
    fun holdsNothingWhenItCantListen() = runTest {
        val loopback = LoopbackReceiver { throw IllegalStateException("no port") }
        val browser = BrowserSignIn(AuthApi(createHttpClient(MockEngine { error("no calls") }), cookies, tokens), loopback, keepAlive)
        assertIs<ApiResult.Failed>(browser.signIn(url, null) { events += "open" })
        assertEquals(emptyList(), events)
    }

    @Test
    fun aRefusedHoldStillSignsIn() = runTest {
        val (browser, loopback) = signIn(keepAlive = FakeKeepAlive(grants = false)) { mapOf("code" to "C", "state" to it.parameters["state"]!!) }
        assertIs<ApiResult.Success<Unit>>(browser.signIn(url, null) { events += "open"; loopback.opened = Url(it) })
        assertEquals(listOf("hold", "open", "release"), events)
    }

    @Test
    fun challengeMatchesRfc7636Example() {
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", pkceChallenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
    }

    @Test
    fun verifierIsLongEnoughAndUrlSafe() {
        val verifier = pkceVerifier()
        assertEquals(43, verifier.length)
        assertTrue(verifier.all { it.isLetterOrDigit() || it == '-' || it == '_' })
    }
}
