package com.mobileagent.core.llm

import com.mobileagent.core.model.Capability
import com.mobileagent.core.model.ChatRequest
import com.mobileagent.core.model.ChatResponse
import com.mobileagent.core.model.JsonCodec
import com.mobileagent.core.model.LlmErrorKind
import com.mobileagent.core.model.LlmException
import com.mobileagent.core.model.LlmMessage
import com.mobileagent.core.model.LlmToolCall
import com.mobileagent.core.model.LlmToolSpec
import com.mobileagent.core.model.ModelCapabilityReport
import com.mobileagent.core.model.ProviderConfig
import com.mobileagent.core.model.Usage
import com.mobileagent.core.security.Redactor
import com.mobileagent.core.tools.ToolNames
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

/**
 * OpenAI-compatible client used by both plain chat and the agent harness.
 *
 * Important protocol details handled here:
 * - `apiPath` is explicit; DeepSeek uses base `https://api.deepseek.com` + `/chat/completions`.
 * - function names are sanitized by the registry; this client never sends dotted names.
 * - streaming supports multi-line SSE events, comments, `[DONE]`, error frames and JSON fallback.
 * - an empty answer is an explicit error, never a silent hang.
 * - provider error bodies are surfaced (redacted, truncated) for diagnostics.
 */
class OpenAiCompatibleClient(
    private val httpClient: OkHttpClient = defaultClient(),
) : LlmClient {

    override suspend fun chat(
        config: ProviderConfig,
        apiKey: String?,
        request: ChatRequest,
        onDelta: (String) -> Unit,
    ): ChatResponse = withContext(Dispatchers.IO) {
        validateConfig(config)
        val streaming = request.stream && config.streaming
        val body = buildRequestBody(config, request, streaming)
        val url = chatUrl(config)
        val builder = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody(JSON_MEDIA))
            .header("Accept", if (streaming) "text/event-stream" else "application/json")
            .header("Content-Type", "application/json")
        if (!apiKey.isNullOrBlank()) builder.header("Authorization", "Bearer ${apiKey.trim()}")
        val started = System.currentTimeMillis()

        val response = try {
            clientFor(streaming).newCall(builder.build()).execute()
        } catch (e: UnknownHostException) {
            throw LlmException(
                "无法解析服务器域名，请检查网络或 Base URL。",
                kind = LlmErrorKind.NETWORK,
                technical = e.message,
                cause = e,
            )
        } catch (e: SocketTimeoutException) {
            throw LlmException(
                "请求超时，服务器没有在设定时间内响应。",
                kind = LlmErrorKind.TIMEOUT,
                retryable = true,
                technical = e.message,
                cause = e,
            )
        } catch (e: SSLException) {
            throw LlmException(
                "TLS 握手失败，请检查网络代理或证书。",
                kind = LlmErrorKind.NETWORK,
                technical = e.message,
                cause = e,
            )
        } catch (e: Exception) {
            throw LlmException(
                "网络请求失败：${e.message ?: e::class.java.simpleName}",
                kind = LlmErrorKind.NETWORK,
                technical = e.message,
                cause = e,
            )
        }

        response.use { resp ->
            if (!resp.isSuccessful) {
                val raw = runCatching { resp.body?.string().orEmpty() }.getOrDefault("")
                throw LlmException(
                    providerErrorMessage(resp.code, raw),
                    kind = LlmException.kindForStatus(resp.code),
                    httpStatus = resp.code,
                    retryable = resp.code == 429 || resp.code >= 500,
                    technical = Redactor.redact(raw.take(1_200)),
                )
            }

            val contentType = resp.header("Content-Type").orEmpty()
            val parsed = if (streaming && contentType.contains("text/event-stream")) {
                parseStream(resp, onDelta, started)
            } else {
                parseJsonBody(resp.body?.string().orEmpty(), started)
            }

            if (parsed.content.isBlank() && parsed.toolCalls.isEmpty()) {
                throw LlmException(
                    "模型返回了空内容" + (parsed.finishReason?.let { "（finish_reason=$it）" } ?: "") + "。",
                    kind = LlmErrorKind.EMPTY,
                    technical = "empty content, finish=${parsed.finishReason}",
                )
            }
            parsed
        }
    }

    override suspend fun listModels(config: ProviderConfig, apiKey: String?): List<String> = withContext(Dispatchers.IO) {
        validateConfig(config)
        val requestBuilder = Request.Builder().url(modelsUrl(config)).get()
        if (!apiKey.isNullOrBlank()) requestBuilder.header("Authorization", "Bearer ${apiKey.trim()}")
        httpClient.newCall(requestBuilder.build()).execute().use { response ->
            val raw = runCatching { response.body?.string().orEmpty() }.getOrDefault("")
            if (!response.isSuccessful) {
                throw LlmException(
                    providerErrorMessage(response.code, raw),
                    kind = LlmException.kindForStatus(response.code),
                    httpStatus = response.code,
                    technical = Redactor.redact(raw.take(800)),
                )
            }
            val root = runCatching { JsonCodec.json.parseToJsonElement(raw).jsonObject }.getOrNull()
                ?: return@use emptyList()
            root["data"]?.let { data ->
                runCatching { data.jsonArray }.getOrNull().orEmpty()
                    .mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.content }
            }.orEmpty()
        }
    }

    override suspend fun probe(
        config: ProviderConfig,
        apiKey: String?,
        includeVision: Boolean,
        includeToolCalling: Boolean,
    ): ModelCapabilityReport {
        val verified = mutableSetOf<Capability>()
        val unverified = mutableSetOf(
            Capability.TEXT,
            Capability.TOOL_CALLING,
            Capability.VISION,
            Capability.STREAMING,
            Capability.STRUCTURED_OUTPUT,
            Capability.REASONING,
            Capability.EMBEDDING,
        )
        val notes = StringBuilder()

        // 1) real non-stream text answer
        try {
            val response = chat(
                config = config,
                apiKey = apiKey,
                request = ChatRequest(
                    messages = listOf(LlmMessage.user("只回复两个字符：OK")),
                    maxTokens = 16,
                    stream = false,
                ),
            )
            if (response.content.isNotBlank()) {
                verified += Capability.TEXT
                unverified -= Capability.TEXT
            }
        } catch (e: LlmException) {
            notes.append("文本测试失败：").append(friendly(e)).append("；")
        }

        // 2) streaming deltas
        try {
            var sawDelta = false
            chat(
                config = config,
                apiKey = apiKey,
                request = ChatRequest(
                    messages = listOf(LlmMessage.user("请逐字输出：流式测试成功")),
                    maxTokens = 32,
                    stream = true,
                ),
                onDelta = { sawDelta = true },
            )
            if (sawDelta) {
                verified += Capability.STREAMING
                unverified -= Capability.STREAMING
            } else {
                notes.append("服务端未返回流式增量；")
            }
        } catch (e: LlmException) {
            notes.append("流式测试失败：").append(friendly(e)).append("；")
        }

        // 3) tool calling (only when requested)
        if (includeToolCalling) {
            try {
                val response = chat(
                    config = config,
                    apiKey = apiKey,
                    request = ChatRequest(
                        messages = listOf(LlmMessage.user("调用 ping 工具，参数 value 为 ok。不要用文字回答。")),
                        tools = listOf(
                            LlmToolSpec(
                                name = "ping",
                                description = "Echo a value",
                                parameters = buildJsonObject {
                                    put("type", JsonPrimitive("object"))
                                    put("properties", buildJsonObject {
                                        put("value", buildJsonObject { put("type", JsonPrimitive("string")) })
                                    })
                                    put("required", buildJsonArray { add(JsonPrimitive("value")) })
                                },
                            ),
                        ),
                        maxTokens = 128,
                        stream = false,
                    ),
                )
                if (response.toolCalls.isNotEmpty()) {
                    verified += Capability.TOOL_CALLING
                    unverified -= Capability.TOOL_CALLING
                } else {
                    notes.append("模型未返回工具调用；")
                }
            } catch (e: LlmException) {
                notes.append("工具调用测试失败：").append(friendly(e)).append("；")
            }
        }

        if (config.vision) {
            notes.append("视觉能力未自动认证（需要真实图片测试）；")
        }

        return ModelCapabilityReport(
            providerId = config.id,
            modelId = config.modelId,
            verified = verified,
            unverified = unverified,
            notes = notes.toString().trim(),
            checkedAt = System.currentTimeMillis(),
        )
    }

    // --------------------------------------------------------------- URL / body

    private fun validateConfig(config: ProviderConfig) {
        if (config.baseUrl.isBlank()) {
            throw LlmException("尚未填写 API 地址。", kind = LlmErrorKind.CONFIG)
        }
        if (!config.baseUrl.startsWith("http://") && !config.baseUrl.startsWith("https://")) {
            throw LlmException("API 地址必须以 http:// 或 https:// 开头。", kind = LlmErrorKind.CONFIG)
        }
        if (config.modelId.isBlank()) {
            throw LlmException("尚未填写模型 ID。", kind = LlmErrorKind.CONFIG)
        }
    }

    private fun chatUrl(config: ProviderConfig): String {
        val base = config.baseUrl.trim().trimEnd('/')
        if (base.endsWith("/chat/completions")) return base
        val rawPath = config.apiPath.ifBlank { "/chat/completions" }
        val path = if (rawPath.startsWith("/")) rawPath else "/$rawPath"
        return if (base.endsWith("/v1") && path.startsWith("/v1/")) {
            base + path.removePrefix("/v1")
        } else {
            base + path
        }
    }

    private fun modelsUrl(config: ProviderConfig): String {
        val base = config.baseUrl.trim().trimEnd('/')
        return if (base.endsWith("/v1")) "$base/models" else "$base/v1/models"
    }

    private fun buildRequestBody(config: ProviderConfig, request: ChatRequest, streaming: Boolean): JsonObject =
        buildJsonObject {
            put("model", JsonPrimitive(config.modelId.trim()))
            put("messages", JsonArray(request.messages.map { it.toJson() }))
            if (request.tools.isNotEmpty()) {
                put("tools", JsonArray(request.tools.map { tool ->
                    buildJsonObject {
                        put("type", JsonPrimitive("function"))
                        put("function", buildJsonObject {
                            put("name", JsonPrimitive(ToolNames.wireName(tool.name).take(64)))
                            put("description", JsonPrimitive(tool.description))
                            put("parameters", tool.parameters)
                        })
                    }
                }))
                request.forceToolName?.let { force ->
                    put("tool_choice", buildJsonObject {
                        put("type", JsonPrimitive("function"))
                        put("function", buildJsonObject {
                            put("name", JsonPrimitive(ToolNames.wireName(force)))
                        })
                    })
                }
            }
            val maxTokens = request.maxTokens ?: config.maxOutputTokens
            if (maxTokens > 0) put("max_tokens", JsonPrimitive(maxTokens))
            (request.temperature ?: config.temperature).takeIf { it >= 0 }?.let {
                put("temperature", JsonPrimitive(it))
            }
            put("stream", JsonPrimitive(streaming))
            // DeepSeek enables thinking by default; plain chat should answer immediately and use
            // the configured temperature. Agent requests keep thinking available for tool use.
            val deepSeek = config.presetId == "deepseek" || config.baseUrl.contains("deepseek.com")
            if (deepSeek && request.tools.isEmpty()) {
                put("thinking", buildJsonObject { put("type", JsonPrimitive("disabled")) })
            }
        }

    private fun LlmMessage.toJson(): JsonObject = buildJsonObject {
        put("role", JsonPrimitive(role))
        if (images.isEmpty()) {
            content?.let { put("content", JsonPrimitive(it)) }
        } else {
            val parts = mutableListOf<JsonElement>()
            content?.takeIf { it.isNotBlank() }?.let { text ->
                parts += buildJsonObject {
                    put("type", JsonPrimitive("text"))
                    put("text", JsonPrimitive(text))
                }
            }
            images.forEach { image ->
                parts += buildJsonObject {
                    put("type", JsonPrimitive("image_url"))
                    put("image_url", buildJsonObject { put("url", JsonPrimitive(image)) })
                }
            }
            put("content", JsonArray(parts))
        }
        if (toolCalls.isNotEmpty()) {
            put("tool_calls", JsonArray(toolCalls.map { call ->
                buildJsonObject {
                    put("id", JsonPrimitive(call.id))
                    put("type", JsonPrimitive("function"))
                    put("function", buildJsonObject {
                        put("name", JsonPrimitive(ToolNames.wireName(call.name)))
                        put("arguments", JsonPrimitive(call.argumentsJson))
                    })
                }
            }))
        }
        toolCallId?.let { put("tool_call_id", JsonPrimitive(it)) }
        name?.let { put("name", JsonPrimitive(ToolNames.wireName(it))) }
    }

    // ------------------------------------------------------------------ streaming

    private fun parseStream(response: Response, onDelta: (String) -> Unit, started: Long): ChatResponse {
        val content = StringBuilder()
        val toolAccumulator = LinkedHashMap<Int, ToolCallBuilder>()
        var usage: Usage? = null
        var finishReason: String? = null
        var sawDelta = false
        var sawDone = false
        val eventData = StringBuilder()

        val source = response.body?.source() ?: throw LlmException(
            "服务器返回了空的响应体。",
            kind = LlmErrorKind.STREAM,
        )

        fun flush() {
            if (eventData.isEmpty()) return
            val payload = eventData.toString().trim()
            eventData.setLength(0)
            if (payload.isEmpty()) return
            if (payload == "[DONE]") {
                sawDone = true
                return
            }
            val obj = runCatching { JsonCodec.json.parseToJsonElement(payload).jsonObject }.getOrNull() ?: return
            obj["error"]?.let { error ->
                val errObj = runCatching { error.jsonObject }.getOrNull()
                val message = errObj?.get("message")?.jsonPrimitive?.content ?: error.toString()
                val code = errObj?.get("code")?.jsonPrimitive?.intOrNull
                throw LlmException(
                    "模型服务返回错误：$message",
                    kind = if (code == 401 || code == 403) LlmErrorKind.AUTH else LlmErrorKind.BAD_REQUEST,
                    technical = Redactor.redact(payload.take(800)),
                )
            }
            obj["usage"]?.let { usage = parseUsage(it) }
            val choices = obj["choices"]?.let { runCatching { it.jsonArray }.getOrNull() }.orEmpty()
            for (choiceElement in choices) {
                val choice = runCatching { choiceElement.jsonObject }.getOrNull() ?: continue
                choice["finish_reason"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content?.let { finishReason = it }
                val delta = choice["delta"]?.let { runCatching { it.jsonObject }.getOrNull() }
                    ?: choice["message"]?.let { runCatching { it.jsonObject }.getOrNull() }
                    ?: continue

                extractText(delta["content"])?.let { piece ->
                    if (piece.isNotEmpty()) {
                        content.append(piece)
                        sawDelta = true
                        onDelta(piece)
                    }
                }

                delta["tool_calls"]?.let { callsElement ->
                    val calls = runCatching { callsElement.jsonArray }.getOrNull().orEmpty()
                    calls.forEach { callElement ->
                        val call = runCatching { callElement.jsonObject }.getOrNull() ?: return@forEach
                        val index = call["index"]?.jsonPrimitive?.intOrNull ?: 0
                        val builder = toolAccumulator.getOrPut(index) { ToolCallBuilder() }
                        call["id"]?.jsonPrimitive?.content?.let { if (it.isNotBlank()) builder.id = it }
                        call["function"]?.let { fnElement ->
                            val fn = runCatching { fnElement.jsonObject }.getOrNull() ?: return@let
                            fn["name"]?.jsonPrimitive?.content?.let { if (it.isNotBlank()) builder.name = it }
                            fn["arguments"]?.jsonPrimitive?.content?.let { builder.arguments.append(it) }
                        }
                    }
                }
            }
        }

        while (true) {
            val line = source.readUtf8Line() ?: break
            when {
                line.isBlank() -> flush()
                line.startsWith(":") -> Unit
                line.startsWith("data:") -> {
                    val chunk = line.removePrefix("data:").trim()
                    if (chunk == "[DONE]") {
                        flush()
                        sawDone = true
                        break
                    }
                    if (eventData.isNotEmpty()) eventData.append('\n')
                    eventData.append(chunk)
                }
                line.startsWith("{") -> {
                    if (eventData.isNotEmpty()) eventData.append('\n')
                    eventData.append(line.trim())
                }
                else -> Unit
            }
            if (sawDone) break
        }
        flush()

        val toolCalls = toolAccumulator.values.filter { it.name.isNotBlank() }.map { it.build() }

        if (content.isEmpty() && toolCalls.isEmpty() && !sawDone) {
            throw LlmException(
                "流式响应提前中断，没有收到完整内容。",
                kind = LlmErrorKind.STREAM,
                technical = "finish=$finishReason",
            )
        }

        return ChatResponse(
            content = content.toString(),
            toolCalls = toolCalls,
            usage = usage,
            finishReason = finishReason,
            latencyMs = System.currentTimeMillis() - started,
            streamed = sawDelta,
        )
    }

    // ---------------------------------------------------------------- non-stream

    private fun parseJsonBody(raw: String, started: Long): ChatResponse {
        val root = runCatching { JsonCodec.json.parseToJsonElement(raw).jsonObject }.getOrElse {
            throw LlmException(
                "无法解析模型响应：${Redactor.redact(raw.take(300))}",
                kind = LlmErrorKind.BAD_REQUEST,
                technical = raw.take(800),
            )
        }
        root["error"]?.let { error ->
            val errObj = runCatching { error.jsonObject }.getOrNull()
            val message = errObj?.get("message")?.jsonPrimitive?.content ?: error.toString()
            throw LlmException("模型服务返回错误：$message", kind = LlmErrorKind.BAD_REQUEST)
        }
        val choice = root["choices"]?.let { runCatching { it.jsonArray.firstOrNull()?.jsonObject }.getOrNull() }
            ?: throw LlmException(
                "模型响应中没有 choices：${raw.take(300)}",
                kind = LlmErrorKind.BAD_REQUEST,
            )
        val message = choice["message"]?.let { runCatching { it.jsonObject }.getOrNull() }
        val text = extractText(message?.get("content")).orEmpty()
        val toolCalls = message?.get("tool_calls")?.let { element ->
            runCatching {
                element.jsonArray.mapNotNull { item ->
                    val obj = item.jsonObject
                    val fn = obj["function"]?.jsonObject ?: return@mapNotNull null
                    val name = fn["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
                    LlmToolCall(
                        id = obj["id"]?.jsonPrimitive?.content ?: "call_${System.nanoTime()}",
                        name = name,
                        argumentsJson = fn["arguments"]?.jsonPrimitive?.content ?: "{}",
                    )
                }
            }.getOrDefault(emptyList())
        }.orEmpty()

        return ChatResponse(
            content = text,
            toolCalls = toolCalls,
            usage = root["usage"]?.let { parseUsage(it) },
            finishReason = choice["finish_reason"]?.jsonPrimitive?.content,
            latencyMs = System.currentTimeMillis() - started,
            streamed = false,
        )
    }

    private fun extractText(element: JsonElement?): String? {
        if (element == null || element is JsonNull) return null
        val primitive = element as? JsonPrimitive
        if (primitive != null) return primitive.content.takeIf { it != "null" }
        val array = runCatching { element.jsonArray }.getOrNull() ?: return null
        return array.mapNotNull { item ->
            val obj = runCatching { item.jsonObject }.getOrNull() ?: return@mapNotNull null
            when (obj["type"]?.jsonPrimitive?.content) {
                "text", null -> obj["text"]?.jsonPrimitive?.content
                else -> null
            }
        }.joinToString("").takeIf { it.isNotEmpty() }
    }

    private fun parseUsage(element: JsonElement): Usage? {
        val obj = runCatching { element.jsonObject }.getOrNull() ?: return null
        val input = obj["prompt_tokens"]?.jsonPrimitive?.intOrNull
        val output = obj["completion_tokens"]?.jsonPrimitive?.intOrNull
        val cached = obj["prompt_tokens_details"]?.let { details ->
            runCatching { details.jsonObject["cached_tokens"]?.jsonPrimitive?.intOrNull }.getOrNull()
        }
        if (input == null && output == null) return null
        return Usage(input, output, cached)
    }

    private fun providerErrorMessage(status: Int, raw: String): String {
        val detail = runCatching {
            val obj = JsonCodec.json.parseToJsonElement(raw).jsonObject
            val err = obj["error"]?.let { runCatching { it.jsonObject }.getOrNull() }
            err?.get("message")?.jsonPrimitive?.content
                ?: obj["message"]?.jsonPrimitive?.content
                ?: raw
        }.getOrElse { raw }
        val prefix = when (status) {
            401 -> "API Key 无效或未授权（401）"
            403 -> "没有访问该模型的权限（403）"
            404 -> "接口地址或模型 ID 不存在（404）"
            429 -> "请求过于频繁或额度不足（429）"
            400 -> "请求参数或协议不兼容（400）"
            in 500..599 -> "模型服务暂时不可用（$status）"
            else -> "模型请求失败（HTTP $status）"
        }
        return "$prefix：${Redactor.redact(detail.take(500))}"
    }

    private fun friendly(error: LlmException): String = when (error.kind) {
        LlmErrorKind.AUTH -> "API Key 无效"
        LlmErrorKind.FORBIDDEN -> "无权限"
        LlmErrorKind.NOT_FOUND -> "地址或模型不存在"
        LlmErrorKind.RATE_LIMIT -> "限流或额度不足"
        LlmErrorKind.TIMEOUT -> "超时"
        LlmErrorKind.NETWORK -> "网络错误"
        LlmErrorKind.CONFIG -> "配置不完整"
        else -> error.message ?: "未知错误"
    }

    private fun clientFor(streaming: Boolean): OkHttpClient =
        httpClient.newBuilder()
            .apply {
                if (streaming) {
                    callTimeout(0, TimeUnit.MILLISECONDS)
                    readTimeout(300, TimeUnit.SECONDS)
                }
            }
            .build()

    private class ToolCallBuilder {
        var id: String = "call_${System.nanoTime()}"
        var name: String = ""
        val arguments = StringBuilder()

        fun build(): LlmToolCall = LlmToolCall(
            id = id,
            name = name,
            argumentsJson = arguments.toString().ifBlank { "{}" },
        )
    }

    companion object {
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .callTimeout(200, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }
}
