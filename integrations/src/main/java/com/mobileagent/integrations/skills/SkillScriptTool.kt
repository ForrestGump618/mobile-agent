package com.mobileagent.integrations.skills

import com.mobileagent.core.data.IntegrationRepository
import com.mobileagent.core.model.AgentTool
import com.mobileagent.core.model.RiskLevel
import com.mobileagent.core.model.ToolCategory
import com.mobileagent.core.model.ToolContext
import com.mobileagent.core.model.ToolResult
import com.mobileagent.core.model.ToolSpec
import com.mobileagent.execution.shell.LocalShellExecutor
import com.mobileagent.execution.workspace.Workspace
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File

/**
 * Runs the entry point of a script skill (python/node/shell). The runtime is verified on the device
 * first; if it is missing the tool reports Runtime Unavailable instead of pretending to run.
 */
class SkillScriptTool(
    private val repository: IntegrationRepository,
    private val workspace: Workspace,
    private val localShell: LocalShellExecutor,
) : AgentTool {

    override val spec: ToolSpec = ToolSpec(
        name = "skill.run",
        title = "Run a skill script",
        description = "Execute the declared entry point of an installed script skill with the app UID. " +
            "Python/Node must actually be installed. Script output, exit code and errors are returned.",
        parametersSchema = com.mobileagent.execution.tools.Schemas.obj(
            required = listOf("skillId"),
            props = mapOf(
                "skillId" to com.mobileagent.execution.tools.Schemas.str("Installed skill id"),
                "input" to com.mobileagent.execution.tools.Schemas.str("Optional input passed as a file argument", maxLength = 20_000),
                "timeoutMs" to com.mobileagent.execution.tools.Schemas.int("Timeout", 1_000, 300_000),
            ),
        ),
        risk = RiskLevel.HIGH,
        category = ToolCategory.SKILL,
        source = "skills",
    )

    override suspend fun execute(context: ToolContext, arguments: JsonObject): ToolResult {
        val startedAt = System.currentTimeMillis()
        val skillId = arguments["skillId"]?.jsonPrimitive?.content?.trim().orEmpty()
        val skill = repository.skill(skillId)
            ?: return fail(context, "Unknown skill '$skillId'", startedAt)
        if (!skill.enabled) return fail(context, "Skill '${skill.name}' is disabled.", startedAt)

        val runtime = skill.manifest.runtime.lowercase()
        if (runtime == "prompt") {
            return fail(
                context,
                "Skill '${skill.name}' is prompt-only: it guides the agent through registered tools and has no script to execute.",
                startedAt,
            )
        }
        val entry = skill.manifest.entryPoint
            ?: return fail(context, "Skill '${skill.name}' has no entry_point.", startedAt)

        val skillDir = File(workspace.root, "skills/${skill.id}").canonicalFile
        val script = File(skillDir, entry).canonicalFile
        if (!script.path.startsWith(skillDir.path + File.separator) || !script.exists()) {
            return fail(context, "Skill script is missing from the workspace: $entry", startedAt)
        }
        if (entry.contains('"') || entry.contains('$')) {
            return fail(context, "entry_point contains unsupported characters.", startedAt)
        }

        val binary = when (runtime) {
            "python", "python3" -> "python3"
            "node" -> "node"
            "shell", "bash" -> "sh"
            else -> return fail(context, "Unsupported runtime '$runtime'.", startedAt)
        }
        val available = localShell.execute("command -v $binary 2>/dev/null || true", timeoutMs = 5_000)
        if (available.stdout.isBlank()) {
            return fail(
                context,
                "Runtime Unavailable: '$binary' is not installed on this device. Install a Python/Node runtime or convert the skill to a prompt skill.",
                startedAt,
            )
        }

        val input = arguments["input"]?.jsonPrimitive?.content.orEmpty()
        val inputFile = File(workspace.subdirectory("cache"), "skill-input-${System.currentTimeMillis()}.txt")
        inputFile.writeText(input, Charsets.UTF_8)

        val timeout = arguments["timeoutMs"]?.jsonPrimitive?.longOrNull?.coerceIn(1_000L, 300_000L) ?: 60_000L
        val command = "cd \"${skillDir.absolutePath}\" && AGENT_SKILL_ID=$skillId $binary \"${script.name}\" \"${inputFile.absolutePath}\""
        val result = localShell.execute(command, timeout)
        val ok = result.exitCode == 0 && !result.timedOut && result.error == null

        val output = buildJsonObject {
            put("skillId", JsonPrimitive(skill.id))
            put("runtime", JsonPrimitive(runtime))
            put("exitCode", JsonPrimitive(result.exitCode))
            put("stdout", JsonPrimitive(result.stdout))
            put("stderr", JsonPrimitive(result.stderr))
            put("timedOut", JsonPrimitive(result.timedOut))
            put("durationMs", JsonPrimitive(result.durationMs))
        }
        return ToolResult(
            callId = context.callId,
            toolName = spec.name,
            ok = ok,
            output = output,
            summary = if (ok) {
                "Skill ${skill.name} finished with exit code 0 (${result.stdout.length} chars of output)."
            } else {
                "Skill ${skill.name} failed: ${result.error ?: "exit ${result.exitCode}"}"
            },
            error = if (ok) null else (result.error ?: "exit code ${result.exitCode}"),
            durationMs = System.currentTimeMillis() - startedAt,
        )
    }

    private fun fail(context: ToolContext, message: String, startedAt: Long): ToolResult = ToolResult(
        callId = context.callId,
        toolName = spec.name,
        ok = false,
        output = JsonObject(emptyMap()),
        summary = message,
        error = message,
        durationMs = System.currentTimeMillis() - startedAt,
    )
}
