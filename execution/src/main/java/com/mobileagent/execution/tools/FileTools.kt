package com.mobileagent.execution.tools

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.mobileagent.core.model.AgentTool
import com.mobileagent.core.model.RiskLevel
import com.mobileagent.core.model.ToolCategory
import com.mobileagent.core.model.ToolContext
import com.mobileagent.core.model.ToolResult
import com.mobileagent.core.model.ToolSpec
import com.mobileagent.execution.workspace.Workspace
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val MAX_READ_BYTES = 512_000
private const val MAX_WRITE_BYTES = 2_000_000
private const val MAX_IMPORT_BYTES = 20_000_000

/** Lists a directory inside the agent workspace. */
class FileListTool(private val workspace: Workspace) : AgentTool {

    override val spec: ToolSpec = ToolSpec(
        name = "file.list",
        title = "List workspace files",
        description = "List files and directories inside the agent workspace. Paths are relative to the workspace root.",
        parametersSchema = Schemas.obj(
            required = emptyList(),
            props = mapOf(
                "path" to Schemas.str("Relative directory path, defaults to the workspace root"),
                "recursive" to Schemas.bool("List subdirectories recursively"),
            ),
        ),
        risk = RiskLevel.LOW,
        category = ToolCategory.FILE,
        source = "execution",
    )

    override suspend fun execute(context: ToolContext, arguments: JsonObject): ToolResult {
        val startedAt = System.currentTimeMillis()
        val path = arguments["path"]?.jsonPrimitive?.content.orEmpty()
        val recursive = arguments["recursive"]?.jsonPrimitive?.booleanOrNull ?: false
        val dir = try {
            workspace.resolve(path.ifBlank { "." })
        } catch (e: SecurityException) {
            return failure(context, spec.name, e.message ?: "invalid path", startedAt)
        }
        if (!dir.exists()) return failure(context, spec.name, "Directory does not exist: $path", startedAt)
        if (!dir.isDirectory) return failure(context, spec.name, "Not a directory: $path", startedAt)

        val files = (if (recursive) dir.walkTopDown() else dir.listFiles()?.asSequence()?.map { it } ?: emptySequence())
            .take(500)
            .toList()

        val entries = files.take(300).map { file ->
            buildJsonObject {
                put("name", JsonPrimitive(file.name))
                put("path", JsonPrimitive(workspace.relative(file)))
                put("directory", JsonPrimitive(file.isDirectory))
                put("sizeBytes", JsonPrimitive(if (file.isFile) file.length() else 0L))
                put("modifiedAt", JsonPrimitive(DATE_FORMAT.format(Date(file.lastModified()))))
            }
        }

        val output = buildJsonObject {
            put("path", JsonPrimitive(workspace.relative(dir)))
            put("entries", kotlinx.serialization.json.JsonArray(entries))
            put("count", JsonPrimitive(entries.size))
            put("truncated", JsonPrimitive(files.size > 300))
            put("workspaceFiles", JsonPrimitive(workspace.fileCount()))
            put("workspaceUsedBytes", JsonPrimitive(workspace.usedBytes()))
        }
        return success(
            context = context,
            toolName = spec.name,
            summary = "${entries.size} entry(ies) under ${workspace.relative(dir)}",
            startedAt = startedAt,
            output = output,
        )
    }

    private companion object {
        val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    }
}

/** Reads a text file from the workspace. */
class FileReadTool(private val workspace: Workspace) : AgentTool {

    override val spec: ToolSpec = ToolSpec(
        name = "file.read",
        title = "Read workspace file",
        description = "Read a UTF-8 text file from the agent workspace. Binary files are rejected. " +
            "Output is truncated at $MAX_READ_BYTES bytes.",
        parametersSchema = Schemas.obj(
            required = listOf("path"),
            props = mapOf(
                "path" to Schemas.str("Relative file path inside the workspace"),
                "offset" to Schemas.int("Byte offset to start reading", 0),
                "maxBytes" to Schemas.int("Maximum bytes to return", 1, MAX_READ_BYTES),
            ),
        ),
        risk = RiskLevel.LOW,
        category = ToolCategory.FILE,
        source = "execution",
    )

    override suspend fun execute(context: ToolContext, arguments: JsonObject): ToolResult {
        val startedAt = System.currentTimeMillis()
        val path = arguments["path"]?.jsonPrimitive?.content.orEmpty()
        val offset = (arguments["offset"]?.jsonPrimitive?.intOrNull ?: 0).coerceAtLeast(0)
        val maxBytes = (arguments["maxBytes"]?.jsonPrimitive?.intOrNull ?: MAX_READ_BYTES).coerceIn(1, MAX_READ_BYTES)

        val file = try {
            workspace.resolve(path)
        } catch (e: SecurityException) {
            return failure(context, spec.name, e.message ?: "invalid path", startedAt)
        }
        if (!file.exists()) return failure(context, spec.name, "File does not exist: $path", startedAt)
        if (!file.isFile) return failure(context, spec.name, "Not a file: $path", startedAt)
        if (file.length() > 8_000_000L) {
            return failure(context, spec.name, "File is too large (${file.length()} bytes); use file.read with offset on smaller pieces", startedAt)
        }

        val bytes = file.readBytes()
        val slice = bytes.copyOfRange(offset.coerceAtMost(bytes.size), (offset + maxBytes).coerceAtMost(bytes.size))
        if (slice.any { it == 0.toByte() }) {
            return failure(context, spec.name, "Binary file detected; only text files can be read", startedAt)
        }

        val output = buildJsonObject {
            put("path", JsonPrimitive(workspace.relative(file)))
            put("absolutePath", JsonPrimitive(file.absolutePath))
            put("content", JsonPrimitive(String(slice, Charsets.UTF_8)))
            put("bytes", JsonPrimitive(slice.size))
            put("totalBytes", JsonPrimitive(bytes.size))
            put("truncated", JsonPrimitive(offset + slice.size < bytes.size))
        }
        return success(
            context = context,
            toolName = spec.name,
            summary = "Read ${slice.size} of ${bytes.size} byte(s) from ${workspace.relative(file)}",
            startedAt = startedAt,
            output = output,
        )
    }
}

/** Writes a text file into the workspace. */
class FileWriteTool(private val workspace: Workspace) : AgentTool {

    override val spec: ToolSpec = ToolSpec(
        name = "file.write",
        title = "Write workspace file",
        description = "Create or overwrite a UTF-8 text file inside the agent workspace. Parent directories are created. " +
            "Maximum size is $MAX_WRITE_BYTES bytes. Returns the path that can be reported to the user.",
        parametersSchema = Schemas.obj(
            required = listOf("path", "content"),
            props = mapOf(
                "path" to Schemas.str("Relative file path inside the workspace, e.g. exports/report.md"),
                "content" to Schemas.str("Full UTF-8 text content"),
                "append" to Schemas.bool("Append to the file instead of overwriting"),
            ),
        ),
        risk = RiskLevel.MEDIUM,
        category = ToolCategory.FILE,
        source = "execution",
    )

    override suspend fun execute(context: ToolContext, arguments: JsonObject): ToolResult {
        val startedAt = System.currentTimeMillis()
        val path = arguments["path"]?.jsonPrimitive?.content.orEmpty()
        val content = arguments["content"]?.jsonPrimitive?.content ?: ""
        val append = arguments["append"]?.jsonPrimitive?.booleanOrNull ?: false

        val bytes = content.toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_WRITE_BYTES) {
            return failure(context, spec.name, "Content is ${bytes.size} bytes; the limit is $MAX_WRITE_BYTES", startedAt)
        }

        val file = try {
            workspace.resolve(path)
        } catch (e: SecurityException) {
            return failure(context, spec.name, e.message ?: "invalid path", startedAt)
        }
        if (file.isDirectory) return failure(context, spec.name, "$path is a directory", startedAt)

        return try {
            workspace.ensureParent(file)
            if (append) file.appendText(content, Charsets.UTF_8) else file.writeText(content, Charsets.UTF_8)
            val output = buildJsonObject {
                put("path", JsonPrimitive(file.absolutePath))
                put("relativePath", JsonPrimitive(workspace.relative(file)))
                put("bytes", JsonPrimitive(file.length()))
                put("append", JsonPrimitive(append))
            }
            success(
                context = context,
                toolName = spec.name,
                summary = "Wrote ${file.length()} byte(s) to ${workspace.relative(file)}",
                startedAt = startedAt,
                artifacts = listOf(file.absolutePath),
                output = output,
            )
        } catch (t: Throwable) {
            failure(context, spec.name, "Write failed: ${t.message}", startedAt)
        }
    }
}

/** Moves or renames a workspace file. */
class FileMoveTool(private val workspace: Workspace) : AgentTool {

    override val spec: ToolSpec = ToolSpec(
        name = "file.move",
        title = "Move workspace file",
        description = "Move or rename a file or directory inside the workspace.",
        parametersSchema = Schemas.obj(
            required = listOf("from", "to"),
            props = mapOf(
                "from" to Schemas.str("Source path"),
                "to" to Schemas.str("Destination path"),
            ),
        ),
        risk = RiskLevel.MEDIUM,
        category = ToolCategory.FILE,
        source = "execution",
    )

    override suspend fun execute(context: ToolContext, arguments: JsonObject): ToolResult {
        val startedAt = System.currentTimeMillis()
        val from = arguments["from"]?.jsonPrimitive?.content.orEmpty()
        val to = arguments["to"]?.jsonPrimitive?.content.orEmpty()
        val source = try {
            workspace.resolve(from)
        } catch (e: SecurityException) {
            return failure(context, spec.name, e.message ?: "invalid source", startedAt)
        }
        val target = try {
            workspace.resolve(to)
        } catch (e: SecurityException) {
            return failure(context, spec.name, e.message ?: "invalid destination", startedAt)
        }
        if (!source.exists()) return failure(context, spec.name, "Source does not exist: $from", startedAt)
        workspace.ensureParent(target)
        if (target.exists() && !target.deleteRecursively()) {
            return failure(context, spec.name, "Destination exists and could not be replaced: $to", startedAt)
        }
        val moved = if (source.renameTo(target)) {
            true
        } else {
            runCatching {
                source.copyRecursively(target, overwrite = true)
                source.deleteRecursively()
            }.getOrDefault(false)
        }
        if (!moved) return failure(context, spec.name, "Move failed (cross-device or permission issue)", startedAt)

        val output = buildJsonObject {
            put("from", JsonPrimitive(workspace.relative(source)))
            put("to", JsonPrimitive(workspace.relative(target)))
            put("exists", JsonPrimitive(target.exists()))
        }
        return success(context, spec.name, "Moved ${workspace.relative(source)} to ${workspace.relative(target)}", startedAt, output = output)
    }
}

/** Deletes a workspace file or directory. High risk: always confirmed by policy. */
class FileDeleteTool(private val workspace: Workspace) : AgentTool {

    override val spec: ToolSpec = ToolSpec(
        name = "file.delete",
        title = "Delete workspace file",
        description = "Delete a file or directory inside the agent workspace. This is irreversible and always requires confirmation.",
        parametersSchema = Schemas.obj(
            required = listOf("path"),
            props = mapOf(
                "path" to Schemas.str("Relative path to delete"),
                "recursive" to Schemas.bool("Required to delete a non-empty directory"),
            ),
        ),
        risk = RiskLevel.HIGH,
        category = ToolCategory.FILE,
        source = "execution",
    )

    override suspend fun execute(context: ToolContext, arguments: JsonObject): ToolResult {
        val startedAt = System.currentTimeMillis()
        val path = arguments["path"]?.jsonPrimitive?.content.orEmpty()
        val recursive = arguments["recursive"]?.jsonPrimitive?.booleanOrNull ?: false
        val file = try {
            workspace.resolve(path)
        } catch (e: SecurityException) {
            return failure(context, spec.name, e.message ?: "invalid path", startedAt)
        }
        if (file == workspace.root) return failure(context, spec.name, "Refusing to delete the workspace root", startedAt)
        if (!file.exists()) return failure(context, spec.name, "Path does not exist: $path", startedAt)

        val deleted = if (file.isDirectory) {
            if (!recursive && file.list()?.isNotEmpty() == true) {
                return failure(context, spec.name, "Directory is not empty; pass recursive=true to delete it", startedAt)
            }
            file.deleteRecursively()
        } else {
            file.delete()
        }
        if (!deleted) return failure(context, spec.name, "Delete failed: $path", startedAt)

        val output = buildJsonObject {
            put("path", JsonPrimitive(workspace.relative(file)))
            put("deleted", JsonPrimitive(true))
        }
        return success(context, spec.name, "Deleted ${workspace.relative(file)}", startedAt, output = output)
    }
}

/** Imports a user-chosen document (SAF URI) into the workspace. */
class FileImportTool(
    private val appContext: Context,
    private val workspace: Workspace,
) : AgentTool {

    override val spec: ToolSpec = ToolSpec(
        name = "file.import",
        title = "Import a document",
        description = "Copy a document the user picked with the system file picker (content:// URI) into the workspace. " +
            "Maximum 20 MB. Use this instead of assuming access to the user's storage.",
        parametersSchema = Schemas.obj(
            required = listOf("uri"),
            props = mapOf(
                "uri" to Schemas.str("content:// URI granted by the user"),
                "destination" to Schemas.str("Optional workspace path; defaults to cache/<display name>"),
            ),
        ),
        risk = RiskLevel.MEDIUM,
        category = ToolCategory.FILE,
        source = "execution",
    )

    override suspend fun execute(context: ToolContext, arguments: JsonObject): ToolResult {
        val startedAt = System.currentTimeMillis()
        val uriText = arguments["uri"]?.jsonPrimitive?.content.orEmpty()
        if (!uriText.startsWith("content://")) {
            return failure(context, spec.name, "Only content:// URIs granted through the picker are supported", startedAt)
        }
        val uri = Uri.parse(uriText)
        val displayName = queryDisplayName(uri) ?: "imported-${System.currentTimeMillis()}.bin"
        val destination = arguments["destination"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: "cache/$displayName"
        val target = try {
            workspace.resolve(destination)
        } catch (e: SecurityException) {
            return failure(context, spec.name, e.message ?: "invalid destination", startedAt)
        }

        return try {
            val input = appContext.contentResolver.openInputStream(uri)
                ?: return failure(context, spec.name, "Could not open the document (permission expired?)", startedAt)
            workspace.ensureParent(target)
            input.use { stream ->
                target.outputStream().use { output ->
                    val copied = stream.copyTo(output)
                    if (copied > MAX_IMPORT_BYTES) {
                        target.delete()
                        return failure(context, spec.name, "Document is larger than 20 MB", startedAt)
                    }
                }
            }
            val result = buildJsonObject {
                put("path", JsonPrimitive(target.absolutePath))
                put("relativePath", JsonPrimitive(workspace.relative(target)))
                put("bytes", JsonPrimitive(target.length()))
                put("sourceUri", JsonPrimitive(uriText))
            }
            success(
                context = context,
                toolName = spec.name,
                summary = "Imported $displayName (${target.length()} bytes) into ${workspace.relative(target)}",
                startedAt = startedAt,
                artifacts = listOf(target.absolutePath),
                output = result,
            )
        } catch (t: Throwable) {
            failure(context, spec.name, "Import failed: ${t.message}", startedAt)
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        val projection = arrayOf(OpenableColumns.DISPLAY_NAME)
        return appContext.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) cursor.getString(index)?.replace(File.separatorChar, '_') else null
            } else {
                null
            }
        }
    }
}
