package com.mobileagent.execution.workspace

import android.content.Context
import java.io.File

/**
 * Application-private workspace. Every file tool resolves paths through [resolve], so the agent
 * cannot read or write outside this directory (and never gets implicit access to the user's files).
 */
class Workspace(context: Context) {

    val root: File = File(context.filesDir, "workspace")

    init {
        listOf("tasks", "artifacts", "logs", "scripts", "skills", "cache", "exports").forEach {
            File(root, it).mkdirs()
        }
    }

    fun resolve(relativePath: String): File {
        val raw = relativePath.trim()
        require(raw.isNotEmpty()) { "path is empty" }
        val rootCanonical = root.canonicalFile
        val candidate = when {
            raw.startsWith(rootCanonical.absolutePath) -> File(raw)
            raw.startsWith("workspace/") -> File(root, raw.removePrefix("workspace/"))
            raw.startsWith("/") -> File(raw)
            else -> File(root, raw)
        }.canonicalFile

        if (candidate != rootCanonical && !candidate.path.startsWith(rootCanonical.path + File.separator)) {
            throw SecurityException("Path escapes the agent workspace: $relativePath")
        }
        return candidate
    }

    fun relative(file: File): String {
        val rootCanonical = root.canonicalFile
        val canonical = file.canonicalFile
        return if (canonical.path.startsWith(rootCanonical.path + File.separator)) {
            canonical.path.removePrefix(rootCanonical.path + File.separator)
        } else {
            canonical.path
        }
    }

    fun ensureParent(file: File) {
        file.parentFile?.mkdirs()
    }

    fun usedBytes(): Long = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    fun fileCount(): Int = root.walkTopDown().filter { it.isFile }.count()

    fun subdirectory(name: String): File {
        val dir = File(root, name)
        dir.mkdirs()
        return dir
    }
}
