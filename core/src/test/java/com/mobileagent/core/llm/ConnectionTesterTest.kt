package com.mobileagent.core.llm

import com.mobileagent.core.model.ChatRequest
import com.mobileagent.core.model.ChatResponse
import com.mobileagent.core.model.LlmErrorKind
import com.mobileagent.core.model.LlmException
import com.mobileagent.core.model.ModelCapabilityReport
import com.mobileagent.core.model.ProviderConfig
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionTesterTest {

    private val provider = ProviderConfig(
        id = "p",
        name = "DeepSeek",
        baseUrl = "https://api.deepseek.com",
        modelId = "deepseek-flash",
    )

    @Test
    fun `success requires a real text answer and reports each stage`() = runBlocking {
        val tester = ConnectionTester(FakeClient("连接成功"))
        val result = tester.test(provider, "sk-test")

        assertTrue(result.ok)
        assertEquals(setOf("config", "network", "auth", "model", "text", "stream"), result.checks.map { it.id }.toSet())
        assertTrue(result.checks.all { it.status == CheckStatus.OK })
        assertTrue(result.verified.contains(com.mobileagent.core.model.Capability.TEXT))
        assertTrue(result.verified.contains(com.mobileagent.core.model.Capability.STREAMING))
    }

    @Test
    fun `missing key fails at config stage without a request`() = runBlocking {
        val client = FakeClient("x")
        val result = ConnectionTester(client).test(provider, "")
        assertFalse(result.ok)
        assertEquals(CheckStatus.FAILED, result.checks.first().status)
        assertTrue(client.requests == 0)
    }

    @Test
    fun `auth error maps to auth stage and skips model stages`() = runBlocking {
        val tester = ConnectionTester(
            FakeClient(error = LlmException("bad key", kind = LlmErrorKind.AUTH, httpStatus = 401)),
        )
        val result = tester.test(provider, "bad")
        assertFalse(result.ok)
        val byId = result.checks.associateBy { it.id }
        assertEquals(CheckStatus.OK, byId["network"]?.status)
        assertEquals(CheckStatus.FAILED, byId["auth"]?.status)
        assertEquals(CheckStatus.SKIPPED, byId["model"]?.status)
        assertEquals(CheckStatus.SKIPPED, byId["text"]?.status)
    }

    @Test
    fun `network error maps to network stage`() = runBlocking {
        val tester = ConnectionTester(
            FakeClient(error = LlmException("dns", kind = LlmErrorKind.NETWORK)),
        )
        val result = tester.test(provider, "k")
        val byId = result.checks.associateBy { it.id }
        assertEquals(CheckStatus.FAILED, byId["network"]?.status)
        assertEquals(CheckStatus.SKIPPED, byId["auth"]?.status)
    }
}

private class FakeClient(
    private val reply: String = "ok",
    private val error: LlmException? = null,
) : LlmClient {
    var requests = 0

    override suspend fun chat(
        config: ProviderConfig,
        apiKey: String?,
        request: ChatRequest,
        onDelta: (String) -> Unit,
    ): ChatResponse {
        requests++
        error?.let { throw it }
        if (reply.isNotEmpty()) onDelta(reply)
        return ChatResponse(reply, emptyList(), null, "stop", 1, request.stream)
    }

    override suspend fun listModels(config: ProviderConfig, apiKey: String?): List<String> = emptyList()

    override suspend fun probe(
        config: ProviderConfig,
        apiKey: String?,
        includeVision: Boolean,
        includeToolCalling: Boolean,
    ): ModelCapabilityReport = ModelCapabilityReport(config.id, config.modelId, emptySet(), emptySet(), "", 0)
}
