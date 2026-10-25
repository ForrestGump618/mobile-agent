package com.mobileagent.core.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "tasks")
data class TaskEntity(
    @PrimaryKey val id: String,
    val title: String,
    val goal: String,
    val status: String,
    val createdAt: Long,
    val updatedAt: Long,
    val planJson: String? = null,
    val contextSummary: String? = null,
    val lastError: String? = null,
    val toolCallCount: Int = 0,
    val modelLabel: String? = null,
    val resultText: String? = null,
    val cancelRequested: Boolean = false,
    val iteration: Int = 0,
    /** "chat" for plain conversation, "agent" for tool-using tasks. Nullable for migration. */
    val mode: String? = null,
)

@Entity(tableName = "messages", indices = [Index("taskId")])
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0L,
    val id: String,
    val taskId: String,
    val role: String,
    val content: String,
    val createdAt: Long,
    val toolCallId: String? = null,
    val toolName: String? = null,
)

@Entity(tableName = "tool_calls", indices = [Index("taskId"), Index("executionId")])
data class ToolCallEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0L,
    val id: String,
    val taskId: String,
    val executionId: String,
    val toolName: String,
    val argumentsJson: String,
    val resultJson: String? = null,
    val ok: Boolean? = null,
    val risk: String,
    val approved: Boolean,
    val startedAt: Long,
    val finishedAt: Long? = null,
    val error: String? = null,
)

@Entity(tableName = "checkpoints", indices = [Index("taskId")])
data class CheckpointEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0L,
    val id: String,
    val taskId: String,
    val createdAt: Long,
    val payloadJson: String,
)

@Entity(tableName = "agent_events", indices = [Index("taskId"), Index("type")])
data class EventEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0L,
    val id: String,
    val taskId: String?,
    val executionId: String?,
    val type: String,
    val message: String,
    val payloadJson: String?,
    val createdAt: Long,
)

@Entity(tableName = "providers")
data class ProviderEntity(
    @PrimaryKey val id: String,
    val name: String,
    val baseUrl: String,
    val modelId: String,
    val apiKeyId: String?,
    val timeoutMs: Long,
    val streaming: Boolean,
    val toolCalling: Boolean,
    val vision: Boolean,
    val reasoning: Boolean,
    val structuredOutput: Boolean,
    val contextWindow: Int,
    val maxOutputTokens: Int,
    val temperature: Double,
    val enabled: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
    val lastProbeJson: String? = null,
    val presetId: String? = null,
    val apiPath: String? = null,
    val lastError: String? = null,
    val lastTestedAt: Long? = null,
    val verifiedCapsJson: String? = null,
)

@Entity(tableName = "mcp_servers")
data class McpServerEntity(
    @PrimaryKey val id: String,
    val name: String,
    val transport: String,
    val endpoint: String,
    val authHeaderName: String?,
    val authSecretId: String?,
    val enabled: Boolean,
    val allowedToolsJson: String,
    val toolsJson: String,
    val protocolVersion: String?,
    val lastError: String?,
    val lastConnectedAt: Long?,
    val createdAt: Long,
)

@Entity(tableName = "skills")
data class SkillEntity(
    @PrimaryKey val id: String,
    val name: String,
    val version: String,
    val description: String,
    val manifestJson: String,
    val instructions: String,
    val enabled: Boolean,
    val source: String,
    val permissionsJson: String,
    val installedAt: Long,
)

@Entity(tableName = "memories")
data class MemoryEntity(
    @PrimaryKey val id: String,
    val scope: String,
    val key: String,
    val value: String,
    val embeddingJson: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val enabled: Boolean,
    val sensitive: Boolean,
)

@Entity(tableName = "artifacts", indices = [Index("taskId")])
data class ArtifactEntity(
    @PrimaryKey val id: String,
    val taskId: String?,
    val name: String,
    val path: String,
    val mime: String,
    val sizeBytes: Long,
    val createdAt: Long,
    val description: String,
)

@Entity(tableName = "schedules")
data class ScheduleEntity(
    @PrimaryKey val id: String,
    val name: String,
    val goal: String,
    val intervalMinutes: Int,
    val enabled: Boolean,
    val lastRunAt: Long?,
    val nextRunAt: Long?,
    val createdAt: Long,
)
