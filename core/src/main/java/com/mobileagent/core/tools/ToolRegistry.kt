package com.mobileagent.core.tools

import com.mobileagent.core.model.AgentTool
import com.mobileagent.core.model.LlmToolSpec
import com.mobileagent.core.model.ToolSpec
import java.util.concurrent.ConcurrentHashMap

/**
 * Central registry. Every capability exposed to the model — local tools, MCP tools, skills —
 * must be registered here so that policy checks, logging and schema validation are uniform.
 *
 * Tool names are sanitized for the wire because OpenAI-compatible APIs reject dots in function
 * names. [resolveWireName] maps back to the internal name.
 */
class ToolRegistry {

    private val tools = ConcurrentHashMap<String, AgentTool>()
    private val wireNames = ConcurrentHashMap<String, String>()

    fun register(tool: AgentTool) {
        tools[tool.spec.name] = tool
    }

    fun registerAll(list: List<AgentTool>) = list.forEach { register(it) }

    fun unregister(name: String) {
        tools.remove(name)
        wireNames.entries.removeIf { it.value == name }
    }

    fun unregisterSource(source: String) {
        val removed = tools.entries.filter { it.value.spec.source == source }.map { it.key }
        removed.forEach { tools.remove(it) }
        wireNames.entries.removeIf { it.value in removed }
    }

    fun get(name: String): AgentTool? = tools[name]

    fun all(): List<AgentTool> = tools.values.sortedBy { it.spec.name }

    fun specs(): List<ToolSpec> = all().map { it.spec }

    fun llmTools(allowed: Set<String>? = null): List<LlmToolSpec> {
        val mapping = ConcurrentHashMap<String, String>()
        val used = HashSet<String>()
        val result = mutableListOf<LlmToolSpec>()

        all()
            .filter { allowed == null || allowed.contains(it.spec.name) }
            .forEach { tool ->
                val base = ToolNames.wireName(tool.spec.name)
                var candidate = base
                var suffix = 2
                while (!used.add(candidate)) {
                    val trimmed = base.take(ToolNames.MAX_LENGTH - 4)
                    candidate = "${trimmed}_$suffix"
                    suffix++
                }
                mapping[candidate] = tool.spec.name
                result += LlmToolSpec(
                    name = candidate,
                    description = tool.spec.description,
                    parameters = tool.spec.parametersSchema,
                )
            }

        wireNames.clear()
        wireNames.putAll(mapping)
        return result
    }

    fun resolveWireName(wireName: String): String? =
        wireNames[wireName] ?: if (tools.containsKey(wireName)) wireName else null

    fun wireNameOf(internalName: String): String? =
        wireNames.entries.firstOrNull { it.value == internalName }?.key

    fun contains(name: String): Boolean = tools.containsKey(name)
}
