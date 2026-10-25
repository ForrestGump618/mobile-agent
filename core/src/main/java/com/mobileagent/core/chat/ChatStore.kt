package com.mobileagent.core.chat

import com.mobileagent.core.model.AgentEventType
import com.mobileagent.core.model.ChatMessage
import com.mobileagent.core.model.TaskRecord
import com.mobileagent.core.model.TaskStatus
import kotlinx.coroutines.flow.Flow

/**
 * Persistence contract for plain conversations. Implemented by the Room-backed store and faked in
 * unit tests, so the chat engine can be tested without Android.
 */
interface ChatStore {
    fun observeChats(limit: Int = 100): Flow<List<TaskRecord>>
    fun observeConversation(id: String): Flow<TaskRecord?>
    fun observeMessages(id: String): Flow<List<ChatMessage>>
    suspend fun createConversation(firstMessage: String, modelLabel: String?): TaskRecord
    suspend fun getConversation(id: String): TaskRecord?
    suspend fun messages(id: String): List<ChatMessage>
    suspend fun append(conversationId: String, role: String, content: String): ChatMessage
    suspend fun latestAssistant(conversationId: String): ChatMessage?
    suspend fun deleteMessage(messageId: String)
    suspend fun updateStatus(id: String, status: TaskStatus, error: String?)
    suspend fun setResult(id: String, text: String)
    suspend fun addEvent(conversationId: String?, type: AgentEventType, message: String)
    suspend fun deleteConversation(id: String)
}
