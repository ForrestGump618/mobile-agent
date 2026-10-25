package com.mobileagent.integrations.skills

import android.content.Context
import android.net.Uri
import com.mobileagent.core.data.IntegrationRepository
import com.mobileagent.core.model.SkillManifest
import com.mobileagent.core.model.SkillSummary
import com.mobileagent.core.model.SkillValidationResult
import com.mobileagent.core.tools.ToolRegistry
import com.mobileagent.execution.workspace.Workspace
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.zip.ZipInputStream

/**
 * Loads skills from assets, directories or a user-picked zip. Validation happens before anything is
 * installed; a skill never gains tools or permissions it does not declare.
 */
class SkillLoader(
    private val context: Context,
    private val repository: IntegrationRepository,
    private val registry: ToolRegistry,
    private val workspace: Workspace,
) {

    suspend fun seedBuiltins() {
        val names = runCatching { context.assets.list("skills")?.toList() }.getOrNull().orEmpty()
        names.forEach { folder ->
            runCatching {
                val base = "skills/$folder"
                val yaml = context.assets.open("$base/skill.yaml").bufferedReader().use { it.readText() }
                val instructions = context.assets.open("$base/SKILL.md").bufferedReader().use { it.readText() }
                val manifest = MiniYaml.toManifest(MiniYaml.parse(yaml), instructions, sourceUri = "builtin:$folder")
                if (repository.skill(manifest.id) == null) {
                    repository.upsertSkill(manifest, source = "builtin", enabled = true)
                }
            }
        }
    }

    fun validate(manifest: SkillManifest, baseDir: File?): SkillValidationResult {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        if (!manifest.id.matches(Regex("[a-z0-9][a-z0-9_-]{1,63}"))) {
            errors += "id must be 2-64 chars of lowercase letters, digits, '-' or '_'"
        }
        if (manifest.name.isBlank()) errors += "name is required"
        if (!manifest.version.matches(Regex("[0-9A-Za-z][0-9A-Za-z.+\\-]{0,31}"))) {
            errors += "version is invalid"
        }

        val runtime = manifest.runtime
        val supported = setOf("prompt", "python", "python3", "node", "shell", "bash")
        if (runtime !in supported) errors += "Unsupported runtime '$runtime' (allowed: ${supported.joinToString()})"

        if (runtime != "prompt") {
            val entry = manifest.entryPoint
            if (entry.isNullOrBlank()) {
                errors += "runtime '$runtime' requires an entry_point"
            } else if (baseDir != null) {
                val file = File(baseDir, entry).canonicalFile
                if (!file.path.startsWith(baseDir.canonicalFile.path + File.separator)) {
                    errors += "entry_point escapes the skill directory"
                } else if (!file.exists()) {
                    errors += "entry_point does not exist: $entry"
                }
            }
        } else if (manifest.requiredTools.isEmpty()) {
            warnings += "Prompt-only skill declares no required tools; it can guide the agent but cannot act by itself."
        }

        val missingTools = manifest.requiredTools.filter { !registry.contains(it) }
        if (missingTools.isNotEmpty()) {
            warnings += "Declared tool(s) not currently registered: ${missingTools.joinToString()}. " +
                "The skill will be unavailable until the providing integration is enabled."
        }

        if (manifest.permissions.shizuku && manifest.permissions.shell) {
            warnings += "This skill requests both shell and Shizuku access."
        }
        if (manifest.permissions.network) {
            warnings += "This skill may send data over the network."
        }

        return SkillValidationResult(errors.isEmpty(), errors, warnings)
    }

    suspend fun installFromDirectory(directory: File, source: String): Result<SkillSummary> {
        return try {
            val skillMd = File(directory, "SKILL.md")
            if (!skillMd.exists()) return Result.failure(IllegalArgumentException("SKILL.md is missing"))
            val instructions = skillMd.readText(StandardCharsets.UTF_8)
            val manifest = readManifest(directory, instructions, source)
                ?: return Result.failure(IllegalArgumentException("skill.yaml or skill.json is missing"))
            val validation = validate(manifest, directory)
            if (!validation.valid) {
                return Result.failure(IllegalArgumentException(validation.errors.joinToString("; ")))
            }
            repository.upsertSkill(manifest, source = source, enabled = true)
            Result.success(repository.skill(manifest.id)!!)
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    suspend fun installFromZip(uri: Uri, displayName: String): Result<SkillSummary> {
        return try {
            val targetRoot = File(workspace.subdirectory("skills"), "imported-${System.currentTimeMillis()}")
            targetRoot.mkdirs()
            val input = context.contentResolver.openInputStream(uri)
                ?: return Result.failure(IllegalStateException("Could not open $displayName"))
            input.use { extractZip(it, targetRoot) }

            val candidate = findSkillRoot(targetRoot)
                ?: return Result.failure(IllegalArgumentException("The archive does not contain SKILL.md"))
            installFromDirectory(candidate, source = "import:${uri.lastPathSegment ?: displayName}")
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    suspend fun setEnabled(id: String, enabled: Boolean) = repository.setSkillEnabled(id, enabled)

    suspend fun remove(id: String) = repository.deleteSkill(id)

    /** Guidance block for prompt-based skills that have their required tools available. */
    suspend fun guidanceBlock(maxSkills: Int = 4, maxCharsPerSkill: Int = 500): String {
        val skills = repository.skills().filter { it.enabled }
        if (skills.isEmpty()) return ""
        val usable = skills.filter { skill ->
            skill.manifest.requiredTools.isEmpty() || skill.manifest.requiredTools.all { registry.contains(it) }
        }.take(maxSkills)
        if (usable.isEmpty()) return ""
        return buildString {
            append("Skills available for this task (follow their instructions when relevant):\n")
            usable.forEach { skill ->
                append("\n### ").append(skill.name).append(" v").append(skill.version).append('\n')
                append(skill.manifest.instructions.take(maxCharsPerSkill).trim()).append('\n')
            }
        }
    }

    private fun readManifest(directory: File, instructions: String, source: String): SkillManifest? {
        val yaml = File(directory, "skill.yaml")
        if (yaml.exists()) {
            return runCatching {
                MiniYaml.toManifest(MiniYaml.parse(yaml.readText(StandardCharsets.UTF_8)), instructions, source)
            }.getOrNull()
        }
        val json = File(directory, "skill.json")
        if (json.exists()) {
            return runCatching {
                val obj = Json.parseToJsonElement(json.readText(StandardCharsets.UTF_8)).jsonObject
                SkillManifest(
                    id = obj["id"]?.jsonPrimitive?.content.orEmpty(),
                    name = obj["name"]?.jsonPrimitive?.content.orEmpty(),
                    version = obj["version"]?.jsonPrimitive?.content ?: "0.0.1",
                    description = obj["description"]?.jsonPrimitive?.content.orEmpty(),
                    instructions = instructions,
                    entryPoint = obj["entry_point"]?.jsonPrimitive?.content,
                    runtime = obj["runtime"]?.jsonPrimitive?.content ?: "prompt",
                    requiredTools = runCatching {
                        obj["required_tools"]?.jsonArray?.mapNotNull { it.jsonPrimitive.content }.orEmpty()
                    }.getOrDefault(emptyList()),
                    sourceUri = source,
                )
            }.getOrNull()
        }
        return null
    }

    private fun findSkillRoot(root: File): File? {
        if (File(root, "SKILL.md").exists()) return root
        return root.walkTopDown().firstOrNull { it.isFile && it.name == "SKILL.md" }?.parentFile
    }

    private fun extractZip(input: InputStream, target: File) {
        var entries = 0
        var totalBytes = 0L
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries++
                if (entries > 200) throw IllegalArgumentException("Archive has too many files")
                val name = entry.name.replace('\\', '/')
                if (name.startsWith("/") || name.contains("..")) throw IllegalArgumentException("Unsafe archive entry: $name")
                val outFile = File(target, name)
                if (!outFile.canonicalPath.startsWith(target.canonicalPath + File.separator)) {
                    throw IllegalArgumentException("Unsafe archive entry: $name")
                }
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    outFile.outputStream().use { out ->
                        val copied = zip.copyTo(out)
                        totalBytes += copied
                        if (totalBytes > 5_000_000L) throw IllegalArgumentException("Skill archive is larger than 5 MB")
                    }
                }
                zip.closeEntry()
            }
        }
    }
}
