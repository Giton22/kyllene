package dev.hermeskotlin.ui.gateways

import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import dev.hermeskotlin.core.gateway.GatewayRepository
import dev.hermeskotlin.core.gateway.SavedGateway
import dev.hermeskotlin.core.storage.InMemoryKeyValueStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/** The sidebar header, Settings and Insights name the gateway through [savedLabel], as the chat does. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GatewayLabelTest {
    private val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val rules: TestRule = RuleChain.outerRule(TestRule { base, _ ->
        object : org.junit.runners.model.Statement() {
            override fun evaluate() {
                val app = RuntimeEnvironment.getApplication()
                Shadows.shadowOf(app.packageManager).addActivityIfNotPresent(
                    ComponentName(app.packageName, ComponentActivity::class.java.name),
                )
                base.evaluate()
            }
        }
    }).around(compose)

    private val gateways = GatewayRepository(InMemoryKeyValueStore())

    @After
    fun tearDown() = stopKoin()

    @Test
    fun aRenameShowsWithoutAFreshCopyOfTheGateway() {
        // The copy a screen holds, taken before the rename.
        val held = SavedGateway("https://fixture.example")
        runBlocking { gateways.save(held) }
        show(held)
        compose.onNodeWithText("fixture.example").assertExists()

        runBlocking { gateways.rename(held.url, "Homelab") }
        compose.onNodeWithText("Homelab").assertExists()

        runBlocking { gateways.rename(held.url, "") }
        compose.onNodeWithText("fixture.example").assertExists()
    }

    @Test
    fun aGatewayNoLongerSavedKeepsItsOwnLabel() {
        val held = SavedGateway("https://gone.example", name = "Old name")
        runBlocking {
            gateways.save(SavedGateway("https://other.example"))
            gateways.save(held)
        }
        show(held)
        runBlocking { gateways.remove(held.url) }
        compose.onNodeWithText("Old name").assertExists()
    }

    private fun show(gateway: SavedGateway) {
        startKoin { modules(module { single { gateways } }) }
        compose.setContent { BasicText(gateway.savedLabel()) }
    }
}
