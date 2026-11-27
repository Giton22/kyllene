package dev.hermeskotlin.core.chat

import dev.hermeskotlin.core.cache.OfflineCache
import dev.hermeskotlin.core.connection.ConnectionState
import dev.hermeskotlin.core.connection.GatewayConnection
import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.network.ApiResult
import dev.hermeskotlin.core.network.errorMessage
import dev.hermeskotlin.core.rpc.GatewayEvent
import dev.hermeskotlin.core.rpc.JsonRpcClient
import dev.hermeskotlin.core.rpc.RpcException
import dev.hermeskotlin.core.rpc.RpcTimeoutException
import dev.hermeskotlin.core.sessions.SessionMessage
import dev.hermeskotlin.core.sessions.SessionsApi
import dev.hermeskotlin.core.sessions.distinctCommentary
import dev.hermeskotlin.core.sessions.putStartInstruction
import dev.hermeskotlin.core.slash.SlashCommand
import dev.hermeskotlin.core.slash.SlashResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first
import dev.hermeskotlin.core.models.ReasoningEffort
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * One chat over the gateway, the way Hermes Desktop drives it: the stored transcript comes over REST,
 * `session.resume {omit_messages}` attaches a live runtime session, and the turn streams in as events.
 * A new chat ([initialStoredId] null) is created with `session.create` on the first send.
 *
 * Re-attaches after every reconnect (runtime ids belong to the socket). What happened while we were away is
 * replayed from the gateway's event ring (`session.events.since`) when it still holds it; otherwise the
 * transcript is refetched, since a turn may have finished meanwhile.
 *
 * The transcript loads a page at a time: the newest on opening, older ones through [loadOlder].
 *
 * [profile] names the Hermes profile the chat belongs to (null: the gateway's launch profile). Only
 * create, resume and REST calls carry it; the gateway scopes the rest by the runtime session.
 */
class ChatSession(
    val gateway: GatewayUrl,
    initialStoredId: String?,
    initialTitle: String?,
    private val connection: GatewayConnection,
    private val sessions: SessionsApi,
    private val scope: CoroutineScope,
    /** The profile the chat runs in; null for the gateway's launch profile. */
    val profile: String? = null,
    /** Where flagged tool output is remembered, since the transcript forgets it. */
    private val risks: ToolRiskStore? = null,
    /** Dates what this device sends and sees finish; live events carry no time of their own. */
    private val clock: Clock = Clock.System,
    /** For a new chat: the working folder it starts in (a project's), or null for the profile's default. See [startIn]. */
    private var cwd: String? = null,
    /** The device's copy of the transcript, shown while the gateway can't be read. */
    private val cache: OfflineCache? = null,
    /** `/btw` and `/bg` cards from when this chat was last open ([ChatHost] keeps them across a switch). */
    sideTasks: List<ChatMessage.Command> = emptyList(),
    /** For a new chat: what it tells the agent first, out of sight (see `putStartInstruction`); read when it's created. */
    private val startInstruction: () -> String? = { null },
    /** The host's account/profile lifetime when this source was created, even if a collector starts later. */
    val ownerVersion: Long = 0,
) {
    private fun nowSeconds(): Double = clock.now().toEpochMilliseconds() / 1000.0

    private val _state = MutableStateFlow(
        ChatState(storedSessionId = initialStoredId, title = initialTitle, messages = sideTasks.map(::reopenedSideTask)),
    )
    val state: StateFlow<ChatState> = _state.asStateFlow()

    /**
     * The stored row exists only after the first accepted prompt, or once a chat seeded with a starting instruction is
     * created; until then a reconnect re-creates.
     */
    private var rowExists = initialStoredId != null
    private val attachMutex = Mutex()

    /** Held by a [submit] from its uploads until its prompt is settled; see there. */
    private val sendMutex = Mutex()
    private val reactionMutex = Mutex()

    /** The stored rows of replies whose reaction is being saved now. */
    private val reacting = MutableStateFlow(emptySet<Long>())

    /**
     * The socket [ChatState.runtimeSessionId] was attached on. Runtime ids belong to the
     * socket: after a reconnect or a relogin the cached id must not go out on the new
     * socket (the gateway has no subscription for it there and refuses the send as a
     * stranger's). Written wherever the id is (re-)attached, read by [ensureAttached].
     */
    @kotlin.concurrent.Volatile
    private var attachedClient: JsonRpcClient? = null

    /**
     * The gateway's `execution_generation` for the attached runtime session (captured from `session.resume`,
     * `session.create`, `prompt.submit`, and turn events). Required by canonical session-authority controls.
     */
    @kotlin.concurrent.Volatile
    private var executionGeneration: Long? = null

    /** The session authority's `confirm` token for the last guarded pick: model, provider, token. */
    private var modelConfirm: Triple<String, String, String>? = null

    /**
     * The session authority's `revision` for the attached session (from `session.resume`, `session.create`,
     * `session.info` and `session.updated`), which a `session.mutate` presents as `expected_revision`.
     */
    @kotlin.concurrent.Volatile
    private var revision: Long? = null

    /**
     * Prompts this client submitted whose turn hasn't started yet. A `message.start` beyond them is a
     * turn another client (Desktop, CLI, a messaging platform) started: the gateway sends no event with
     * that prompt, so the transcript is refetched to show it.
     */
    private var ownTurnsPending = 0
    private var foreignTurn = false

    /** A [rewind] cut the transcript; the stored rows replace the live copy once its turn ends. */
    private var rewound = false
    private var jobs: List<Job> = emptyList()

    /** What went out for each bubble marked by a [SendCheck], by key: a skill's bubble shows less than was sent. */
    private val unsettled = mutableMapOf<String, UnsettledPrompt>()

    /**
     * Requests for a runtime id we don't know yet: one can land between the gateway answering
     * `session.resume`/`session.create` and us reading that reply. Claimed once the id is known.
     */
    private val unclaimed = ArrayDeque<Pair<String, InputRequest>>()

    /** The stored rows loaded so far, oldest first: the newest page and any older ones scrolled back to. */
    private var rows: List<SessionMessage> = emptyList()

    /** The session the newest page was read from; prompts stored under an older one can't be cut at. */
    private var rowsSessionId: String? = null

    /** Rows an older page found already loaded, as the transcript grew since: the next one reads past them. */
    private var olderSkew = 0
    private val historyMutex = Mutex()

    /**
     * Where the event stream stands, to pick it up after a drop: the seq of the last event applied, the runtime
     * session it counts in and the `replay_epoch` numbering it. Null seq: nothing to resume from. The epoch is the
     * gateway run's (`gateway.ready`) on the legacy gateway, and the session's own on the session authority, from
     * `session.resume` / `session.create` and each event (see [EventReplay]).
     */
    private var lastSeq: Long? = null
    private var seqRuntime: String? = null
    private var seqEpoch: String? = null

    /** Live events held back while the gap before them is filled; null when nothing is being caught up. */
    private var held: MutableList<GatewayEvent>? = null

    /** Frames received after the gateway resumed a chat, while its transcript is still being read. */
    private class PendingResume(val client: JsonRpcClient, val runtimeId: String) {
        val events = mutableListOf<GatewayEvent>()
    }

    private var pendingResume: PendingResume? = null

    /** The socket waiting for a replay-gap resume to start. */
    private var pendingGapClient: JsonRpcClient? = null

    /**
     * The runtime taken up again after a `session.replay_gap`, and the frames of its new subscription, held until the
     * resume is in: while it attaches the state names no runtime, so they'd be dropped (a `message.complete` with
     * them). Null when none is being taken up.
     */
    private var gapRuntime: String? = null
    private var gapHeld: MutableList<GatewayEvent>? = null

    /** Orders applying events, holding them and filling gaps, which run on different threads. Never held across I/O. */
    private val streamMutex = Mutex()

    /** The running turn's reply so far as the last `session.resume` had it, for when the gap can't be filled. */
    @kotlin.concurrent.Volatile private var inflightText: String? = null

    /** When the turn running at the last `session.resume` started (gateway clock); null when none ran or it's unknown. */
    @kotlin.concurrent.Volatile private var turnStartedAt: Double? = null

    /**
     * The running turn's live reply, as the gateway's copy named it at the last `session.resume`. It stays the turn's
     * once text streams on past that copy, so the saved steps still fold into it when the list is rebuilt.
     */
    @kotlin.concurrent.Volatile private var turnReplyKey: String? = null

    /** The [turnStartedAt] the loaded rows were last laid out with, so they're laid out again once it's known. */
    @kotlin.concurrent.Volatile private var rowsLaidOutFor: Double? = null

    fun start() {
        if (jobs.isNotEmpty()) return
        jobs = listOf(
            scope.launch { if (rowExists) loadHistory() else _state.update { it.copy(historyLoaded = true) } },
            scope.launch { followConnection() },
            scope.launch { followReactionSupport() },
            scope.launch { followEvents() },
            scope.launch { followServerRequests() },
        ) + _state.value.messages.mapNotNull { card ->
            (card as? ChatMessage.Command)?.takeIf { it.running && it.isBackground }?.taskId?.let { scope.launch { followBackgroundTask(it) } }
        }
    }

    fun stop() {
        jobs.forEach { it.cancel() }
        jobs = emptyList()
    }

    /**
     * Sends a prompt with any [attachments], creating or attaching the live session first and uploading
     * the attachments to it before `prompt.submit`. Returns false if it was not accepted. The bubble
     * shows [display] instead of the text when given (a skill's body is for the model, not the chat).
     *
     * Mid-turn, the gateway folds text into the running turn (Desktop's stop-and-correct) unless [queue]
     * asks for it to run as the next turn instead.
     *
     * A prompt that went out without a reply may be running, so the stored transcript decides before
     * this returns (see [settleUnanswered]).
     */
    suspend fun send(
        text: String,
        attachments: List<OutgoingAttachment> = emptyList(),
        display: String? = null,
        queue: Boolean = false,
    ): Boolean = submit(text, attachments, display, queue) == SendOutcome.Sent

    /**
     * [send], telling a prompt that's gone apart from one whose bubble stays [SendOutcome.Unsettled].
     *
     * A [voiceLive] prompt is a GPT-Live delegation: the gateway adds a note that the text was spoken and the
     * reply will be, with the recent spoken exchange it refers to (`surface: voice-live`, `voice_context`).
     */
    suspend fun submit(
        text: String,
        attachments: List<OutgoingAttachment> = emptyList(),
        display: String? = null,
        queue: Boolean = false,
        voiceLive: VoiceLiveTurn? = null,
    ): SendOutcome = submitPrompt(text, attachments, display, queue, voiceLive, resent = emptyList())

    /**
     * Sets or clears the user's reaction on the stored reply [replyKey], by its row: the newest row can change
     * meanwhile. Reactions go one at a time; another tap on the same reply (by its row: a reload can rename a live
     * reply meanwhile) while its reaction is still being saved is ignored, as the same emoji again would take it
     * off after its follow-up has gone.
     */
    suspend fun react(replyKey: String, reaction: ReplyReaction, stillOpen: () -> Boolean) {
        val reply = _state.value.messages.filterIsInstance<ChatMessage.Assistant>().firstOrNull { it.key == replyKey }
            ?.takeUnless { it.streaming } ?: return
        val rowId = reply.reactionRowId ?: return
        var claimed = false
        reacting.update { rows -> (rowId !in rows).also { claimed = it }.let { if (it) rows + rowId else rows } }
        if (!claimed) return
        try {
            reactionMutex.withLock { reactToStoredReply(reply, rowId, reaction, stillOpen) }
        } finally {
            reacting.update { it - rowId }
        }
    }

    private suspend fun reactToStoredReply(
        reply: ChatMessage.Assistant,
        rowId: Long,
        reaction: ReplyReaction,
        stillOpen: () -> Boolean,
    ) {
        try {
            if (!stillOpen()) return
            val client = connectedClient() ?: throw RpcException(0, "Connect to the gateway to react.")
            if (!client.replyReactionsSupported.value) return
            val runtimeId = ensureAttached(client)
            // By its row: a reload meanwhile renames a live reply's key but not its row.
            val shown = _state.value.messages.firstOrNull {
                it is ChatMessage.Assistant && !it.streaming && it.reactionRowId == rowId
            } as? ChatMessage.Assistant
            if (!stillOpen() || connectedClient() !== client || shown == null) return
            // The same emoji again clears it on the gateway, so what happens follows the gateway's state, not the
            // screen's. One set where the screen showed none, as on another client not yet reloaded here, is set again.
            val setting = shown.userReaction != reaction.emoji
            suspend fun send() = client.request("message.react", buildJsonObject {
                put("session_id", runtimeId)
                put("row_id", rowId)
                put("emoji", reaction.emoji)
            }) as? JsonObject
            val result = try {
                send().let { first ->
                    val cleared = userReactionEmoji(first?.get("reactions")) != reaction.emoji
                    if (setting && cleared && first?.get("reactions") is JsonArray) send() else first
                }
            } catch (e: RpcException) {
                if (e.code != METHOD_NOT_FOUND) throw e
                client.markReplyReactionsUnsupported()
                if (stillOpen()) _state.update { it.copy(error = "This gateway doesn't support reactions. Update Hermes to use them.") }
                return
            }
            val returned = result?.get("reactions") as? JsonArray
                ?: throw RpcException(0, "The gateway did not confirm the reaction.")
            val emoji = userReactionEmoji(returned)
            historyMutex.withLock {
                // Saved on the gateway by its stored row, which a re-attach or reconnect meanwhile doesn't change.
                if (!stillOpen()) return
                rows = rows.map { row ->
                    if (row.id == rowId) {
                        val metadata = displayMetadataObject(row.displayMetadata).orEmpty()
                        row.copy(displayMetadata = JsonObject(metadata + ("reactions" to returned)))
                    } else row
                }
                _state.update { state ->
                    state.copy(messages = state.messages.map {
                        if (it is ChatMessage.Assistant && it.reactionRowId == rowId) it.copy(userReaction = emoji) else it
                    })
                }
            }
            if (cache != null) scope.launch { refreshSavedCopy() }
            if (emoji == reaction.emoji) reaction.prompt(reply.text)?.let { prompt ->
                submitPrompt(
                    prompt, emptyList(), display = null, queue = _state.value.running, voiceLive = null,
                    resent = emptyList(), keepRejected = true,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (stillOpen()) _state.update { it.copy(error = e.message ?: "Could not save the reaction.") }
        }
    }

    /** [submit], with [resent]: the `@file:` references, and their cards, of a prompt sent again ([resend]). */
    private suspend fun submitPrompt(
        text: String,
        attachments: List<OutgoingAttachment>,
        display: String?,
        queue: Boolean,
        voiceLive: VoiceLiveTurn?,
        resent: List<Pair<String, ShownAttachment>>,
        resentImages: List<Pair<StagedImage, ShownAttachment>> = emptyList(),
        keepRejected: Boolean = false,
    ): SendOutcome {
        val trimmed = text.trim()
        if (trimmed.isEmpty() && attachments.isEmpty() && resent.isEmpty() && resentImages.isEmpty()) return SendOutcome.NotSent
        // Desktop's fallback, so an image-only prompt still asks something.
        val visible = trimmed.ifEmpty {
            if (attachments.any { it.kind == AttachmentKind.Image } || resentImages.isNotEmpty()) IMAGE_ONLY_PROMPT else ""
        }
        val copiesBefore = if (keepRejected) _state.value.messages.count {
            it is ChatMessage.User && !it.pending && it.check == null && it.text.contains(visible)
        } else 0
        val key = "local-${_state.value.keySeq}"
        _state.update {
            it.copy(
                messages = it.messages + ChatMessage.User(
                    key,
                    display ?: visible,
                    pending = true,
                    attachments = resent.map { (_, shown) -> shown } + resentImages.map { (_, shown) -> shown } + attachments.map { a -> a.toShown() },
                    timestamp = nowSeconds(),
                ),
                keySeq = it.keySeq + 1,
                error = null,
                refused = null,
            )
        }
        // One prompt's uploads and submit at a time: images queued on the session ride with the next
        // prompt.submit, so another send in between (while a large file goes up) would take them. Held
        // until this one is settled, so the next one sees whether a turn is running.
        val waited = !sendMutex.tryLock()
        if (waited) sendMutex.lock()
        // Sent while nothing ran, then held behind a prompt that started a turn: it runs after that turn,
        // as it would have alone, rather than going in as a correction to a turn it wasn't meant for.
        val queued = queue || (waited && _state.value.running)
        // Counted before submitting: the turn's message.start can arrive before the prompt.submit reply.
        ownTurnsPending++
        // Images queued on the session: if the prompt never goes out they would ride along
        // with the next one, so a failed send takes them back.
        val queuedImages = mutableListOf<String>()
        var uploadClient: JsonRpcClient? = null
        var uploadRuntimeId: String? = null
        // Only a prompt that starts a turn is written to the transcript at once; a correction or a
        // queued one isn't, so for those the transcript can't say whether it arrived.
        val startsTurn = !queued && !_state.value.running
        // The prompts shown, which the transcript has too; not countable when the transcript failed to load.
        val promptsBefore = _state.value.takeIf { it.historyRead }
            ?.messages?.count { it is ChatMessage.User && !it.pending && it.check == null }
        var submitted = false
        // The files' references in the workspace, each with its card, kept for a resend.
        val files = resent.toMutableList()
        // Staged images (path + mime) for session-authority prompt.submit `attachments`, kept for a resend.
        val stagedImages = resentImages.toMutableList()
        return try {
            // The composer holds files to this size; checked before any goes, so none is uploaded for nothing.
            attachments.firstOrNull { it.size > OutgoingAttachment.MAX_BYTES }?.let {
                throw RpcException(JsonRpcClient.MESSAGE_TOO_LARGE, "${it.name} is larger than ${OutgoingAttachment.MAX_SIZE}, too large to send.")
            }
            val client = connectedClient() ?: throw RpcException(0, "Not connected to the gateway. Your message will need resending.")
            val runtimeId = ensureAttached(client)
            uploadClient = client
            uploadRuntimeId = runtimeId
            attachments.forEach { a -> upload(client, runtimeId, a, queuedImages, stagedImages, key)?.let { files += it to a.toShown() } }
            val refs = files.map { (ref, _) -> ref }
            // The session authority answers before the prompt's row is written; its turn's end names the row by this id.
            // On the bubble before it goes: a short turn can end before this reads the reply.
            val submissionId = if (client.sessionAuthority) newRequestId() else null
            if (submissionId != null) {
                _state.update { it.copy(messages = it.messages.updateUser(key) { u -> u.copy(submissionId = submissionId) }) }
            }
            submitted = true
            val result = client.request(
                "prompt.submit",
                buildJsonObject {
                    put("session_id", runtimeId)
                    submissionId?.let { put("submission_id", it) }
                    // Like Desktop: the file references first, then what was typed.
                    put("text", (refs + visible).filter { it.isNotEmpty() }.joinToString("\n\n"))
                    if (client.sessionAuthority && stagedImages.isNotEmpty()) {
                        putJsonArray("attachments") {
                            stagedImages.forEach { (image, _) ->
                                addJsonObject {
                                    put("path", image.path)
                                    put("mime", image.mime)
                                }
                            }
                        }
                    }
                    if (queued) put("queued", true)
                    if (voiceLive != null) {
                        put("surface", "voice-live")
                        if (voiceLive.context.isNotBlank()) put("voice_context", voiceLive.context)
                    }
                },
            ) as? JsonObject
            result.long("execution_generation")?.let { executionGeneration = it }
            rowExists = true
            val status = result.string("status")
            // Steering or redirecting folds the text into the running turn instead of starting one.
            if (status !in TURN_STARTING_STATUSES) ownTurnsPending = (ownTurnsPending - 1).coerceAtLeast(0)
            // The row written for it, so it can be regenerated or edited before the transcript is read again.
            val rowId = result.long("user_row_id")
            val sentText = visible.takeIf { attachments.isEmpty() && resent.isEmpty() && display == null && it.isNotEmpty() }
            // The session authority's id for it, which its turn's events carry.
            val admissionId = result.string("admission_id")?.takeIf { client.sessionAuthority }
            _state.update { state ->
                // The session authority answers every prompt "queued", one that starts a turn too. Marked so, a prompt
                // whose turn began before this read the reply would wait for a start that has already come.
                val started = admissionId != null && admissionId in state.startedAdmissions
                val held = status == "queued" && !(client.sessionAuthority && startsTurn) && !started
                val sent = state.copy(
                    // A turn whose start was read first has said whether it still runs, its end too.
                    running = state.running || !started,
                    messages = state.messages.updateUser(key) {
                        it.copy(pending = false, queued = held, rowId = rowId ?: it.rowId, sentText = sentText, admissionId = admissionId)
                    },
                )
                // What streamed before the correction stays above it; the rest of the turn continues below.
                if (status in CORRECTION_STATUSES) sent.sealReplyBefore(key, sameResponse = status != "redirected") else sent
            }
            SendOutcome.Sent
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ownTurnsPending = (ownTurnsPending - 1).coerceAtLeast(0)
            // An error reply is the gateway turning it down; anything else after it went out is no verdict.
            if (submitted && e !is RpcException) {
                val arrived = if (startsTurn) findInTranscript(visible, key, promptsBefore) else null
                val prompt = UnsettledPrompt(text, display, startsTurn, files.toList(), stagedImages.toList(), keepRejected, copiesBefore)
                return settleUnanswered(key, arrived, prompt) {
                    detach(uploadClient, uploadRuntimeId, queuedImages)
                }
            }
            detach(uploadClient, uploadRuntimeId, queuedImages)
            val refused = SessionRefusal.of(e)
            val error = if (e is StaleResume) "The connection changed before the message could be sent."
            else e.message ?: "Couldn't send the message."
            if (keepRejected) {
                unsettled[key] = UnsettledPrompt(text, display, startsTurn, keepRejected = true, copiesBefore = copiesBefore)
                retainRejectedPrompt(key, error, refused)
            } else {
                _state.update { state ->
                    state.copy(
                        error = if (refused != null) null else error,
                        refused = refused,
                        // The caller gets the text back to resend; a dead bubble would only duplicate it.
                        messages = state.messages.filterNot { it.key == key },
                    )
                }
                SendOutcome.NotSent
            }
        } finally {
            sendMutex.unlock()
        }
    }

    /**
     * Settles a prompt that went out without a reply, once the transcript had its say ([arrived]). In it:
     * sent, and the stored rows take over. Missing from it: the caller gets the text back as for any
     * failure. Can't tell: the bubble stays, marked, so a resend is made knowing it may already be running.
     */
    private suspend fun settleUnanswered(
        key: String,
        arrived: Boolean?,
        prompt: UnsettledPrompt,
        unknownError: String = LOST_WHILE_SENDING,
        takeBack: suspend () -> Unit,
    ): SendOutcome {
        when (arrived) {
            true -> {
                rowExists = true
                _state.update { it.copy(messages = it.messages.updateUser(key) { u -> u.copy(check = null) }) }
                loadHistory()
            }
            false -> {
                takeBack()
                if (prompt.keepRejected) {
                    unsettled[key] = prompt
                    return retainRejectedPrompt(key, "Hermes didn't get your message.")
                }
                _state.update { state ->
                    state.copy(
                        error = "Hermes didn't get your message. It's back in the composer.",
                        messages = state.messages.filterNot { it.key == key },
                    )
                }
            }
            null -> {
                unsettled[key] = prompt
                _state.update { state ->
                    state.copy(
                        error = unknownError,
                        messages = state.messages.updateUser(key) { it.copy(pending = false, check = SendCheck.Unknown) },
                    )
                }
            }
        }
        return when (arrived) {
            true -> SendOutcome.Sent
            false -> SendOutcome.NotSent
            null -> SendOutcome.Unsettled
        }
    }

    /** A generated prompt has no composer draft to restore. Keep its bubble available to resend. */
    private fun retainRejectedPrompt(key: String, error: String, refused: SessionRefusal? = null): SendOutcome {
        _state.update { state ->
            state.copy(
                // A refusal says why with its own guidance, as for any prompt; the bubble stays to resend from.
                error = if (refused != null) null else error,
                refused = refused,
                messages = state.messages.updateUser(key) { it.copy(pending = false, check = SendCheck.NotReceived) },
            )
        }
        return SendOutcome.Unsettled
    }

    /**
     * Looks for the unsettled prompt [key] in the stored transcript again, when the user asks. Found: it
     * counts as sent. Missing: marked [SendCheck.NotReceived], safe to resend. Still can't tell: it stays
     * [SendCheck.Unknown]. Never sends anything itself.
     */
    suspend fun checkDelivery(key: String) {
        val bubble = _state.value.messages.firstOrNull { it.key == key } as? ChatMessage.User ?: return
        if (bubble.check == null || bubble.check == SendCheck.Checking) return
        // Reloaded first only when it never loaded, since the count below needs it: a reload settles any
        // prompt whose text the transcript has anywhere, an earlier one alike, so it can't be the check.
        if (!_state.value.historyRead) loadHistory()
        val messages = _state.value.messages
        val index = messages.indexOfFirst { it.key == key }
        if (index < 0) return run { unsettled.remove(key) }
        val before = _state.value.takeIf { it.historyRead }?.let {
            messages.take(index).count { m -> m is ChatMessage.User && !m.pending && m.check == null }
        }
        val arrived = findInTranscript(bubble.text, key, before)
        // A steer the agent read is kept in no user row, so a miss can't say it's safe to send again, mid-turn or after.
        val steerUnseen = arrived == false && unsettled[key]?.steer == true
        // A correction or a queued prompt is written only once the turn takes it in, so mid-turn a miss proves nothing.
        val mayBeWaiting = arrived == false && !steerUnseen && unsettled[key]?.startsTurn == false && _state.value.running
        when {
            arrived == true -> {
                rowExists = true
                unsettled.remove(key)
                _state.update { it.copy(error = null, messages = it.messages.updateUser(key) { u -> u.copy(check = null) }) }
                loadHistory()
            }
            arrived == false && !mayBeWaiting && !steerUnseen ->
                _state.update { it.copy(error = null, messages = it.messages.updateUser(key) { u -> u.copy(check = SendCheck.NotReceived) }) }
            else -> _state.update { state ->
                state.copy(
                    error = if (mayBeWaiting) {
                        "Hermes may still be holding it for the running turn. Check again once the turn ends."
                    } else if (steerUnseen) {
                        "A steer isn't saved in the conversation, so it can't be checked. See whether the reply followed it."
                    } else {
                        "Couldn't read the conversation to check. Try again once connected."
                    },
                    messages = state.messages.updateUser(key) { it.copy(check = SendCheck.Unknown) },
                )
            }
        }
    }

    /**
     * Removes the unsettled prompt [key] (one marked by a [SendCheck]) and hands back its text as shown, to
     * edit; null for any other message.
     */
    fun takeBack(key: String): String? {
        val message = _state.value.messages.firstOrNull { it.key == key } as? ChatMessage.User ?: return null
        if (message.check == null || message.check == SendCheck.Checking) return null
        unsettled.remove(key)
        _state.update { state -> state.copy(messages = state.messages.filterNot { it.key == key }) }
        return message.text
    }

    /**
     * Sends the unsettled prompt [key] again as it first went out (a skill's full body, not the command its
     * bubble shows), in place of its bubble. Returns the text shown to give back to the composer when it
     * didn't go and no bubble kept it; null otherwise.
     */
    suspend fun resend(key: String): String? {
        val prompt = unsettled[key]
        val shown = takeBack(key) ?: return null
        // Its files are in the workspace already: their references go again, with their cards.
        // On session authority, staged image refs go again too.
        val outcome = submitPrompt(
            prompt?.text ?: shown,
            emptyList(),
            prompt?.display,
            queue = prompt?.keepRejected == true && _state.value.running,
            voiceLive = null,
            resent = prompt?.files.orEmpty(),
            resentImages = prompt?.images.orEmpty(),
            keepRejected = prompt?.keepRejected == true,
        )
        return shown.takeIf { outcome == SendOutcome.NotSent }
    }

    /**
     * Rewinds the chat to prompt [key] and sends [text] in its place: a regenerate sends the prompt's own text
     * again, an edit a new one. The prompt and everything after it go, here and on the gateway, in one
     * `prompt.submit` that cuts the stored transcript before the prompt's row, as Desktop's rewind does. The session
     * authority takes the cut and the prompt apart instead ([mutateRewind], then a plain `prompt.submit`). Only
     * between turns, and only for a prompt with a stored row ([ChatMessage.User.rowId]).
     *
     * Refused (busy, the chat changed elsewhere, or the gateway can't find the row any more): nothing changed, and
     * [SendOutcome.NotSent]. When the link drops before the answer, the stored transcript says whether it went. On
     * the session authority the prompt can fail after the cut is made: it stays, marked, as [SendOutcome.Unsettled].
     */
    suspend fun rewind(key: String, text: String): SendOutcome {
        val trimmed = text.trim()
        val before = _state.value
        val index = before.messages.indexOfFirst { it.key == key }
        val rowId = (before.messages.getOrNull(index) as? ChatMessage.User)?.rowId
        if (trimmed.isEmpty() || rowId == null) return SendOutcome.NotSent
        // The gateway won't cut the chat mid-turn; say so rather than leave the send doing nothing.
        if (before.running) {
            _state.update { it.copy(error = BUSY_MESSAGE) }
            return SendOutcome.NotSent
        }
        val client = connectedClient() ?: run {
            _state.update { it.copy(error = "$NOT_CONNECTED Try again once it reconnects.") }
            return SendOutcome.NotSent
        }
        val newKey = "local-${before.keySeq}"
        // Shown cut at once; put back as it was if the gateway turns it down.
        _state.update {
            it.copy(
                messages = it.messages.take(index) + ChatMessage.User(newKey, trimmed, pending = true),
                keySeq = it.keySeq + 1,
                error = null,
                refused = null,
            )
        }
        ownTurnsPending++
        var submitted = false
        // The session authority cut the chat in a request of its own, before the prompt's.
        var cut = false
        return try {
            val runtimeId = ensureAttached(client)
            submitted = true
            val result = if (client.sessionAuthority) {
                mutateRewind(client, runtimeId, rowId)
                cut = true
                // Its row comes with its turn's end, named by this id (as for a prompt sent from the composer).
                val submissionId = newRequestId()
                _state.update { it.copy(messages = it.messages.updateUser(newKey) { u -> u.copy(submissionId = submissionId) }) }
                (client.request(
                    "prompt.submit",
                    buildJsonObject {
                        put("session_id", runtimeId)
                        put("submission_id", submissionId)
                        put("text", trimmed)
                    },
                ) as? JsonObject)?.also { reply -> reply.long("execution_generation")?.let { executionGeneration = it } }
            } else {
                client.request(
                    "prompt.submit",
                    buildJsonObject {
                        put("session_id", runtimeId)
                        put("text", trimmed)
                        put("truncate_before_row_id", rowId)
                        put("confirm_truncate", true)
                        // Cutting at the first prompt can empty the transcript (all but a hidden starting instruction), which the gateway wants said outright.
                        put("confirm_empty_truncate", true)
                    },
                ) as? JsonObject
            }
            if (result.string("status") !in TURN_STARTING_STATUSES) ownTurnsPending = (ownTurnsPending - 1).coerceAtLeast(0)
            // The stored rows decide once the turn is over, so what shows matches the gateway's transcript.
            rewound = true
            _state.update { state ->
                state.copy(
                    running = true,
                    messages = state.messages.updateUser(newKey) {
                        // The session authority's reply has no row; a short turn may have given it one already.
                        it.copy(pending = false, rowId = result.long("user_row_id") ?: it.rowId, sentText = trimmed)
                    },
                )
            }
            SendOutcome.Sent
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ownTurnsPending = (ownTurnsPending - 1).coerceAtLeast(0)
            if (cut) return keepPromptAfterCut(newKey, trimmed, e)
            if (submitted && e !is RpcException) {
                // No answer: the cut may have happened. The transcript shows which, and its last prompt says whether this went.
                _state.update { state -> state.copy(messages = state.messages.filterNot { it.key == newKey }) }
                loadHistory()
                // The session authority's prompt never went: its cut got no answer.
                val arrived = !client.sessionAuthority && _state.value.historyRead &&
                    (_state.value.messages.lastOrNull { it is ChatMessage.User } as? ChatMessage.User)?.text == trimmed
                if (arrived) {
                    rewound = true
                    return SendOutcome.Sent
                }
                _state.update { it.copy(error = "Lost the connection while sending. The chat shows what Hermes has.") }
                return SendOutcome.NotSent
            }
            val rpc = e as? RpcException
            // The session authority's refusals of the cut, which wrote nothing: they come by reason, under one code.
            val fenced = client.sessionAuthority && rpc?.reason in FENCE_REFUSALS
            val busy = rpc?.code == BUSY || (client.sessionAuthority && rpc?.reason == "session_busy")
            val stale = rpc?.code in STALE_REWIND_CODES ||
                (client.sessionAuthority && rpc?.reason == "invalid_params" && rpc?.data.string("field") == "target_message_id")
            // A session authority without `session.mutate` has no other way to cut: its `prompt.submit` refuses the keys.
            val noCut = client.sessionAuthority && rpc?.code == METHOD_NOT_FOUND
            val refused = SessionRefusal.of(e)
            _state.update { state ->
                state.copy(
                    messages = before.messages,
                    refused = refused,
                    error = when {
                        refused != null -> null
                        busy -> BUSY_MESSAGE
                        stale -> "This message can't be changed any more."
                        fenced -> CHANGED_ELSEWHERE
                        noCut -> "This gateway can't cut a chat back. Update Hermes to edit or regenerate here."
                        else -> e.message ?: "Couldn't send the message."
                    },
                )
            }
            // The gateway's transcript isn't what this chat shows; show it as it is.
            if (stale || fenced || (busy && client.sessionAuthority)) loadHistory()
            SendOutcome.NotSent
        }
    }

    /**
     * A rewind's cut on the session authority, whose `prompt.submit` takes no truncation keys: a `rewind` through
     * `session.mutate` at the prompt's row [rowId], fenced by the session's revision and execution generation as
     * Hermes Desktop sends it. The prompt goes after, in a plain `prompt.submit`. A refusal wrote nothing; one for
     * stale fences carries the current ones, kept for the next try rather than sent again here, since the chat it
     * would cut is no longer the one shown.
     */
    private suspend fun mutateRewind(client: JsonRpcClient, runtimeId: String, rowId: Long) {
        val receipt = try {
            client.request(
                "session.mutate",
                buildJsonObject {
                    put("session_id", runtimeId)
                    put("request_id", newRequestId())
                    // Unknown only if the gateway sent neither; its refusal then says what they are.
                    put("expected_revision", revision ?: 0)
                    put("expected_generation", executionGeneration ?: 0)
                    put("operation", "rewind")
                    putJsonObject("payload") { put("target_message_id", rowId) }
                },
            ) as? JsonObject
        } catch (e: RpcException) {
            if (e.reason in FENCE_REFUSALS) {
                e.data.long("revision")?.let { revision = it }
                e.data.long("execution_generation")?.let { executionGeneration = it }
            }
            throw e
        }
        receipt.long("revision")?.let { seen -> revision = maxOf(revision ?: seen, seen) }
        receipt.long("execution_generation")?.let { executionGeneration = it }
    }

    /**
     * The session authority cut the chat, but the prompt [key] that was to follow didn't go ([e]). The chat shows the
     * cut, and the prompt stays under it to send again: turned down, Hermes hasn't got it; with no answer, it may have.
     */
    private suspend fun keepPromptAfterCut(key: String, text: String, e: Exception): SendOutcome {
        loadHistory()
        val refused = SessionRefusal.of(e)
        unsettled[key] = UnsettledPrompt(text, display = null, startsTurn = true)
        _state.update { state ->
            state.copy(
                refused = refused,
                error = when {
                    refused != null -> null
                    e is RpcException -> "The chat was cut back, but your message didn't go. Send it again."
                    else -> "Lost the connection while sending. Check that it isn't running before you send it again."
                },
                messages = state.messages.updateUser(key) {
                    it.copy(pending = false, check = if (e is RpcException) SendCheck.NotReceived else SendCheck.Unknown)
                },
            )
        }
        return SendOutcome.Unsettled
    }

    @OptIn(ExperimentalUuidApi::class)
    private fun newRequestId(): String = Uuid.random().toString()

    /**
     * Whether the transcript has a prompt more than the [before] shown ahead of the send, the latest being
     * [visible], while bubble [key] says it's being checked. Counted, not matched by key: an own prompt's
     * bubble keeps its local key, so its stored row always looks new. The gateway writes the prompt as the
     * turn starts, so a few looks a moment apart cover a slow start. Null when the transcript couldn't be read.
     */
    private suspend fun findInTranscript(visible: String, key: String, before: Int?): Boolean? {
        _state.update { it.copy(messages = it.messages.updateUser(key) { u -> u.copy(pending = false, check = SendCheck.Checking) }) }
        before ?: return null
        val id = _state.value.storedSessionId ?: return null
        var read = false
        repeat(DELIVERY_LOOKS) { look ->
            if (look > 0) delay(DELIVERY_LOOK_INTERVAL_MS)
            when (val result = sessions.messages(gateway, id, limit = HISTORY_PAGE, profile = profile)) {
                is ApiResult.Success -> {
                    // Counted over the same stretch as the prompts shown: the loaded rows, brought up to date.
                    val stored = rows.afterNewestPage(result.value.messages) ?: return@repeat
                    read = true
                    val prompts = historyToMessages(stored).filterIsInstance<ChatMessage.User>()
                    // Any prompt past the ones shown before it: later prompts may follow it by a later check.
                    // A file sent with no words: every text contains "", so only a prompt with attachments of its own counts.
                    if (prompts.drop(before).any { it.text.contains(visible) && (visible.isNotEmpty() || it.attachments.isNotEmpty()) }) return true
                }
                // A new chat's stored row only appears with its first prompt.
                is ApiResult.Failed -> if (result.status == 404 && !rowExists) read = true
                else -> {}
            }
        }
        return if (read) false else null
    }

    /**
     * Uploads one attachment to [runtimeId]. Images are queued for the next turn; a PDF or other file
     * comes back as the `@file:` reference to put in the prompt, which this returns. Bubble [key] shows
     * how far a large one has gone up.
     */
    private suspend fun upload(
        client: JsonRpcClient,
        runtimeId: String,
        attachment: OutgoingAttachment,
        queuedImages: MutableList<String>,
        stagedImages: MutableList<Pair<StagedImage, ShownAttachment>>,
        key: String,
    ): String? {
        val failure = "Couldn't attach ${attachment.name}"
        suspend fun asFile(): String {
            val result = client.request(
                "file.attach",
                buildJsonObject {
                    put("session_id", runtimeId)
                    put("name", attachment.name)
                    put("data_url", "data:${attachment.mimeType};base64,${attachment.base64()}")
                },
                timeoutMs = UPLOAD_TIMEOUT_MS,
            ) as? JsonObject
            return result.string("ref_text")?.takeIf { result.boolean("attached") == true } ?: throw RpcException(0, failure)
        }
        if (!attachment.inline) return uploadStaged(client, runtimeId, attachment, key)
        return when (attachment.kind) {
            AttachmentKind.Image -> {
                val result = client.request(
                    "image.attach_bytes",
                    buildJsonObject {
                        put("session_id", runtimeId)
                        put("content_base64", attachment.base64())
                        put("filename", attachment.name)
                    },
                    timeoutMs = UPLOAD_TIMEOUT_MS,
                ) as? JsonObject
                if (result.boolean("attached") != true) throw RpcException(0, result.string("message") ?: failure)
                val path = result.string("path")
                if (path != null) {
                    queuedImages.add(path)
                    val mime = result.string("mime") ?: result.string("mime_type") ?: attachment.mimeType
                    stagedImages.add(StagedImage(path, mime) to attachment.toShown())
                }
                null
            }
            AttachmentKind.Pdf, AttachmentKind.File -> asFile()
        }
    }

    /**
     * An attachment too large for one WebSocket message: it goes up over HTTP ([AttachmentUploads]) and is
     * attached by its path there as an `@file:` reference, a PDF whole like any other file. `file.attach`
     * copies a file from outside the session's workspace into it, so the upload is removed after; one
     * inside the workspace stays. Bubble [key] shows how far it has gone up, by whole percents, while it goes.
     */
    private suspend fun uploadStaged(
        client: JsonRpcClient,
        runtimeId: String,
        attachment: OutgoingAttachment,
        key: String,
    ): String? {
        fun show(progress: UploadProgress?) =
            _state.update { it.copy(messages = it.messages.updateUser(key) { u -> u.copy(upload = progress) }) }
        var shown = UploadProgress(attachment.name, 0, attachment.size)
        show(shown)
        val staged = try {
            sessions.uploads.stage(gateway, attachment) { sent ->
                val next = shown.copy(sent = sent)
                if (next.percent != shown.percent) {
                    shown = next
                    show(next)
                }
            }
        } finally {
            show(null)
        }
        var usedInPlace = false
        try {
            val result = client.request(
                "file.attach",
                buildJsonObject {
                    put("session_id", runtimeId)
                    put("path", staged.path)
                    put("name", attachment.name)
                },
                timeoutMs = UPLOAD_TIMEOUT_MS,
            ) as? JsonObject
            // `uploaded: false` is a file referenced where it is (in the chat's workspace), which has to stay.
            usedInPlace = result.boolean("uploaded")?.not() ?: (result.string("path") == staged.path)
            return result.string("ref_text")?.takeIf { result.boolean("attached") == true }
                ?: throw RpcException(0, "Couldn't attach ${attachment.name}")
        } finally {
            if (!usedInPlace) {
                withContext(NonCancellable) {
                    try {
                        sessions.uploads.discard(gateway, staged)
                    } catch (_: Exception) {
                        // Best effort: a leftover upload is only a file in the gateway's uploads folder.
                    }
                }
            }
        }
    }

    /** Best effort: un-queues [paths] after a send that failed part-way. */
    private suspend fun detach(client: JsonRpcClient?, runtimeId: String?, paths: List<String>) {
        if (client == null || runtimeId == null) return
        for (path in paths) {
            try {
                client.request("image.detach", buildJsonObject { put("session_id", runtimeId); put("path", path) })
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return // the socket is likely gone, and the runtime session's queue with it
            }
        }
    }

    /**
     * Runs [command] on the gateway the way Desktop does: `slash.exec` first (built-ins, quick and
     * plugin commands), then `command.dispatch` for what the slash worker won't take (skills, bundles).
     * Output shows under the command in the chat. Returns text to put back in the composer (`/undo`).
     */
    suspend fun runCommand(command: SlashCommand): String? {
        val key = addCommand("/${command.name}")
        val client = connectedClient() ?: return finishCommand(key, NOT_CONNECTED, failed = true).let { null }
        val runtimeId = try {
            ensureAttached(client)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            finishCommand(key, e.message ?: "Couldn't open the session.", failed = true)
            return null
        }
        var execError: Exception? = null
        val exec = try {
            val reply = client.request(
                "slash.exec",
                buildJsonObject {
                    put("session_id", runtimeId)
                    put("command", command.line)
                },
                timeoutMs = COMMAND_TIMEOUT_MS,
            ) as? JsonObject
            SlashResult.parse(reply) ?: SlashResult.Output("")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            execError = e
            null
        }
        val result = exec ?: try {
            SlashResult.parse(
                client.request(
                    "command.dispatch",
                    buildJsonObject {
                        put("session_id", runtimeId)
                        put("name", command.name)
                        if (command.arg.isNotEmpty()) put("arg", command.arg)
                    },
                    timeoutMs = COMMAND_TIMEOUT_MS,
                ) as? JsonObject,
            ) ?: SlashResult.Output("The gateway's answer to /${command.name} couldn't be read.")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // "Not a quick/plugin/bundle/skill command" only means the fallback had nothing either; the worker's
            // failure (a timeout, a crash) is the one worth showing.
            val routingNoise = NOT_DISPATCHABLE.containsMatchIn(e.message.orEmpty())
            val shown = if (routingNoise && execError != null) execError else e
            finishCommand(key, shown.message ?: "/${command.name} failed.", failed = true)
            return null
        }
        // A command may have set or moved the goal, loop or heartbeat: `/goal`, `/hb`, or a quick alias the session
        // authority resolves itself, so the name typed doesn't tell. v0.22.1's authority sends no update for it.
        if (result !is SlashResult.Alias && controlUnpublished(anyChat = true)) scope.launch { refreshControl() }
        return when (result) {
            is SlashResult.Output -> {
                val text = result.text.ifBlank { "Done." }
                finishCommand(key, result.warning?.let { "$it\n\n$text" } ?: text)
                null
            }
            is SlashResult.Alias -> {
                removeMessage(key)
                runCommand(SlashCommand(result.target.lowercase(), command.arg))
            }
            is SlashResult.Send -> {
                result.notice?.takeIf { it.isNotBlank() }?.let { finishCommand(key, it.trim()) } ?: removeMessage(key)
                val display = result.display?.takeIf { it.isNotBlank() } ?: skillInvocationText(result.message) ?: "/${command.line}"
                send(result.message, display = display)
                null
            }
            is SlashResult.Prefill -> {
                result.notice?.takeIf { it.isNotBlank() }?.let { finishCommand(key, it.trim()) } ?: removeMessage(key)
                // `/undo` took turns off the stored transcript; show it without them.
                if (rowExists) loadHistory()
                result.message
            }
        }
    }

    /**
     * `/compress`: summarizes older turns through `session.compress` (the slash worker times out on it). The
     * session authority has none: there it is a `session.mutate` `compress`.
     */
    suspend fun compress(focus: String) = runOnGateway("/compress", COMPRESS_TIMEOUT_MS) { client, runtimeId ->
        if (client.sessionAuthority) {
            val result = mutateFenced(client, runtimeId, "compress", CHANGED_WHILE_COMPRESSING, COMPRESS_TIMEOUT_MS) {
                if (focus.isNotBlank()) put("focus", focus.trim())
            }
            // gateway/session_mutation_compress.py: a compress that removed nothing says `noop`; the summary words
            // the outcome as /compress does.
            val summary = result?.get("summary") as? JsonObject
            if (result.string("status") != "noop" && summary.boolean("noop") != true) scope.launch { loadHistory() }
            return@runOnGateway listOfNotNull(summary.string("headline"), summary.string("token_line"), summary.string("note"))
                .map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n").ifEmpty { "Compressed." }
        }
        val result = client.request(
            "session.compress",
            buildJsonObject {
                put("session_id", runtimeId)
                if (focus.isNotBlank()) put("focus_topic", focus.trim())
            },
            timeoutMs = COMPRESS_TIMEOUT_MS,
        ) as? JsonObject
        if (result.boolean("compressed") == false) return@runOnGateway result.string("message") ?: "Nothing to compress yet."
        scope.launch { loadHistory() }
        val before = result.int("before_messages")
        val after = result.int("after_messages")
        val beforeTokens = result.int("before_tokens")
        val afterTokens = result.int("after_tokens")
        buildString {
            append("Compressed")
            if (before != null && after != null) append(": $before → $after messages")
            if (beforeTokens != null && afterTokens != null) append(", about $beforeTokens → $afterTokens tokens")
            append('.')
        }
    }

    /**
     * `/status`: the session report from `session.status`, or on the session authority, which has none, from
     * `slash.exec status` (the same report, gateway/session_commands.py).
     */
    suspend fun status() = runOnGateway("/status") { client, runtimeId ->
        val result = if (client.sessionAuthority) {
            client.request(
                "slash.exec",
                buildJsonObject {
                    put("session_id", runtimeId)
                    put("command", "status")
                },
                timeoutMs = COMMAND_TIMEOUT_MS,
            ) as? JsonObject
        } else {
            client.request("session.status", buildJsonObject { put("session_id", runtimeId) }) as? JsonObject
        }
        result.string("output") ?: "No status."
    }

    /**
     * The provider account's limits and credits as the gateway words them (`session.usage` `account_lines` /
     * `credits_lines`, or on the session authority `session.account_usage` `lines` / `nous_lines`, e.g. a quota
     * window); empty when there are none, offline, or on a gateway without the call (an authority before
     * hermes-agent v0.23.2).
     */
    suspend fun accountLimits(): List<String> {
        val client = connectedClient() ?: return emptyList()
        val runtimeId = liveRuntimeId(client) ?: return emptyList()
        return try {
            val authority = client.sessionAuthority
            val method = if (authority) "session.account_usage" else "session.usage"
            val result = client.request(method, buildJsonObject { put("session_id", runtimeId) }) as? JsonObject
            val keys = if (authority) listOf("lines", "nous_lines") else listOf("account_lines", "credits_lines")
            keys.flatMap { key ->
                (result?.get(key) as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotEmpty) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** What fills the context window now (`session.context_breakdown`); null before the first prompt or offline. */
    suspend fun contextBreakdown(): ContextBreakdown? {
        val client = connectedClient() ?: return null
        val runtimeId = liveRuntimeId(client) ?: return null
        return try {
            ContextBreakdown.parse(
                client.request("session.context_breakdown", buildJsonObject { put("session_id", runtimeId) }) as? JsonObject,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    /** The background processes this chat's agent started (`process.list`); null when offline or before the first prompt. */
    suspend fun processes(): List<BackgroundProcess>? {
        val client = connectedClient() ?: return null
        val runtimeId = liveRuntimeId(client) ?: return null
        return try {
            val result = client.request("process.list", buildJsonObject { put("session_id", runtimeId) }) as? JsonObject
            result?.takeIf {
                it["processes"] is JsonArray && connectedClient() === client && liveRuntimeId(client) == runtimeId
            }?.let(BackgroundProcess::parseList)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    /** Stops one background process (`process.kill`), and says how that went. */
    suspend fun killProcess(processId: String): String {
        val client = connectedClient() ?: return NOT_CONNECTED
        val runtimeId = liveRuntimeId(client) ?: return NOT_CONNECTED
        return try {
            killOutcome(
                client.request(
                    "process.kill",
                    buildJsonObject {
                        put("session_id", runtimeId)
                        put("process_id", processId)
                    },
                ) as? JsonObject,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e.message ?: "Couldn't stop it."
        }
    }

    /**
     * The folder snapshots Hermes took before the agent changed files in this chat (`rollback.list`). Null when
     * offline or for a chat with nothing on the gateway yet; a stored chat is attached first, as the call needs it live.
     * Throws when the gateway fails the call, so the reason can be shown.
     */
    suspend fun checkpoints(): Checkpoints? {
        val client = connectedClient() ?: return null
        val runtimeId = liveIdForCheckpoints(client) ?: return null
        return Checkpoints.parse(client.request("rollback.list", buildJsonObject { put("session_id", runtimeId) }) as? JsonObject)
    }

    /** What changed in the folder since [checkpoint] (`rollback.diff`). */
    suspend fun checkpointDiff(checkpoint: Checkpoint): CheckpointDiff {
        val client = connectedClient() ?: return CheckpointDiff(error = NOT_CONNECTED)
        return try {
            val runtimeId = liveIdForCheckpoints(client) ?: return CheckpointDiff(error = NOT_CONNECTED)
            CheckpointDiff.parse(
                client.request(
                    "rollback.diff",
                    buildJsonObject {
                        put("session_id", runtimeId)
                        put("hash", checkpoint.hash)
                    },
                ) as? JsonObject,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            CheckpointDiff(error = e.message ?: "Couldn't read the changes.")
        }
    }

    /**
     * Puts the folder back as it was at [checkpoint] (`rollback.restore`), which also takes back the chat's last
     * turn; the transcript is read again after. The gateway refuses while a reply runs. Says how it went.
     */
    suspend fun restoreCheckpoint(checkpoint: Checkpoint): String {
        val client = connectedClient() ?: return NOT_CONNECTED
        return try {
            val runtimeId = liveIdForCheckpoints(client) ?: return NOT_CONNECTED
            val result = client.request(
                "rollback.restore",
                buildJsonObject {
                    put("session_id", runtimeId)
                    put("hash", checkpoint.hash)
                },
            ) as? JsonObject
            if (result.boolean("success") == true) scope.launch { loadHistory() }
            restoreOutcome(result)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A reply that timed out may still have restored the folder and taken back the turn on the gateway.
            scope.launch { loadHistory() }
            e.message ?: "Couldn't restore the checkpoint."
        }
    }

    /** The runtime id the `rollback.*` calls take: attaching a stored chat, but never creating one for an empty chat. */
    private suspend fun liveIdForCheckpoints(client: JsonRpcClient): String? =
        liveRuntimeId(client) ?: if (rowExists) ensureAttached(client) else null

    /**
     * The session-authority link whose `session.control` came back "no such method" (Hermes v0.22.1 serves only
     * `.read` there): a button runs as its typed command instead, as v0.22.3's own `session.control` does. A new
     * link may be to an updated gateway, so it asks once more.
     */
    private var controlByCommandOn: JsonRpcClient? = null

    /**
     * The link a `session.control.update` came in on. A session authority of Hermes v0.22.1 sends none, not after a
     * typed `/goal` nor after the goal's judge; v0.22.3 and later do (gateway/session_commands.py, run_goals.py).
     * Those send one only when something changes, so a new link to them is read after each turn until one comes.
     */
    private var controlPublishedOn: JsonRpcClient? = null

    /** Writes to the state that aren't reads (`session.control.update`, a [runControl] answer); a read out meanwhile yields. */
    private var controlWrites = 0

    /** The last `session.control.read` sent, and the last whose answer was taken: an earlier read's answer is older. */
    private var controlReadsSent = 0
    private var controlReadTaken = 0

    /**
     * Whether the attached link is a session authority that hasn't shown it sends `session.control.update`, while the
     * chat has a goal, loop or heartbeat: its state is read again when a turn ends or the judge speaks. With
     * [anyChat] it holds for a chat with none yet, as another client may just have set one.
     */
    private fun controlUnpublished(anyChat: Boolean = false): Boolean {
        val client = attachedClient ?: return false
        return client.sessionAuthority && client !== controlPublishedOn && (anyChat || _state.value.control != null)
    }

    /**
     * Reads the session's goal, loop and heartbeat once (`session.control.read`), since `session.resume`
     * doesn't carry them; later changes arrive as `session.control.update` events. Best effort: a failure
     * leaves the state as it is.
     * Returns whether the gateway answered; an answer older than an update or read that came in meanwhile yields to it.
     */
    suspend fun refreshControl(): Boolean {
        val client = connectedClient() ?: return false
        val runtimeId = liveRuntimeId(client) ?: return false
        // An update that lands while the read is out is newer than the read's answer, so the answer yields to it, as it
        // does to a later read's answer: reads after a turn's end and after the judge's status can overlap.
        val writes = controlWrites
        val read = ++controlReadsSent
        val result = try {
            client.request("session.control.read", buildJsonObject { put("session_id", runtimeId) }) as? JsonObject
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return false
        }
        if (result == null || "control" !in result) return false
        if (controlWrites == writes && read > controlReadTaken) {
            controlReadTaken = read
            _state.update { it.withControl(SessionControl.parse(result["control"] as? JsonObject)) }
        }
        return true
    }

    /**
     * Reads the session's reasoning effort once (`config.get reasoning`), since `session.create` and a
     * lazy `session.resume` leave `reasoning_effort` out of `info`; later changes arrive as `session.info`
     * events. Best effort: a failure leaves the state as it is.
     * Before a cold resume's agent is built, the gateway answers with the profile's level even when the
     * chat kept its own; the build's `session.info` corrects that a few seconds later.
     */
    suspend fun refreshReasoning() {
        val client = connectedClient() ?: return
        val runtimeId = liveRuntimeId(client) ?: return
        // A pick or session.info that lands while the read is out is newer than the read's answer.
        val before = _state.value.reasoningRevision
        val result = try {
            client.request(
                "config.get",
                buildJsonObject {
                    put("key", "reasoning")
                    put("session_id", runtimeId)
                },
            ) as? JsonObject
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return
        }
        val value = result.string("value")
        if (ReasoningEffort.fromWire(value) != null) {
            // A re-attach while the read was out made a new runtime session; its own read answers for it.
            _state.update {
                if (it.reasoningRevision != before || it.runtimeSessionId != runtimeId) it else it.copy(reasoningEffort = value)
            }
        }
    }

    /**
     * Reads the profile's default level for a chat with no session yet (`config.get reasoning` without a
     * `session_id`), so its pill shows that rather than Medium. Best effort, like [refreshReasoning].
     */
    private suspend fun refreshProfileReasoning(client: JsonRpcClient) {
        val result = try {
            client.request(
                "config.get",
                buildJsonObject {
                    put("key", "reasoning")
                    profile?.let { put("profile", it) }
                },
            ) as? JsonObject
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return
        }
        val value = result.string("value")
        if (ReasoningEffort.fromWire(value) != null) _state.update { it.copy(profileReasoningEffort = value) }
    }

    /**
     * Runs one `session.control` action (pause a goal, add a sub-goal, ...) and applies the snapshot it
     * returns at once rather than waiting for the event. A `send` dispatch (a resumed goal's kickoff)
     * goes out as a prompt with its `display` on the bubble, queued behind a running turn. Returns null
     * on success, or a user-readable reason it didn't go.
     */
    suspend fun runControl(action: ControlAction, text: String? = null, index: Int? = null): String? {
        val client = connectedClient() ?: return NOT_CONNECTED
        val runtimeId = try {
            ensureAttached(client)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return e.message ?: "Couldn't open the session."
        }
        // As with the read: an update from another client that lands first is newer than this answer. A read's answer
        // that lands first isn't: it may have been sent before this action.
        val writes = controlWrites
        val result = try {
            if (client === controlByCommandOn) {
                controlByCommand(client, runtimeId, action, text, index)
            } else {
                try {
                    client.request(
                        "session.control",
                        buildJsonObject {
                            put("session_id", runtimeId)
                            put("action", action.wire)
                            if (text != null) put("args", buildJsonObject { put("text", text) })
                            else if (index != null) put("args", buildJsonObject { put("index", index) })
                        },
                    ) as? JsonObject
                } catch (e: RpcException) {
                    if (e.code != METHOD_NOT_FOUND || !client.sessionAuthority) throw e
                    controlByCommandOn = client
                    controlByCommand(client, runtimeId, action, text, index)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return e.message ?: "Couldn't ${action.verb}."
        }
        if (result != null && "control" in result && controlWrites == writes) {
            controlWrites++
            // Reads still out were sent before this answer, so theirs are no newer.
            controlReadTaken = controlReadsSent
            _state.update { it.withControl(SessionControl.parse(result["control"] as? JsonObject)) }
        }
        val dispatch = result?.get("dispatch") as? JsonObject
        if (dispatch.string("type") == "send") {
            val message = dispatch.string("message")?.takeIf { it.isNotBlank() }
            if (message != null) {
                val display = dispatch.string("display")?.takeIf { it.isNotBlank() } ?: "/goal resume"
                // The gateway has already resumed it; without this prompt the agent wouldn't take the next step.
                if (!send(message, display = display, queue = _state.value.running)) {
                    return "The goal is resumed, but its next step didn't go out. Resend it from the chat."
                }
            }
        }
        return null
    }

    /**
     * [action] as its typed command (`command.dispatch`), for the session-authority gateway of
     * [controlByCommandOn]. Its answer is the `dispatch` that `session.control` would carry; that gateway
     * publishes no `session.control.update`, so the state is read again. Throws with a user-readable
     * reason when the command fails as well. The command answers a refusal ("No goal set.", a sub-goal
     * number that's gone) as its plain output, so output with a state that didn't move is that reason.
     * The state is read before the command too: with no updates, the one on screen may be stale.
     */
    private suspend fun controlByCommand(
        client: JsonRpcClient,
        runtimeId: String,
        action: ControlAction,
        text: String?,
        index: Int?,
    ): JsonObject {
        if (action == ControlAction.SubgoalAdd && text.orEmpty().all { it.isPythonSpace() }) {
            throw IllegalStateException(SUBGOAL_TEXT_MISSING)
        }
        val (name, arg) = action.command(text, index) ?: throw IllegalStateException(SUBGOAL_READS_AS_VERB)
        val readBefore = refreshControl()
        val revision = _state.value.control?.revision
        val dispatch = try {
            client.request(
                "command.dispatch",
                // Only what Hermes v0.22.1 takes here (gateway/session_commands.py `_parse`): strings, no more.
                buildJsonObject {
                    put("session_id", runtimeId)
                    put("name", name)
                    put("arg", arg)
                },
                timeoutMs = COMMAND_TIMEOUT_MS,
            ) as? JsonObject
        } catch (e: CancellationException) {
            throw e
        } catch (e: RpcException) {
            throw IllegalStateException(commandRefusal(action, e))
        } catch (_: RpcTimeoutException) {
            // It may have gone through all the same, and no update will say so.
            refreshControl()
            throw IllegalStateException("Couldn't ${action.verb}: the gateway didn't answer in time.")
        }
        val output = dispatch.string("output")?.trim()
        if (dispatch.string("type") == "exec" && !output.isNullOrEmpty() && refreshControl() && readBefore &&
            _state.value.control?.revision == revision
        ) {
            throw IllegalStateException(output)
        }
        return buildJsonObject { dispatch?.let { put("dispatch", it) } }
    }

    /** Why `command.dispatch` refused [action]: the session authority answers with a bare code (gateway/session_controls.py). */
    private fun commandRefusal(action: ControlAction, e: RpcException): String {
        val code = e.reason ?: e.message
        val why = when {
            // Only a gateway without the command is helped by an update.
            e.code == METHOD_NOT_FOUND || code == "unsupported_command" -> return "Couldn't ${action.verb} on this gateway. Update Hermes to use goals."
            code == "permission_denied" || code == "profile_mismatch" -> "the gateway didn't allow it."
            code == "not_found" -> "the chat isn't open on the gateway any more."
            code == "runtime_draining" -> "the gateway is restarting. Try again in a moment."
            code == "session_busy" -> "the chat is busy. Try again in a moment."
            code.isBlank() -> "the gateway refused it."
            else -> "the gateway refused it ($code)."
        }
        return "Couldn't ${action.verb}: $why"
    }

    /**
     * `/stop`: stops the reply, then the background processes this chat's agent left running. Other chats' processes
     * are left alone. When the reply was stopped but the processes couldn't be, the card says both.
     */
    suspend fun stopEverything() {
        val started = TimeSource.Monotonic.markNow()
        runOnGateway("/stop") { client, runtimeId ->
            val lines = mutableListOf<String>()
            var sessionId = runtimeId
            if (_state.value.running) {
                sessionId = interruptOnGateway(client, runtimeId)
                lines += "Stopped the reply."
            }
            val stop = ProcessStop()
            try {
                // Within /stop's own time limit, so a slow kill can't take "Stopped the reply." with it.
                withTimeoutOrNull(COMMAND_TIMEOUT_MS.milliseconds - started.elapsedNow()) {
                    stopProcesses(client, sessionId, stop)
                } ?: run { stop.failure = stop.failure ?: RpcTimeoutException("process.kill") }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                stop.failure = e
            }
            val failure = stop.failure
            if (failure != null && stop.stopped == 0 && lines.isEmpty()) throw failure
            if (failure == null || stop.stopped > 0) {
                lines += when (stop.stopped) {
                    0 -> "No background processes were running."
                    1 -> "Stopped 1 background process."
                    else -> "Stopped ${stop.stopped} background processes."
                }
            }
            if (failure != null) {
                val which = if (stop.stopped > 0) "every background process" else "the background processes"
                lines += "Couldn't stop $which: ${failure.message ?: "the gateway didn't say why."}"
            }
            lines.joinToString("\n")
        }
    }

    /** How far `/stop` got with this chat's background processes: how many it stopped, and the first thing that failed. */
    private class ProcessStop {
        var stopped = 0
        var failure: Exception? = null
    }

    /**
     * Stops the background processes of chat [sessionId], counting them in [stop]. On the session authority that is
     * `process.stop` (gateway/session_ancillary.py stop_processes). The legacy sidecar's `process.stop` is the
     * registry-wide `kill_all`, every chat's processes, so there it's this chat's `process.list` and a `process.kill`
     * for each running one. One kill failing doesn't keep the others from being tried.
     */
    private suspend fun stopProcesses(client: JsonRpcClient, sessionId: String, stop: ProcessStop) {
        if (client.sessionAuthority) {
            val result = client.request("process.stop", buildJsonObject { put("session_id", sessionId) }) as? JsonObject
            stop.stopped = result.int("killed") ?: 0
            return
        }
        val listed = client.request("process.list", buildJsonObject { put("session_id", sessionId) }) as? JsonObject
        BackgroundProcess.parseList(listed).filter { it.running }.forEach { process ->
            try {
                val result = client.request(
                    "process.kill",
                    buildJsonObject {
                        put("session_id", sessionId)
                        put("process_id", process.id)
                    },
                ) as? JsonObject
                when (result.string("status")) {
                    "killed" -> stop.stopped++
                    // tools/process_registry.py kill_process: a failed kill is a result, not a JSON-RPC error.
                    "error" -> stop.failure = stop.failure ?: Exception(result.string("error"))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                stop.failure = stop.failure ?: e
            }
        }
    }

    /**
     * `/btw`: a side question answered from a snapshot of the chat without interrupting it
     * (`prompt.btw`). The answer arrives later as `btw.complete` and fills in the same card.
     */
    suspend fun askAside(question: String) {
        if (question.isBlank()) {
            showCommandOutput("/btw", "Usage: /btw <question>. It's answered from a snapshot of this chat, without interrupting it.")
            return
        }
        startSideTask("/btw", "prompt.btw", question.trim(), "Couldn't ask the side question.")
    }

    /**
     * `/bg <prompt>`: a fresh agent runs [prompt] in its own session beside this chat (`prompt.background`),
     * as the TUI and Desktop do. The reply arrives later as `background.complete` and fills in the card.
     * Through `slash.exec` the slash worker would print it after its output was already collected, so it
     * never reached the chat (hermes-agent #97635).
     */
    suspend fun runInBackground(prompt: String) {
        if (prompt.isBlank()) {
            showCommandOutput("/bg", "Usage: /bg <prompt>. A separate agent runs it in the background, and its answer shows up here.")
            return
        }
        startSideTask("/bg", "prompt.background", prompt.trim(), "Couldn't start the background task.")
    }

    /**
     * Starts a side agent through [method] under a running card for [command]. Its answer comes back as an
     * event with the returned `task_id`, which the reducer matches to the card; a `/bg` answer is also read
     * from the task's stored session ([followBackgroundTask]), since the event is lost after a reconnect.
     */
    private suspend fun startSideTask(command: String, method: String, text: String, failure: String) {
        val key = addCommand("$command $text")
        val client = connectedClient() ?: return finishCommand(key, NOT_CONNECTED, failed = true)
        try {
            val runtimeId = ensureAttached(client)
            val taskId = (client.request(method, buildJsonObject { put("session_id", runtimeId); put("text", text) }) as? JsonObject)
                .string("task_id")
            _state.update { state ->
                state.copy(messages = state.messages.map { if (it is ChatMessage.Command && it.key == key) it.copy(taskId = taskId) else it })
            }
            if (taskId != null && command == "/bg") scope.launch { followBackgroundTask(taskId) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            finishCommand(key, e.message ?: failure, failed = true)
        }
    }

    /**
     * Fills the running `/bg` card for [taskId] from the task's own stored session (the agent runs under
     * that id) once its last row is the final answer. `background.complete` usually gets there first; this
     * covers the event going to a runtime this chat no longer holds (a reconnect, another chat open meanwhile).
     */
    private suspend fun followBackgroundTask(taskId: String) {
        repeat(BACKGROUND_POLLS) {
            delay(BACKGROUND_POLL_MS)
            if (!isRunningTask(taskId)) return
            val rows = (sessions.messages(gateway, taskId, limit = 1, profile = profile) as? ApiResult.Success)?.value?.messages
            val last = rows?.lastOrNull()
            val answer = last?.takeIf { it.role == "assistant" && it.storedToolCalls.isEmpty() }?.text?.trim()
            if (!answer.isNullOrEmpty()) {
                _state.update { it.answerSideTask(taskId, answer, "/bg") }
                return
            }
        }
        if (isRunningTask(taskId)) {
            _state.update { state ->
                state.copy(messages = state.messages.map {
                    if (it is ChatMessage.Command && it.taskId == taskId) it.copy(output = BACKGROUND_NO_ANSWER, running = false) else it
                })
            }
        }
    }

    private fun isRunningTask(taskId: String) =
        _state.value.messages.any { it is ChatMessage.Command && it.taskId == taskId && it.running }

    /**
     * `/reasoning [level|show|hide] [--global]` through `config.set reasoning`, as the TUI does; bare,
     * it reports the current setting. Returns the value the gateway settled on.
     */
    suspend fun reasoning(arg: String): String? {
        var settled: String? = null
        runOnGateway("/reasoning") { client, runtimeId ->
            val words = arg.trim().split(Regex("""\s+""")).filter { it.isNotEmpty() }
            val scope = when {
                words.any { it.lowercase() in GLOBAL_FLAGS } -> "global"
                words.any { it.lowercase() in SESSION_FLAGS } -> "session"
                else -> null
            }
            val value = words.filterNot { it.lowercase() in GLOBAL_FLAGS || it.lowercase() in SESSION_FLAGS }.joinToString(" ")
            if (value.isEmpty()) {
                val current = client.request(
                    "config.get",
                    buildJsonObject {
                        put("key", "reasoning")
                        put("session_id", runtimeId)
                    },
                ) as? JsonObject
                return@runOnGateway "Reasoning: ${current.string("value")?.ifBlank { null } ?: "medium"} · " +
                    "thinking ${if (current.string("display") == "show") "shown" else "hidden"}"
            }
            val result = client.request(
                "config.set",
                buildJsonObject {
                    put("key", "reasoning")
                    put("session_id", runtimeId)
                    put("value", value)
                    scope?.let { put("scope", it) }
                },
            ) as? JsonObject
            val applied = result.string("value") ?: value
            settled = applied
            if (ReasoningEffort.fromWire(applied) != null) {
                _state.update { it.copy(reasoningEffort = applied, reasoningRevision = it.reasoningRevision + 1) }
            }
            when (applied) {
                "show" -> "Thinking is shown."
                "hide" -> "Thinking is hidden."
                else -> "Reasoning set to $applied" + if (scope == "global") " for every chat." else "."
            }
        }
        return settled
    }

    /** `/yolo`: skips (or restores) tool approvals for this chat only (`config.set yolo`, session scope). */
    suspend fun toggleYolo() = runOnGateway("/yolo") { client, runtimeId ->
        val next = _state.value.yolo != true
        val result = client.request(
            "config.set",
            buildJsonObject {
                put("key", "yolo")
                put("session_id", runtimeId)
                put("value", if (next) "1" else "0")
            },
        ) as? JsonObject
        val on = result.string("value") == "1"
        _state.update { it.copy(yolo = on) }
        if (on) "YOLO on: this chat runs tools without asking for approval." else "YOLO off: risky tools ask for approval again."
    }

    /**
     * `/title <name>`: renames the chat through `session.title` (REST can't address a live session's row yet). The
     * session authority has none: there it is a `session.mutate` `rename`.
     */
    suspend fun retitle(title: String) = runOnGateway("/title") { client, runtimeId ->
        if (client.sessionAuthority) {
            val result = try {
                mutateFenced(client, runtimeId, "rename", CHANGED_WHILE_RENAMING) { put("title", title.trim()) }
            } catch (e: RpcException) {
                // hermes-agent (v0.23.4) answers a title another chat has with a bare internal error: the store's
                // "already in use" isn't mapped to a refusal (persistence/sqlite/conversation/_store/titles.py).
                if (e.code != INTERNAL_ERROR) throw e
                throw RpcException(e.code, RENAME_FAILED, e.reason, e.data)
            }
            val final = (if (result?.containsKey("title") == true) result.string("title") else title).orEmpty().trim()
            _state.update { it.copy(title = final.ifEmpty { null }) }
            return@runOnGateway if (final.isEmpty()) "Title cleared." else "Title set: $final"
        }
        val result = client.request(
            "session.title",
            buildJsonObject {
                put("session_id", runtimeId)
                put("title", title.trim())
            },
        ) as? JsonObject
        val final = (result.string("title") ?: title).trim()
        _state.update { it.copy(title = final.ifEmpty { null }) }
        when {
            final.isEmpty() -> "Title cleared."
            result.boolean("pending") == true -> "Title set: $final (saved once the chat starts)."
            else -> "Title set: $final"
        }
    }

    /**
     * `/branch [count]`: copies this chat (or its first [count] messages) into a new one, as Desktop's
     * branch does. Returns the new chat's stored id and title to open, or null when it failed.
     */
    suspend fun branch(count: Int?): Pair<String, String?>? {
        if (!rowExists) {
            showCommandOutput("/branch", "Nothing to branch yet. Send a message first.", failed = true)
            return null
        }
        // The gateway's count takes in the hidden rows (a starting instruction, compaction wrappers) the chat doesn't show.
        val keep = count?.let {
            if (!loadAllHistory()) return null
            withHiddenRows(it)
        }
        return branchKeeping(keep) {
            if (count == null || keep == null) return@branchKeeping null
            // Past the loaded rows (turns of ours since they were read): counted on the rows as stored now.
            lastCountedRowId(keep, rows) ?: currentRows()?.let { lastCountedRowId(withHiddenRows(count, it), it) ?: it.lastOrNull()?.id }
        }
    }

    /**
     * "Branch from here": a new chat with this one up to and with the message [key], a reply with all its steps.
     * Counted on the stored rows, as the gateway counts them: a reply split by its tool calls is one message here
     * but a row per step there, and hidden rows show nowhere. Null when it failed.
     */
    suspend fun branchFrom(key: String): Pair<String, String?>? {
        if (!rowExists) return branch(null)
        // Counted from the first row, so the pages not scrolled back to yet are read first.
        if (!loadAllHistory()) return null
        val messages = _state.value.messages
        val index = messages.indexOfFirst { it.key == key }
        if (index < 0) return null
        // Up to the row the next message starts at; the last message keeps every row.
        val next = messages.drop(index + 1).firstNotNullOfOrNull { message -> message.firstStoredRowId() }
        val end = next?.let { id -> rows.indexOfFirst { it.id == id } }?.takeIf { it >= 0 } ?: rows.size
        val message = messages[index]
        // The session authority cuts at a row id: the newest stored row the message reaches.
        return branchKeeping(rows.take(end).count { it.countsInBranch }) {
            if (message.firstStoredRowId() != null) rows.take(end).lastOrNull()?.id else liveRowId(messages, index)
        }
    }

    /**
     * The newest stored row of the message at [index], shown from the live stream. The loaded rows don't have it
     * (they are read on opening, not after each turn of ours), so it is placed among the rows stored since: the
     * live prompts are those rows' prompts in order, and a reply reaches up to the next prompt's row. Null when
     * they can't be read, or don't pair up (a prompt that never reached the gateway).
     */
    private suspend fun liveRowId(messages: List<ChatMessage>, index: Int): Long? {
        val message = messages[index]
        if (message is ChatMessage.User) message.rowId?.let { return it }
        val known = rows.mapNotNullTo(HashSet()) { it.id }
        val since = currentRows()?.takeLastWhile { it.id !in known } ?: return null
        // Read as the chat shows them: a `user` row may be a note (a model switch, a finished process), not a prompt.
        val prompts = historyToMessages(since).filterIsInstance<ChatMessage.User>().mapNotNullTo(HashSet()) { it.firstStoredRowId() }
        val stored = since.indices.filter { since[it].id in prompts }
        val live = messages.withIndex().filter { it.value is ChatMessage.User && it.value.firstStoredRowId() == null }
        if (live.size != stored.size) return null
        val sent = live.count { it.index <= index }
        if (message is ChatMessage.User) return since[stored[sent - 1]].id
        val end = stored.getOrNull(sent) ?: since.size
        return (since.getOrNull(end - 1) ?: rows.lastOrNull())?.id
    }

    /** The stored row a message starts at: a folded group is keyed by its newest row, but starts at its first. */
    private fun ChatMessage.firstStoredRowId(): Long? =
        storedRowId(((this as? ChatMessage.Event)?.event as? TranscriptEvent.SystemMessages)?.firstKey ?: key)

    /**
     * A new chat with the first [keep] rows `session.branch` counts, or the whole chat when null. The session
     * authority has no `session.branch`: there it is a `session.mutate` branch up to and with the row [through]
     * finds, asked only there.
     */
    private suspend fun branchKeeping(keep: Int?, through: suspend () -> Long?): Pair<String, String?>? {
        var branched: Pair<String, String?>? = null
        runOnGateway("/branch") { client, runtimeId ->
            val result = if (client.sessionAuthority) {
                val row = if (keep == null) null else through() ?: throw RpcException(0, BRANCH_ROW_UNKNOWN)
                mutateFenced(client, runtimeId, "branch", CHANGED_WHILE_BRANCHING) {
                    row?.let { put("through_message_id", it) }
                }
            } else {
                val params = buildJsonObject {
                    put("session_id", runtimeId)
                    keep?.let { put("count", it) }
                }
                client.request(if (keep == null) "session.branch_whole" else "session.branch", params) as? JsonObject
            }
            // The session authority names the new chat `branched_session_id` and leaves its title to the chat list.
            val stored = result.string("stored_session_id") ?: result.string("branched_session_id")
                ?: throw RpcException(0, "The gateway didn't say where the branch went.")
            val title = result.string("title")?.takeIf { it.isNotBlank() }
            branched = stored to title
            "Branched into ${title?.let { "“$it”" } ?: "a new chat"}."
        }
        return branched
    }

    /**
     * A `session.mutate` [operation] (`branch`, `model`, `rename`, `compress`) on the session authority
     * (gateway/session_mutations.py), as Hermes Desktop sends it: fenced by the session's revision and execution
     * generation. A refusal for stale ones carries the current ones, so it goes once more with those; a second refusal
     * is the error, as [changed].
     */
    @OptIn(ExperimentalUuidApi::class)
    private suspend fun mutateFenced(
        client: JsonRpcClient,
        runtimeId: String,
        operation: String,
        changed: String,
        timeoutMs: Long = 30_000,
        payload: JsonObjectBuilder.() -> Unit,
    ): JsonObject? {
        suspend fun send(expectedRevision: Long, expectedGeneration: Long) = client.request(
            "session.mutate",
            buildJsonObject {
                put("session_id", runtimeId)
                // Nothing is stored for a refused request, so each attempt is its own.
                put("request_id", Uuid.random().toString())
                put("expected_revision", expectedRevision)
                put("expected_generation", expectedGeneration)
                put("operation", operation)
                putJsonObject("payload", payload)
            },
            timeoutMs = timeoutMs,
        ) as? JsonObject
        return try {
            // Unknown only if the gateway sent neither; its refusal then says what they are.
            send(revision ?: 0, executionGeneration ?: 0)
        } catch (e: RpcException) {
            val fresh = e.data.long("revision")
            val generation = e.data.long("execution_generation")
            if (e.reason !in FENCE_REFUSALS) throw e
            // hermes-agent v0.22.1 refuses without the current ones, so there is nothing to retry with.
            if (fresh == null || generation == null) throw RpcException(e.code, changed, e.reason, e.data)
            revision = fresh
            executionGeneration = generation
            try {
                send(fresh, generation)
            } catch (again: RpcException) {
                if (again.reason !in FENCE_REFUSALS) throw again
                throw RpcException(again.code, changed, again.reason, again.data)
            }
        }
    }

    /**
     * The loaded rows with those stored since joined on, read without showing them; null when the newest page
     * couldn't be read, no longer meets them, or (none loaded) may not start at the chat's first row.
     */
    private suspend fun currentRows(): List<SessionMessage>? {
        val id = _state.value.storedSessionId ?: return null
        val page = (sessions.messages(gateway, id, limit = HISTORY_PAGE_MAX, profile = profile) as? ApiResult.Success)?.value?.messages
            ?: return null
        if (rows.isEmpty() && page.size >= HISTORY_PAGE_MAX) return null
        return rows.afterNewestPage(page, HISTORY_PAGE_MAX)
    }

    /** The id of the [keep]th of [rows] `session.branch` counts; null when there are fewer. */
    private fun lastCountedRowId(keep: Int, rows: List<SessionMessage>): Long? {
        var counted = 0
        for (row in rows) {
            if (row.countsInBranch && ++counted == keep) return row.id
        }
        return null
    }

    /**
     * How many stored user and assistant rows with text the first [visible] shown ones reach, as `session.branch`
     * counts them (`_visible_branch_history`): with the hidden rows among them.
     */
    private fun withHiddenRows(visible: Int, rows: List<SessionMessage> = this.rows): Int {
        // The rows the chat starts a message at, as it shows them: a row it leaves out (a compaction reminder, a
        // restated request whose original is loaded) starts none, and a reply's next step adds to that reply. A
        // message may start at a row the gateway doesn't count, such as a reply's first step with only a tool call,
        // and one row can start several (a batch of finished processes). Laid out as on screen, so a running turn's
        // first step starts its own reply.
        val starts = historyToMessages(rows, rowsSessionId, rowsLaidOutFor).mapNotNull { it.firstStoredRowId() }
            .groupingBy { it }.eachCount()
        var seen = 0
        var counted = 0
        // User rows the chat leaves out, kept only once a later row shows they sit inside the messages kept: after
        // the last one, they belong to the next turn (its hidden prompt, say).
        var leftOut = 0
        for (row in rows) {
            starts[row.id]?.let { here ->
                if (seen >= visible) return counted
                seen += here
                counted += leftOut
                leftOut = 0
            }
            if (!row.countsInBranch) continue
            if (row.role == "user" && row.id !in starts) {
                leftOut++
            } else {
                counted += leftOut + 1
                leftOut = 0
            }
        }
        return counted + (visible - seen).coerceAtLeast(0)
    }

    /** A row `session.branch` counts (`_visible_branch_history`): a user or assistant one with text, hidden or not. */
    private val SessionMessage.countsInBranch: Boolean
        get() = (role == "user" || role == "assistant") && text.isNotBlank()

    /** The stored row id a message's key names (`row-12`, or `row-12-0` for one of its parts); null for a live one. */
    private fun storedRowId(key: String): Long? = ROW_KEY.matchEntire(key)?.groupValues?.get(1)?.toLongOrNull()

    /**
     * `/handoff <platform>`: queues this chat for a messaging platform's home channel and waits for the
     * gateway's messaging service to claim it (`handoff.request`, then `handoff.state`), like Desktop.
     */
    suspend fun handoff(platform: String) {
        val target = platform.trim().lowercase()
        if (target.isEmpty()) {
            showCommandOutput("/handoff", "Usage: /handoff <platform>, e.g. /handoff telegram.")
            return
        }
        runOnGateway("/handoff $target", HANDOFF_WAIT_MS) { client, runtimeId ->
            client.request(
                "handoff.request",
                buildJsonObject {
                    put("session_id", runtimeId)
                    put("platform", target)
                },
            )
            val deadline = TimeSource.Monotonic.markNow() + HANDOFF_WAIT_MS.milliseconds
            while (deadline.hasNotPassedNow()) {
                delay(800)
                val record = runCatching {
                    client.request("handoff.state", buildJsonObject { put("session_id", runtimeId) }) as? JsonObject
                }.getOrNull() ?: continue
                when (record.string("state")) {
                    "completed" -> return@runOnGateway "Handed off to $target. Continue the chat there."
                    "failed" -> throw RpcException(0, record.string("error")?.ifBlank { null } ?: "The handoff to $target failed.")
                }
            }
            val cleanup = runCatching {
                client.request(
                    "handoff.fail",
                    buildJsonObject {
                        put("session_id", runtimeId)
                        put("error", "Timed out waiting for the messaging service.")
                    },
                ) as? JsonObject
            }.getOrNull()
            if (cleanup.string("state") == "completed") "Handed off to $target. Continue the chat there."
            else throw RpcException(0, "Nothing picked up the handoff. Is the gateway's messaging service running?")
        }
    }

    /** Shows [text] under [command] at once, for commands this client answers itself. */
    fun showCommandOutput(command: String, text: String, failed: Boolean = false) {
        finishCommand(addCommand(command), text, failed)
    }

    private suspend fun runOnGateway(
        command: String,
        timeoutMs: Long = COMMAND_TIMEOUT_MS,
        call: suspend (JsonRpcClient, String) -> String,
    ) {
        val key = addCommand(command)
        val client = connectedClient() ?: return finishCommand(key, NOT_CONNECTED, failed = true)
        try {
            val output = withTimeout(timeoutMs + 5_000) { call(client, ensureAttached(client)) }
            finishCommand(key, output)
        } catch (e: TimeoutCancellationException) {
            finishCommand(key, "$command took too long to answer.", failed = true)
        } catch (e: RpcTimeoutException) {
            finishCommand(key, "$command took too long to answer.", failed = true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            finishCommand(key, e.message ?: "$command failed.", failed = true)
        }
    }

    private fun addCommand(command: String): String {
        var key = ""
        _state.update {
            key = "cmd-${it.keySeq}"
            it.copy(messages = it.messages + ChatMessage.Command(key, command), keySeq = it.keySeq + 1, error = null)
        }
        return key
    }

    private fun finishCommand(key: String, output: String, failed: Boolean = false) = _state.update { state ->
        state.copy(
            messages = state.messages.map {
                if (it is ChatMessage.Command && it.key == key) {
                    // The slash worker prints for a terminal; its colour codes mean nothing here.
                    it.copy(output = output.replace(ANSI_ESCAPE, "").trimEnd(), running = false, failed = failed)
                } else {
                    it
                }
            },
        )
    }

    private fun removeMessage(key: String) = _state.update { state -> state.copy(messages = state.messages.filterNot { it.key == key }) }

    /**
     * Slips [text] into the running turn with `session.steer`: the agent reads it after its current tool
     * call, without stopping. With nothing running, or when the gateway turns it down (`rejected`), it goes
     * as the next prompt instead; an agent that can't steer gets a plain prompt, which the gateway's busy mode
     * folds in as before.
     *
     * On the session authority, a refusal that came before any agent saw the text (the turn it was meant for
     * ended) sends it as the next prompt too. A `rejected` the agent may already have read isn't sent again.
     *
     * A steer the turn ended before reading isn't lost: the gateway queues the leftover as the next prompt,
     * and that turn shows up like one started elsewhere, its prompt pulled from the transcript.
     */
    suspend fun steer(text: String): SendOutcome {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return SendOutcome.NotSent
        val client = connectedClient()
        val runtimeId = client?.let(::liveRuntimeId)
        // Offline or mid-reconnect, submit says so or attaches again, and queues the text.
        if (!_state.value.running || client == null || runtimeId == null) return submit(trimmed, queue = true)
        val key = "local-${_state.value.keySeq}"
        _state.update {
            it.copy(messages = it.messages + ChatMessage.User(key, trimmed, pending = true), keySeq = it.keySeq + 1, error = null)
        }
        val reply = try {
            steerOnGateway(client, runtimeId, trimmed)
        } catch (e: CancellationException) {
            throw e
        } catch (e: RpcException) {
            removeMessage(key)
            if (e.code == STEER_UNSUPPORTED) return submit(trimmed)
            // Refused before any agent saw it: the turn it was meant for is gone, or can't take a steer. It runs next.
            if (client.sessionAuthority && e.reason in UNDELIVERED_STEER_REFUSALS) return submit(trimmed, queue = true)
            _state.update { it.copy(error = e.message.ifBlank { "Couldn't steer the reply." }) }
            return SendOutcome.NotSent
        } catch (e: SteerNotSent) {
            // The running turn couldn't be read, so nothing went out: the text goes back.
            removeMessage(key)
            _state.update { it.copy(error = "Couldn't reach the running reply, so the steer wasn't sent.") }
            return SendOutcome.NotSent
        } catch (e: Exception) {
            // Out without a reply: the agent may have read it, so it isn't handed back to go twice. Like a
            // lost correction, the bubble stays marked; a steer leaves nothing in the transcript to settle it.
            return settleUnanswered(key, arrived = null, UnsettledPrompt(trimmed, display = null, startsTurn = false, steer = true)) {}
        }
        val status = reply.string("status")
        if (status == STEER_REJECTED && client.sessionAuthority && reply.boolean("delivered") != false) {
            // The session authority's plain `rejected` can follow delivery (a steer whose acknowledgement timed
            // out), so it isn't sent again; only `delivered: false` says no agent saw it.
            return settleUnanswered(
                key,
                arrived = null,
                UnsettledPrompt(trimmed, display = null, startsTurn = false, steer = true),
                unknownError = STEER_MAYBE_DELIVERED,
            ) {}
        }
        if (status != STEER_ACCEPTED) {
            // Turned down: the turn ended meanwhile, or the agent can't take it now. It runs next instead.
            removeMessage(key)
            return submit(trimmed, queue = true)
        }
        _state.update { state ->
            // Like a correction: what streamed so far stays above it, the rest of the turn continues below.
            state.copy(messages = state.messages.updateUser(key) { it.copy(pending = false) }).sealReplyBefore(key)
        }
        return SendOutcome.Sent
    }

    /**
     * Sends `session.steer` for [text]. The session authority takes the running turn's `execution_generation`
     * with it, and refuses the steer without one (gateway/session_busy_controls.py `correct`); other gateways
     * take only the id and the text, and refuse any other key. A generation that isn't known yet is read with
     * `session.resume` first; null when that shows no turn running, so nothing went to one. One the authority
     * calls stale isn't tried again with a newer one, as Desktop doesn't: the turn running by then may be
     * another prompt's, so the caller sends the text as the next prompt.
     *
     * @throws SteerNotSent when the running turn couldn't be read.
     */
    private suspend fun steerOnGateway(client: JsonRpcClient, runtimeId: String, text: String): JsonObject? {
        suspend fun send(id: String, generation: Long?) = client.request(
            "session.steer",
            buildJsonObject {
                put("session_id", id)
                put("text", text)
                generation?.let { put("execution_generation", it) }
            },
        ) as? JsonObject
        if (!client.sessionAuthority) return send(runtimeId, null)
        executionGeneration?.let { return send(runtimeId, it) }
        val fresh = try {
            attach(client, reconnected = true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Not left "Attaching": the chat shows the failure with its retry, as a failed send's attach does.
            _state.update {
                if (it.attachment !is Attachment.Attaching) it
                else it.copy(
                    attachment = Attachment.Failed(e.message ?: "Couldn't open the session."),
                    // As runCatchingAttach: a chat the gateway turned down has no turn left to follow.
                    running = it.running && e !is RpcException,
                )
            }
            throw SteerNotSent(e)
        }
        val generation = executionGeneration?.takeIf { _state.value.running } ?: return null
        return send(fresh, generation)
    }

    /** A resume overtaken by a change of connection. */
    private class StaleResume : Exception()

    /** A steer that never went out: the running turn's generation couldn't be read ([cause]). */
    private class SteerNotSent(cause: Throwable) : Exception(cause.message, cause)

    /**
     * Stops the running reply (`session.interrupt`), waits for the turn to settle, then sends [text] as a
     * fresh turn. The gateway drops the prompts queued behind the stopped turn; their text comes back in
     * [StopAndSend.dropped]. A turn that won't settle in time gets the prompt queued behind it, so it still
     * runs after it rather than folding into the turn it was meant to replace.
     */
    suspend fun stopAndSubmit(
        text: String,
        attachments: List<OutgoingAttachment> = emptyList(),
        display: String? = null,
        voiceLive: VoiceLiveTurn? = null,
    ): StopAndSend {
        if (!_state.value.running) return StopAndSend(submit(text, attachments, display, voiceLive = voiceLive))
        val dropped = interruptTurn() ?: run {
            _state.update { it.copy(error = it.error ?: NOT_CONNECTED) }
            return StopAndSend(SendOutcome.NotSent)
        }
        val settled = withTimeoutOrNull(STOP_SETTLE_TIMEOUT_MS) { _state.first { !it.running } } != null
        return StopAndSend(submit(text, attachments, display, queue = !settled, voiceLive = voiceLive), dropped)
    }

    /**
     * Asks the gateway to stop the running turn; `message.complete {status: interrupted}` follows. The
     * gateway drops the prompts queued behind the turn too, so their bubbles go and their text comes back,
     * in order, for the composer.
     */
    suspend fun interrupt(): List<String> = interruptTurn().orEmpty()

    /** [interrupt], null when the turn couldn't be stopped (the reason is in [ChatState.error]). */
    private suspend fun interruptTurn(): List<String>? {
        val client = connectedClient() ?: return null
        // Tapped mid-reconnect, an id from the old socket would be turned down: a stored chat's stop waits for the
        // re-attach instead. A new chat has nothing to stop until it's created.
        if (liveRuntimeId(client) == null && !rowExists) return null
        try {
            val runtimeId = ensureAttached(client)
            interruptOnGateway(client, runtimeId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(error = e.message ?: "Couldn't stop the turn.") }
            // Turned down by the gateway, the runtime may be gone (its bot deleted mid-turn, say): asked again,
            // a chat that still exists shows its turn as it is, and one that doesn't stops showing one.
            if (e is RpcException) runCatchingAttach(client, reconnected = true)
            return null
        }
        var dropped = emptyList<String>()
        _state.update { state ->
            val (queued, kept) = state.messages.partition { it is ChatMessage.User && it.queued }
            dropped = queued.map { (it as ChatMessage.User).text }
            state.copy(messages = kept)
        }
        return dropped
    }

    /**
     * Sends `session.interrupt` with `execution_generation` when known (required by canonical session authority).
     * If a session-authority socket lacks the generation, re-attaches first to fetch it. Returns the chat id the
     * interrupt went to, which the re-attach can change.
     */
    private suspend fun interruptOnGateway(client: JsonRpcClient, runtimeId: String): String {
        val targetId = if (executionGeneration == null && client.sessionAuthority && rowExists) {
            attach(client, reconnected = true)
        } else {
            runtimeId
        }
        val gen = executionGeneration
        client.request(
            "session.interrupt",
            buildJsonObject {
                put("session_id", targetId)
                gen?.let { put("execution_generation", it) }
            },
        )
        return targetId
    }

    /** Stops one running subagent (`subagent.interrupt`); its `subagent.complete` follows. */
    suspend fun stopSubagent(subagentId: String) {
        val client = connectedClient() ?: return
        val runtimeId = liveRuntimeId(client) ?: return
        try {
            val result = client.request(
                "subagent.interrupt",
                buildJsonObject {
                    put("session_id", runtimeId)
                    put("subagent_id", subagentId)
                },
            ) as? JsonObject
            // Already over: nothing will say so, so stop showing it as running.
            if (result.boolean("found") == false) {
                _state.update { state ->
                    state.copy(subagents = state.subagents.map {
                        if (it.id == subagentId && it.status.live) it.copy(status = SubagentStatus.Interrupted) else it
                    })
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(error = e.message ?: "Couldn't stop the subagent.") }
        }
    }

    /**
     * The subagents running now: the gateway's roster says which, with the activity this chat has seen. Null when
     * the chat isn't attached or the answer didn't come.
     */
    suspend fun activeSubagents(): List<SubagentRow>? {
        val client = connectedClient() ?: return null
        val runtimeId = liveRuntimeId(client) ?: return null
        val snapshots = subagentSnapshots(client, runtimeId) ?: return null
        return _state.value.activeSubagentRows(snapshots)
    }

    /** Subagents already running when this chat attached; events supply their subsequent activity. */
    private suspend fun refreshSubagents(client: JsonRpcClient, runtimeId: String) {
        val snapshots = subagentSnapshots(client, runtimeId) ?: return
        if (snapshots.isNotEmpty()) _state.update { it.withSubagentSnapshots(snapshots) }
    }

    private suspend fun subagentSnapshots(client: JsonRpcClient, runtimeId: String): List<JsonObject>? {
        val result = try {
            client.request("subagent.list", buildJsonObject { put("session_id", runtimeId) }) as? JsonObject
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        if (connectedClient() !== client || liveRuntimeId(client) != runtimeId) return null
        return (result?.get("subagents") as? JsonArray)?.asObjectList()
    }

    /**
     * Switches this chat's model. A new chat only remembers the pick for `session.create`; a live one
     * switches at once, or at the next turn when one is running. Pricey models ask to [confirm] first.
     */
    suspend fun setModel(model: String, provider: String, confirm: Boolean = false): ModelSwitch {
        val previous = _state.value
        if (!rowExists && previous.runtimeSessionId == null) {
            _state.update { it.copy(model = model, provider = provider) }
            return ModelSwitch.Done
        }
        _state.update { it.copy(model = model, provider = provider) }
        return try {
            val client = connectedClient() ?: throw RpcException(0, "Not connected to the gateway.")
            // --session: a pick on the phone must not quietly rewrite the profile default (config.yaml).
            suspend fun overConfigSet() = configSet("model", JsonPrimitive("$model --provider $provider --session")) {
                if (confirm) put("confirm_expensive_model", true)
            }
            val result = if (client.sessionAuthority) {
                try {
                    mutateModel(client, model, provider, confirm)
                } catch (e: RpcException) {
                    // Every session authority so far (hermes-agent v0.22.1 on) has it; one that doesn't gets the old call.
                    if (e.code != METHOD_NOT_FOUND) throw e
                    overConfigSet()
                }
            } else {
                overConfigSet()
            }
            // The session authority marks a guarded pick with both; key on either.
            if (result.boolean("confirm_required") == true || result.string("status") == "confirmation_required") {
                _state.update { it.copy(model = previous.model, provider = previous.provider) }
                modelConfirm = result.string("confirm")?.let { Triple(model, provider, it) }
                ModelSwitch.NeedsConfirmation(result.string("confirm_message") ?: "This model costs more than usual. Switch anyway?")
            } else {
                ModelSwitch.Done
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(model = previous.model, provider = previous.provider, error = e.message ?: "Couldn't switch the model.") }
            ModelSwitch.Failed
        }
    }

    /** Sets the reasoning effort ([effort] `none` turns thinking off) for this chat only. */
    suspend fun setReasoningEffort(effort: String) =
        setLive({ it.copy(reasoningEffort = effort, reasoningRevision = it.reasoningRevision + 1) }) {
            configSet("reasoning", JsonPrimitive(effort))
        }

    /** Turns the priority tier on or off for this chat only. */
    suspend fun setFast(fast: Boolean) = setLive({ it.copy(fast = fast) }) {
        configSet("fast", JsonPrimitive(if (fast) "fast" else "normal"))
    }

    /** Applies [apply] at once and sends it with [call] when the chat is live; rolls back on failure. */
    private suspend fun setLive(apply: (ChatState) -> ChatState, call: suspend () -> Unit) {
        val previous = _state.value
        _state.update(apply)
        if (!rowExists && previous.runtimeSessionId == null) return
        try {
            call()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(reasoningEffort = previous.reasoningEffort, fast = previous.fast, error = e.message ?: "Couldn't change the setting.") }
        }
    }

    /**
     * The session authority refuses `config.set model` (`use_session_mutation_model`, gateway/session_busy_controls.py):
     * there a pick is `session.mutate` `model`, as Hermes Desktop sends it. A guarded (expensive) target answers
     * `confirmation_required` with a `confirm` token, and only that token applies it; [confirm] sends the one the
     * gateway gave for this pick. A pick during a turn is deferred to the next one, which is still a switch.
     */
    private suspend fun mutateModel(client: JsonRpcClient, model: String, provider: String, confirm: Boolean): JsonObject? {
        val runtimeId = ensureAttached(client)
        val token = modelConfirm?.takeIf { confirm && it.first == model && it.second == provider }?.third
        modelConfirm = null
        return try {
            mutateFenced(client, runtimeId, "model", CHANGED_WHILE_SWITCHING) {
                put("model", model)
                put("provider", provider)
                token?.let { put("confirm", it) }
            }
        } catch (e: RpcException) {
            // v0.22.1 refuses a pick during a turn; later versions defer it to the next turn.
            if (e.reason != "session_busy") throw e
            throw RpcException(e.code, SWITCH_WHEN_DONE, e.reason, e.data)
        }
    }

    /** Session-scoped `config.set`; attaches first, since the key needs a live runtime session. */
    private suspend fun configSet(key: String, value: JsonElement, extra: JsonObjectBuilder.() -> Unit = {}): JsonObject? {
        val client = connectedClient() ?: throw RpcException(0, "Not connected to the gateway.")
        val runtimeId = ensureAttached(client)
        return client.request(
            "config.set",
            buildJsonObject {
                put("session_id", runtimeId)
                put("key", key)
                put("value", value)
                extra()
            },
        ) as? JsonObject
    }

    /** Retries the transcript load or a failed attach. */
    fun retry() {
        scope.launch { if (rowExists) loadHistory() }
        val client = connectedClient() ?: return connection.retry()
        if (_state.value.attachment is Attachment.Failed) scope.launch { runCatchingAttach(client) }
    }

    fun dismissError() = _state.update { it.copy(error = null, refused = null) }

    /** Hides a gateway notice on this phone (its ×). The gateway isn't told. */
    fun dismissNotice(key: String) = _state.update { it.withoutNotice(key) }

    /** Shows a title set elsewhere (a REST rename) without waiting for the gateway to echo it. */
    fun showTitle(title: String?) = _state.update { it.copy(title = title) }

    /**
     * Moves a chat the gateway hasn't made yet to [folder] (a project's; null: the profile's default), so its first
     * send starts there, with the model and text picked for it kept. False, leaving it, once it's being made or is.
     */
    fun startIn(folder: String?): Boolean {
        if (!unstarted) return false
        cwd = folder
        return true
    }

    /** Not made on the gateway yet, nor being made: [startIn] can still move it. */
    val unstarted: Boolean
        get() = _state.value.let { !rowExists && it.storedSessionId == null && it.attachment == Attachment.Detached }

    /**
     * Shows a model set elsewhere (a Bot Chat's bot's own, which the chat follows) without asking the
     * gateway to pin it here; [error] says it couldn't be set.
     */
    fun showModel(model: String?, provider: String?, error: String? = null) =
        _state.update { if (error != null) it.copy(error = error) else it.copy(model = model, provider = provider) }

    /**
     * Sends [result] as the answer to [request] (see [InputAnswers]). The request leaves the state
     * once the answer is out; another client may have answered first, which the gateway ignores.
     */
    suspend fun answer(request: InputRequest, result: JsonObject): Boolean {
        val client = connectedClient() ?: run {
            _state.update { it.copy(error = "Not connected to the gateway. Answer again once it reconnects.") }
            return false
        }
        return try {
            val delivery = request.delivery
            val method = InputRequest.respondMethod(request)
            if (delivery is InputRequestDelivery.PendingControl && method != null) {
                // Refused before attaching, so nothing at all goes out.
                InputRequest.pendingControlRefusal(request, result)?.let { throw IllegalArgumentException(it) }
                val runtimeId = liveRuntimeId(client) ?: ensureAttached(client)
                client.request(method, InputRequest.pendingControlParams(request, runtimeId, result))
            } else {
                client.respond(request.id, result)
            }
            _state.update { state -> state.copy(inputRequests = state.inputRequests.filterNot { it.id == request.id }) }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(error = e.message ?: "Couldn't send the answer.") }
            false
        }
    }

    private suspend fun followConnection() {
        var attachedBefore = false
        connection.state.collectLatest { connectionState ->
            if (connectionState !is ConnectionState.Connected) {
                _state.update { it.copy(attachment = Attachment.Detached) }
                return@collectLatest
            }
            if (!rowExists) { // a new chat attaches on first send
                refreshProfileReasoning(connectionState.client)
                return@collectLatest
            }
            // Load what couldn't be read before the link came up; after a drop, attaching catches up.
            // A load still running (showing the saved copy meanwhile) isn't one that failed: it brings the rows itself.
            if (!attachedBefore && !_state.value.historyRead && !historyMutex.isLocked) loadHistory()
            // A chat created on this link was live too, though it never resumed: it catches up the same way.
            val reconnected = attachedBefore || streamMutex.withLock { seqRuntime != null }
            // A send on this link may have attached first; that runtime is kept, not resumed a second time.
            if (runCatchingAttach(connectionState.client, reconnected = reconnected, reuse = true)) attachedBefore = true
        }
    }

    private suspend fun followReactionSupport() {
        connection.state.collectLatest { state ->
            val client = (state as? ConnectionState.Connected)?.client ?: return@collectLatest
            client.replyReactionsSupported.collect { supported ->
                _state.update { it.copy(replyReactionsSupported = supported) }
            }
        }
    }

    private suspend fun followEvents() {
        connection.state.collectLatest { state ->
            val client = (state as? ConnectionState.Connected)?.client ?: return@collectLatest
            client.events.collect { event ->
                val runtimeId = event.sessionId ?: return@collect
                val replayGap = streamMutex.withLock {
                    if (connectedClient() !== client) return@collect
                    val pending = pendingResume
                    if (pending?.client === client && pending.runtimeId == runtimeId) {
                        pending.events += event
                        return@collect
                    }
                    val live = liveRuntimeId(client)
                    if (live == null) {
                        gapHeld?.takeIf { gapRuntime == runtimeId }?.add(event)
                        return@collect
                    }
                    if (live != runtimeId) return@collect
                    if (event.type == REPLAY_GAP && client.sessionAuthority) true else {
                        deliver(event)
                        false
                    }
                }
                if (replayGap) resumeAfterGap(client, runtimeId)
            }
        }
    }

    /**
     * The session authority ended this socket's subscription to the chat: this viewer fell too far behind, so its
     * backlog was dropped (hermes-agent `tui_gateway/transport.py` FanoutTransport.write) and one `session.replay_gap`
     * sent instead (`gateway/session_events.py` SessionEvents.publish). The socket stays up, so no reconnect comes,
     * and nothing of the session arrives until a `session.resume` attaches it again, as Desktop and the TUI do. The
     * hole is longer than `session.events.since` keeps, so the chat reloads the transcript, as after a drop with no
     * replay. The legacy sidecar sends no such frame (it closes the socket instead), so off the authority it's ignored.
     */
    private suspend fun resumeAfterGap(client: JsonRpcClient, runtimeId: String) {
        streamMutex.withLock {
            // Only this socket's subscription ended; a runtime not attached on it has nothing to take up again.
            if (connectedClient() !== client || liveRuntimeId(client) != runtimeId) return
            if (pendingGapClient === client) return
            pendingGapClient = client
        }
        scope.launch { reattachAfterGap(client, runtimeId) }
    }

    /**
     * The `session.resume` after a gap. The frames since the last one applied are gone, so it picks up from nothing
     * (`afterGap`), and held ones from before the gap are in the transcript it reloads. The new subscription's frames
     * wait for it in [gapHeld], then go through in order; a gap among them takes another resume.
     */
    private suspend fun reattachAfterGap(client: JsonRpcClient, runtimeId: String) {
        val waiting = mutableListOf<GatewayEvent>()
        streamMutex.withLock {
            if (pendingGapClient !== client) return
            pendingGapClient = null
            if (connectedClient() !== client || liveRuntimeId(client) != runtimeId) return
            held = null
            gapRuntime = runtimeId
            gapHeld = waiting
        }
        val attached = runCatchingAttach(client, reconnected = true, afterGap = true)
        val again = streamMutex.withLock {
            // A later gap took over the holding; its resume lets them through.
            if (gapHeld !== waiting) return
            gapHeld = null
            gapRuntime = null
            if (!attached || connectedClient() !== client) return
            if (waiting.none { it.type == REPLAY_GAP }) {
                waiting.forEach(::deliver)
                return
            }
            // The new subscription ended too: take up the runtime the resume named, which may not be the one before.
            liveRuntimeId(client)
        }
        again?.let { resumeAfterGap(client, it) }
    }

    /**
     * Catches the open chat up with the gateway after the app was away. A backgrounded phone misses live
     * events, and a socket that never reported a drop leaves the connection on Connected: no attach fires
     * on the way back, so the transcript stays short until an unrelated event arrives. A chat nothing has
     * been read for yet has nothing to catch up on; the first send attaches it as usual.
     */
    fun resync() {
        val state = _state.value
        val runtimeId = state.runtimeSessionId ?: return
        // Nothing read yet: the first send attaches the chat and loads the transcript as it always does.
        if (!state.historyRead) return
        scope.launch {
            val client = connectedClient() ?: return@launch
            val waiting = streamMutex.withLock {
                // The existing catch-up or gap recovery will pick up events for this socket.
                if (liveRuntimeId(client) != runtimeId || held != null || gapHeld != null || pendingGapClient === client) return@launch
                mutableListOf<GatewayEvent>().also { held = it }
            }
            catchUp(client, runtimeId, waiting, foreground = true)
        }
    }

    /**
     * Applies a live event in order: one already applied (replayed, or sent twice) is skipped, and one past a gap
     * waits, with those after it, while [catchUp] fills the gap. Called under [streamMutex].
     */
    private fun deliver(event: GatewayEvent) {
        gapHeld?.let {
            it += event
            return
        }
        held?.let {
            it += event
            return
        }
        val seq = event.seq
        // The session authority started counting the session anew: seqs from before don't follow. It does that only
        // after the last viewer left (SessionEvents.release), which the next session.resume reports, so this only
        // guards against a frame that says so first. Only there: the legacy gateway's seqs count in gateway.ready's
        // epoch, whatever a frame says.
        event.replayEpoch?.takeIf { attachedClient?.sessionAuthority == true }?.let { epoch ->
            if (epoch != seqEpoch) {
                seqEpoch = epoch
                lastSeq = null
            }
        }
        val last = lastSeq
        if (seq != null && last != null) {
            if (seq <= last) return
            if (seq > last + 1) {
                val client = connectedClient() ?: return
                val runtimeId = liveRuntimeId(client) ?: return
                val waiting = mutableListOf(event)
                held = waiting
                scope.launch { catchUp(client, runtimeId, waiting) }
                return
            }
        }
        apply(event)
    }

    /**
     * Fills the gap after [lastSeq] from the gateway's event ring (`session.events.since`), then lets the held live
     * events through. When the ring can't fill it (too long away, a restarted gateway) the chat
     * reloads the transcript and takes the running reply as the gateway has it, as it did before replay existed.
     */
    private suspend fun catchUp(
        client: JsonRpcClient,
        runtimeId: String,
        waiting: MutableList<GatewayEvent>,
        foreground: Boolean = false,
    ) {
        val (from, epoch) = streamMutex.withLock {
            if (!ownsCatchUp(client, runtimeId, waiting)) return
            lastSeq to seqEpoch
        }
        val missed = if (from == null) null else try {
            val reply = client.request(
                "session.events.since",
                buildJsonObject {
                    put("session_id", runtimeId)
                    put("last_seen", from)
                    // The session authority answers only in the session's epoch, and a gateway before it (up to
                    // v0.22.2) refuses the param: only a session's own epoch is sent. Never `last_sequence`, which
                    // those refuse too.
                    if (client.sessionAuthority && epoch != null) put("replay_epoch", epoch)
                },
            ) as? JsonObject
            EventReplay.parse(reply, runtimeId, from, epoch)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null // the ring couldn't answer, or the link went again
        }
        // Back in sight mid-turn with no replay: the running reply and the gateway's copy of it are from before the
        // app went away, so neither is laid over a fresh transcript. The turn goes on from what shows, as before.
        val keep = missed == null && foreground && _state.value.running
        // Read while live events stay held; the stream lock is only taken to apply.
        if (missed == null && !keep && rowExists && _state.value.runtimeSessionId == runtimeId) loadHistory()
        streamMutex.withLock {
            if (!ownsCatchUp(client, runtimeId, waiting)) return
            if (missed != null) {
                missed.forEach(::apply)
            } else if (!keep) {
                lastSeq = null
                val streamed = inflightText
                if (streamed != null && _state.value.running) {
                    var replyKey: String? = null
                    _state.update { state ->
                        val shown = state.withInflight(streamed)
                        replyKey = shown.messages.inflightReplyKey(streamed)
                        // The transcript just read has the steps saved while away, which the gateway's copy now repeats.
                        shown.copy(messages = shown.messages.withSavedStepsFolded())
                    }
                    // Named by the try of the update that landed, not by one that lost to another change.
                    turnReplyKey = replyKey
                } else if (!_state.value.running) {
                    _state.update { it.withoutStaleReply().withoutAgentNotices() }
                }
            }
            held = null
            waiting.forEach(::deliver)
        }
    }

    /** Only this catch-up may abandon its buffer. Caller holds [streamMutex]. */
    private fun ownsCatchUp(client: JsonRpcClient, runtimeId: String, waiting: MutableList<GatewayEvent>): Boolean {
        if (held !== waiting) return false
        if (connectedClient() === client && liveRuntimeId(client) == runtimeId) return true
        held = null
        return false
    }

    /** Replacing the stream retires all replay work from the previous attachment. Caller holds [streamMutex]. */
    private fun resetReplayWork() {
        held = null
        gapHeld = null
        gapRuntime = null
        pendingGapClient = null
    }

    private fun apply(event: GatewayEvent) {
        event.seq?.let { lastSeq = it }
        // The session authority stamps a turn's events with its generation beside the payload; a few payloads carry it
        // too. Only ever forward, as Desktop keeps it: a replayed or late event of an older turn must not rewind it.
        (event.executionGeneration ?: (event.payload as? JsonObject).long("execution_generation"))?.let { seen ->
            executionGeneration = maxOf(executionGeneration ?: seen, seen)
        }
        if (event.type == "session.info" || event.type == "session.updated") {
            val payload = event.payload as? JsonObject
            // A receipt names its session; only ever forward, as a replayed older one must not rewind it.
            val ours = payload.string("session_id")?.let { it == _state.value.runtimeSessionId } ?: true
            payload.long("revision")?.takeIf { ours }?.let { seen -> revision = maxOf(revision ?: seen, seen) }
        }
        val now = nowSeconds()
        val noticesBefore = _state.value.notices
        // Only an update that carries the state moves it (ChatReducer), so only that one outdates a read that's out.
        if (event.type == "session.control.update" && (event.payload as? JsonObject)?.get("control") is JsonObject) controlWrites++
        // Cleared before the turn's end lands, so no rows laid out meanwhile still take it as running.
        if (event.type == "message.complete") {
            turnStartedAt = null
            turnReplyKey = null
        }
        _state.update { it.reduce(event, now) }
        when (event.type) {
            // Timed here, not on screen, so it ends on time even while the chat isn't showing. A notice that
            // replaced this one by the time it runs out has its own timer.
            "notification.show" -> (_state.value.notices - noticesBefore.toSet()).forEach { notice ->
                val ttl = notice.ttlMillis?.takeIf { notice.kind == GatewayNotice.Kind.Timed } ?: return@forEach
                scope.launch {
                    delay(ttl)
                    _state.update { state -> if (notice in state.notices) state.withoutNotice(notice.key) else state }
                }
            }
            "tool.output_risk" -> flaggedOutput(event.payload as? JsonObject)?.let { (id, risk) ->
                risks?.let { scope.launch { it.remember(id, risk) } }
            }
            "session.control.update" -> controlPublishedOn = attachedClient
            // The goal's judge runs after the turn ends and can finish after the read on `message.complete`, so its
            // verdict is read again on the status it sends (v0.22.1 gateway/run_goals.py `_send_goal_status_notice`).
            "status.update" -> if ((event.payload as? JsonObject).string("kind") == "goal" && controlUnpublished(anyChat = true)) {
                scope.launch { refreshControl() }
            }
            "message.start" -> if (ownTurnsPending > 0) {
                ownTurnsPending--
            } else {
                foreignTurn = true
                scope.launch { loadHistory() }
                // The kickoff of a goal another client just typed: v0.22.1 stored it before the turn, unpublished.
                if (controlUnpublished(anyChat = true)) scope.launch { refreshControl() }
            }
            // Swap the live copy for the stored rows, which now hold the other client's prompt for sure.
            "message.complete" -> {
                // Nothing can still wait on a person once the turn is over; this also clears a
                // clarify answered on another client, which gets no request.cancel.
                _state.update { it.copy(inputRequests = emptyList()) }
                // A gateway that sends no `session.control.update` (v0.22.1's authority): the goal's turn moved it on
                // unseen, so it's read again. A turn from another client may be the kickoff of a goal it just typed
                // into a chat that had none, so that one is read whatever the strip shows.
                if (controlUnpublished(anyChat = foreignTurn)) scope.launch { refreshControl() }
                if (foreignTurn || rewound || stoppedUnnamed(event.payload as? JsonObject)) {
                    foreignTurn = false
                    rewound = false
                    scope.launch { loadHistory() }
                } else if (cache != null) {
                    scope.launch { refreshSavedCopy() }
                }
            }
            "clarify.request", "approval.request" -> {
                val parsed = InputRequest.parseEvent(event.type, event.payload as? JsonObject) ?: return
                _state.update { it.copy(inputRequests = it.inputRequests.plusNew(listOf(parsed))) }
            }
            "request.cancel", "approval.cancelled", "clarify.settled", "approval.settled", "approval.responded" -> {
                val id = InputRequest.settledId(event.type, event.payload as? JsonObject) ?: return
                _state.update { state -> state.copy(inputRequests = state.inputRequests.filterNot { it.id == id }) }
            }
        }
    }

    private suspend fun followServerRequests() {
        connection.serverRequests.collect { request ->
            val sessionId = request.sessionId ?: return@collect
            val parsed = InputRequest.parse(request.id, request.method, request.params) ?: return@collect
            if (sessionId == _state.value.runtimeSessionId) {
                _state.update { it.copy(inputRequests = it.inputRequests.plusNew(listOf(parsed))) }
            } else {
                unclaimed.addLast(sessionId to parsed)
                if (unclaimed.size > MAX_UNCLAIMED) unclaimed.removeFirst()
            }
        }
    }

    private fun claimUnclaimed(runtimeId: String): List<InputRequest> =
        unclaimed.filter { it.first == runtimeId }.map { it.second }.also { unclaimed.removeAll { it.first == runtimeId } }

    /**
     * Reads the newest page of the transcript and lays it over the loaded rows, so older pages scrolled back to
     * stay. When it no longer meets them (many turns since), the chat starts over from the newest page.
     */
    private suspend fun loadHistory(): Unit = historyMutex.withLock {
        val id = _state.value.storedSessionId ?: return
        // Nothing shown yet: the copy saved on this device, while the gateway is asked (which may take long offline).
        if (rows.isEmpty()) {
            cache?.savedTranscript(gateway, profile, id)?.let { saved ->
                rows = saved.rows
                rowsSessionId = saved.rowsSessionId
                olderSkew = 0
                // Earlier pages can't be read until the gateway can, which brings this copy up to date first.
                showRows(risks?.all().orEmpty(), older = false, savedCopyAt = saved.savedAt)
            }
        }
        val result = sessions.messages(gateway, id, limit = HISTORY_PAGE, profile = profile)
        val flagged = risks?.all().orEmpty()
        when (result) {
            is ApiResult.Success -> {
                // The saved copy may be out of date before the page too (an undo, a compression): start over.
                if (_state.value.savedCopyAt != null) rows = emptyList()
                val page = result.value.messages
                val full = page.size >= HISTORY_PAGE
                val joined = if (full && rows.isNotEmpty()) rows.withNewestFull(page) else null
                val older = if (joined != null) _state.value.olderMessages else full
                rows = joined ?: if (full) page.fromFirstTurn() else page
                rowsSessionId = result.value.sessionId
                olderSkew = 0
                showRows(flagged, older)
                saveCopy(id, rows, rowsSessionId)
            }
            else -> {
                val gone = result is ApiResult.Failed && result.status == HTTP_NOT_FOUND
                if (gone) {
                    // A write still under way would bring it back.
                    saveJob?.cancel()
                    savedCopyKey = null
                    cache?.forgetChat(gateway, profile, id)
                    // The saved copy is of a chat that no longer exists.
                    if (_state.value.savedCopyAt != null) {
                        rows = emptyList()
                        showRows(flagged, older = false)
                    }
                }
                _state.update {
                    it.copy(
                        historyLoaded = true,
                        historyError = result.errorMessage,
                        // Gone from the gateway (deleted elsewhere, or with its bot): nothing of it is still running.
                        running = it.running && !gone,
                    )
                }
            }
        }
    }

    /** The last saved copy's row count, newest row ID and user reactions. */
    private var savedCopyKey: Triple<Int, Long?, List<Pair<Long?, String>>>? = null
    private var saveJob: Job? = null
    private val savedCopyRefreshMutex = Mutex()

    /** Writes [saved] as the device's copy of chat [id], off the history lock; a newer copy replaces one still being written. */
    private fun saveCopy(id: String, saved: List<SessionMessage>, savedSessionId: String?) {
        val cache = cache ?: return
        val reactions = saved.mapNotNull { row -> row.userReaction?.let { row.id to it } }
        val key = Triple(saved.size, saved.lastOrNull()?.id, reactions)
        if (key == savedCopyKey) return
        savedCopyKey = key
        saveJob?.cancel()
        saveJob = scope.launch { cache.saveTranscript(gateway, profile, id, saved, savedSessionId) }
    }

    /**
     * Brings the device's copy up to date after a turn of ours: the screen shows it from live events, which the
     * loaded rows don't have. Nothing shown changes.
     */
    private suspend fun refreshSavedCopy() {
        savedCopyRefreshMutex.withLock {
            val id = _state.value.storedSessionId ?: return
            if (!_state.value.historyRead) return
            // A newer saved copy (from a history load) can land while the refresh reads the gateway.
            val previousSave = historyMutex.withLock { saveJob }
            val page = (sessions.messages(gateway, id, limit = HISTORY_PAGE, profile = profile) as? ApiResult.Success)?.value ?: return
            historyMutex.withLock {
                if (!_state.value.historyRead || _state.value.storedSessionId != id || saveJob !== previousSave) return
                val full = page.messages.size >= HISTORY_PAGE
                val newest = if (full) page.messages.fromFirstTurn() else page.messages
                val joined = (if (full) rows.withNewestFull(page.messages) else null) ?: newest
                saveCopy(id, joined, page.sessionId)
            }
        }
    }

    /**
     * Loads the page of the transcript before the loaded rows, when there is one, as the reader nears the top.
     * Its messages go above the ones shown, which keep their keys, so the list stays where it was.
     */
    suspend fun loadOlder() {
        if (_state.value.loadingOlder) return
        loadOlderPage(HISTORY_PAGE)
    }

    /**
     * Loads every older page, for what needs the whole transcript (counting rows to branch at). False when a page
     * couldn't be read.
     */
    suspend fun loadAllHistory(): Boolean {
        // A saved copy says nothing of the rows before it; the gateway's transcript is needed first.
        if (_state.value.savedCopyAt != null) loadHistory()
        if (_state.value.savedCopyAt != null) {
            _state.update { it.copy(error = "Couldn't read the whole conversation from Hermes. ${it.historyError.orEmpty()}".trim()) }
            return false
        }
        repeat(MAX_HISTORY_PAGES) {
            if (!_state.value.olderMessages) return true
            if (!loadOlderPage(HISTORY_PAGE_MAX)) return false
        }
        return !_state.value.olderMessages
    }

    private suspend fun loadOlderPage(limit: Int): Boolean {
        val id = _state.value.storedSessionId ?: return false
        if (!_state.value.olderMessages) return true
        _state.update { it.copy(loadingOlder = true) }
        try {
            return historyMutex.withLock {
                // Counted back from the newest row, so it reads from just before the oldest one loaded.
                val result = sessions.messages(gateway, id, limit = limit, profile = profile, offset = rows.size + olderSkew)
                if (result !is ApiResult.Success) {
                    _state.update { it.copy(error = "Couldn't load earlier messages. ${result.errorMessage}".trim()) }
                    return@withLock false
                }
                val page = result.value.messages
                val full = page.size >= limit
                val known = rows.mapNotNullTo(HashSet()) { it.id }
                // Rows already loaded come again when the transcript grew since: skip them, and past them next time.
                val fresh = (if (full) page.fromFirstTurn() else page).filter { it.id == null || it.id !in known }
                // Past only those: rows before the page's first prompt that aren't loaded yet come with the next page.
                if (fresh.isEmpty() && full) olderSkew += page.count { it.id != null && it.id in known }
                val startedAt = turnStartedAt
                val shownBefore = historyToMessages(rows, rowsSessionId, startedAt).mapTo(HashSet()) { it.key }
                rows = fresh + rows
                val flagged = risks?.all().orEmpty()
                _state.update { state ->
                    // The stored rows lead the list; what follows them (the live turn, local notes) stays as it is.
                    val stored = state.messages.takeWhile { it.key in shownBefore }.size
                    state.copy(
                        // The live reply took in the turn's saved steps, which this page brings again.
                        messages = (historyToMessages(rows, rowsSessionId, startedAt).withRisks(flagged) + state.messages.drop(stored))
                            .withSavedStepsFolded(),
                        olderMessages = full,
                    )
                }
                true
            }
        } finally {
            _state.update { it.copy(loadingOlder = false) }
        }
    }

    /**
     * Shows the loaded [rows] in place of the stored messages, keeping what they can't hold yet. [savedCopyAt]: they're
     * the copy saved on this device then, not the gateway's. [relaid]: the same rows again, now that the turn's start is known.
     */
    private fun showRows(flagged: Map<String, ToolRisk>, older: Boolean, savedCopyAt: Long? = null, relaid: Boolean = false) = _state.update { state ->
        // Read again on each try of the update, so the one that lands says what the rows were laid out with.
        val startedAt = turnStartedAt.also { rowsLaidOutFor = it }
        // Keep a reply that is streaming right now; the stored rows don't have it yet. Nor do they
        // have a correction mid-turn, or the part of the reply shown before it.
        val corrected = state.messages.indexOfFirst { it.key == state.correctedReplyKey }
        val live = if (corrected >= 0) {
            state.messages.take(corrected).filter { it.isLocalOnly } + state.messages.drop(corrected)
        } else {
            state.messages.filter { it.isLocalOnly }
        }
        // The running turn's saved steps apart from the reply before them, to fold into its live reply below.
        val stored = historyToMessages(rows, rowsSessionId, startedAt).withRisks(flagged)
        // A prompt sent without a reply that the transcript now has arrived after all.
        val storedPrompts = stored.mapNotNull { (it as? ChatMessage.User)?.text }
        // Only the gateway's transcript says a prompt arrived; the saved copy is from before it.
        val unsettled = if (savedCopyAt != null) live else {
            live.filterNot { message ->
                if (message !is ChatMessage.User || message.check == null) return@filterNot false
                val prompt = unsettled[message.key]
                // A reaction's follow-up can repeat an earlier one word for word: it arrived once the transcript
                // holds one more copy than when it went.
                if (prompt?.keepRejected == true) storedPrompts.count { it.contains(message.text) } > prompt.copiesBefore
                else storedPrompts.any { it.contains(message.text) }
            }
        }
        // Steps of the running turn are saved as it goes, and the live copy repeats them: show them once.
        val messages = (stored + unsettled).withSavedStepsFolded()
        state.copy(
            messages = messages,
            historyLoaded = true,
            // Laid out again, the rows read no better than before: a failed read still shows as one.
            historyError = if (relaid) state.historyError else null,
            savedCopyAt = savedCopyAt,
            olderMessages = older,
        )
    }

    private suspend fun runCatchingAttach(
        client: JsonRpcClient,
        reconnected: Boolean = false,
        reuse: Boolean = false,
        afterGap: Boolean = false,
    ): Boolean = try {
        val runtimeId = attach(client, reconnected, reuse, afterGap)
        scope.launch { refreshSubagents(client, runtimeId) }
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: StaleResume) {
        false
    } catch (e: Exception) {
        _state.update {
            if (connectedClient() !== client || it.attachment !is Attachment.Attaching) it else it.copy(
                attachment = Attachment.Failed(e.message ?: "Couldn't open the session."),
                // The gateway itself turned the chat down (gone with its deleted bot, say): no turn of it can be
                // followed, so none shows as running, nor keeps "Working…" in the shade. A lost link isn't that.
                running = it.running && e !is RpcException,
            )
        }
        false
    }

    private suspend fun ensureAttached(client: JsonRpcClient): String {
        // The cached id belongs to the socket it was attached on; a new socket re-attaches
        // (under the attach mutex, so a send racing a reconnect waits for the re-attach)
        // instead of firing the stale id where the gateway has no subscription for it.
        liveRuntimeId(client)?.let { return it }
        // Attached before, on another socket or before a failed attach on this one, events may have been missed since.
        val stored = rowExists
        return try {
            if (stored) attach(client, reconnected = attachedClient != null, reuse = true) else create(client)
        } catch (e: CancellationException) {
            throw e
        } catch (e: StaleResume) {
            throw e
        } catch (e: Exception) {
            // Not left "Attaching": a stored chat shows the failure with its retry; a new one is made again on the next send.
            _state.update {
                if (connectedClient() !== client || it.attachment !is Attachment.Attaching) it
                else it.copy(attachment = if (stored) Attachment.Failed(e.message ?: "Couldn't open the session.") else Attachment.Detached)
            }
            throw e
        }
    }

    /** The runtime id, when it was attached on [client]; one from an earlier socket is null, since it's no good on this one. */
    private fun liveRuntimeId(client: JsonRpcClient): String? = _state.value.runtimeSessionId?.takeIf { attachedClient === client }

    /**
     * `session.resume` the stored id; the reply's `session_id` is the runtime id events carry. After a drop
     * ([reconnected]) the same runtime picks up from the last event applied (see [catchUp]); a new one, or one
     * with nothing to pick up from, reloads the transcript instead. With [reuse], a runtime already attached on
     * [client] by the time the lock is free (a send and the reconnect racing) is kept rather than resumed again.
     * [afterGap] takes up a subscription the session authority ended: nothing to pick up from, and nothing to do
     * when a reconnect attached the chat on a new socket first.
     */
    private suspend fun attach(
        client: JsonRpcClient,
        reconnected: Boolean = false,
        reuse: Boolean = false,
        afterGap: Boolean = false,
    ): String = attachMutex.withLock {
        if (connectedClient() !== client || (afterGap && attachedClient !== client)) throw StaleResume()
        if (reuse) liveRuntimeId(client)?.let { return@withLock it }
        try {
            resume(client, reconnected, afterGap)
        } finally {
            if (reconnected && !afterGap) withContext(NonCancellable) {
                streamMutex.withLock { pendingResume = null }
            }
        }
    }

    private suspend fun resume(client: JsonRpcClient, reconnected: Boolean, afterGap: Boolean): String {
        val stored = _state.value.storedSessionId ?: error("No stored session to resume")
        _state.update { it.copy(attachment = Attachment.Attaching) }
        val result = client.request(
            "session.resume",
            buildJsonObject {
                put("session_id", stored)
                profile?.let { put("profile", it) }
                put("source", CLIENT_SOURCE)
                put("cols", TERMINAL_COLUMNS)
                put("omit_messages", true)
            },
        ) as? JsonObject ?: error("Empty session.resume reply")
        val runtimeId = result.string("session_id") ?: error("session.resume returned no session_id")
        executionGeneration = result.long("execution_generation")
        revision = result.long("revision")
        val running = result.boolean("running") == true
        // A turn already running when we attach is someone else's; reconcile with the stored rows when it ends.
        if (running && ownTurnsPending == 0) foreignTurn = true
        val inflight = result["inflight"] as? JsonObject
        turnStartedAt = result.double("turn_started_at").takeIf { running }
        // Questions asked while no socket of ours was attached; the gateway keeps them open for us.
        val open = InputRequest.parseResume(result)
        val streamed = inflight.string("assistant").orEmpty()
        val epoch = replayEpoch(client, result)
        val (resumable, waiting) = streamMutex.withLock {
            if (connectedClient() !== client) throw StaleResume()
            if (!afterGap && (reconnected || attachedClient !== client)) resetReplayWork()
            if (reconnected && !afterGap) pendingResume = PendingResume(client, runtimeId)
            inflightText = streamed
            // Seqs count per runtime session and per epoch, so only the same of both can be picked up.
            val resumable = !afterGap && reconnected && lastSeq != null && runtimeId == seqRuntime && epoch != null && epoch == seqEpoch
            if (!resumable) {
                lastSeq = startSeq(client, result, epoch)
                // The turn may have changed while away: the gateway's copy names its reply again below.
                turnReplyKey = null
            }
            seqRuntime = runtimeId
            seqEpoch = epoch
            // The resume may name another runtime (the session's newest after a compression): its frames are held.
            if (afterGap && gapHeld != null) gapRuntime = runtimeId
            // Held from before the state names the runtime, so no live event slips in ahead of the missed ones.
            val waiting = if (resumable && held == null) mutableListOf<GatewayEvent>().also { held = it } else null
            resumable to waiting
        }
        if (reconnected && (!resumable || (!_state.value.historyRead && !historyMutex.isLocked))) loadHistory()
        if (connectedClient() !== client) throw StaleResume()
        var replyKey: String? = null
        // Named with the id it goes with, so no reader ever pairs this socket with the old id or the old socket with this one.
        attachedClient = client
        _state.update { state ->
            state.copy(
                attachment = Attachment.Attached(runtimeId),
                running = running,
                // Picking up replays the turn itself; otherwise the reply shows as far as the gateway has it,
                // or, when the turn ended while away, as the transcript just read has it.
                messages = when {
                    resumable -> state.messages
                    running -> state.withInflight(streamed).messages
                        .also { replyKey = it.inflightReplyKey(streamed) }
                        .withSavedStepsFolded()
                    reconnected -> state.withoutStaleReply().messages
                    else -> state.messages
                },
                // Rebuilt from the gateway's copy, the reply's current response begins somewhere unknown.
                responseStart = state.responseStart.takeIf { resumable || (!running && !reconnected) },
                inputRequests = open.plusNew(claimUnclaimed(runtimeId)),
                keySeq = state.keySeq + 1,
            ).withInfo(result["info"] as? JsonObject).withTodos(TodoList.parse(result["todo_state"] as? JsonObject))
                // Opened without one (a branch on the session authority, whose `session.mutate` names no title).
                .let { if (it.title.isNullOrBlank()) it.copy(title = (result["info"] as? JsonObject).string("title")?.takeIf(String::isNotBlank)) else it }
                // Nothing runs, so the agent is built; its clear may have gone while this phone was away.
                .let { if (running || resumable) it else it.withoutAgentNotices() }
                // Not picked up, so what the turn did since the compaction is lost: its "complete" line is out of date.
                .let { if (resumable) it else it.withoutCompactionDone() }
        }
        // Named by the try of the update that landed, not by one that lost to another change.
        if (running && !resumable) turnReplyKey = replyKey
        // Read before the turn's start was known, the rows merged its saved steps into the reply before them when its
        // prompt doesn't show: lay them out again, now apart, so they fold into the live reply.
        if (running && !resumable && turnStartedAt != null && _state.value.historyLoaded) {
            historyMutex.withLock {
                if (rows.isNotEmpty() && rowsLaidOutFor != turnStartedAt) {
                    showRows(risks?.all().orEmpty(), _state.value.olderMessages, _state.value.savedCopyAt, relaid = true)
                }
            }
        }
        val replayGap = streamMutex.withLock {
            if (connectedClient() !== client) throw StaleResume()
            val pending = pendingResume ?: return@withLock false
            pendingResume = null
            if (client.sessionAuthority && pending.events.any { it.type == REPLAY_GAP }) true else {
                pending.events.forEach(::deliver)
                false
            }
        }
        if (replayGap) resumeAfterGap(client, runtimeId)
        // session.resume doesn't carry the goal/loop/heartbeat, nor the reasoning effort before the agent is built;
        // read them once, events take it from there.
        // After the catch-up: a missed update it replays is older than the read, so it mustn't land after it.
        val readReasoning = (result["info"] as? JsonObject)?.containsKey("reasoning_effort") != true
        scope.launch {
            if (waiting != null && !replayGap) catchUp(client, runtimeId, waiting)
            refreshControl()
            if (readReasoning) refreshReasoning()
        }
        return runtimeId
    }

    /** [withSavedStepsInLiveReply] for the running turn. */
    private fun List<ChatMessage>.withSavedStepsFolded(): List<ChatMessage> =
        withSavedStepsInLiveReply(turnStartedAt, inflightText, turnReplyKey)

    /**
     * The `replay_epoch` the seqs of a session opened on [client] count in: the session's own from the session
     * authority's `session.resume` / `session.create` [result], else the gateway run's from `gateway.ready`, when
     * [client] is the connected one.
     */
    private fun replayEpoch(client: JsonRpcClient, result: JsonObject): String? =
        if (client.sessionAuthority) {
            result.string("replay_epoch")
        } else {
            (connection.state.value as? ConnectionState.Connected)?.takeIf { it.client === client }?.ready.string("replay_epoch")
        }

    /**
     * Where a freshly opened session's stream starts: the session authority's `last_sequence` from [result], so a
     * gap in the first events is filled too. Null elsewhere: the first event applied sets it.
     */
    private fun startSeq(client: JsonRpcClient, result: JsonObject, epoch: String?): Long? =
        result.long("last_sequence").takeIf { client.sessionAuthority && epoch != null }

    /**
     * `session.create` for a brand-new chat; its stored row appears with the first prompt, or at once when it starts
     * with an instruction (a seeded chat's row is stored on create), so it's resumed from then on, not made again.
     */
    private suspend fun create(client: JsonRpcClient): String = attachMutex.withLock {
        if (connectedClient() !== client) throw StaleResume()
        // Two sends racing on a new chat: the one that waited takes the session the first made.
        liveRuntimeId(client)?.let { return@withLock it }
        _state.update { it.copy(attachment = Attachment.Attaching) }
        val picks = _state.value
        val instruction = startInstruction()
        val result = client.request(
            "session.create",
            buildJsonObject {
                profile?.let { put("profile", it) }
                put("source", CLIENT_SOURCE)
                put("cols", TERMINAL_COLUMNS)
                cwd?.let {
                    put("cwd", it)
                    put("cwd_explicit", true)
                }
                // Picked before the first send; without them the profile defaults apply.
                if (picks.model != null && picks.provider != null) {
                    put("model", picks.model)
                    put("provider", picks.provider)
                }
                picks.reasoningEffort?.let { put("reasoning_effort", it) }
                picks.fast?.let { put("fast", it) }
                putStartInstruction(instruction)
            },
        ) as? JsonObject ?: error("Empty session.create reply")
        val runtimeId = result.string("session_id") ?: error("session.create returned no session_id")
        executionGeneration = result.long("execution_generation")
        revision = result.long("revision")
        // The session authority stores every new chat's row on create, so a send after a lost link resumes it.
        if (client.sessionAuthority || !instruction.isNullOrBlank()) rowExists = true
        streamMutex.withLock {
            resetReplayWork()
            seqEpoch = replayEpoch(client, result)
            lastSeq = startSeq(client, result, seqEpoch)
            seqRuntime = runtimeId
        }
        // The gateway takes a folder that's gone without an error and runs the chat in its own folder instead.
        val ranIn = (result["info"] as? JsonObject).string("cwd")
        val movedFrom = cwd?.takeIf { ranIn != null && ranIn.trimEnd('/', '\\') != it.trimEnd('/', '\\') }
        attachedClient = client
        _state.update {
            it.copy(
                attachment = Attachment.Attached(runtimeId),
                storedSessionId = result.string("stored_session_id") ?: it.storedSessionId,
                historyLoaded = true,
                inputRequests = it.inputRequests.plusNew(claimUnclaimed(runtimeId)),
                error = if (movedFrom != null) "The project's folder $movedFrom wasn't found, so this chat runs in $ranIn." else it.error,
            ).withInfo(result["info"] as? JsonObject)
        }
        // A level picked before the first send went with the create; otherwise the profile's applies, so read it.
        if (picks.reasoningEffort == null && (result["info"] as? JsonObject)?.containsKey("reasoning_effort") != true) {
            scope.launch { refreshReasoning() }
        }
        runtimeId
    }

    private fun connectedClient(): JsonRpcClient? = (connection.state.value as? ConnectionState.Connected)?.client

    private companion object {
        /** The session authority's notice that it ended this socket's subscription to a session. */
        const val REPLAY_GAP = "session.replay_gap"

        /** A message key made from a stored row (`historyToMessages`): `row-<id>`, or `row-<id>-<n>` for one of its events. */
        val ROW_KEY = Regex("row-(\\d+)(?:-\\d+)?")

        /**
         * The surface the agent is told it's on (agent/prompt_builder.py PLATFORM_HINTS). "desktop", like
         * Hermes Desktop and the dashboard chat: Markdown renders and files come back as `MEDIA:` paths,
         * where the default "tui" tells the agent there is no way to show a picture.
         */
        const val CLIENT_SOURCE = "desktop"

        /** Width the agent formats terminal-ish output for; a phone is narrow. */
        const val TERMINAL_COLUMNS = 80

        /** `prompt.submit` statuses that start (or queue) a turn of their own. */
        val TURN_STARTING_STATUSES = setOf("streaming", "queued")

        /** `prompt.submit` statuses for text folded into the running turn (busy mode interrupt or steer). */
        val CORRECTION_STATUSES = setOf("redirected", "steered")

        /** `session.steer`'s status for a steer the running turn took in. */
        const val STEER_ACCEPTED = "queued"

        /** `session.steer`'s status for a steer the agent didn't take, or (on the session authority) may have. */
        const val STEER_REJECTED = "rejected"

        /**
         * Session-authority refusals of a steer that came before any agent saw it (session_busy_controls.py
         * `correct`, Desktop's `UNDELIVERED_CORRECTION_REASONS`): safe to send as the next prompt.
         */
        val UNDELIVERED_STEER_REFUSALS = setOf("stale_generation", "execution_not_ready", "unsupported_control")

        const val LOST_WHILE_SENDING = "Lost the connection while sending. Check that it isn't running before you send it again."

        const val STEER_MAYBE_DELIVERED =
            "Hermes may have read your steer, so it wasn't sent again. Check the reply before you send it again."

        /** `session.steer`'s error for an agent without `steer()` (tui_gateway/methods_session_interrupt.py). */
        const val STEER_UNSUPPORTED = 4010

        /** How long Stop & send waits for the stopped turn's `message.complete` before queueing behind it. */
        const val STOP_SETTLE_TIMEOUT_MS = 10_000L

        const val MAX_UNCLAIMED = 8

        /** Bounds [loadAllHistory]: 50 pages of the dashboard's most is 25,000 rows. */
        const val MAX_HISTORY_PAGES = 50

        /** Uploads of several MB over a phone link take a while. */
        const val UPLOAD_TIMEOUT_MS = 120_000L

        /** The gateway's "busy": a turn is running, so nothing was cut. */
        const val BUSY = 4009

        const val BUSY_MESSAGE = "Wait for the reply to finish, then try again."

        /**
         * A rewind the gateway can't place: the row isn't in what the live agent holds (4018: compressed
         * away, cut elsewhere), the prompt count drifted (4030), or the target was malformed (4004).
         */
        val STALE_REWIND_CODES = setOf(4018, 4030, 4004)

        const val CHANGED_ELSEWHERE = "The chat changed somewhere else meanwhile. It's up to date now; try again."

        const val NOT_CONNECTED = "Not connected to the gateway."

        /**
         * A sub-goal Hermes v0.22.1's `/subgoal` would take for its own `remove` or `clear`
         * (gateway/slash_commands_goals.py), so it isn't sent as that command.
         */
        const val SUBGOAL_READS_AS_VERB =
            "This gateway would read a sub-goal starting with \"remove\" or \"clear\" as a command. " +
                "Reword it, or update Hermes."

        /** A sub-goal with no text, which `/subgoal` would answer with the goal's status instead of adding it. */
        const val SUBGOAL_TEXT_MISSING = "Write the sub-goal first."

        /** Looks at the transcript for a prompt sent without a reply: about 7 s in all. */
        const val DELIVERY_LOOKS = 4
        const val DELIVERY_LOOK_INTERVAL_MS = 2_500L

        val ANSI_ESCAPE = Regex("""\u001B\[[0-9;?]*[ -/]*[@-~]""")

        /** JSON-RPC "method not found": a call this gateway, or its mode (legacy or session authority), doesn't serve. */
        const val METHOD_NOT_FOUND = -32601

        /** `session.mutate` refusals for a stale `expected_revision` or `expected_generation`; they carry the current ones. */
        val FENCE_REFUSALS = setOf("revision_conflict", "stale_generation")

        const val BRANCH_ROW_UNKNOWN = "Couldn't find this message in the saved chat. Open the chat again, then branch from it."

        const val CHANGED_WHILE_BRANCHING = "The chat changed on the gateway while branching. Try again."

        const val CHANGED_WHILE_SWITCHING = "The chat changed on the gateway while switching the model. Try again."

        const val SWITCH_WHEN_DONE = "The model can't change while a reply is running. Switch it when this one is done."

        const val CHANGED_WHILE_RENAMING = "The chat changed on the gateway while renaming. Try again."

        const val CHANGED_WHILE_COMPRESSING = "The chat changed on the gateway while compressing. Try again."

        /** JSON-RPC "internal error". */
        const val INTERNAL_ERROR = -32603

        const val RENAME_FAILED = "The gateway couldn't rename the chat. Another chat may have this title already."

        /** A REST read of a session the gateway no longer has. */
        const val HTTP_NOT_FOUND = 404

        /** How long a handoff may wait for the messaging service to claim it (Desktop's minute). */
        const val HANDOFF_WAIT_MS = 60_000L

        /** `/reasoning`'s scope words (Desktop's reasoning-slash.ts). */
        val GLOBAL_FLAGS = setOf("--global", "-g", "global")
        val SESSION_FLAGS = setOf("--session", "-s", "session")

        /** The slash worker can be slow to start (it loads the agent and its MCP servers). */
        const val COMMAND_TIMEOUT_MS = 60_000L

        /** Compressing calls the model to summarize; a long chat takes a while. */
        const val COMPRESS_TIMEOUT_MS = 180_000L

        /** `command.dispatch`'s "not a quick/plugin/bundle/skill command" (tui_gateway/methods_tools.py). */
        val NOT_DISPATCHABLE = Regex("""not a quick/plugin/bundle/skill command""", RegexOption.IGNORE_CASE)

        /** How often a running `/bg` card looks for its answer in the task's stored session, and for how long (an hour). */
        const val BACKGROUND_POLL_MS = 15_000L
        const val BACKGROUND_POLLS = 240

        const val BACKGROUND_NO_ANSWER = "No answer yet. When it's done, it's in its own chat in the list."
    }
}

/** A `/bg` card, as opposed to a `/btw` one. */
private val ChatMessage.Command.isBackground: Boolean get() = command == "/bg" || command.startsWith("/bg ")

/**
 * A side-task card brought back into its reopened chat, keyed by its task so it can't clash with this session's
 * keys. A `/btw` answer that came while another chat was open is lost; only a `/bg` one can be read back.
 */
private fun reopenedSideTask(card: ChatMessage.Command): ChatMessage.Command = card.copy(
    key = "task-${card.taskId}",
    output = if (card.running && !card.isBackground) "Its answer may have come while another chat was open." else card.output,
    running = card.running && card.isBackground,
)

/** How [ChatSession.stopAndSubmit] went: the send's [outcome], and the queued prompts the stop dropped, to give back. */
data class StopAndSend(val outcome: SendOutcome, val dropped: List<String> = emptyList())

/** A prompt that came from a GPT-Live voice call; [context] is the recent spoken exchange, newest last. */
data class VoiceLiveTurn(val context: String)

/** How [ChatSession.setModel] went. */
sealed interface ModelSwitch {
    data object Done : ModelSwitch

    /** The gateway wants an explicit yes (an expensive model); call again with `confirm = true`. */
    data class NeedsConfirmation(val message: String) : ModelSwitch

    /** Rolled back; the reason is in [ChatState.error]. */
    data object Failed : ModelSwitch
}

private fun List<InputRequest>.plusNew(more: List<InputRequest>): List<InputRequest> =
    this + more.filter { new -> none { it.id == new.id } }

/**
 * The running turn's reply as the gateway has it so far ([streamed], `inflight.assistant`): it opens the reply when
 * none is, and replaces one that missed part of it. A reply that doesn't lead into it (written since) stays.
 */
internal fun ChatState.withInflight(streamed: String): ChatState {
    val open = messages.indexOfLast { it is ChatMessage.Assistant && it.streaming }
    // What came while away may hold tools, so where the current response begins isn't known any more.
    if (open < 0) {
        return copy(
            messages = messages + ChatMessage.Assistant(key = "live-$keySeq", text = streamed, streaming = true),
            keySeq = keySeq + 1,
            responseStart = null,
        )
    }
    val reply = messages[open] as ChatMessage.Assistant
    if (streamed.length <= reply.text.length || !streamed.startsWith(reply.text)) return this
    return copy(messages = messages.toMutableList().apply { set(open, reply.copy(text = streamed)) }, responseStart = null)
}

/**
 * The running turn's steps already saved, folded into its live reply. The gateway saves each step (text and tool
 * calls) as the turn goes, while the live copy (`inflight.assistant`, then the stream) holds the whole reply so far:
 * a chat opened mid-turn has both, the saved part right before the live one. Like Desktop, they are one reply only
 * when all of it holds: the saved one was written after the turn began ([turnStartedAt], the gateway's clock; without
 * it nothing folds), the live one is this turn's (it agrees with the gateway's copy, [inflightText]), and the live text
 * carries on from the saved text. Then the live one stays (deltas keep landing on it), with the saved tool calls.
 * [turnReplyKey] is the reply the gateway's copy named when it was read ([inflightReplyKey]): it stays this turn's once
 * text streams on past that copy (onto a reply that had none), and each rebuild brings the saved steps again.
 */
internal fun List<ChatMessage>.withSavedStepsInLiveReply(
    turnStartedAt: Double?,
    inflightText: String?,
    turnReplyKey: String? = null,
): List<ChatMessage> {
    if (turnStartedAt == null || inflightText == null) return this
    val liveAt = indexOfLast { it is ChatMessage.Assistant && it.streaming }
    if (liveAt < 1) return this
    val live = this[liveAt] as ChatMessage.Assistant
    // A rebuild puts this device's own lines (notices, commands) after the stored rows; they don't part the two.
    val savedAt = (liveAt - 1 downTo 0).firstOrNull { !this[it].isLocalLine } ?: return this
    val saved = this[savedAt] as? ChatMessage.Assistant ?: return this
    if (saved.streaming || saved.error != null || saved.warning != null) return this
    if (saved.timestamp == null || saved.timestamp < turnStartedAt) return this
    // A reply left streaming from an earlier turn isn't this one's, whatever was saved before it.
    if (live.key != turnReplyKey && live.key != inflightReplyKey(inflightText)) return this
    // Steps are saved apart and joined with blank lines; the stream runs them together. Spacing aside, the same.
    if (!live.text.startsWithIgnoringSpaces(saved.text)) return this
    val commentary = (saved.commentary + live.commentary).distinctCommentary()
    val shown = if (commentary.isEmpty()) "" else live.text.commentaryParagraphs()
    val prose by lazy { COMMENTARY_NON_PROSE.replace(shown, " ") }
    val folded = live.copy(
        commentary = commentary.filterNot {
            val paragraph = it.commentaryParagraphs()
            paragraph in shown || prose.containsJoinedCommentary(paragraph.removeSurrounding("\n\n"))
        },
        // Folded before, the live reply leads with the saved reasoning and has streamed on from there.
        reasoning = if (live.reasoning.startsWithIgnoringSpaces(saved.reasoning)) live.reasoning
            else listOf(saved.reasoning, live.reasoning).filter { it.isNotEmpty() }.joinToString("\n\n"),
        // A call still running in the live copy (folded before) has its events since; once the saved one has its
        // result, that is newer (an end missed while the link was down).
        // A copy folded from rows read before the turn's start was known didn't know a call without a result still ran.
        tools = saved.tools.map { tool ->
            val shown = live.tools.firstOrNull { it.id == tool.id }
            when {
                shown == null -> tool
                // The saved call has its result row (empty ones included): its end, missed while the link was down.
                shown.running && !tool.running -> tool
                // An end the live copy got itself carries its time or summary; one folded from older rows has neither.
                tool.running && !shown.running && shown.output == null && shown.durationSeconds == null && shown.summary == null -> tool
                else -> shown
            }
        } +
            live.tools.filter { tool -> saved.tools.none { it.id == tool.id } },
        repliedTo = live.repliedTo ?: saved.repliedTo,
    )
    return toMutableList().apply {
        set(liveAt, folded)
        removeAt(savedAt)
    }
}

/** Keep paragraph boundaries when comparing a saved update to text already shown by the stream. */
private fun String.commentaryParagraphs(): String =
    replace("\r\n", "\n").split("\n\n").joinToString("\n\n", prefix = "\n\n", postfix = "\n\n") { it.trim() }

// Relaxed sentence matching is for prose. A URL or code span can contain the same punctuation.
private val COMMENTARY_NON_PROSE =
    Regex("""```[\s\S]*?(?:```|$)|`+[^`\n]*`+|[a-z][a-z0-9+.-]*://\S+""", RegexOption.IGNORE_CASE)

/** Match a whole update when adjacent streamed sentences lost the separator between them. */
private fun String.containsJoinedCommentary(update: String): Boolean {
    if (update.isEmpty()) return false
    var start = indexOf(update)
    while (start >= 0) {
        val end = start + update.length
        val startsAtBoundary = startsWith("\n\n", start - 2) ||
            (start > 0 && this[start - 1] in ".!?" && startsJoinedSentence(start)) ||
            (startsSpacedSentence(start) && followsSentenceEnd(start))
        val endsAtBoundary = startsWith("\n\n", end) ||
            (update.last() in ".!?" && (startsJoinedSentence(end) || startsSentenceAfterSpaces(end)))
        if (startsAtBoundary && endsAtBoundary) return true
        start = indexOf(update, start + 1)
    }
    return false
}

/** Whether a sentence ends just before [at], with spaces or one line break between ("Looking. Checking."). */
private fun String.followsSentenceEnd(at: Int): Boolean {
    var i = at - 1
    var breaks = 0
    while (i >= 0 && this[i].isWhitespace() && (this[i] != '\n' || ++breaks == 1)) i--
    return i in 0..<at - 1 && this[i] in ".!?"
}

/** Whether a sentence starts after [at] past spaces or one line break ("Checking. Done."). */
private fun String.startsSentenceAfterSpaces(at: Int): Boolean {
    var i = at
    var breaks = 0
    while (i < length && this[i].isWhitespace() && (this[i] != '\n' || ++breaks == 1)) i++
    return i > at && startsSpacedSentence(i)
}

// Past a space a dotted suffix can't be meant, so an acronym ("CI is green.") or a number starts a sentence too.
private fun String.startsSpacedSentence(at: Int): Boolean = getOrNull(at)?.let { it.isUpperCase() || it.isDigit() } == true

// Leave uppercase suffixes such as a dotted ".COM" alone.
private fun String.startsJoinedSentence(at: Int): Boolean =
    getOrNull(at)?.isUpperCase() == true && getOrNull(at + 1)?.isUpperCase() != true

/**
 * The streaming reply that is the running turn's: it carries on from the gateway's copy ([inflightText]), and has
 * nothing yet while that copy is still empty. Null when the reply open isn't (an earlier turn's, left streaming).
 */
internal fun List<ChatMessage>.inflightReplyKey(inflightText: String): String? {
    val live = lastOrNull { it is ChatMessage.Assistant && it.streaming } ?: return null
    val liveText = (live as ChatMessage.Assistant).text
    return live.key.takeIf { liveText.startsWithIgnoringSpaces(inflightText) && (inflightText.isNotBlank() || liveText.isBlank()) }
}

/** A line only this device shows (a notice, a command) and no reply or prompt: it sits after the stored rows. */
private val ChatMessage.isLocalLine: Boolean
    get() = isLocalOnly && this !is ChatMessage.Assistant && this !is ChatMessage.User

/** Whether this starts with [prefix], whitespace aside in both. Walks the two in place: replies run long. */
private fun String.startsWithIgnoringSpaces(prefix: String): Boolean {
    var i = 0
    var j = 0
    while (true) {
        while (j < prefix.length && prefix[j].isWhitespace()) j++
        if (j == prefix.length) return true
        while (i < length && this[i].isWhitespace()) i++
        if (i == length || this[i] != prefix[j]) return false
        i++
        j++
    }
}

/**
 * A stopped turn's `message.complete` whose reply is stored but not named, so the transcript is read for its row.
 * The receipt names the reply's row only for a turn that completed (v0.23.4 agent/persisted_turn_receipt.py), but a
 * stopped one is stored too. Only when the receipt lists a stored row besides the prompt's: without one, nothing says
 * the reply was stored (a save that failed, a stop before any text), and a read could drop the live copy. Row 0 names
 * nothing, as the reducer reads it.
 */
internal fun stoppedUnnamed(payload: JsonObject?): Boolean {
    val receipt = payload?.get("persisted_turn") as? JsonObject ?: return false
    val prompts = setOfNotNull(receipt.long("user_row_id")) +
        (receipt["user_row_ids"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.longOrNull }
    val stored = (receipt["row_ids"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.longOrNull }
    return payload.string("status") == "interrupted" &&
        stored.any { it !in prompts } &&
        receipt.long("final_assistant_row_id")?.takeIf { it > 0 } == null
}

/**
 * The reply still marked streaming from a turn that ended while the link was down and couldn't be replayed. The
 * transcript just read holds the turn, so the live copy goes; when it couldn't be read, the copy stays, closed.
 */
internal fun ChatState.withoutStaleReply(): ChatState {
    if (messages.none { it is ChatMessage.Assistant && it.streaming }) return this
    return copy(
        messages = if (historyRead) {
            messages.filterNot { it is ChatMessage.Assistant && it.streaming }
        } else {
            messages.map { if (it is ChatMessage.Assistant && it.streaming) it.copy(streaming = false) else it }
        },
        correctedReplyKey = null,
        responseStart = null,
    )
}

private fun List<ChatMessage>.updateUser(key: String, change: (ChatMessage.User) -> ChatMessage.User): List<ChatMessage> =
    map { if (it is ChatMessage.User && it.key == key) change(it) else it }

/**
 * Closes the reply streaming above the prompt [key] so the turn's later output opens a new one below
 * it; a reply that had shown nothing yet goes away. [sameResponse]: the model goes on with the response it was writing
 * (a steer), whose start stays with the reply above; a redirect has it answer again, from scratch.
 */
internal fun ChatState.sealReplyBefore(key: String, sameResponse: Boolean = true): ChatState {
    val prompt = messages.indexOfFirst { it.key == key }
    val open = messages.indexOfLast { it is ChatMessage.Assistant && it.streaming }
    if (prompt < 0 || open !in 0 until prompt) return this
    val reply = messages[open] as ChatMessage.Assistant
    if (reply.text.isBlank() && reply.reasoning.isBlank() && reply.commentary.isEmpty() && reply.tools.isEmpty()) {
        return copy(messages = messages.toMutableList().apply { removeAt(open) })
    }
    return copy(
        messages = messages.toMutableList().apply { set(open, reply.copy(streaming = false)) },
        correctedReplyKey = reply.key,
        responseStart = responseStart.takeIf { sameResponse },
    )
}

/**
 * Messages the stored transcript can't contain yet: the streaming reply and prompts still being sent.
 * A reply that ended with a warning stays too: the warning is usually that it wasn't saved.
 */
private val ChatMessage.isLocalOnly: Boolean
    get() = when (this) {
        is ChatMessage.Assistant -> streaming || warning != null
        // A queued prompt isn't in the transcript until its turn starts.
        is ChatMessage.User -> pending || queued || check != null
        is ChatMessage.Notice -> !stored
        is ChatMessage.Event -> false
        is ChatMessage.Command -> true
    }
