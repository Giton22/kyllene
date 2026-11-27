package dev.hermeskotlin.ui.chat

import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import com.composeunstyled.theme.ColorScheme
import dev.hermeskotlin.core.chat.ChatState
import dev.hermeskotlin.core.models.ModelCatalog
import dev.hermeskotlin.core.models.ModelOption
import dev.hermeskotlin.core.models.ModelProvider
import dev.hermeskotlin.core.models.starKey
import dev.hermeskotlin.core.settings.AppSettings
import dev.hermeskotlin.designsystem.HermesTheme
import dev.hermeskotlin.ui.LocalAppSettings
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-rUS-w360dp-h640dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AndroidModelPickerTest {
    private val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val rules: TestRule = RuleChain.outerRule(TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                val app = RuntimeEnvironment.getApplication()
                Shadows.shadowOf(app.packageManager).addActivityIfNotPresent(
                    ComponentName(app.packageName, ComponentActivity::class.java.name),
                )
                base.evaluate()
            }
        }
    }).around(compose)

    @Test
    fun currentModelAndEffortStayVisibleWhenTheListScrolls() {
        val models = listOf(current) + (1..30).map { ModelOption("fixture-model-$it", "openai") }
        val favorite = ModelOption("fixture-favorite", "anthropic")
        openModels(models, listOf(favorite), listOf(favorite.starKey))

        compose.onNodeWithContentDescription("Favorites").assertIsDisplayed()
        compose.onNodeWithText("Fixture Favorite").assertIsDisplayed()
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(26)
        compose.onNodeWithContentDescription("Favorites").assertIsNotDisplayed()
        compose.onNodeWithText("Models").assertIsDisplayed()
        compose.onNodeWithText("GPT-5.4").assertIsDisplayed()
        compose.onNodeWithText("Medium", substring = true).assertIsDisplayed()
    }

    @Test
    fun providerNamesAreShownOnRowsOnlyWhenSearchMatchesAcrossProviders() {
        openModels(
            listOf(current, ModelOption("shared-openai", "openai")),
            listOf(ModelOption("shared-anthropic", "anthropic")),
        )

        compose.onNodeWithText("Shared OpenAI").assertIsDisplayed()
        // The provider heading is the only place that needs the provider name while browsing a group.
        compose.onAllNodesWithText("OpenAI", useUnmergedTree = true).assertCountEquals(1)

        compose.onNode(hasSetTextAction()).performTextInput("shared")
        compose.onNodeWithText("Shared OpenAI").assertIsDisplayed()
        compose.onNodeWithText("Shared Anthropic").assertIsDisplayed()
        // Both result rows carry the provider name, which is also announced by the group headings.
        compose.onNodeWithText("OpenAI", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Anthropic", useUnmergedTree = true).assertIsDisplayed()

        compose.onNode(hasSetTextAction()).performTextReplacement("shared-openai")
        compose.onNodeWithText("Shared OpenAI").assertIsDisplayed()
        compose.onNodeWithText("OpenAI", useUnmergedTree = true).assertDoesNotExist()
    }

    private fun openModels(openAi: List<ModelOption>, anthropic: List<ModelOption>, stars: List<String> = emptyList()) {
        val catalog = ModelCatalog(
            listOf(ModelProvider("openai", "OpenAI", openAi), ModelProvider("anthropic", "Anthropic", anthropic)),
            current.id,
            current.provider,
        )
        compose.setContent {
            HermesTheme(ColorScheme.Dark) {
                CompositionLocalProvider(LocalAppSettings provides AppSettings(starredModels = stars)) {
                    ModelSheet(
                        visible = true, onDismiss = {}, state = ChatState(reasoningEffort = "medium"),
                        picker = ModelPickerState(catalog), onRefresh = {}, onSelectModel = {},
                        onSelectEffort = {}, onFast = {}, onSettings = {}, quickPicker = true,
                    )
                }
            }
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1000)
        compose.onNodeWithContentDescription("Reasoning effort").assertIsDisplayed()
        compose.onNodeWithText("GPT-5.4").performClick()
        compose.waitForIdle()
    }

    private companion object {
        val current = ModelOption("gpt-5.4", "openai", reasoning = true)
    }
}
