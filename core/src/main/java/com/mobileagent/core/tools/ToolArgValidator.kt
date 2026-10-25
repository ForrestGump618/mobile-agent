package com.mobileagent.core.tools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Validates model-provided tool arguments against the tool's declared JSON schema, before the
 * policy engine and before any execution. This is what stops malformed or out-of-range calls.
 */
object ToolArgValidator {

    fun validate(schema: JsonObject, arguments: JsonObject): List<String> {
        val errors = mutableListOf<String>()
        val type = (schema["type"] as? JsonPrimitive)?.content
        if (type != null && type != "object") {
            errors += "Tool schema must describe an object"
            return errors
        }

        val required = (schema["required"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty()
        required.forEach { key ->
            if (!arguments.containsKey(key) || arguments[key] == kotlinx.serialization.json.JsonNull) {
                errors += "Missing required argument '$key'"
            }
        }

        val properties = schema["properties"]?.let { runCatching { it.jsonObject }.getOrNull() } ?: return errors
        arguments.forEach { (key, value) ->
            val property = properties[key]?.let { runCatching { it.jsonObject }.getOrNull() } ?: return@forEach
            val expected = (property["type"] as? JsonPrimitive)?.content ?: return@forEach
            val primitive = value as? JsonPrimitive
            if (primitive == null) {
                if (expected != "object" && expected != "array") errors += "Argument '$key' must be $expected"
                return@forEach
            }
            val matches = when (expected) {
                "string" -> primitive.isString
                "integer" -> !primitive.isString && primitive.longOrNull != null
                "number" -> !primitive.isString && primitive.doubleOrNull != null
                "boolean" -> primitive.booleanOrNull != null
                "array" -> value is JsonArray
                "object" -> value is JsonObject
                else -> true
            }
            if (!matches) errors += "Argument '$key' must be $expected"

            (property["enum"] as? JsonArray)?.let { enumValues ->
                val allowed = enumValues.mapNotNull { (it as? JsonPrimitive)?.content }
                if (primitive.isString && allowed.isNotEmpty() && primitive.content !in allowed) {
                    errors += "Argument '$key' must be one of ${allowed.joinToString()}"
                }
            }

            (property["maxLength"] as? JsonPrimitive)?.content?.toIntOrNull()?.let { max ->
                if (primitive.isString && primitive.content.length > max) {
                    errors += "Argument '$key' exceeds maxLength $max"
                }
            }
        }
        return errors
    }

    fun parseArguments(raw: String): Result<JsonObject> = runCatching {
        if (raw.isBlank()) JsonObject(emptyMap())
        else com.mobileagent.core.model.JsonCodec.json.parseToJsonElement(raw).jsonObject
    }
}
