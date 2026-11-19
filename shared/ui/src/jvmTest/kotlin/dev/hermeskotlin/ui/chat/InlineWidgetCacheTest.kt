package dev.hermeskotlin.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.hermeskotlin.core.chat.ReplyMedia
import dev.hermeskotlin.core.media.FileText
import dev.hermeskotlin.core.media.GatewayFile
import dev.hermeskotlin.designsystem.HermesTheme
import dev.hermeskotlin.ui.DesktopWeb
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import org.koin.compose.KoinIsolatedContext
import org.koin.dsl.koinApplication
import org.koin.dsl.module

@OptIn(ExperimentalTestApi::class)
class InlineWidgetCacheTest {
    @Test
    fun aRecreatedWidgetUsesItsCachedHtmlAndHeightBeforeItsBrowserReports() = runComposeUiTest {
        val file = GatewayFile("/tmp/document.html")
        var loads = 0
        var views = 0
        var disposed = 0
        val files = WidgetFiles("chat", {
            loads++
            FileLoad.Ready(FileText("<p>Fixture</p>", 14, false, false))
        }, { error("Unexpected download") })
        val web = object : DesktopWeb {
            @Composable
            override fun Widget(
                document: String,
                name: String,
                modifier: Modifier,
                keepsWheel: (String?, Boolean) -> Boolean,
                onTitle: (String?) -> Unit,
                onFailure: (String) -> Unit,
            ) {
                Box(modifier.testTag("widget"))
                DisposableEffect(Unit) {
                    if (++views == 1) onTitle("widget:tb||first|450,280")
                    onDispose { disposed++ }
                }
            }

            @Composable
            override fun PluginPage(url: String, gateway: String, title: String, onClose: () -> Unit) = Unit
        }
        val app = koinApplication { modules(module { single<DesktopWeb> { web } }) }
        var visible by mutableStateOf(true)
        var width by mutableStateOf(400.dp)
        var messageKey by mutableStateOf("reply")
        setContent {
            KoinIsolatedContext(app) {
                HermesTheme {
                    if (visible) Box(Modifier.width(width)) {
                        InlineWidget(ReplyMedia(file.path, file.name, false, preview = true), file, files, messageKey)
                    }
                }
            }
        }

        fun height() = onNodeWithTag("widget").fetchSemanticsNode().boundsInRoot.height
        val measured = height()
        runOnIdle { visible = false }
        onNodeWithTag("widget").assertDoesNotExist()
        runOnIdle { assertEquals(1, disposed); visible = true }
        assertEquals(measured, height())
        runOnIdle { assertEquals(1, loads); assertEquals(2, views); width = 300.dp }
        assertNotEquals(measured, height())
        runOnIdle { assertEquals(1, loads); messageKey = "new-reply" }
        runOnIdle { assertEquals(2, loads) }
    }
}
