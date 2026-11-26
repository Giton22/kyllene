package dev.hermeskotlin.ui.chat

import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.hermeskotlin.designsystem.LocalDesignWords
import dev.hermeskotlin.ui.components.rememberDesignWords
import com.composeunstyled.theme.ColorScheme
import dev.hermeskotlin.core.chat.ChatMessage
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
import kotlin.test.assertTrue

/**
 * The row under a finished reply on a narrow screen at the largest text: the app's Largest (1.3) on Android's
 * font scale 2.0. The token count used to take the row and leave Copy no width, and its end trim then laid it
 * out at a negative width, which crashed the chat.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w320dp-h700dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReplyActionsFitTest {

    private val composeRule = createAndroidComposeRule<ComponentActivity>()

    // The library ships no manifest declaring an activity, so register one before createComposeRule.
    @get:Rule
    val rules: TestRule = RuleChain.outerRule(RegisterHostActivity()).around(composeRule)

    @Test
    fun theActionsKeepTheirWidthBesideALongCount() = assertActionsFit("Copy", "More")

    @Test
    @Config(qualifiers = "de-w320dp-h700dp-xxhdpi")
    fun germanActionsKeepTheirWidthBesideALongCount() = assertActionsFit("Kopieren", "Mehr")

    @Test
    @Config(qualifiers = "hu-w320dp-h700dp-xxhdpi")
    fun hungarianActionsKeepTheirWidthBesideALongCount() = assertActionsFit("Másolás", "Továbbiak")

    private fun assertActionsFit(copy: String, more: String) {
        composeRule.setContent {
            HermesTheme(ColorScheme.Light) {
                val density = LocalDensity.current
                CompositionLocalProvider(
                    LocalDensity provides Density(density.density, 2.6f),
                    // The app's words, so "Copied", which the labelled Copy makes room for, is in this language too.
                    LocalDesignWords provides rememberDesignWords(),
                    // The count is what squeezed the actions, so it's shown whatever the default.
                    LocalAppSettings provides AppSettings(showUsage = true),
                ) {
                    Box(Modifier.width(300.dp)) {
                        AssistantReply(
                            ChatMessage.Assistant(
                                key = "reply",
                                text = "Done.",
                                usage = TurnUsage(input = 184_000, output = 61_200, reasoning = 0, calls = 1),
                            ),
                            onBranch = {},
                            onRegenerate = {},
                            last = true,
                            onReadAloud = {},
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()
        val touch = with(composeRule.density) { 48.dp.toPx() }
        for (action in listOf(copy, more)) {
            val node = composeRule.onNodeWithContentDescription(action).fetchSemanticsNode()
            assertTrue(node.boundsInRoot.width >= touch - 1, "$action is ${node.boundsInRoot.width}px wide, expected the full ${touch}px touch target")
        }
        val usage = composeRule.onNodeWithText("184k ↓ · 61.2k ↑").fetchSemanticsNode()
        assertTrue(usage.boundsInRoot.width >= touch, "The token count should have room beside the actions")
    }

    @Test
    fun moreMenuUsesPhoneSizedRows() {
        composeRule.setContent {
            HermesTheme(ColorScheme.Light) {
                AssistantReply(
                    ChatMessage.Assistant(key = "reply", text = "Done."),
                    onBranch = {},
                    onRegenerate = {},
                    last = true,
                )
            }
        }

        composeRule.onNodeWithContentDescription("More").performClick()
        val touch = with(composeRule.density) { 48.dp.toPx() }
        for (action in listOf("Branch from here", "Regenerate")) {
            val bounds = composeRule.onNodeWithText(action).fetchSemanticsNode().boundsInRoot
            assertTrue(bounds.height >= touch - 1, "$action should have a full-height touch row")
        }
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
