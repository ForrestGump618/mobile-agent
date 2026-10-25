package com.mobileagent.app.ui.vm

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mobileagent.app.MobileAgentApplication
import com.mobileagent.core.model.Capability
import com.mobileagent.core.model.ProviderConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ModelsViewModel(application: Application) : AndroidViewModel(application) {

    private val graph = (application as MobileAgentApplication).graph

    val providers: StateFlow<List<ProviderConfig>> = graph.providers.observeProviders()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val activeProviderId: StateFlow<String?> = graph.settings.settings.map { it.activeProviderId }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val testingId = MutableStateFlow<String?>(null)
    val message = MutableStateFlow<String?>(null)

    fun setDefault(id: String) {
        viewModelScope.launch { graph.settings.setActiveProvider(id) }
    }

    fun test(id: String) {
        viewModelScope.launch {
            val provider = graph.providers.get(id) ?: return@launch
            testingId.value = id
            val key = provider.apiKeyId?.let { graph.secrets.get(it) }
            val result = graph.connectionTester.test(provider, key)
            graph.providers.saveProbe(
                report = com.mobileagent.core.model.ModelCapabilityReport(
                    providerId = provider.id,
                    modelId = provider.modelId,
                    verified = result.verified,
                    unverified = Capability.entries.filter { it !in result.verified }.toSet(),
                    notes = result.checks.joinToString { "${it.id}:${it.status}" },
                    checkedAt = System.currentTimeMillis(),
                ),
                error = if (result.ok) null else (result.technical ?: "测试失败"),
            )
            testingId.value = null
            message.value = if (result.ok) {
                "连接成功，已验证：${result.verified.joinToString { capabilityName(it) }}"
            } else {
                "连接失败：${result.technical ?: "请打开编辑页查看分步结果"}"
            }
        }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            val provider = graph.providers.get(id)
            provider?.apiKeyId?.let { graph.secrets.delete(it) }
            graph.providers.delete(id)
            if (graph.settings.current().activeProviderId == id) {
                graph.settings.setActiveProvider(graph.providers.list().firstOrNull()?.id)
            }
            message.value = "已删除模型。"
        }
    }

    fun clearMessage() {
        message.value = null
    }

    fun capabilityName(capability: Capability): String = when (capability) {
        Capability.TEXT -> "文本"
        Capability.TOOL_CALLING -> "工具调用"
        Capability.VISION -> "视觉"
        Capability.STREAMING -> "流式"
        Capability.STRUCTURED_OUTPUT -> "结构化输出"
        Capability.REASONING -> "推理"
        Capability.EMBEDDING -> "向量"
    }
}
