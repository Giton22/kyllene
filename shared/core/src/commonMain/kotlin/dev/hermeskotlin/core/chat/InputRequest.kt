package dev.hermeskotlin.core.chat

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** How far an approval reaches: this command once, the pattern for the session, or permanently. */
enum class ApprovalChoice(val wire: String) {
    Once("once"),
    Session("session"),
    Always("always"),
    Deny("deny"),
    ;

    companion object {
        fun fromWire(value: String): ApprovalChoice? = entries.firstOrNull { it.wire == value }
    }
}

/** How a notification or a watch names the choice. */
val ApprovalChoice.label: String
    get() = when (this) {
        ApprovalChoice.Once -> "Allow once"
        ApprovalChoice.Session -> "Allow for chat"
        ApprovalChoice.Always -> "Always allow"
        ApprovalChoice.Deny -> "Deny"
    }

/** The choices offered outside the app: "Always" is too big a decision for a notification or a watch. */
val InputRequest.Approval.choicesAway: List<ApprovalChoice> get() = choices.filter { it != ApprovalChoice.Always }

data class ClarifyQuestion(
    /** Batch question id; null for a single-question request. */
    val qid: String?,
    val question: String,
    val choices: List<String> = emptyList(),
    val multiSelect: Boolean = false,
)

/**
 * How an answer to an [InputRequest] goes back to the gateway:
 * - [ServerRequest]: classic JSON-RPC response frame (`client.respond(id, result)`).
 * - [PendingControl]: canonical RPC (`clarify.respond` / `approval.respond`) with `prompt_id` and `execution_generation`.
 */
sealed interface InputRequestDelivery {
    data object ServerRequest : InputRequestDelivery
    data class PendingControl(val executionGeneration: Long?) : InputRequestDelivery
}

/**
 * Something the agent is blocked on until a person answers: a server→client request
 * (tui_gateway/server_requests.py) or a pending-controls prompt event (`clarify.request` /
 * `approval.request`) that this client knows how to show.
 */
sealed interface InputRequest {
    /** The `srq-…` request id or pending-controls `prompt_id` the answer is sent back under. */
    val id: String

    /** How the answer is delivered back to the gateway. */
    val delivery: InputRequestDelivery get() = InputRequestDelivery.ServerRequest

    /** A dangerous command waits for a decision. The gateway already redacted [command]. */
    data class Approval(
        override val id: String,
        val command: String,
        val description: String,
        val toolName: String?,
        /** What the gateway will accept, in its order; always holds [ApprovalChoice.Once] and [ApprovalChoice.Deny]. */
        val choices: List<ApprovalChoice>,
        override val delivery: InputRequestDelivery = InputRequestDelivery.ServerRequest,
    ) : InputRequest

    /** The clarify tool: one question, or a batch answered together. */
    data class Clarify(
        override val id: String,
        val questions: List<ClarifyQuestion>,
        override val delivery: InputRequestDelivery = InputRequestDelivery.ServerRequest,
    ) : InputRequest {
        val batch: Boolean get() = questions.any { it.qid != null }
    }

    /**
     * A one-line value: the sudo password for a terminal command, a named secret, the master password
     * of a password manager ([Kind.VaultUnlock]) or a sign-in code for the page the agent is on
     * ([Kind.VaultCode], the only one not masked).
     */
    data class Secret(
        override val id: String,
        val kind: Kind,
        val prompt: String,
        /** The env var a [Kind.Secret] fills, e.g. `OPENAI_API_KEY`. */
        val envVar: String?,
        /** The command a [Kind.Sudo] password is for. */
        val command: String?,
    ) : InputRequest {
        enum class Kind { Sudo, Secret, VaultUnlock, VaultCode }
    }

    /**
     * The agent is on a sign-in page with nothing in the vault: save a login for it. Answered with
     * [InputAnswers.saveLogin], or an empty value to not save.
     */
    data class VaultSaveLogin(
        override val id: String,
        /** The site's name as the agent shows it, e.g. `github.com`. */
        val site: String,
        /** The page's origin, e.g. `https://github.com`. */
        val origin: String,
    ) : InputRequest {
        /** The question, without a blank where the site goes when the gateway named none. */
        val title: String get() = if (site.isBlank()) "Save a login?" else "Save your $site login?"
    }

    companion object {
        /** The methods this client answers; anything else is left for another client. */
        val METHODS = setOf("approval", "clarify", "sudo", "secret", "vault.unlock_prompt", "vault.code", "vault.save_login")

        fun parse(
            id: String,
            method: String,
            params: JsonObject,
            delivery: InputRequestDelivery = InputRequestDelivery.ServerRequest,
        ): InputRequest? = when (method) {
            "approval" -> Approval(
                id = id,
                command = params.string("command").orEmpty(),
                description = params.string("description").orEmpty(),
                toolName = params.string("tool_name"),
                choices = approvalChoices(params),
                delivery = delivery,
            )
            "clarify" -> clarifyQuestions(params).takeIf { it.isNotEmpty() }?.let { Clarify(id, it, delivery) }
            "sudo" -> Secret(id, Secret.Kind.Sudo, "Password for sudo", envVar = null, command = params.string("command"))
            "secret" -> Secret(
                id = id,
                kind = Secret.Kind.Secret,
                prompt = params.string("prompt")?.takeIf { it.isNotBlank() } ?: "Value for ${params.string("env_var").orEmpty()}",
                envVar = params.string("env_var"),
                command = null,
            )
            "vault.unlock_prompt" -> {
                val name = params.string("display_name")?.takeIf { it.isNotBlank() }
                    ?: params.string("backend")?.takeIf { it.isNotBlank() }
                    ?: "your password manager"
                Secret(id, Secret.Kind.VaultUnlock, "Hermes needs $name unlocked to sign in for you.", envVar = null, command = null)
            }
            "vault.code" -> {
                val site = params.string("site")?.takeIf { it.isNotBlank() }
                val hint = params.string("hint")?.takeIf { it.isNotBlank() }
                val ask = if (site != null) "Enter the sign-in code for $site." else "Enter the sign-in code."
                Secret(id, Secret.Kind.VaultCode, listOfNotNull(ask, hint).joinToString(" "), envVar = null, command = null)
            }
            "vault.save_login" -> {
                val origin = params.string("origin").orEmpty()
                VaultSaveLogin(id, site = params.string("site")?.takeIf { it.isNotBlank() } ?: origin, origin = origin)
            }
            else -> null
        }

        /** Parses a live pending-controls event (`clarify.request` or `approval.request`). */
        fun parseEvent(type: String, payload: JsonObject?): InputRequest? {
            if (payload == null) return null
            val method = when (type) {
                "clarify.request" -> "clarify"
                "approval.request" -> "approval"
                else -> return null
            }
            val id = payload.string("prompt_id") ?: payload.string("id") ?: return null
            val generation = payload.long("execution_generation")
            return parse(id, method, payload, InputRequestDelivery.PendingControl(generation))
        }

        /** The id of a prompt retired by a cancel/settled event, or null if [type] isn't one. */
        fun settledId(type: String, payload: JsonObject?): String? = when (type) {
            "request.cancel", "approval.cancelled", "clarify.settled", "approval.settled", "approval.responded" ->
                payload.string("prompt_id") ?: payload.string("id") ?: payload.string("request_id")
            else -> null
        }

        /**
         * All open prompts returned by `session.resume`, deduplicated by id: the session authority's pending
         * clarify and approval prompts in `prompts` (`CanonicalSnapshot.prompts`, the only place a prompt asked
         * while this client wasn't attached comes back), classic server→client `open_requests`, and the
         * `pending_controls` / `pending_clarify` / `pending_approval` shapes read before.
         */
        fun parseResume(result: JsonObject): List<InputRequest> {
            val found = mutableListOf<InputRequest>()
            val seen = HashSet<String>()
            fun add(request: InputRequest?) {
                if (request != null && seen.add(request.id)) found += request
            }
            result["prompts"].asObjectList().forEach { add(parsePendingControl(it, defaultKind = null)) }
            result["open_requests"].asObjectList().forEach { snapshot ->
                val promptId = snapshot.string("prompt_id")
                if (promptId != null) {
                    add(parsePendingControl(snapshot, defaultKind = null))
                } else {
                    val id = snapshot.string("id") ?: return@forEach
                    val method = snapshot.string("method") ?: return@forEach
                    val params = snapshot["params"] as? JsonObject ?: JsonObject(emptyMap())
                    add(parse(id, method, params))
                }
            }
            when (val pending = result["pending_controls"]) {
                is JsonArray -> pending.asObjectList().forEach { add(parsePendingControl(it, defaultKind = null)) }
                is JsonObject -> {
                    pending["items"].asObjectList().forEach { add(parsePendingControl(it, defaultKind = null)) }
                    (pending["clarify"] as? JsonObject)?.let { add(parsePendingControl(it, defaultKind = "clarify")) }
                    (pending["approval"] as? JsonObject)?.let { add(parsePendingControl(it, defaultKind = "approval")) }
                }
                else -> Unit
            }
            (result["pending_clarify"] as? JsonObject)?.let { add(parsePendingControl(it, defaultKind = "clarify")) }
            (result["pending_approval"] as? JsonObject)?.let { add(parsePendingControl(it, defaultKind = "approval")) }
            return found
        }

        private fun parsePendingControl(snapshot: JsonObject, defaultKind: String?): InputRequest? {
            val payload = (snapshot["payload"] as? JsonObject) ?: (snapshot["params"] as? JsonObject) ?: snapshot
            val rawKind = snapshot.string("kind") ?: snapshot.string("type") ?: snapshot.string("method") ?: defaultKind
            val method = when (rawKind) {
                "clarify", "clarify.request" -> "clarify"
                "approval", "approval.request" -> "approval"
                else -> when {
                    payload.string("command") != null -> "approval"
                    payload.string("question") != null || payload["questions"] is JsonArray -> "clarify"
                    else -> return null
                }
            }
            val id = snapshot.string("prompt_id") ?: payload.string("prompt_id") ?: snapshot.string("id") ?: payload.string("id") ?: return null
            val generation = snapshot.long("execution_generation") ?: payload.long("execution_generation")
            return parse(id, method, payload, InputRequestDelivery.PendingControl(generation))
        }

        /** The canonical respond RPC method for a [InputRequestDelivery.PendingControl] request. */
        fun respondMethod(request: InputRequest): String? = when (request) {
            is Clarify -> "clarify.respond"
            is Approval -> "approval.respond"
            else -> null
        }

        /** The payload's own `choices` when present, else the gateway's rule (`_approval_request_payload`). */
        private fun approvalChoices(params: JsonObject): List<ApprovalChoice> {
            val offered = (params["choices"] as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.let(ApprovalChoice::fromWire) }
                ?.takeIf { it.isNotEmpty() }
            val choices = offered ?: buildList {
                add(ApprovalChoice.Once)
                if (params.boolean("smart_denied") != true && params.boolean("allow_session") != false) {
                    add(ApprovalChoice.Session)
                    if (params.boolean("allow_permanent") != false) add(ApprovalChoice.Always)
                }
            }
            return (listOf(ApprovalChoice.Once) + choices + ApprovalChoice.Deny).distinct().sortedBy { it.ordinal }
        }

        private fun clarifyQuestions(params: JsonObject): List<ClarifyQuestion> {
            val batch = (params["questions"] as? JsonArray)?.mapNotNull { item ->
                val question = item as? JsonObject ?: return@mapNotNull null
                ClarifyQuestion(
                    qid = question.string("qid") ?: return@mapNotNull null,
                    question = question.string("question").orEmpty(),
                    choices = question.strings("choices"),
                    multiSelect = question.boolean("multi_select") == true,
                )
            }
            if (!batch.isNullOrEmpty()) return batch
            val question = params.string("question") ?: return emptyList()
            return listOf(ClarifyQuestion(null, question, params.strings("choices"), params.boolean("multi_select") == true))
        }
    }
}

/** The response `result` for each kind of answer, shaped as the gateway contract wants it. */
object InputAnswers {
    fun approval(choice: ApprovalChoice): JsonObject = buildJsonObject { put("choice", choice.wire) }

    /**
     * One entry per question. A multi-select answer is the picked options as a JSON array string,
     * like Desktop sends it; an empty answer means skipped.
     */
    fun clarify(request: InputRequest.Clarify, answers: List<List<String>>): JsonObject {
        val texts = answers.map { picked ->
            when (picked.size) {
                0 -> ""
                1 -> picked.single()
                else -> buildJsonArray { picked.forEach { add(JsonPrimitive(it)) } }.toString()
            }
        }
        return buildJsonObject {
            if (request.batch) {
                put("answers", buildJsonObject { request.questions.zip(texts).forEach { (q, text) -> put(q.qid.orEmpty(), text) } })
            } else {
                put("answer", texts.firstOrNull().orEmpty())
            }
        }
    }

    /** `{}` (neither `answer` nor `answers`) cancels every question at once. */
    val clarifyCancel: JsonObject = JsonObject(emptyMap())

    /** An empty [value] declines the prompt. */
    fun value(value: String): JsonObject = buildJsonObject { put("value", value) }

    /**
     * A [InputRequest.VaultSaveLogin] answer: the login as a JSON string in `value`. The gateway drops
     * one without a password, so send [value] `""` to not save.
     */
    fun saveLogin(identifier: String, password: String): JsonObject = value(
        buildJsonObject {
            put("identifier", identifier)
            put("password", password)
        }.toString(),
    )
}

private fun JsonObject.strings(key: String): List<String> =
    (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()

internal fun JsonElement?.asObjectList(): List<JsonObject> = (this as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
