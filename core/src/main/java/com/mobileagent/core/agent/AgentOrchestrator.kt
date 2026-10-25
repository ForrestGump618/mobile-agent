package com.mobileagent.core.agent

import com.mobileagent.core.data.TaskRepository
import com.mobileagent.core.llm.LlmClient
import com.mobileagent.core.llm.ModelRouter
import com.mobileagent.core.llm.ResolvedModel
import com.mobileagent.core.llm.describe
import com.mobileagent.core.llm.userMessage
import com.mobileagent.core.memory.MemoryManager
import com.mobileagent.core.model.AgentEventType
import com.mobileagent.core.model.ChatMessage
import com.mobileagent.core.model.ChatRequest
import com.mobileagent.core.model.JsonCodec
import com.mobileagent.core.model.LlmException
import com.mobileagent.core.model.LlmMessage
import com.mobileagent.core.model.ModelRole
import com.mobileagent.core.model.StepStatus
import com.mobileagent.core.model.TaskPlan
import com.mobileagent.core.model.TaskRecord
import com.mobileagent.core.model.TaskStatus
import com.mobileagent.core.model.ToolContext
import com.mobileagent.core.model.ToolResult
import com.mobileagent.core.security.PolicyEngine
import com.mobileagent.core.security.RuntimeCapabilities
import com.mobileagent.core.settings.SettingsStore
import com.mobileagent.core.tools.ToolArgValidator
import com.mobileagent.core.tools.ToolRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.util.UUID

/**
 * The agent loop: Goal -> Plan -> Model -> Tool request -> Policy -> Execute -> Observe ->
 * Verify -> Checkpoint -> Continue. Every step is persisted before the next one starts.
 */
class AgentOrchestrator(
    private val tasks: TaskRepository,
    private val tools: ToolRegistry,
    private val policy: PolicyEngine,
    private val llm: LlmClient,
    private val router: ModelRouter,
    private val settings: SettingsStore,
    private val contextManager: ContextManager,
    private val checkpoints: CheckpointManager,
    private val planner: Planner,
    private val verifier: ResultVerifier,
    private val memory: MemoryManager,
    private val capabilities: RuntimeCapabilities,
    private val runs: AgentRunRegistry,
    private val skillGuidance: suspend () -> String = { "" },
) {

    suspend fun run(taskId: String, followUpMessage: String? = null) {
        var task = tasks.getTask(taskId) ?: return
        if (task.status.isTerminal) return

        val executionId = UUID.randomUUID().toString()
        val isResume = task.status == TaskStatus.RECOVERING
        runs.start(taskId, task.plan)

        try {
            val model = router.resolve(ModelRole.EXECUTION) ?: router.resolve(ModelRole.CHAT)
            if (model == null) {
                failTask(task, executionId, "No usable model provider is configured. Add one in Settings > Models.")
                return
            }
            val appSettings = settings.current()

            if (isResume) {
                tasks.addEvent(taskId, executionId, AgentEventType.TASK_RECOVERED, "Resuming interrupted task")
            }

            var plan: TaskPlan? = task.plan
            if (isResume && plan == null) {
                plan = checkpoints.latest(taskId)?.plan
            }

            if (!isResume && plan == null && planner.shouldPlan(task.goal)) {
                task = moveTo(task, TaskStatus.PLANNING)
                plan = planner.createPlan(taskId, task.goal, model)
                runs.setPlan(taskId, plan)
                task = moveTo(task, TaskStatus.READY)
            }

            task = moveTo(task, TaskStatus.RUNNING_HEADLESS)
            val recoveryNote = if (isResume) checkpoints.recoveryNote(taskId) else null
            val memoryBlock = memory.contextBlock(task.goal)
            val skillBlock = runCatching { skillGuidance() }.getOrDefault("")
            val systemPrompt = PromptComposer.systemPrompt(capabilities, tools, model.label, memoryBlock, plan, skillBlock)
            val budgetChars = appSettings.contextWindowTokens * 4
            val conversation = contextManager.initialMessages(
                task = task,
                systemPrompt = systemPrompt,
                resume = isResume || followUpMessage != null,
                budgetChars = budgetChars,
                recoveryNote = recoveryNote,
                userMessage = followUpMessage,
            )

            var iteration = task.iteration
            var toolCount = task.toolCallCount
            var lastError: String? = null

            while (iteration < appSettings.maxIterations) {
                currentCoroutineContext().ensureActive()
                if (tasks.isCancelRequested(taskId)) {
                    finishTask(task, executionId, TaskStatus.CANCELLED, "Cancelled by user before the next step.")
                    return
                }

                iteration++
                runs.beginIteration(taskId, iteration)
                runs.setPlan(taskId, plan)
                contextManager.trimForBudget(conversation, budgetChars)

                val toolSpecs = tools.llmTools()
                tasks.addEvent(
                    taskId,
                    executionId,
                    AgentEventType.MODEL_REQUEST,
                    "Request #$iteration to ${model.label} with ${toolSpecs.size} tools",
                )

                val attempt = callModel(taskId, executionId, model, toolSpecs, conversation, appSettings.temperature, appSettings.maxOutputTokens)
                if (attempt.response == null) {
                    val reason = attempt.error?.userMessage() ?: "模型请求失败，原因未知。"
                    failTask(task, executionId, reason, plan)
                    return
                }
                val response = attempt.response
                task = tasks.getTask(taskId) ?: task
                tasks.addEvent(
                    taskId,
                    executionId,
                    AgentEventType.MODEL_RESPONSE,
                    "Response in ${response.latencyMs} ms, ${response.toolCalls.size} tool call(s), " +
                        "tokens=${response.usage?.total?.toString() ?: "unknown"}",
                )

                val assistantTurn = LlmMessage(
                    role = "assistant",
                    content = response.content.ifBlank { null },
                    toolCalls = response.toolCalls,
                )
                conversation += assistantTurn

                if (response.content.isNotBlank()) {
                    tasks.appendMessage(taskId, ChatMessage.ROLE_ASSISTANT, response.content)
                } else if (response.toolCalls.isNotEmpty()) {
                    tasks.appendMessage(
                        taskId,
                        ChatMessage.ROLE_ASSISTANT,
                        "[requested ${response.toolCalls.size} tool call(s): ${response.toolCalls.joinToString { it.name }}]",
                    )
                }
                runs.setDraft(taskId, "")

                if (response.toolCalls.isEmpty()) {
                    completeTask(task, executionId, response.content, plan, iteration)
                    return
                }

                task = tasks.getTask(taskId) ?: task
                for (call in response.toolCalls) {
                    currentCoroutineContext().ensureActive()
                    if (tasks.isCancelRequested(taskId)) break

                    val internalName = tools.resolveWireName(call.name) ?: call.name
                    val tool = tools.get(internalName)
                    if (tool == null) {
                        val message = "FAILURE: unknown tool '${call.name}'. Available tools: ${tools.specs().take(24).joinToString { it.name }}"
                        conversation += LlmMessage.tool(call.id, call.name, message)
                        tasks.appendMessage(taskId, ChatMessage.ROLE_TOOL, message, call.id, call.name)
                        continue
                    }

                    val parsed = ToolArgValidator.parseArguments(call.argumentsJson).getOrNull()
                    if (parsed == null) {
                        val message = "FAILURE: arguments for ${call.name} are not a valid JSON object: ${call.argumentsJson.take(300)}"
                        conversation += LlmMessage.tool(call.id, call.name, message)
                        tasks.appendMessage(taskId, ChatMessage.ROLE_TOOL, message, call.id, call.name)
                        continue
                    }
                    val validationErrors = ToolArgValidator.validate(tool.spec.parametersSchema, parsed)
                    if (validationErrors.isNotEmpty()) {
                        val message = "FAILURE: invalid arguments for ${call.name}: ${validationErrors.joinToString("; ")}"
                        conversation += LlmMessage.tool(call.id, call.name, message)
                        tasks.appendMessage(taskId, ChatMessage.ROLE_TOOL, message, call.id, call.name)
                        tasks.addEvent(taskId, executionId, AgentEventType.TOOL_DENIED, message)
                        continue
                    }

                    val evaluation = policy.evaluate(tool, parsed)
                    if (!evaluation.allowed) {
                        val message = "DENIED: ${evaluation.reason}"
                        tasks.addEvent(taskId, executionId, AgentEventType.TOOL_DENIED, "${call.name}: ${evaluation.reason}")
                        conversation += LlmMessage.tool(call.id, call.name, message)
                        tasks.appendMessage(taskId, ChatMessage.ROLE_SYSTEM, message, call.id, call.name)
                        continue
                    }
                    if (evaluation.requiresConfirmation) {
                        task = moveTo(task, TaskStatus.WAITING_FOR_CONFIRMATION)
                    }

                    val outcome = policy.authorize(taskId, tool, parsed)
                    if (!outcome.allowed) {
                        if (evaluation.requiresConfirmation) task = moveTo(task, TaskStatus.RUNNING_HEADLESS)
                        val message = "DENIED: ${outcome.reason}"
                        conversation += LlmMessage.tool(call.id, call.name, message)
                        tasks.appendMessage(taskId, ChatMessage.ROLE_SYSTEM, message, call.id, call.name)
                        continue
                    }

                    val interactive = tool.spec.requiresAccessibility
                    task = moveTo(task, if (interactive) TaskStatus.RUNNING_INTERACTIVE else TaskStatus.RUNNING_HEADLESS)
                    runs.setCurrentTool(taskId, tool.spec.name)

                    val rowId = tasks.startToolCall(
                        taskId = taskId,
                        executionId = executionId,
                        toolName = tool.spec.name,
                        argumentsJson = redact(parsed.toString()).take(8_000),
                        risk = outcome.risk.name,
                        approved = true,
                    )
                    tasks.addEvent(
                        taskId,
                        executionId,
                        AgentEventType.TOOL_STARTED,
                        "${tool.spec.name} started (${outcome.risk})",
                    )
                    val startedAt = System.currentTimeMillis()
                    val result = executeTool(tool, parsed, taskId, call.id, appSettings.toolTimeoutMs)
                    val duration = System.currentTimeMillis() - startedAt
                    val verified = verifier.verifyToolResult(tool, result)
                    tasks.finishToolCall(rowId, result)
                    tasks.addEvent(
                        taskId,
                        executionId,
                        if (result.ok) AgentEventType.TOOL_COMPLETED else AgentEventType.TOOL_FAILED,
                        "${tool.spec.name} ${if (result.ok) "succeeded" else "failed"} in ${duration} ms" +
                            (result.error?.let { ": ${redact(it)}" } ?: ""),
                    )

                    result.artifacts.forEach { path ->
                        val file = File(path)
                        if (file.exists() && file.isFile) {
                            tasks.recordArtifact(
                                taskId = taskId,
                                name = file.name,
                                path = file.absolutePath,
                                mime = guessMime(file.name),
                                sizeBytes = file.length(),
                                description = "Produced by ${tool.spec.name}",
                            )
                        }
                    }

                    val modelText = redact(result.toModelText()).take(6_000)
                    conversation += LlmMessage.tool(call.id, tool.spec.name, modelText)
                    tasks.appendMessage(
                        taskId,
                        ChatMessage.ROLE_TOOL,
                        modelText.take(4_000),
                        call.id,
                        tool.spec.name,
                    )

                    toolCount++
                    plan = if (result.ok) {
                        verifier.advancePlan(plan, StepStatus.DONE, result.summary)
                    } else {
                        verifier.advancePlan(plan, StepStatus.FAILED, result.error)
                    }
                    plan?.let { tasks.updatePlan(taskId, it) }
                    runs.setPlan(taskId, plan)
                    runs.setCurrentTool(taskId, null)

                    if (!result.ok) {
                        lastError = result.error
                        if (verified.notes.isNotEmpty()) {
                            tasks.addEvent(
                                taskId,
                                executionId,
                                AgentEventType.DIAGNOSTIC,
                                verified.notes.joinToString("; "),
                            )
                        }
                    }

                    task = tasks.getTask(taskId) ?: task
                    tasks.bumpIteration(taskId, iteration, toolCount, model.label)
                    checkpoints.save(
                        task = task,
                        plan = plan,
                        iteration = iteration,
                        lastError = lastError,
                        recoveryStrategy = "Verify the effect of the last tool before retrying it.",
                        permissions = permissionSnapshot(),
                    )
                }

                task = tasks.getTask(taskId) ?: task
                if (tasks.isCancelRequested(taskId)) {
                    finishTask(task, executionId, TaskStatus.CANCELLED, "Cancelled by user.", plan)
                    return
                }
            }

            failTask(
                task,
                executionId,
                "Reached the maximum of ${appSettings.maxIterations} model iterations without finishing. " +
                    "Progress and checkpoints are saved; resume the task to continue.",
                plan,
            )
        } catch (c: CancellationException) {
            tasks.addEvent(taskId, executionId, AgentEventType.TASK_CANCELLED, "Execution cancelled")
            runCatching { tasks.updateStatus(taskId, TaskStatus.CANCELLED) }
            runs.finish(taskId, TaskStatus.CANCELLED)
            throw c
        } catch (t: Throwable) {
            val message = "Unhandled harness error: ${t.message ?: t::class.java.simpleName}"
            runCatching {
                tasks.addEvent(taskId, executionId, AgentEventType.TASK_FAILED, message)
                tasks.updateStatus(taskId, TaskStatus.FAILED, message)
                tasks.appendMessage(taskId, ChatMessage.ROLE_SYSTEM, "Task failed: $message")
                runs.finish(taskId, TaskStatus.FAILED, message)
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private suspend fun callModel(
        taskId: String,
        executionId: String,
        model: ResolvedModel,
        toolSpecs: List<com.mobileagent.core.model.LlmToolSpec>,
        conversation: MutableList<LlmMessage>,
        temperature: Double,
        maxTokens: Int,
    ): ModelCallResult {
        val retries = settings.current().retries
        var attempt = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            try {
                return ModelCallResult(
                    response = llm.chat(
                        config = model.provider,
                        apiKey = model.apiKey,
                        request = ChatRequest(
                            messages = conversation.toList(),
                            tools = toolSpecs,
                            temperature = temperature,
                            maxTokens = maxTokens,
                            stream = true,
                        ),
                        onDelta = { delta -> runs.appendDraft(taskId, delta) },
                    ),
                    error = null,
                )
            } catch (e: LlmException) {
                attempt++
                runs.setDraft(taskId, "")
                tasks.addEvent(
                    taskId,
                    executionId,
                    AgentEventType.DIAGNOSTIC,
                    "Model call failed (attempt $attempt/${retries + 1}): ${e.kind} ${e.httpStatus ?: ""} ${e.describe()}",
                )
                if (!e.retryable || attempt > retries) return ModelCallResult(null, e)
                delay(1_000L * attempt)
            }
        }
    }

    private data class ModelCallResult(
        val response: com.mobileagent.core.model.ChatResponse?,
        val error: LlmException?,
    )

    private suspend fun executeTool(
        tool: com.mobileagent.core.model.AgentTool,
        arguments: JsonObject,
        taskId: String,
        callId: String,
        timeoutMs: Long,
    ): ToolResult {
        val startedAt = System.currentTimeMillis()
        val executed = withTimeoutOrNull(timeoutMs) {
            runCatching {
                tool.execute(
                    ToolContext(
                        taskId = taskId,
                        callId = callId,
                        isCancelled = { false },
                        progress = { runs.setCurrentTool(taskId, tool.spec.name) },
                    ),
                    arguments,
                )
            }.getOrElse { error ->
                ToolResult(
                    callId = callId,
                    toolName = tool.spec.name,
                    ok = false,
                    output = JsonObject(emptyMap()),
                    summary = "Tool crashed: ${error.message ?: error::class.java.simpleName}",
                    error = error.message ?: error::class.java.simpleName,
                    durationMs = System.currentTimeMillis() - startedAt,
                )
            }
        }
        return executed ?: ToolResult(
            callId = callId,
            toolName = tool.spec.name,
            ok = false,
            output = JsonObject(mapOf("timeoutMs" to JsonPrimitive(timeoutMs))),
            summary = "Timed out after $timeoutMs ms",
            error = "timeout",
            durationMs = System.currentTimeMillis() - startedAt,
        )
    }

    private suspend fun completeTask(
        task: TaskRecord,
        executionId: String,
        answer: String,
        plan: TaskPlan?,
        iteration: Int,
    ) {
        val verification = verifier.verifyCompletion(task.id, answer, plan)
        if (!verification.ok) {
            val reason = verification.hardIssues.joinToString("; ").ifBlank { "Verification failed." }
            tasks.appendMessage(task.id, ChatMessage.ROLE_SYSTEM, "Verification failed: $reason")
            failTask(task, executionId, "Task output failed verification: $reason", plan)
            return
        }

        val finalText = buildString {
            append(answer.trim())
            if (verification.notes.isNotEmpty()) {
                append("\n\n---\nVerification notes:\n")
                verification.notes.forEach { append("- ").append(it).append('\n') }
            }
        }

        val updatedPlan = plan?.let { current ->
            current.copy(steps = current.steps.map {
                if (it.status == StepStatus.PENDING || it.status == StepStatus.RUNNING) {
                    it.copy(status = StepStatus.DONE)
                } else {
                    it
                }
            })
        }
        updatedPlan?.let { tasks.updatePlan(task.id, it) }
        tasks.setResult(task.id, finalText)
        tasks.addEvent(
            task.id,
            executionId,
            AgentEventType.TASK_COMPLETED,
            "Task completed after $iteration iteration(s)",
            JsonObject(
                mapOf(
                    "answerChars" to JsonPrimitive(finalText.length),
                    "verificationNotes" to JsonPrimitive(verification.notes.size),
                ),
            ),
        )
        tasks.updateStatus(task.id, TaskStatus.COMPLETED)
        runs.finish(task.id, TaskStatus.COMPLETED)
        runCatching {
            checkpoints.save(
                task = tasks.getTask(task.id) ?: task,
                plan = updatedPlan,
                iteration = iteration,
                lastError = null,
                recoveryStrategy = "Completed.",
                permissions = permissionSnapshot(),
            )
        }
    }

    private suspend fun failTask(
        task: TaskRecord,
        executionId: String,
        message: String,
        plan: TaskPlan? = null,
    ) {
        tasks.addEvent(task.id, executionId, AgentEventType.TASK_FAILED, message)
        tasks.appendMessage(task.id, ChatMessage.ROLE_SYSTEM, "Task failed: $message")
        tasks.updateStatus(task.id, TaskStatus.FAILED, message)
        runs.finish(task.id, TaskStatus.FAILED, message)
        runCatching {
            checkpoints.save(
                task = tasks.getTask(task.id) ?: task,
                plan = plan ?: task.plan,
                iteration = task.iteration,
                lastError = message,
                recoveryStrategy = "Inspect the last tool result, verify side effects, then resume.",
                permissions = permissionSnapshot(),
            )
        }
    }

    private suspend fun finishTask(
        task: TaskRecord,
        executionId: String,
        status: TaskStatus,
        message: String,
        plan: TaskPlan? = null,
    ) {
        tasks.addEvent(task.id, executionId, AgentEventType.TASK_CANCELLED, message)
        tasks.updateStatus(task.id, status, message)
        runs.finish(task.id, status, message)
        runCatching {
            checkpoints.save(
                task = tasks.getTask(task.id) ?: task,
                plan = plan ?: task.plan,
                iteration = task.iteration,
                lastError = message,
                recoveryStrategy = "Resume when the blocking condition is resolved.",
                permissions = permissionSnapshot(),
            )
        }
    }

    private suspend fun moveTo(task: TaskRecord, target: TaskStatus): TaskRecord {
        if (task.status == target) return task
        if (!TaskStateMachine.canTransition(task.status, target)) {
            tasks.addEvent(
                task.id,
                null,
                AgentEventType.DIAGNOSTIC,
                "Rejected illegal transition ${task.status} -> $target",
            )
            return task
        }
        tasks.updateStatus(task.id, target)
        runs.updateStatus(task.id, target)
        tasks.addEvent(task.id, null, AgentEventType.TASK_STATUS_CHANGED, "${task.status} -> $target")
        return task.copy(status = target)
    }

    private fun permissionSnapshot(): Map<String, Boolean> = mapOf(
        "shizukuRunning" to capabilities.shizukuAvailable(),
        "shizukuPermission" to capabilities.shizukuPermissionGranted(),
        "accessibility" to capabilities.accessibilityConnected(),
        "localShell" to capabilities.localShellAvailable(),
    )

    private fun redact(text: String): String = com.mobileagent.core.security.Redactor.redact(text)

    private fun guessMime(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "md", "markdown" -> "text/markdown"
        "json" -> "application/json"
        "txt", "log" -> "text/plain"
        "csv" -> "text/csv"
        "html" -> "text/html"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "pdf" -> "application/pdf"
        else -> "application/octet-stream"
    }

    /** Exposes the loaded plan for UI queries without touching the database directly. */
    suspend fun planOf(taskId: String): TaskPlan? = tasks.getTask(taskId)?.plan

    /** Utility used by tests to inspect the exact serialized conversation. */
    fun encodeConversation(messages: List<LlmMessage>): String =
        JsonCodec.json.encodeToString(kotlinx.serialization.builtins.ListSerializer(LlmMessage.serializer()), messages)
}
