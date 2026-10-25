package com.mobileagent.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Shared JSON configuration for all structured payloads in the app. */
object JsonCodec {
    val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        explicitNulls = false
        prettyPrint = false
    }

    val pretty: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        explicitNulls = false
        prettyPrint = true
    }
}

/** Risk classification used by the policy engine. */
enum class RiskLevel {
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL,
    ;

    val requiresConfirmation: Boolean
        get() = this == HIGH || this == CRITICAL
}

/** Every state a task can be in. Transitions are validated by [com.mobileagent.core.agent.TaskStateMachine]. */
enum class TaskStatus {
    CREATED,
    PLANNING,
    READY,
    RUNNING_HEADLESS,
    RUNNING_INTERACTIVE,
    WAITING_FOR_PERMISSION,
    WAITING_FOR_CONFIRMATION,
    WAITING_FOR_UI,
    WAITING_FOR_NETWORK,
    PAUSED,
    RECOVERING,
    COMPLETED,
    FAILED,
    CANCELLED,
    ;

    val isTerminal: Boolean
        get() = this == COMPLETED || this == FAILED || this == CANCELLED

    val isRunning: Boolean
        get() = this == RUNNING_HEADLESS || this == RUNNING_INTERACTIVE || this == PLANNING || this == RECOVERING
}

@Serializable
enum class StepStatus { PENDING, RUNNING, DONE, FAILED, BLOCKED, SKIPPED }

@Serializable
data class PlanStep(
    val id: String,
    val title: String,
    val detail: String = "",
    val toolHint: String? = null,
    val status: StepStatus = StepStatus.PENDING,
    val dependsOn: List<String> = emptyList(),
    val result: String? = null,
)

@Serializable
data class TaskPlan(
    val goal: String,
    val steps: List<PlanStep> = emptyList(),
    val version: Int = 1,
    val createdAt: Long = 0L,
    val notes: String = "",
) {
    val completedSteps: List<PlanStep> get() = steps.filter { it.status == StepStatus.DONE }
    val pendingSteps: List<PlanStep> get() = steps.filter { it.status == StepStatus.PENDING || it.status == StepStatus.BLOCKED }

    fun withStepStatus(stepId: String, status: StepStatus, result: String? = null): TaskPlan =
        copy(steps = steps.map { if (it.id == stepId) it.copy(status = status, result = result ?: it.result) else it })

    fun withStepRunning(stepId: String): TaskPlan =
        copy(steps = steps.map { if (it.id == stepId) it.copy(status = StepStatus.RUNNING) else it })
}

/** The coherent view of a task used by the UI and the harness. */
data class TaskRecord(
    val id: String,
    val title: String,
    val goal: String,
    val status: TaskStatus,
    val createdAt: Long,
    val updatedAt: Long,
    val plan: TaskPlan?,
    val contextSummary: String?,
    val lastError: String?,
    val toolCallCount: Int,
    val modelLabel: String?,
    val resultText: String?,
    val cancelRequested: Boolean,
    val iteration: Int = 0,
    val mode: ChatMode = ChatMode.AGENT,
)

data class ChatMessage(
    val id: String,
    val taskId: String,
    val role: String,
    val content: String,
    val createdAt: Long,
    val toolCallId: String? = null,
    val toolName: String? = null,
) {
    companion object {
        const val ROLE_USER = "user"
        const val ROLE_ASSISTANT = "assistant"
        const val ROLE_TOOL = "tool"
        const val ROLE_SYSTEM = "system"
    }
}

/** Structured, queryable event log entry. One event type per state change or tool action. */
enum class AgentEventType {
    TASK_CREATED,
    TASK_STATUS_CHANGED,
    PLAN_CREATED,
    PLAN_UPDATED,
    MODEL_REQUEST,
    MODEL_RESPONSE,
    TOOL_REQUESTED,
    TOOL_AUTHORIZED,
    TOOL_DENIED,
    TOOL_STARTED,
    TOOL_COMPLETED,
    TOOL_FAILED,
    APPROVAL_REQUESTED,
    APPROVAL_GRANTED,
    APPROVAL_REJECTED,
    CHECKPOINT_SAVED,
    TASK_PAUSED,
    TASK_CANCELLED,
    TASK_RECOVERED,
    TASK_COMPLETED,
    TASK_FAILED,
    UI_ACTION,
    RECOVERY_DECISION,
    DIAGNOSTIC,
}

data class EventRecord(
    val id: String,
    val taskId: String?,
    val executionId: String?,
    val type: AgentEventType,
    val message: String,
    val payloadJson: String?,
    val createdAt: Long,
)

/** Snapshot that makes a long task restartable without replaying side effects. */
@Serializable
data class CheckpointPayload(
    val taskId: String,
    val goal: String,
    val plan: TaskPlan?,
    val completedSteps: List<String>,
    val pendingSteps: List<String>,
    val toolResults: List<String>,
    val artifactPaths: List<String>,
    val permissionState: Map<String, Boolean>,
    val lastError: String?,
    val recoveryStrategy: String,
    val contextSummary: String,
    val iteration: Int,
)

data class ArtifactRecord(
    val id: String,
    val taskId: String?,
    val name: String,
    val path: String,
    val mime: String,
    val sizeBytes: Long,
    val createdAt: Long,
    val description: String,
)

data class ScheduleRecord(
    val id: String,
    val name: String,
    val goal: String,
    val intervalMinutes: Int,
    val enabled: Boolean,
    val lastRunAt: Long?,
    val nextRunAt: Long?,
)
