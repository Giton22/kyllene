package dev.hermeskotlin.ui.chat

import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import com.composeunstyled.theme.ColorScheme
import dev.hermeskotlin.core.chat.ApprovalChoice
import dev.hermeskotlin.core.chat.ClarifyQuestion
import dev.hermeskotlin.core.chat.InputRequest
import dev.hermeskotlin.designsystem.HermesTheme
import kotlinx.serialization.json.JsonObject
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
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * An approval or a question minimized to a pill, to read the chat behind it, and brought back with what the user had
 * started; a new request while it's a pill opens the card again (#559).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xxhdpi")
class MinimizeRequestCardTest {

    private val composeRule = createAndroidComposeRule<ComponentActivity>()

    // The library ships no manifest declaring an activity, so register one before createComposeRule.
    @get:Rule
    val rules: TestRule = RuleChain.outerRule(RegisterHostActivity()).around(composeRule)

    private val approval = InputRequest.Approval("r1", "make clean", "Clear the build", "terminal", listOf(ApprovalChoice.Once, ApprovalChoice.Deny))

    private val questions = InputRequest.Clarify(
        "r2",
        listOf(
            ClarifyQuestion("q1", "Which branch?", choices = listOf("main", "dev")),
            ClarifyQuestion("q2", "What should the release be called?"),
        ),
    )

    private fun show(requests: List<InputRequest>, answered: MutableList<JsonObject> = mutableListOf()) {
        composeRule.setContent {
            HermesTheme(ColorScheme.Light) {
                InputRequestPanel(requests, connected = true, onAnswer = { _, answer -> answered += answer })
            }
        }
    }

    private fun minimize() = composeRule.onNode(hasContentDescription("Minimize")).performClick()

    /** The pill, by what a tap on it does. */
    private fun pill(): SemanticsNodeInteraction = composeRule.onNode(
        SemanticsMatcher("taps to \"Expand\"") { it.config.getOrNull(SemanticsActions.OnClick)?.label == "Expand" },
    )

    /** Past the moment a card that just showed ignores taps. */
    private fun pastArming() {
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeBy(1_000)
    }

    private fun button(text: String) = composeRule.onNode(hasText(text) and hasClickAction())

    private fun gone(text: String) = assertTrue(composeRule.onAllNodes(hasText(text)).fetchSemanticsNodes().isEmpty(), "\"$text\" still shows")

    @Test
    fun anApprovalMinimizesToAPillAndComesBack() {
        show(listOf(approval))
        minimize()

        pill().assertIsDisplayed()
        composeRule.onNode(hasText("Approval needed")).assertIsDisplayed()
        // Answered only from the card.
        gone("Allow once")
        gone("make clean")

        pill().performClick()

        button("Allow once").assertIsDisplayed()
        composeRule.onNode(hasText("make clean")).assertIsDisplayed()
        assertTrue(composeRule.onAllNodes(hasText("Approval needed")).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun aQuestionKeepsWhatWasPickedAndTypedWhileMinimized() {
        val answered = mutableListOf<JsonObject>()
        show(listOf(questions), answered)
        pastArming()

        button("dev").performClick()
        button("Next").performClick()
        composeRule.onNode(hasSetTextAction()).performTextInput("Spring cleanup")
        minimize()
        composeRule.onNode(hasText("Question waiting")).assertIsDisplayed()
        gone("Spring cleanup")

        pill().performClick()
        pastArming()

        // Back on the question it was on, with the text typed.
        composeRule.onNode(hasText("What should the release be called?")).assertIsDisplayed()
        composeRule.onNode(hasText("Spring cleanup", substring = true)).assertIsDisplayed()
        button("Send").assertIsEnabled().performClick()

        // The first question's pick came through too.
        assertEquals(1, answered.size)
        val sent = answered.single().toString()
        assertTrue("dev" in sent && "Spring cleanup" in sent, sent)
    }

    @Test
    fun aNewRequestWhileMinimizedOpensTheCardAgain() {
        val requests = mutableStateListOf<InputRequest>(approval)
        show(requests)
        minimize()
        pill().assertIsDisplayed()

        composeRule.runOnIdle { requests += questions }

        // The oldest still goes first, now with the new one counted behind it.
        button("Allow once").assertIsDisplayed()
        composeRule.onNode(hasText("+1 more")).assertIsDisplayed()
        assertTrue(composeRule.onAllNodes(hasText("Approval needed")).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun aCardOpenedByANewRequestStaysOpenWhenThatRequestGoes() {
        val requests = mutableStateListOf<InputRequest>(approval)
        show(requests)
        minimize()
        composeRule.runOnIdle { requests += questions }
        button("Allow once").assertIsDisplayed()

        // The new question answered on another client: the card the user was shown stays.
        composeRule.runOnIdle { requests.remove(questions) }

        button("Allow once").assertIsDisplayed()
        gone("Approval needed")
    }

    @Test
    fun oneAnsweredElsewhereWhileMinimizedLeavesThePill() {
        val requests = mutableStateListOf<InputRequest>(approval, questions)
        show(requests)
        minimize()
        composeRule.onNode(hasText("Approval needed")).assertIsDisplayed()

        composeRule.runOnIdle { requests.removeAt(0) }

        composeRule.onNode(hasText("Question waiting")).assertIsDisplayed()
        gone("Which branch?")
    }

    @Test
    fun aPasswordCardShownWhileMinimizedDoesNotFoldBackWhenAnswered() {
        val sudo = InputRequest.Secret("r3", InputRequest.Secret.Kind.Sudo, "Password", envVar = null, command = "apt update")
        val requests = mutableStateListOf<InputRequest>(approval, sudo, questions)
        show(requests)
        minimize()
        pill().assertIsDisplayed()

        // The approval answered on another client: the password card shows, as it can't be a pill.
        composeRule.runOnIdle { requests.remove(approval) }
        gone("Approval needed")
        gone("Question waiting")

        // Once the password is sent, the question the user was shown a card for shows as a card too.
        composeRule.runOnIdle { requests.remove(sudo) }
        composeRule.onNode(hasText("Which branch?")).assertIsDisplayed()
        gone("Question waiting")
    }

    @Test
    fun aMinimizedCardStaysAPillAcrossARotation() {
        val restoration = StateRestorationTester(composeRule)
        restoration.setContent {
            HermesTheme(ColorScheme.Light) { InputRequestPanel(listOf(approval), connected = true, onAnswer = { _, _ -> }) }
        }
        minimize()
        pill().assertIsDisplayed()

        restoration.emulateSavedInstanceStateRestore()

        pill().assertIsDisplayed()
        gone("Allow once")
    }

    @Test
    fun aQuestionBroughtBackFromThePillIgnoresATapForAMoment() {
        val answered = mutableListOf<JsonObject>()
        show(listOf(questions), answered)
        pastArming()
        minimize()
        pill().assertIsDisplayed()

        // A double tap: the first opens the card where the pill was, the second lands on Skip.
        composeRule.mainClock.autoAdvance = false
        pill().performSemanticsAction(SemanticsActions.OnClick)
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeBy(200)
        button("Skip").assertIsNotEnabled().performClick()
        composeRule.mainClock.advanceTimeBy(100)
        assertTrue(answered.isEmpty(), "the second tap answered the question: $answered")

        composeRule.mainClock.advanceTimeBy(1_000)
        button("Skip").assertIsEnabled().performClick()
        composeRule.mainClock.advanceTimeBy(100)
        assertEquals(1, answered.size)
    }

    @Test
    fun aPasswordCardHasNoMinimize() {
        show(listOf(InputRequest.Secret("r3", InputRequest.Secret.Kind.Sudo, "Password", envVar = null, command = "apt update")))
        composeRule.waitForIdle()
        assertTrue(composeRule.onAllNodes(hasContentDescription("Minimize")).fetchSemanticsNodes().isEmpty())
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
