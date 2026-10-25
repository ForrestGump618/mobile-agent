package com.mobileagent.app.ui.vm

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mobileagent.app.MobileAgentApplication
import com.mobileagent.core.llm.ConnectionTestResult
import com.mobileagent.core.llm.ProviderPreset
import com.mobileagent.core.llm.ProviderPresets
import com.mobileagent.core.model.Capability
import com.mobileagent.core.model.ProviderConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

data class ProviderFormState(
    val editingId: String? = null,
    val presetId: String = "deepseek",
    val name: String = "DeepSeek",
    val baseUrl: String = "https://api.deepseek.com",
    val apiPath: String = "/chat/completions",
    val modelId: String = "deepseek-flash",
    val apiKey: String = "",
    val hasStoredKey: Boolean = false,
    val streaming: Boolean = true,
    val toolCalling: Boolean = true,
    val vision: Boolean = false,
    val temperature: String = "0.7",
    val maxOutputTokens: String = "2048",
    val contextWindow: String = "32768",
    val timeoutSec: String = "60",
    val advanced: Boolean = false,
)

class ProviderSetupViewModel(application: Application) : AndroidViewModel(application) {

    private val graph = (application as MobileAgentApplication).graph

    private val _form = MutableStateFlow(ProviderFormState())
    val form: StateFlow<ProviderFormState> = _form.asStateFlow()

    private val _testResult = MutableStateFlow<ConnectionTestResult?>(null)
    val testResult: StateFlow<ConnectionTestResult?> = _testResult.asStateFlow()

    private val _testing = MutableStateFlow(false)
    val testing: StateFlow<Boolean> = _testing.asStateFlow()

    private val _saving = MutableStateFlow(false)
    val saving: StateFlow<Boolean> = _saving.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _savedId = MutableStateFlow<String?>(null)
    val savedId: StateFlow<String?> = _savedId.asStateFlow()

    private var existingSecretId: String? = null

    val presets: List<ProviderPreset> = ProviderPresets.all

    fun load(providerId: String) {
        viewModelScope.launch {
            val provider = graph.providers.get(providerId) ?: return@launch
            existingSecretId = provider.apiKeyId
            _form.value = ProviderFormState(
                editingId = provider.id,
                presetId = provider.presetId ?: "custom",
                name = provider.name,
                baseUrl = provider.baseUrl,
                apiPath = provider.apiPath,
                modelId = provider.modelId,
                hasStoredKey = provider.apiKeyId != null,
                streaming = provider.streaming,
                toolCalling = provider.toolCalling,
                vision = provider.vision,
                temperature = provider.temperature.toString(),
                maxOutputTokens = provider.maxOutputTokens.toString(),
                contextWindow = provider.contextWindow.toString(),
                timeoutSec = (provider.timeoutMs / 1000).toString(),
            )
            _testResult.value = null
        }
    }

    fun update(block: (ProviderFormState) -> ProviderFormState) {
        _form.value = block(_form.value)
        _error.value = null
    }

    fun selectPreset(preset: ProviderPreset) {
        if (existingSecretId == null) {
            _testResult.value = null
        }
        _form.value = ProviderFormState(
            editingId = _form.value.editingId,
            hasStoredKey = _form.value.hasStoredKey,
            presetId = preset.id,
            name = preset.displayName,
            baseUrl = preset.baseUrl,
            apiPath = preset.apiPath,
            modelId = preset.defaultModel,
            streaming = true,
            toolCalling = preset.supportsTools,
            vision = preset.supportsVision,
        )
    }

    fun test() {
        val form = _form.value
        if (_testing.value) return
        val key = resolveKey(form)
        if (key.isNullOrBlank()) {
            _error.value = "请先填写 API Key。"
            return
        }
        if (form.modelId.isBlank()) {
            _error.value = "请填写模型 ID。"
            return
        }
        if (!form.baseUrl.startsWith("http")) {
            _error.value = "Base URL 必须以 http:// 或 https:// 开头。"
            return
        }

        viewModelScope.launch {
            _testing.value = true
            _error.value = null
            _testResult.value = null
            try {
                val result = graph.connectionTester.test(toConfig(form), key)
                _testResult.value = result
            } catch (t: Throwable) {
                _error.value = t.message ?: "测试失败"
            } finally {
                _testing.value = false
            }
        }
    }

    fun save(onSaved: (Boolean) -> Unit = {}) {
        val form = _form.value
        if (_saving.value) return
        if (!form.baseUrl.startsWith("http")) {
            _error.value = "Base URL 必须以 http:// 或 https:// 开头。"
            return
        }
        if (form.modelId.isBlank()) {
            _error.value = "请填写模型 ID。"
            return
        }
        val key = resolveKey(form)
        if (key.isNullOrBlank()) {
            _error.value = "请填写 API Key。"
            return
        }

        viewModelScope.launch {
            _saving.value = true
            try {
                val providerId = form.editingId ?: UUID.randomUUID().toString()
                val secretId = existingSecretId ?: "provider-$providerId"
                graph.secrets.put(secretId, key)

                val current = graph.providers.get(providerId)
                val test = _testResult.value
                val config = ProviderConfig(
                    id = providerId,
                    name = form.name.ifBlank { "自定义模型" },
                    baseUrl = form.baseUrl.trim().trimEnd('/'),
                    modelId = form.modelId.trim(),
                    apiKeyId = secretId,
                    timeoutMs = ((form.timeoutSec.toLongOrNull() ?: 60L) * 1000).coerceIn(5_000, 300_000),
                    streaming = form.streaming,
                    toolCalling = form.toolCalling,
                    vision = form.vision,
                    reasoning = current?.reasoning ?: false,
                    structuredOutput = current?.structuredOutput ?: false,
                    contextWindow = (form.contextWindow.toIntOrNull() ?: 32_768).coerceIn(2_048, 1_000_000),
                    maxOutputTokens = (form.maxOutputTokens.toIntOrNull() ?: 2_048).coerceIn(128, 32_000),
                    temperature = (form.temperature.toDoubleOrNull() ?: 0.7).coerceIn(0.0, 2.0),
                    enabled = true,
                    presetId = form.presetId,
                    apiPath = form.apiPath.ifBlank { "/chat/completions" },
                    lastError = if (test?.ok == false) test.technical else null,
                    lastTestedAt = test?.let { System.currentTimeMillis() },
                    verifiedCapabilities = test?.verified.orEmpty(),
                )
                graph.providers.upsert(config)
                val currentSettings = graph.settings.current()
                if (currentSettings.activeProviderId == null || form.editingId == null) {
                    graph.settings.setActiveProvider(providerId)
                }
                graph.settings.completeOnboarding()
                _savedId.value = providerId
                onSaved(true)
            } catch (t: Throwable) {
                _error.value = "保存失败：${t.message}"
                onSaved(false)
            } finally {
                _saving.value = false
            }
        }
    }

    fun clearError() {
        _error.value = null
    }

    fun clearSaved() {
        _savedId.value = null
    }

    private fun resolveKey(form: ProviderFormState): String? {
        if (form.apiKey.isNotBlank()) return form.apiKey.trim()
        return existingSecretId?.let { graph.secrets.get(it) }
    }

    private fun toConfig(form: ProviderFormState): ProviderConfig = ProviderConfig(
        id = form.editingId ?: "test-${System.currentTimeMillis()}",
        name = form.name.ifBlank { form.presetId },
        baseUrl = form.baseUrl.trim().trimEnd('/'),
        modelId = form.modelId.trim(),
        apiKeyId = null,
        timeoutMs = ((form.timeoutSec.toLongOrNull() ?: 60L) * 1000).coerceIn(5_000, 300_000),
        streaming = form.streaming,
        toolCalling = form.toolCalling,
        vision = form.vision,
        contextWindow = (form.contextWindow.toIntOrNull() ?: 32_768).coerceIn(2_048, 1_000_000),
        maxOutputTokens = (form.maxOutputTokens.toIntOrNull() ?: 2_048).coerceIn(128, 32_000),
        temperature = (form.temperature.toDoubleOrNull() ?: 0.7).coerceIn(0.0, 2.0),
        presetId = form.presetId,
        apiPath = form.apiPath.ifBlank { "/chat/completions" },
    )

    fun capabilityLabel(capability: Capability): String = when (capability) {
        Capability.TEXT -> "文本"
        Capability.TOOL_CALLING -> "工具调用"
        Capability.VISION -> "视觉"
        Capability.STREAMING -> "流式"
        Capability.STRUCTURED_OUTPUT -> "结构化输出"
        Capability.REASONING -> "推理"
        Capability.EMBEDDING -> "向量"
    }
}
