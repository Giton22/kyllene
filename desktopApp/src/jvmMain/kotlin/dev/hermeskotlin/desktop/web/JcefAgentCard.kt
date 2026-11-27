package dev.hermeskotlin.desktop.web

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import com.composeunstyled.theme.Theme
import dev.hermeskotlin.core.chat.AgentScreen
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.text
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import org.cef.CefApp
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.browser.CefMessageRouter
import org.cef.callback.CefQueryCallback
import org.cef.handler.CefMessageRouterHandlerAdapter
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.GZIPInputStream
import javax.swing.SwingUtilities

private const val CARD_URL = "https://herald-agent-screen.invalid/"
private const val MAX_ANSWER_CHARS = 64 * 1024

private val renderer by lazy {
    val resource = checkNotNull(object {}.javaClass.getResourceAsStream("/agent-screen/renderer.js.gz"))
    GZIPInputStream(resource).bufferedReader().use { it.readText() }
        .replace("</script", "<\\/script", ignoreCase = true)
}

private val scriptHash by lazy {
    Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(renderer.encodeToByteArray()))
}

private fun document() = """
    <!doctype html>
    <meta charset="utf-8">
    <meta http-equiv="Content-Security-Policy" content="default-src 'none'; script-src 'sha256-$scriptHash'; base-uri 'none'; form-action 'none'; style-src 'unsafe-inline'; img-src data: blob:; media-src data: blob:">
    <style>
      body { margin: 0; font: 14px system-ui; overflow-wrap: anywhere }
      main { display: grid; gap: 8px }
    </style>
    <main></main><script>$renderer</script>
""".trimIndent()

private fun Color.css() = "#%06x".format(toArgb() and 0xffffff)

@Composable
internal fun JcefAgentCard(
    open: suspend () -> CefApp?,
    screen: AgentScreen,
    takesTaps: Boolean,
    modifier: Modifier,
    onRendered: () -> Unit,
    onAnswer: (String, JsonElement) -> Unit,
    onFailure: () -> Unit,
) {
    val rendered by rememberUpdatedState(onRendered)
    val answer by rememberUpdatedState(onAnswer)
    val failure by rememberUpdatedState(onFailure)
    val app by produceState<CefApp?>(null) {
        value = open()
        if (value == null) failure()
    }
    val cef = app ?: return Box(modifier)
    val client = remember(cef) { cef.createClient() }
    val live by rememberUpdatedState(takesTaps)
    val surfaces by rememberUpdatedState(screen.surfaceIds)
    val active = remember(client) { AtomicBoolean(true) }
    var ready by remember(client) { mutableStateOf(false) }
    var height by remember(client) { mutableFloatStateOf(48f) }
    var sequence by remember(client) { mutableStateOf(0) }
    var canScrollUp by remember(client) { mutableStateOf(false) }
    var canScrollDown by remember(client) { mutableStateOf(false) }
    val page = remember(client) {
        client.lockDown(
            document = ::document,
            onFailure = { SwingUtilities.invokeLater { if (active.get()) failure() } },
            pageUrl = CARD_URL,
            onReady = { SwingUtilities.invokeLater { if (active.get()) ready = true } },
        )
        val router = CefMessageRouter.create()
        router.addHandler(object : CefMessageRouterHandlerAdapter() {
            override fun onQuery(
                browser: CefBrowser, source: CefFrame, queryId: Long, request: String, persistent: Boolean,
                callback: CefQueryCallback,
            ): Boolean {
                if (!source.isMain || source.url != CARD_URL || request.length > MAX_ANSWER_CHARS) return false
                val message = runCatching { Json.parseToJsonElement(request) as? JsonObject }.getOrNull() ?: return false
                SwingUtilities.invokeLater {
                    if (!active.get() || (message["sequence"] as? JsonPrimitive)?.intOrNull != sequence) return@invokeLater
                    when ((message["type"] as? JsonPrimitive)?.content) {
                        "shown" -> rendered()
                        "error" -> failure()
                        "layout" -> (message["height"] as? JsonPrimitive)?.floatOrNull
                            ?.takeIf { it.isFinite() }?.let {
                                height = it.coerceIn(48f, 520f)
                                canScrollUp = (message["above"] as? JsonPrimitive)?.content == "true"
                                canScrollDown = (message["below"] as? JsonPrimitive)?.content == "true"
                            }
                        "answer" -> {
                            val surface = (message["surface"] as? JsonPrimitive)?.content
                            val result = message["result"] as? JsonObject
                            if (live && surface != null && surface in surfaces && result != null) answer(surface, result)
                        }
                    }
                }
                callback.success("")
                return true
            }
        }, true)
        client.addMessageRouter(router)
        OffscreenBrowser(client, CARD_URL, transparent = true)
    }
    DisposableEffect(client) {
        onDispose {
            active.set(false)
            client.dispose()
        }
    }
    val foreground = Theme[colors][text].css()
    LaunchedEffect(ready, screen.messages, takesTaps, foreground) {
        if (!ready) return@LaunchedEffect
        val messages = JsonArray(screen.messages.map(::JsonPrimitive))
        sequence++
        page.browser.executeJavaScript(
            "document.body.style.color='$foreground';window.renderAgentScreen($messages,$takesTaps,$sequence)",
            CARD_URL,
            0,
        )
    }
    BrowserSurface(page, modifier.fillMaxWidth().height(height.dp)) { down ->
        if (down) canScrollDown else canScrollUp
    }
}
