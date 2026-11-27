package dev.hermeskotlin.ui.settings

import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import com.composeunstyled.theme.ColorScheme
import dev.hermeskotlin.core.auth.AuthApi
import dev.hermeskotlin.core.auth.PersistentCookiesStorage
import dev.hermeskotlin.core.connection.GatewayConnection
import dev.hermeskotlin.core.gateway.ConnectionCheck
import dev.hermeskotlin.core.gateway.GatewayProbe
import dev.hermeskotlin.core.gateway.GatewayRepository
import dev.hermeskotlin.core.gateway.SavedGateway
import dev.hermeskotlin.core.network.createHttpClient
import dev.hermeskotlin.core.plugins.NextPromptApi
import dev.hermeskotlin.core.plugins.PluginsApi
import dev.hermeskotlin.core.profiles.ProfilesApi
import dev.hermeskotlin.core.push.PushApi
import dev.hermeskotlin.core.push.PushSetup
import dev.hermeskotlin.core.sessions.SessionsApi
import dev.hermeskotlin.core.settings.CallTarget
import dev.hermeskotlin.core.settings.InMemoryBlobFile
import dev.hermeskotlin.core.settings.SettingsStore
import dev.hermeskotlin.core.settings.WallpaperStore
import dev.hermeskotlin.core.storage.InMemoryKeyValueStore
import dev.hermeskotlin.designsystem.HermesTheme
import dev.hermeskotlin.ui.AppViewModelTest.NoPushKeys
import dev.hermeskotlin.ui.bots.BotFaces
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.coroutines.cancel
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-rUS-w360dp-h800dp-xxhdpi")
class CallTargetPickerTest {
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

    @Test
    fun theOnlyDefaultProfileCanBePickedAsABot() = withPicker("""[{"name":"default","is_default":true}]""") { selected ->
        compose.onNodeWithText("Hermes").performClick()
        compose.runOnIdle { assertEquals(CallTarget(profile = "default", label = "Hermes"), selected()) }
    }

    @Test
    fun theDefaultProfileIsListedOnceBesideNamedBots() = withPicker(
        """[{"name":"work","display_name":"Work Bot"},{"name":"default","is_default":true}]""",
    ) { selected ->
        assertEquals(1, compose.onAllNodes(hasText("Hermes")).fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodes(hasText("default")).fetchSemanticsNodes().size)
        compose.onNodeWithText("Work Bot").performClick()
        compose.runOnIdle { assertEquals(CallTarget(profile = "work", label = "Work Bot"), selected()) }
        compose.onNodeWithText("Hermes").performClick()
        compose.runOnIdle { assertEquals(CallTarget(profile = "default", label = "Hermes"), selected()) }
    }

    /** Opens Call a bot on a gateway whose `GET /api/profiles` lists [profiles], then runs [check]. */
    private fun withPicker(profiles: String, check: (selected: () -> CallTarget?) -> Unit) {
        val disk = InMemoryKeyValueStore()
        val scope = compose.activity.lifecycleScope
        val client = createHttpClient(MockEngine { request ->
            val body = when (request.url.encodedPath) {
                "/api/profiles/active" -> """{"current":"default"}"""
                "/api/profiles" -> """{"profiles":$profiles}"""
                else -> error("Unexpected request: ${request.url}")
            }
            respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val store = SettingsStore(disk, scope)
        val auth = AuthApi(client, PersistentCookiesStorage(disk))
        val probe = GatewayProbe(client)
        val connection = GatewayConnection(auth, { _, _ -> error("Unexpected socket") }, scope)
        val push = PushSetup(PushApi(connection), NoPushKeys, connection, GatewayRepository(disk), store, scope) { "Test" }
        val viewModel = SettingsViewModel(
            store, auth, probe, ConnectionCheck(probe, auth, { _, _ -> error("Unexpected socket") }),
            push, WallpaperStore(InMemoryBlobFile(), scope), ProfilesApi(client), SessionsApi(client),
            NextPromptApi(client, PluginsApi(client)),
        )
        var selected: CallTarget? = null
        try {
            compose.setContent {
                HermesTheme(ColorScheme.Light) {
                    CallTargetPicker(SavedGateway("https://fixture.example"), viewModel, BotFaces(), {}, { selected = it })
                }
            }
            compose.onNodeWithText("Call a bot").performClick()
            // Not "No bots available.": the picker shows it for a frame before the profiles request starts.
            compose.waitUntil(5_000) { compose.onAllNodes(hasText("Hermes")).fetchSemanticsNodes().isNotEmpty() }
            check { selected }
        } finally {
            viewModel.viewModelScope.cancel()
            client.close()
        }
    }
}
