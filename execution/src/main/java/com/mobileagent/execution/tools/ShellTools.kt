package com.mobileagent.execution.tools

import com.mobileagent.core.model.AgentTool
import com.mobileagent.core.model.RiskLevel
import com.mobileagent.core.model.ToolCategory
import com.mobileagent.core.model.ToolContext
import com.mobileagent.core.model.ToolResult
import com.mobileagent.core.model.ToolSpec
import com.mobileagent.execution.shell.LocalShellExecutor
import com.mobileagent.execution.shell.ShizukuShellExecutor
import com.mobileagent.execution.shell.ShellCommandPolicy
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Local (app UID) shell execution. */
class LocalShellTool(private val executor: LocalShellExecutor) : AgentTool {

    override val spec: ToolSpec = ToolSpec(
        name = "shell.exec",
        title = "Local shell",
        description = "Run a non-interactive command with /system/bin/sh as the Mobile Agent app UID. " +
            "This is NOT root and NOT ADB: it can only touch app-accessible files and standard Android shell tools. " +
            "Returns exit code, stdout and stderr. Catastrophic commands are blocked.",
        parametersSchema = Schemas.obj(
            required = listOf("command"),
            props = mapOf(
                "command" to Schemas.str("Shell command to execute", maxLength = 8_000),
                "timeoutMs" to Schemas.int("Timeout in milliseconds", 1_000, 120_000),
            ),
        ),
        risk = RiskLevel.MEDIUM,
        category = ToolCategory.SHELL,
        source = "execution",
    )

    override suspend fun execute(context: ToolContext, arguments: JsonObject): ToolResult {
        val startedAt = System.currentTimeMillis()
        val command = arguments["command"]?.jsonPrimitive?.content?.trim().orEmpty()
        if (command.isEmpty()) return failure(context, spec.name, "command is required", startedAt)

        ShellCommandPolicy.blockedReason(command)?.let { reason ->
            return failure(context, spec.name, reason, startedAt)
        }

        val timeout = arguments["timeoutMs"]?.jsonPrimitive?.longOrNull?.coerceIn(1_000L, 120_000L) ?: 30_000L
        val result = executor.execute(command, timeout)
        val output = buildJsonObject {
            put("executor", JsonPrimitive("local"))
            put("uid", JsonPrimitive("app"))
            put("exitCode", JsonPrimitive(result.exitCode))
            put("stdout", JsonPrimitive(result.stdout))
            put("stderr", JsonPrimitive(result.stderr))
            put("timedOut", JsonPrimitive(result.timedOut))
            put("truncated", JsonPrimitive(result.truncated))
            put("durationMs", JsonPrimitive(result.durationMs))
        }
        val error = result.error
        val ok = result.exitCode == 0 && !result.timedOut && error == null
        return ToolResult(
            callId = context.callId,
            toolName = spec.name,
            ok = ok,
            output = output,
            summary = buildString {
                append("local shell exit=").append(result.exitCode)
                append(", ").append(result.stdout.length).append(" stdout chars")
                if (result.stderr.isNotBlank()) append(", stderr: ").append(result.stderr.take(200))
                if (result.timedOut) append(" (timed out)")
            },
            error = error,
            durationMs = result.durationMs,
            truncated = result.truncated,
        )
    }
}

/** Shizuku (ADB shell UID) execution. High risk because it can touch other apps and system state. */
class ShizukuShellTool(private val executor: ShizukuShellExecutor) : AgentTool {

    override val spec: ToolSpec = ToolSpec(
        name = "adb.exec",
        title = "Shizuku ADB shell",
        description = "Run a non-interactive shell command under the Android ADB shell UID through Shizuku. " +
            "Enables pm, am, cmd, settings, dumpsys and similar commands that the app UID cannot use. " +
            "Requires Shizuku to be running with permission granted. Every call needs user confirmation under the default policy.",
        parametersSchema = Schemas.obj(
            required = listOf("command"),
            props = mapOf(
                "command" to Schemas.str("Shell command executed with the shell UID", maxLength = 8_000),
                "timeoutMs" to Schemas.int("Timeout in milliseconds", 1_000, 300_000),
            ),
        ),
        risk = RiskLevel.HIGH,
        category = ToolCategory.SHELL,
        source = "execution",
        requiresShizuku = true,
    )

    override suspend fun execute(context: ToolContext, arguments: JsonObject): ToolResult {
        val startedAt = System.currentTimeMillis()
        val command = arguments["command"]?.jsonPrimitive?.content?.trim().orEmpty()
        if (command.isEmpty()) return failure(context, spec.name, "command is required", startedAt)

        ShellCommandPolicy.blockedReason(command)?.let { reason ->
            return failure(context, spec.name, reason, startedAt)
        }

        val timeout = arguments["timeoutMs"]?.jsonPrimitive?.longOrNull?.coerceIn(1_000L, 300_000L) ?: 60_000L
        val result = executor.execute(command, timeout)
        val output = buildJsonObject {
            put("executor", JsonPrimitive("shizuku"))
            put("uid", JsonPrimitive("shell"))
            put("exitCode", JsonPrimitive(result.exitCode))
            put("stdout", JsonPrimitive(result.stdout))
            put("stderr", JsonPrimitive(result.stderr))
            put("timedOut", JsonPrimitive(result.timedOut))
            put("truncated", JsonPrimitive(result.truncated))
            put("durationMs", JsonPrimitive(result.durationMs))
        }
        val error = result.error
        val ok = result.exitCode == 0 && !result.timedOut && error == null
        return ToolResult(
            callId = context.callId,
            toolName = spec.name,
            ok = ok,
            output = output,
            summary = buildString {
                append("shizuku shell exit=").append(result.exitCode)
                append(", ").append(result.stdout.length).append(" stdout chars")
                if (result.stderr.isNotBlank()) append(", stderr: ").append(result.stderr.take(200))
            },
            error = error,
            durationMs = result.durationMs,
            truncated = result.truncated,
        )
    }
}

/** Reports which optional script runtimes actually exist on this device. */
class ScriptRuntimeTool(
    private val localShell: LocalShellExecutor,
) : AgentTool {

    override val spec: ToolSpec = ToolSpec(
        name = "runtime.status",
        title = "Script runtime status",
        description = "Check whether bash, python3, node or toybox applets are actually available. " +
            "Android does not ship Python or Node by default; this returns the real binaries found.",
        parametersSchema = Schemas.obj(required = emptyList(), props = emptyMap()),
        risk = RiskLevel.LOW,
        category = ToolCategory.DEVICE,
        source = "execution",
    )

    override suspend fun execute(context: ToolContext, arguments: JsonObject): ToolResult {
        val startedAt = System.currentTimeMillis()
        val result = localShell.execute(
            "for b in sh bash python python3 node npm busybox toybox; do p=\$(command -v \$b 2>/dev/null); " +
                "if [ -n \"\$p\" ]; then echo \"\$b=\$p\"; fi; done",
            timeoutMs = 8_000,
        )
        val runtimeError = result.error
        if (runtimeError != null) return failure(context, spec.name, runtimeError, startedAt)

        val found = result.stdout.lineSequence()
            .mapNotNull { line ->
                val parts = line.split("=", limit = 2)
                if (parts.size == 2 && parts[0].isNotBlank()) parts[0].trim() to parts[1].trim() else null
            }
            .toMap()

        val output = buildJsonObject {
            put("binaries", JsonObject(found.mapValues { JsonPrimitive(it.value) }))
            put("abi", JsonPrimitive(android.os.Build.SUPPORTED_ABIS.joinToString(",")))
            put("pythonAvailable", JsonPrimitive(found.containsKey("python3") || found.containsKey("python")))
            put("nodeAvailable", JsonPrimitive(found.containsKey("node")))
            put("bashAvailable", JsonPrimitive(found.containsKey("bash")))
        }
        return success(
            context = context,
            toolName = spec.name,
            summary = if (found.isEmpty()) {
                "No optional runtime found; only Android's built-in sh/toybox are available."
            } else {
                "Found: ${found.entries.joinToString { "${it.key} -> ${it.value}" }}"
            },
            startedAt = startedAt,
            output = output,
        )
    }
}
