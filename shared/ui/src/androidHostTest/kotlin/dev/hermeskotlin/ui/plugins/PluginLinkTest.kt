package dev.hermeskotlin.ui.plugins

import android.content.ComponentName
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.composeunstyled.theme.ColorScheme
import dev.hermeskotlin.core.auth.AuthApi
import dev.hermeskotlin.core.auth.NativeTokens
import dev.hermeskotlin.core.auth.PersistentCookiesStorage
import dev.hermeskotlin.core.auth.WebCookieJar
import dev.hermeskotlin.core.gateway.AccessTokens
import dev.hermeskotlin.core.network.createHttpClient
import dev.hermeskotlin.core.storage.InMemoryKeyValueStore
import dev.hermeskotlin.designsystem.HermesTheme
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A link on a plugin page that leaves the gateway opens like every other web link in the app: through
 * [LocalUriHandler], which on Android is the in-app browser (a Custom Tab), not a bare intent to the
 * system's browser.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PluginLinkTest {
    private val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val rules: TestRule = RuleChain.outerRule(TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                val app = RuntimeEnvironment.getApplication()
                Shadows.shadowOf(app.packageManager).addActivityIfNotPresent(
                    ComponentName(app.packageName, ComponentActivity::class.java.name),
                )
                base.evaluate()
            }
        }
    }).around(compose)

    @After
    fun tearDown() = stopKoin()

    @Test
    fun aLinkOffTheGatewayOpensInTheAppsBrowser() {
        val opened = mutableListOf<String>()
        val webView = showPage(object : UriHandler {
            override fun openUri(uri: String) { opened += uri }
        })

        val handled = compose.runOnIdle { webView.webViewClient.shouldOverrideUrlLoading(webView, request("https://docs.example.org/plugin")) }

        assertTrue(handled, "the plugin page must not navigate away itself")
        assertEquals(listOf("https://docs.example.org/plugin"), opened)
        assertNull(Shadows.shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity, "no bare intent to the system browser")
    }

    @Test
    fun aLinkOnTheGatewayStaysOnThePage() {
        val opened = mutableListOf<String>()
        val webView = showPage(object : UriHandler {
            override fun openUri(uri: String) { opened += uri }
        })

        val handled = compose.runOnIdle { webView.webViewClient.shouldOverrideUrlLoading(webView, request("$GATEWAY/example-board/other")) }

        assertEquals(false, handled)
        assertEquals(emptyList(), opened)
    }

    private fun showPage(uris: UriHandler): WebView {
        val store = InMemoryKeyValueStore()
        val cookies = PersistentCookiesStorage(store)
        val tokens = NativeTokens(store)
        val client = createHttpClient(MockEngine { respond("{}") }, cookies)
        startKoin {
            modules(module { single { WebCookieJar(cookies, AccessTokens(store), tokens, AuthApi(client, cookies, tokens)) } })
        }
        compose.setContent {
            HermesTheme(ColorScheme.Light) {
                CompositionLocalProvider(LocalUriHandler provides uris) {
                    PluginWebView("$GATEWAY/example-board", GATEWAY, "Example board", onClose = {})
                }
            }
        }
        compose.waitForIdle()
        return assertNotNull(compose.runOnIdle { compose.activity.window.decorView.findWebView() }, "no WebView on screen")
    }

    private fun request(url: String) = object : WebResourceRequest {
        override fun getUrl(): Uri = Uri.parse(url)
        override fun isForMainFrame() = true
        override fun isRedirect() = false
        override fun hasGesture() = true
        override fun getMethod() = "GET"
        override fun getRequestHeaders() = emptyMap<String, String>()
    }

    private fun View.findWebView(): WebView? {
        if (this is WebView) return this
        if (this is ViewGroup) for (i in 0 until childCount) getChildAt(i).findWebView()?.let { return it }
        return null
    }

    private companion object {
        const val GATEWAY = "https://hermes.example.ts.net"
    }
}
