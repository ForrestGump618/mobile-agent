package com.mobileagent.core.data

import com.mobileagent.core.data.db.AgentDatabase
import com.mobileagent.core.model.AgentEventType
import com.mobileagent.core.model.ArtifactRecord
import com.mobileagent.core.model.CheckpointPayload
import com.mobileagent.core.model.ChatMessage
import com.mobileagent.core.model.ChatMode
import com.mobileagent.core.model.EventRecord
import com.mobileagent.core.model.JsonCodec
import com.mobileagent.core.model.TaskPlan
import com.mobileagent.core.model.TaskRecord
import com.mobileagent.core.model.TaskStatus
import com.mobileagent.core.model.ToolResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonObject
import java.util.UUID

/**
 * Single persistence gateway for task state, transcripts, tool invocations, checkpoints,
 * audit events and produced artifacts. All harness writes go through here.
 */
class TaskRepository(private val db: AgentDatabase) {

    private val taskDao = db.taskDao()
    private val messageDao = db.messageDao()
    private val toolCallDao = db.toolCallDao()
    private val checkpointDao = db.checkpointDao()
    private val eventDao = db.eventDao()
    private val artifactDao = db.artifactDao()

    // ---------------------------------------------------------------- tasks

    fun observeTasks(limit: Int = 100): Flow<List<TaskRecord>> =
        taskDao.observeRecent(limit).map { list -> list.map { it.toRecord() } }

    fun observeByMode(mode: ChatMode, limit: Int = 100): Flow<List<TaskRecord>> =
        taskDao.observeByMode(mode.name.lowercase(), limit).map { list -> list.map { it.toRecord() } }

    fun observeChats(limit: Int = 100): Flow<List<TaskRecord>> = observeByMode(ChatMode.CHAT, limit)

    fun observeAgentTasks(limit: Int = 100): Flow<List<TaskRecord>> = observeByMode(ChatMode.AGENT, limit)

    fun observeTask(taskId: String): Flow<TaskRecord?> = taskDao.observe(taskId).map { it?.toRecord() }

    suspend fun getTask(taskId: String): TaskRecord? = taskDao.get(taskId)?.toRecord()

    suspend fun createTask(
        goal: String,
        title: String = defaultTitle(goal),
        modelLabel: String? = null,
        mode: ChatMode = ChatMode.AGENT,
    ): TaskRecord {
        val now = System.currentTimeMillis()
        val entity = com.mobileagent.core.data.db.TaskEntity(
            id = UUID.randomUUID().toString(),
            title = title,
            goal = goal,
            status = TaskStatus.CREATED.name,
            createdAt = now,
            updatedAt = now,
            modelLabel = modelLabel,
            mode = mode.name.lowercase(),
        )
        taskDao.upsert(entity)
        return entity.toRecord()
    }

    suspend fun updateStatus(taskId: String, status: TaskStatus, error: String? = null) {
        val current = taskDao.get(taskId) ?: return
        taskDao.upsert(
            current.copy(
                status = status.name,
                updatedAt = System.currentTimeMillis(),
                lastError = if (error != null) error else current.lastError,
                cancelRequested = if (status == TaskStatus.CANCELLED) true else current.cancelRequested,
            ),
        )
    }

    suspend fun updatePlan(taskId: String, plan: TaskPlan?) {
        val current = taskDao.get(taskId) ?: return
        taskDao.upsert(
            current.copy(
                planJson = plan?.let { JsonCodec.json.encodeToString(TaskPlan.serializer(), it) },
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun updateSummary(taskId: String, summary: String) {
        val current = taskDao.get(taskId) ?: return
        taskDao.upsert(current.copy(contextSummary = summary, updatedAt = System.currentTimeMillis()))
    }

    suspend fun setResult(taskId: String, result: String) {
        val current = taskDao.get(taskId) ?: return
        taskDao.upsert(
            current.copy(
                resultText = result,
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun bumpIteration(taskId: String, iteration: Int, toolCount: Int, modelLabel: String?) {
        val current = taskDao.get(taskId) ?: return
        taskDao.upsert(
            current.copy(
                iteration = iteration,
                toolCallCount = toolCount,
                modelLabel = modelLabel ?: current.modelLabel,
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun setCancelRequested(taskId: String, cancel: Boolean) {
        taskDao.setCancelRequested(taskId, cancel)
    }

    suspend fun requestCancel(taskId: String) {
        taskDao.setCancelRequested(taskId, true)
        val current = taskDao.get(taskId) ?: return
        if (!runCatching { TaskStatus.valueOf(current.status) }.getOrDefault(TaskStatus.CREATED).isTerminal) {
            taskDao.updateStatus(taskId, TaskStatus.CANCELLED.name, System.currentTimeMillis())
        }
    }

    suspend fun isCancelRequested(taskId: String): Boolean = taskDao.get(taskId)?.cancelRequested ?: false

    suspend fun deleteTask(taskId: String) {
        messageDao.deleteByTask(taskId)
        toolCallDao.deleteByTask(taskId)
        checkpointDao.deleteByTask(taskId)
        taskDao.delete(taskId)
    }

    suspend fun tasksInStatus(vararg statuses: TaskStatus): List<TaskRecord> =
        taskDao.findByStatuses(statuses.map { it.name }).map { it.toRecord() }

    suspend fun taskCount(): Int = taskDao.count()

    // ------------------------------------------------------------- messages

    fun observeMessages(taskId: String): Flow<List<ChatMessage>> =
        messageDao.observeByTask(taskId).map { list ->
            list.map {
                ChatMessage(
                    id = it.id,
                    taskId = it.taskId,
                    role = it.role,
                    content = it.content,
                    createdAt = it.createdAt,
                    toolCallId = it.toolCallId,
                    toolName = it.toolName,
                )
            }
        }

    suspend fun messages(taskId: String): List<ChatMessage> =
        messageDao.listByTask(taskId).map {
            ChatMessage(it.id, it.taskId, it.role, it.content, it.createdAt, it.toolCallId, it.toolName)
        }

    suspend fun recentMessages(limit: Int = 40): List<ChatMessage> =
        messageDao.recent(limit).reversed().map {
            ChatMessage(it.id, it.taskId, it.role, it.content, it.createdAt, it.toolCallId, it.toolName)
        }

    suspend fun appendMessage(
        taskId: String,
        role: String,
        content: String,
        toolCallId: String? = null,
        toolName: String? = null,
    ): ChatMessage {
        val message = ChatMessage(
            id = UUID.randomUUID().toString(),
            taskId = taskId,
            role = role,
            content = content,
            createdAt = System.currentTimeMillis(),
            toolCallId = toolCallId,
            toolName = toolName,
        )
        messageDao.insert(
            com.mobileagent.core.data.db.MessageEntity(
                id = message.id,
                taskId = taskId,
                role = role,
                content = content,
                createdAt = message.createdAt,
                toolCallId = toolCallId,
                toolName = toolName,
            ),
        )
        return message
    }

    suspend fun deleteMessage(messageId: String) = messageDao.deleteById(messageId)

    suspend fun latestAssistantMessage(taskId: String): ChatMessage? =
        messageDao.latestAssistant(taskId)?.let {
            ChatMessage(it.id, it.taskId, it.role, it.content, it.createdAt, it.toolCallId, it.toolName)
        }

    // ------------------------------------------------------------ tool calls

    fun observeToolCalls(taskId: String): Flow<List<com.mobileagent.core.data.db.ToolCallEntity>> =
        toolCallDao.observeByTask(taskId)

    fun observeRecentToolCalls(limit: Int = 100) = toolCallDao.observeRecent(limit)

    suspend fun startToolCall(
        taskId: String,
        executionId: String,
        toolName: String,
        argumentsJson: String,
        risk: String,
        approved: Boolean,
    ): String {
        val id = UUID.randomUUID().toString()
        toolCallDao.insert(
            com.mobileagent.core.data.db.ToolCallEntity(
                id = id,
                taskId = taskId,
                executionId = executionId,
                toolName = toolName,
                argumentsJson = argumentsJson,
                risk = risk,
                approved = approved,
                startedAt = System.currentTimeMillis(),
            ),
        )
        return id
    }

    suspend fun finishToolCall(callRowId: String, result: ToolResult) {
        toolCallDao.complete(
            id = callRowId,
            resultJson = result.output.toString(),
            ok = result.ok,
            finishedAt = System.currentTimeMillis(),
            error = result.error,
        )
    }

    suspend fun toolCallsFor(taskId: String) = toolCallDao.listByTask(taskId)

    // ------------------------------------------------------------ checkpoints

    suspend fun saveCheckpoint(taskId: String, payload: CheckpointPayload) {
        checkpointDao.insert(
            com.mobileagent.core.data.db.CheckpointEntity(
                id = UUID.randomUUID().toString(),
                taskId = taskId,
                createdAt = System.currentTimeMillis(),
                payloadJson = JsonCodec.json.encodeToString(CheckpointPayload.serializer(), payload),
            ),
        )
        addEvent(taskId, null, AgentEventType.CHECKPOINT_SAVED, "Checkpoint saved after ${payload.completedSteps.size} completed step(s)")
    }

    suspend fun latestCheckpoint(taskId: String): CheckpointPayload? {
        val entity = checkpointDao.latest(taskId) ?: return null
        return runCatching { JsonCodec.json.decodeFromString(CheckpointPayload.serializer(), entity.payloadJson) }.getOrNull()
    }

    fun observeCheckpoints(taskId: String) = checkpointDao.observeByTask(taskId)

    // ----------------------------------------------------------------- events

    suspend fun addEvent(
        taskId: String?,
        executionId: String?,
        type: AgentEventType,
        message: String,
        payload: JsonObject? = null,
    ) {
        eventDao.insert(
            com.mobileagent.core.data.db.EventEntity(
                id = UUID.randomUUID().toString(),
                taskId = taskId,
                executionId = executionId,
                type = type.name,
                message = message,
                payloadJson = payload?.toString(),
                createdAt = System.currentTimeMillis(),
            ),
        )
    }

    fun observeEvents(limit: Int = 300): Flow<List<EventRecord>> =
        eventDao.observeRecent(limit).map { list -> list.map { it.toRecord() } }

    fun observeTaskEvents(taskId: String): Flow<List<EventRecord>> =
        eventDao.observeByTask(taskId).map { list -> list.map { it.toRecord() } }

    suspend fun taskEvents(taskId: String): List<EventRecord> = eventDao.listByTask(taskId).map { it.toRecord() }

    suspend fun recentEvents(limit: Int = 300): List<EventRecord> =
        eventDao.observeRecent(limit).first().map { it.toRecord() }

    suspend fun executionEvents(executionId: String): List<EventRecord> =
        eventDao.listByExecution(executionId).map { it.toRecord() }

    suspend fun deleteOldEvents(before: Long) = eventDao.deleteOlderThan(before)

    suspend fun eventCount(): Int = eventDao.count()

    // --------------------------------------------------------------- artifacts

    suspend fun recordArtifact(
        taskId: String?,
        name: String,
        path: String,
        mime: String,
        sizeBytes: Long,
        description: String = "",
    ): ArtifactRecord {
        val record = ArtifactRecord(
            id = UUID.randomUUID().toString(),
            taskId = taskId,
            name = name,
            path = path,
            mime = mime,
            sizeBytes = sizeBytes,
            createdAt = System.currentTimeMillis(),
            description = description,
        )
        artifactDao.upsert(
            com.mobileagent.core.data.db.ArtifactEntity(
                id = record.id,
                taskId = taskId,
                name = name,
                path = path,
                mime = mime,
                sizeBytes = sizeBytes,
                createdAt = record.createdAt,
                description = description,
            ),
        )
        return record
    }

    fun observeArtifacts(): Flow<List<ArtifactRecord>> =
        artifactDao.observeAll().map { list -> list.map { it.toArtifact() } }

    fun observeTaskArtifacts(taskId: String): Flow<List<ArtifactRecord>> =
        artifactDao.observeByTask(taskId).map { list -> list.map { it.toArtifact() } }

    suspend fun deleteArtifact(id: String) = artifactDao.delete(id)

    suspend fun artifactsFor(taskId: String): List<ArtifactRecord> =
        artifactDao.byTask(taskId).map { it.toArtifact() }

    private fun com.mobileagent.core.data.db.ArtifactEntity.toArtifact() = ArtifactRecord(
        id = id,
        taskId = taskId,
        name = name,
        path = path,
        mime = mime,
        sizeBytes = sizeBytes,
        createdAt = createdAt,
        description = description,
    )

    private fun com.mobileagent.core.data.db.EventEntity.toRecord() = EventRecord(
        id = id,
        taskId = taskId,
        executionId = executionId,
        type = runCatching { AgentEventType.valueOf(type) }.getOrDefault(AgentEventType.DIAGNOSTIC),
        message = message,
        payloadJson = payloadJson,
        createdAt = createdAt,
    )

    private fun com.mobileagent.core.data.db.TaskEntity.toRecord(): TaskRecord = TaskRecord(
        id = id,
        title = title,
        goal = goal,
        status = runCatching { TaskStatus.valueOf(status) }.getOrDefault(TaskStatus.CREATED),
        createdAt = createdAt,
        updatedAt = updatedAt,
        plan = planJson?.let {
            runCatching { JsonCodec.json.decodeFromString(TaskPlan.serializer(), it) }.getOrNull()
        },
        contextSummary = contextSummary,
        lastError = lastError,
        toolCallCount = toolCallCount,
        modelLabel = modelLabel,
        resultText = resultText,
        cancelRequested = cancelRequested,
        iteration = iteration,
        mode = runCatching { ChatMode.valueOf((mode ?: "agent").uppercase()) }.getOrDefault(ChatMode.AGENT),
    )

    private fun defaultTitle(goal: String): String {
        val single = goal.replace(Regex("\\s+"), " ").trim()
        return if (single.length <= 48) single else single.take(45) + "..."
    }
}
