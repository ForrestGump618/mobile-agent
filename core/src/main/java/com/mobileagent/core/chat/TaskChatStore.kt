package com.mobileagent.core.chat

import com.mobileagent.core.data.TaskRepository
import com.mobileagent.core.model.AgentEventType
import com.mobileagent.core.model.ChatMode
import com.mobileagent.core.model.ChatMessage
import com.mobileagent.core.model.TaskRecord
import com.mobileagent.core.model.TaskStatus
import kotlinx.coroutines.flow.Flow

/** Room-backed implementation for plain chat conversations. */
class TaskChatStore(private val tasks: TaskRepository) : ChatStore {

    override fun observeChats(limit: Int): Flow<List<TaskRecord>> = tasks.observeChats(limit)

    override fun observeConversation(id: String): Flow<TaskRecord?> = tasks.observeTask(id)

    override fun observeMessages(id: String): Flow<List<ChatMessage>> = tasks.observeMessages(id)

    override suspend fun createConversation(firstMessage: String, modelLabel: String?): TaskRecord {
        val title = firstMessage.replace(Regex("\\s+"), " ").trim().let {
            if (it.length <= 24) it else it.take(23) + "…"
        }
        return tasks.createTask(
            goal = firstMessage,
            title = title.ifBlank { "新对话" },
            modelLabel = modelLabel,
            mode = ChatMode.CHAT,
        )
    }

    override suspend fun getConversation(id: String): TaskRecord? = tasks.getTask(id)

    override suspend fun messages(id: String): List<ChatMessage> = tasks.messages(id)

    override suspend fun append(conversationId: String, role: String, content: String): ChatMessage =
        tasks.appendMessage(conversationId, role, content)

    override suspend fun latestAssistant(conversationId: String): ChatMessage? =
        tasks.latestAssistantMessage(conversationId)

    override suspend fun deleteMessage(messageId: String) = tasks.deleteMessage(messageId)

    override suspend fun updateStatus(id: String, status: TaskStatus, error: String?) =
        tasks.updateStatus(id, status, error)

    override suspend fun setResult(id: String, text: String) = tasks.setResult(id, text)

    override suspend fun addEvent(conversationId: String?, type: AgentEventType, message: String) =
        tasks.addEvent(conversationId, null, type, message)

    override suspend fun deleteConversation(id: String) = tasks.deleteTask(id)
}
