package com.mobileagent.core.agent

import com.mobileagent.core.data.TaskRepository
import com.mobileagent.core.model.AgentTool
import com.mobileagent.core.model.StepStatus
import com.mobileagent.core.model.TaskPlan
import com.mobileagent.core.model.ToolResult
import java.io.File

data class VerificationResult(
    val ok: Boolean,
    val notes: List<String> = emptyList(),
    /** Problems that must not be reported as success. */
    val hardIssues: List<String> = emptyList(),
)

/**
 * Verification is concrete: tool output fields, declared artifacts on disk, plan state and failed
 * tool calls. The model never grades its own answer.
 */
class ResultVerifier(private val tasks: TaskRepository) {

    fun verifyToolResult(tool: AgentTool, result: ToolResult): VerificationResult {
        val notes = mutableListOf<String>()
        if (!result.ok) {
            notes += "${tool.spec.name} failed: ${result.error ?: "unknown error"}"
            return VerificationResult(false, notes)
        }
        val required = when (tool.spec.name) {
            "shell.exec", "adb.exec" -> listOf("exitCode", "stdout")
            "file.write" -> listOf("path", "bytes")
            "file.read" -> listOf("content")
            "http.request" -> listOf("status")
            "ui.snapshot" -> listOf("nodeCount")
            "ui.click" -> listOf("action")
            "device.info" -> listOf("androidVersion")
            else -> emptyList()
        }
        val missing = required.filter { !result.output.containsKey(it) }
        if (missing.isNotEmpty()) {
            notes += "${tool.spec.name} returned ok but is missing ${missing.joinToString()}"
        }
        return VerificationResult(missing.isEmpty(), notes)
    }

    suspend fun verifyCompletion(taskId: String, answer: String, plan: TaskPlan?): VerificationResult {
        val hard = mutableListOf<String>()
        val notes = mutableListOf<String>()

        if (answer.isBlank()) hard += "The assistant produced an empty final answer."

        val artifacts = tasks.artifactsFor(taskId)
        val missingFiles = artifacts.filter { !File(it.path).exists() }
        if (missingFiles.isNotEmpty()) {
            hard += "Missing artifact file(s): ${missingFiles.joinToString { it.name }}"
        }

        plan?.steps?.filter { it.status == StepStatus.FAILED || it.status == StepStatus.BLOCKED }?.let { failed ->
            if (failed.isNotEmpty()) notes += "Plan step(s) not completed: ${failed.joinToString { it.title }}"
        }

        val failedTools = tasks.toolCallsFor(taskId).filter { it.ok == false }
        if (failedTools.isNotEmpty()) {
            notes += "${failedTools.size} tool call(s) failed during the task (${failedTools.take(4).joinToString { it.toolName }})"
        }

        val unfinishedTools = tasks.toolCallsFor(taskId).filter { it.ok == null }
        if (unfinishedTools.isNotEmpty()) {
            notes += "${unfinishedTools.size} tool call(s) have an unknown outcome (${unfinishedTools.joinToString { it.toolName }}); verify manually."
        }

        return VerificationResult(ok = hard.isEmpty(), notes = notes, hardIssues = hard)
    }

    /** Marks the earliest actionable plan step and returns the updated plan. */
    fun advancePlan(plan: TaskPlan?, status: StepStatus, stepResult: String? = null): TaskPlan? {
        if (plan == null) return null
        val step = plan.steps.firstOrNull { it.status == StepStatus.RUNNING }
            ?: plan.steps.firstOrNull { it.status == StepStatus.PENDING || it.status == StepStatus.BLOCKED }
            ?: return plan
        return plan.withStepStatus(step.id, status, stepResult?.take(200))
    }
}
