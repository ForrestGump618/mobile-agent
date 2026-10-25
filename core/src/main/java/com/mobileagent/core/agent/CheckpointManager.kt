package com.mobileagent.core.agent

import com.mobileagent.core.data.TaskRepository
import com.mobileagent.core.model.CheckpointPayload
import com.mobileagent.core.model.TaskPlan
import com.mobileagent.core.model.TaskRecord
import com.mobileagent.core.model.AgentEventType

/**
 * Persists restartable state after every meaningful step. Secrets never enter a checkpoint: the
 * payload only carries ids, paths, status flags and text summaries.
 */
class CheckpointManager(private val tasks: TaskRepository) {

    suspend fun save(
        task: TaskRecord,
        plan: TaskPlan?,
        iteration: Int,
        lastError: String?,
        recoveryStrategy: String,
        permissions: Map<String, Boolean>,
    ) {
        val artifacts = tasks.artifactsFor(task.id)
        val payload = CheckpointPayload(
            taskId = task.id,
            goal = task.goal,
            plan = plan,
            completedSteps = plan?.completedSteps?.map { it.id }.orEmpty(),
            pendingSteps = plan?.pendingSteps?.map { it.id }.orEmpty(),
            toolResults = tasks.toolCallsFor(task.id).map { call ->
                "${call.toolName}#${call.id.take(8)}=${if (call.ok == true) "ok" else if (call.ok == false) "failed" else "unknown"}"
            },
            artifactPaths = artifacts.map { it.path },
            permissionState = permissions,
            lastError = lastError,
            recoveryStrategy = recoveryStrategy,
            contextSummary = task.contextSummary.orEmpty(),
            iteration = iteration,
        )
        tasks.saveCheckpoint(task.id, payload)
    }

    suspend fun latest(taskId: String): CheckpointPayload? = tasks.latestCheckpoint(taskId)

    /**
     * A crashed tool call (started, never finished) may have had a side effect. The recovery plan
     * therefore always asks for verification instead of blind replay.
     */
    suspend fun recoveryNote(taskId: String): String {
        val checkpoint = latest(taskId)
        val unfinished = tasks.toolCallsFor(taskId).filter { it.ok == null }
        val builder = StringBuilder()
        if (checkpoint != null) {
            builder.append("Last checkpoint iteration ${checkpoint.iteration}, ")
            builder.append("${checkpoint.completedSteps.size} completed step(s), ")
            builder.append("${checkpoint.artifactPaths.size} artifact(s). ")
        }
        if (unfinished.isNotEmpty()) {
            builder.append("The following tool calls started but never reported a result: ")
            builder.append(unfinished.joinToString { it.toolName })
            builder.append(". Verify their effect before retrying.")
            tasks.addEvent(
                taskId,
                null,
                AgentEventType.RECOVERY_DECISION,
                "Unfinished tool call(s) detected: ${unfinished.joinToString { it.toolName }}. Verification required before replay.",
            )
        }
        return builder.toString().trim()
    }
}
