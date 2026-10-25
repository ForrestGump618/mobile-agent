package com.mobileagent.app.ui.vm

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mobileagent.app.MobileAgentApplication
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

enum class TerminalMode { LOCAL, SHIZUKU }

enum class TerminalTone { COMMAND, OUTPUT, ERROR, INFO }

data class TerminalLine(val id: Long, val text: String, val tone: TerminalTone)

class TerminalViewModel(application: Application) : AndroidViewModel(application) {

    private val graph = (application as MobileAgentApplication).graph
    private val nextId = java.util.concurrent.atomic.AtomicLong(0)

    val mode = MutableStateFlow(TerminalMode.LOCAL)
    val lines = MutableStateFlow<List<TerminalLine>>(emptyList())
    val running = MutableStateFlow(false)
    val history = MutableStateFlow<List<String>>(emptyList())
    val shizukuStatus = MutableStateFlow(runCatching { graph.shizuku.status() }.getOrNull())

    private var job: Job? = null

    fun setMode(mode: TerminalMode) {
        this.mode.value = mode
        shizukuStatus.value = runCatching { graph.shizuku.status() }.getOrNull()
        append(
            if (mode == TerminalMode.LOCAL) {
                "已切换到应用 Shell（应用 UID，无 root、无 ADB）。"
            } else {
                "已切换到 Shizuku Shell（ADB shell UID）。${shizukuStatus.value?.message.orEmpty()}"
            },
            TerminalTone.INFO,
        )
    }

    fun refreshShizuku() {
        shizukuStatus.value = runCatching { graph.shizuku.status() }.getOrNull()
    }

    fun run(rawCommand: String, timeoutMs: Long = 60_000L) {
        val command = rawCommand.trim()
        if (command.isEmpty() || running.value) return
        history.value = (history.value + command).takeLast(50)
        append("$ $command", TerminalTone.COMMAND)
        append(
            if (mode.value == TerminalMode.LOCAL) "执行器=local uid=app" else "执行器=shizuku uid=shell",
            TerminalTone.INFO,
        )
        job = viewModelScope.launch {
            running.value = true
            try {
                val result = if (mode.value == TerminalMode.LOCAL) {
                    graph.localShell.execute(command, timeoutMs)
                } else {
                    graph.shizuku.execute(command, timeoutMs)
                }
                if (result.stdout.isNotBlank()) append(result.stdout.trimEnd(), TerminalTone.OUTPUT)
                if (result.stderr.isNotBlank()) append(result.stderr.trimEnd(), TerminalTone.ERROR)
                val summary = buildString {
                    append("exit=").append(result.exitCode)
                    append(" duration=").append(result.durationMs).append("ms")
                    if (result.timedOut) append(" TIMEOUT")
                    if (result.truncated) append(" (output truncated)")
                    result.error?.let { append(" error=").append(it) }
                }
                append(summary, if (result.exitCode == 0 && result.error == null) TerminalTone.INFO else TerminalTone.ERROR)
            } catch (t: Throwable) {
                append("Execution failed: ${t.message ?: t::class.java.simpleName}", TerminalTone.ERROR)
            } finally {
                running.value = false
            }
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
        append("命令已被用户取消。", TerminalTone.ERROR)
        running.value = false
    }

    fun clear() {
        lines.value = emptyList()
    }

    private fun append(text: String, tone: TerminalTone) {
        lines.value = (lines.value + TerminalLine(nextId.incrementAndGet(), text, tone)).takeLast(500)
    }
}
