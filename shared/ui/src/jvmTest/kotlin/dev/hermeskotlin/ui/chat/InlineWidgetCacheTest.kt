package dev.hermeskotlin.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
    fun stackedWidgetsKeepTheirHeightsWhenNewBrowsersReportDifferentSizes() = runComposeUiTest {
        val widgets = (1..3).map { GatewayFile("/tmp/widget-$it.html") }
        var loads = 0
        val views = mutableMapOf<String, Int>()
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
                Box(modifier.testTag(name))
                DisposableEffect(Unit) {
                    val count = (views[name] ?: 0) + 1
                    views[name] = count
                    val established = 176 + 12 * widgets.indexOfFirst { it.name == name }
                    if (count == 1) onTitle("widget:tb||first|$established,280")
                    else onTitle("widget:tb||second|248,$established")
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
                    if (visible) Column(Modifier.width(width)) {
                        widgets.forEach { file ->
                            InlineWidget(ReplyMedia(file.path, file.name, false, preview = true), file, files, messageKey)
                        }
                    }
                }
            }
        }

        fun heights() = widgets.map { onNodeWithTag(it.name).fetchSemanticsNode().boundsInRoot.height }
        val measured = heights()
        runOnIdle { visible = false }
        widgets.forEach { onNodeWithTag(it.name).assertDoesNotExist() }
        runOnIdle { assertEquals(3, disposed); visible = true }
        assertEquals(measured, heights())
        runOnIdle { assertEquals(3, loads); assertEquals(listOf(2, 2, 2), views.values.toList()); width = 300.dp }
        assertNotEquals(measured, heights())
        runOnIdle { assertEquals(3, loads); messageKey = "new-reply" }
        runOnIdle { assertEquals(6, loads) }
    }
}
