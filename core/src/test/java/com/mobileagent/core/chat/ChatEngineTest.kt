package com.mobileagent.core.chat

import com.mobileagent.core.llm.LlmClient
import com.mobileagent.core.llm.ResolvedModel
import com.mobileagent.core.model.ChatMessage
import com.mobileagent.core.model.ChatMode
import com.mobileagent.core.model.ChatRequest
import com.mobileagent.core.model.ChatResponse
import com.mobileagent.core.model.LlmErrorKind
import com.mobileagent.core.model.LlmException
import com.mobileagent.core.model.ModelCapabilityReport
import com.mobileagent.core.model.ProviderConfig
import com.mobileagent.core.model.TaskRecord
import com.mobileagent.core.model.TaskStatus
import com.mobileagent.core.model.Usage
import com.mobileagent.core.settings.AgentSettings
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class ChatEngineTest {

    private val provider = ProviderConfig(
        id = "p1",
        name = "DeepSeek",
        baseUrl = "https://api.deepseek.com",
        modelId = "deepseek-flash",
    )
    private val model = ResolvedModel(provider, "sk-test", "DeepSeek · deepseek-flash")

    @Test
    fun `plain chat sends no tools and persists the assistant reply`() = runBlocking {
        val store = FakeChatStore()
        val llm = FakeLlm("你好，我是助手")
        val engine = engine(store, llm)

        val conversation = engine.send(null, "你好")
        val status = awaitTerminal(store)

        assertEquals(TaskStatus.COMPLETED, status)
        assertEquals(2, store.messages(conversation.id).size)
        assertEquals(ChatMessage.ROLE_USER, store.messages(conversation.id)[0].role)
        assertEquals(ChatMessage.ROLE_ASSISTANT, store.messages(conversation.id)[1].role)
        assertEquals("你好，我是助手", store.messages(conversation.id)[1].content)
        assertTrue(llm.requests.isNotEmpty())
        assertTrue(llm.requests.first().tools.isEmpty())
        assertEquals("deepseek-flash", provider.modelId)
    }

    @Test
    fun `multi turn keeps user and assistant history in order`() = runBlocking {
        val store = FakeChatStore()
        val llm = FakeLlm("第一次回答")
        val engine = engine(store, llm)

        val conversation = engine.send(null, "第一个问题")
        awaitTerminal(store)
        llm.reply = "第二次回答"
        engine.send(conversation.id, "第二个问题")
        awaitTerminal(store)

        assertEquals(4, store.messages(conversation.id).size)
        val second = llm.requests.last()
        assertEquals("system", second.messages.first().role)
        val tail = second.messages.takeLast(3)
        assertEquals(listOf("user", "assistant", "user"), tail.map { it.role })
        assertEquals("第一个问题", tail[0].content)
        assertEquals("第一次回答", tail[1].content)
        assertEquals("第二个问题", tail[2].content)
    }

    @Test
    fun `auth failure ends the task with an actionable message`() = runBlocking {
        val store = FakeChatStore()
        val llm = FakeLlm(error = LlmException("bad key", kind = LlmErrorKind.AUTH, httpStatus = 401))
        val engine = engine(store, llm)

        engine.send(null, "你好")
        val status = awaitTerminal(store)

        assertEquals(TaskStatus.FAILED, status)
        val error = store.task.value?.lastError.orEmpty()
        assertTrue(error.contains("API Key"))
        assertFalse(error.contains("sk-test"))
    }

    @Test
    fun `empty answer is reported as a failure not a hang`() = runBlocking {
        val store = FakeChatStore()
        val llm = FakeLlm("")
        val engine = engine(store, llm)

        engine.send(null, "你好")
        val status = awaitTerminal(store)

        assertEquals(TaskStatus.FAILED, status)
        assertTrue(store.task.value?.lastError.orEmpty().contains("空内容"))
    }

    private fun engine(store: ChatStore, llm: LlmClient): ChatEngine = ChatEngine(
        store = store,
        llm = llm,
        modelProvider = { model },
        settingsProvider = { AgentSettings(temperature = 0.2, maxOutputTokens = 256, retries = 0) },
        runs = ChatRunRegistry(),
    )

    private suspend fun awaitTerminal(store: FakeChatStore): TaskStatus = withTimeout(5_000) {
        var status: TaskStatus? = store.task.value?.status
        while (status == null || !status.isTerminal) {
            delay(10)
            status = store.task.value?.status
        }
        status!!
    }
}

private class FakeChatStore : ChatStore {

    val task = MutableStateFlow<TaskRecord?>(null)
    private val messageFlow = MutableStateFlow<List<ChatMessage>>(emptyList())
    private val conversationFlow = MutableStateFlow<List<TaskRecord>>(emptyList())

    override fun observeChats(limit: Int): Flow<List<TaskRecord>> = conversationFlow
    override fun observeConversation(id: String): Flow<TaskRecord?> = task
    override fun observeMessages(id: String): Flow<List<ChatMessage>> = messageFlow

    override suspend fun createConversation(firstMessage: String, modelLabel: String?): TaskRecord {
        val now = System.currentTimeMillis()
        val record = TaskRecord(
            id = UUID.randomUUID().toString(),
            title = firstMessage.take(20),
            goal = firstMessage,
            status = TaskStatus.CREATED,
            createdAt = now,
            updatedAt = now,
            plan = null,
            contextSummary = null,
            lastError = null,
            toolCallCount = 0,
            modelLabel = modelLabel,
            resultText = null,
            cancelRequested = false,
            mode = ChatMode.CHAT,
        )
        task.value = record
        conversationFlow.value = listOf(record)
        return record
    }

    override suspend fun getConversation(id: String): TaskRecord? = task.value

    override suspend fun messages(id: String): List<ChatMessage> = messageFlow.value

    override suspend fun append(conversationId: String, role: String, content: String): ChatMessage {
        val message = ChatMessage(
            id = UUID.randomUUID().toString(),
            taskId = conversationId,
            role = role,
            content = content,
            createdAt = System.currentTimeMillis(),
        )
        messageFlow.value = messageFlow.value + message
        return message
    }

    override suspend fun latestAssistant(conversationId: String): ChatMessage? =
        messageFlow.value.lastOrNull { it.role == ChatMessage.ROLE_ASSISTANT }

    override suspend fun deleteMessage(messageId: String) {
        messageFlow.value = messageFlow.value.filterNot { it.id == messageId }
    }

    override suspend fun updateStatus(id: String, status: TaskStatus, error: String?) {
        task.value = task.value?.copy(status = status, lastError = error, updatedAt = System.currentTimeMillis())
        conversationFlow.value = task.value?.let { listOf(it) } ?: emptyList()
    }

    override suspend fun setResult(id: String, text: String) {
        task.value = task.value?.copy(resultText = text)
    }

    override suspend fun addEvent(conversationId: String?, type: com.mobileagent.core.model.AgentEventType, message: String) = Unit

    override suspend fun deleteConversation(id: String) {
        task.value = null
        conversationFlow.value = emptyList()
        messageFlow.value = emptyList()
    }
}

private class FakeLlm(
    var reply: String = "ok",
    private val error: LlmException? = null,
) : LlmClient {

    val requests = mutableListOf<ChatRequest>()

    override suspend fun chat(
        config: ProviderConfig,
        apiKey: String?,
        request: ChatRequest,
        onDelta: (String) -> Unit,
    ): ChatResponse {
        requests += request
        error?.let { throw it }
        if (reply.isNotEmpty()) onDelta(reply)
        return ChatResponse(
            content = reply,
            toolCalls = emptyList(),
            usage = Usage(5, 3),
            finishReason = "stop",
            latencyMs = 1,
            streamed = true,
        )
    }

    override suspend fun listModels(config: ProviderConfig, apiKey: String?): List<String> = listOf("deepseek-flash")

    override suspend fun probe(
        config: ProviderConfig,
        apiKey: String?,
        includeVision: Boolean,
        includeToolCalling: Boolean,
    ): ModelCapabilityReport = ModelCapabilityReport(
        providerId = config.id,
        modelId = config.modelId,
        verified = emptySet(),
        unverified = emptySet(),
        notes = "",
        checkedAt = 0,
    )
}
