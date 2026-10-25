package com.mobileagent.integrations.mcp

import com.mobileagent.core.model.JsonCodec
import com.mobileagent.core.model.McpToolCallOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class McpException(message: String, val code: Int? = null, val httpStatus: Int? = null) : Exception(message)

data class McpInitializeResult(
    val protocolVersion: String,
    val serverName: String,
    val serverVersion: String,
    val instructions: String?,
)

/**
 * MCP client for the Streamable HTTP transport (JSON-RPC over POST with an optional SSE response).
 * It performs the initialize handshake, keeps the session id, lists tools and calls them.
 */
class StreamableHttpMcpClient(
    private val endpoint: String,
    private val authHeaderName: String? = null,
    private val authToken: String? = null,
    private val client: OkHttpClient = defaultClient(),
) {

    private val ids = AtomicInteger(0)
    private var sessionId: String? = null
    private var protocolVersion: String? = null
    private var initialized = false

    suspend fun initialize(): McpInitializeResult = withContext(Dispatchers.IO) {
        if (initialized) {
            return@withContext McpInitializeResult(
                protocolVersion ?: SUPPORTED_VERSIONS.first(),
                "",
                "",
                null,
            )
        }
        val id = ids.incrementAndGet()
        val response = post(
            buildJsonObject {
                put("jsonrpc", JsonPrimitive("2.0"))
                put("id", JsonPrimitive(id))
                put("method", JsonPrimitive("initialize"))
                put("params", buildJsonObject {
                    put("protocolVersion", JsonPrimitive(SUPPORTED_VERSIONS.first()))
                    put("capabilities", buildJsonObject { })
                    put("clientInfo", buildJsonObject {
                        put("name", JsonPrimitive("Mobile Agent"))
                        put("version", JsonPrimitive("0.1.0"))
                    })
                })
            },
        )
        val body = extractJsonRpc(response, id)
        val result = body["result"]?.jsonObject ?: throw McpException(
            "MCP initialize returned no result: ${body.toString().take(300)}",
            httpStatus = response.code,
        )
        val version = result["protocolVersion"]?.jsonPrimitive?.content ?: SUPPORTED_VERSIONS.first()
        protocolVersion = version
        val serverInfo = result["serverInfo"]?.let { runCatching { it.jsonObject }.getOrNull() }
        sendNotification("notifications/initialized", buildJsonObject { })
        initialized = true
        McpInitializeResult(
            protocolVersion = version,
            serverName = serverInfo?.get("name")?.jsonPrimitive?.content.orEmpty(),
            serverVersion = serverInfo?.get("version")?.jsonPrimitive?.content.orEmpty(),
            instructions = result["instructions"]?.jsonPrimitive?.content,
        )
    }

    suspend fun listTools(): List<JsonObject> = withContext(Dispatchers.IO) {
        ensureInitialized()
        var cursor: String? = null
        val collected = mutableListOf<JsonObject>()
        var guard = 0
        do {
            guard++
            val id = ids.incrementAndGet()
            val params = buildJsonObject {
                cursor?.let { put("cursor", JsonPrimitive(it)) }
            }
            val body = extractSafely(id, "tools/list", params)
            val result = body["result"]?.jsonObject ?: break
            val tools = result["tools"]?.let { runCatching { it.jsonArray }.getOrNull() }.orEmpty()
            tools.forEach { element -> runCatching { element.jsonObject }.getOrNull()?.let { collected += it } }
            cursor = result["nextCursor"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content
        } while (!cursor.isNullOrBlank() && guard < 10)
        collected
    }

    suspend fun callTool(name: String, arguments: JsonObject): McpToolCallOutcome = withContext(Dispatchers.IO) {
        ensureInitialized()
        val id = ids.incrementAndGet()
        val body = extractSafely(
            id,
            "tools/call",
            buildJsonObject {
                put("name", JsonPrimitive(name))
                put("arguments", arguments)
            },
        )
        val result = body["result"]?.jsonObject
            ?: throw McpException("MCP tools/call returned no result: ${body.toString().take(300)}")
        val isError = result["isError"]?.jsonPrimitive?.content == "true"
        val text = buildString {
            val content = result["content"]?.let { runCatching { it.jsonArray }.getOrNull() }.orEmpty()
            content.forEachIndexed { index, element ->
                val item = runCatching { element.jsonObject }.getOrNull() ?: return@forEachIndexed
                when (item["type"]?.jsonPrimitive?.content) {
                    "text" -> append(item["text"]?.jsonPrimitive?.content.orEmpty())
                    "image" -> append("[image ${item["mimeType"]?.jsonPrimitive?.content.orEmpty()}]")
                    "resource" -> append(item["resource"]?.jsonObject?.get("text")?.jsonPrimitive?.content.orEmpty())
                    else -> append(item.toString())
                }
                if (index < content.size - 1) append('\n')
            }
        }
        McpToolCallOutcome(
            ok = !isError,
            content = text.ifBlank { result.toString() },
            isError = isError,
            structured = result["structuredContent"]?.let { runCatching { it.jsonObject }.getOrNull() },
        )
    }

    fun close() {
        initialized = false
        sessionId = null
    }

    private suspend fun ensureInitialized() {
        if (!initialized) initialize()
    }

    private fun extractSafely(id: Int, method: String, params: JsonObject): JsonObject {
        val payload = buildJsonObject {
            put("jsonrpc", JsonPrimitive("2.0"))
            put("id", JsonPrimitive(id))
            put("method", JsonPrimitive(method))
            put("params", params)
        }
        val response = post(payload)
        return extractJsonRpc(response, id)
    }

    private fun post(payload: JsonObject): Response {
        val requestBuilder = Request.Builder()
            .url(endpoint)
            .post(payload.toString().toRequestBody(JSON_MEDIA))
            .header("Accept", "application/json, text/event-stream")
            .header("Content-Type", "application/json")
        sessionId?.let { requestBuilder.header("Mcp-Session-Id", it) }
        protocolVersion?.let { requestBuilder.header("MCP-Protocol-Version", it) }
        authHeaderName?.takeIf { it.isNotBlank() }?.let { header ->
            authToken?.takeIf { it.isNotBlank() }?.let { token -> requestBuilder.header(header, token) }
        }
        val response = try {
            client.newCall(requestBuilder.build()).execute()
        } catch (t: Throwable) {
            throw McpException("MCP endpoint unreachable: ${t.message ?: t::class.java.simpleName}")
        }
        response.header("Mcp-Session-Id")?.let { sessionId = it }
        if (!response.isSuccessful) {
            val text = runCatching { response.body?.string().orEmpty() }.getOrDefault("")
            response.close()
            throw McpException(
                "MCP HTTP ${response.code}: ${text.take(400)}",
                httpStatus = response.code,
            )
        }
        return response
    }

    private fun extractJsonRpc(response: Response, expectedId: Int): JsonObject {
        response.use { resp ->
            val contentType = resp.header("Content-Type").orEmpty()
            if (contentType.contains("text/event-stream")) {
                return readSse(resp, expectedId)
            }
            val raw = resp.body?.string().orEmpty()
            if (raw.isBlank()) {
                throw McpException("MCP returned an empty body (HTTP ${resp.code})", httpStatus = resp.code)
            }
            val element = runCatching { JsonCodec.json.parseToJsonElement(raw) }.getOrNull()
                ?: throw McpException("MCP returned invalid JSON: ${raw.take(300)}")
            val obj = runCatching { element.jsonObject }.getOrNull()
                ?: throw McpException("MCP returned a non-object JSON-RPC payload")
            throwIfError(obj)
            val id = obj["id"]?.jsonPrimitive?.intOrNull
            if (id != null && id != expectedId) {
                throw McpException("MCP response id mismatch (expected $expectedId, got $id)")
            }
            return obj
        }
    }

    private fun readSse(response: Response, expectedId: Int): JsonObject {
        val source = response.body?.source() ?: throw McpException("MCP SSE body is empty")
        val data = StringBuilder()
        while (true) {
            val line = source.readUtf8Line() ?: break
            if (line.isBlank()) {
                if (data.isNotEmpty()) {
                    val parsed = runCatching { JsonCodec.json.parseToJsonElement(data.toString()).jsonObject }.getOrNull()
                    data.setLength(0)
                    if (parsed != null) {
                        throwIfError(parsed)
                        val id = parsed["id"]?.jsonPrimitive?.intOrNull
                        if (id == expectedId) return parsed
                    }
                }
                continue
            }
            if (line.startsWith("data:")) {
                if (data.isNotEmpty()) data.append('\n')
                data.append(line.removePrefix("data:").trim())
            }
        }
        throw McpException("MCP SSE stream ended before the response arrived")
    }

    private fun throwIfError(obj: JsonObject) {
        val error = obj["error"] ?: return
        val errObj = runCatching { error.jsonObject }.getOrNull()
        val message = errObj?.get("message")?.jsonPrimitive?.content ?: error.toString()
        val code = errObj?.get("code")?.jsonPrimitive?.intOrNull
        throw McpException("MCP error: $message", code = code)
    }

    private fun sendNotification(method: String, params: JsonObject) {
        val payload = buildJsonObject {
            put("jsonrpc", JsonPrimitive("2.0"))
            put("method", JsonPrimitive(method))
            put("params", params)
        }
        runCatching {
            post(payload).use { it.body?.string() }
        }
    }

    /** Extracts text from a tools/list entry, tolerating schema differences between revisions. */
    fun parseToolDescriptor(element: JsonElement): com.mobileagent.core.model.McpToolDescriptor? {
        val obj = runCatching { element.jsonObject }.getOrNull() ?: return null
        val name = obj["name"]?.jsonPrimitive?.content ?: return null
        val schema = obj["inputSchema"]?.let { runCatching { it.jsonObject }.getOrNull() }
        return com.mobileagent.core.model.McpToolDescriptor(
            name = name,
            description = obj["description"]?.jsonPrimitive?.content.orEmpty(),
            inputSchema = schema,
        )
    }

    companion object {
        val SUPPORTED_VERSIONS = listOf("2025-06-18", "2025-03-26", "2024-11-05")
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .callTimeout(120, TimeUnit.SECONDS)
            .build()
    }
}
