package com.mobileagent.core.agent

import com.mobileagent.core.model.TaskPlan
import com.mobileagent.core.model.TaskStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** In-memory streaming state for running tasks. Persisted history stays in the database. */
data class LiveRun(
    val taskId: String,
    val status: TaskStatus,
    val draft: String = "",
    val currentTool: String? = null,
    val iteration: Int = 0,
    val plan: TaskPlan? = null,
    val lastError: String? = null,
    val startedAt: Long = System.currentTimeMillis(),
)

class AgentRunRegistry {

    private val _runs = MutableStateFlow<Map<String, LiveRun>>(emptyMap())
    val runs: StateFlow<Map<String, LiveRun>> = _runs.asStateFlow()

    fun start(taskId: String, plan: TaskPlan?) {
        _runs.update {
            it + (taskId to LiveRun(taskId = taskId, status = TaskStatus.RUNNING_HEADLESS, plan = plan))
        }
    }

    fun updateStatus(taskId: String, status: TaskStatus, error: String? = null) {
        _runs.update { current ->
            val existing = current[taskId] ?: LiveRun(taskId, status)
            current + (taskId to existing.copy(status = status, lastError = error ?: existing.lastError))
        }
    }

    fun beginIteration(taskId: String, iteration: Int) {
        _runs.update { current ->
            val existing = current[taskId] ?: LiveRun(taskId, TaskStatus.RUNNING_HEADLESS)
            current + (taskId to existing.copy(iteration = iteration, draft = "", currentTool = null))
        }
    }

    fun appendDraft(taskId: String, delta: String) {
        _runs.update { current ->
            val existing = current[taskId] ?: LiveRun(taskId, TaskStatus.RUNNING_HEADLESS)
            current + (taskId to existing.copy(draft = existing.draft + delta))
        }
    }

    fun setDraft(taskId: String, text: String) {
        _runs.update { current ->
            val existing = current[taskId] ?: LiveRun(taskId, TaskStatus.RUNNING_HEADLESS)
            current + (taskId to existing.copy(draft = text))
        }
    }

    fun setCurrentTool(taskId: String, toolName: String?) {
        _runs.update { current ->
            val existing = current[taskId] ?: LiveRun(taskId, TaskStatus.RUNNING_HEADLESS)
            current + (taskId to existing.copy(currentTool = toolName))
        }
    }

    fun setPlan(taskId: String, plan: TaskPlan?) {
        _runs.update { current ->
            val existing = current[taskId] ?: LiveRun(taskId, TaskStatus.RUNNING_HEADLESS)
            current + (taskId to existing.copy(plan = plan))
        }
    }

    fun finish(taskId: String, status: TaskStatus, error: String? = null) {
        updateStatus(taskId, status, error)
        _runs.update { current ->
            val existing = current[taskId] ?: return@update current
            current + (taskId to existing.copy(draft = "", currentTool = null, status = status, lastError = error))
        }
    }

    fun get(taskId: String): LiveRun? = _runs.value[taskId]

    fun isRunning(taskId: String): Boolean = _runs.value[taskId]?.status?.isRunning == true
}
