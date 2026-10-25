package com.mobileagent.execution.tools

import com.mobileagent.core.model.AgentTool
import com.mobileagent.core.model.RiskLevel
import com.mobileagent.core.model.ToolCategory
import com.mobileagent.core.model.ToolContext
import com.mobileagent.core.model.ToolResult
import com.mobileagent.core.model.ToolSpec
import com.mobileagent.core.model.UiSnapshot
import com.mobileagent.execution.accessibility.AccessibilityBridge
import com.mobileagent.execution.workspace.Workspace
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File

private fun UiSnapshot.toJson(limit: Int = 150): JsonObject {
    val all = allNodes()
    val interesting = all.filter {
        it.visible && (it.text.isNotBlank() || it.contentDescription.isNotBlank() || it.clickable || it.editable || it.scrollable)
    }
    return buildJsonObject {
        put("package", JsonPrimitive(packageName))
        put("activity", JsonPrimitive(activityName.orEmpty()))
        put("capturedAt", JsonPrimitive(capturedAt))
        put("screenWidth", JsonPrimitive(screenWidth))
        put("screenHeight", JsonPrimitive(screenHeight))
        put("nodeCount", JsonPrimitive(all.size))
        put("shownNodes", JsonPrimitive(minOf(interesting.size, limit)))
        put("truncated", JsonPrimitive(truncated || interesting.size > limit))
        put(
            "nodes",
            JsonArray(
                interesting.take(limit).map { node ->
                    buildJsonObject {
                        put("id", JsonPrimitive(node.nodeId))
                        put("text", JsonPrimitive(node.text))
                        put("desc", JsonPrimitive(node.contentDescription))
                        put("class", JsonPrimitive(node.className.substringAfterLast('.')))
                        put("rid", JsonPrimitive(node.resourceId))
                        put("bounds", JsonPrimitive(node.bounds))
                        if (node.clickable) put("clickable", JsonPrimitive(true))
                        if (node.editable) put("editable", JsonPrimitive(true))
                        if (node.scrollable) put("scrollable", JsonPrimitive(true))
                        if (node.checkable) put("checked", JsonPrimitive(node.checked))
                    }
                },
            ),
        )
    }
}

class UiSnapshotTool : AgentTool {
    override val spec: ToolSpec = ToolSpec(
        name = "ui.snapshot",
        title = "Read the visible screen",
        description = "Return the accessibility tree of the current screen: package, window and up to 150 labelled/clickable nodes " +
            "with node ids, bounds and flags. Node ids are valid only for the snapshot they came from.",
        parametersSchema = Schemas.obj(required = emptyList(), props = emptyMap()),
        risk = RiskLevel.LOW,
        category = ToolCategory.UI,
        source = "execution",
        requiresAccessibility = true,
    )

    override suspend fun execute(context: ToolContext, arguments: JsonObject): ToolResult {
        val startedAt = System.currentTimeMillis()
        if (!AccessibilityBridge.isConnected()) {
            return failure(context, spec.name, "Accessibility service is not connected.", startedAt)
        }
        val snapshot = AccessibilityBridge.snapshot()
            ?: return failure(context, spec.name, "No active window is readable right now (lock screen or protected app?).", startedAt)
        val output = snapshot.toJson()
        return success(
            context = context,
            toolName = spec.name,
            summary = "Screen ${snapshot.packageName} with ${snapshot.allNodes().size} node(s)",
            startedAt = startedAt,
            output = output,
        )
    }
}

class UiClickTool : AgentTool {
    override val spec: ToolSpec = ToolSpec(
        name = "ui.click",
        title = "Tap a UI element",
        description = "Tap an element on the current screen identified by node id (preferred), visible text or resource id. " +
            "The tap is verified by comparing the screen before and after; a no-op is reported honestly.",
        parametersSchema = Schemas.obj(
            required = emptyList(),
            props = mapOf(
                "nodeId" to Schemas.str("Node id from the latest ui.snapshot, e.g. n12"),
                "text" to Schemas.str("Visible text or content description of the target"),
                "resourceId" to Schemas.str("Full view id, e.g. com.app:id/button"),
            ),
        ),
        risk = RiskLevel.MEDIUM,
        category = ToolCategory.UI,
        source = "execution",
        requiresAccessibility = true,
    )

    override suspend fun execute(context: ToolContext, arguments: JsonObject): ToolResult {
        val startedAt = System.currentTimeMillis()
        if (!AccessibilityBridge.isConnected()) {
            return failure(context, spec.name, "Accessibility service is not connected.", startedAt)
        }
        val nodeId = arguments["nodeId"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
        val text = arguments["text"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
        val resourceId = arguments["resourceId"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
        if (nodeId == null && text == null && resourceId == null) {
            return failure(context, spec.name, "Provide nodeId, text or resourceId", startedAt)
        }

        val snapshot = AccessibilityBridge.snapshot()
            ?: return failure(context, spec.name, "No active window is readable.", startedAt)
        val target = when {
            nodeId != null -> snapshot.findByNodeId(nodeId)
            resourceId != null -> snapshot.findById(resourceId).firstOrNull()
            else -> snapshot.findText(text!!).firstOrNull()
        } ?: return failure(
            context,
            spec.name,
            "Target not found on the current screen (${snapshot.packageName}). Take a fresh snapshot and retry.",
            startedAt,
        )

        val before = AccessibilityBridge.screenSignature()
        val result = AccessibilityBridge.click(target.nodeId)
        if (!result.ok) return failure(context, spec.name, result.message, startedAt)

        delay(500)
        val afterSnapshot = AccessibilityBridge.snapshot()
        val after = AccessibilityBridge.screenSignature()
        val textGone = text != null && afterSnapshot?.findText(text)?.none { it.visible } == true
        val verified = result.verified || after != before || textGone
        val output = buildJsonObject {
            put("action", JsonPrimitive("click"))
            put("targetId", JsonPrimitive(target.nodeId))
            put("targetLabel", JsonPrimitive(target.label().take(120)))
            put("verified", JsonPrimitive(verified))
            put("screenChanged", JsonPrimitive(after != before))
            put("package", JsonPrimitive(afterSnapshot?.packageName.orEmpty()))
            put("supported", JsonPrimitive(true))
        }
        return ToolResult(
            callId = context.callId,
            toolName = spec.name,
            ok = true,
            output = output,
            summary = if (verified) {
                "Tapped ${target.label().take(60)} and the screen changed."
            } else {
                "Tapped ${target.label().take(60)}, but the screen did not change. The element may be a no-op."
            },
            durationMs = System.currentTimeMillis() - startedAt,
        )
    }
}

class UiSetTextTool : AgentTool {
    override val spec: ToolSpec = ToolSpec(
        name = "ui.set_text",
        title = "Type into a field",
        description = "Set the text of an editable field identified by node id, resource id or a label near it. " +
            "Verifies that the field now contains the text.",
        parametersSchema = Schemas.obj(
            required = listOf("text"),
            props = mapOf(
                "text" to Schemas.str("Text to enter", maxLength = 2_000),
                "nodeId" to Schemas.str("Editable node id from the latest snapshot"),
                "resourceId" to Schemas.str("Full view id of the editable field"),
            ),
        ),
        risk = RiskLevel.MEDIUM,
        category = ToolCategory.UI,
        source = "execution",
        requiresAccessibility = true,
    )

    override suspend fun execute(context: ToolContext, arguments: JsonObject): ToolResult {
        val startedAt = System.currentTimeMillis()
        if (!AccessibilityBridge.isConnected()) {
            return failure(context, spec.name, "Accessibility service is not connected.", startedAt)
        }
        val text = arguments["text"]?.jsonPrimitive?.content.orEmpty()
        val nodeId = arguments["nodeId"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
        val resourceId = arguments["resourceId"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }

        val snapshot = AccessibilityBridge.snapshot()
            ?: return failure(context, spec.name, "No active window is readable.", startedAt)
        val target = when {
            nodeId != null -> snapshot.findByNodeId(nodeId)
            resourceId != null -> snapshot.findById(resourceId).firstOrNull { it.editable }
            else -> snapshot.allNodes().firstOrNull { it.editable && it.visible }
        } ?: return failure(context, spec.name, "No editable field found. Provide nodeId from a fresh snapshot.", startedAt)

        if (!target.editable) {
            return failure(context, spec.name, "Node ${target.nodeId} is not editable.", startedAt)
        }

        val result = AccessibilityBridge.setText(target.nodeId, text)
        if (!result.ok) return failure(context, spec.name, result.message, startedAt)

        delay(350)
        val after = AccessibilityBridge.snapshot()
        val applied = after?.allNodes()?.any { it.editable && it.text == text } == true
        val output = buildJsonObject {
            put("action", JsonPrimitive("set_text"))
            put("targetId", JsonPrimitive(target.nodeId))
            put("chars", JsonPrimitive(text.length))
            put("verified", JsonPrimitive(applied))
        }
        return ToolResult(
            callId = context.callId,
            toolName = spec.name,
            ok = true,
            output = output,
            summary = if (applied) "Text entered and verified in ${target.label().take(40)}" else
                "Text action dispatched but the field content could not be verified.",
            durationMs = System.currentTimeMillis() - startedAt,
        )
    }
}

class UiScrollTool : AgentTool {
    override val spec: ToolSpec = ToolSpec(
        name = "ui.scroll",
        title = "Scroll the screen",
        description = "Scroll a scrollable container forward or backward, or swipe the screen if no container is given.",
        parametersSchema = Schemas.obj(
            required = emptyList(),
            props = mapOf(
                "direction" to Schemas.str("Scroll direction", enum = listOf("forward", "backward")),
                "nodeId" to Schemas.str("Scrollable node id from the latest snapshot"),
            ),
        ),
        risk = RiskLevel.MEDIUM,
        category = ToolCategory.UI,
        source = "execution",
        requiresAccessibility = true,
    )

    override suspend fun execute(context: ToolContext, arguments: JsonObject): ToolResult {
        val startedAt = System.currentTimeMillis()
        if (!AccessibilityBridge.isConnected()) {
            return failure(context, spec.name, "Accessibility service is not connected.", startedAt)
        }
        val forward = (arguments["direction"]?.jsonPrimitive?.content ?: "forward") != "backward"
        val nodeId = arguments["nodeId"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
        val before = AccessibilityBridge.screenSignature()

        if (nodeId != null) {
            val result = AccessibilityBridge.scroll(nodeId, forward)
            if (!result.ok) return failure(context, spec.name, result.message, startedAt)
        } else {
            val snapshot = AccessibilityBridge.snapshot()
                ?: return failure(context, spec.name, "No active window is readable.", startedAt)
            val metrics = snapshot.screenWidth to snapshot.screenHeight
            val ok = if (forward) {
                AccessibilityBridge.swipe(
                    metrics.first / 2,
                    (metrics.second * 0.75).toInt(),
                    metrics.first / 2,
                    (metrics.second * 0.3).toInt(),
                )
            } else {
                AccessibilityBridge.swipe(
                    metrics.first / 2,
                    (metrics.second * 0.3).toInt(),
                    metrics.first / 2,
                    (metrics.second * 0.75).toInt(),
                )
            }
            if (!ok) return failure(context, spec.name, "The system refused the gesture.", startedAt)
        }

        delay(450)
        val after = AccessibilityBridge.screenSignature()
        val output = buildJsonObject {
            put("action", JsonPrimitive("scroll"))
            put("forward", JsonPrimitive(forward))
            put("screenChanged", JsonPrimitive(after != before))
        }
        return success(
            context = context,
            toolName = spec.name,
            summary = if (after != before) "Scrolled ${if (forward) "forward" else "backward"}." else "Scroll dispatched but nothing changed (end of list?).",
            startedAt = startedAt,
            output = output,
        )
    }
}

class UiBackTool : AgentTool {
    override val spec: ToolSpec = ToolSpec(
        name = "ui.back",
        title = "Press Back",
        description = "Perform the system back action and report whether the screen changed.",
        parametersSchema = Schemas.obj(required = emptyList(), props = emptyMap()),
        risk = RiskLevel.LOW,
        category = ToolCategory.UI,
        source = "execution",
        requiresAccessibility = true,
    )

    override suspend fun execute(context: ToolContext, arguments: JsonObject): ToolResult {
        val startedAt = System.currentTimeMillis()
        val result = AccessibilityBridge.back()
        if (!result.ok) return failure(context, spec.name, result.message, startedAt)
        val output = buildJsonObject {
            put("action", JsonPrimitive("back"))
            put("screenChanged", JsonPrimitive(result.verified))
        }
        return success(
            context = context,
            toolName = spec.name,
            summary = if (result.verified) "Back pressed; the screen changed." else "Back pressed; the screen did not change.",
            startedAt = startedAt,
            output = output,
        )
    }
}

class UiWaitForTextTool : AgentTool {
    override val spec: ToolSpec = ToolSpec(
        name = "ui.wait_for_text",
        title = "Wait for text",
        description = "Poll the current screen until the given text appears or the timeout expires. " +
            "Use it after navigation instead of guessing that a screen loaded.",
        parametersSchema = Schemas.obj(
            required = listOf("text"),
            props = mapOf(
                "text" to Schemas.str("Text to wait for", maxLength = 200),
                "timeoutMs" to Schemas.int("Timeout", 500, 30_000),
            ),
        ),
        risk = RiskLevel.LOW,
        category = ToolCategory.UI,
        source = "execution",
        requiresAccessibility = true,
    )

    override suspend fun execute(context: ToolContext, arguments: JsonObject): ToolResult {
        val startedAt = System.currentTimeMillis()
        if (!AccessibilityBridge.isConnected()) {
            return failure(context, spec.name, "Accessibility service is not connected.", startedAt)
        }
        val text = arguments["text"]?.jsonPrimitive?.content.orEmpty()
        val timeout = arguments["timeoutMs"]?.jsonPrimitive?.longOrNull?.coerceIn(500L, 30_000L) ?: 8_000L
        val deadline = System.currentTimeMillis() + timeout
        var lastPackage = ""
        while (System.currentTimeMillis() < deadline) {
            val snapshot = AccessibilityBridge.snapshot()
            lastPackage = snapshot?.packageName.orEmpty()
            val match = snapshot?.findText(text)?.firstOrNull { it.visible }
            if (match != null) {
                val output = buildJsonObject {
                    put("found", JsonPrimitive(true))
                    put("waitedMs", JsonPrimitive(System.currentTimeMillis() - startedAt))
                    put("nodeId", JsonPrimitive(match.nodeId))
                    put("package", JsonPrimitive(lastPackage))
                }
                return success(context, spec.name, "Text '$text' appeared on screen.", startedAt, output = output)
            }
            delay(400)
        }
        val output = buildJsonObject {
            put("found", JsonPrimitive(false))
            put("waitedMs", JsonPrimitive(System.currentTimeMillis() - startedAt))
            put("package", JsonPrimitive(lastPackage))
        }
        return ToolResult(
            callId = context.callId,
            toolName = spec.name,
            ok = false,
            output = output,
            summary = "Text '$text' did not appear within $timeout ms.",
            error = "timeout",
            durationMs = System.currentTimeMillis() - startedAt,
        )
    }
}

class UiScreenshotTool(private val workspace: Workspace) : AgentTool {
    override val spec: ToolSpec = ToolSpec(
        name = "ui.screenshot",
        title = "Capture the screen",
        description = "Capture the current screen into the workspace as a PNG and return its path and size. " +
            "Protected windows cannot be captured. Nothing is uploaded by this tool.",
        parametersSchema = Schemas.obj(required = emptyList(), props = emptyMap()),
        risk = RiskLevel.MEDIUM,
        category = ToolCategory.UI,
        source = "execution",
        requiresAccessibility = true,
    )

    override suspend fun execute(context: ToolContext, arguments: JsonObject): ToolResult {
        val startedAt = System.currentTimeMillis()
        val frame = AccessibilityBridge.screenshot()
            ?: return failure(
                context,
                spec.name,
                "Screen capture failed. The accessibility service must be connected and the window must allow capture.",
                startedAt,
            )
        val directory = workspace.subdirectory("artifacts")
        val file = File(directory, "screenshot-${System.currentTimeMillis()}.png")
        return runCatching {
            file.writeBytes(frame.pngBytes)
            val output = buildJsonObject {
                put("path", JsonPrimitive(file.absolutePath))
                put("relativePath", JsonPrimitive(workspace.relative(file)))
                put("width", JsonPrimitive(frame.width))
                put("height", JsonPrimitive(frame.height))
                put("bytes", JsonPrimitive(frame.pngBytes.size))
            }
            success(
                context = context,
                toolName = spec.name,
                summary = "Captured ${frame.width}x${frame.height} screenshot (${frame.pngBytes.size} bytes).",
                startedAt = startedAt,
                artifacts = listOf(file.absolutePath),
                output = output,
            )
        }.getOrElse {
            failure(context, spec.name, "Could not save the screenshot: ${it.message}", startedAt)
        }
    }
}
