package com.mobileagent.core.llm

import com.mobileagent.core.model.ChatRequest
import com.mobileagent.core.model.LlmErrorKind
import com.mobileagent.core.model.LlmException
import com.mobileagent.core.model.LlmMessage
import com.mobileagent.core.model.LlmToolSpec
import com.mobileagent.core.model.ProviderConfig
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class OpenAiCompatibleClientTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun config(model: String = "deepseek-flash") = ProviderConfig(
        id = "p1",
        name = "DeepSeek",
        baseUrl = server.url("").toString(),
        modelId = model,
        apiPath = "/chat/completions",
        timeoutMs = 10_000,
        temperature = 0.0,
        maxOutputTokens = 128,
        streaming = true,
        presetId = "deepseek",
    )

    private fun jsonResponse(body: String) = MockResponse()
        .addHeader("Content-Type", "application/json")
        .setBody(body)

    @Test
    fun `deepseek shaped request uses api path auth and no stream_options`() = runBlocking {
        server.enqueue(
            jsonResponse(
                """{"id":"1","choices":[{"index":0,"message":{"role":"assistant","content":"你好"},"finish_reason":"stop"}],"usage":{"prompt_tokens":10,"completion_tokens":3}}""",
            ),
        )

        val client = OpenAiCompatibleClient()
        val response = client.chat(
            config = config(),
            apiKey = "sk-test-key",
            request = ChatRequest(
                messages = listOf(LlmMessage.user("你好")),
                stream = false,
                maxTokens = 64,
            ),
        )

        assertEquals("你好", response.content)
        assertEquals(3, response.usage?.outputTokens)

        val request = server.takeRequest()
        assertEquals("/chat/completions", request.path)
        assertEquals("Bearer sk-test-key", request.getHeader("Authorization"))
        val body = JsonPrimitive("x").let {
            com.mobileagent.core.model.JsonCodec.json.parseToJsonElement(request.body.readUtf8()).jsonObject
        }
        assertEquals("deepseek-flash", body["model"]?.jsonPrimitive?.content)
        assertFalse(body.containsKey("stream_options"))
        assertFalse(body.containsKey("tools"))
        assertEquals(64, body["max_tokens"]?.jsonPrimitive?.content?.toInt())
        assertEquals("disabled", body["thinking"]?.jsonObject?.get("type")?.jsonPrimitive?.content)
    }

    @Test
    fun `streaming sse is assembled and deltas are reported`() = runBlocking {
        val sse = buildString {
            append("data: {\"choices\":[{\"delta\":{\"content\":\"你\"}}]}\n\n")
            append("data: {\"choices\":[{\"delta\":{\"content\":\"好\"},\"finish_reason\":\"stop\"}],")
            append("\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":2}}\n\n")
            append("data: [DONE]\n\n")
        }
        server.enqueue(
            MockResponse()
                .addHeader("Content-Type", "text/event-stream")
                .setChunkedBody(sse, 17),
        )

        val deltas = StringBuilder()
        val response = OpenAiCompatibleClient().chat(
            config = config(),
            apiKey = "k",
            request = ChatRequest(messages = listOf(LlmMessage.user("hi")), stream = true),
            onDelta = { deltas.append(it) },
        )

        assertEquals("你好", response.content)
        assertEquals("你好", deltas.toString())
        assertTrue(response.streamed)
        assertEquals("stop", response.finishReason)
        assertEquals(2, response.usage?.outputTokens)
    }

    @Test
    fun `tool names are sanitized on the wire`() = runBlocking {
        server.enqueue(
            jsonResponse(
                """{"choices":[{"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}]}""",
            ),
        )
        OpenAiCompatibleClient().chat(
            config = config(),
            apiKey = "k",
            request = ChatRequest(
                messages = listOf(LlmMessage.user("read")),
                tools = listOf(
                    LlmToolSpec(
                        name = "file.read",
                        description = "read",
                        parameters = buildJsonObject { put("type", JsonPrimitive("object")) },
                    ),
                ),
                stream = false,
            ),
        )
        val body = com.mobileagent.core.model.JsonCodec.json
            .parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        val name = body["tools"]?.jsonArray?.first()?.jsonObject
            ?.get("function")?.jsonObject?.get("name")?.jsonPrimitive?.content
        assertEquals("file_read", name)
        assertFalse(body.containsKey("thinking"))
    }

    @Test
    fun `http 401 becomes an auth error with technical detail`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(401)
                .addHeader("Content-Type", "application/json")
                .setBody("""{"error":{"message":"Authentication Fails, Your api key is invalid"}}"""),
        )
        val error = runCatching {
            OpenAiCompatibleClient().chat(
                config = config(),
                apiKey = "bad",
                request = ChatRequest(messages = listOf(LlmMessage.user("hi")), stream = false),
            )
        }.exceptionOrNull()
        assertTrue(error is LlmException)
        val llm = error as LlmException
        assertEquals(LlmErrorKind.AUTH, llm.kind)
        assertEquals(401, llm.httpStatus)
        assertTrue(llm.message.orEmpty().contains("401"))
        assertTrue(llm.userMessage().contains("API Key"))
    }

    @Test
    fun `empty answer is an explicit error`() = runBlocking {
        server.enqueue(
            jsonResponse("""{"choices":[{"message":{"role":"assistant","content":""},"finish_reason":"stop"}]}"""),
        )
        val error = runCatching {
            OpenAiCompatibleClient().chat(
                config = config(),
                apiKey = "k",
                request = ChatRequest(messages = listOf(LlmMessage.user("hi")), stream = false),
            )
        }.exceptionOrNull()
        assertTrue(error is LlmException)
        assertEquals(LlmErrorKind.EMPTY, (error as LlmException).kind)
    }

    @Test
    fun `base url with v1 and api path are joined without duplication`() = runBlocking {
        server.enqueue(
            jsonResponse("""{"choices":[{"message":{"content":"ok"},"finish_reason":"stop"}]}"""),
        )
        val base = server.url("/v1").toString().trimEnd('/')
        OpenAiCompatibleClient().chat(
            config = config().copy(baseUrl = base),
            apiKey = "k",
            request = ChatRequest(messages = listOf(LlmMessage.user("hi")), stream = false),
        )
        assertEquals("/v1/chat/completions", server.takeRequest().path)
    }
}
