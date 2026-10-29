package dev.hermeskotlin.ui.chat

import android.content.Context
import androidx.a2ui.compose.runtime.A2uiMessageParser
import androidx.a2ui.compose.ui.A2uiCatalog
import androidx.a2ui.compose.ui.A2uiMessageProcessor
import androidx.a2ui.compose.ui.catalog.A2uiBasicCatalogV1
import androidx.a2ui.model.processor.processInput
import androidx.a2ui.model.protocol.A2uiClientErrorMessage
import androidx.a2ui.model.protocol.A2uiClientEventMessage
import androidx.a2ui.model.protocol.A2uiException
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.a2ui.A2uiSurface
import androidx.compose.material3.a2ui.catalog.MaterialA2uiBasicCatalogV1Defaults
import androidx.compose.material3.a2ui.catalog.materialA2uiBasicCatalogV1
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.semantics.clearAndSetSemantics
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
    screen: AgentScreen,
    live: Boolean,
    onAnswer: (surfaceId: String, result: JsonElement) -> Unit,
    onFail: (surfaceId: String, message: String) -> Unit,
    modifier: Modifier,
) {
    val context = LocalContext.current
    // One processor a screen: the messages are the whole of it, drawn again from the start when they change.
    val processor = remember(screen.messages) { A2uiMessageProcessor(catalogs = listOf(catalog(context))) }
    val isLive by rememberUpdatedState(live)
    val answer by rememberUpdatedState(onAnswer)
    val fail by rememberUpdatedState(onFail)
    LaunchedEffect(processor) { processor.collectMessages() }
    LaunchedEffect(processor) {
        processor.outboundEvents.collect { message ->
            if (!isLive) return@collect
            when (message) {
                is A2uiClientEventMessage -> answer(message.surfaceId, message.toAnswer())
                is A2uiClientErrorMessage -> {
                    // A message that didn't parse belongs to no surface yet; it's still this screen's.
                    val surface = message.surfaceId.takeIf { it in screen.surfaceIds } ?: screen.surfaceIds.first()
                    fail(surface, "${message.code}: ${message.message}")
                }
            }
        }
    }
    LaunchedEffect(processor) {
        val parser = A2uiMessageParser()
        screen.messages.forEach { processor.processInput(parser, it) }
    }
    val surfaces by processor.activeSurfaces.collectAsStateWithLifecycle()
    MaterialTheme(colorScheme = materialColors(), typography = materialType()) {
        Box(modifier) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                surfaces.forEach { key(it.id) { A2uiSurface(surfaceModel = it) } }
            }
            // Answered, or never waited on: what it showed, without taking taps the agent would never hear of.
            if (!live) {
                Box(
                    Modifier.matchParentSize().clearAndSetSemantics {}.pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                        }
                    },
                )
            }
        }
    }
}

/** Why [screen]'s messages aren't A2UI, in words for the agent to fix them; null when they parse. */
fun agentScreenProblem(screen: AgentScreen): String? {
    val parser = A2uiMessageParser()
    screen.messages.forEachIndexed { i, message ->
        try {
            parser.parse(message)
        } catch (e: A2uiException) {
            return "Message ${i + 1} isn't A2UI: ${e.message}"
        }
    }
    return null
}

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
