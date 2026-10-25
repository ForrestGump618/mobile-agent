package com.mobileagent.core.llm

import com.mobileagent.core.model.Capability
import com.mobileagent.core.model.ChatRequest
import com.mobileagent.core.model.LlmErrorKind
import com.mobileagent.core.model.LlmException
import com.mobileagent.core.model.LlmMessage
import com.mobileagent.core.model.ProviderConfig

enum class CheckStatus { OK, FAILED, SKIPPED }

data class ConnectionCheck(
    val id: String,
    val status: CheckStatus,
    val technical: String? = null,
)

data class ConnectionTestResult(
    val ok: Boolean,
    val checks: List<ConnectionCheck>,
    val latencyMs: Long,
    val verified: Set<Capability> = emptySet(),
    val technical: String? = null,
)

/**
 * Staged connection test. A 200 response alone is not success: the final step requires a real,
 * non-empty text answer from the selected model. Each stage is reported separately so the UI can
 * tell the user whether the network, the key, the endpoint or the model is wrong.
 */
class ConnectionTester(private val llm: LlmClient) {

    suspend fun test(config: ProviderConfig, apiKey: String?, testStreaming: Boolean = true): ConnectionTestResult {
        val started = System.currentTimeMillis()
        val checks = mutableListOf<ConnectionCheck>()

        val configProblem = when {
            config.baseUrl.isBlank() -> "Base URL 为空"
            !config.baseUrl.startsWith("http") -> "Base URL 必须以 http(s) 开头"
            config.modelId.isBlank() -> "模型 ID 为空"
            apiKey.isNullOrBlank() -> "API Key 为空"
            else -> null
        }
        checks += ConnectionCheck("config", if (configProblem == null) CheckStatus.OK else CheckStatus.FAILED, configProblem)
        if (configProblem != null) {
            return ConnectionTestResult(false, checks, System.currentTimeMillis() - started, technical = configProblem)
        }

        try {
            val response = llm.chat(
                config = config,
                apiKey = apiKey,
                request = ChatRequest(
                    messages = listOf(LlmMessage.user("请只回复：连接成功")),
                    maxTokens = 256,
                    stream = false,
                    temperature = 0.0,
                ),
            )
            val text = response.content.trim()
            checks += ConnectionCheck("network", CheckStatus.OK)
            checks += ConnectionCheck("auth", CheckStatus.OK)
            checks += ConnectionCheck("model", CheckStatus.OK)
            checks += ConnectionCheck(
                "text",
                if (text.isNotEmpty()) CheckStatus.OK else CheckStatus.FAILED,
                if (text.isEmpty()) "HTTP 200 但模型没有返回文本" else "返回 ${text.length} 个字符",
            )
            var verified = setOf(Capability.TEXT)

            if (testStreaming) {
                try {
                    var sawDelta = false
                    llm.chat(
                        config = config,
                        apiKey = apiKey,
                        request = ChatRequest(
                            messages = listOf(LlmMessage.user("请逐字输出：流式正常")),
                            maxTokens = 256,
                            stream = true,
                            temperature = 0.0,
                        ),
                        onDelta = { sawDelta = true },
                    )
                    checks += ConnectionCheck("stream", if (sawDelta) CheckStatus.OK else CheckStatus.FAILED, "未收到增量")
                    if (sawDelta) verified = verified + Capability.STREAMING
                } catch (e: LlmException) {
                    checks += ConnectionCheck("stream", CheckStatus.FAILED, e.technical ?: e.message)
                }
            }

            return ConnectionTestResult(
                ok = text.isNotEmpty(),
                checks = checks,
                latencyMs = System.currentTimeMillis() - started,
                verified = verified,
                technical = if (text.isEmpty()) "empty text" else null,
            )
        } catch (e: LlmException) {
            checks += failedChecks(e)
            return ConnectionTestResult(
                ok = false,
                checks = checks,
                latencyMs = System.currentTimeMillis() - started,
                technical = e.technical ?: e.message,
            )
        }
    }

    /** Maps an error to the stage that actually failed; later stages are marked skipped. */
    private fun failedChecks(error: LlmException): List<ConnectionCheck> = when (error.kind) {
        LlmErrorKind.NETWORK, LlmErrorKind.TIMEOUT -> listOf(
            ConnectionCheck("network", CheckStatus.FAILED, error.technical ?: error.message),
            ConnectionCheck("auth", CheckStatus.SKIPPED),
            ConnectionCheck("model", CheckStatus.SKIPPED),
            ConnectionCheck("text", CheckStatus.SKIPPED),
        )

        LlmErrorKind.AUTH -> listOf(
            ConnectionCheck("network", CheckStatus.OK),
            ConnectionCheck("auth", CheckStatus.FAILED, error.technical ?: error.message),
            ConnectionCheck("model", CheckStatus.SKIPPED),
            ConnectionCheck("text", CheckStatus.SKIPPED),
        )

        LlmErrorKind.FORBIDDEN -> listOf(
            ConnectionCheck("network", CheckStatus.OK),
            ConnectionCheck("auth", CheckStatus.OK),
            ConnectionCheck("model", CheckStatus.FAILED, error.technical ?: error.message),
            ConnectionCheck("text", CheckStatus.SKIPPED),
        )

        LlmErrorKind.NOT_FOUND, LlmErrorKind.RATE_LIMIT, LlmErrorKind.BAD_REQUEST, LlmErrorKind.SERVER -> listOf(
            ConnectionCheck("network", CheckStatus.OK),
            ConnectionCheck("auth", CheckStatus.OK),
            ConnectionCheck("model", CheckStatus.FAILED, error.technical ?: error.message),
            ConnectionCheck("text", CheckStatus.SKIPPED),
        )

        LlmErrorKind.EMPTY -> listOf(
            ConnectionCheck("network", CheckStatus.OK),
            ConnectionCheck("auth", CheckStatus.OK),
            ConnectionCheck("model", CheckStatus.OK),
            ConnectionCheck("text", CheckStatus.FAILED, "模型返回空内容"),
        )

        else -> listOf(
            ConnectionCheck("network", CheckStatus.SKIPPED),
            ConnectionCheck("auth", CheckStatus.SKIPPED),
            ConnectionCheck("model", CheckStatus.FAILED, error.technical ?: error.message),
            ConnectionCheck("text", CheckStatus.SKIPPED),
        )
    }
}
