package com.mobileagent.integrations.mcp

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class StreamableHttpMcpClientTest {

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

    @Test
    fun `initialize lists tools and calls a tool`() = runBlocking {
        server.enqueue(
            MockResponse()
                .addHeader("Content-Type", "application/json")
                .addHeader("Mcp-Session-Id", "session-1")
                .setBody(
                    """{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-06-18","capabilities":{},"serverInfo":{"name":"demo","version":"1.0"}}}""",
                ),
        )
        server.enqueue(MockResponse().setResponseCode(202))
        server.enqueue(
            MockResponse()
                .addHeader("Content-Type", "application/json")
                .setBody(
                    """{"jsonrpc":"2.0","id":2,"result":{"tools":[{"name":"echo","description":"Echo text","inputSchema":{"type":"object","properties":{}}}]}}""",
                ),
        )
        server.enqueue(
            MockResponse()
                .addHeader("Content-Type", "application/json")
                .setBody(
                    """{"jsonrpc":"2.0","id":3,"result":{"content":[{"type":"text","text":"hello"}],"isError":false}}""",
                ),
        )

        val client = StreamableHttpMcpClient(server.url("/mcp").toString())
        val init = client.initialize()
        assertEquals("2025-06-18", init.protocolVersion)
        assertEquals("demo", init.serverName)

        val tools = client.listTools()
        assertEquals(1, tools.size)
        assertEquals("echo", tools.first()["name"]?.let { (it as JsonPrimitive).content })

        val outcome = client.callTool("echo", buildJsonObject { })
        assertTrue(outcome.ok)
        assertFalse(outcome.isError)
        assertEquals("hello", outcome.content)

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertTrue(request.getHeader("Mcp-Session-Id") == null) // first request has no session yet
        val sessionRequest = server.takeRequest()
        assertEquals("session-1", sessionRequest.getHeader("Mcp-Session-Id"))
    }

    @Test
    fun `sse responses are parsed`() = runBlocking {
        server.enqueue(
            MockResponse()
                .addHeader("Content-Type", "application/json")
                .setBody("""{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-06-18","capabilities":{},"serverInfo":{"name":"sse","version":"1"}}}"""),
        )
        server.enqueue(MockResponse().setResponseCode(202))
        server.enqueue(
            MockResponse()
                .addHeader("Content-Type", "text/event-stream")
                .setBody(
                    "event: message\n" +
                        "data: {\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"tools\":[{\"name\":\"search\",\"description\":\"s\",\"inputSchema\":{\"type\":\"object\"}}]}}\n\n",
                ),
        )

        val client = StreamableHttpMcpClient(server.url("/mcp").toString())
        client.initialize()
        val tools = client.listTools()
        assertEquals(1, tools.size)
        assertEquals("search", (tools.first()["name"] as JsonPrimitive).content)
    }

    @Test
    fun `json rpc error surfaces as a failure`() = runBlocking {
        server.enqueue(
            MockResponse()
                .addHeader("Content-Type", "application/json")
                .setBody("""{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-06-18","capabilities":{},"serverInfo":{"name":"x","version":"1"}}}"""),
        )
        server.enqueue(MockResponse().setResponseCode(202))
        server.enqueue(
            MockResponse()
                .addHeader("Content-Type", "application/json")
                .setBody("""{"jsonrpc":"2.0","id":2,"error":{"code":-32601,"message":"Method not found"}}"""),
        )

        val client = StreamableHttpMcpClient(server.url("/mcp").toString())
        client.initialize()
        val result = runCatching { client.listTools() }
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("Method not found") == true)
    }
}
