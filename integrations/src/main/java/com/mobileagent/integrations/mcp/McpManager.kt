package com.mobileagent.integrations.mcp

import com.mobileagent.core.data.IntegrationRepository
import com.mobileagent.core.model.AgentTool
import com.mobileagent.core.model.JsonCodec
import com.mobileagent.core.model.McpServerConfig
import com.mobileagent.core.model.McpToolCallOutcome
import com.mobileagent.core.model.McpToolDescriptor
import com.mobileagent.core.model.McpTransport
import com.mobileagent.core.model.RiskLevel
import com.mobileagent.core.model.ToolCategory
import com.mobileagent.core.model.ToolContext
import com.mobileagent.core.model.ToolResult
import com.mobileagent.core.model.ToolSpec
import com.mobileagent.core.security.SecretVault
import com.mobileagent.core.tools.ToolRegistry
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.ConcurrentHashMap

data class McpConnectResult(
    val server: McpServerConfig,
    val tools: List<McpToolDescriptor>,
    val protocolVersion: String? = null,
    val error: String? = null,
) {
    val ok: Boolean get() = error == null
}

/**
 * Manages MCP servers: handshake, tool discovery, persistence, and registration of every remote
 * tool in the shared tool registry so the same policy engine applies.
 */
class McpManager(
    private val repository: IntegrationRepository,
    private val secrets: SecretVault,
    private val registry: ToolRegistry,
) {

    private val clients = ConcurrentHashMap<String, StreamableHttpMcpClient>()

    suspend fun connect(serverId: String): McpConnectResult {
        val stored = repository.getMcpServer(serverId)
            ?: return McpConnectResult(McpServerConfig(serverId, serverId), emptyList(), error = "MCP server not found")
        if (!stored.enabled) {
            return McpConnectResult(stored, emptyList(), error = "This MCP server is disabled.")
        }
        if (stored.transport == McpTransport.STDIO) {
            val message = "stdio MCP is not available: Android has no guaranteed Python/Node runtime. " +
                "Use a Streamable HTTP endpoint or install a runtime first."
            repository.upsertMcpServer(stored.copy(lastError = message, tools = emptyList()))
            registry.unregisterSource(sourceTag(serverId))
            return McpConnectResult(stored.copy(lastError = message), emptyList(), error = message)
        }
        if (stored.endpoint.isBlank()) {
            val message = "Endpoint URL is empty."
            repository.upsertMcpServer(stored.copy(lastError = message))
            return McpConnectResult(stored.copy(lastError = message), emptyList(), error = message)
        }

        val token = stored.authSecretId?.let { secrets.get(it) }
        val client = StreamableHttpMcpClient(
            endpoint = stored.endpoint,
            authHeaderName = stored.authHeaderName,
            authToken = token,
        )

        return try {
            val init = client.initialize()
            val rawTools = client.listTools()
            val descriptors = rawTools.mapNotNull { element -> client.parseToolDescriptor(element) }
            val allowed = if (stored.allowedTools.isEmpty()) descriptors else descriptors.filter { stored.allowedTools.contains(it.name) }
            clients[serverId] = client
            val updated = stored.copy(
                tools = descriptors,
                protocolVersion = init.protocolVersion,
                lastError = null,
                lastConnectedAt = System.currentTimeMillis(),
            )
            repository.upsertMcpServer(updated)
            registry.unregisterSource(sourceTag(serverId))
            registerTools(updated, allowed, client)
            McpConnectResult(updated, descriptors, init.protocolVersion)
        } catch (t: Throwable) {
            clients.remove(serverId)
            registry.unregisterSource(sourceTag(serverId))
            val message = t.message ?: t::class.java.simpleName
            repository.upsertMcpServer(stored.copy(lastError = message))
            McpConnectResult(stored.copy(lastError = message), emptyList(), error = message)
        }
    }

    suspend fun connectEnabled(): List<McpConnectResult> =
        repository.mcpServers().filter { it.enabled }.map { connect(it.id) }

    suspend fun save(config: McpServerConfig) {
        repository.upsertMcpServer(config)
        if (!config.enabled) {
            disconnect(config.id)
        }
    }

    suspend fun delete(serverId: String) {
        disconnect(serverId)
        repository.deleteMcpServer(serverId)
    }

    fun disconnect(serverId: String) {
        clients.remove(serverId)?.close()
        registry.unregisterSource(sourceTag(serverId))
    }

    fun disconnectAll() {
        clients.keys.toList().forEach { disconnect(it) }
    }

    /** Diagnostic call that does not mutate stored state. */
    suspend fun test(config: McpServerConfig): McpConnectResult {
        if (config.transport == McpTransport.STDIO) {
            return McpConnectResult(config, emptyList(), error = "stdio transport is not supported on this device.")
        }
        val token = config.authSecretId?.let { secrets.get(it) }
        val client = StreamableHttpMcpClient(config.endpoint, config.authHeaderName, token)
        return try {
            val init = client.initialize()
            val tools = client.listTools().mapNotNull { client.parseToolDescriptor(it) }
            client.close()
            McpConnectResult(config, tools, init.protocolVersion)
        } catch (t: Throwable) {
            McpConnectResult(config, emptyList(), error = t.message ?: t::class.java.simpleName)
        }
    }

    private fun registerTools(server: McpServerConfig, tools: List<McpToolDescriptor>, client: StreamableHttpMcpClient) {
        tools.forEach { descriptor ->
            val localName = toolName(server, descriptor.name)
            registry.register(
                McpToolAdapter(
                    localName = localName,
                    serverName = server.name,
                    descriptor = descriptor,
                    call = { arguments -> client.callTool(descriptor.name, arguments) },
                ),
            )
        }
    }

    private fun toolName(server: McpServerConfig, remote: String): String {
        val serverSlug = server.name.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').ifBlank { "server" }
        val toolSlug = remote.lowercase().replace(Regex("[^a-z0-9_\\-]+"), "_").trim('_').ifBlank { "tool" }
        return ("mcp_${serverSlug}_$toolSlug").take(64)
    }

    private fun sourceTag(serverId: String) = "mcp:$serverId"
}

/** Bridges one remote MCP tool into the local tool registry. */
class McpToolAdapter(
    private val localName: String,
    private val serverName: String,
    private val descriptor: McpToolDescriptor,
    private val call: suspend (JsonObject) -> McpToolCallOutcome,
) : AgentTool {

    override val spec: ToolSpec = ToolSpec(
        name = localName,
        title = "MCP · ${descriptor.name}",
        description = buildString {
            append(descriptor.description.ifBlank { "Remote MCP tool ${descriptor.name}" })
            append("\nProvided by the MCP server \"").append(serverName).append("\". Arguments must match its JSON schema.")
        },
        parametersSchema = descriptor.inputSchema ?: buildJsonObject {
            put("type", JsonPrimitive("object"))
            put("properties", buildJsonObject { })
        },
        risk = RiskLevel.MEDIUM,
        category = ToolCategory.MCP,
        source = "mcp",
    )

    override suspend fun execute(context: ToolContext, arguments: JsonObject): ToolResult {
        val startedAt = System.currentTimeMillis()
        return try {
            val outcome = call(arguments)
            val output = buildJsonObject {
                put("server", JsonPrimitive(serverName))
                put("tool", JsonPrimitive(descriptor.name))
                put("content", JsonPrimitive(outcome.content))
                put("isError", JsonPrimitive(outcome.isError))
                outcome.structured?.let { put("structured", it) }
            }
            ToolResult(
                callId = context.callId,
                toolName = localName,
                ok = !outcome.isError,
                output = output,
                summary = outcome.content.take(400).ifBlank { "MCP tool ${descriptor.name} returned no text." },
                error = if (outcome.isError) outcome.content.take(400) else null,
                durationMs = System.currentTimeMillis() - startedAt,
            )
        } catch (t: Throwable) {
            ToolResult(
                callId = context.callId,
                toolName = localName,
                ok = false,
                output = buildJsonObject {
                    put("server", JsonPrimitive(serverName))
                    put("tool", JsonPrimitive(descriptor.name))
                },
                summary = "MCP call failed: ${t.message ?: t::class.java.simpleName}",
                error = t.message ?: t::class.java.simpleName,
                durationMs = System.currentTimeMillis() - startedAt,
            )
        }
    }
}
