package com.mobileagent.execution.shell

/** Hard blocks for commands that must never run silently, independent of model phrasing. */
object ShellCommandPolicy {

    private val catastrophic = listOf(
        Regex("""rm\s+(-[a-zA-Z]*[rf][a-zA-Z]*\s+)+/(\s|$)"""),
        Regex("""rm\s+(-[a-zA-Z]*[rf][a-zA-Z]*\s+)+/(system|vendor|data|sdcard)\b"""),
        Regex("""\bmkfs(\.[a-z0-9]+)?\b"""),
        Regex("""\bdd\b[^\n]*\bof=/dev/block"""),
        Regex("""\bdd\b[^\n]*\bof=/dev/"""),
        Regex(""">\s*/dev/block/"""),
        Regex("""\bformat\s+(userdata|system|cache)\b"""),
        Regex(""":\(\)\s*\{\s*:\|:&\s*\}\s*;:"""),
    )

    fun blockedReason(command: String): String? {
        val normalized = command.trim()
        if (normalized.isEmpty()) return "Command is empty"
        catastrophic.firstOrNull { it.containsMatchIn(normalized) }?.let { pattern ->
            return "Blocked by the safety policy (pattern: ${pattern.pattern}). " +
                "This command could destroy the device or its data and is never executed automatically."
        }
        return null
    }

    fun requiresShellFeatures(command: String): Boolean =
        command.contains("|") || command.contains("&&") || command.contains(">") || command.contains("$(")
}
