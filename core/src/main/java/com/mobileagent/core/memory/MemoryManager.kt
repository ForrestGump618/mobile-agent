package com.mobileagent.core.memory

import com.mobileagent.core.data.IntegrationRepository
import com.mobileagent.core.settings.SettingsStore
import java.util.UUID

/** Pluggable embedding hook. When no embedding model is configured the agent falls back to keywords. */
interface EmbeddingProvider {
    val id: String
    suspend fun embed(texts: List<String>): List<FloatArray>
}

fun cosineSimilarity(a: FloatArray, b: FloatArray): Double {
    if (a.isEmpty() || a.size != b.size) return 0.0
    var dot = 0.0
    var normA = 0.0
    var normB = 0.0
    for (i in a.indices) {
        dot += a[i] * b[i]
        normA += a[i] * a[i]
        normB += b[i] * b[i]
    }
    if (normA == 0.0 || normB == 0.0) return 0.0
    return dot / (kotlin.math.sqrt(normA) * kotlin.math.sqrt(normB))
}

/**
 * Long-term memory with a deterministic keyword retriever. Memories are user-visible and can be
 * disabled or deleted; sensitive entries are never included in prompts unless explicitly enabled.
 */
class MemoryManager(
    private val repository: IntegrationRepository,
    private val settings: SettingsStore,
) {

    suspend fun contextBlock(goal: String, limit: Int = 8): String {
        if (!settings.current().memoryEnabled) return ""
        val memories = repository.enabledMemories().filter { !it.sensitive }
        if (memories.isEmpty()) return ""
        val scored = memories
            .map { it to score(goal, it.key, it.value) }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .take(limit)
        if (scored.isEmpty()) return ""
        return buildString {
            append("Long-term memory (facts the user saved earlier):\n")
            scored.forEach { (memory, _) ->
                append("- [").append(memory.scope).append("] ").append(memory.key).append(": ").append(memory.value).append('\n')
            }
        }
    }

    suspend fun remember(
        scope: String,
        key: String,
        value: String,
        sensitive: Boolean = false,
    ) {
        repository.upsertMemory(
            id = UUID.randomUUID().toString(),
            scope = scope,
            key = key,
            value = value,
            sensitive = sensitive,
        )
    }

    suspend fun forget(id: String) = repository.deleteMemory(id)

    fun observe() = repository.observeMemories()

    private fun score(goal: String, key: String, value: String): Int {
        val haystack = "$key $value".lowercase()
        val words = goal.lowercase().split(Regex("[\\s,.;:!?，。；：！？]+")).filter { it.length >= 2 }
        if (words.isEmpty()) return 0
        return words.count { haystack.contains(it) }
    }
}
