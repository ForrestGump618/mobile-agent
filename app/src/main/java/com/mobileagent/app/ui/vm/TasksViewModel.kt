package com.mobileagent.app.ui.vm

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mobileagent.app.MobileAgentApplication
import com.mobileagent.core.model.ArtifactRecord
import com.mobileagent.core.model.EventRecord
import com.mobileagent.core.model.TaskRecord
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

class TasksViewModel(application: Application) : AndroidViewModel(application) {

    private val graph = (application as MobileAgentApplication).graph
    private val selectedId = MutableStateFlow<String?>(null)

    val tasks: StateFlow<List<TaskRecord>> = graph.tasks.observeAgentTasks(60)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val selected: StateFlow<TaskRecord?> = selectedId
        .flatMapLatest { id -> if (id == null) flowOf(null) else graph.tasks.observeTask(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val toolCalls = selectedId.flatMapLatest { id ->
        if (id == null) flowOf(emptyList()) else graph.tasks.observeToolCalls(id)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val events: StateFlow<List<EventRecord>> = selectedId.flatMapLatest { id ->
        if (id == null) flowOf(emptyList()) else graph.tasks.observeTaskEvents(id)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val checkpoints = selectedId.flatMapLatest { id ->
        if (id == null) flowOf(emptyList()) else graph.tasks.observeCheckpoints(id)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val artifacts: StateFlow<List<ArtifactRecord>> = selectedId.flatMapLatest { id ->
        if (id == null) flowOf(emptyList()) else graph.tasks.observeTaskArtifacts(id)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val message = MutableStateFlow<String?>(null)

    fun select(taskId: String?) {
        selectedId.value = taskId
    }

    fun cancel(taskId: String) {
        viewModelScope.launch { graph.runtime.cancel(taskId) }
    }

    fun resume(taskId: String) {
        viewModelScope.launch {
            val ok = graph.runtime.resume(taskId)
            if (!ok) message.value = "This task cannot be resumed in its current state."
        }
    }

    fun delete(taskId: String) {
        viewModelScope.launch {
            graph.runtime.cancel(taskId)
            graph.tasks.deleteTask(taskId)
            if (selectedId.value == taskId) selectedId.value = null
        }
    }

    /** Writes a Markdown transcript with every tool call, event and artifact for this task. */
    fun export(taskId: String) {
        viewModelScope.launch {
            try {
                val task = graph.tasks.getTask(taskId) ?: return@launch
                val messages = graph.tasks.messages(taskId)
                val calls = graph.tasks.toolCallsFor(taskId)
                val events = graph.tasks.taskEvents(taskId)
                val artifacts = graph.tasks.artifactsFor(taskId)
                val builder = StringBuilder()
                builder.append("# ").append(task.title).append("\n\n")
                builder.append("- Goal: ").append(task.goal).append('\n')
                builder.append("- Status: ").append(task.status).append('\n')
                builder.append("- Created: ").append(java.util.Date(task.createdAt)).append('\n')
                builder.append("- Model: ").append(task.modelLabel ?: "unknown").append("\n\n")
                builder.append("## Transcript\n\n")
                messages.forEach { message ->
                    builder.append("**").append(message.role).append("**")
                    if (message.toolName != null) builder.append(" (").append(message.toolName).append(")")
                    builder.append(":\n\n").append(message.content).append("\n\n")
                }
                builder.append("## Tool calls\n\n")
                calls.forEach { call ->
                    builder.append("- `").append(call.toolName).append("` risk=").append(call.risk)
                        .append(" ok=").append(call.ok).append(" args=").append(call.argumentsJson.take(400)).append('\n')
                }
                builder.append("\n## Audit events\n\n")
                events.forEach { event ->
                    builder.append("- ").append(java.util.Date(event.createdAt)).append(" [").append(event.type).append("] ")
                        .append(event.message).append('\n')
                }
                if (artifacts.isNotEmpty()) {
                    builder.append("\n## Artifacts\n\n")
                    artifacts.forEach { artifact ->
                        builder.append("- ").append(artifact.name).append(" — ").append(artifact.path).append('\n')
                    }
                }
                val file = File(graph.workspace.subdirectory("exports"), "task-${task.id.take(8)}-${System.currentTimeMillis()}.md")
                file.writeText(builder.toString(), Charsets.UTF_8)
                graph.tasks.recordArtifact(taskId, file.name, file.absolutePath, "text/markdown", file.length(), "Task transcript export")
                message.value = "Exported to ${file.absolutePath}"
            } catch (t: Throwable) {
                message.value = "Export failed: ${t.message}"
            }
        }
    }

    fun clearMessage() {
        message.value = null
    }
}
