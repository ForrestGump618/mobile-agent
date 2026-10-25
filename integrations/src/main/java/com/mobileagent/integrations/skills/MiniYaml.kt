package com.mobileagent.integrations.skills

import com.mobileagent.core.model.SkillManifest
import com.mobileagent.core.model.SkillPermissions

/**
 * Deliberately small YAML subset parser for skill.yaml: top-level scalars, one nested mapping
 * (permissions) and one list (required_tools). Anything deeper is rejected so a malformed manifest
 * fails loudly instead of being silently misread.
 */
object MiniYaml {

    fun parse(text: String): Map<String, Any?> {
        val root = LinkedHashMap<String, Any?>()
        var currentKey: String? = null

        text.lineSequence().forEachIndexed { index, rawLine ->
            val line = stripComment(rawLine)
            if (line.isBlank()) return@forEachIndexed
            val indent = line.takeWhile { it == ' ' }.length
            val content = line.trim()

            if (indent == 0) {
                require(content.contains(':')) { "Line ${index + 1}: expected 'key: value'" }
                val key = content.substringBefore(':').trim()
                val value = content.substringAfter(':').trim()
                require(key.isNotEmpty()) { "Line ${index + 1}: empty key" }
                currentKey = key
                root[key] = if (value.isEmpty()) LinkedHashMap<String, Any?>() else scalar(value)
            } else {
                require(indent <= 4) { "Line ${index + 1}: indentation deeper than one level is not supported" }
                val key = currentKey ?: throw IllegalArgumentException("Line ${index + 1}: nested value without a parent key")
                when {
                    content.startsWith("- ") -> {
                        val item = content.removePrefix("- ").trim()
                        val existing = root[key]
                        val list: MutableList<Any?> = when (existing) {
                            is MutableList<*> -> existing as MutableList<Any?>
                            is Map<*, *> -> if (existing.isEmpty()) mutableListOf<Any?>() else throw IllegalArgumentException(
                                "Line ${index + 1}: '$key' cannot be both a map and a list",
                            )
                            else -> mutableListOf<Any?>()
                        }
                        if (item.contains(": ") || item.endsWith(":")) {
                            val map = LinkedHashMap<String, Any?>()
                            map[item.substringBefore(':').trim()] = scalar(item.substringAfter(':', "").trim())
                            list.add(map)
                        } else {
                            list.add(scalar(item))
                        }
                        root[key] = list
                    }

                    content.contains(':') -> {
                        val nestedKey = content.substringBefore(':').trim()
                        val nestedValue = content.substringAfter(':').trim()
                        val existing = root[key]
                        val map: MutableMap<String, Any?> = when (existing) {
                            is MutableMap<*, *> -> existing as MutableMap<String, Any?>
                            is List<*> -> throw IllegalArgumentException("Line ${index + 1}: '$key' cannot be both a list and a map")
                            else -> LinkedHashMap()
                        }
                        map[nestedKey] = if (nestedValue.isEmpty()) "" else scalar(nestedValue)
                        root[key] = map
                    }

                    else -> throw IllegalArgumentException("Line ${index + 1}: expected 'key: value' or '- item'")
                }
            }
        }
        return root
    }

    fun toManifest(map: Map<String, Any?>, instructions: String, sourceUri: String? = null): SkillManifest {
        val permissions = (map["permissions"] as? Map<*, *>)?.let { perms ->
            SkillPermissions(
                network = perms.boolean("network"),
                files = perms.boolean("files"),
                shell = perms.boolean("shell"),
                shizuku = perms.boolean("shizuku"),
                accessibility = perms.boolean("accessibility"),
            )
        } ?: SkillPermissions()

        val id = map.string("id").ifBlank { map.string("name").lowercase().replace(Regex("[^a-z0-9_-]+"), "-") }
        return SkillManifest(
            id = id,
            name = map.string("name").ifBlank { id },
            version = map.string("version").ifBlank { "0.0.1" },
            description = map.string("description"),
            instructions = instructions,
            entryPoint = map.string("entry_point").ifBlank { map.string("entrypoint") }.ifBlank { null },
            runtime = map.string("runtime").ifBlank { "prompt" }.lowercase(),
            requiredTools = map.list("required_tools").ifEmpty { map.list("tools") },
            permissions = permissions,
            dependencies = map.list("dependencies"),
            author = map.string("author").ifBlank { null },
            sourceUri = sourceUri,
        )
    }

    private fun Map<*, *>.string(key: String): String = (this[key] as? String)?.trim().orEmpty()

    private fun Map<*, *>.list(key: String): List<String> =
        (this[key] as? List<*>)?.mapNotNull { it?.toString()?.trim()?.takeIf { value -> value.isNotEmpty() } }.orEmpty()

    private fun Map<*, *>.boolean(key: String): Boolean {
        val raw = this[key]?.toString()?.lowercase() ?: return false
        return raw == "true" || raw == "yes" || raw == "on"
    }

    private fun scalar(value: String): Any {
        val trimmed = value.trim().removeSurrounding("\"").removeSurrounding("'")
        return when {
            trimmed.equals("true", true) -> true
            trimmed.equals("false", true) -> false
            trimmed.toIntOrNull() != null -> trimmed.toInt()
            else -> trimmed
        }
    }

    private fun stripComment(line: String): String {
        var inSingle = false
        var inDouble = false
        line.forEachIndexed { index, c ->
            when (c) {
                '\'' -> if (!inDouble) inSingle = !inSingle
                '"' -> if (!inSingle) inDouble = !inDouble
                '#' -> if (!inSingle && !inDouble && (index == 0 || line[index - 1].isWhitespace())) {
                    return line.substring(0, index)
                }
            }
        }
        return line
    }
}
