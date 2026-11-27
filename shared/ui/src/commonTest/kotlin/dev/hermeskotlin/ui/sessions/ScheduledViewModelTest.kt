package dev.hermeskotlin.ui.sessions

import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import dev.hermeskotlin.core.auth.AuthApi
import dev.hermeskotlin.core.auth.PersistentCookiesStorage
import dev.hermeskotlin.core.connection.GatewayConnection
import dev.hermeskotlin.core.cron.CronApi
import dev.hermeskotlin.core.gateway.SavedGateway
import dev.hermeskotlin.core.models.ModelCatalog
import dev.hermeskotlin.core.models.ModelOption
import dev.hermeskotlin.core.models.ModelProvider
import dev.hermeskotlin.core.models.ModelsApi
import dev.hermeskotlin.core.network.createHttpClient
import dev.hermeskotlin.core.storage.InMemoryKeyValueStore
import dev.hermeskotlin.core.chat.ChatState
import dev.hermeskotlin.ui.chat.ModelPickerState
import dev.hermeskotlin.ui.chat.ModelSelection
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
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
import kotlin.test.assertNull
import kotlin.test.assertIs
import kotlin.test.assertTrue
import dev.hermeskotlin.ui.resources.*

@OptIn(ExperimentalCoroutinesApi::class)
class ScheduledViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val gateway = SavedGateway("https://hermes.example.ts.net")
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private var jobsBody = """[{"id":"a1","name":"Briefing","prompt":"Brief me","schedule":{"kind":"cron","expr":"0 9 * * *"}}]"""
    private val created = mutableListOf<String>()
    /** Holds a create until completed, so a test can act while the save is in flight. */
    private var createGate: CompletableDeferred<Unit>? = null
    private var createStatus = HttpStatusCode.OK
    private var createdQuery: String? = null
    private var createdBody: String? = null
    private var updatedBody: String? = null

    private fun viewModel(): ScheduledViewModel {
        // On the test dispatcher, so no request is still finishing on another thread when a test ends.
        val config = MockEngineConfig()
        config.dispatcher = dispatcher
        config.addHandler { request ->
            when {
                request.method == HttpMethod.Post && request.url.encodedPath == "/api/cron/jobs" -> {
                    createGate?.await()
                    created += request.url.encodedPath
                    createdQuery = request.url.encodedQuery
                    createdBody = request.body.toByteArray().decodeToString()
                    if (createStatus == HttpStatusCode.OK) {
                        respond("""{"id":"n1","name":"New","prompt":"Do it","schedule":{"kind":"cron","expr":"0 9 * * *"}}""", createStatus, json)
                    } else {
                        respond("""{"detail":"Gateway is busy"}""", createStatus, json)
                    }
                }
                request.method == HttpMethod.Put -> {
                    updatedBody = request.body.toByteArray().decodeToString()
                    respond("""{"id":"a1","name":"Briefing","prompt":"Brief me","schedule":{"kind":"cron","expr":"0 9 * * *"}}""", HttpStatusCode.OK, json)
                }
                request.url.encodedPath == "/api/cron/jobs" -> respond(jobsBody, HttpStatusCode.OK, json)
                request.url.encodedPath.endsWith("/runs") -> respond("""{"runs":[]}""", HttpStatusCode.OK, json)
                else -> respond("""{"targets":[]}""", HttpStatusCode.OK, json)
            }
        }
        val cookies = PersistentCookiesStorage(InMemoryKeyValueStore())
        val client = createHttpClient(MockEngine(config), cookies)
        val connection = GatewayConnection(AuthApi(client, cookies), { _, _ -> error("not connecting in tests") }, CoroutineScope(dispatcher))
        return ScheduledViewModel(CronApi(client), connection, ModelsApi(connection))
    }

    private suspend fun ScheduledViewModel.awaitLoaded() = state.first { !it.loading }

    private fun ScheduledViewModel.fillNewJob() {
        newJob()
        prompt.setTextAndPlaceCursorAtEnd("Do it")
        schedule.setTextAndPlaceCursorAtEnd("0 9 * * *")
    }

    private val routinesBody = """[
        {"id":"r1","name":"[bot:scribe] Morning brief","prompt":"Brief me","profile":"scribe","schedule":{"kind":"cron","expr":"0 9 * * *"}},
        {"id":"r2","name":"[bot:scribe] Old style","prompt":"x","profile":"default","schedule":{"kind":"cron","expr":"0 9 * * *"}},
        {"id":"r3","name":"Somebody else's","prompt":"y","profile":"researcher","schedule":{"kind":"cron","expr":"0 9 * * *"}},
        {"id":"a1","name":"Briefing","prompt":"Brief me","profile":"default","schedule":{"kind":"cron","expr":"0 9 * * *"}}
    ]"""

    @Test
    fun aBotsPageListsOnlyItsRoutines() = runTest(dispatcher) {
        jobsBody = routinesBody
        val vm = viewModel()
        vm.bind(gateway, RoutineOwner("scribe", "Scribe"))

        // Its own store's, and one an older Desktop kept in the launch profile's store, tagged for it.
        assertEquals(listOf("r1", "r2"), vm.awaitLoaded().jobs.map { it.id })
        assertEquals("Morning brief", vm.state.value.jobs.first().displayName)
    }

    @Test
    fun aNewRoutineGoesInTheBotsStoreTaggedAndReportingToItsChat() = runTest(dispatcher) {
        jobsBody = routinesBody
        val vm = viewModel()
        vm.bind(gateway, RoutineOwner("scribe", "Scribe"))
        vm.awaitLoaded()
        vm.fillNewJob()
        assertEquals("bot-chat", vm.state.value.editor?.deliver)
        vm.name.setTextAndPlaceCursorAtEnd("Evening wrap")

        vm.saveJob()
        val saved = vm.state.first { it.editor == null }

        assertEquals("profile=scribe", createdQuery)
        assertTrue(createdBody!!.contains(""""name":"[bot:scribe] Evening wrap""""), createdBody)
        assertTrue(createdBody!!.contains(""""deliver":"bot-chat""""), createdBody)
        // The gateway's answer carries no profile; the page keeps it, so the routine stays listed.
        assertEquals("scribe", saved.jobs.first { it.id == "n1" }.profile)
    }

    @Test
    fun aRoutineNeedsAName() = runTest(dispatcher) {
        val vm = viewModel()
        vm.bind(gateway, RoutineOwner("scribe", "Scribe"))
        vm.awaitLoaded()
        vm.fillNewJob()

        vm.saveJob()

        assertEquals(said(Res.string.sessions_job_needs_name), vm.state.value.editor?.error)
        assertTrue(created.isEmpty())
    }

    @Test
    fun editingARoutineShowsItsTitleAndKeepsTheTag() = runTest(dispatcher) {
        jobsBody = routinesBody
        val vm = viewModel()
        vm.bind(gateway, RoutineOwner("scribe", "Scribe"))
        vm.awaitLoaded()
        vm.openJob("r1")

        vm.editJob()

        assertEquals("Morning brief", vm.name.text.toString())
    }

    @Test
    fun savingAJobDeletedElsewhereDoesNotCreateItAgain() = runTest(dispatcher) {
        val vm = viewModel()
        vm.bind(gateway)
        vm.awaitLoaded()
        vm.openJob("a1")
        vm.editJob()

        // Another client removes it, and the cron.changed refetch drops it from the list.
        jobsBody = "[]"
        vm.refresh()
        vm.state.first { it.jobs.isEmpty() }

        vm.saveJob()

        assertTrue(created.isEmpty())
        assertEquals(said(Res.string.sessions_job_gone), vm.state.value.editor?.error)
    }

    @Test
    fun aSaveThatFinishesAfterTheFormClosedLeavesThePageAlone() = runTest(dispatcher) {
        val vm = viewModel()
        vm.bind(gateway)
        vm.awaitLoaded()
        val gate = CompletableDeferred<Unit>().also { createGate = it }
        vm.fillNewJob()
        vm.saveJob()

        // Back out of the form and open another job while the create is still in flight.
        vm.closeEditor()
        vm.openJob("a1")
        gate.complete(Unit)

        val state = vm.state.first { it.message != null }
        assertEquals("a1", state.openJobId)
        assertNull(state.editor)
        assertEquals(said(Res.string.sessions_job_scheduled), state.message)
        assertTrue(state.jobs.any { it.id == "n1" })
    }

    @Test
    fun aSaveThatFailsAfterTheFormClosedSaysSo() = runTest(dispatcher) {
        createStatus = HttpStatusCode.ServiceUnavailable
        val vm = viewModel()
        vm.bind(gateway)
        vm.awaitLoaded()
        val gate = CompletableDeferred<Unit>().also { createGate = it }
        vm.fillNewJob()
        vm.saveJob()

        vm.closeEditor()
        gate.complete(Unit)

        val message = assertIs<Said.Line>(vm.state.first { it.message != null }.message)
        assertEquals(Res.string.sessions_job_save_failed, message.res)
        assertTrue("Gateway is busy" in message.detail.orEmpty(), message.toString())
    }

    private val pinnedBody = """[
        {"id":"a1","name":"Briefing","prompt":"Brief me","model":"claude-sonnet-4.5","provider":"anthropic","schedule":{"kind":"cron","expr":"0 9 * * *"}},
        {"id":"a2","name":"Digest","prompt":"Digest","model":"","provider":null,"schedule":{"kind":"cron","expr":"0 9 * * *"}}
    ]"""

    @Test
    fun aNewJobLeftOnTheDefaultSendsNoModel() = runTest(dispatcher) {
        val vm = viewModel()
        vm.bind(gateway)
        vm.awaitLoaded()
        vm.fillNewJob()
        assertNull(vm.state.value.editor?.model)

        vm.saveJob()
        vm.state.first { it.editor == null }

        assertFalse("model" in createdBody!!, createdBody)
        assertFalse("provider" in createdBody!!, createdBody)
    }

    @Test
    fun aNewJobSendsThePickedModelAndItsProvider() = runTest(dispatcher) {
        val vm = viewModel()
        vm.bind(gateway)
        vm.awaitLoaded()
        vm.fillNewJob()

        vm.setModel(ModelOption(id = "gpt-5.1", provider = "openrouter"))
        assertEquals("gpt-5.1", vm.state.value.editor?.model)
        assertEquals("openrouter", vm.state.value.editor?.provider)
        assertTrue(vm.editorChanged())
        vm.saveJob()
        vm.state.first { it.editor == null }

        assertTrue(createdBody!!.contains(""""model":"gpt-5.1","provider":"openrouter""""), createdBody)
    }

    @Test
    fun editingAJobShowsItsSavedModelAndLeavesItAloneWhenUntouched() = runTest(dispatcher) {
        jobsBody = pinnedBody
        val vm = viewModel()
        vm.bind(gateway)
        vm.awaitLoaded()
        vm.openJob("a1")

        vm.editJob()
        assertEquals("claude-sonnet-4.5", vm.state.value.editor?.model)
        assertEquals("anthropic", vm.state.value.editor?.provider)
        assertFalse(vm.editorChanged())
        vm.name.setTextAndPlaceCursorAtEnd("Morning")
        vm.saveJob()
        vm.state.first { it.editor == null }

        assertEquals("""{"updates":{"name":"Morning"}}""", updatedBody)
    }

    @Test
    fun clearingAJobsModelSendsBothFieldsEmpty() = runTest(dispatcher) {
        jobsBody = pinnedBody
        val vm = viewModel()
        vm.bind(gateway)
        vm.awaitLoaded()
        vm.openJob("a1")
        vm.editJob()

        vm.clearModel()
        assertNull(vm.state.value.editor?.model)
        assertNull(vm.state.value.editor?.provider)
        vm.saveJob()
        vm.state.first { it.editor == null }

        // The gateway stores an empty model or provider as none, so the job runs on its profile's default.
        assertEquals("""{"updates":{"model":"","provider":""}}""", updatedBody)
    }

    @Test
    fun aNewModelForAJobSendsItWithItsProvider() = runTest(dispatcher) {
        jobsBody = pinnedBody
        val vm = viewModel()
        vm.bind(gateway)
        vm.awaitLoaded()
        vm.openJob("a1")
        vm.editJob()

        // The same model id from another provider is a change too.
        vm.setModel(ModelOption(id = "claude-sonnet-4.5", provider = "openrouter"))
        vm.saveJob()
        vm.state.first { it.editor == null }

        assertEquals("""{"updates":{"model":"claude-sonnet-4.5","provider":"openrouter"}}""", updatedBody)
    }

    @Test
    fun aJobWithABlankModelShowsTheDefault() = runTest(dispatcher) {
        jobsBody = pinnedBody
        val vm = viewModel()
        vm.bind(gateway)
        vm.awaitLoaded()
        vm.openJob("a2")

        vm.editJob()

        assertNull(vm.state.value.editor?.model)
        vm.saveJob()
        vm.state.first { it.editor == null }
        // Nothing changed, so nothing is sent.
        assertNull(updatedBody)
    }

    @Test
    fun theModelsThatCannotLoadSayWhy() = runTest(dispatcher) {
        val vm = viewModel()
        vm.bind(gateway)
        vm.awaitLoaded()
        vm.fillNewJob()

        vm.loadModels()

        val picker = vm.modelPicker.first { !it.loading }
        assertNotNull(picker.error)
        assertNull(picker.catalog)
    }

    private val cliPinnedBody = """[
        {"id":"c1","name":"Local","prompt":"Brief me","model":"m1","provider":"custom:lab","base_url":"http://lab:8000/v1","schedule":{"kind":"cron","expr":"0 9 * * *"}},
        {"id":"c2","name":"Provider only","prompt":"Brief me","model":null,"provider":"anthropic","schedule":{"kind":"cron","expr":"0 9 * * *"}}
    ]"""

    private suspend fun ScheduledViewModel.editCliJob(id: String) {
        jobsBody = cliPinnedBody
        bind(gateway)
        awaitLoaded()
        openJob(id)
        editJob()
    }

    @Test
    fun pickingAModelForAJobWithAnEndpointClearsTheEndpoint() = runTest(dispatcher) {
        val vm = viewModel()
        vm.editCliJob("c1")

        vm.setModel(ModelOption(id = "gpt-5.1", provider = "openrouter"))
        vm.saveJob()
        vm.state.first { it.editor == null }

        // The scheduler calls a job's base_url whatever its provider, so the old endpoint mustn't stay behind.
        assertEquals("""{"updates":{"model":"gpt-5.1","provider":"openrouter","base_url":""}}""", updatedBody)
    }

    @Test
    fun clearingAJobWithAnEndpointClearsTheEndpointToo() = runTest(dispatcher) {
        val vm = viewModel()
        vm.editCliJob("c1")
        assertEquals("http://lab:8000/v1", vm.state.value.editor?.baseUrl)

        vm.clearModel()
        assertTrue(vm.editorChanged())
        vm.saveJob()
        vm.state.first { it.editor == null }

        assertEquals("""{"updates":{"model":"","provider":"","base_url":""}}""", updatedBody)
    }

    @Test
    fun aJobPinnedToAProviderAloneShowsItAndCanBeCleared() = runTest(dispatcher) {
        val vm = viewModel()
        vm.editCliJob("c2")

        // Its runs go to that provider, so the form mustn't say it's on the profile default.
        assertNull(vm.state.value.editor?.model)
        assertEquals("anthropic", vm.state.value.editor?.provider)
        assertFalse(vm.editorChanged())

        vm.clearModel()
        vm.saveJob()
        vm.state.first { it.editor == null }

        assertEquals("""{"updates":{"model":"","provider":""}}""", updatedBody)
    }

    @Test
    fun anUntouchedProviderOnlyJobSendsNothingForIt() = runTest(dispatcher) {
        val vm = viewModel()
        vm.editCliJob("c2")

        vm.name.setTextAndPlaceCursorAtEnd("Renamed")
        vm.saveJob()
        vm.state.first { it.editor == null }

        assertEquals("""{"updates":{"name":"Renamed"}}""", updatedBody)
    }

    @Test
    fun thePickerMarksNothingForAJobOnTheDefault() {
        // The catalog's current model is the chat default; cron runs can use cron.model instead.
        val catalog = ModelCatalog(
            providers = listOf(ModelProvider(slug = "anthropic", name = "Anthropic", models = listOf(ModelOption(id = "claude-x", provider = "anthropic")))),
            currentModel = "claude-x",
            currentProvider = "anthropic",
        )
        val picker = ModelPickerState(catalog = catalog).forJob()

        assertNull(ModelSelection.of(ChatState(model = null, provider = null), picker.catalog).option)
        // A job's own model is still marked.
        assertEquals("claude-x", ModelSelection.of(ChatState(model = "claude-x", provider = "anthropic"), picker.catalog).option?.id)
    }
}
