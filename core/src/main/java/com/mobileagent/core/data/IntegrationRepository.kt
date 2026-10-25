package com.mobileagent.core.data

import com.mobileagent.core.data.db.AgentDatabase
import com.mobileagent.core.data.db.McpServerEntity
import com.mobileagent.core.data.db.ScheduleEntity
import com.mobileagent.core.data.db.SkillEntity
import com.mobileagent.core.model.JsonCodec
import com.mobileagent.core.model.McpServerConfig
import com.mobileagent.core.model.McpToolDescriptor
import com.mobileagent.core.model.McpTransport
import com.mobileagent.core.model.MemoryRecord
import com.mobileagent.core.model.ScheduleRecord
import com.mobileagent.core.model.SkillManifest
import com.mobileagent.core.model.SkillPermissions
import com.mobileagent.core.model.SkillSummary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer

/** Persistence for MCP servers, skills, long-term memory and scheduled checks. */
class IntegrationRepository(db: AgentDatabase) {

    private val mcpDao = db.mcpServerDao()
    private val skillDao = db.skillDao()
    private val memoryDao = db.memoryDao()
    private val scheduleDao = db.scheduleDao()

    // ------------------------------------------------------------------- MCP

    fun observeMcpServers(): Flow<List<McpServerConfig>> =
        mcpDao.observeAll().map { list -> list.map { it.toConfig() } }

    suspend fun mcpServers(): List<McpServerConfig> = mcpDao.list().map { it.toConfig() }

    suspend fun getMcpServer(id: String): McpServerConfig? = mcpDao.get(id)?.toConfig()

    suspend fun upsertMcpServer(config: McpServerConfig) {
        mcpDao.upsert(
            McpServerEntity(
                id = config.id,
                name = config.name,
                transport = config.transport.name,
                endpoint = config.endpoint,
                authHeaderName = config.authHeaderName,
                authSecretId = config.authSecretId,
                enabled = config.enabled,
                allowedToolsJson = JsonCodec.json.encodeToString(
                    ListSerializer(String.serializer()),
                    config.allowedTools.toList(),
                ),
                toolsJson = JsonCodec.json.encodeToString(
                    ListSerializer(McpToolDescriptor.serializer()),
                    config.tools,
                ),
                protocolVersion = config.protocolVersion,
                lastError = config.lastError,
                lastConnectedAt = config.lastConnectedAt,
                createdAt = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun deleteMcpServer(id: String) = mcpDao.delete(id)

    private fun McpServerEntity.toConfig(): McpServerConfig {
        val tools = runCatching {
            JsonCodec.json.decodeFromString(ListSerializer(McpToolDescriptor.serializer()), toolsJson)
        }.getOrDefault(emptyList())
        val allowed = runCatching {
            JsonCodec.json.decodeFromString(ListSerializer(String.serializer()), allowedToolsJson)
        }.getOrDefault(emptyList())
        return McpServerConfig(
            id = id,
            name = name,
            transport = runCatching { McpTransport.valueOf(transport) }.getOrDefault(McpTransport.STREAMABLE_HTTP),
            endpoint = endpoint,
            authHeaderName = authHeaderName,
            authSecretId = authSecretId,
            enabled = enabled,
            allowedTools = allowed.toSet(),
            protocolVersion = protocolVersion,
            lastError = lastError,
            lastConnectedAt = lastConnectedAt,
            tools = tools,
        )
    }

    // ---------------------------------------------------------------- skills

    fun observeSkills(): Flow<List<SkillSummary>> =
        skillDao.observeAll().map { list -> list.mapNotNull { it.toSummary() } }

    suspend fun skills(): List<SkillSummary> = skillDao.list().mapNotNull { it.toSummary() }

    suspend fun skill(id: String): SkillSummary? = skillDao.get(id)?.toSummary()

    suspend fun upsertSkill(manifest: SkillManifest, source: String, enabled: Boolean = true, installedAt: Long = System.currentTimeMillis()) {
        skillDao.upsert(
            SkillEntity(
                id = manifest.id,
                name = manifest.name,
                version = manifest.version,
                description = manifest.description,
                manifestJson = JsonCodec.json.encodeToString(SkillManifest.serializer(), manifest),
                instructions = manifest.instructions,
                enabled = enabled,
                source = source,
                permissionsJson = JsonCodec.json.encodeToString(SkillPermissions.serializer(), manifest.permissions),
                installedAt = installedAt,
            ),
        )
    }

    suspend fun setSkillEnabled(id: String, enabled: Boolean) {
        val existing = skillDao.get(id) ?: return
        skillDao.upsert(existing.copy(enabled = enabled))
    }

    suspend fun deleteSkill(id: String) = skillDao.delete(id)

    suspend fun skillCount(): Int = skillDao.count()

    private fun SkillEntity.toSummary(): SkillSummary? {
        val manifest = runCatching {
            JsonCodec.json.decodeFromString(SkillManifest.serializer(), manifestJson)
        }.getOrNull() ?: return null
        return SkillSummary(
            id = id,
            name = name,
            version = version,
            description = description,
            enabled = enabled,
            source = source,
            manifest = manifest,
            installedAt = installedAt,
        )
    }

    // ---------------------------------------------------------------- memory

    fun observeMemories(): Flow<List<MemoryRecord>> =
        memoryDao.observeAll().map { list ->
            list.map { MemoryRecord(it.id, it.scope, it.key, it.value, it.enabled, it.sensitive, it.updatedAt) }
        }

    fun observeEnabledMemories(): Flow<List<MemoryRecord>> =
        memoryDao.observeEnabled().map { list ->
            list.map { MemoryRecord(it.id, it.scope, it.key, it.value, it.enabled, it.sensitive, it.updatedAt) }
        }

    suspend fun enabledMemories(): List<MemoryRecord> =
        memoryDao.listEnabled().map { MemoryRecord(it.id, it.scope, it.key, it.value, it.enabled, it.sensitive, it.updatedAt) }

    suspend fun findMemory(scope: String, key: String): MemoryRecord? = memoryDao.find(scope, key)?.let {
        MemoryRecord(it.id, it.scope, it.key, it.value, it.enabled, it.sensitive, it.updatedAt)
    }

    suspend fun upsertMemory(
        id: String,
        scope: String,
        key: String,
        value: String,
        sensitive: Boolean = false,
        embeddingJson: String? = null,
        enabled: Boolean = true,
    ) {
        val now = System.currentTimeMillis()
        val existing = memoryDao.find(scope, key)
        memoryDao.upsert(
            com.mobileagent.core.data.db.MemoryEntity(
                id = existing?.id ?: id,
                scope = scope,
                key = key,
                value = value,
                embeddingJson = embeddingJson,
                createdAt = existing?.createdAt ?: now,
                updatedAt = now,
                enabled = enabled,
                sensitive = sensitive,
            ),
        )
    }

    suspend fun setMemoryEnabled(id: String, enabled: Boolean) =
        memoryDao.setEnabled(id, enabled, System.currentTimeMillis())

    suspend fun deleteMemory(id: String) = memoryDao.delete(id)

    // ------------------------------------------------------------- schedules

    fun observeSchedules(): Flow<List<ScheduleRecord>> =
        scheduleDao.observeAll().map { list -> list.map { it.toRecord() } }

    suspend fun enabledSchedules(): List<ScheduleRecord> = scheduleDao.listEnabled().map { it.toRecord() }

    suspend fun getSchedule(id: String): ScheduleRecord? = scheduleDao.get(id)?.toRecord()

    suspend fun upsertSchedule(record: ScheduleRecord) {
        scheduleDao.upsert(
            ScheduleEntity(
                id = record.id,
                name = record.name,
                goal = record.goal,
                intervalMinutes = record.intervalMinutes,
                enabled = record.enabled,
                lastRunAt = record.lastRunAt,
                nextRunAt = record.nextRunAt,
                createdAt = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun deleteSchedule(id: String) = scheduleDao.delete(id)

    private fun ScheduleEntity.toRecord() = ScheduleRecord(
        id = id,
        name = name,
        goal = goal,
        intervalMinutes = intervalMinutes,
        enabled = enabled,
        lastRunAt = lastRunAt,
        nextRunAt = nextRunAt,
    )
}
