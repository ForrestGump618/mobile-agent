package com.mobileagent.app.tools

import com.mobileagent.core.data.IntegrationRepository
import com.mobileagent.core.model.AgentTool
import com.mobileagent.core.model.RiskLevel
import com.mobileagent.core.model.ToolCategory
import com.mobileagent.core.model.ToolContext
import com.mobileagent.core.model.ToolResult
import com.mobileagent.core.model.ToolSpec
import com.mobileagent.execution.tools.Schemas
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** Saves a durable fact the user asked the agent to remember. */
class MemorySaveTool(private val repository: IntegrationRepository) : AgentTool {
    override val spec: ToolSpec = ToolSpec(
        name = "memory.save",
        title = "Remember a fact",
        description = "Store a durable fact in long-term memory so later tasks can reuse it. " +
            "Never store API keys, passwords or one-time codes.",
        parametersSchema = Schemas.obj(
            required = listOf("key", "value"),
            props = mapOf(
                "key" to Schemas.str("Short identifier, e.g. preferred_report_language", maxLength = 120),
                "value" to Schemas.str("The fact to remember", maxLength = 2_000),
                "scope" to Schemas.str("Scope: user, project or tool", enum = listOf("user", "project", "tool")),
                "sensitive" to Schemas.bool("Mark as sensitive so it is excluded from prompts by default"),
            ),
        ),
        risk = RiskLevel.LOW,
        category = ToolCategory.MEMORY,
        source = "app",
    )

    override suspend fun execute(context: ToolContext, arguments: JsonObject): ToolResult {
        val startedAt = System.currentTimeMillis()
        val key = arguments["key"]?.jsonPrimitive?.content.orEmpty()
        val value = arguments["value"]?.jsonPrimitive?.content.orEmpty()
        if (key.isBlank() || value.isBlank()) {
            return ToolResult(
                context.callId, spec.name, false, JsonObject(emptyMap()),
                "key and value are required", error = "invalid arguments", durationMs = System.currentTimeMillis() - startedAt,
            )
        }
        val scope = arguments["scope"]?.jsonPrimitive?.content ?: "user"
        val sensitive = arguments["sensitive"]?.jsonPrimitive?.booleanOrNull ?: false
        repository.upsertMemory(
            id = java.util.UUID.randomUUID().toString(),
            scope = scope,
            key = key,
            value = value,
            sensitive = sensitive,
        )
        return ToolResult(
            callId = context.callId,
            toolName = spec.name,
            ok = true,
            output = buildJsonObject {
                put("key", JsonPrimitive(key))
                put("scope", JsonPrimitive(scope))
                put("sensitive", JsonPrimitive(sensitive))
            },
            summary = "Saved memory '$key' in scope $scope.",
            durationMs = System.currentTimeMillis() - startedAt,
        )
    }
}

/** Keyword search over long-term memory with an honest hit count. */
class MemorySearchTool(private val repository: IntegrationRepository) : AgentTool {
    override val spec: ToolSpec = ToolSpec(
        name = "memory.search",
        title = "Search memory",
        description = "Search saved long-term memory with keywords. Returns the matching facts and highlights that no semantic search is configured.",
        parametersSchema = Schemas.obj(
            required = listOf("query"),
            props = mapOf(
                "query" to Schemas.str("Keywords to look for", maxLength = 200),
                "limit" to Schemas.int("Maximum results", 1, 20),
            ),
        ),
        risk = RiskLevel.LOW,
        category = ToolCategory.MEMORY,
        source = "app",
    )

    override suspend fun execute(context: ToolContext, arguments: JsonObject): ToolResult {
        val startedAt = System.currentTimeMillis()
        val query = arguments["query"]?.jsonPrimitive?.content.orEmpty().lowercase()
        val limit = arguments["limit"]?.jsonPrimitive?.content?.toIntOrNull()?.coerceIn(1, 20) ?: 5
        val matches = repository.enabledMemories()
            .filter { it.key.lowercase().contains(query) || it.value.lowercase().contains(query) }
            .take(limit)
        val output = buildJsonObject {
            put("count", JsonPrimitive(matches.size))
            put(
                "matches",
                kotlinx.serialization.json.JsonArray(
                    matches.map { memory ->
                        buildJsonObject {
                            put("key", JsonPrimitive(memory.key))
                            put("value", JsonPrimitive(memory.value))
                            put("scope", JsonPrimitive(memory.scope))
                        }
                    },
                ),
            )
        }
        return ToolResult(
            callId = context.callId,
            toolName = spec.name,
            ok = true,
            output = output,
            summary = if (matches.isEmpty()) "No memory matched '$query' (keyword search)." else "${matches.size} memory match(es).",
            durationMs = System.currentTimeMillis() - startedAt,
        )
    }
}
