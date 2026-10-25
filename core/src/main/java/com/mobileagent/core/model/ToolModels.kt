package com.mobileagent.core.model

import kotlinx.serialization.json.JsonObject

enum class ToolCategory { SHELL, DEVICE, FILE, NETWORK, MCP, SKILL, UI, MEMORY, VISION, SYSTEM }

/** Declarative description of a tool. The JSON schema is handed to the language model verbatim. */
data class ToolSpec(
    val name: String,
    val title: String,
    val description: String,
    val parametersSchema: JsonObject,
    val risk: RiskLevel = RiskLevel.LOW,
    val category: ToolCategory = ToolCategory.SYSTEM,
    val source: String = "builtin",
    val requiresShizuku: Boolean = false,
    val requiresAccessibility: Boolean = false,
) {
    val requiresConfirmation: Boolean get() = risk.requiresConfirmation
}

data class ToolCallRequest(
    val callId: String,
    val name: String,
    val arguments: JsonObject,
)

data class ToolResult(
    val callId: String,
    val toolName: String,
    val ok: Boolean,
    val output: JsonObject,
    val summary: String,
    val error: String? = null,
    val durationMs: Long = 0L,
    val truncated: Boolean = false,
    val artifacts: List<String> = emptyList(),
) {
    fun toModelText(): String = buildString {
        append(if (ok) "SUCCESS" else "FAILURE")
        append(" tool=").append(toolName)
        append('\n').append(summary)
        if (!ok && error != null) {
            append("\nError: ").append(error)
        }
        if (output.isNotEmpty()) {
            append("\nData: ").append(output.toString())
        }
    }
}

/** Everything a tool may need from the harness at invocation time. */
class ToolContext(
    val taskId: String,
    val callId: String,
    val isCancelled: () -> Boolean = { false },
    val progress: (String) -> Unit = {},
    val workspaceDir: () -> java.io.File? = { null },
)

/**
 * A tool is the only way the agent can affect the device or the outside world.
 * Implementations must never bypass the policy engine; the orchestrator authorizes before execute().
 */
interface AgentTool {
    val spec: ToolSpec
    suspend fun execute(context: ToolContext, arguments: JsonObject): ToolResult
}
