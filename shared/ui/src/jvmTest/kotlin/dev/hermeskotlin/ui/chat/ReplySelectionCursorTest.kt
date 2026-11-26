package dev.hermeskotlin.ui.chat

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.platform.FrameRecomposer
import androidx.compose.ui.platform.PlatformContext
import androidx.compose.ui.scene.CanvasLayersComposeScene
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.unit.IntSize
import com.composeunstyled.theme.ColorScheme
import dev.hermeskotlin.core.chat.ChatMessage
import dev.hermeskotlin.designsystem.HermesTheme
import dev.hermeskotlin.designsystem.LocalDesktopLayout
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
class ReplySelectionCursorTest {
    @Test
    fun theTextCursorStaysVisibleWhileDraggingAcrossParagraphs() {
        val first = "A paragraph to start selecting from."
        val second = "Another paragraph to drag into."
        val platform = CursorPlatform()
        val recomposer = FrameRecomposer(Dispatchers.Unconfined)
        val scene = CanvasLayersComposeScene(recomposer, size = IntSize(420, 240), platformContext = platform)
        try {
            scene.setContent {
                HermesTheme(ColorScheme.Light) {
                    CompositionLocalProvider(LocalDesktopLayout provides true) {
                        AssistantReply(ChatMessage.Assistant("reply", text = "$first\n\n$second"), null, null, last = true)
                    }
                }
            }
            recomposer.performFrame(0)
            scene.measureAndLayout()

            val start = platform.textBounds(first)
            val end = platform.textBounds(second)
            val startPoint = Offset(start.left + 10, start.center.y)
            val endPoint = Offset(end.left + 60, end.center.y)
            val gap = Offset(startPoint.x, (start.bottom + end.top) / 2)
            for ((from, to) in listOf(startPoint to endPoint, endPoint to startPoint)) {
                scene.sendPointerEvent(PointerEventType.Move, from)
                assertEquals(PointerIcon.Text, platform.cursor, "Hovering over the origin paragraph")
                scene.sendPointerEvent(PointerEventType.Press, from, button = PointerButton.Primary)
                scene.sendPointerEvent(PointerEventType.Move, gap)
                assertEquals(PointerIcon.Text, platform.cursor, "Dragging through the paragraph gap")
                scene.sendPointerEvent(PointerEventType.Move, to)
                assertEquals(PointerIcon.Text, platform.cursor, "Dragging into the other paragraph")
                scene.sendPointerEvent(PointerEventType.Release, to, button = PointerButton.Primary)
            }
        } finally {
            scene.close()
            recomposer.close()
        }
    }

    @Test
    fun aCodeBlocksScrollbarKeepsTheArrow() {
        val code = "val line = \"" + "a".repeat(200) + "\""
        val short = "val fits = 1"
        val platform = CursorPlatform()
        val recomposer = FrameRecomposer(Dispatchers.Unconfined)
        val scene = CanvasLayersComposeScene(recomposer, size = IntSize(420, 360), platformContext = platform)
        try {
            scene.setContent {
                HermesTheme(ColorScheme.Light) {
                    CompositionLocalProvider(LocalDesktopLayout provides true) {
                        val reply = "```kotlin\n$code\n```\n\n```kotlin\n$short\n```"
                        AssistantReply(ChatMessage.Assistant("reply", text = reply), null, null, last = true)
                    }
                }
            }
            recomposer.performFrame(0)
            scene.measureAndLayout()
            // A second frame, for the bars to learn from the first layout whether there's anything to scroll.
            recomposer.performFrame(1)
            scene.measureAndLayout()

            val lines = platform.textBounds(code)
            scene.sendPointerEvent(PointerEventType.Move, Offset(lines.left + 10, lines.center.y))
            assertEquals(PointerIcon.Text, platform.cursor, "Hovering over the code")
            // The scrollbar sits in the block's bottom padding, under the long line.
            scene.sendPointerEvent(PointerEventType.Move, Offset(lines.left + 10, lines.bottom + 6))
            assertEquals(PointerIcon.Default, platform.cursor, "Hovering over the code's scrollbar")
            // A block that fits draws no bar, so its padding keeps the reply's text cursor.
            val fits = platform.textBounds(short)
            scene.sendPointerEvent(PointerEventType.Move, Offset(fits.left + 10, fits.bottom + 6))
            assertEquals(PointerIcon.Text, platform.cursor, "Hovering under code that fits")
        } finally {
            scene.close()
            recomposer.close()
        }
    }

    private class CursorPlatform : PlatformContext.Empty(), PlatformContext.SemanticsOwnerListener {
        var cursor = PointerIcon.Default
            private set
        private lateinit var owner: SemanticsOwner

        override val semanticsOwnerListener get() = this
        override fun onSemanticsOwnerAppended(semanticsOwner: SemanticsOwner) {
            owner = semanticsOwner
        }

        override fun onSemanticsOwnerRemoved(semanticsOwner: SemanticsOwner) = Unit
        override fun onSemanticsChange(semanticsOwner: SemanticsOwner) = Unit
        override fun onLayoutChange(semanticsOwner: SemanticsOwner, semanticsNodeId: Int) = Unit
        override fun setPointerIcon(pointerIcon: PointerIcon) {
            cursor = pointerIcon
        }

        fun textBounds(text: String) = owner.getAllSemanticsNodes(mergingEnabled = false)
            .single { node -> node.config.getOrElse(SemanticsProperties.Text) { emptyList() }.any { it.text == text } }
            .boundsInRoot
    }
}
