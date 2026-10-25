package com.mobileagent.core.security

import com.mobileagent.core.model.AgentTool
import com.mobileagent.core.model.AgentEventType
import com.mobileagent.core.model.RiskLevel
import com.mobileagent.core.model.ToolCategory
import com.mobileagent.core.data.TaskRepository
import com.mobileagent.core.settings.ApprovalMode
import com.mobileagent.core.settings.SettingsStore
import kotlinx.serialization.json.JsonObject

/**
 * Live view of what the device currently allows. Implemented by the execution layer so the policy
 * engine never assumes a capability that is not actually present.
 */
interface RuntimeCapabilities {
    fun shizukuAvailable(): Boolean
    fun shizukuPermissionGranted(): Boolean
    fun accessibilityConnected(): Boolean
    fun localShellAvailable(): Boolean
}

data class PolicyOutcome(
    val allowed: Boolean,
    val requiresConfirmation: Boolean,
    val reason: String,
    val risk: RiskLevel,
    val deniedBy: String? = null,
)

/**
 * The single authorization gate for every tool call. A model can never change a risk level or
 * grant itself permission: the decision depends only on the tool spec, user settings and live
 * device capabilities.
 */
class PolicyEngine(
    private val settings: SettingsStore,
    private val capabilities: RuntimeCapabilities,
    private val approvals: ApprovalBus,
    private val tasks: TaskRepository,
) {

    suspend fun evaluate(tool: AgentTool, arguments: JsonObject): PolicyOutcome {
        val spec = tool.spec
        val current = settings.current()

        if (spec.requiresShizuku) {
            if (!current.allowShizuku) {
                return PolicyOutcome(false, false, "Shizuku execution is turned off in Settings > Execution.", spec.risk, "setting")
            }
            if (!capabilities.shizukuAvailable()) {
                return PolicyOutcome(false, false, "Shizuku is not running. Start Shizuku, then retry.", spec.risk, "capability")
            }
            if (!capabilities.shizukuPermissionGranted()) {
                return PolicyOutcome(false, false, "Mobile Agent has not been granted Shizuku permission.", spec.risk, "permission")
            }
        }

        if (spec.requiresAccessibility) {
            if (!current.allowAccessibility) {
                return PolicyOutcome(false, false, "Accessibility automation is turned off in Settings > Execution.", spec.risk, "setting")
            }
            if (!capabilities.accessibilityConnected()) {
                return PolicyOutcome(false, false, "The Accessibility service is not connected. Enable it in Settings > Accessibility.", spec.risk, "capability")
            }
        }

        if (spec.category == ToolCategory.VISION && !current.allowCloudScreenshots) {
            return PolicyOutcome(false, false, "Sending screenshots to a cloud model is disabled. Enable it in Settings > Security.", spec.risk, "setting")
        }

        if (spec.category == ToolCategory.SHELL && !current.allowLocalShell && !spec.requiresShizuku) {
            return PolicyOutcome(false, false, "Local shell execution is disabled in Settings > Execution.", spec.risk, "setting")
        }

        val mustAsk = when (current.approvalMode) {
            ApprovalMode.ALWAYS_ASK -> true
            ApprovalMode.HIGH_RISK_ASK -> spec.risk.requiresConfirmation
            ApprovalMode.AUTO_APPROVE_SAFE -> spec.risk == RiskLevel.CRITICAL
        }

        return PolicyOutcome(
            allowed = true,
            requiresConfirmation = mustAsk,
            reason = if (mustAsk) {
                "This ${spec.risk.name.lowercase()} risk action needs your confirmation."
            } else {
                "Allowed by current policy."
            },
            risk = spec.risk,
        )
    }

    /**
     * Evaluates and, when required, suspends until the user answers. Returns the final outcome so
     * the caller can log a precise denial reason.
     */
    suspend fun authorize(taskId: String, tool: AgentTool, arguments: JsonObject): PolicyOutcome {
        val outcome = evaluate(tool, arguments)
        if (!outcome.allowed) {
            tasks.addEvent(taskId, null, AgentEventType.TOOL_DENIED, "${tool.spec.name} denied: ${outcome.reason}")
            return outcome
        }
        if (!outcome.requiresConfirmation) {
            tasks.addEvent(taskId, null, AgentEventType.TOOL_AUTHORIZED, "${tool.spec.name} auto-authorized (${outcome.risk})")
            return outcome
        }

        tasks.addEvent(taskId, null, AgentEventType.APPROVAL_REQUESTED, "Waiting for confirmation to run ${tool.spec.name}")
        val request = ApprovalRequest(
            taskId = taskId,
            toolName = tool.spec.name,
            title = tool.spec.title,
            risk = outcome.risk,
            summary = tool.spec.description,
            argumentsJson = arguments.toString(),
            target = (arguments["path"] ?: arguments["command"] ?: arguments["url"] ?: arguments["package"] ?: arguments["query"])
                ?.toString()?.take(300) ?: "device",
            canUndo = outcome.risk != RiskLevel.CRITICAL,
        )
        val approved = approvals.request(request)
        tasks.addEvent(
            taskId,
            null,
            if (approved) AgentEventType.APPROVAL_GRANTED else AgentEventType.APPROVAL_REJECTED,
            if (approved) "User approved ${tool.spec.name}" else "User rejected ${tool.spec.name}",
        )
        return if (approved) {
            outcome.copy(requiresConfirmation = false, reason = "Approved by user.")
        } else {
            outcome.copy(allowed = false, reason = "The user did not approve this action.", deniedBy = "user")
        }
    }
}
