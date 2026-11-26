package dev.hermeskotlin.ui.chat

import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.composeunstyled.theme.ColorScheme
import dev.hermeskotlin.core.chat.ChatMessage
import dev.hermeskotlin.core.chat.ReplyReaction
import dev.hermeskotlin.core.chat.TurnUsage
import dev.hermeskotlin.core.settings.AppSettings
import dev.hermeskotlin.designsystem.HermesTheme
import dev.hermeskotlin.ui.LocalAppSettings
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
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The four reactions under a reply on a narrow phone: inline, at the full touch size, with no menu to open. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w320dp-h700dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReplyReactionsPhoneTest {

    private val composeRule = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val rules: TestRule = RuleChain.outerRule(RegisterHostActivity()).around(composeRule)

    private val labels = listOf("Got it", "Continue", "Verify", "Explain")

    @Test
    fun newestReplyShowsTheFourReactionsInlineAboveItsTools() {
        val selected = mutableStateOf<ReplyReaction?>(null)
        composeRule.setContent {
            HermesTheme(ColorScheme.Light) {
                val density = LocalDensity.current
                // The largest text, with the count shown: the row under the reply at its most crowded.
                CompositionLocalProvider(
                    LocalDensity provides Density(density.density, 2.6f),
                    LocalAppSettings provides AppSettings(showUsage = true),
                ) {
                    Box(Modifier.width(288.dp)) {
                        AssistantReply(
                            ChatMessage.Assistant(
                                key = "reply", text = "Done.", reactionRowId = 42, userReaction = selected.value?.emoji,
                                usage = TurnUsage(input = 184_000, output = 61_200, reasoning = 0, calls = 1),
                            ),
                            onBranch = {},
                            onRegenerate = {},
                            last = true,
                            onReadAloud = {},
                            onReact = { selected.value = it.takeUnless { selected.value == it } },
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()
        val touch = with(composeRule.density) { 48.dp.toPx() }
        val width = with(composeRule.density) { 288.dp.toPx() }
        composeRule.onNodeWithContentDescription("More").assertDoesNotExist()
        val copy = composeRule.onNodeWithContentDescription("Copy").fetchSemanticsNode().boundsInRoot
        for (label in labels) {
            val node = composeRule.onNodeWithContentDescription(label).assertIsDisplayed().assertHasClickAction().assertIsNotSelected()
            val bounds = node.fetchSemanticsNode().boundsInRoot
            assertTrue(bounds.width >= touch - 1 && bounds.height >= touch - 1, "$label is ${bounds.width}x${bounds.height}px")
            assertTrue(bounds.right <= width, "$label runs past the reply")
            assertTrue(bounds.bottom <= copy.top + 1f, "$label should sit above the reply's tools")
        }
        // Copy keeps its full size under them.
        assertTrue(copy.width >= touch - 1)

        composeRule.onNodeWithContentDescription("Verify").performClick()
        composeRule.runOnIdle { assertEquals(ReplyReaction.Verify, selected.value) }
        composeRule.onNodeWithContentDescription("Verify").assertIsSelected()
        composeRule.onNodeWithContentDescription("Got it").assertIsNotSelected()
        composeRule.onNodeWithContentDescription("Verify").performClick()
        composeRule.runOnIdle { assertEquals(null, selected.value) }
    }

    @Test
    fun anOlderReplyKeepsOnlyItsChosenReaction() {
        val reaction = mutableStateOf<String?>(ReplyReaction.Explain.emoji)
        composeRule.setContent {
            HermesTheme(ColorScheme.Dark) {
                AssistantReply(
                    ChatMessage.Assistant(key = "reply", text = "An earlier reply.", reactionRowId = 7, userReaction = reaction.value),
                    onBranch = {},
                    onRegenerate = {},
                    last = false,
                    onReact = { reaction.value = null },
                )
            }
        }
        composeRule.onNodeWithContentDescription("Explain").assertIsDisplayed().assertIsSelected()
        for (label in labels - "Explain") composeRule.onNodeWithContentDescription(label).assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Explain").performClick()
        composeRule.runOnIdle { assertEquals(null, reaction.value) }
        composeRule.onNodeWithContentDescription("Explain").assertDoesNotExist()
    }

    @Test
    fun anOlderReplyShowsAReactionSetOnAnotherClient() {
        composeRule.setContent {
            HermesTheme(ColorScheme.Light) {
                AssistantReply(
                    ChatMessage.Assistant(key = "reply", text = "Earlier.", reactionRowId = 5, userReaction = "❤️"),
                    onBranch = {},
                    onRegenerate = {},
                    last = false,
                    onReact = {},
                )
            }
        }
        composeRule.onNodeWithContentDescription("❤️").assertIsDisplayed().assertIsSelected()
        for (label in labels) composeRule.onNodeWithContentDescription(label).assertDoesNotExist()
    }

    @Test
    fun aReplyWithoutAStoredRowOffersNone() {
        composeRule.setContent {
            HermesTheme(ColorScheme.Light) {
                AssistantReply(
                    ChatMessage.Assistant(key = "reply", text = "Not saved yet."),
                    onBranch = {},
                    onRegenerate = {},
                    last = true,
                    onReact = { error("nothing to react to") },
                )
            }
        }
        composeRule.onNodeWithContentDescription("Copy").assertIsDisplayed()
        for (label in labels) composeRule.onNodeWithContentDescription(label).assertDoesNotExist()
    }

    /** Registers the Compose host activity Robolectric would otherwise fail to resolve. */
    private class RegisterHostActivity : TestRule {
        override fun apply(base: Statement, description: Description): Statement = object : Statement() {
            override fun evaluate() {
                val app = RuntimeEnvironment.getApplication()
                Shadows.shadowOf(app.packageManager).addActivityIfNotPresent(
                    ComponentName(app.packageName, ComponentActivity::class.java.name),
                )
                base.evaluate()
            }
        }
    }
}
