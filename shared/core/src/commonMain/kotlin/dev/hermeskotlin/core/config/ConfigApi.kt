package dev.hermeskotlin.core.config

import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.network.ApiResult
import dev.hermeskotlin.core.network.HermesJson
import dev.hermeskotlin.core.network.apiCall
import dev.hermeskotlin.core.network.map
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** One setting as `GET /api/config/schema` describes it, keyed elsewhere by its dotted path (`approvals.mode`). */
@Serializable
data class ConfigField(
    /** `boolean`, `number`, `string`, `select` or `list`. */
    val type: String = "string",
    val description: String = "",
    /** The schema's own grouping (`agent`, `voice`, `tts`…), not Desktop's sections. */
    val category: String = "general",
    /** A select's choices; `""` among them means "not set". */
    val options: List<JsonElement> = emptyList(),
    /** The select may be left empty. */
    val clearable: Boolean = false,
    /** The choices are many (the time zones), so the picker gets a search. */
    val searchable: Boolean = false,
) {
    /** [options] as text, each once; a `null` option is "not set", as `""` is. */
    val choices: List<String> get() = options.map { if (it is JsonNull) "" else (it as? JsonPrimitive)?.content ?: it.toString() }.distinct()
}

/** Every setting the gateway knows, and the order Desktop shows its categories in. */
@Serializable
data class ConfigSchema(
    val fields: Map<String, ConfigField> = emptyMap(),
    @SerialName("category_order") val categoryOrder: List<String> = emptyList(),
)

/** The profile's config.yaml as written, and where it lives on the gateway. */
@Serializable
data class RawConfig(val yaml: String = "", val path: String = "")

@Serializable
private data class ConfigUpdate(val config: JsonObject)

@Serializable
private data class RawConfigUpdate(@SerialName("yaml_text") val yamlText: String)

/**
 * Hermes's own settings (config.yaml), through the dashboard routes Desktop's Settings use. A save
 * sends only what changed: the gateway deep-merges it onto the file, so a setting changed elsewhere
 * while the page was open keeps its new value.
 */
class ConfigApi(private val client: HttpClient) {

    suspend fun schema(url: GatewayUrl, profile: String?): ApiResult<ConfigSchema> = apiCall {
        client.get(url.resolve("api/config/schema")) { profile(profile) }
    }.map { lenientJson.decodeFromString<ConfigSchema>(it.bodyAsText()) }

    /** The settings with Hermes's defaults filled in; `model` comes flattened to its name, as the schema has it. */
    suspend fun values(url: GatewayUrl, profile: String?): ApiResult<JsonObject> = apiCall {
        client.get(url.resolve("api/config")) { profile(profile) }
    }.map { lenientJson.decodeFromString<JsonObject>(it.bodyAsText()) }

    /** Merges [patch] (a nested object holding only the changed keys) into the profile's config. */
    suspend fun save(url: GatewayUrl, profile: String?, patch: JsonObject): ApiResult<Unit> = apiCall {
        client.put(url.resolve("api/config")) {
            profile(profile)
            contentType(ContentType.Application.Json)
            setBody(ConfigUpdate(patch))
        }
    }.map { }

    suspend fun raw(url: GatewayUrl, profile: String?): ApiResult<RawConfig> = apiCall {
        client.get(url.resolve("api/config/raw")) { profile(profile) }
    }.map { lenientJson.decodeFromString<RawConfig>(it.bodyAsText()) }

    /** Replaces the whole file with [yaml]; the gateway refuses text that isn't a YAML mapping. */
    suspend fun saveRaw(url: GatewayUrl, profile: String?, yaml: String): ApiResult<Unit> = apiCall {
        client.put(url.resolve("api/config/raw")) {
            profile(profile)
            contentType(ContentType.Application.Json)
            setBody(RawConfigUpdate(yaml))
        }
    }.map { }

    private fun HttpRequestBuilder.profile(profile: String?) {
        profile?.let { parameter("profile", it) }
    }

    private companion object {
        /** The schema writes `null` for what a field doesn't have; that reads as the default. */
        val lenientJson = Json(HermesJson) { coerceInputValues = true }
    }
}
