package dev.hermeskotlin.ui.sessions

import dev.hermeskotlin.core.capabilities.CapabilitiesApi
import dev.hermeskotlin.core.gateway.SavedGateway
import dev.hermeskotlin.core.network.createHttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class CapabilitiesViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val gateway = SavedGateway("https://hermes.example.ts.net")
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    @Test
    fun aLateAnswerForThePreviousProfileIsDropped() = runTest(dispatcher) {
        val releaseOld = CompletableDeferred<Unit>()
        val oldAsked = CompletableDeferred<Unit>()
        val oldAnswered = CompletableDeferred<Unit>()
        // On the test dispatcher, so the late answer arrives in order and nothing is left on another thread.
        val config = MockEngineConfig()
        config.dispatcher = dispatcher
        config.addHandler { request ->
            val profile = request.url.parameters["profile"]
            if (profile == "old") {
                oldAsked.complete(Unit)
                // Finishes either way: released, or cancelled with the binding.
                try { releaseOld.await() } finally { oldAnswered.complete(Unit) }
            }
            respond("""[{"name":"$profile-skill"}]""", HttpStatusCode.OK, json)
        }
        val api = CapabilitiesApi(createHttpClient(MockEngine(config)))
        val vm = CapabilitiesViewModel(api)

        vm.bind(gateway, "old")
        oldAsked.await()
        vm.bind(gateway, "new")
        vm.state.first { it.skills.items != null }
        releaseOld.complete(Unit)
        oldAnswered.await()

        assertEquals(listOf("new-skill"), vm.state.value.skills.items?.map { it.name })
    }

    @Test
    fun failedRefreshPreservesAnOptimisticToggleMadeInFlight() = runTest(dispatcher) {
        val refreshGate = CompletableDeferred<Unit>()
        var calls = 0
        val config = MockEngineConfig()
        config.dispatcher = dispatcher
        config.addHandler { request ->
            when {
                request.url.encodedPath.endsWith("/toggle") -> respond("""{"ok":true}""", HttpStatusCode.OK, json)
                else -> {
                    if (calls++ == 0) {
                        respond("""[{"name":"alpha","enabled":true}]""", HttpStatusCode.OK, json)
                    } else {
                        refreshGate.await()
                        respond("""{"detail":"Gateway down"}""", HttpStatusCode.ServiceUnavailable, json)
                    }
                }
            }
        }
        val vm = CapabilitiesViewModel(CapabilitiesApi(createHttpClient(MockEngine(config))))
        vm.bind(gateway, null)
        val skill = vm.state.first { it.skills.items != null }.skills.items!!.single()
        assertTrue(skill.enabled)

        // Refresh starts while enabled=true, then user toggles it off before refresh fails.
        vm.refresh()
        vm.setSkillEnabled(skill, enabled = false)
        assertFalse(vm.state.value.skills.items!!.single().enabled)

        refreshGate.complete(Unit)
        val after = vm.state.first { !it.skills.loading }
        assertNotNull(after.skills.error)
        assertFalse(after.skills.items!!.single().enabled)
    }
}
