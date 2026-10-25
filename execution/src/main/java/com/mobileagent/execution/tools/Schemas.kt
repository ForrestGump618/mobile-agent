package com.mobileagent.execution.tools

import com.mobileagent.core.model.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Small helpers so every tool publishes an explicit, valid JSON schema. */
object Schemas {

    fun obj(required: List<String>, props: Map<String, JsonObject>): JsonObject = buildJsonObject {
        put("type", JsonPrimitive("object"))
        put("properties", JsonObject(props))
        put(
            "required",
            kotlinx.serialization.json.JsonArray(required.map { JsonPrimitive(it) }),
        )
        put("additionalProperties", JsonPrimitive(false))
    }

    fun str(description: String, enum: List<String>? = null, maxLength: Int? = null): JsonObject = buildJsonObject {
        put("type", JsonPrimitive("string"))
        put("description", JsonPrimitive(description))
        if (enum != null) {
            put("enum", kotlinx.serialization.json.JsonArray(enum.map { JsonPrimitive(it) }))
        }
        if (maxLength != null) put("maxLength", JsonPrimitive(maxLength))
    }

    fun int(description: String, minimum: Int? = null, maximum: Int? = null): JsonObject = buildJsonObject {
        put("type", JsonPrimitive("integer"))
        put("description", JsonPrimitive(description))
        if (minimum != null) put("minimum", JsonPrimitive(minimum))
        if (maximum != null) put("maximum", JsonPrimitive(maximum))
    }

    fun bool(description: String): JsonObject = buildJsonObject {
        put("type", JsonPrimitive("boolean"))
        put("description", JsonPrimitive(description))
    }

    fun arr(description: String, items: JsonObject): JsonObject = buildJsonObject {
        put("type", JsonPrimitive("array"))
        put("description", JsonPrimitive(description))
        put("items", items)
    }

    fun objRef(description: String): JsonObject = buildJsonObject {
        put("type", JsonPrimitive("object"))
        put("description", JsonPrimitive(description))
    }
}

internal fun buildToolOutput(builder: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): JsonObject =
    buildJsonObject(builder)

internal fun failure(
    context: com.mobileagent.core.model.ToolContext,
    toolName: String,
    error: String,
    startedAt: Long,
): ToolResult = ToolResult(
    callId = context.callId,
    toolName = toolName,
    ok = false,
    output = JsonObject(emptyMap()),
    summary = error,
    error = error,
    durationMs = System.currentTimeMillis() - startedAt,
)

internal fun success(
    context: com.mobileagent.core.model.ToolContext,
    toolName: String,
    summary: String,
    startedAt: Long,
    artifacts: List<String> = emptyList(),
    output: JsonObject,
): ToolResult = ToolResult(
    callId = context.callId,
    toolName = toolName,
    ok = true,
    output = output,
    summary = summary,
    durationMs = System.currentTimeMillis() - startedAt,
    artifacts = artifacts,
)
