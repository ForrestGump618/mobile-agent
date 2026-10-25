package com.mobileagent.core.chat

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class ChatRun(
    val conversationId: String,
    val draft: String = "",
    val running: Boolean = false,
    val error: String? = null,
)

/** Streaming state for plain chat, kept out of the database until the answer is complete. */
class ChatRunRegistry {

    private val _runs = MutableStateFlow<Map<String, ChatRun>>(emptyMap())
    val runs: StateFlow<Map<String, ChatRun>> = _runs.asStateFlow()

    fun start(conversationId: String) {
        _runs.update { it + (conversationId to ChatRun(conversationId, running = true)) }
    }

    fun append(conversationId: String, delta: String) {
        _runs.update { current ->
            val existing = current[conversationId] ?: ChatRun(conversationId, running = true)
            current + (conversationId to existing.copy(draft = existing.draft + delta, running = true, error = null))
        }
    }

    fun draft(conversationId: String): String = _runs.value[conversationId]?.draft.orEmpty()

    fun finish(conversationId: String) {
        _runs.update { it + (conversationId to ChatRun(conversationId, running = false)) }
    }

    fun fail(conversationId: String, error: String) {
        _runs.update { it + (conversationId to ChatRun(conversationId, running = false, error = error)) }
    }

    fun isRunning(conversationId: String): Boolean = _runs.value[conversationId]?.running == true

    fun clear(conversationId: String) {
        _runs.update { it - conversationId }
    }
}
