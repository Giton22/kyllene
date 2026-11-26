package dev.hermeskotlin.core.chat

import dev.hermeskotlin.core.connection.ConnectionState
import dev.hermeskotlin.core.connection.GatewayConnection
import dev.hermeskotlin.core.rpc.FakeGateway
import dev.hermeskotlin.core.rpc.event
import dev.hermeskotlin.core.rpc.isCall
import dev.hermeskotlin.core.rpc.json
import dev.hermeskotlin.core.sessions.ActiveSessions
import dev.hermeskotlin.core.sessions.SessionsApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionWatcherTest {

    /** A live row: stored id, status, title. Runtime ids are `rt-<stored id>` unless given. */
    private data class Row(val key: String, val status: String, val title: String = "Chat $key", val runtime: String = "rt-$key")

    /** What the fake gateway lists as live, and what `session.activate` hands back as open requests. */
    private val live = MutableStateFlow(listOf<Row>())
    private var openRequests = "[]"

    /** Whether `session.activate` says the turn runs, and how long it takes to answer. */
    private var activateRunning = true
    private var activateDelayMs = 0L

    private lateinit var gateway: FakeGateway

    private val sockets get() = gateway.sockets
    private fun sentMethods(method: String) = gateway.sent(method)

    private class Setup(val connection: GatewayConnection, val host: ChatHost, val tracker: AttentionTracker, val watcher: SessionWatcher)

    /**
     * A gateway on its session [authority] has no `session.activate` (-32601, as gateway/session_controls.py answers
     * it); its attach is a `session.resume` without the messages, whose reply also carries the replay fields.
     */
    private suspend fun TestScope.setup(maxWatched: Int = 10, authority: Boolean = false): Setup {
        val scope = backgroundScope
        gateway = FakeGateway(scope).apply {
            ready = { if (authority) AUTHORITY_READY else FakeGateway.QUIET_READY }
            http = { json("""{"messages":[]}""") }
            answer = { call ->
                val sid = call.param("session_id").orEmpty()
                val attach = if (authority) "session.resume" else "session.activate"
                when {
                    call.method == "session.active_list" -> live.value.joinToString(",", """{"sessions":[""", "]}") {
                        """{"id":"${it.runtime}","session_key":"${it.key}","status":"${it.status}","title":"${it.title}"}"""
                    }
                    authority && call.method == "session.activate" -> call.error(
                        -32601, "unknown method: session.activate — not available on this Hermes backend", reason = "unknown_method",
                    )
                    // The watcher attaches a listed runtime id; an open chat resumes its stored id.
                    call.method == attach && (!authority || sid.startsWith("rt-")) -> {
                        val result = """{"session_id":"$sid","running":$activateRunning,"open_requests":$openRequests""" +
                            if (authority) ""","subscription_id":"sub-$sid","replay_epoch":"watcher-epoch","last_sequence":99,"inflight":{"assistant":"Half a reply from elsewhere"}}""" else "}"
                        if (activateDelayMs == 0L) result
                        else null.also { scope.launch { delay(activateDelayMs); call.socket.push(call.reply(result)) } }
                    }
                    else -> when (call.method) {
                        "session.resume" -> """{"session_id":"rt-$sid","running":false}"""
                        else -> "{}"
                    }
                }
            }
        }
        val connection = gateway.start()
        val clock = { testScheduler.currentTime + 1 }
        val host = ChatHost(connection, SessionsApi(gateway.client), scope)
        val active = ActiveSessions(connection, scope, clock = clock)
        val tracker = AttentionTracker(connection, host, active, scope, clock)
        // Following, as out of sight with notifications on.
        val watcher = SessionWatcher(connection, host, active, tracker, scope, maxWatched, clock).also { it.follow(true) }
        return Setup(connection, host, tracker, watcher)
    }

    private fun approval(id: String, runtimeId: String) =
        """{"jsonrpc":"2.0","id":"$id","method":"approval","params":{"session_id":"$runtimeId","command":"rm -rf build","choices":["once","deny"]}}"""

    @Test
    fun aChatRunningElsewhereIsAttachedByItsRuntimeIdWithoutItsMessages() = runTest {
        live.value = listOf(Row("a", "working"), Row("b", "idle"))
        val watcher = setup().watcher
        val chats = watcher.chats.first { it.isNotEmpty() }
        assertEquals(setOf("a"), chats.keys)
        val activate = sentMethods("session.activate").single()["params"]!!.jsonObject
        assertEquals("rt-a", activate["session_id"]?.jsonPrimitive?.contentOrNull)
        assertEquals("true", activate["omit_messages"]?.jsonPrimitive?.contentOrNull)
        assertTrue(sentMethods("session.resume").isEmpty())
    }

    @Test
    fun theOpenChatBotChatsAndChatsAlreadyWatchedAreSkipped() = runTest {
        val setup = setup()
        setup.host.open(FakeGateway.URL, "open", null).state.first { it.runtimeSessionId == "rt-open" }
        live.value = listOf(Row("open", "working"), Row("bot", "working", title = "Bot Chat"), Row("a", "waiting"))
        setup.watcher.chats.first { "a" in it }
        advanceTimeBy(10_001)
        runCurrent()
        advanceTimeBy(10_001)
        runCurrent()
        assertEquals(setOf("a"), setup.watcher.chats.value.keys)
        assertEquals(listOf("rt-a"), sentMethods("session.activate").map { it["params"]!!.jsonObject["session_id"]!!.jsonPrimitive.content })
    }

    @Test
    fun noMoreThanTheLimitAreAttachedAndNoneIsEverClosed() = runTest {
        live.value = listOf(Row("a", "working"), Row("b", "starting"), Row("c", "waiting"))
        val watcher = setup(maxWatched = 2).watcher
        watcher.chats.first { it.size == 2 }
        advanceTimeBy(10_001)
        runCurrent()
        assertEquals(2, watcher.chats.value.size)
        assertEquals(2, sentMethods("session.activate").size)
        assertTrue(sentMethods("session.close").isEmpty())
    }

    @Test
    fun requestsComeFromTheAttachAndLiveAndLeaveWhenWithdrawnOrTheTurnEnds() = runTest {
        openRequests = """[{"id":"srq-old","method":"approval","params":{"session_id":"rt-a","command":"ls","choices":["once","deny"]}}]"""
        live.value = listOf(Row("a", "waiting"))
        val setup = setup()
        val watcher = setup.watcher
        assertEquals(listOf("srq-old"), watcher.chats.first { it["a"]?.requests?.isNotEmpty() == true }.getValue("a").requests.map { it.id })
        // The list's label knows the kind of request, not just that the chat waits.
        assertEquals(Waiting.Approval, setup.tracker.waiting.first { "a" in it }["a"])

        sockets.last().push(approval("srq-new", "rt-a"))
        watcher.chats.first { chats -> chats["a"]?.requests?.any { it.id == "srq-new" } == true }
        sockets.last().push(event("request.cancel", "rt-a", """{"id":"srq-old"}"""))
        watcher.chats.first { chats -> chats["a"]?.requests?.map { it.id } == listOf("srq-new") }

        val end = async { watcher.turnEnds.first() }
        runCurrent()
        sockets.last().push(event("message.complete", "rt-a", """{"text":"All done.","status":"complete"}"""))
        assertEquals(WatchedTurnEnd("a", "Chat a", "All done.", TurnOutcome.Complete, null), end.await())
        assertTrue(watcher.chats.value.getValue("a").requests.isEmpty())
    }

    @Test
    fun aRequestAnsweredOnAnotherClientLeavesOnceTheListStopsShowingItWaiting() = runTest {
        live.value = listOf(Row("a", "working"))
        val watcher = setup().watcher
        watcher.chats.first { "a" in it }
        sockets.last().push(approval("srq-1", "rt-a"))
        watcher.chats.first { it["a"]?.requests?.isNotEmpty() == true }
        live.value = listOf(Row("a", "waiting"))
        advanceTimeBy(10_001)
        runCurrent()
        assertEquals(1, watcher.chats.value.getValue("a").requests.size)

        // Desktop answered: no event says so, but the next list shows the turn working again.
        live.value = listOf(Row("a", "working"))
        advanceTimeBy(10_001)
        runCurrent()
        assertTrue(watcher.chats.value.getValue("a").requests.isEmpty())
    }

    @Test
    fun anAnswerFromANotificationGoesOutWithTheRequestId() = runTest {
        live.value = listOf(Row("a", "working"))
        val watcher = setup().watcher
        watcher.chats.first { "a" in it }
        sockets.last().push(approval("srq-1", "rt-a"))
        watcher.chats.first { it["a"]?.requests?.isNotEmpty() == true }
        assertEquals("srq-1", watcher.find("srq-1")?.second?.id)

        assertTrue(watcher.answer("srq-1", buildJsonObject { put("choice", "once") }))
        val response = sockets.last().awaitSent { it["id"]?.jsonPrimitive?.contentOrNull == "srq-1" && "result" in it }
        assertEquals("once", (response["result"] as JsonObject)["choice"]?.jsonPrimitive?.contentOrNull)
        assertNull(watcher.find("srq-1"))
    }

    /**
     * A pending-controls clarify waiting in watched chat `a`, answered from a notification with [result]. Returns
     * whether it went out. `clarify.respond` takes exactly these four keys (hermes-agent `ClarifyRespondParams`).
     */
    private suspend fun TestScope.answerPendingClarify(questions: String, result: (InputRequest.Clarify) -> JsonObject): Boolean {
        live.value = listOf(Row("a", "working"))
        val watcher = setup().watcher
        watcher.chats.first { "a" in it }
        sockets.last().push(event("clarify.request", "rt-a", """{"prompt_id":"prm-1","execution_generation":4,"questions":[$questions]}"""))
        watcher.chats.first { it["a"]?.requests?.isNotEmpty() == true }
        val clarify = watcher.find("prm-1")?.second as InputRequest.Clarify
        val sent = watcher.answer("prm-1", result(clarify))
        assertEquals(!sent, watcher.find("prm-1") != null)
        return sent
    }

    private fun clarifyRespond(): JsonObject = sentMethods("clarify.respond").single()["params"]!!.jsonObject

    private fun assertClarifyRespond(answer: String) {
        val params = clarifyRespond()
        assertEquals(setOf("session_id", "execution_generation", "prompt_id", "answer"), params.keys)
        assertEquals("rt-a", params["session_id"]?.jsonPrimitive?.contentOrNull)
        assertEquals("4", params["execution_generation"]?.jsonPrimitive?.contentOrNull)
        assertEquals("prm-1", params["prompt_id"]?.jsonPrimitive?.contentOrNull)
        assertEquals(answer, params["answer"]?.jsonPrimitive?.contentOrNull)
    }

    private val q1 = """{"qid":"q1","question":"Name?"}"""
    private val q2 = """{"qid":"q2","question":"Colour?","choices":["Red","Blue"]}"""

    @Test
    fun aPendingClarifyBatchOfOneGoesAsItsAnswer() = runTest {
        assertTrue(answerPendingClarify(q1) { InputAnswers.clarify(it, listOf(listOf("Ada"))) })
        assertClarifyRespond("Ada")
    }

    @Test
    fun aPendingClarifyCancelGoesAsAnEmptyAnswer() = runTest {
        assertTrue(answerPendingClarify("$q1,$q2") { InputAnswers.clarifyCancel })
        assertClarifyRespond("")
    }

    @Test
    fun aPendingClarifyBatchOfTwoIsNotSent() = runTest {
        assertFalse(answerPendingClarify("$q1,$q2") { InputAnswers.clarify(it, listOf(listOf("Ada"), listOf("Red"))) })
        assertTrue(sentMethods("clarify.respond").isEmpty())
    }

    @Test
    fun anInlineReplyIsSubmittedToTheLiveSession() = runTest {
        live.value = listOf(Row("a", "working"))
        val watcher = setup().watcher
        watcher.chats.first { "a" in it }
        assertTrue(watcher.reply("a", "Thanks, go on."))
        val submit = sentMethods("prompt.submit").single()["params"]!!.jsonObject
        assertEquals("rt-a", submit["session_id"]?.jsonPrimitive?.contentOrNull)
        assertEquals("Thanks, go on.", submit["text"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun aNewSocketAttachesAgain() = runTest {
        live.value = listOf(Row("a", "working"))
        val setup = setup()
        setup.watcher.chats.first { "a" in it }
        sockets.last().serverClose(1006, "gone")
        setup.connection.state.first { it !is ConnectionState.Connected }
        advanceTimeBy(2_000)
        setup.connection.state.first { it is ConnectionState.Connected }
        setup.watcher.chats.first { "a" in it }
        runCurrent()
        assertEquals(2, sockets.size)
        assertEquals(1, sockets.last().sent.value.count { it.isCall("session.activate") })
    }

    private suspend fun TestScope.reconnect(setup: Setup) {
        sockets.last().serverClose(1006, "gone")
        setup.connection.state.first { it !is ConnectionState.Connected }
        advanceTimeBy(2_000)
        setup.connection.state.first { it is ConnectionState.Connected }
        runCurrent()
    }

    @Test
    fun aRequestAnsweredInTheOpenChatNoLongerWaitsOnceTheChatIsLeft() = runTest {
        live.value = listOf(Row("a", "working"))
        val setup = setup()
        setup.watcher.chats.first { "a" in it }
        // Watched first, then opened: both hear its requests on the one socket.
        val session = setup.host.open(FakeGateway.URL, "a", null)
        session.state.first { it.runtimeSessionId == "rt-a" }
        sockets.last().push(approval("srq-1", "rt-a"))
        val request = session.state.first { it.inputRequests.isNotEmpty() }.inputRequests.single()
        setup.watcher.chats.first { it["a"]?.requests?.isNotEmpty() == true }

        assertTrue(session.answer(request, InputAnswers.approval(ApprovalChoice.Once)))
        session.state.first { it.inputRequests.isEmpty() }
        runCurrent()
        setup.host.open(FakeGateway.URL, "b", null)
        runCurrent()
        // Else, once out of sight, the approval just given would notify again.
        assertTrue(setup.watcher.chats.value.getValue("a").requests.isEmpty())
    }

    @Test
    fun aNewSocketKeepsAWaitingRequestInsteadOfDroppingItAndAskingAgain() = runTest {
        openRequests = """[{"id":"srq-1","method":"approval","params":{"session_id":"rt-a","command":"ls","choices":["once","deny"]}}]"""
        live.value = listOf(Row("a", "waiting"))
        val setup = setup()
        setup.watcher.chats.first { it["a"]?.requests?.isNotEmpty() == true }
        val seen = mutableListOf<List<String>>()
        backgroundScope.launch { setup.watcher.chats.collect { all -> seen += all["a"]?.requests.orEmpty().map { it.id } } }
        runCurrent()

        reconnect(setup)
        assertEquals(1, sockets.last().sent.value.count { it.isCall("session.activate") })
        // A gap would take its notification down and post it again, with a second heads-up.
        assertTrue(seen.all { it == listOf("srq-1") }, "requests seen: $seen")
    }

    @Test
    fun aRequestAnsweredWhileTheSocketWasDownLeavesWithTheNewAttach() = runTest {
        openRequests = """[{"id":"srq-1","method":"approval","params":{"session_id":"rt-a","command":"ls","choices":["once","deny"]}}]"""
        live.value = listOf(Row("a", "waiting"))
        val setup = setup()
        setup.watcher.chats.first { it["a"]?.requests?.isNotEmpty() == true }
        openRequests = "[]"
        live.value = listOf(Row("a", "working"))
        reconnect(setup)
        assertTrue(setup.watcher.chats.value.getValue("a").requests.isEmpty())
    }

    @Test
    fun aReplyAfterANewSocketStillReachesAChatWhoseTurnEnded() = runTest {
        live.value = listOf(Row("a", "working"))
        val setup = setup()
        setup.watcher.chats.first { "a" in it }
        // Attached, not only being attached.
        runCurrent()
        live.value = listOf(Row("a", "idle"))
        reconnect(setup)

        assertEquals(setOf("a"), setup.watcher.chats.value.keys)
        assertTrue(setup.watcher.reply("a", "And then?"))
        val submit = sockets.last().sent.value.single { it.isCall("prompt.submit") }
        assertEquals("rt-a", submit["params"]!!.jsonObject["session_id"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun signingOutForgetsTheWatchedChats() = runTest {
        live.value = listOf(Row("a", "working"))
        val setup = setup()
        setup.watcher.chats.first { "a" in it }
        setup.connection.stop()
        setup.watcher.chats.first { it.isEmpty() }
    }

    @Test
    fun aChatWhoseStoredIdChangesKeepsItsOneAttach() = runTest {
        live.value = listOf(Row("a", "working"))
        val watcher = setup().watcher
        watcher.chats.first { "a" in it }
        // Compressing a chat goes on in a new stored session, on the same runtime.
        live.value = listOf(Row("a2", "working", runtime = "rt-a"))
        advanceTimeBy(10_001)
        runCurrent()
        assertEquals(setOf("a2"), watcher.chats.value.keys)
        assertEquals(1, sentMethods("session.activate").size)

        sockets.last().push(approval("srq-1", "rt-a"))
        watcher.chats.first { it["a2"]?.requests?.isNotEmpty() == true }
    }

    @Test
    fun aChatIsAttachedOnlyWhileFollowingAndAtOnceWhenItStarts() = runTest {
        live.value = listOf(Row("a", "working"))
        val watcher = setup().watcher
        // In sight, or with nothing to notify: attaching would only keep the session loaded on the gateway.
        watcher.follow(false)
        advanceTimeBy(10_001)
        runCurrent()
        assertTrue(sentMethods("session.activate").isEmpty())
        assertTrue(watcher.chats.value.isEmpty())

        // Out of sight: the chat is attached from the last list, without waiting for the next one.
        val asked = sentMethods("session.active_list").size
        watcher.follow(true)
        watcher.chats.first { "a" in it }
        assertEquals(asked, sentMethods("session.active_list").size)

        // Off again: it stays (there's no detach), and nothing new is attached.
        watcher.follow(false)
        live.value = listOf(Row("a", "working"), Row("b", "working"))
        advanceTimeBy(10_001)
        runCurrent()
        assertEquals(setOf("a"), watcher.chats.value.keys)
        assertEquals(1, sentMethods("session.activate").size)
    }

    @Test
    fun aTurnStartingInAWatchedChatIsTold() = runTest {
        live.value = listOf(Row("a", "working"))
        val watcher = setup().watcher
        // Attached with its turn running: one start, for a turn begun before this socket attached.
        assertEquals("a", watcher.turnStarts.first())
        watcher.chats.first { "a" in it }
        sockets.last().push(event("message.complete", "rt-a", """{"text":"Done.","status":"complete"}"""))
        runCurrent()

        val start = async { watcher.turnStarts.first() }
        runCurrent()
        sockets.last().push(event("message.start", "rt-a"))
        assertEquals("a", start.await())
    }

    @Test
    fun followingStoppedWhileAnAttachWaitsAttachesNoMore() = runTest {
        activateDelayMs = 1_000
        live.value = listOf(Row("a", "working"), Row("b", "working"), Row("c", "working"))
        val watcher = setup().watcher
        sockets.last().awaitSent { it.isCall("session.activate") }
        // Back in sight while the first attach is still out.
        watcher.follow(false)
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(1, sentMethods("session.activate").size)
    }

    @Test
    fun aTurnThatEndedBeforeTheAttachStillSaysItFinished() = runTest {
        // The list showed it running; by the time the attach went out, the turn was over.
        activateRunning = false
        live.value = listOf(Row("a", "working"))
        val watcher = setup().watcher
        assertEquals(WatchedTurnEnd("a", "Chat a", null, TurnOutcome.Complete, null), watcher.turnEnds.first())
    }

    // --- A gateway whose /api/ws runs on its session authority ---

    @Test
    fun onTheSessionAuthorityAChatRunningElsewhereIsAttachedWithSessionResume() = runTest {
        live.value = listOf(Row("a", "working"), Row("b", "idle"))
        val watcher = setup(authority = true).watcher
        assertEquals(setOf("a"), watcher.chats.first { it.isNotEmpty() }.keys)
        val resume = sentMethods("session.resume").single()["params"]!!.jsonObject
        assertEquals("rt-a", resume["session_id"]?.jsonPrimitive?.contentOrNull)
        assertEquals("true", resume["omit_messages"]?.jsonPrimitive?.contentOrNull)
        assertTrue(sentMethods("session.activate").isEmpty())
    }

    @Test
    fun onTheSessionAuthorityTheWatcherKeepsWatchingAndTakesTheOpenRequests() = runTest {
        openRequests = """[{"id":"srq-old","method":"approval","params":{"session_id":"rt-a","command":"ls","choices":["once","deny"]}}]"""
        live.value = listOf(Row("a", "waiting"), Row("b", "working"))
        val setup = setup(authority = true)
        val watcher = setup.watcher
        assertEquals(listOf("srq-old"), watcher.chats.first { it["a"]?.requests?.isNotEmpty() == true }.getValue("a").requests.map { it.id })
        // Not parked as a gateway that can't be watched on: the next chat is attached too, and its turn is heard.
        watcher.chats.first { "b" in it }
        assertEquals(listOf("rt-a", "rt-b"), sentMethods("session.resume").map { it["params"]!!.jsonObject["session_id"]!!.jsonPrimitive.content })
        val end = async { watcher.turnEnds.first() }
        runCurrent()
        sockets.last().push(event("message.complete", "rt-b", """{"text":"All done.","status":"complete"}"""))
        assertEquals(WatchedTurnEnd("b", "Chat b", "All done.", TurnOutcome.Complete, null), end.await())
    }

    @Test
    fun onTheSessionAuthorityTheWatchersResumeLeavesTheOpenChatAlone() = runTest {
        val setup = setup(authority = true)
        val open = setup.host.open(FakeGateway.URL, "open", null)
        open.state.first { it.runtimeSessionId == "rt-open" && it.historyLoaded }
        val before = open.state.value
        // Its reply carries another chat's replay epoch, sequence and half-streamed reply.
        live.value = listOf(Row("a", "working"))
        setup.watcher.chats.first { "a" in it }
        runCurrent()
        assertEquals(before.messages, open.state.value.messages)
        assertEquals(before.running, open.state.value.running)
        assertTrue(open.state.value.messages.none { it.toString().contains("Half a reply") })
    }

    @Test
    fun onAnOlderGatewayTheWatcherStillAttachesWithSessionActivate() = runTest {
        live.value = listOf(Row("a", "working"))
        val watcher = setup(authority = false).watcher
        watcher.chats.first { "a" in it }
        assertEquals(1, sentMethods("session.activate").size)
        assertTrue(sentMethods("session.resume").isEmpty())
    }

    private companion object {
        const val AUTHORITY_READY =
            """{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready","payload":{"session_authority":true}}}"""
    }
}
