package com.mobileagent.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
enum class McpTransport { STREAMABLE_HTTP, STDIO }

enum class McpConnectionState { DISCONNECTED, CONNECTING, CONNECTED, ERROR, RUNTIME_UNAVAILABLE }

@Serializable
data class McpServerConfig(
    val id: String,
    val name: String,
    val transport: McpTransport = McpTransport.STREAMABLE_HTTP,
    val endpoint: String = "",
    val authHeaderName: String? = null,
    val authSecretId: String? = null,
    val enabled: Boolean = true,
    val allowedTools: Set<String> = emptySet(),
    val protocolVersion: String? = null,
    val lastError: String? = null,
    val lastConnectedAt: Long? = null,
    val tools: List<McpToolDescriptor> = emptyList(),
)

@Serializable
data class McpToolDescriptor(
    val name: String,
    val description: String,
    val inputSchema: JsonObject?,
    val enabled: Boolean = true,
)

data class McpToolCallOutcome(
    val ok: Boolean,
    val content: String,
    val isError: Boolean,
    val structured: JsonObject? = null,
)

/** Skill manifest (skill.yaml) after parsing and validation. */
@Serializable
data class SkillManifest(
    val id: String,
    val name: String,
    val version: String,
    val description: String,
    val instructions: String = "",
    val entryPoint: String? = null,
    val runtime: String = "prompt",
    val requiredTools: List<String> = emptyList(),
    val permissions: SkillPermissions = SkillPermissions(),
    val dependencies: List<String> = emptyList(),
    val author: String? = null,
    val sourceUri: String? = null,
)

@Serializable
data class SkillPermissions(
    val network: Boolean = false,
    val files: Boolean = false,
    val shell: Boolean = false,
    val shizuku: Boolean = false,
    val accessibility: Boolean = false,
)

data class SkillSummary(
    val id: String,
    val name: String,
    val version: String,
    val description: String,
    val enabled: Boolean,
    val source: String,
    val manifest: SkillManifest,
    val installedAt: Long,
)

data class SkillValidationResult(
    val valid: Boolean,
    val errors: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
)

data class MemoryRecord(
    val id: String,
    val scope: String,
    val key: String,
    val value: String,
    val enabled: Boolean,
    val sensitive: Boolean,
    val updatedAt: Long,
)

/** Snapshot of one accessibility node. Node ids are only meaningful within one snapshot. */
data class UiNode(
    val nodeId: String,
    val text: String,
    val contentDescription: String,
    val className: String,
    val resourceId: String,
    val bounds: String,
    val clickable: Boolean,
    val longClickable: Boolean,
    val scrollable: Boolean,
    val editable: Boolean,
    val enabled: Boolean,
    val visible: Boolean,
    val checkable: Boolean,
    val checked: Boolean,
    val depth: Int,
    val children: List<UiNode> = emptyList(),
) {
    fun flatten(): List<UiNode> = buildList {
        add(this@UiNode)
        children.forEach { addAll(it.flatten()) }
    }

    fun label(): String = when {
        text.isNotBlank() -> text
        contentDescription.isNotBlank() -> contentDescription
        resourceId.isNotBlank() -> resourceId
        else -> className
    }
}

data class UiSnapshot(
    val packageName: String,
    val activityName: String?,
    val capturedAt: Long,
    val nodes: List<UiNode>,
    val screenWidth: Int,
    val screenHeight: Int,
    val truncated: Boolean,
) {
    fun allNodes(): List<UiNode> = nodes.flatMap { it.flatten() }

    fun findText(query: String): List<UiNode> {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return emptyList()
        return allNodes().filter {
            it.text.lowercase().contains(needle) || it.contentDescription.lowercase().contains(needle)
        }
    }

    fun findById(resourceId: String): List<UiNode> = allNodes().filter { it.resourceId == resourceId }

    fun findByNodeId(nodeId: String): UiNode? = allNodes().firstOrNull { it.nodeId == nodeId }
}

data class ScreenFrame(
    val pngBytes: ByteArray,
    val width: Int,
    val height: Int,
    val capturedAt: Long,
) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is ScreenFrame && pngBytes.contentEquals(other.pngBytes) && width == other.width && height == other.height)

    override fun hashCode(): Int = 31 * pngBytes.contentHashCode() + width
}

data class ShellResult(
    val executionId: String,
    val executor: String,
    val command: String,
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val startedAt: Long,
    val endedAt: Long,
    val cancelled: Boolean,
    val timedOut: Boolean,
    val error: String? = null,
    val truncated: Boolean = false,
) {
    val durationMs: Long get() = endedAt - startedAt
    val success: Boolean get() = exitCode == 0 && error == null && !cancelled && !timedOut
}
