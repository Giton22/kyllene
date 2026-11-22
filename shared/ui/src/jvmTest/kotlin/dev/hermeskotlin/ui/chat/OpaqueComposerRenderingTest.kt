package dev.hermeskotlin.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.composeunstyled.theme.ColorScheme
import com.composeunstyled.theme.Theme
import dev.hermeskotlin.core.chat.ChatMessage
import dev.hermeskotlin.core.chat.ChatState
import dev.hermeskotlin.core.chat.TodoItem
import dev.hermeskotlin.core.chat.TodoList
import dev.hermeskotlin.core.chat.TodoStatus
import dev.hermeskotlin.core.settings.AppSettings
import dev.hermeskotlin.core.slash.SlashSuggestion
import dev.hermeskotlin.designsystem.HermesTheme
import dev.hermeskotlin.designsystem.LocalDesktopLayout
import dev.hermeskotlin.designsystem.background
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.ui.LocalAppSettings
import dev.hermeskotlin.ui.preview.PreviewChatActions
import dev.hermeskotlin.ui.voice.DictationState
import dev.hermeskotlin.ui.voice.VoiceChatState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class OpaqueComposerRenderingTest {
    @Test
    fun bottomBackdropCoversPlanAndComposerButLeavesCommandListOverWallpaper() = runComposeUiTest {
        val opaque = mutableStateOf(false)
        val showCommands = mutableStateOf(false)
        var page = Color.Unspecified
        setContent {
            HermesTheme(ColorScheme.Dark) {
                page = Theme[colors][background]
                Conversation(opaque.value, desktop = false, empty = false, commands = showCommands.value)
            }
        }

        val gap = (rowBottom("Last plan 1/1") + rowTop("Draft")) / 2
        assertNotEquals(page, edgePixel(gap))
        runOnIdle { opaque.value = true }
        assertEquals(page, edgePixel(gap))
        assertEquals(page, edgePixel(rowCenter("Last plan 1/1")))
        assertEquals(page, edgePixel(rowCenter("Draft")))

        runOnIdle { showCommands.value = true }
        assertTrue(rowBottom("/help") < rowTop("Last plan 1/1"))
        val openGap = (rowBottom("Last plan 1/1") + rowTop("Draft")) / 2
        assertEquals(page, edgePixel(openGap))
        assertNotEquals(page, edgePixel(rowCenter("/help")))
        assertEquals(page, edgePixel(rowCenter("Last plan 1/1")))
        assertEquals(page, edgePixel(rowCenter("Draft")))
    }

    @Test
    fun centeredDesktopComposerKeepsWallpaperAtItsEdges() = runComposeUiTest {
        val opaque = mutableStateOf(false)
        setContent {
            HermesTheme(ColorScheme.Dark) {
                Conversation(opaque.value, desktop = true, empty = true, commands = true)
            }
        }

        val before = edgePixel(rowCenter("Draft"))
        runOnIdle { opaque.value = true }
        assertEquals(before, edgePixel(rowCenter("Draft")))
    }

    private fun ComposeUiTest.rowTop(text: String) = onNodeWithText(text).fetchSemanticsNode().boundsInRoot.top
    private fun ComposeUiTest.rowBottom(text: String) = onNodeWithText(text).fetchSemanticsNode().boundsInRoot.bottom
    private fun ComposeUiTest.rowCenter(text: String) = (rowTop(text) + rowBottom(text)) / 2

    private fun ComposeUiTest.edgePixel(y: Float): Color {
        val root = onNodeWithTag("conversation")
        val top = root.fetchSemanticsNode().boundsInRoot.top
        return root.captureToImage().toPixelMap()[2, (y - top).toInt()]
    }

    private val wallpaper = ImageBitmap(8, 8).also {
        Canvas(it).drawRect(Rect(0f, 0f, 8f, 8f), Paint().apply { color = Color.Magenta })
    }

    @Composable
    private fun Conversation(opaque: Boolean, desktop: Boolean, empty: Boolean, commands: Boolean) {
        CompositionLocalProvider(
            LocalDesktopLayout provides desktop,
            LocalAppSettings provides AppSettings(opaqueComposer = opaque, showPet = false),
        ) {
            Box(Modifier.size(360.dp, 640.dp).testTag("conversation")) {
                ChatView(
                    title = "Conversation",
                    state = ChatState(
                        storedSessionId = "chat",
                        historyLoaded = true,
                        messages = if (empty) emptyList() else listOf(ChatMessage.User("user", "Plan the work")),
                        todos = if (empty) null else TodoList(listOf(TodoItem("1", "Do the work", TodoStatus.Completed)), 1),
                    ),
                    picker = ModelPickerState(), connected = true, attachments = emptyList(), attachmentError = null,
                    voiceChat = VoiceChatState(), dictation = DictationState(), sprite = null,
                    suggestions = if (commands) listOf(SlashSuggestion("/help", "/help")) else emptyList(),
                    placeholder = "Message", notice = null, actions = androidx.compose.runtime.remember { PreviewChatActions("Draft") },
                    onOpenSidebar = {}, onNewChat = null, onOpenMenu = null, onOpenModels = {},
                    onAttach = {}, onDictate = {}, onVoiceChat = {}, onOpenPets = {}, onViewImage = {}, onNotice = {},
                    wallpaper = wallpaper,
                )
            }
        }
    }
}
