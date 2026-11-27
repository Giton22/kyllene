package dev.hermeskotlin.desktop.web

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.composeunstyled.Text
import com.composeunstyled.theme.Theme
import dev.hermeskotlin.core.chat.AgentScreen
import dev.hermeskotlin.designsystem.accent
import dev.hermeskotlin.designsystem.background
import dev.hermeskotlin.designsystem.caption
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.danger
import dev.hermeskotlin.designsystem.input
import dev.hermeskotlin.designsystem.onAccent
import dev.hermeskotlin.designsystem.strokeStrong
import dev.hermeskotlin.designsystem.surface
import dev.hermeskotlin.designsystem.surface3
import dev.hermeskotlin.designsystem.text
import dev.hermeskotlin.designsystem.typography
import dev.hermeskotlin.designsystem.warning
import dev.hermeskotlin.desktop.resources.Res
import dev.hermeskotlin.desktop.resources.agent_card_answer_too_large
import dev.hermeskotlin.desktop.resources.agent_card_draft_lost
import dev.hermeskotlin.desktop.resources.agent_card_draft_too_large
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import org.cef.CefApp
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.browser.CefMessageRouter
import org.cef.callback.CefQueryCallback
import org.cef.handler.CefMessageRouterHandlerAdapter
import org.jetbrains.compose.resources.stringResource
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.GZIPInputStream
import javax.swing.SwingUtilities

private const val CARD_URL = "https://herald-agent-screen.invalid/"
private const val MAX_QUERY_CHARS = 64 * 1024
private const val MAX_DRAFTS = 32

/** Local form state for cards that leave the lazy list, keyed by the chat and the call that owns the card. */
internal class AgentCardDrafts {
    // A null model records an edit that exceeded the bridge limit, replacing any older saved text.
    data class Draft(val prefix: Int, val fingerprint: String, val models: JsonObject?)

    private val drafts = LinkedHashMap<String, Draft>(MAX_DRAFTS, 0.75f, true)

    @Synchronized
    fun restore(key: String, messages: List<String>): Draft? {
        val draft = drafts[key] ?: return null
        if (draft.prefix > messages.size || draft.fingerprint != messageFingerprint(messages.take(draft.prefix))) return null
        return draft
    }

    @Synchronized
    fun save(key: String, prefix: Int, fingerprint: String, models: JsonObject?) {
        drafts[key] = Draft(prefix, fingerprint, models)
        if (drafts.size > MAX_DRAFTS) drafts.remove(drafts.keys.first())
    }
}

internal fun messageFingerprint(messages: List<String>): String {
    val digest = MessageDigest.getInstance("SHA-256")
    for (message in messages) {
        digest.update(message.encodeToByteArray())
        digest.update(0.toByte())
    }
    return Base64.getEncoder().encodeToString(digest.digest())
}

private data class CardSubmission(
    val sequence: Int,
    val revision: String,
    val messages: List<String>,
    val takesTaps: Boolean,
    val validateOnly: Boolean,
) {
    val fingerprint by lazy(LazyThreadSafetyMode.NONE) { messageFingerprint(messages) }
}

private class CardBridgeState {
    var submitted: CardSubmission? = null
    var previousInteractive: CardSubmission? = null
    var lastDraftSequence = 0
}

private enum class DraftNotice { TooLarge, Lost }

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

private fun Color.css(): String {
    val argb = toArgb()
    val alpha = argb ushr 24
    return if (alpha == 255) "#%06x".format(argb and 0xffffff) else "#%08x".format((argb shl 8) or alpha)
}

@Composable
private fun agentCardTheme(): JsonObject {
    val palette = Theme[colors]
    val ink = palette[text].css()
    val ground = palette[background]
    // Basic Catalog fallbacks live on :root. A surface can still supply its own primary color.
    return buildJsonObject {
        put("color-scheme", if (ground.luminance() < 0.5f) "dark" else "light")
        put("color", ink)
        put("--a2ui-color-background", ground.css())
        put("--a2ui-color-on-background", ink)
        put("--a2ui-color-surface", palette[surface].css())
        put("--a2ui-color-on-surface", ink)
        put("--a2ui-color-primary", palette[accent].css())
        put("--a2ui-color-on-primary", palette[onAccent].css())
        put("--a2ui-color-secondary", palette[surface3].css())
        put("--a2ui-color-on-secondary", ink)
        put("--a2ui-color-input", palette[input].css())
        put("--a2ui-color-on-input", ink)
        put("--a2ui-color-border", palette[strokeStrong].css())
        put("--a2ui-textfield-color-error", palette[danger].css())
    }
}

@Composable
internal fun JcefAgentCard(
    open: suspend () -> CefApp?,
    drafts: AgentCardDrafts,
    draftKey: String,
    revision: String,
    screen: AgentScreen,
    takesTaps: Boolean,
    validateOnly: Boolean,
    modifier: Modifier,
    onRendered: (Set<String>) -> Unit,
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
    val client = remember(cef, draftKey) { cef.createClient() }
    val live by rememberUpdatedState(takesTaps)
    val currentScreen by rememberUpdatedState(screen)
    val currentRevision by rememberUpdatedState(revision)
    val validating by rememberUpdatedState(validateOnly)
    val active = remember(client) { AtomicBoolean(true) }
    var ready by remember(client) { mutableStateOf(false) }
    var height by remember(client) { mutableFloatStateOf(48f) }
    val bridge = remember(client) { CardBridgeState() }
    var answerTooLarge by remember(client) { mutableStateOf(false) }
    var draftNotice by remember(client) { mutableStateOf<DraftNotice?>(null) }
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
                if (!source.isMain || source.url != CARD_URL || request.length > MAX_QUERY_CHARS) return false
                val message = runCatching { Json.parseToJsonElement(request) as? JsonObject }.getOrNull() ?: return false
                SwingUtilities.invokeLater {
                    val sequence = (message["sequence"] as? JsonPrimitive)?.intOrNull ?: return@invokeLater
                    val type = (message["type"] as? JsonPrimitive)?.content
                    val submitted = bridge.submitted
                    if (type == "draft" || type == "draftTooLarge") {
                        // An edit already sent by the page can arrive while its row is being disposed or updated.
                        val source = submitted?.takeIf { it.sequence == sequence && it.takesTaps && !it.validateOnly }
                            ?: bridge.previousInteractive?.takeIf { it.sequence == sequence }
                        val models = message["models"] as? JsonObject
                        if (source != null && (models != null || type == "draftTooLarge") && sequence >= bridge.lastDraftSequence) {
                            drafts.save(draftKey, source.messages.size, source.fingerprint, models)
                            bridge.lastDraftSequence = sequence
                            if (active.get()) draftNotice = if (models == null) DraftNotice.TooLarge else null
                        }
                        return@invokeLater
                    }
                    if (!active.get() || submitted == null || submitted.sequence != sequence || submitted.revision != currentRevision ||
                        submitted.messages != currentScreen.messages || submitted.validateOnly != validating
                    ) return@invokeLater
                    when (type) {
                        "shown", "validated" -> if ((type == "validated") == submitted.validateOnly) {
                            val surfaces = (message["surfaces"] as? JsonArray).orEmpty().mapNotNullTo(LinkedHashSet()) {
                                (it as? JsonPrimitive)?.takeIf { id -> id.isString }?.contentOrNull
                                    ?.takeIf(currentScreen.surfaceIds::contains)
                            }
                            if (surfaces.isNotEmpty()) rendered(surfaces)
                        }
                        "error" -> failure()
                        "answerTooLarge" -> if (live && submitted.takesTaps && !submitted.validateOnly) {
                            answerTooLarge = true
                        }
                        "layout" -> (message["height"] as? JsonPrimitive)?.floatOrNull
                            ?.takeIf { it.isFinite() }?.let {
                                height = it.coerceIn(48f, 520f)
                                canScrollUp = (message["above"] as? JsonPrimitive)?.content == "true"
                                canScrollDown = (message["below"] as? JsonPrimitive)?.content == "true"
                            }
                        "answer" -> {
                            val surface = (message["surface"] as? JsonPrimitive)?.content
                            val result = message["result"] as? JsonObject
                            if (live && submitted.takesTaps && !submitted.validateOnly &&
                                surface != null && surface in currentScreen.surfaceIds && result != null
                            ) {
                                answerTooLarge = false
                                answer(surface, result)
                            }
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
    val theme = agentCardTheme()
    LaunchedEffect(revision) { answerTooLarge = false }
    LaunchedEffect(ready, revision, screen.messages, takesTaps, validateOnly, theme) {
        if (!ready) return@LaunchedEffect
        val messages = JsonArray(screen.messages.map(::JsonPrimitive))
        val saved = drafts.restore(draftKey, screen.messages)
        val previous = bridge.submitted
        if (previous == null && saved != null && saved.models == null) draftNotice = DraftNotice.Lost
        val draft = saved?.models?.let { models ->
            val payload = buildJsonObject {
                put("prefix", saved.prefix)
                put("models", models)
            }
            "JSON.parse(${JsonPrimitive(payload.toString())})"
        } ?: "null"
        if (previous != null && previous.takesTaps && !previous.validateOnly) bridge.previousInteractive = previous
        val sequence = (previous?.sequence ?: 0) + 1
        bridge.submitted = CardSubmission(sequence, revision, screen.messages, takesTaps, validateOnly)
        page.browser.executeJavaScript(
            "window.setAgentScreenTheme($theme);window.renderAgentScreen($messages,$takesTaps,$sequence,$draft,$validateOnly)",
            CARD_URL,
            0,
        )
    }
    Column(modifier.fillMaxWidth()) {
        BrowserSurface(page, Modifier.fillMaxWidth().height(height.dp)) { down ->
            if (down) canScrollDown else canScrollUp
        }
        if (takesTaps && answerTooLarge) {
            AgentCardNotice(stringResource(Res.string.agent_card_answer_too_large))
        }
        when (draftNotice) {
            DraftNotice.TooLarge -> AgentCardNotice(stringResource(Res.string.agent_card_draft_too_large))
            DraftNotice.Lost -> AgentCardNotice(stringResource(Res.string.agent_card_draft_lost))
            null -> Unit
        }
    }
}

@Composable
private fun AgentCardNotice(message: String) {
    Text(
        text = message,
        style = Theme[typography][caption],
        color = Theme[colors][warning],
        modifier = Modifier.padding(top = 4.dp).semantics { liveRegion = LiveRegionMode.Polite },
    )
}
