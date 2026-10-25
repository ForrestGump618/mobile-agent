package com.mobileagent.core.llm

import com.mobileagent.core.model.ChatRequest
import com.mobileagent.core.model.ChatResponse
import com.mobileagent.core.model.LlmErrorKind
import com.mobileagent.core.model.LlmException
import com.mobileagent.core.model.McpToolDescriptor
import com.mobileagent.core.model.ProviderConfig
import com.mobileagent.core.security.Redactor

/** Transport contract for any OpenAI-compatible chat endpoint. */
interface LlmClient {
    suspend fun chat(
        config: ProviderConfig,
        apiKey: String?,
        request: ChatRequest,
        onDelta: (String) -> Unit = {},
    ): ChatResponse

    suspend fun listModels(config: ProviderConfig, apiKey: String?): List<String>

    suspend fun probe(
        config: ProviderConfig,
        apiKey: String?,
        includeVision: Boolean = false,
        includeToolCalling: Boolean = true,
    ): com.mobileagent.core.model.ModelCapabilityReport
}

/** Helper used by orchestration code to decide whether a provider can actually be used. */
fun ProviderConfig.isUsable(): Boolean = enabled && baseUrl.isNotBlank() && modelId.isNotBlank()

fun LlmException.describe(): String = when {
    httpStatus == 401 || httpStatus == 403 -> "Authentication failed ($httpStatus). Check the API key for this provider."
    httpStatus == 404 -> "Endpoint or model not found (404). Check the base URL and model id."
    httpStatus == 429 -> "Rate limited by the provider (429). Wait and retry, or choose another model."
    httpStatus != null && httpStatus >= 500 -> "Provider error ($httpStatus). The service is unavailable right now."
    else -> message ?: "Unknown model error"
}

/**
 * Chinese, actionable explanation shown in the UI. The technical detail is appended separately by
 * the caller so the user can copy it for support without exposing credentials.
 */
fun LlmException.userMessage(): String {
    val prefix = when (kind) {
        LlmErrorKind.CONFIG -> "模型配置不完整"
        LlmErrorKind.AUTH -> "API Key 无效或已过期，请在「我的 > 模型」中重新填写"
        LlmErrorKind.FORBIDDEN -> "当前 API Key 没有访问该模型的权限"
        LlmErrorKind.NOT_FOUND -> "接口地址或模型 ID 不存在，请检查 Base URL 与模型名称"
        LlmErrorKind.RATE_LIMIT -> "请求过于频繁或额度不足，请稍后重试"
        LlmErrorKind.BAD_REQUEST -> "请求参数或协议不被服务端接受"
        LlmErrorKind.SERVER -> "模型服务暂时不可用，请稍后重试"
        LlmErrorKind.NETWORK -> "网络连接失败，请检查网络、代理或 DNS"
        LlmErrorKind.TIMEOUT -> "请求超时，模型没有在限定时间内响应"
        LlmErrorKind.STREAM -> "流式响应解析失败"
        LlmErrorKind.EMPTY -> "模型返回了空内容"
        LlmErrorKind.UNKNOWN -> "模型请求失败"
    }
    val detail = technical?.takeIf { it.isNotBlank() }?.let { Redactor.redact(it).take(300) }
        ?: message?.take(300)
    return if (detail.isNullOrBlank() || prefix.contains(detail)) prefix else "$prefix：$detail"
}

/** A tool descriptor coming from MCP needs the same shape as a local tool when given to the model. */
fun McpToolDescriptor.toLlmSchema(): com.mobileagent.core.model.LlmToolSpec = com.mobileagent.core.model.LlmToolSpec(
    name = name,
    description = description.ifBlank { name },
    parameters = inputSchema ?: kotlinx.serialization.json.JsonObject(emptyMap()),
)
