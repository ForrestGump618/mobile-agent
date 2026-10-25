package com.mobileagent.core.agent

import com.mobileagent.core.model.TaskPlan
import com.mobileagent.core.security.RuntimeCapabilities
import com.mobileagent.core.tools.ToolRegistry

/**
 * System prompt assembly. It states real capabilities and explicit honesty rules; the model is
 * never told a capability is available unless the runtime reports it.
 */
object PromptComposer {

    fun systemPrompt(
        capabilities: RuntimeCapabilities,
        tools: ToolRegistry,
        modelLabel: String,
        memoryBlock: String,
        plan: TaskPlan?,
        skillBlock: String = "",
    ): String = buildString {
        append("You are Mobile Agent, an autonomous agent running as a native Android app.\n")
        append("Current model: ").append(modelLabel).append('\n')
        append("Device: Android ").append(android.os.Build.VERSION.RELEASE)
            .append(" (SDK ").append(android.os.Build.VERSION.SDK_INT).append("), ")
            .append(android.os.Build.MANUFACTURER).append(' ').append(android.os.Build.MODEL).append('\n')
        append("Capabilities right now: ")
        append("local shell=").append(if (capabilities.localShellAvailable()) "available" else "unavailable")
        append(", Shizuku=").append(
            when {
                !capabilities.shizukuAvailable() -> "not running"
                !capabilities.shizukuPermissionGranted() -> "running, permission not granted"
                else -> "ready"
            },
        )
        append(", Accessibility=").append(if (capabilities.accessibilityConnected()) "connected" else "not connected")
        append(".\n")
        append("Registered tools: ").append(tools.specs().size).append(" (see the tool list).\n")

        append(
            """
            |
            |Rules you must follow:
            |1. Use a tool whenever an answer depends on the device, the network, files or an app UI. Never invent command output, HTTP bodies, file contents or device values.
            |2. Call one or a few tools at a time and read the result before the next step. Do not describe actions you have not executed.
            |3. A tool result marked FAILURE is a real failure. Report the exact error; do not pretend it succeeded. If a capability is unavailable, stop and say what the user must enable.
            |4. Destructive or high-risk actions (deleting files, uninstalling apps, changing system settings, sending data to new endpoints) require the user's confirmation; the app enforces this, so simply request the tool.
            |5. Keep shell commands bounded and non-interactive. Prefer structured tools (device.info, file.*, http.request, ui.*) over raw shell.
            |6. Files you create belong in the app workspace; use file.write and report the returned path.
            |7. Write the final answer in the user's language, state what was actually done, list artifact paths, and mention anything that failed or was skipped.
            |8. After a tool call that changed the device, verify the outcome with a read-only check when possible.
            |
            """.trimMargin(),
        )

        if (memoryBlock.isNotBlank()) {
            append("\n").append(memoryBlock).append('\n')
        }

        if (skillBlock.isNotBlank()) {
            append("\n").append(skillBlock).append('\n')
        }

        if (plan != null && plan.steps.isNotEmpty()) {
            append("\nCurrent plan:\n")
            plan.steps.forEachIndexed { index, step ->
                append(index + 1).append(". [").append(step.status.name).append("] ").append(step.title)
                if (step.detail.isNotBlank()) append(" — ").append(step.detail)
                append('\n')
            }
        }
    }
}
