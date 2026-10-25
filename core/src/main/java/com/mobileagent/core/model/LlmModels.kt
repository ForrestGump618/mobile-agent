package com.mobileagent.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** A configured OpenAI-compatible provider endpoint plus the model selected on it. */
data class ProviderConfig(
    val id: String,
    val name: String,
    val baseUrl: String,
    val modelId: String,
    val apiKeyId: String? = null,
    val timeoutMs: Long = 60_000L,
    val streaming: Boolean = true,
    val toolCalling: Boolean = true,
    val vision: Boolean = false,
    val reasoning: Boolean = false,
    val structuredOutput: Boolean = false,
    val contextWindow: Int = 32_768,
    val maxOutputTokens: Int = 2_048,
    val temperature: Double = 0.2,
    val enabled: Boolean = true,
    val presetId: String? = null,
    val apiPath: String = "/chat/completions",
    val lastError: String? = null,
    val lastTestedAt: Long? = null,
    val verifiedCapabilities: Set<Capability> = emptySet(),
)

data class LlmToolSpec(
    val name: String,
    val description: String,
    val parameters: JsonObject,
)

@Serializable
data class LlmToolCall(
    val id: String,
    val name: String,
    val argumentsJson: String,
)

@Serializable
data class LlmMessage(
    val role: String,
    val content: String? = null,
    val toolCalls: List<LlmToolCall> = emptyList(),
    val toolCallId: String? = null,
    val name: String? = null,
    /** Data URLs (data:image/jpeg;base64,...) for vision models. */
    val images: List<String> = emptyList(),
) {
    companion object {
        fun system(text: String) = LlmMessage(role = "system", content = text)
        fun user(text: String) = LlmMessage(role = "user", content = text)
        fun assistant(text: String) = LlmMessage(role = "assistant", content = text)
        fun tool(callId: String, name: String, text: String) =
            LlmMessage(role = "tool", content = text, toolCallId = callId, name = name)
    }
}

data class Usage(
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val cachedTokens: Int? = null,
) {
    val total: Int? get() = if (inputTokens == null && outputTokens == null) null else (inputTokens ?: 0) + (outputTokens ?: 0)
}

data class ChatRequest(
    val messages: List<LlmMessage>,
    val tools: List<LlmToolSpec> = emptyList(),
    val temperature: Double? = null,
    val maxTokens: Int? = null,
    val stream: Boolean = true,
    val forceToolName: String? = null,
)

data class ChatResponse(
    val content: String,
    val toolCalls: List<LlmToolCall>,
    val usage: Usage?,
    val finishReason: String?,
    val latencyMs: Long,
    val streamed: Boolean,
)

enum class LlmErrorKind {
    CONFIG, AUTH, FORBIDDEN, NOT_FOUND, RATE_LIMIT, BAD_REQUEST,
    SERVER, NETWORK, TIMEOUT, STREAM, EMPTY, UNKNOWN,
}

class LlmException(
    message: String,
    val kind: LlmErrorKind = LlmErrorKind.UNKNOWN,
    val httpStatus: Int? = null,
    val retryable: Boolean = false,
    val technical: String? = null,
    cause: Throwable? = null,
) : Exception(message, cause) {
    companion object {
        fun kindForStatus(status: Int): LlmErrorKind = when {
            status == 401 -> LlmErrorKind.AUTH
            status == 403 -> LlmErrorKind.FORBIDDEN
            status == 404 -> LlmErrorKind.NOT_FOUND
            status == 429 -> LlmErrorKind.RATE_LIMIT
            status == 408 -> LlmErrorKind.TIMEOUT
            status in 400..499 -> LlmErrorKind.BAD_REQUEST
            status >= 500 -> LlmErrorKind.SERVER
            else -> LlmErrorKind.UNKNOWN
        }
    }
}

/** Capabilities are recorded from observed behaviour and explicit configuration, never inferred from the name. */
enum class Capability { TEXT, TOOL_CALLING, VISION, STRUCTURED_OUTPUT, STREAMING, REASONING, EMBEDDING }

@Serializable
data class ModelCapabilityReport(
    val providerId: String,
    val modelId: String,
    val verified: Set<Capability>,
    val unverified: Set<Capability>,
    val notes: String,
    val checkedAt: Long,
)

/** Roles that can be bound to different providers/models. */
enum class ModelRole { CHAT, PLANNING, EXECUTION, VISION, SUMMARY, EMBEDDING }

data class ModelBinding(
    val role: ModelRole,
    val providerId: String,
    val modelId: String,
)
