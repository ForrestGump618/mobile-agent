package com.mobileagent.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(task: TaskEntity)

    @Query("SELECT * FROM tasks WHERE id = :id")
    fun observe(id: String): Flow<TaskEntity?>

    @Query("SELECT * FROM tasks WHERE id = :id")
    suspend fun get(id: String): TaskEntity?

    @Query("SELECT * FROM tasks ORDER BY updatedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int = 100): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE COALESCE(mode, 'agent') = :mode ORDER BY updatedAt DESC LIMIT :limit")
    fun observeByMode(mode: String, limit: Int): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE status IN (:statuses) ORDER BY updatedAt DESC")
    suspend fun findByStatuses(statuses: List<String>): List<TaskEntity>

    @Query("SELECT COUNT(*) FROM tasks")
    suspend fun count(): Int

    @Query("UPDATE tasks SET status = :status, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateStatus(id: String, status: String, updatedAt: Long)

    @Query("UPDATE tasks SET cancelRequested = :cancel WHERE id = :id")
    suspend fun setCancelRequested(id: String, cancel: Boolean)

    @Query("DELETE FROM tasks WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface MessageDao {
    @Insert
    suspend fun insert(message: MessageEntity): Long

    @Query("SELECT * FROM messages WHERE taskId = :taskId ORDER BY rowId ASC")
    fun observeByTask(taskId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE taskId = :taskId ORDER BY rowId ASC")
    suspend fun listByTask(taskId: String): List<MessageEntity>

    @Query("SELECT * FROM messages ORDER BY rowId DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE taskId = :taskId AND role = 'assistant' ORDER BY rowId DESC LIMIT 1")
    suspend fun latestAssistant(taskId: String): MessageEntity?

    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM messages WHERE taskId = :taskId")
    suspend fun deleteByTask(taskId: String)
}

@Dao
interface ToolCallDao {
    @Insert
    suspend fun insert(call: ToolCallEntity): Long

    @Query("UPDATE tool_calls SET resultJson = :resultJson, ok = :ok, finishedAt = :finishedAt, error = :error WHERE id = :id")
    suspend fun complete(id: String, resultJson: String, ok: Boolean, finishedAt: Long, error: String?)

    @Query("SELECT * FROM tool_calls WHERE taskId = :taskId ORDER BY rowId ASC")
    fun observeByTask(taskId: String): Flow<List<ToolCallEntity>>

    @Query("SELECT * FROM tool_calls WHERE taskId = :taskId ORDER BY rowId ASC")
    suspend fun listByTask(taskId: String): List<ToolCallEntity>

    @Query("SELECT * FROM tool_calls ORDER BY rowId DESC LIMIT :limit")
    fun observeRecent(limit: Int = 100): Flow<List<ToolCallEntity>>

    @Query("SELECT COUNT(*) FROM tool_calls WHERE taskId = :taskId")
    suspend fun countByTask(taskId: String): Int

    @Query("DELETE FROM tool_calls WHERE taskId = :taskId")
    suspend fun deleteByTask(taskId: String)
}

@Dao
interface CheckpointDao {
    @Insert
    suspend fun insert(checkpoint: CheckpointEntity)

    @Query("SELECT * FROM checkpoints WHERE taskId = :taskId ORDER BY rowId DESC LIMIT 1")
    suspend fun latest(taskId: String): CheckpointEntity?

    @Query("SELECT * FROM checkpoints WHERE taskId = :taskId ORDER BY rowId ASC")
    fun observeByTask(taskId: String): Flow<List<CheckpointEntity>>

    @Query("DELETE FROM checkpoints WHERE taskId = :taskId")
    suspend fun deleteByTask(taskId: String)
}

@Dao
interface EventDao {
    @Insert
    suspend fun insert(event: EventEntity)

    @Query("SELECT * FROM agent_events ORDER BY rowId DESC LIMIT :limit")
    fun observeRecent(limit: Int = 300): Flow<List<EventEntity>>

    @Query("SELECT * FROM agent_events WHERE taskId = :taskId ORDER BY rowId ASC")
    fun observeByTask(taskId: String): Flow<List<EventEntity>>

    @Query("SELECT * FROM agent_events WHERE taskId = :taskId ORDER BY rowId ASC")
    suspend fun listByTask(taskId: String): List<EventEntity>

    @Query("SELECT * FROM agent_events WHERE executionId = :executionId ORDER BY rowId ASC")
    suspend fun listByExecution(executionId: String): List<EventEntity>

    @Query("SELECT * FROM agent_events WHERE type = :type ORDER BY rowId DESC LIMIT :limit")
    suspend fun byType(type: String, limit: Int = 50): List<EventEntity>

    @Query("SELECT COUNT(*) FROM agent_events")
    suspend fun count(): Int

    @Query("DELETE FROM agent_events WHERE createdAt < :before")
    suspend fun deleteOlderThan(before: Long)
}

@Dao
interface ProviderDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(provider: ProviderEntity)

    @Query("SELECT * FROM providers ORDER BY createdAt ASC")
    fun observeAll(): Flow<List<ProviderEntity>>

    @Query("SELECT * FROM providers ORDER BY createdAt ASC")
    suspend fun list(): List<ProviderEntity>

    @Query("SELECT * FROM providers WHERE id = :id")
    suspend fun get(id: String): ProviderEntity?

    @Query("UPDATE providers SET lastProbeJson = :probe, lastTestedAt = :testedAt, verifiedCapsJson = :caps, lastError = :error WHERE id = :id")
    suspend fun updateTestResult(id: String, probe: String?, testedAt: Long, caps: String?, error: String?)

    @Query("DELETE FROM providers WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface McpServerDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(server: McpServerEntity)

    @Query("SELECT * FROM mcp_servers ORDER BY createdAt ASC")
    fun observeAll(): Flow<List<McpServerEntity>>

    @Query("SELECT * FROM mcp_servers ORDER BY createdAt ASC")
    suspend fun list(): List<McpServerEntity>

    @Query("SELECT * FROM mcp_servers WHERE id = :id")
    suspend fun get(id: String): McpServerEntity?

    @Query("DELETE FROM mcp_servers WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface SkillDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(skill: SkillEntity)

    @Query("SELECT * FROM skills ORDER BY name ASC")
    fun observeAll(): Flow<List<SkillEntity>>

    @Query("SELECT * FROM skills ORDER BY name ASC")
    suspend fun list(): List<SkillEntity>

    @Query("SELECT * FROM skills WHERE id = :id")
    suspend fun get(id: String): SkillEntity?

    @Query("SELECT COUNT(*) FROM skills")
    suspend fun count(): Int

    @Query("DELETE FROM skills WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface MemoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(memory: MemoryEntity)

    @Query("SELECT * FROM memories WHERE enabled = 1 ORDER BY updatedAt DESC")
    fun observeEnabled(): Flow<List<MemoryEntity>>

    @Query("SELECT * FROM memories WHERE enabled = 1 ORDER BY updatedAt DESC")
    suspend fun listEnabled(): List<MemoryEntity>

    @Query("SELECT * FROM memories ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<MemoryEntity>>

    @Query("SELECT * FROM memories WHERE scope = :scope AND `key` = :key LIMIT 1")
    suspend fun find(scope: String, key: String): MemoryEntity?

    @Query("DELETE FROM memories WHERE id = :id")
    suspend fun delete(id: String)

    @Query("UPDATE memories SET enabled = :enabled, updatedAt = :updatedAt WHERE id = :id")
    suspend fun setEnabled(id: String, enabled: Boolean, updatedAt: Long)
}

@Dao
interface ArtifactDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(artifact: ArtifactEntity)

    @Query("SELECT * FROM artifacts ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<ArtifactEntity>>

    @Query("SELECT * FROM artifacts WHERE taskId = :taskId ORDER BY createdAt ASC")
    fun observeByTask(taskId: String): Flow<List<ArtifactEntity>>

    @Query("SELECT * FROM artifacts WHERE taskId = :taskId ORDER BY createdAt ASC")
    suspend fun byTask(taskId: String): List<ArtifactEntity>

    @Query("DELETE FROM artifacts WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface ScheduleDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(schedule: ScheduleEntity)

    @Query("SELECT * FROM schedules ORDER BY createdAt ASC")
    fun observeAll(): Flow<List<ScheduleEntity>>

    @Query("SELECT * FROM schedules WHERE enabled = 1")
    suspend fun listEnabled(): List<ScheduleEntity>

    @Query("SELECT * FROM schedules WHERE id = :id")
    suspend fun get(id: String): ScheduleEntity?

    @Query("DELETE FROM schedules WHERE id = :id")
    suspend fun delete(id: String)
}
