package com.mobileagent.app.ui.vm

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mobileagent.app.MobileAgentApplication
import com.mobileagent.core.agent.DiagnosticItem
import com.mobileagent.core.model.ArtifactRecord
import com.mobileagent.core.model.EventRecord
import com.mobileagent.core.model.MemoryRecord
import com.mobileagent.core.model.ProviderConfig
import com.mobileagent.core.model.ScheduleRecord
import com.mobileagent.core.security.Redactor
import com.mobileagent.core.settings.AgentSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val graph = (application as MobileAgentApplication).graph

    val settings: StateFlow<AgentSettings> = graph.settings.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AgentSettings())

    val providers: StateFlow<List<ProviderConfig>> = graph.providers.observeProviders()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val memories: StateFlow<List<MemoryRecord>> = graph.integrations.observeMemories()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val schedules: StateFlow<List<ScheduleRecord>> = graph.integrations.observeSchedules()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val events: StateFlow<List<EventRecord>> = graph.tasks.observeEvents(300)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val artifacts: StateFlow<List<ArtifactRecord>> = graph.tasks.observeArtifacts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val diagnostics = MutableStateFlow<List<DiagnosticItem>>(emptyList())
    val message = MutableStateFlow<String?>(null)
    val probingProviderId = MutableStateFlow<String?>(null)

    fun setLanguage(tag: String) {
        viewModelScope.launch { graph.settings.setLanguage(tag) }
    }

    fun update(block: (AgentSettings) -> AgentSettings) {
        viewModelScope.launch { graph.settings.update(block) }
    }

    fun saveProvider(
        id: String?,
        name: String,
        baseUrl: String,
        modelId: String,
        apiKey: String,
        vision: Boolean,
        toolCalling: Boolean,
        streaming: Boolean,
        reasoning: Boolean,
        temperature: Double,
        maxOutputTokens: Int,
        contextWindow: Int,
        timeoutMs: Long,
    ) {
        viewModelScope.launch {
            try {
                val existing = id?.let { graph.providers.get(it) }
                val providerId = existing?.id ?: UUID.randomUUID().toString()
                val secretId = if (apiKey.isNotBlank()) {
                    val secret = existing?.apiKeyId ?: "provider-$providerId"
                    graph.secrets.put(secret, apiKey.trim())
                    secret
                } else {
                    existing?.apiKeyId
                }
                val config = ProviderConfig(
                    id = providerId,
                    name = name.ifBlank { "Provider" },
                    baseUrl = baseUrl.trim().trimEnd('/'),
                    modelId = modelId.trim(),
                    apiKeyId = secretId,
                    timeoutMs = timeoutMs.coerceIn(5_000, 300_000),
                    streaming = streaming,
                    toolCalling = toolCalling,
                    vision = vision,
                    reasoning = reasoning,
                    contextWindow = contextWindow.coerceIn(2_048, 1_000_000),
                    maxOutputTokens = maxOutputTokens.coerceIn(128, 32_000),
                    temperature = temperature.coerceIn(0.0, 2.0),
                    enabled = true,
                )
                graph.providers.upsert(config)
                val currentSettings = graph.settings.current()
                if (currentSettings.activeProviderId == null) {
                    graph.settings.setActiveProvider(providerId)
                }
                message.value = "Saved ${config.name}."
            } catch (t: Throwable) {
                message.value = "Could not save the provider: ${t.message}"
            }
        }
    }

    fun deleteProvider(id: String) {
        viewModelScope.launch {
            graph.providers.get(id)?.apiKeyId?.let { graph.secrets.delete(it) }
            graph.providers.delete(id)
            if (graph.settings.current().activeProviderId == id) {
                graph.settings.setActiveProvider(graph.providers.list().firstOrNull()?.id)
            }
            message.value = "Provider removed."
        }
    }

    fun setActiveProvider(id: String?) {
        viewModelScope.launch { graph.settings.setActiveProvider(id) }
    }

    fun probe(id: String) {
        viewModelScope.launch {
            val config = graph.providers.get(id) ?: return@launch
            probingProviderId.value = id
            val report = graph.llm.probe(config, config.apiKeyId?.let { graph.secrets.get(it) })
            graph.providers.saveProbe(report)
            probingProviderId.value = null
            message.value = buildString {
                append("Probe finished. Verified: ")
                append(report.verified.joinToString { it.name }.ifBlank { "none" })
                append(". Unverified: ")
                append(report.unverified.joinToString { it.name }.ifBlank { "none" })
                if (report.notes.isNotBlank()) append(". ").append(report.notes)
            }
        }
    }

    fun refreshDiagnostics() {
        viewModelScope.launch {
            diagnostics.value = runCatching { graph.diagnostics.snapshot() }.getOrDefault(emptyList())
        }
    }

    fun setMemoryEnabled(id: String, enabled: Boolean) {
        viewModelScope.launch { graph.integrations.setMemoryEnabled(id, enabled) }
    }

    fun deleteMemory(id: String) {
        viewModelScope.launch { graph.integrations.deleteMemory(id) }
    }

    fun deleteSchedule(id: String) {
        viewModelScope.launch {
            graph.scheduler.cancel(id)
            graph.integrations.deleteSchedule(id)
            message.value = "Schedule removed."
        }
    }

    fun toggleSchedule(record: ScheduleRecord, enabled: Boolean) {
        viewModelScope.launch {
            graph.integrations.upsertSchedule(record.copy(enabled = enabled))
            if (enabled) graph.scheduler.schedule(record.copy(enabled = true)) else graph.scheduler.cancel(record.id)
        }
    }

    fun toggleSkill(id: String, enabled: Boolean) {
        viewModelScope.launch { graph.skills.setEnabled(id, enabled) }
    }

    suspend fun workspaceFiles(): List<File> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val root = graph.workspace.root
        root.walkTopDown().filter { it.isFile }.sortedByDescending { it.lastModified() }.take(200).toList()
    }

    fun exportLogs() {
        viewModelScope.launch {
            try {
                val builder = StringBuilder("# Mobile Agent diagnostics export\n\n")
                builder.append("Generated: ").append(java.util.Date()).append("\n\n")
                builder.append("## Health\n\n")
                runCatching { graph.diagnostics.snapshot() }.getOrDefault(emptyList()).forEach { item ->
                    builder.append("- ").append(item.label).append(" [").append(item.status).append("] ").append(item.detail).append('\n')
                }
                builder.append("\n## Recent events\n\n")
                graph.tasks.recentEvents(200).forEach { event ->
                    builder.append("- ").append(java.util.Date(event.createdAt)).append(" [").append(event.type).append("] ")
                        .append(event.message).append('\n')
                }
                val file = File(graph.workspace.subdirectory("exports"), "diagnostics-${System.currentTimeMillis()}.md")
                file.writeText(Redactor.redact(builder.toString()), Charsets.UTF_8)
                message.value = "Diagnostics exported to ${file.absolutePath}"
            } catch (t: Throwable) {
                message.value = "Export failed: ${t.message}"
            }
        }
    }

    fun clearMessage() {
        message.value = null
    }
}
