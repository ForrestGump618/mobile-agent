package com.mobileagent.core.agent

import com.mobileagent.core.data.TaskRepository
import com.mobileagent.core.llm.ModelRouter
import com.mobileagent.core.llm.describe
import com.mobileagent.core.model.AgentEventType
import com.mobileagent.core.model.ChatMessage
import com.mobileagent.core.model.TaskRecord
import com.mobileagent.core.model.TaskStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Owns the coroutine jobs for agent runs. The harness never depends on an Activity or a Composable:
 * the application process owns this runtime, and a foreground service keeps the process alive while
 * a task is running.
 */
class AgentRuntime(
    private val tasks: TaskRepository,
    private val orchestrator: AgentOrchestrator,
    private val runs: AgentRunRegistry,
    private val router: ModelRouter,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val jobs = ConcurrentHashMap<String, Job>()

    suspend fun submit(goal: String): TaskRecord {
        val label = runCatching { router.activeLabel() }.getOrNull()
        val task = tasks.createTask(goal, modelLabel = label)
        tasks.appendMessage(task.id, ChatMessage.ROLE_USER, goal)
        tasks.addEvent(task.id, null, AgentEventType.TASK_CREATED, "Task created.")
        start(task.id)
        return tasks.getTask(task.id) ?: task
    }

    /** Starts a task exactly once; a second call while it is active is ignored. */
    fun start(taskId: String, followUpMessage: String? = null) {
        synchronized(jobs) {
            if (jobs[taskId]?.isActive == true) return
            jobs[taskId] = scope.launch {
                try {
                    orchestrator.run(taskId, followUpMessage)
                } finally {
                    jobs.remove(taskId)
                }
            }
        }
    }

    /** Adds a user turn to an existing conversation and starts the next agent turn. */
    suspend fun continueTask(taskId: String, text: String): Boolean {
        val task = tasks.getTask(taskId) ?: return false
        if (isRunning(taskId)) return false
        tasks.appendMessage(taskId, ChatMessage.ROLE_USER, text)
        tasks.setCancelRequested(taskId, false)
        if (task.status.isTerminal || task.status == TaskStatus.PAUSED) {
            if (!TaskStateMachine.canTransition(task.status, TaskStatus.RECOVERING)) return false
            tasks.updateStatus(taskId, TaskStatus.RECOVERING)
        } else if (task.status != TaskStatus.RECOVERING) {
            tasks.updateStatus(taskId, TaskStatus.RECOVERING)
        }
        tasks.addEvent(taskId, null, AgentEventType.TASK_RECOVERED, "Follow-up message received; continuing the conversation.")
        start(taskId, followUpMessage = text)
        return true
    }

    suspend fun resume(taskId: String): Boolean {
        val task = tasks.getTask(taskId) ?: return false
        if (task.status.isTerminal) return false
        if (isRunning(taskId)) return false
        tasks.setCancelRequested(taskId, false)
        if (task.status != TaskStatus.RECOVERING) {
            if (!TaskStateMachine.canTransition(task.status, TaskStatus.RECOVERING)) return false
            tasks.updateStatus(taskId, TaskStatus.RECOVERING)
            tasks.addEvent(taskId, null, AgentEventType.TASK_RECOVERED, "User resumed the task from ${task.status}.")
        }
        start(taskId)
        return true
    }

    suspend fun cancel(taskId: String) {
        tasks.requestCancel(taskId)
        jobs.remove(taskId)?.cancel()
        runs.finish(taskId, TaskStatus.CANCELLED, "Cancelled by user.")
        tasks.addEvent(taskId, null, AgentEventType.TASK_CANCELLED, "Cancelled by user")
    }

    fun isRunning(taskId: String): Boolean = jobs[taskId]?.isActive == true

    fun runningTaskIds(): Set<String> = jobs.filterValues { it.isActive }.keys

    /**
     * Marks tasks that were mid-flight when the process died as PAUSED. They are never replayed
     * automatically; the user resumes them explicitly and the harness verifies side effects first.
     */
    suspend fun recoverInterrupted(): List<TaskRecord> {
        val candidates = tasks.tasksInStatus(
            TaskStatus.PLANNING,
            TaskStatus.RUNNING_HEADLESS,
            TaskStatus.RUNNING_INTERACTIVE,
            TaskStatus.WAITING_FOR_PERMISSION,
            TaskStatus.WAITING_FOR_CONFIRMATION,
            TaskStatus.WAITING_FOR_UI,
            TaskStatus.WAITING_FOR_NETWORK,
        ).filter { !isRunning(it.id) }

        candidates.forEach { task ->
            tasks.updateStatus(task.id, TaskStatus.PAUSED, "Interrupted by process restart. Resume to continue.")
            tasks.addEvent(
                task.id,
                null,
                AgentEventType.TASK_RECOVERED,
                "Marked PAUSED after process restart; no automatic replay.",
            )
        }
        return candidates
    }

    fun shutdown() {
        jobs.values.forEach { it.cancel() }
        jobs.clear()
    }

    /** Human readable reason for the last model failure, used by the diagnostics screen. */
    suspend fun describeLastFailure(taskId: String): String? {
        val task = tasks.getTask(taskId) ?: return null
        return task.lastError
    }

    fun describeError(e: Throwable): String =
        if (e is com.mobileagent.core.model.LlmException) e.describe() else (e.message ?: e::class.java.simpleName)
}
