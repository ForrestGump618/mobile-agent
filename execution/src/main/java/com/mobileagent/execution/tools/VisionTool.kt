package com.mobileagent.execution.tools

import android.util.Base64
import com.mobileagent.core.llm.LlmClient
import com.mobileagent.core.llm.ModelRouter
import com.mobileagent.core.llm.describe
import com.mobileagent.core.model.AgentTool
import com.mobileagent.core.model.ChatRequest
import com.mobileagent.core.model.LlmException
import com.mobileagent.core.model.LlmMessage
import com.mobileagent.core.model.ModelRole
import com.mobileagent.core.model.RiskLevel
import com.mobileagent.core.model.ToolCategory
import com.mobileagent.core.model.ToolContext
import com.mobileagent.core.model.ToolResult
import com.mobileagent.core.model.ToolSpec
import com.mobileagent.core.settings.SettingsStore
import com.mobileagent.execution.accessibility.AccessibilityBridge
import com.mobileagent.execution.workspace.Workspace
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * Sends the current screen to a vision-capable model. Requires explicit opt-in, records which
 * provider received the image, and never runs when screenshots are disabled.
 */
class VisionTool(
    private val llm: LlmClient,
    private val router: ModelRouter,
    private val settings: SettingsStore,
    private val workspace: Workspace,
) : AgentTool {

    override val spec: ToolSpec = ToolSpec(
        name = "vision.analyze",
        title = "Analyze the screen with a vision model",
        description = "Capture the current screen, downscale it, and ask a vision-capable model a question about it. " +
            "Requires 'send screenshots to cloud models' to be enabled. Protected windows cannot be captured. " +
            "Returns the model answer and the saved screenshot path.",
        parametersSchema = Schemas.obj(
            required = listOf("question"),
            props = mapOf(
                "question" to Schemas.str("What to determine from the screenshot", maxLength = 1_500),
            ),
        ),
        risk = RiskLevel.HIGH,
        category = ToolCategory.VISION,
        source = "execution",
        requiresAccessibility = true,
    )

    override suspend fun execute(context: ToolContext, arguments: JsonObject): ToolResult {
        val startedAt = System.currentTimeMillis()
        if (!settings.current().allowCloudScreenshots) {
            return failure(
                context,
                spec.name,
                "Sending screenshots to a cloud model is disabled in Settings > Security.",
                startedAt,
            )
        }
        if (!AccessibilityBridge.isConnected()) {
            return failure(context, spec.name, "Accessibility service is not connected.", startedAt)
        }
        val question = arguments["question"]?.jsonPrimitive?.content?.trim().orEmpty()
        if (question.isBlank()) return failure(context, spec.name, "question is required", startedAt)

        val model = router.resolve(ModelRole.VISION)
            ?: return failure(
                context,
                spec.name,
                "No vision-capable model is configured. Enable vision for a provider in Settings > Models.",
                startedAt,
            )

        val jpeg = AccessibilityBridge.screenshotScaled(maxDimension = 1024, jpegQuality = 75)
            ?: return failure(
                context,
                spec.name,
                "Screen capture failed (protected window, lock screen, or capture not permitted).",
                startedAt,
            )

        val directory = workspace.subdirectory("artifacts")
        val savedFile = File(directory, "vision-${System.currentTimeMillis()}.jpg")
        runCatching { savedFile.writeBytes(jpeg) }

        val dataUrl = "data:image/jpeg;base64," + Base64.encodeToString(jpeg, Base64.NO_WRAP)
        return try {
            val response = llm.chat(
                config = model.provider,
                apiKey = model.apiKey,
                request = ChatRequest(
                    messages = listOf(
                        LlmMessage.system(
                            "You are analyzing an Android screenshot for an automation agent. " +
                                "Describe only what is visible. Do not invent UI elements, credentials or text.",
                        ),
                        LlmMessage(
                            role = "user",
                            content = question,
                            images = listOf(dataUrl),
                        ),
                    ),
                    temperature = 0.0,
                    maxTokens = 700,
                    stream = false,
                ),
            )
            val output = buildJsonObject {
                put("answer", JsonPrimitive(response.content))
                put("model", JsonPrimitive(model.label))
                put("imageBytes", JsonPrimitive(jpeg.size))
                put("screenshotPath", JsonPrimitive(savedFile.absolutePath))
            }
            success(
                context = context,
                toolName = spec.name,
                summary = "Vision model ${model.label} answered in ${response.latencyMs} ms.",
                startedAt = startedAt,
                artifacts = listOf(savedFile.absolutePath),
                output = output,
            )
        } catch (e: LlmException) {
            failure(context, spec.name, "Vision request failed: ${e.describe()}", startedAt)
        } catch (t: Throwable) {
            failure(context, spec.name, "Vision request failed: ${t.message ?: t::class.java.simpleName}", startedAt)
        }
    }
}
