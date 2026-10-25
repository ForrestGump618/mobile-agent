package com.mobileagent.app.ui.vm

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mobileagent.app.MobileAgentApplication
import com.mobileagent.core.model.McpServerConfig
import com.mobileagent.core.model.SkillSummary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

class McpViewModel(application: Application) : AndroidViewModel(application) {

    private val graph = (application as MobileAgentApplication).graph

    val servers: StateFlow<List<McpServerConfig>> = graph.integrations.observeMcpServers()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val busyServerId = MutableStateFlow<String?>(null)
    val message = MutableStateFlow<String?>(null)

    fun save(
        id: String?,
        name: String,
        endpoint: String,
        authHeader: String,
        authToken: String,
        enabled: Boolean,
        allowedTools: Set<String>,
    ) {
        viewModelScope.launch {
            try {
                val existing = id?.let { graph.integrations.getMcpServer(it) }
                val serverId = existing?.id ?: UUID.randomUUID().toString()
                val secretId = if (authToken.isNotBlank()) {
                    val secret = existing?.authSecretId ?: "mcp-$serverId"
                    graph.secrets.put(secret, authToken)
                    secret
                } else {
                    existing?.authSecretId
                }
                val config = McpServerConfig(
                    id = serverId,
                    name = name.ifBlank { "MCP server" },
                    endpoint = endpoint.trim(),
                    authHeaderName = authHeader.takeIf { it.isNotBlank() },
                    authSecretId = secretId,
                    enabled = enabled,
                    allowedTools = allowedTools,
                    tools = existing?.tools.orEmpty(),
                    protocolVersion = existing?.protocolVersion,
                    lastConnectedAt = existing?.lastConnectedAt,
                )
                graph.mcp.save(config)
                message.value = "Saved ${config.name}. Press Connect to handshake and discover tools."
            } catch (t: Throwable) {
                message.value = "Could not save: ${t.message}"
            }
        }
    }

    fun connect(serverId: String) {
        viewModelScope.launch {
            busyServerId.value = serverId
            val result = graph.mcp.connect(serverId)
            busyServerId.value = null
            message.value = if (result.ok) {
                "Connected to ${result.server.name}: ${result.tools.size} tool(s), protocol ${result.protocolVersion ?: "unknown"}."
            } else {
                "Connection failed: ${result.error}"
            }
        }
    }

    fun test(id: String?, name: String, endpoint: String, authHeader: String, authToken: String) {
        viewModelScope.launch {
            val secretId = id?.let { graph.integrations.getMcpServer(it)?.authSecretId }
            val config = McpServerConfig(
                id = id ?: "test",
                name = name,
                endpoint = endpoint,
                authHeaderName = authHeader.takeIf { it.isNotBlank() },
                authSecretId = secretId,
            )
            val result = graph.mcp.test(config)
            message.value = if (result.ok) {
                "Handshake ok. Tools: ${result.tools.joinToString { it.name }.take(300)}"
            } else {
                "Test failed: ${result.error}"
            }
        }
    }

    fun delete(serverId: String) {
        viewModelScope.launch {
            graph.mcp.delete(serverId)
            message.value = "MCP server removed."
        }
    }

    fun clearMessage() {
        message.value = null
    }
}

class SkillsViewModel(application: Application) : AndroidViewModel(application) {

    private val graph = (application as MobileAgentApplication).graph

    val skills: StateFlow<List<SkillSummary>> = graph.integrations.observeSkills()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val message = MutableStateFlow<String?>(null)
    val busy = MutableStateFlow(false)

    fun install(uri: Uri, displayName: String) {
        viewModelScope.launch {
            busy.value = true
            val result = runCatching { graph.skills.installFromZip(uri, displayName) }.getOrThrow()
            busy.value = false
            message.value = result.fold(
                onSuccess = { "Installed ${it.name} v${it.version}." },
                onFailure = { "Install failed: ${it.message}" },
            )
        }
    }

    fun setEnabled(id: String, enabled: Boolean) {
        viewModelScope.launch {
            graph.skills.setEnabled(id, enabled)
            message.value = if (enabled) "Skill enabled." else "Skill disabled."
        }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            graph.skills.remove(id)
            message.value = "Skill removed."
        }
    }

    fun clearMessage() {
        message.value = null
    }
}
