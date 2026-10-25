package com.mobileagent.core.data

import com.mobileagent.core.data.db.AgentDatabase
import com.mobileagent.core.data.db.ProviderEntity
import com.mobileagent.core.model.JsonCodec
import com.mobileagent.core.model.ModelCapabilityReport
import com.mobileagent.core.model.ProviderConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

class ProviderRepository(db: AgentDatabase) {

    private val dao = db.providerDao()

    fun observeProviders(): Flow<List<ProviderConfig>> = dao.observeAll().map { list -> list.map { it.toConfig() } }

    suspend fun list(): List<ProviderConfig> = dao.list().map { it.toConfig() }

    suspend fun get(id: String): ProviderConfig? = dao.get(id)?.toConfig()

    suspend fun upsert(config: ProviderConfig) {
        val now = System.currentTimeMillis()
        val existing = dao.get(config.id)
        dao.upsert(
            ProviderEntity(
                id = config.id,
                name = config.name,
                baseUrl = config.baseUrl.trimEnd('/'),
                modelId = config.modelId,
                apiKeyId = config.apiKeyId,
                timeoutMs = config.timeoutMs,
                streaming = config.streaming,
                toolCalling = config.toolCalling,
                vision = config.vision,
                reasoning = config.reasoning,
                structuredOutput = config.structuredOutput,
                contextWindow = config.contextWindow,
                maxOutputTokens = config.maxOutputTokens,
                temperature = config.temperature,
                enabled = config.enabled,
                createdAt = existing?.createdAt ?: now,
                updatedAt = now,
                lastProbeJson = existing?.lastProbeJson,
                presetId = config.presetId,
                apiPath = config.apiPath,
                lastError = config.lastError ?: existing?.lastError,
                lastTestedAt = config.lastTestedAt ?: existing?.lastTestedAt,
                verifiedCapsJson = config.verifiedCapabilities
                    .takeIf { it.isNotEmpty() }
                    ?.joinToString(",") { it.name }
                    ?: existing?.verifiedCapsJson,
            ),
        )
    }

    suspend fun create(
        name: String,
        baseUrl: String,
        modelId: String,
        apiKeySecretId: String?,
        vision: Boolean = false,
        toolCalling: Boolean = true,
        streaming: Boolean = true,
        contextWindow: Int = 32_768,
        maxOutputTokens: Int = 2_048,
        temperature: Double = 0.2,
        reasoning: Boolean = false,
    ): ProviderConfig {
        val config = ProviderConfig(
            id = UUID.randomUUID().toString(),
            name = name,
            baseUrl = baseUrl,
            modelId = modelId,
            apiKeyId = apiKeySecretId,
            vision = vision,
            toolCalling = toolCalling,
            streaming = streaming,
            contextWindow = contextWindow,
            maxOutputTokens = maxOutputTokens,
            temperature = temperature,
            reasoning = reasoning,
        )
        upsert(config)
        return config
    }

    suspend fun delete(id: String) = dao.delete(id)

    suspend fun saveProbe(report: ModelCapabilityReport, error: String? = null) {
        dao.updateTestResult(
            id = report.providerId,
            probe = JsonCodec.pretty.encodeToString(ModelCapabilityReport.serializer(), report),
            testedAt = System.currentTimeMillis(),
            caps = report.verified.joinToString(",") { it.name },
            error = error,
        )
    }

    suspend fun recordTestFailure(id: String, error: String) {
        dao.updateTestResult(id, null, System.currentTimeMillis(), null, error)
    }

    suspend fun probeReport(id: String): ModelCapabilityReport? {
        val raw = dao.get(id)?.lastProbeJson ?: return null
        return runCatching { JsonCodec.json.decodeFromString(ModelCapabilityReport.serializer(), raw) }.getOrNull()
    }

    private fun ProviderEntity.toConfig() = ProviderConfig(
        id = id,
        name = name,
        baseUrl = baseUrl,
        modelId = modelId,
        apiKeyId = apiKeyId,
        timeoutMs = timeoutMs,
        streaming = streaming,
        toolCalling = toolCalling,
        vision = vision,
        reasoning = reasoning,
        structuredOutput = structuredOutput,
        contextWindow = contextWindow,
        maxOutputTokens = maxOutputTokens,
        temperature = temperature,
        enabled = enabled,
        presetId = presetId,
        apiPath = apiPath ?: "/chat/completions",
        lastError = lastError,
        lastTestedAt = lastTestedAt,
        verifiedCapabilities = verifiedCapsJson
            ?.split(",")
            ?.mapNotNull { name -> runCatching { com.mobileagent.core.model.Capability.valueOf(name.trim()) }.getOrNull() }
            ?.toSet()
            .orEmpty(),
    )
}
