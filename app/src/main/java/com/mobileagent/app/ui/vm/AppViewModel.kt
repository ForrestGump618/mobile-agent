package com.mobileagent.app.ui.vm

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mobileagent.app.MobileAgentApplication
import com.mobileagent.core.model.ProviderConfig
import com.mobileagent.core.security.ApprovalRequest
import com.mobileagent.core.settings.AgentSettings
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class AppViewModel(application: Application) : AndroidViewModel(application) {

    private val graph = (application as MobileAgentApplication).graph

    val settings: StateFlow<AgentSettings> = graph.settings.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AgentSettings())

    val providers: StateFlow<List<ProviderConfig>> = graph.providers.observeProviders()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val approval: StateFlow<ApprovalRequest?> = graph.approvals.pending

    val notices = MutableSharedFlow<String>(extraBufferCapacity = 4)

    fun completeOnboarding() {
        viewModelScope.launch { graph.settings.completeOnboarding() }
    }

    fun setLanguage(tag: String) {
        viewModelScope.launch { graph.settings.setLanguage(tag) }
    }

    fun respondApproval(requestId: String, approved: Boolean) {
        graph.approvals.respond(requestId, approved)
        viewModelScope.launch {
            notices.emit(if (approved) "Action approved." else "Action rejected.")
        }
    }
}
