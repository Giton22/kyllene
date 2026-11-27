package dev.hermeskotlin.core.auth

import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.network.ApiResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.decrementAndFetch
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.io.encoding.Base64
import kotlin.time.Duration.Companion.minutes

/** Listens on the phone's loopback address for the browser to come back from the sign-in. */
fun interface LoopbackReceiver {
    /** Starts listening on 127.0.0.1 at a free port. */
    suspend fun start(): LoopbackListener
}

interface LoopbackListener : AutoCloseable {
    /** `http://127.0.0.1:<port>/<path>`: where the gateway sends the browser back to. */
    val redirectUri: String

    /** The query of the next request to [redirectUri]'s path; that request is answered by sending the browser back to the app. */
    suspend fun awaitCallback(): Map<String, String>
}

/**
 * Signs in through the system browser, the way the gateway's native sign-in (RFC 8252 + PKCE) works: the browser
 * opens `/auth/native/authorize`, the user signs in with whatever the gateway offers (an OIDC provider, Nous, or
 * its own password page), and the gateway sends the browser to [LoopbackReceiver]'s address with a one-time code.
 * The code and the PKCE verifier, which never left the app, then buy the bearer tokens.
 */
class BrowserSignIn(
    private val auth: AuthApi,
    private val loopback: LoopbackReceiver,
    private val keepAlive: SignInKeepAlive = SignInKeepAlive.None,
) {

    /**
     * Sign-ins waiting for the browser. Usually one, but a cancelled one lets go only once its listener notices, which
     * can be after the next one started: the app is held while any waits.
     */
    @OptIn(ExperimentalAtomicApi::class)
    private val waiting = AtomicInt(0)

    /** The platform won't let the browser hand a sign-in back (battery usage Restricted): see [SignInKeepAlive.restricted]. */
    val restricted: StateFlow<Boolean> get() = keepAlive.restricted

    /** [openBrowser] shows the sign-in page. A blank [provider] lets the gateway pick, or ask when it has several. */
    suspend fun signIn(url: GatewayUrl, provider: String?, openBrowser: (String) -> Unit): ApiResult<Unit> {
        val listener = try {
            loopback.start()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return ApiResult.Failed(0, "Couldn't get ready for the browser to come back: ${e.message}")
        }
        return listener.use {
            val verifier = pkceVerifier()
            val state = randomToken(16)
            // Held while the app is still in front, which is when Android allows it, and until the code is redeemed.
            hold()
            try {
                openBrowser(auth.nativeAuthorizeUrl(url, provider, pkceChallenge(verifier), listener.redirectUri, state))
                // The gateway forgets an unfinished sign-in after 10 minutes. Anything else that reaches the address (a
                // reload of an old page, another app) doesn't carry this sign-in's state and is passed over.
                val code = withTimeoutOrNull(10.minutes) {
                    var query = listener.awaitCallback()
                    while (query["state"] != state || query["code"].isNullOrEmpty()) query = listener.awaitCallback()
                    query.getValue("code")
                }
                if (code == null) ApiResult.Failed(0, "The sign-in took too long. Try again.")
                else auth.redeemNativeCode(url, code, verifier)
            } finally {
                release()
            }
        }
    }

    @OptIn(ExperimentalAtomicApi::class)
    private fun hold() {
        if (waiting.incrementAndFetch() == 1) keepAlive.hold()
    }

    @OptIn(ExperimentalAtomicApi::class)
    private fun release() {
        if (waiting.decrementAndFetch() == 0) keepAlive.release()
    }
}

/** RFC 7636 code verifier: 32 random bytes, base64url, 43 characters. */
internal fun pkceVerifier(): String = randomToken(32)

/** The S256 challenge for [verifier]: base64url of its SHA-256, without padding. */
internal fun pkceChallenge(verifier: String): String = UrlSafe.encode(sha256(verifier.encodeToByteArray()))

private fun randomToken(bytes: Int): String = UrlSafe.encode(secureRandomBytes(bytes))

private val UrlSafe = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)

internal expect fun sha256(bytes: ByteArray): ByteArray

internal expect fun secureRandomBytes(count: Int): ByteArray
