package dev.hermeskotlin.core.chat

import dev.hermeskotlin.core.rpc.FakeGateway
import dev.hermeskotlin.core.rpc.event
import dev.hermeskotlin.core.rpc.isCall
import dev.hermeskotlin.core.rpc.json
import dev.hermeskotlin.core.rpc.param
import dev.hermeskotlin.core.sessions.SessionsApi
import dev.hermeskotlin.core.slash.SlashCommand
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Goal, loop and heartbeat buttons by gateway mode. A session-authority gateway of hermes-agent v0.22.1 serves
 * `session.control.read` (gateway/session_ancillary.py) but not `session.control`: it isn't in
 * `AuthorityConnection.handlers()` (gateway/session_controls.py), and a `session.` verb may not use the legacy
 * fallback (tui_gateway/ws_legacy_fallback.py). It answers -32601. There a button runs as its typed command through
 * `command.dispatch`, which takes only `session_id`, `profile`, `name` and `arg` (gateway/session_commands.py
 * `_parse`). v0.22.3's own `session.control` does the same (gateway/session_control_actions.py).
 */
class ChatSessionControlAuthorityTest {

    private val url = FakeGateway.URL

    private val authorityReady =
        """{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready","payload":{"session_authority":true}}}"""

    /** v0.22.1's frame for a method it has no handler for (gateway/session_controls.py). */
    private fun FakeGateway.Call.unknownMethod() = error(-32601, "unknown method: $method", reason = "unknown_method")

    /** The goal as `session.control.read` answers it: active until a `command.dispatch` pauses it. */
    private var paused = false

    private suspend fun setup(
        scope: kotlinx.coroutines.CoroutineScope,
        authority: Boolean,
        reconnects: Boolean = false,
        answer: (FakeGateway.Call) -> String?,
    ): Pair<FakeGateway, ChatSession> {
        val gw = FakeGateway(scope, reconnects).apply {
            if (authority) ready = { authorityReady }
            this.answer = { call ->
                answer(call) ?: when (call.method) {
                    "session.resume" -> """{"session_id":"rt1","running":false}"""
                    "session.control.read" ->
                        """{"control":${if (paused) ControlFixtures.GOAL_PAUSED else ControlFixtures.GOAL_ACTIVE}}"""
                    else -> "{}"
                }
            }
            http = { json("""{"session_id":"stored-1","messages":[]}""") }
            start(awaitConnected = true)
        }
        val chat = ChatSession(url, "stored-1", "Greeting", gw.connection, SessionsApi(gw.client), scope)
        chat.start()
        chat.state.first { it.control != null }
        return gw to chat
    }

    /** What `session.control.read` answers with in [newChat] and [openGoallessChat]; null: no goal yet. */
    private var goalRead: String? = null

    /**
     * A new chat on the session authority, whose first words are a typed command. With [publishes] the gateway also
     * sends `session.control.update` after `/goal`, as v0.22.3+ do (gateway/session_commands.py); v0.22.1 sends none.
     */
    private suspend fun newChat(
        scope: kotlinx.coroutines.CoroutineScope,
        authority: Boolean = true,
        publishes: Boolean = false,
    ): Pair<FakeGateway, ChatSession> {
        lateinit var gw: FakeGateway
        gw = FakeGateway(scope, reconnects = false).apply {
            if (authority) ready = { authorityReady }
            answer = { call ->
                when (call.method) {
                    "session.create" -> """{"session_id":"rt1","stored_session_id":"stored-1"}"""
                    "slash.exec" -> {
                        goalRead = ControlFixtures.GOAL_ACTIVE
                        if (publishes) gw.socket.push(event("session.control.update", "rt1", """{"control":$goalRead}"""))
                        """{"type":"send","message":"Tidy the docs.","display":"/goal Tidy the docs.","notice":"Goal set."}"""
                    }
                    "prompt.submit" -> """{"status":"streaming","user_row_id":7}"""
                    "session.control.read" -> """{"control":${goalRead ?: "null"}}"""
                    else -> "{}"
                }
            }
            http = { json("""{"session_id":"stored-1","messages":[]}""") }
            start(awaitConnected = true)
        }
        val chat = ChatSession(url, null, null, gw.connection, SessionsApi(gw.client), scope)
        chat.start()
        return gw to chat
    }

    @Test
    fun aGoalTypedInANewChatShowsWithoutAResume() = runTest {
        val (gw, chat) = newChat(backgroundScope)

        chat.runCommand(SlashCommand("goal", "Tidy the docs."))

        assertEquals("active", chat.state.first { it.control != null }.control?.goal?.status)
        assertTrue(gw.sent("session.resume").isEmpty())
    }

    @Test
    fun withNoUpdatesTheTypedGoalIsReadAgainAfterEachTurn() = runTest {
        val (gw, chat) = newChat(backgroundScope)
        chat.runCommand(SlashCommand("goal", "Tidy the docs."))
        chat.state.first { it.control != null }
        val reads = gw.sent("session.control.read").size

        // The judge paused the goal at the kickoff turn's end, unpublished on v0.22.1.
        goalRead = ControlFixtures.GOAL_PAUSED
        gw.socket.push(event("message.complete", "rt1", """{"text":"Step done","status":"complete"}"""))

        chat.state.first { it.control?.goal?.status == "paused" }
        assertEquals(reads + 1, gw.sent("session.control.read").size)
    }

    @Test
    fun withNoUpdatesTheJudgesVerdictIsRead() = runTest {
        val (gw, chat) = newChat(backgroundScope)
        chat.runCommand(SlashCommand("goal", "Tidy the docs."))
        chat.state.first { it.control != null }

        // v0.22.1 judges after the turn's end, then says so in a `status.update {kind: goal}` (gateway/run_goals.py).
        goalRead = ControlFixtures.GOAL_PAUSED
        gw.socket.push(event("status.update", "rt1", """{"kind":"goal","text":"Goal paused."}"""))

        chat.state.first { it.control?.goal?.status == "paused" }
    }

    @Test
    fun aGatewayThatPublishesUpdatesIsNotReadAfterEachTurn() = runTest {
        val (gw, chat) = newChat(backgroundScope, publishes = true)
        chat.runCommand(SlashCommand("goal", "Tidy the docs."))
        chat.state.first { it.control != null }

        gw.socket.push(event("message.complete", "rt1", """{"text":"Step done","status":"complete"}"""))
        // A later frame, so the turn's end has been handled when it lands.
        gw.socket.push(event("session.control.update", "rt1", """{"control":${ControlFixtures.GOAL_PAUSED}}"""))

        chat.state.first { it.control?.goal?.status == "paused" }
        // The read after the command goes out unless the update was handled before the command's answer; no other.
        assertTrue(gw.sent("session.control.read").size <= 1)
    }

    @Test
    fun aLegacyGatewaysTypedGoalIsNotReadAgain() = runTest {
        val (gw, chat) = newChat(backgroundScope, authority = false)

        chat.runCommand(SlashCommand("goal", "Tidy the docs."))
        gw.socket.awaitSent { it.isCall("prompt.submit") }

        assertTrue(gw.sent("session.control.read").isEmpty())
    }

    /** Waits for [count] calls in [held], failing rather than hanging when they don't come. */
    private suspend fun awaitHeld(held: List<FakeGateway.Call>, count: Int) {
        repeat(10_000) { if (held.size >= count) return else kotlinx.coroutines.yield() }
        assertEquals(count, held.size, "calls held")
    }

    /** A stored chat with no goal yet, open here; `session.control.read` answers with [goalRead]. */
    private suspend fun openGoallessChat(scope: kotlinx.coroutines.CoroutineScope): Pair<FakeGateway, ChatSession> {
        val gw = FakeGateway(scope, reconnects = false).apply {
            ready = { authorityReady }
            answer = { call ->
                when (call.method) {
                    "session.resume" -> """{"session_id":"rt1","running":false}"""
                    "session.control.read" -> """{"control":${goalRead ?: "null"}}"""
                    else -> "{}"
                }
            }
            http = { json("""{"session_id":"stored-1","messages":[]}""") }
            start(awaitConnected = true)
        }
        val chat = ChatSession(url, "stored-1", "Greeting", gw.connection, SessionsApi(gw.client), scope)
        chat.start()
        chat.state.first { it.runtimeSessionId == "rt1" }
        gw.socket.awaitSent { it.isCall("session.control.read") }
        return gw to chat
    }

    @Test
    fun aGoalAnotherClientSetsInAChatWithNoneShowsWhenItsKickoffStarts() = runTest {
        val (gw, chat) = openGoallessChat(backgroundScope)

        // Another client typed `/goal` and sent its kickoff; v0.22.1 publishes no update, only the turn.
        goalRead = ControlFixtures.GOAL_ACTIVE
        gw.socket.push(event("message.start", "rt1", "{}"))

        assertEquals("active", chat.state.first { it.control != null }.control?.goal?.status)
    }

    @Test
    fun aGoalAnotherClientSetsShowsByTheEndOfItsTurn() = runTest {
        val (gw, chat) = openGoallessChat(backgroundScope)
        val reads = gw.sent("session.control.read").size

        // The goal is set only after the other client's turn began, as a typed `/goal` mid-turn would be: the read at
        // the turn's start finds none.
        val held = mutableListOf<FakeGateway.Call>()
        val answers = gw.answer
        gw.answer = { call -> if (call.method == "session.control.read") null.also { held += call } else answers(call) }
        gw.socket.push(event("message.start", "rt1", "{}"))
        awaitHeld(held, 1)
        gw.socket.push(held[0].reply("""{"control":null}"""))
        gw.answer = answers
        goalRead = ControlFixtures.GOAL_ACTIVE
        gw.socket.push(event("message.complete", "rt1", """{"text":"Step done","status":"complete"}"""))

        assertEquals("active", chat.state.first { it.control != null }.control?.goal?.status)
        assertEquals(reads + 2, gw.sent("session.control.read").size)
    }

    @Test
    fun aLinkThatPublishedIsNotReadAfterACommandNorATurn() = runTest {
        val (gw, chat) = openGoallessChat(backgroundScope)
        gw.socket.push(event("session.control.update", "rt1", """{"control":${ControlFixtures.GOAL_ACTIVE}}"""))
        chat.state.first { it.control != null }
        val reads = gw.sent("session.control.read").size

        chat.runCommand(SlashCommand("goal", "pause"))
        gw.socket.push(event("message.start", "rt1", "{}"))
        gw.socket.push(event("message.complete", "rt1", """{"text":"Step done","status":"complete"}"""))
        // A later frame, so the turn has been handled when it lands.
        gw.socket.push(event("session.control.update", "rt1", """{"control":${ControlFixtures.GOAL_PAUSED}}"""))

        chat.state.first { it.control?.goal?.status == "paused" }
        assertEquals(reads, gw.sent("session.control.read").size)
    }

    @Test
    fun aGoalStatusInAChatWithNoGoalIsRead() = runTest {
        val (gw, chat) = openGoallessChat(backgroundScope)

        // A goal status means there is a goal, whoever set it.
        goalRead = ControlFixtures.GOAL_PAUSED
        gw.socket.push(event("status.update", "rt1", """{"kind":"goal","text":"Goal paused."}"""))

        assertEquals("paused", chat.state.first { it.control != null }.control?.goal?.status)
    }

    @Test
    fun ofTwoReadsOutAtOnceTheLaterOnesAnswerStays() = runTest {
        val (gw, chat) = openGoallessChat(backgroundScope)
        val held = mutableListOf<FakeGateway.Call>()
        gw.answer = { call -> if (call.method == "session.control.read") null.also { held += call } else "{}" }

        // Two of the judge's statuses close together each send a read; the verdict is in the later one only.
        gw.socket.push(event("status.update", "rt1", """{"kind":"goal","text":"Working."}"""))
        gw.socket.push(event("status.update", "rt1", """{"kind":"goal","text":"Goal paused."}"""))
        awaitHeld(held, 2)
        val (earlier, later) = held

        gw.socket.push(earlier.reply("""{"control":${ControlFixtures.GOAL_ACTIVE}}"""))
        chat.state.first { it.control?.goal?.status == "active" }
        gw.socket.push(later.reply("""{"control":${ControlFixtures.GOAL_PAUSED}}"""))

        chat.state.first { it.control?.goal?.status == "paused" }
    }

    /** Opening a stored chat that has a goal shows it with no send: `session.control.read` follows the resume. */
    @Test
    fun openingAStoredChatShowsItsGoalWithoutASend() = runTest {
        val gw = FakeGateway(backgroundScope, reconnects = false).apply {
            ready = {
                """{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready","payload":{"change_events":true,"heartbeat":true,"replay_epoch":"e1","session_authority":true}}}"""
            }
            answer = { call ->
                when (call.method) {
                    "session.resume" -> """{"session_id":"stored-1","stored_session_id":"stored-1",
                        "messages":[{"role":"user","content":"hello","row_id":43},
                                    {"role":"assistant","content":"Test reply.","row_id":44}],
                        "message_count":2,"running":false,"authority_epoch":4,"replay_epoch":"e0","last_sequence":14,
                        "subscription_id":"sub-1","revision":0,"execution_generation":0,"pending":[],"prompts":[],
                        "info":{"source":"gui","model":"test-model","lazy":false,"desktop_protocol":"hermes-gateway-v1",
                                "profile_name":"default","reasoning_effort":"","reasoning_effort_wire":"","service_tier":"",
                                "fast":false,"title":"Greeting","yolo":false}}"""
                    "session.control.read" -> """{"control":${ControlFixtures.GOAL_ACTIVE}}"""
                    else -> "{}"
                }
            }
            http = { json("""{"session_id":"stored-1","messages":[]}""") }
            start(awaitConnected = true)
        }
        val chat = ChatSession(url, "stored-1", "Greeting", gw.connection, SessionsApi(gw.client), backgroundScope)
        chat.start()

        chat.state.first { it.runtimeSessionId == "stored-1" && it.historyLoaded }
        gw.socket.awaitSent { it.isCall("session.control.read") }

        assertEquals("active", chat.state.first { it.control != null }.control?.goal?.status)
        assertTrue(gw.sent("prompt.submit").isEmpty())
    }

    @Test
    fun legacyGatewaysKeepTheirSessionControlCallAndItsError() = runTest {
        val (gw, chat) = setup(backgroundScope, authority = false) { call ->
            if (call.method == "session.control") call.unknownMethod() else null
        }

        assertEquals("unknown method: session.control", chat.runControl(ControlAction.GoalPause))
        assertTrue(gw.sent("command.dispatch").isEmpty())
    }

    @Test
    fun anAuthorityGatewayWithSessionControlIsAskedDirectly() = runTest {
        val (gw, chat) = setup(backgroundScope, authority = true) { call ->
            if (call.method == "session.control") {
                """{"control":${ControlFixtures.GOAL_PAUSED},"dispatch":{"type":"exec","output":"Paused"}}"""
            } else null
        }

        assertNull(chat.runControl(ControlAction.GoalPause))

        assertEquals("goal.pause", gw.sent("session.control").single().param("action"))
        assertTrue(gw.sent("command.dispatch").isEmpty())
        assertEquals("paused", chat.state.value.control?.goal?.status)
    }

    @Test
    fun withoutSessionControlAButtonRunsAsItsCommandAndTheStateIsReadAgain() = runTest {
        val (gw, chat) = setup(backgroundScope, authority = true) { call ->
            when (call.method) {
                "session.control" -> call.unknownMethod()
                "command.dispatch" -> if (call.param("name") == "goal") {
                    """{"type":"exec","output":"⏸ Goal paused"}""".also { paused = true }
                } else {
                    """{"type":"exec","output":""}"""
                }
                else -> null
            }
        }

        assertNull(chat.runControl(ControlAction.GoalPause))

        val dispatch = gw.sent("command.dispatch").single()
        // Only what v0.22.1's `_parse` allows, every value a string.
        assertEquals(setOf("session_id", "name", "arg"), dispatch["params"]!!.jsonObject.keys)
        assertEquals("rt1", dispatch.param("session_id"))
        assertEquals("goal", dispatch.param("name"))
        assertEquals("pause", dispatch.param("arg"))
        assertEquals("paused", chat.state.first { it.control?.goal?.status == "paused" }.control?.goal?.status)
        // Once when the chat opened, then before and after the command: no update says what it changed.
        assertEquals(3, gw.sent("session.control.read").size)

        // That link has no `session.control`, so the next tap goes straight to the command.
        assertNull(chat.runControl(ControlAction.LoopStop))
        assertEquals(1, gw.sent("session.control").size)
        val second = gw.sent("command.dispatch").last()
        assertEquals("loop", second.param("name"))
        assertEquals("stop", second.param("arg"))
    }

    @Test
    fun aNewLinkAsksForSessionControlAgain() = runTest {
        val (gw, chat) = setup(backgroundScope, authority = true, reconnects = true) { call ->
            when (call.method) {
                "session.control" -> call.unknownMethod()
                "command.dispatch" -> """{"type":"exec","output":""}"""
                else -> null
            }
        }
        assertNull(chat.runControl(ControlAction.HeartbeatPause))

        // Updating a gateway restarts it, so a new link may well serve the method now.
        gw.socket.serverClose(1006)
        chat.state.first { it.runtimeSessionId == null }
        chat.state.first { it.runtimeSessionId == "rt1" }
        assertNull(chat.runControl(ControlAction.HeartbeatPause))

        assertEquals(2, gw.sent("session.control").size)
        assertEquals(listOf("pause", "pause"), gw.sent("command.dispatch").map { it.param("arg") })
        assertEquals(listOf("heartbeat", "heartbeat"), gw.sent("command.dispatch").map { it.param("name") })
    }

    @Test
    fun aResumedGoalsKickoffFromTheCommandGoesOut() = runTest {
        val (gw, chat) = setup(backgroundScope, authority = true) { call ->
            when (call.method) {
                "session.control" -> call.unknownMethod()
                "command.dispatch" -> """{"type":"send","message":"Kick off the goal work now.","display":"/goal resume"}"""
                "prompt.submit" -> """{"status":"streaming","user_row_id":7}"""
                else -> null
            }
        }

        assertNull(chat.runControl(ControlAction.GoalResume))

        assertEquals("resume", gw.sent("command.dispatch").single().param("arg"))
        assertEquals("Kick off the goal work now.", gw.sent("prompt.submit").single().param("text"))
        assertEquals("/goal resume", assertIs<ChatMessage.User>(chat.state.value.messages.last()).text)
    }

    @Test
    fun withoutSessionControlTheGoalIsReadAgainAfterATurn() = runTest {
        val (gw, chat) = setup(backgroundScope, authority = true) { call ->
            when (call.method) {
                "session.control" -> call.unknownMethod()
                "command.dispatch" -> """{"type":"exec","output":""}"""
                else -> null
            }
        }
        assertNull(chat.runControl(ControlAction.GoalUnwait))
        val reads = gw.sent("session.control.read").size

        // The goal's judge paused it at the turn's end; v0.22.1 sends no `session.control.update` for that.
        paused = true
        gw.socket.push(event("message.complete", "rt1", """{"text":"Step done","status":"complete"}"""))

        chat.state.first { it.control?.goal?.status == "paused" }
        assertEquals(reads + 1, gw.sent("session.control.read").size)
    }

    @Test
    fun subgoalButtonsRunAsTheSubgoalCommand() = runTest {
        val (gw, chat) = setup(backgroundScope, authority = true) { call ->
            when (call.method) {
                "session.control" -> call.unknownMethod()
                "command.dispatch" -> """{"type":"exec","output":""}"""
                else -> null
            }
        }

        assertNull(chat.runControl(ControlAction.SubgoalAdd, text = "Pin the wire contract"))
        assertNull(chat.runControl(ControlAction.SubgoalRemove, index = 2))
        assertNull(chat.runControl(ControlAction.SubgoalClear))
        assertNull(chat.runControl(ControlAction.GoalUnwait))

        val calls = gw.sent("command.dispatch")
        assertEquals(listOf("subgoal", "subgoal", "subgoal", "goal"), calls.map { it.param("name") })
        assertEquals(listOf("Pin the wire contract", "remove 2", "clear", "unwait"), calls.map { it.param("arg") })
    }

    @Test
    fun aSubgoalTheCommandWouldReadAsItsOwnVerbIsNotSent() = runTest {
        val (gw, chat) = setup(backgroundScope, authority = true) { call ->
            when (call.method) {
                "session.control" -> call.unknownMethod()
                "command.dispatch" -> """{"type":"exec","output":""}"""
                else -> null
            }
        }

        // `/subgoal clear the cache` would clear every sub-goal on v0.22.1 (gateway/slash_commands_goals.py).
        val error = chat.runControl(ControlAction.SubgoalAdd, text = "Clear the cache")

        assertEquals(
            "This gateway would read a sub-goal starting with \"remove\" or \"clear\" as a command. Reword it, or update Hermes.",
            error,
        )
        assertTrue(gw.sent("command.dispatch").isEmpty())
    }

    @Test
    fun aSubgoalStartingWithAVerbAndANoBreakSpaceIsNotSentEither() = runTest {
        val (gw, chat) = setup(backgroundScope, authority = true) { call ->
            when (call.method) {
                "session.control" -> call.unknownMethod()
                "command.dispatch" -> """{"type":"exec","output":""}"""
                else -> null
            }
        }

        // Python's `split(None, 1)` there ends the word at any Unicode space, so this too reads as `clear`.
        val error = chat.runControl(ControlAction.SubgoalAdd, text = "Clear the cache")

        assertEquals(
            "This gateway would read a sub-goal starting with \"remove\" or \"clear\" as a command. Reword it, or update Hermes.",
            error,
        )
        assertTrue(gw.sent("command.dispatch").isEmpty())
    }

    @Test
    fun aSubgoalWithNoTextIsNotSent() = runTest {
        val (gw, chat) = setup(backgroundScope, authority = true) { call ->
            when (call.method) {
                "session.control" -> call.unknownMethod()
                "command.dispatch" -> """{"type":"exec","output":""}"""
                else -> null
            }
        }

        // A bare `/subgoal` answers with the goal's status, which would pass for an added sub-goal.
        assertEquals("Write the sub-goal first.", chat.runControl(ControlAction.SubgoalAdd, text = "  "))
        assertTrue(gw.sent("command.dispatch").isEmpty())
    }

    @Test
    fun aCommandThatRefusesInItsOutputShowsWhy() = runTest {
        val (_, chat) = setup(backgroundScope, authority = true) { call ->
            when (call.method) {
                "session.control" -> call.unknownMethod()
                // v0.22.1's `/subgoal remove` for a number another client already took away: text, no error.
                "command.dispatch" -> """{"type":"exec","output":"No sub-goal 3 (there are 2)."}"""
                else -> null
            }
        }

        assertEquals("No sub-goal 3 (there are 2).", chat.runControl(ControlAction.SubgoalRemove, index = 3))
        assertEquals("active", chat.state.value.control?.goal?.status)
    }

    @Test
    fun aRefusalIsShownWhenTheStateOnScreenWasStale() = runTest {
        val (_, chat) = setup(backgroundScope, authority = true) { call ->
            when (call.method) {
                "session.control" -> call.unknownMethod()
                "command.dispatch" -> """{"type":"exec","output":"No sub-goal 3 (there are 2)."}"""
                else -> null
            }
        }
        // Another client changed it; v0.22.1 sends no update, so this one still shows the old state.
        paused = true

        assertEquals("No sub-goal 3 (there are 2).", chat.runControl(ControlAction.SubgoalRemove, index = 3))
        assertEquals("paused", chat.state.value.control?.goal?.status)
    }

    @Test
    fun aSubgoalOfOnlyANextLineIsNotSent() = runTest {
        val (gw, chat) = setup(backgroundScope, authority = true) { call ->
            when (call.method) {
                "session.control" -> call.unknownMethod()
                "command.dispatch" -> """{"type":"exec","output":""}"""
                else -> null
            }
        }

        // Python strips U+0085 too, so this would go out as a bare `/subgoal`.
        assertEquals("Write the sub-goal first.", chat.runControl(ControlAction.SubgoalAdd, text = "\u0085"))
        assertTrue(gw.sent("command.dispatch").isEmpty())
    }

    @Test
    fun whenTheGatewayHasNoSuchCommandTheSheetSaysToUpdateHermes() = runTest {
        val (gw, chat) = setup(backgroundScope, authority = true) { call ->
            when (call.method) {
                "session.control" -> call.unknownMethod()
                "command.dispatch" -> call.error(4001, "unsupported_command", reason = "unsupported_command")
                else -> null
            }
        }
        gw.socket.awaitSent { it.isCall("session.control.read") }

        val error = chat.runControl(ControlAction.GoalPause)

        assertEquals("Couldn't pause the goal on this gateway. Update Hermes to use goals.", error)
        assertEquals("active", chat.state.value.control?.goal?.status)
    }

    @Test
    fun anotherRefusalIsNotPutDownToTheVersion() = runTest {
        val (_, chat) = setup(backgroundScope, authority = true) { call ->
            when (call.method) {
                "session.control" -> call.unknownMethod()
                "command.dispatch" -> call.error(4001, "permission_denied", reason = "permission_denied")
                else -> null
            }
        }

        assertEquals("Couldn't pause the goal: the gateway didn't allow it.", chat.runControl(ControlAction.GoalPause))
    }
}
