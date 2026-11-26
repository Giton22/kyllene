package dev.hermeskotlin.ui.chat

import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import com.composeunstyled.theme.ColorScheme
import com.composeunstyled.theme.Theme
import dev.hermeskotlin.core.chat.ChatMessage
import dev.hermeskotlin.core.chat.ChatState
import dev.hermeskotlin.core.chat.TodoItem
import dev.hermeskotlin.core.chat.TodoList
import dev.hermeskotlin.core.chat.TodoStatus
import dev.hermeskotlin.core.settings.AppSettings
import dev.hermeskotlin.designsystem.HermesTheme
import dev.hermeskotlin.designsystem.background
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.ui.LocalAppSettings
import dev.hermeskotlin.ui.preview.PreviewChatActions
import dev.hermeskotlin.ui.voice.DictationState
import dev.hermeskotlin.ui.voice.VoiceChatState
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The phone's dock with Opaque composer on, and the blur around the transcript's controls over a wallpaper. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h720dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OpaqueDockBackingTest {
    private val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val rules: TestRule = RuleChain.outerRule(RegisterHostActivity()).around(compose)

    @Test
    fun opaqueBackingLeavesNoGapAroundTheReconnectBannerOrTheEditingBanner() {
        var page = Color.Unspecified
        conversation(opaque = true, offline = true, editing = true) { page = it }

        val banner = bounds("Retry now")
        val plan = bounds("Last plan 1/1")
        val edit = bounds(EDITING, substring = true)
        val draft = bounds("Draft in progress")
        assertTrue(banner.bottom <= plan.top && plan.bottom <= edit.top && edit.bottom <= draft.top)
        val shot = shot()
        // Beside the banner, between it and the plan, and above and below the editing banner.
        assertEquals(page, shot.edge((banner.top + banner.bottom) / 2))
        assertEquals(page, shot.edge((banner.bottom + plan.top) / 2))
        assertEquals(page, shot.edge((plan.bottom + edit.top) / 2))
        assertEquals(page, shot.edge((edit.bottom + draft.top) / 2))
    }

    @Test
    fun jumpButtonBlursTheWallpaperAroundItInsteadOfCoveringItWithThePage() {
        var page = Color.Unspecified
        conversation(opaque = false, offline = false, editing = false) { page = it }
        compose.onNode(hasScrollToIndexAction()).performTouchInput { swipeDown(durationMillis = 550) }
        compose.waitForIdle()

        val jump = compose.onNodeWithContentDescription("Jump to latest", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val shot = shot()
        val y = ((jump.top + jump.bottom) / 2).toInt()
        // The arrow sits 11 dp inside the button. Just outside the button, inside its blurred rim, the wallpaper
        // shows as it does further out.
        val rim = shot[(jump.left - 15 * DENSITY).toInt(), y]
        val wall = shot[(jump.left - 60 * DENSITY).toInt(), y]
        assertTrue(!wall.near(page), "the wallpaper $wall should show beside the button")
        assertTrue(rim.near(wall),"rim $rim should be close to the wallpaper $wall, not the page $page")
    }

    @Test
    fun theDockKeepsItsHeightWhenTheBackingComesOrGoesUnderTheBanner() {
        val opaque = mutableStateOf(false)
        conversation(opaque = opaque, offline = true, editing = false) {}
        val off = bounds("Retry now")
        val offPlan = bounds("Last plan 1/1")
        compose.runOnIdle { opaque.value = true }
        compose.waitForIdle()
        assertEquals(off, bounds("Retry now"))
        assertEquals(offPlan, bounds("Last plan 1/1"))
    }

    private fun conversation(opaque: Boolean, offline: Boolean, editing: Boolean, page: (Color) -> Unit) =
        conversation(mutableStateOf(opaque), offline, editing, page)

    private fun conversation(opaque: State<Boolean>, offline: Boolean, editing: Boolean, page: (Color) -> Unit) {
        compose.setContent {
            HermesTheme(ColorScheme.Dark) {
                page(Theme[colors][background])
                CompositionLocalProvider(LocalAppSettings provides AppSettings(opaqueComposer = opaque.value, showPet = false)) {
                    Box(Modifier.fillMaxSize().testTag("conversation")) {
                        ChatView(
                            title = "Conversation",
                            state = ChatState(
                                historyLoaded = true,
                                messages = (1..40).map { ChatMessage.Assistant("reply-$it", text = "Reply $it", timestamp = it.toDouble()) },
                                todos = TodoList(listOf(TodoItem("1", "Check layout", TodoStatus.Completed)), revision = 1),
                            ),
                            picker = ModelPickerState(),
                            connected = !offline,
                            linkStatus = if (offline) "Reconnecting…" else null,
                            editing = if (editing) "reply-40" else null,
                            attachments = emptyList(),
                            attachmentError = null,
                            voiceChat = VoiceChatState(),
                            dictation = DictationState(),
                            suggestions = emptyList(),
                            sprite = null,
                            placeholder = "Message",
                            notice = null,
                            actions = PreviewChatActions("Draft in progress"),
                            readUpTo = 40.0,
                            onOpenSidebar = {},
                            onNewChat = null,
                            onOpenMenu = null,
                            onOpenModels = {},
                            onAttach = {},
                            onDictate = {},
                            onVoiceChat = {},
                            onOpenPets = {},
                            onViewImage = {},
                            onNotice = {},
                            wallpaper = wallpaper,
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun bounds(text: String, substring: Boolean = false) =
        compose.onNodeWithText(text, substring = substring, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    private fun shot(): PixelMap = compose.onNodeWithTag("conversation").captureToImage().toPixelMap()

    /** A pixel 2 dp in from the left edge, outside every panel's side padding. */
    private fun PixelMap.edge(y: Float): Color = this[(2 * DENSITY).toInt(), y.toInt()]

    private fun Color.near(other: Color) =
        abs(red - other.red) < 0.06f && abs(green - other.green) < 0.06f && abs(blue - other.blue) < 0.06f

    private val wallpaper = ImageBitmap(8, 8).also {
        Canvas(it).drawRect(Rect(0f, 0f, 8f, 8f), Paint().apply { color = Color.Magenta })
    }

    private class RegisterHostActivity : TestRule {
        override fun apply(base: Statement, description: Description): Statement = object : Statement() {
            override fun evaluate() {
                val app = RuntimeEnvironment.getApplication()
                Shadows.shadowOf(app.packageManager).addActivityIfNotPresent(ComponentName(app.packageName, ComponentActivity::class.java.name))
                base.evaluate()
            }
        }
    }

    private companion object {
        const val EDITING = "Editing a message"
        const val DENSITY = 3f
    }
}
