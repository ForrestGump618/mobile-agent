package com.mobileagent.core.agent

import com.mobileagent.core.data.TaskRepository
import com.mobileagent.core.model.ChatMessage
import com.mobileagent.core.model.LlmMessage
import com.mobileagent.core.model.TaskRecord

/**
 * Builds the model conversation with an explicit character budget. For a resumed run the previous
 * transcript is folded into one system block, which keeps the provider message sequence valid
 * (an orphan `tool` message without its `assistant.tool_calls` pair would be rejected).
 */
class ContextManager(private val tasks: TaskRepository) {

    suspend fun initialMessages(
        task: TaskRecord,
        systemPrompt: String,
        resume: Boolean,
        budgetChars: Int,
        recoveryNote: String? = null,
        userMessage: String? = null,
    ): MutableList<LlmMessage> {
        val messages = mutableListOf<LlmMessage>()
        if (!resume) {
            messages += LlmMessage.system(systemPrompt)
            messages += LlmMessage.user(task.goal)
            return messages
        }

        val transcript = buildTranscript(task.id, (budgetChars * 0.5).toInt())
        val system = buildString {
            append(systemPrompt)
            append("\n\n## Earlier conversation\n")
            append("The transcript below is the record of this conversation and any interrupted work. ")
            append("Before repeating a state-changing action, verify its effect with a read-only tool. Never assume a step succeeded.\n")
            recoveryNote?.takeIf { it.isNotBlank() }?.let { append("\nRecovery note: ").append(it).append('\n') }
            append("\n--- transcript ---\n")
            append(transcript)
        }
        messages += LlmMessage.system(system)
        messages += LlmMessage.user(
            userMessage ?: (
                "Continue the task \"${task.goal}\". Inspect the transcript, verify any uncertain step, then finish the remaining work. " +
                    "Report exactly what was already done and what you do now."
                ),
        )
        return messages
    }

    suspend fun buildTranscript(taskId: String, maxChars: Int): String {
        val history = tasks.messages(taskId)
        if (history.isEmpty()) return "(no previous messages)"
        val builder = StringBuilder()
        val selected = ArrayDeque<ChatMessage>()
        var used = 0
        for (message in history.asReversed()) {
            val line = format(message)
            if (used + line.length > maxChars && selected.isNotEmpty()) break
            selected.addFirst(message)
            used += line.length
        }
        if (selected.size < history.size) {
            builder.append("(").append(history.size - selected.size).append(" older messages omitted)\n")
        }
        selected.forEach { builder.append(format(it)).append('\n') }
        return builder.toString().trim()
    }

    private fun format(message: ChatMessage): String {
        val role = when (message.role) {
            ChatMessage.ROLE_TOOL -> "tool(${message.toolName ?: "?"})"
            else -> message.role
        }
        val body = if (message.content.length > 1_200) message.content.take(1_200) + " ...[truncated]" else message.content
        return "[$role] $body"
    }

    fun shouldCompress(transcriptChars: Int, contextWindowTokens: Int): Boolean =
        transcriptChars > contextWindowTokens * 4 * 0.6

    fun trimForBudget(messages: MutableList<LlmMessage>, budgetChars: Int) {
        var total = messages.sumOf { (it.content?.length ?: 0) + 80 }
        if (total <= budgetChars) return
        // Always keep the system prompt and the latest user turn; drop from the oldest middle.
        val protected = messages.size.coerceAtMost(2)
        var index = protected
        while (total > budgetChars && index < messages.size - 1) {
            val removed = messages.removeAt(index)
            total -= (removed.content?.length ?: 0) + 80
        }
    }
}
