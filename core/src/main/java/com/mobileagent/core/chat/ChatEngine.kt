package com.mobileagent.core.chat

import com.mobileagent.core.llm.LlmClient
import com.mobileagent.core.llm.ResolvedModel
import com.mobileagent.core.model.AgentEventType
import com.mobileagent.core.model.ChatMessage
import com.mobileagent.core.model.ChatRequest
import com.mobileagent.core.model.LlmErrorKind
import com.mobileagent.core.model.LlmException
import com.mobileagent.core.model.LlmMessage
import com.mobileagent.core.model.ModelRole
import com.mobileagent.core.model.TaskRecord
import com.mobileagent.core.model.TaskStatus
import com.mobileagent.core.security.Redactor
import com.mobileagent.core.settings.AgentSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/** Resolves the model for a chat turn (active provider). */
fun interface ChatModelProvider {
    suspend fun resolve(): ResolvedModel?
}

/** Supplies runtime chat parameters (temperature, retries, context budget). */
fun interface ChatSettingsProvider {
    suspend fun get(): AgentSettings
}

/**
 * Plain chat engine: model + transcript only. No tools, no MCP, no Shizuku, no accessibility and no
 * policy approvals are involved, so a normal conversation works even when every device capability is
 * unavailable. This is intentionally a separate path from AgentOrchestrator.
 */
class ChatEngine(
    private val store: ChatStore,
    private val llm: LlmClient,
    private val modelProvider: ChatModelProvider,
    private val settingsProvider: ChatSettingsProvider,
    private val runs: ChatRunRegistry,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, Job>()

    suspend fun send(conversationId: String?, text: String): TaskRecord {
        val trimmed = text.trim()
        require(trimmed.isNotEmpty()) { "消息不能为空" }

        val conversation = if (conversationId.isNullOrBlank()) {
            store.createConversation(trimmed, runCatching { modelLabel() }.getOrNull())
        } else {
            store.getConversation(conversationId) ?: store.createConversation(
                trimmed,
                runCatching { modelLabel() }.getOrNull(),
            )
        }

        if (isRunning(conversation.id)) {
            throw IllegalStateException("上一条回复还在生成中，请先停止或等待完成。")
        }

        store.append(conversation.id, ChatMessage.ROLE_USER, trimmed)
        store.updateStatus(conversation.id, TaskStatus.RUNNING_HEADLESS, null)
        store.addEvent(conversation.id, AgentEventType.MODEL_REQUEST, "chat request queued")
        start(conversation.id)
        return conversation
    }

    fun start(conversationId: String) {
        synchronized(jobs) {
            if (jobs[conversationId]?.isActive == true) return
            jobs[conversationId] = scope.launch {
                try {
                    respond(conversationId)
                } finally {
                    jobs.remove(conversationId)
                }
            }
        }
    }

    private suspend fun respond(conversationId: String) {
        val conversation = store.getConversation(conversationId) ?: return
        val appSettings = settingsProvider.get()
        val model = modelProvider.resolve()
        if (model == null) {
            fail(conversationId, "尚未配置可用的模型。请在「我的 > 模型」中选择服务商并填写 API Key。")
            return
        }

        runs.start(conversationId)
        val messages = buildMessages(conversation)
        var attempt = 0

        try {
            while (true) {
                try {
                    val response = llm.chat(
                        config = model.provider,
                        apiKey = model.apiKey,
                        request = ChatRequest(
                            messages = messages,
                            tools = emptyList(),
                            temperature = appSettings.temperature,
                            maxTokens = appSettings.maxOutputTokens,
                            stream = true,
                        ),
                        onDelta = { delta -> runs.append(conversationId, delta) },
                    )

                    val answer = response.content.trim()
                    if (answer.isEmpty()) {
                        throw LlmException(
                            "模型返回了空内容，请重试或更换模型。",
                            kind = LlmErrorKind.EMPTY,
                        )
                    }
                    store.append(conversationId, ChatMessage.ROLE_ASSISTANT, answer)
                    store.setResult(conversationId, answer)
                    store.updateStatus(conversationId, TaskStatus.COMPLETED, null)
                    store.addEvent(
                        conversationId,
                        AgentEventType.MODEL_RESPONSE,
                        "chat reply in ${response.latencyMs} ms, ${response.usage?.total?.toString() ?: "usage unknown"} tokens",
                    )
                    runs.finish(conversationId)
                    return
                } catch (e: LlmException) {
                    val retries = appSettings.retries
                    if (e.retryable && attempt < retries) {
                        attempt++
                        store.addEvent(
                            conversationId,
                            AgentEventType.DIAGNOSTIC,
                            "chat retry $attempt/${retries}: ${e.kind}",
                        )
                        runs.start(conversationId)
                        delay(700L * attempt)
                        continue
                    }
                    store.addEvent(
                        conversationId,
                        AgentEventType.TASK_FAILED,
                        "chat failed: ${e.kind} ${e.httpStatus ?: ""} ${Redactor.redact(e.message.orEmpty()).take(300)}",
                    )
                    fail(conversationId, friendlyMessage(e))
                    return
                }
            }
        } catch (c: CancellationException) {
            val partial = runs.draft(conversationId)
            if (partial.isNotBlank()) {
                store.append(conversationId, ChatMessage.ROLE_ASSISTANT, "$partial\n\n_(已停止生成)_")
            }
            store.updateStatus(conversationId, TaskStatus.CANCELLED, "已停止生成")
            runs.finish(conversationId)
            throw c
        } finally {
            runs.finish(conversationId)
        }
    }

    suspend fun cancel(conversationId: String) {
        jobs.remove(conversationId)?.cancel()
        runs.finish(conversationId)
        store.updateStatus(conversationId, TaskStatus.CANCELLED, "已停止生成")
    }

    /** Deletes the last assistant reply and asks again with the same history. */
    suspend fun regenerate(conversationId: String) {
        if (isRunning(conversationId)) return
        store.latestAssistant(conversationId)?.let { store.deleteMessage(it.id) }
        store.updateStatus(conversationId, TaskStatus.RUNNING_HEADLESS, null)
        start(conversationId)
    }

    suspend fun deleteConversation(conversationId: String) {
        jobs.remove(conversationId)?.cancel()
        runs.clear(conversationId)
        store.deleteConversation(conversationId)
    }

    fun isRunning(conversationId: String): Boolean = jobs[conversationId]?.isActive == true

    private suspend fun modelLabel(): String? = modelProvider.resolve()?.label

    fun shutdown() {
        jobs.values.forEach { it.cancel() }
        jobs.clear()
    }

    private suspend fun buildMessages(conversation: TaskRecord): List<LlmMessage> {
        val appSettings = settingsProvider.get()
        val system = """
            你是 Mobile Agent 的对话助手，用简体中文（或用户使用的语言）自然回答。
            规则：
            1. 你可以正常聊天、解释概念、写作和推理，不需要调用任何设备工具。
            2. 不要声称已经执行了手机操作、命令、联网请求或文件写入；这些能力属于 Agent 模式。
            3. 如果用户要求实际操作手机、运行命令、抓取网页或处理文件，请说明可以切换到 Agent 模式。
            4. 不确定的信息要说明不确定，不要编造事实、链接或执行结果。
        """.trimIndent()

        val budget = (appSettings.contextWindowTokens * 4).coerceAtLeast(8_000)
        val history = store.messages(conversation.id)
            .filter { (it.role == ChatMessage.ROLE_USER || it.role == ChatMessage.ROLE_ASSISTANT) && it.content.isNotBlank() }

        val selected = ArrayDeque<ChatMessage>()
        var used = 0
        for (message in history.asReversed()) {
            val cost = message.content.length + 16
            if (used + cost > budget && selected.isNotEmpty()) break
            selected.addFirst(message)
            used += cost
        }

        return buildList {
            add(LlmMessage.system(system))
            selected.forEach { message ->
                add(
                    if (message.role == ChatMessage.ROLE_USER) {
                        LlmMessage.user(message.content)
                    } else {
                        LlmMessage.assistant(message.content)
                    },
                )
            }
        }
    }

    private suspend fun fail(conversationId: String, message: String) {
        store.updateStatus(conversationId, TaskStatus.FAILED, message)
        runs.fail(conversationId, message)
    }

    private fun friendlyMessage(error: LlmException): String {
        val prefix = when (error.kind) {
            LlmErrorKind.CONFIG -> "模型配置不完整"
            LlmErrorKind.AUTH -> "API Key 无效或已过期，请在「我的 > 模型」中重新填写"
            LlmErrorKind.FORBIDDEN -> "当前 API Key 没有访问该模型的权限"
            LlmErrorKind.NOT_FOUND -> "接口地址或模型 ID 不存在，请检查 Base URL 与模型名称"
            LlmErrorKind.RATE_LIMIT -> "请求过于频繁或额度不足，请稍后重试"
            LlmErrorKind.BAD_REQUEST -> "请求参数不被服务端接受"
            LlmErrorKind.SERVER -> "模型服务暂时不可用，请稍后重试"
            LlmErrorKind.NETWORK -> "网络连接失败，请检查网络、代理或 DNS"
            LlmErrorKind.TIMEOUT -> "请求超时，模型没有在限定时间内响应"
            LlmErrorKind.STREAM -> "流式响应解析失败"
            LlmErrorKind.EMPTY -> "模型返回了空内容"
            LlmErrorKind.UNKNOWN -> "模型请求失败"
        }
        val detail = error.technical?.takeIf { it.isNotBlank() }?.let { Redactor.redact(it).take(300) }
            ?: error.message?.take(300)
        return if (detail.isNullOrBlank() || prefix.contains(detail)) prefix else "$prefix：$detail"
    }
}
