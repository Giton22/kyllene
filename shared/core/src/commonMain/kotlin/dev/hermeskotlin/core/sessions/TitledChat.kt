package dev.hermeskotlin.core.sessions

import dev.hermeskotlin.core.rpc.JsonRpcClient
import dev.hermeskotlin.core.rpc.RpcException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * A new chat titled [title] in [profile] (null: the gateway's launch profile), made the way Desktop makes one and
 * titled at once so its row exists before the first prompt; [options] adds `session.create` fields. Answers its
 * runtime id (which a chat opened on this socket resumes into) and stored id. A title another chat has is
 * [taken], with nothing left behind.
 *
 * On the session authority a titled `session.create` opens the chat that already has the title rather than failing
 * (gateway/session_controls.py `create`, `--create-if-missing`). With [reuse] that chat is the answer: the one chat a
 * title stands for, such as a profile's Bot Chat. Without it the chat is made untitled and then renamed, so a taken
 * title is still [taken].
 */
internal suspend fun JsonRpcClient.createTitledChat(
    profile: String?,
    title: String,
    taken: () -> Exception,
    reuse: Boolean = false,
    options: JsonObjectBuilder.() -> Unit = {},
): Pair<String, String> {
    val authority = sessionAuthority
    val created = request(
        "session.create",
        buildJsonObject {
            profile?.let { put("profile", it) }
            if (!authority || reuse) put("title", title)
            options()
            put("source", CLIENT_SOURCE)
            put("cols", TERMINAL_COLUMNS)
        },
    ) as? JsonObject ?: throw RpcException(0, "Empty session.create reply")
    val runtime = created.text("session_id")
    val stored = created.text("stored_session_id")
    if (runtime == null || stored == null) throw RpcException(0, "session.create returned no session")
    if (authority && reuse) return runtime to stored
    try {
        if (authority) {
            mutate(runtime, created, "rename") { put("title", title) }
        } else {
            request(
                "session.title",
                buildJsonObject {
                    put("session_id", runtime)
                    put("title", title)
                },
            )
        }
    } catch (e: RpcException) {
        // Our runtime is of no use now. The gateway doesn't prune the empty row it made for a
        // non-"tui" source, so let go of the runtime and remove the row rather than leave a stray.
        runCatching { closeChat(runtime) }
        if (authority) {
            runCatching { mutate(runtime, created, "delete") {} }
        } else {
            runCatching {
                request(
                    "session.delete",
                    buildJsonObject {
                        put("session_id", stored)
                        profile?.let { put("profile", it) }
                    },
                )
            }
        }
        // The authority answers a rename to a taken title with a bare internal error: the store's "already in use"
        // isn't mapped to a refusal (persistence/sqlite/conversation/_store/titles.py, as of hermes-agent v0.23.4).
        // A rename of a chat just made, with nothing in it, has no other way to fail like that.
        if (TITLE_TAKEN.containsMatchIn(e.message) || (authority && e.code == INTERNAL_ERROR)) throw taken()
        throw e
    }
    return runtime to stored
}

/**
 * Lets go of this socket's hold on the chat [runtime]: `session.close`, or on the session authority, which has
 * none, `session.detach` with the subscription it was opened with (when this socket opened it).
 */
internal suspend fun JsonRpcClient.closeChat(runtime: String) {
    if (!sessionAuthority) {
        request("session.close", buildJsonObject { put("session_id", runtime) })
        return
    }
    val subscription = subscriptionOf(runtime) ?: return
    request(
        "session.detach",
        buildJsonObject {
            put("session_id", runtime)
            put("subscription_id", subscription)
        },
    )
}

/** A `session.mutate` [operation] of the chat [runtime] just [created], fenced by the revision and generation it has. */
@OptIn(ExperimentalUuidApi::class)
private suspend fun JsonRpcClient.mutate(
    runtime: String,
    created: JsonObject,
    operation: String,
    payload: JsonObjectBuilder.() -> Unit,
) {
    request(
        "session.mutate",
        buildJsonObject {
            put("session_id", runtime)
            put("request_id", Uuid.random().toString())
            put("expected_revision", created.number("revision") ?: 0)
            put("expected_generation", created.number("execution_generation") ?: 0)
            put("operation", operation)
            putJsonObject("payload", payload)
        },
    )
}

private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.number(key: String): Long? = (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull

/** Same surface hint as [dev.hermeskotlin.core.chat.ChatSession]: Markdown and pictures show here. */
private const val CLIENT_SOURCE = "desktop"
private const val TERMINAL_COLUMNS = 80
private val TITLE_TAKEN = Regex("already in use", RegexOption.IGNORE_CASE)

/** JSON-RPC's "internal error". */
private const val INTERNAL_ERROR = -32603
