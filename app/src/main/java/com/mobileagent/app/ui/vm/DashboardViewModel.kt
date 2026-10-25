package com.mobileagent.app.ui.vm

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mobileagent.app.MobileAgentApplication
import com.mobileagent.core.model.TaskRecord
import com.mobileagent.core.model.TaskStatus
import com.mobileagent.execution.accessibility.AccessibilityBridge
import com.mobileagent.execution.shell.ShizukuStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class DashboardStatus(
    val providerCount: Int = 0,
    val modelLabel: String? = null,
    val shizuku: ShizukuStatus? = null,
    val accessibilityConnected: Boolean = false,
    val mcpConnected: Int = 0,
    val mcpTotal: Int = 0,
    val skillsEnabled: Int = 0,
    val runningTaskId: String? = null,
    val runningTaskTitle: String? = null,
    val failedTasks: Int = 0,
)

class DashboardViewModel(application: Application) : AndroidViewModel(application) {

    private val graph = (application as MobileAgentApplication).graph

    val tasks: StateFlow<List<TaskRecord>> = graph.tasks.observeAgentTasks(30)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val status = MutableStateFlow(DashboardStatus())
    val busy = MutableStateFlow<Boolean>(false)

    init {
        refresh()
        viewModelScope.launch {
            graph.runs.runs.collect { refresh() }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            busy.value = true
            try {
                val settings = graph.settings.current()
                val providers = graph.providers.list()
                val label = runCatching { graph.router.activeLabel() }.getOrNull()
                val mcpServers = graph.integrations.mcpServers()
                val skills = graph.integrations.skills()
                val activeRun = graph.runs.runs.value.entries.firstOrNull { it.value.status.isRunning }
                val activeTask = activeRun?.key?.let { graph.tasks.getTask(it) }
                val failed = graph.tasks.tasksInStatus(TaskStatus.FAILED)
                status.value = DashboardStatus(
                    providerCount = providers.count { it.enabled },
                    modelLabel = label,
                    shizuku = runCatching { graph.shizuku.status() }.getOrNull(),
                    accessibilityConnected = AccessibilityBridge.isConnected() && settings.allowAccessibility,
                    mcpConnected = mcpServers.count { it.lastError == null && it.lastConnectedAt != null },
                    mcpTotal = mcpServers.size,
                    skillsEnabled = skills.count { it.enabled },
                    runningTaskId = activeRun?.key,
                    runningTaskTitle = activeTask?.title,
                    failedTasks = failed.size,
                )
            } finally {
                busy.value = false
            }
        }
    }
}
