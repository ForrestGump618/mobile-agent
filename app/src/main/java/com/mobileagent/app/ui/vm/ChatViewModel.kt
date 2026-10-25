package com.mobileagent.app.ui.vm

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mobileagent.app.MobileAgentApplication
import com.mobileagent.core.model.ChatMode
import com.mobileagent.core.model.ChatMessage
import com.mobileagent.core.model.ProviderConfig
import com.mobileagent.core.model.TaskRecord
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val graph = (application as MobileAgentApplication).graph
    private val currentId = MutableStateFlow<String?>(null)

    val input = MutableStateFlow("")
    val error = MutableStateFlow<String?>(null)
    val toast = MutableStateFlow<String?>(null)
    val mode = MutableStateFlow(ChatMode.CHAT)
    val agentTaskId = MutableStateFlow<String?>(null)

    val conversations: StateFlow<List<TaskRecord>> = graph.tasks.observeChats(100)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val conversation: StateFlow<TaskRecord?> = currentId
        .flatMapLatest { id -> if (id == null) flowOf(null) else graph.tasks.observeTask(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val messages: StateFlow<List<ChatMessage>> = currentId
        .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else graph.tasks.observeMessages(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val draft: StateFlow<String> = combine(currentId, graph.chatRuns.runs) { id, runs ->
        id?.let { runs[it]?.draft }.orEmpty()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    val running: StateFlow<Boolean> = combine(currentId, graph.chatRuns.runs) { id, runs ->
        id?.let { runs[it]?.running } == true
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val providers: StateFlow<List<ProviderConfig>> = graph.providers.observeProviders()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val activeProviderId: StateFlow<String?> = graph.settings.settings.map { it.activeProviderId }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val activeProviderLabel: StateFlow<String?> = combine(providers, activeProviderId) { list, id ->
        val provider = list.firstOrNull { it.id == id } ?: list.firstOrNull()
        provider?.let { "${it.name} · ${it.modelId}" }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun newChat() {
        if (running.value) return
        currentId.value = null
        agentTaskId.value = null
        error.value = null
    }

    fun open(conversationId: String) {
        currentId.value = conversationId
        error.value = null
        agentTaskId.value = null
    }

    fun setInput(value: String) {
        input.value = value
    }

    fun setMode(value: ChatMode) {
        if (running.value) return
        mode.value = value
        error.value = null
    }

    fun send() {
        val text = input.value.trim()
        if (text.isEmpty() || running.value) return
        input.value = ""
        viewModelScope.launch {
            try {
                if (mode.value == ChatMode.CHAT) {
                    val conversation = graph.chatEngine.send(currentId.value, text)
                    currentId.value = conversation.id
                } else {
                    val task = graph.runtime.submit(text)
                    agentTaskId.value = task.id
                    toast.value = "已创建 Agent 任务，可在「任务」页查看执行过程。"
                }
            } catch (t: Throwable) {
                error.value = t.message ?: "发送失败"
                if (input.value.isBlank()) input.value = text
            }
        }
    }

    fun stop() {
        val id = currentId.value ?: return
        viewModelScope.launch { graph.chatEngine.cancel(id) }
    }

    fun regenerate() {
        val id = currentId.value ?: return
        viewModelScope.launch { graph.chatEngine.regenerate(id) }
    }

    fun deleteConversation(id: String) {
        viewModelScope.launch {
            graph.chatEngine.deleteConversation(id)
            if (currentId.value == id) newChat()
        }
    }

    fun selectDefaultProvider(providerId: String) {
        viewModelScope.launch { graph.settings.setActiveProvider(providerId) }
    }

    fun clearError() {
        error.value = null
    }

    fun clearToast() {
        toast.value = null
    }
}
