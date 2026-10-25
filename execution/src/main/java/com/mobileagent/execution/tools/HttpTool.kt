package com.mobileagent.execution.tools

import com.mobileagent.core.model.AgentTool
import com.mobileagent.core.model.RiskLevel
import com.mobileagent.core.model.ToolCategory
import com.mobileagent.core.model.ToolContext
import com.mobileagent.core.model.ToolResult
import com.mobileagent.core.model.ToolSpec
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.InputStream
import java.net.URI
import java.util.concurrent.TimeUnit

/** Blocks requests that would turn the agent into a local-network scanner. */
object UrlPolicy {

    fun rejectionReason(url: String): String? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return "Malformed URL"
        val scheme = uri.scheme?.lowercase() ?: return "URL must include http:// or https://"
        if (scheme != "http" && scheme != "https") return "Only http and https are allowed (got $scheme)"
        if (uri.host.isNullOrBlank()) return "URL has no host"

        val host = uri.host.lowercase()
        val isLoopback = host == "localhost" || host == "127.0.0.1" || host == "::1" || host.endsWith(".localhost")
        if (isLoopback) return "Requests to the device itself are blocked"

        if (host.matches(Regex("""^10\.\d+\.\d+\.\d+$"""))) return "Private network addresses are blocked"
        if (host.matches(Regex("""^192\.168\.\d+\.\d+$"""))) return "Private network addresses are blocked"
        if (host.matches(Regex("""^172\.(1[6-9]|2\d|3[01])\.\d+\.\d+$"""))) return "Private network addresses are blocked"
        if (host.matches(Regex("""^169\.254\.\d+\.\d+$"""))) return "Link-local addresses are blocked"
        if (host.endsWith(".local")) return "mDNS names are blocked"
        return null
    }
}

/**
 * HTTP(S) requests with bounded bodies. Useful for public APIs, feeds and downloads of small files.
 */
class HttpTool(
    private val client: OkHttpClient = defaultClient(),
) : AgentTool {

    override val spec: ToolSpec = ToolSpec(
        name = "http.request",
        title = "HTTP request",
        description = "Perform an HTTP(S) request to a public endpoint. Returns status code, content type and up to 200 KB of the body. " +
            "Private/loopback addresses are blocked. Use this for APIs, not for interactive login flows.",
        parametersSchema = Schemas.obj(
            required = listOf("url"),
            props = mapOf(
                "url" to Schemas.str("Absolute http(s) URL", maxLength = 2_000),
                "method" to Schemas.str("HTTP method", enum = listOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD")),
                "headers" to Schemas.objRef("Optional headers object, e.g. {\"Accept\":\"application/json\"}"),
                "body" to Schemas.str("Optional request body (JSON or text)", maxLength = 200_000),
                "timeoutMs" to Schemas.int("Request timeout", 1_000, 120_000),
                "maxBytes" to Schemas.int("Maximum response bytes to return", 1_000, 200_000),
            ),
        ),
        risk = RiskLevel.MEDIUM,
        category = ToolCategory.NETWORK,
        source = "execution",
    )

    override suspend fun execute(context: ToolContext, arguments: JsonObject): ToolResult {
        val startedAt = System.currentTimeMillis()
        val url = arguments["url"]?.jsonPrimitive?.content?.trim().orEmpty()
        UrlPolicy.rejectionReason(url)?.let { return failure(context, spec.name, it, startedAt) }

        val method = (arguments["method"]?.jsonPrimitive?.content ?: "GET").uppercase()
        val timeoutMs = (arguments["timeoutMs"]?.jsonPrimitive?.intOrNull ?: 30_000).coerceIn(1_000, 120_000).toLong()
        val maxBytes = (arguments["maxBytes"]?.jsonPrimitive?.intOrNull ?: 100_000).coerceIn(1_000, 200_000)
        val bodyText = arguments["body"]?.jsonPrimitive?.contentOrNull

        val headers = arguments["headers"]?.let { element ->
            runCatching { element as JsonObject }.getOrNull()
        }
        val contentType = headers?.get("Content-Type")?.jsonPrimitive?.content
            ?: headers?.get("content-type")?.jsonPrimitive?.content
            ?: "application/json"

        val requestBody = when {
            method == "GET" || method == "HEAD" -> null
            bodyText != null -> bodyText.toRequestBody(contentType.toMediaType())
            else -> "".toRequestBody("text/plain".toMediaType())
        }

        val requestBuilder = Request.Builder()
            .url(url)
            .method(method, requestBody)
            .header("User-Agent", "MobileAgent/1.0 (Android)")

        headers?.forEach { (name, value) ->
            if (name.equals("Host", true) || name.equals("Content-Length", true)) return@forEach
            runCatching { requestBuilder.header(name, value.jsonPrimitive.content) }
        }

        val scopedClient = client.newBuilder()
            .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .build()

        return try {
            scopedClient.newCall(requestBuilder.build()).execute().use { response ->
                val (body, truncated) = readBody(response, maxBytes)
                val output = buildJsonObject {
                    put("url", JsonPrimitive(url))
                    put("method", JsonPrimitive(method))
                    put("status", JsonPrimitive(response.code))
                    put("contentType", JsonPrimitive(response.header("Content-Type").orEmpty()))
                    put("body", JsonPrimitive(body))
                    put("truncated", JsonPrimitive(truncated))
                    put("durationMs", JsonPrimitive(System.currentTimeMillis() - startedAt))
                }
                ToolResult(
                    callId = context.callId,
                    toolName = spec.name,
                    ok = response.isSuccessful,
                    output = output,
                    summary = "HTTP ${response.code}, ${body.length} chars" +
                        if (truncated) " (truncated)" else "",
                    error = if (response.isSuccessful) null else "HTTP ${response.code}: ${body.take(300)}",
                    durationMs = System.currentTimeMillis() - startedAt,
                    truncated = truncated,
                )
            }
        } catch (t: Throwable) {
            failure(context, spec.name, "Request failed: ${t.message ?: t::class.java.simpleName}", startedAt)
        }
    }

    private fun readBody(response: Response, limit: Int): Pair<String, Boolean> {
        val stream: InputStream = response.body?.byteStream() ?: return "" to false
        val buffer = ByteArray(8_192)
        val output = java.io.ByteArrayOutputStream()
        var truncated = false
        stream.use {
            while (true) {
                val read = it.read(buffer)
                if (read <= 0) break
                if (output.size() + read > limit) {
                    output.write(buffer, 0, limit - output.size())
                    truncated = true
                    break
                }
                output.write(buffer, 0, read)
            }
        }
        return String(output.toByteArray(), Charsets.UTF_8) to truncated
    }

    companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }
}
