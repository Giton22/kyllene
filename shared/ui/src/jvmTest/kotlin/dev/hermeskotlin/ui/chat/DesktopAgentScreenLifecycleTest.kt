package dev.hermeskotlin.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.composeunstyled.theme.ColorScheme
import dev.hermeskotlin.core.chat.AgentScreen
import dev.hermeskotlin.core.chat.SHOW_UI_TOOL
import dev.hermeskotlin.core.chat.ScreenView
import dev.hermeskotlin.core.chat.ToolActivity
import dev.hermeskotlin.core.phone.AgentScreens
import dev.hermeskotlin.designsystem.HermesTheme
import dev.hermeskotlin.ui.DesktopWeb
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.koin.compose.KoinIsolatedContext
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class DesktopAgentScreenLifecycleTest {
    @Test
    fun anUnfocusedDesktopCardAcknowledgesRendering() = runComposeUiTest {
        val owner = object : LifecycleOwner {
            override val lifecycle = LifecycleRegistry.createUnsafe(this)
        }
        val screen = AgentScreen(listOf("""{"createSurface":{"surfaceId":"pick"}}"""), setOf("pick"), wait = false)
        val call = ToolActivity("choose", SHOW_UI_TOOL, running = true, agentScreen = screen)
        val registry = AgentScreens(shownWithinMs = 1_500)
        val result = CompletableDeferred<Result<JsonElement>>()
        val koin = koinApplication {
            modules(module {
                single { registry }
                single<DesktopWeb> { RenderingBrowser }
            })
        }

        setContent {
            KoinIsolatedContext(koin) {
                HermesTheme(ColorScheme.Light) {
                    CompositionLocalProvider(LocalLifecycleOwner provides owner, LocalAgentScreenChat provides "chat") {
                        CompositionLocalProvider(LocalAgentScreens provides rememberAgentScreenContext()) {
                            LaunchedEffect(Unit) {
                                owner.lifecycle.currentState = Lifecycle.State.STARTED
                                result.complete(runCatching { registry.await(screen, "chat", callId = call.id) })
                            }
                            AgentScreenCard(ScreenView(call, call, screen), newest = true)
                        }
                    }
                }
            }
        }

        waitUntil(timeoutMillis = 5_000) { result.isCompleted }
        assertEquals(buildJsonObject { put("shown", true) }, result.await().getOrThrow())
    }

    private object RenderingBrowser : DesktopWeb {
        @Composable
        override fun AgentCard(
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
            LaunchedEffect(revision, validateOnly) { onRendered(screen.surfaceIds) }
        }

        @Composable
        override fun Widget(
            document: String,
            name: String,
            modifier: Modifier,
            keepsWheel: (String?, Boolean) -> Boolean,
            onFailure: (String) -> Unit,
        ) = Unit

        @Composable
        override fun PluginPage(url: String, gateway: String, title: String, onClose: () -> Unit) = Unit
    }
}
