package dev.hermeskotlin.core.chat

import dev.hermeskotlin.core.connection.ConnectionState
import dev.hermeskotlin.core.rpc.FakeGateway
import dev.hermeskotlin.core.rpc.GatewayEvent
import dev.hermeskotlin.core.rpc.event
import dev.hermeskotlin.core.rpc.json
import dev.hermeskotlin.core.sessions.SessionsApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Editing a prompt queued behind a running turn. Only the session authority can, by `prompt.replace_queued`
 * (hermes-agent v0.22.3 on, gateway/session_queued_controls.py), and only for the admissions whose `pending` row
 * lists `replace` in its `controls` (`CanonicalPendingAdmission`); the legacy dashboard has no such call.
 */
class QueuedEditTest {

    // --- What the gateway offers ---

    @Test
    fun aPendingAdmissionListingReplaceCanBeEdited() {
        val state = queuedState().reduce(info(pending("adm-2", controls = """["steer","replace"]""")))

        assertEquals(setOf("adm-2"), state.replaceableAdmissions)
        assertTrue(state.canEditQueued("local-2"))
    }

    @Test
    fun anOwnerThatListsNoControlsOffersNoEdit() {
        // An owner older than the verb sends the row without `controls`; one that won't replace this row sends [].
        val older = queuedState().reduce(info(pending("adm-2", controls = null)))
        val refused = queuedState().reduce(info(pending("adm-2", controls = """["steer"]""")))

        assertFalse(older.canEditQueued("local-2"))
        assertFalse(refused.canEditQueued("local-2"))
    }

    @Test
    fun anAdmissionThatLeftTheQueueIsNotEditable() {
        val state = queuedState().reduce(info(pending("adm-2", controls = """["replace"]""", status = "started")))

        assertFalse(state.canEditQueued("local-2"))
    }

    @Test
    fun aSessionInfoWithoutPendingKeepsWhatWasKnown() {
        val offered = queuedState().reduce(info(pending("adm-2", controls = """["replace"]""")))

        val after = offered.reduce(GatewayEvent("session.info", Json.parseToJsonElement("""{"model":"m"}""").jsonObject, "stored-1", null))

        assertTrue(after.canEditQueued("local-2"))
    }

    @Test
    fun aPromptWithFilesOrShownAsSomethingElseIsNotEditable() {
        val state = queuedState(sentText = null).reduce(info(pending("adm-2", controls = """["replace"]""")))

        assertFalse(state.canEditQueued("local-2"))
    }

    @Test
    fun anEditGoesToTheBackOfTheQueue() {
        val state = ChatState(
            running = true,
            messages = listOf(
                ChatMessage.User("local-1", "first", rowId = 1, sentText = "first"),
                ChatMessage.Assistant("reply-1", "working", streaming = true),
                ChatMessage.User("local-2", "second", queued = true, sentText = "second", admissionId = "adm-2"),
                ChatMessage.User("local-3", "third", queued = true, sentText = "third", admissionId = "adm-3"),
            ),
            replaceableAdmissions = setOf("adm-2", "adm-3"),
        )

        val edited = state.withQueuedEdit("local-2", "adm-2", "second, edited", "adm-4", "sub-4")

        assertEquals(listOf("local-1", "reply-1", "local-3", "local-2"), edited.messages.map { it.key })
        val prompt = edited.messages.last() as ChatMessage.User
        assertEquals("second, edited", prompt.text)
        assertEquals("second, edited", prompt.sentText)
        assertEquals("adm-4", prompt.admissionId)
        assertEquals("sub-4", prompt.submissionId)
        assertTrue(prompt.queued)
        assertEquals(setOf("adm-3"), edited.replaceableAdmissions)
    }

    @Test
    fun anEditWhoseTurnAlreadyStartedIsNotShownWaiting() {
        val state = queuedState().copy(startedAdmissions = listOf("adm-4"))

        val edited = state.withQueuedEdit("local-2", "adm-2", "edited", "adm-4", "sub-4")

        assertFalse((edited.messages.last() as ChatMessage.User).queued)
    }

    @Test
    fun aLateAnswerForAnotherChatsPromptChangesNothing() {
        // After a chat switch, `local-2` is another chat's prompt, waiting as another admission.
        val other = queuedState().copy(
            messages = queuedState().messages.map {
                if (it is ChatMessage.User && it.key == "local-2") it.copy(text = "other", sentText = "other", admissionId = "adm-9") else it
            },
        )

        assertEquals(other, other.withQueuedEdit("local-2", "adm-2", "edited", "adm-4", "sub-4"))
    }

    @Test
    fun theEditedPromptsTurnStartReleasesIt() {
        val edited = queuedState().withQueuedEdit("local-2", "adm-2", "edited", "adm-4", "sub-4")

        val started = edited.reduce(GatewayEvent("message.start", null, "stored-1", null, admissionId = "adm-4"))

        assertFalse((started.messages.first { it.key == "local-2" } as ChatMessage.User).queued)
    }

    @Test
    fun theReplacedAdmissionsSettlementEndsNoTurn() {
        // gateway/session_authority.py `_settle_cancelled`: a completion stamped with the cancelled admission, no generation.
        val running = queuedState().copy(startedAdmissions = listOf("adm-1"))
        val settled = GatewayEvent(
            "message.complete",
            Json.parseToJsonElement("""{"text":"","content":"","admission_id":"adm-2","outcome":"cancelled"}""").jsonObject,
            "stored-1",
            null,
            admissionId = "adm-2",
        )

        val after = running.reduce(settled)

        assertTrue(after.running)
        assertTrue((after.messages.first { it.key == "reply-1" } as ChatMessage.Assistant).streaming)
    }

    @Test
    fun theRunningTurnsOwnCompletionStillEndsIt() {
        val running = queuedState().copy(startedAdmissions = listOf("adm-1"))
        val complete = GatewayEvent(
            "message.complete",
            Json.parseToJsonElement("""{"text":"working","status":"complete"}""").jsonObject,
            "stored-1",
            null,
            executionGeneration = 2,
            admissionId = "adm-1",
        )

        assertFalse(running.reduce(complete).running)
    }

    // --- The gateway call ---

    private lateinit var gateway: FakeGateway

    private fun params(method: String): List<JsonObject> = gateway.sent(method).map { it["params"]!!.jsonObject }

    private fun JsonObject.text(key: String) = this[key]?.jsonPrimitive?.contentOrNull

    /**
     * A chat with a turn running on a gateway on the session [authority] or not, and one prompt queued behind it as
     * `adm-2` (on the authority; the legacy dashboard names no admission). [replace] answers `prompt.replace_queued`,
     * or stays silent with null. By default it answers as upstream does a method it lacks: the legacy dispatcher, or
     * an authority before v0.22.3.
     */
    private suspend fun queuedChat(
        scope: CoroutineScope,
        authority: Boolean,
        replace: (FakeGateway.Call) -> String? = { it.error(-32601, "unknown method: prompt.replace_queued", if (authority) "unknown_method" else null) },
    ): ChatSession {
        gateway = FakeGateway(scope, reconnects = false).apply {
            ready = { if (authority) AUTHORITY_READY else FakeGateway.QUIET_READY }
            answer = { call ->
                when (call.method) {
                    "session.resume" -> """{"session_id":"stored-1","running":true,"revision":4,"execution_generation":2}"""
                    "prompt.submit" ->
                        if (authority) """{"status":"queued","admission_id":"adm-2","execution_generation":2}""" else """{"status":"queued"}"""
                    "prompt.replace_queued" -> replace(call)
                    else -> "{}"
                }
            }
            http = { json("""{"session_id":"stored-1","messages":[]}""") }
            start(awaitConnected = true)
        }
        val client = (gateway.connection.state.first { it is ConnectionState.Connected } as ConnectionState.Connected).client
        client.ready.await()
        val chat = ChatSession(FakeGateway.URL, "stored-1", "Greeting", gateway.connection, SessionsApi(gateway.client), scope)
        chat.start()
        chat.state.first { it.runtimeSessionId == "stored-1" && it.historyLoaded && it.running }
        assertEquals(SendOutcome.Sent, chat.submit("second", queue = true))
        assertTrue(queuedPrompt(chat).queued)
        return chat
    }

    private fun queuedPrompt(chat: ChatSession) = chat.state.value.messages.filterIsInstance<ChatMessage.User>().last()

    /** The gateway's `session.info` after the prompt was admitted, listing it with [controls]. */
    private suspend fun publishPending(chat: ChatSession, controls: String?) {
        gateway.socket.push(event("session.info", "stored-1", """{"stored_session_id":"stored-1","pending":[${pending("adm-2", controls)}]}"""))
        chat.state.first { s -> controls?.contains("replace") != true || "adm-2" in s.replaceableAdmissions }
    }

    @Test
    fun onTheSessionAuthorityAnEditReplacesTheQueuedAdmission() = runTest(timeout = 5.seconds) {
        val chat = queuedChat(backgroundScope, authority = true, replace = { call ->
            call.reply("""{"admission_id":"adm-3","status":"queued","sequence":3,"outcome":null,"authority_epoch":1,"execution_generation":null,"ref":{}}""")
        })
        publishPending(chat, """["steer","replace"]""")
        val key = queuedPrompt(chat).key
        assertTrue(chat.state.value.canEditQueued(key))

        assertEquals(QueuedEditOutcome.Replaced, chat.replaceQueued(key, "  second, edited "))

        val call = params("prompt.replace_queued").single()
        // Exactly the keys CanonicalReplaceQueuedParams has; the contract refuses any other.
        assertEquals(setOf("session_id", "admission_id", "submission_id", "text"), call.keys)
        assertEquals("stored-1", call.text("session_id"))
        assertEquals("adm-2", call.text("admission_id"))
        assertEquals("second, edited", call.text("text"))
        val prompt = queuedPrompt(chat)
        assertEquals("second, edited", prompt.text)
        assertEquals("adm-3", prompt.admissionId)
        assertEquals(call.text("submission_id"), prompt.submissionId)
        assertNotEquals(params("prompt.submit").single().text("submission_id"), prompt.submissionId)
        assertTrue(prompt.queued)
        assertEquals(null, chat.state.value.error)
    }

    @Test
    fun onTheSessionAuthorityAnEditTooLateLeavesThePromptAsItWas() = runTest(timeout = 5.seconds) {
        val chat = queuedChat(backgroundScope, authority = true, replace = { call -> call.error(4001, "admission_started", "admission_started") })
        publishPending(chat, """["steer","replace"]""")
        val key = queuedPrompt(chat).key

        assertEquals(QueuedEditOutcome.Started, chat.replaceQueued(key, "edited"))

        assertEquals("second", queuedPrompt(chat).text)
        assertEquals("adm-2", queuedPrompt(chat).admissionId)
        assertTrue(chat.state.value.error!!.contains("already started"))
    }

    @Test
    fun aRetryAfterNoAnswerSendsTheSameSubmission() = runTest(timeout = 5.seconds) {
        var calls = 0
        // The first call is lost on the way back; the gateway answers the exact retry with the admission it made.
        val chat = queuedChat(backgroundScope, authority = true, replace = { call ->
            if (++calls == 1) null else call.reply("""{"admission_id":"adm-3","status":"queued"}""")
        })
        publishPending(chat, """["steer","replace"]""")
        val key = queuedPrompt(chat).key

        assertEquals(QueuedEditOutcome.Failed, chat.replaceQueued(key, "edited"))
        assertEquals(QueuedEditOutcome.Replaced, chat.replaceQueued(key, "edited"))

        val (first, retry) = params("prompt.replace_queued")
        assertEquals(first.text("submission_id"), retry.text("submission_id"))
        assertEquals("adm-3", queuedPrompt(chat).admissionId)
        assertEquals("edited", queuedPrompt(chat).text)
    }

    /** The gateway's completion for [admission], cancelled before it ran (`_settle_cancelled`): its id, no generation. */
    private fun settlement(admission: String) =
        """{"jsonrpc":"2.0","method":"event","params":{"type":"message.complete","session_id":"stored-1",""" +
            """"authority_epoch":1,"admission_id":"$admission","payload":{"text":"","content":"","admission_id":"$admission","outcome":"cancelled"}}}"""

    /** The `session.info` the gateway publishes once it took the edit: the replacement queued under [submission]. */
    private fun replaced(submission: String?, vararg before: String) =
        event(
            "session.info",
            "stored-1",
            """{"stored_session_id":"stored-1","pending":[${(before.toList() + pending("adm-3", """["steer","replace"]""", inputId = submission!!, text = "edited")).joinToString(",")}]}""",
        )

    private fun start(admission: String) =
        """{"jsonrpc":"2.0","method":"event","params":{"type":"message.start","session_id":"stored-1",""" +
            """"execution_generation":3,"admission_id":"$admission","payload":{}}}"""

    @Test
    fun anEditTheGatewayTookWithoutAnsweringStillShowsAndStarts() = runTest(timeout = 5.seconds) {
        // The edit landed (adm-2 cancelled, adm-3 admitted at the tail) but its answer was lost on the way back.
        val chat = queuedChat(backgroundScope, authority = true, replace = { null })
        publishPending(chat, """["steer","replace"]""")
        val key = queuedPrompt(chat).key

        assertEquals(QueuedEditOutcome.Failed, chat.replaceQueued(key, "edited"))
        gateway.socket.push(settlement("adm-2"))
        gateway.socket.push(replaced(params("prompt.replace_queued").single().text("submission_id")))
        chat.state.first { (it.messages.first { m -> m.key == key } as ChatMessage.User).text == "edited" }
        val prompt = chat.state.value.messages.first { it.key == key } as ChatMessage.User
        assertEquals("adm-3", prompt.admissionId)
        assertTrue(prompt.queued)
        // Known by its admission again, it can be edited again.
        assertTrue(chat.state.value.canEditQueued(key))

        gateway.socket.push(event("message.complete", "stored-1", """{"status":"complete"}"""))
        gateway.socket.push(start("adm-3"))
        chat.state.first { s -> s.messages.none { it is ChatMessage.User && it.queued } }
        assertEquals("edited", (chat.state.value.messages.first { it.key == key } as ChatMessage.User).text)
    }

    @Test
    fun anotherClientsTurnDoesNotStartALostAnswersEdit() = runTest(timeout = 5.seconds) {
        // Another client's prompt waits ahead of the edit, which went to the back of the queue.
        val chat = queuedChat(backgroundScope, authority = true, replace = { null })
        publishPending(chat, """["steer","replace"]""")
        val key = queuedPrompt(chat).key
        assertEquals(QueuedEditOutcome.Failed, chat.replaceQueued(key, "edited"))
        val ahead = pending("adm-x", """["steer","replace"]""", inputId = "theirs", text = "theirs")
        gateway.socket.push(replaced(params("prompt.replace_queued").single().text("submission_id"), ahead))
        chat.state.first { (it.messages.first { m -> m.key == key } as ChatMessage.User).text == "edited" }

        gateway.socket.push(event("message.complete", "stored-1", """{"status":"complete"}"""))
        gateway.socket.push(start("adm-x"))
        chat.state.first { "adm-x" in it.startedAdmissions }

        assertTrue((chat.state.value.messages.first { it.key == key } as ChatMessage.User).queued)
    }

    @Test
    fun aCancelOfTheOldAdmissionAloneDoesNotShowTheEditAsMade() = runTest(timeout = 5.seconds) {
        // The edit got no answer and another client cancelled the prompt it was for: no replacement is queued.
        val chat = queuedChat(backgroundScope, authority = true, replace = { null })
        publishPending(chat, """["steer","replace"]""")
        val key = queuedPrompt(chat).key
        assertEquals(QueuedEditOutcome.Failed, chat.replaceQueued(key, "edited"))

        gateway.socket.push(settlement("adm-2"))
        gateway.socket.push(event("session.info", "stored-1", """{"stored_session_id":"stored-1","pending":[]}"""))
        chat.state.first { it.replaceableAdmissions.isEmpty() }

        assertEquals("second", (chat.state.value.messages.first { it.key == key } as ChatMessage.User).text)
    }

    @Test
    fun anEditTakenBeforeItsLostAnswerCountsAsMade() = runTest(timeout = 5.seconds) {
        val chat = queuedChat(backgroundScope, authority = true, replace = { call ->
            // The gateway lists the replacement before it answers, and the answer is then lost.
            gateway.socket.push(settlement("adm-2"))
            gateway.socket.push(replaced(call.params["submission_id"]?.jsonPrimitive?.contentOrNull))
            null
        })
        publishPending(chat, """["steer","replace"]""")
        val key = queuedPrompt(chat).key

        assertEquals(QueuedEditOutcome.Replaced, chat.replaceQueued(key, "edited"))

        val prompt = chat.state.value.messages.first { it.key == key } as ChatMessage.User
        assertEquals("edited", prompt.text)
        assertEquals("adm-3", prompt.admissionId)
        assertTrue(prompt.queued)
        assertTrue(chat.state.value.unansweredQueuedEdits.isEmpty())
    }

    @Test
    fun twoEditsWithoutAnswersBothShowWhenTheQueueListsThem() {
        val state = ChatState(
            running = true,
            messages = listOf(
                ChatMessage.User("local-1", "first", rowId = 1, sentText = "first"),
                ChatMessage.Assistant("reply-1", "working", streaming = true),
                ChatMessage.User("local-2", "second", queued = true, sentText = "second", admissionId = "adm-2"),
                ChatMessage.User("local-3", "third", queued = true, sentText = "third", admissionId = "adm-4"),
            ),
            unansweredQueuedEdits = mapOf(
                "sub-a" to UnansweredQueuedEdit("local-2", "adm-2", "second, edited"),
                "sub-b" to UnansweredQueuedEdit("local-3", "adm-4", "third, edited"),
            ),
        )

        val after = state.reduce(
            info(
                pending("adm-5", """["replace"]""", inputId = "sub-a", text = "second, edited"),
                pending("adm-6", """["replace"]""", inputId = "sub-b", text = "third, edited"),
            ),
        )

        val prompts = after.messages.filterIsInstance<ChatMessage.User>().associateBy { it.key }
        assertEquals("second, edited" to "adm-5", prompts.getValue("local-2").let { it.text to it.admissionId })
        assertEquals("third, edited" to "adm-6", prompts.getValue("local-3").let { it.text to it.admissionId })
        assertTrue(after.unansweredQueuedEdits.isEmpty())
    }

    @Test
    fun aQueuedPromptIsNotOfferedForEditWhileItsEditIsOnTheWay() = runTest(timeout = 5.seconds) {
        val answers = kotlinx.coroutines.CompletableDeferred<FakeGateway.Call>()
        val chat = queuedChat(backgroundScope, authority = true, replace = { call -> answers.complete(call); null })
        publishPending(chat, """["steer","replace"]""")
        val key = queuedPrompt(chat).key

        val edit = backgroundScope.async { chat.replaceQueued(key, "edited") }
        answers.await()

        assertFalse(chat.state.value.canEditQueued(key))
        // A `session.info` published meanwhile still lists the old admission; it stays not offered.
        publishPending(chat, """["steer","replace"]""")
        assertFalse(chat.state.value.canEditQueued(key))
        edit.cancel()
    }

    @Test
    fun anEditOfAPromptAStopDroppedSaysSoNotThatItStarted() = runTest(timeout = 5.seconds) {
        val chat = queuedChat(backgroundScope, authority = true)
        publishPending(chat, """["steer","replace"]""")
        val key = queuedPrompt(chat).key
        chat.interrupt()

        assertEquals(QueuedEditOutcome.Failed, chat.replaceQueued(key, "edited"))

        assertFalse(chat.state.value.error!!.contains("started"))
    }

    @Test
    fun onTheSessionAuthorityAnUnsupportedControlHidesTheEdit()= runTest(timeout = 5.seconds) {
        val chat = queuedChat(backgroundScope, authority = true, replace = { call -> call.error(4001, "unsupported_control", "unsupported_control") })
        publishPending(chat, """["steer","replace"]""")
        val key = queuedPrompt(chat).key

        assertEquals(QueuedEditOutcome.Unsupported, chat.replaceQueued(key, "edited"))

        assertEquals("second", queuedPrompt(chat).text)
        assertFalse(chat.state.value.canEditQueued(key))
    }

    @Test
    fun onAnAuthorityWithoutTheVerbTheMethodNotFoundHidesTheEdit() = runTest(timeout = 5.seconds) {
        // An owner listing `replace` but answering -32601: the fallback still leaves the prompt as it was.
        val chat = queuedChat(backgroundScope, authority = true)
        publishPending(chat, """["replace"]""")
        val key = queuedPrompt(chat).key

        assertEquals(QueuedEditOutcome.Unsupported, chat.replaceQueued(key, "edited"))

        assertEquals("second", queuedPrompt(chat).text)
        assertFalse(chat.state.value.canEditQueued(key))
        assertTrue(chat.state.value.error!!.contains("can't change a queued message"))
    }

    @Test
    fun onAnAuthorityBeforeTheVerbNothingIsOfferedOrSent() = runTest(timeout = 5.seconds) {
        // v0.22.1's owner lists its pending admissions without `controls`.
        val chat = queuedChat(backgroundScope, authority = true)
        publishPending(chat, controls = null)
        val key = queuedPrompt(chat).key

        assertFalse(chat.state.value.canEditQueued(key))
        assertEquals(QueuedEditOutcome.Unsupported, chat.replaceQueued(key, "edited"))
        assertTrue(params("prompt.replace_queued").isEmpty())
    }

    @Test
    fun onTheLegacyGatewayNoEditIsOfferedOrSent() = runTest(timeout = 5.seconds) {
        val chat = queuedChat(backgroundScope, authority = false)
        val key = queuedPrompt(chat).key

        assertFalse(chat.state.value.canEditQueued(key))
        assertEquals(QueuedEditOutcome.Unsupported, chat.replaceQueued(key, "edited"))
        assertTrue(params("prompt.replace_queued").isEmpty())
        assertEquals("second", queuedPrompt(chat).text)
    }

    private companion object {
        const val AUTHORITY_READY =
            """{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready","payload":{"session_authority":true}}}"""

        /** A running turn with prompt `local-2` queued behind it as admission `adm-2`. */
        fun queuedState(sentText: String? = "second") = ChatState(
            running = true,
            messages = listOf(
                ChatMessage.User("local-1", "first", rowId = 1, sentText = "first"),
                ChatMessage.Assistant("reply-1", "working", streaming = true),
                ChatMessage.User("local-2", "second", queued = true, sentText = sentText, admissionId = "adm-2"),
            ),
        )

        /** One `CanonicalPendingAdmission` row as gateway/session_authority.py `_pending_receipt` sends it. */
        fun pending(admission: String, controls: String?, status: String = "queued", inputId: String = "sub-2", text: String = "second") =
            """{"admission_id":"$admission","ref":{"profile_id":"default","session_id":"stored-1"},"sequence":2,""" +
                """"status":"$status","outcome":null,"authority_epoch":1,"execution_generation":null,""" +
                """"input_id":"$inputId","text":"$text"${controls?.let { ""","controls":$it""" }.orEmpty()}}"""

        fun info(vararg rows: String) =
            GatewayEvent("session.info", Json.parseToJsonElement("""{"pending":[${rows.joinToString(",")}]}""").jsonObject, "stored-1", null)
    }
}
