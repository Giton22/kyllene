package dev.hermeskotlin.ui.chat

import android.content.Context
import androidx.a2ui.compose.runtime.A2uiMessageParser
import androidx.a2ui.compose.ui.A2uiCatalog
import androidx.a2ui.compose.ui.A2uiMessageProcessor
import androidx.a2ui.compose.ui.catalog.A2uiBasicCatalogV1
import androidx.a2ui.model.processor.A2uiMessageProcessor
import androidx.a2ui.model.processor.processInput
import androidx.a2ui.model.protocol.A2uiClientErrorMessage
import androidx.a2ui.model.protocol.A2uiClientEventMessage
import androidx.a2ui.model.protocol.A2uiException
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.a2ui.A2uiSurface
import androidx.compose.material3.a2ui.catalog.MaterialA2uiBasicCatalogV1Defaults
import androidx.compose.material3.a2ui.catalog.materialA2uiBasicCatalogV1
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composeunstyled.theme.Theme
import dev.hermeskotlin.core.chat.AgentScreen
import dev.hermeskotlin.designsystem.accent
import dev.hermeskotlin.designsystem.accentSoft
import dev.hermeskotlin.designsystem.accentText
import dev.hermeskotlin.designsystem.background
import dev.hermeskotlin.designsystem.body
import dev.hermeskotlin.designsystem.bodySmall
import dev.hermeskotlin.designsystem.caption
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.danger
import dev.hermeskotlin.designsystem.dangerSoft
import dev.hermeskotlin.designsystem.display
import dev.hermeskotlin.designsystem.heading
import dev.hermeskotlin.designsystem.label
import dev.hermeskotlin.designsystem.onAccent
import dev.hermeskotlin.designsystem.stroke
import dev.hermeskotlin.designsystem.strokeStrong
import dev.hermeskotlin.designsystem.surface
import dev.hermeskotlin.designsystem.surface2
import dev.hermeskotlin.designsystem.surface3
import dev.hermeskotlin.designsystem.text
import dev.hermeskotlin.designsystem.textSecondary
import dev.hermeskotlin.designsystem.title
import dev.hermeskotlin.designsystem.typography
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal actual val drawsAgentScreens: Boolean = true

@Composable
internal actual fun AgentScreenSurface(
    key: String,
    screen: AgentScreen,
    live: Boolean,
    onAnswer: (surfaceId: String, result: JsonElement) -> Unit,
    onFail: (surfaceId: String, message: String) -> Unit,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val drawn = remember(key, screen) { DrawnScreens.of(context, key, screen) }
    val answer by rememberUpdatedState(onAnswer)
    val fail by rememberUpdatedState(onFail)
    // Taps reach the agent only while its call waits; at other times the screen is what it showed.
    DisposableEffect(drawn, live) {
        drawn.onTap = if (live) { tap -> answer(tap.surfaceId, tap.toAnswer()) } else null
        onDispose { drawn.onTap = null }
    }
    val error by drawn.error.collectAsStateWithLifecycle()
    LaunchedEffect(live, error) {
        val (surface, message) = error ?: return@LaunchedEffect
        if (live) fail(surface, message)
    }
    val surfaces by drawn.processor.activeSurfaces.collectAsStateWithLifecycle()
    val textColor = Theme[colors][text]
    MaterialTheme(colorScheme = materialColors(), typography = materialType()) {
        // Material's text takes its color from a Surface around it; there's none here, so it's given the app's.
        CompositionLocalProvider(LocalContentColor provides textColor) {
            Box(modifier) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    surfaces.forEach { key(it.id) { A2uiSurface(surfaceModel = it) } }
                }
                // Answered, or never waited on: no taps on it. TalkBack still reads it; a click it sends goes
                // nowhere, since onTap is off.
                if (!live) {
                    Box(
                        Modifier.matchParentSize().pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                            }
                        },
                    )
                }
            }
        }
    }
}

/**
 * A screen's processor, run apart from the chat's list: scrolled out of sight and back, the screen keeps what the
 * user typed or picked. The first error drawing it is kept for the agent until the screen is waited on.
 */
private class DrawnScreen(context: Context, screen: AgentScreen) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val processor: A2uiMessageProcessor = A2uiMessageProcessor(catalogs = listOf(catalog(context)))
    val error = MutableStateFlow<Pair<String, String>?>(null)

    @Volatile var onTap: ((A2uiClientEventMessage) -> Unit)? = null

    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            processor.outboundEvents.collect { message ->
                when (message) {
                    is A2uiClientEventMessage -> onTap?.invoke(message)
                    is A2uiClientErrorMessage -> error.compareAndSet(null, screen.surfaceOf(message) to message.describe())
                }
            }
        }
        scope.launch { processor.collectMessages() }
        val parser = A2uiMessageParser()
        screen.messages.forEach { processor.processInput(parser, it) }
    }

    fun close() = scope.cancel()
}

/** The screens drawn lately, by tool call: a few, the oldest let go. */
private object DrawnScreens {
    private const val MAX = 12
    private val drawn = object : LinkedHashMap<String, Pair<AgentScreen, DrawnScreen>>(MAX, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<AgentScreen, DrawnScreen>>): Boolean =
            (size > MAX).also { if (it) eldest.value.second.close() }
    }

    fun of(context: Context, key: String, screen: AgentScreen): DrawnScreen = synchronized(drawn) {
        drawn[key]?.takeIf { it.first == screen }?.second
            ?: DrawnScreen(context.applicationContext, screen).also { new ->
                drawn.put(key, screen to new)?.second?.close()
            }
    }
}

/**
 * Why [screen]'s messages aren't a screen Kyllene can draw, in words for the agent to fix them; null when they are.
 * Each must parse, and together they must make their surfaces with no error (an unknown catalog, a missing root):
 * found here, before the call waits, rather than once the user looks.
 */
suspend fun agentScreenProblem(context: Context, screen: AgentScreen): String? {
    val parser = A2uiMessageParser()
    screen.messages.forEachIndexed { i, message ->
        try {
            parser.parse(message)
        } catch (e: A2uiException) {
            return "Message ${i + 1} isn't A2UI: ${e.message}"
        }
    }
    return coroutineScope {
        val processor = A2uiMessageProcessor(catalogs = listOf(catalog(context.applicationContext)))
        val errors = MutableStateFlow<String?>(null)
        val listen = launch(start = CoroutineStart.UNDISPATCHED) {
            processor.outboundEvents.collect { if (it is A2uiClientErrorMessage) errors.compareAndSet(null, it.describe()) }
        }
        val run = launch { processor.collectMessages() }
        screen.messages.forEach { processor.processInput(parser, it) }
        withTimeoutOrNull(CHECK_MS) {
            while (errors.value == null && !processor.activeSurfaces.value.map { it.id }.containsAll(screen.surfaceIds)) delay(20)
            // A moment more for an error about a surface just made.
            delay(50)
        }
        listen.cancel()
        run.cancel()
        errors.value
    }
}

private const val CHECK_MS = 1_500L

private fun AgentScreen.surfaceOf(error: A2uiClientErrorMessage): String =
    // A message that didn't parse belongs to no surface yet; it's still this screen's.
    error.surfaceId.takeIf { it in surfaceIds } ?: surfaceIds.first()

private fun A2uiClientErrorMessage.describe() = "$code: $message"

/**
 * The Basic Catalog in Material 3. Pictures, video and audio draw nothing for now (the tool tells the agent so):
 * an error from one would end the wait for the whole screen.
 */
private fun catalog(context: Context): A2uiCatalog = materialA2uiBasicCatalogV1(
    image = MaterialA2uiBasicCatalogV1Defaults.image { _, _, _, _, _ -> },
    video = MaterialA2uiBasicCatalogV1Defaults.video { _, _, _ -> },
    audioPlayer = MaterialA2uiBasicCatalogV1Defaults.audioPlayer { _, _, _, _ -> },
    functions = A2uiBasicCatalogV1.Functions(context),
)

/** The app's colors in Material's roles, so the catalog's components look like the rest of the chat. */
@Composable
private fun materialColors(): ColorScheme {
    val dark = Theme[colors][background].luminance() < 0.5f
    val base = if (dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = Theme[colors][accent],
        onPrimary = Theme[colors][onAccent],
        primaryContainer = Theme[colors][accentSoft],
        onPrimaryContainer = Theme[colors][accentText],
        secondary = Theme[colors][accent],
        onSecondary = Theme[colors][onAccent],
        secondaryContainer = Theme[colors][accentSoft],
        onSecondaryContainer = Theme[colors][accentText],
        background = Theme[colors][surface],
        onBackground = Theme[colors][text],
        surface = Theme[colors][surface],
        onSurface = Theme[colors][text],
        surfaceVariant = Theme[colors][surface2],
        onSurfaceVariant = Theme[colors][textSecondary],
        surfaceContainerLowest = Theme[colors][surface],
        surfaceContainerLow = Theme[colors][surface2],
        surfaceContainer = Theme[colors][surface2],
        surfaceContainerHigh = Theme[colors][surface3],
        surfaceContainerHighest = Theme[colors][surface3],
        outline = Theme[colors][strokeStrong],
        outlineVariant = Theme[colors][stroke],
        error = Theme[colors][danger],
        errorContainer = Theme[colors][dangerSoft],
        onErrorContainer = Theme[colors][danger],
    )
}

/** The app's type steps in Material's roles. */
@Composable
private fun materialType(): Typography {
    val display = Theme[typography][display]
    val title = Theme[typography][title]
    val heading = Theme[typography][heading]
    val body = Theme[typography][body]
    val small = Theme[typography][bodySmall]
    val label = Theme[typography][label]
    val caption = Theme[typography][caption]
    return Typography(
        displayLarge = display, displayMedium = display, displaySmall = display,
        headlineLarge = title, headlineMedium = title, headlineSmall = title,
        titleLarge = title, titleMedium = heading, titleSmall = label,
        bodyLarge = body, bodyMedium = body, bodySmall = small,
        labelLarge = label, labelMedium = label, labelSmall = caption,
    )
}

/** What the agent reads back from a tap: which action, on which component, and what the screen's form holds. */
private fun A2uiClientEventMessage.toAnswer(): JsonElement = buildJsonObject {
    put("event", type)
    put("surface_id", surfaceId)
    put("component_id", componentId)
    put("context", toJson(context))
    clientDataModel?.surfaces?.get(surfaceId)?.let { put("data", toJson(it)) }
}

private fun toJson(value: Any?): JsonElement = when (value) {
    null -> JsonNull
    is JsonElement -> value
    is String -> JsonPrimitive(value)
    is Number -> JsonPrimitive(value)
    is Boolean -> JsonPrimitive(value)
    is Map<*, *> -> JsonObject(value.entries.associate { (k, v) -> k.toString() to toJson(v) })
    is Iterable<*> -> JsonArray(value.map(::toJson))
    is Array<*> -> JsonArray(value.map(::toJson))
    else -> JsonPrimitive(value.toString())
}
