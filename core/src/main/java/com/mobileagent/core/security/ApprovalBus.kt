package com.mobileagent.core.security

import com.mobileagent.core.model.RiskLevel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

data class ApprovalRequest(
    val id: String = UUID.randomUUID().toString(),
    val taskId: String,
    val toolName: String,
    val title: String,
    val risk: RiskLevel,
    val summary: String,
    val argumentsJson: String,
    val target: String,
    val canUndo: Boolean,
    val requestedAt: Long = System.currentTimeMillis(),
)

/**
 * Suspends a tool call until the user answers in the UI. A missing answer (timeout, screen closed)
 * is always treated as a rejection; the call never silently proceeds.
 */
class ApprovalBus {

    private val _pending = MutableStateFlow<ApprovalRequest?>(null)
    val pending: StateFlow<ApprovalRequest?> = _pending.asStateFlow()

    private val mutex = Mutex()
    private var deferred: CompletableDeferred<Boolean>? = null
    private var activeId: String? = null

    suspend fun request(request: ApprovalRequest, timeoutMs: Long = 300_000L): Boolean = mutex.withLock {
        _pending.value = request
        activeId = request.id
        val waiter = CompletableDeferred<Boolean>()
        deferred = waiter
        val result = withTimeoutOrNull(timeoutMs) { waiter.await() } ?: false
        _pending.value = null
        deferred = null
        activeId = null
        result
    }

    fun respond(requestId: String, approved: Boolean) {
        if (requestId != activeId) return
        deferred?.complete(approved)
    }

    fun cancelActive() {
        deferred?.complete(false)
        _pending.value = null
    }
}
