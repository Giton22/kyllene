package dev.hermeskotlin.android.phone

import dev.hermeskotlin.core.phone.PhoneToolException
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** The agent's [name] argument as text; null when left out. Anything but a string is the agent's mistake. */
internal fun JsonObject.string(name: String): String? {
    val value = get(name)
    if (value == null || value == JsonNull) return null
    if (value !is JsonPrimitive || !value.isString) throw PhoneToolException("$name must be a string.")
    return value.content
}

/** The agent's [name] argument as a whole number; null when left out. */
internal fun JsonObject.int(name: String): Int? {
    val value = get(name)
    if (value == null || value == JsonNull) return null
    if (value !is JsonPrimitive || value.isString) throw PhoneToolException("$name must be a whole number.")
    return value.content.toIntOrNull() ?: throw PhoneToolException("$name must be a whole number.")
}

/** The agent's [name] argument as a number; null when left out. */
internal fun JsonObject.double(name: String): Double? {
    val value = get(name)
    if (value == null || value == JsonNull) return null
    if (value !is JsonPrimitive || value.isString) throw PhoneToolException("$name must be a number.")
    return value.content.toDoubleOrNull()?.takeIf { it.isFinite() } ?: throw PhoneToolException("$name must be a number.")
}

/** The agent's [name] argument as true or false; null when left out. */
internal fun JsonObject.boolean(name: String): Boolean? {
    val value = get(name)
    if (value == null || value == JsonNull) return null
    if (value !is JsonPrimitive || value.isString) throw PhoneToolException("$name must be true or false.")
    return value.content.toBooleanStrictOrNull() ?: throw PhoneToolException("$name must be true or false.")
}
