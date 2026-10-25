package com.mobileagent.core.tools

/**
 * OpenAI-compatible providers (including DeepSeek) only accept function names matching
 * `[A-Za-z0-9_-]{1,128}`. Internal tool names use dots for readability (`file.read`), so the wire
 * name must be sanitized. This was the cause of DeepSeek returning HTTP 400 for every chat request:
 * the agent always sent tools, and dotted names are rejected before the model runs.
 */
object ToolNames {

    private val invalid = Regex("[^A-Za-z0-9_-]")
    const val MAX_LENGTH = 64

    fun wireName(internalName: String): String {
        val sanitized = internalName.replace(invalid, "_").trim('_').take(MAX_LENGTH)
        return sanitized.ifBlank { "tool" }
    }

    fun isValidWireName(name: String): Boolean =
        name.isNotEmpty() && name.length <= 128 && name.matches(Regex("[A-Za-z0-9_-]+"))
}
