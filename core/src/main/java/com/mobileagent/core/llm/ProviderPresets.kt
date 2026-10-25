package com.mobileagent.core.llm

/**
 * Provider presets based on the official documentation checked for this release.
 * DeepSeek: https://api-docs.deepseek.com/ (base https://api.deepseek.com, models deepseek-flash
 * and deepseek-v4-pro). Other providers are OpenAI-compatible endpoints with their documented base
 * URLs; model ids are editable because vendors rotate them frequently.
 */
data class ProviderPreset(
    val id: String,
    val displayName: String,
    val baseUrl: String,
    val apiPath: String = "/chat/completions",
    val models: List<String> = emptyList(),
    val defaultModel: String = "",
    val supportsTools: Boolean = true,
    val supportsVision: Boolean = false,
    val modelHint: String? = null,
    val docsUrl: String? = null,
    val notes: String? = null,
)

object ProviderPresets {

    val deepSeek = ProviderPreset(
        id = "deepseek",
        displayName = "DeepSeek",
        baseUrl = "https://api.deepseek.com",
        apiPath = "/chat/completions",
        models = listOf("deepseek-flash", "deepseek-v4-pro"),
        defaultModel = "deepseek-flash",
        supportsTools = true,
        supportsVision = false,
        docsUrl = "https://api-docs.deepseek.com/",
        notes = "官方兼容 OpenAI 协议。deepseek-flash 由 V4.1-Flash 提供服务。",
    )

    val openAi = ProviderPreset(
        id = "openai",
        displayName = "OpenAI",
        baseUrl = "https://api.openai.com/v1",
        apiPath = "/chat/completions",
        models = listOf("gpt-4o-mini", "gpt-4o"),
        defaultModel = "gpt-4o-mini",
        supportsTools = true,
        supportsVision = true,
        docsUrl = "https://platform.openai.com/docs/api-reference",
        notes = "模型 ID 可自定义；以账号实际可用模型为准。",
    )

    val qwen = ProviderPreset(
        id = "qwen",
        displayName = "通义千问 / 阿里云百炼",
        baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
        apiPath = "/chat/completions",
        models = listOf("qwen-plus", "qwen-turbo", "qwen-max", "qwen-vl-plus"),
        defaultModel = "qwen-plus",
        supportsTools = true,
        supportsVision = true,
        docsUrl = "https://help.aliyun.com/zh/model-studio/",
        notes = "百炼 OpenAI 兼容模式；模型 ID 以控制台为准。",
    )

    val volcengine = ProviderPreset(
        id = "volcengine",
        displayName = "火山方舟",
        baseUrl = "https://ark.cn-beijing.volces.com/api/v3",
        apiPath = "/chat/completions",
        models = emptyList(),
        defaultModel = "",
        supportsTools = true,
        docsUrl = "https://www.volcengine.com/docs/82379",
        notes = "模型 ID 通常填写推理接入点 ID（ep- 开头）或控制台给出的模型 ID。",
        modelHint = "填写推理接入点 ID（ep-...）",
    )

    val zhipu = ProviderPreset(
        id = "zhipu",
        displayName = "智谱 GLM",
        baseUrl = "https://open.bigmodel.cn/api/paas/v4",
        apiPath = "/chat/completions",
        models = listOf("glm-4-plus", "glm-4-flash", "glm-4-air"),
        defaultModel = "glm-4-flash",
        supportsTools = true,
        supportsVision = false,
        docsUrl = "https://open.bigmodel.cn/dev/api",
        notes = "模型 ID 以开放平台控制台为准。",
    )

    val moonshot = ProviderPreset(
        id = "moonshot",
        displayName = "Moonshot / Kimi",
        baseUrl = "https://api.moonshot.cn/v1",
        apiPath = "/chat/completions",
        models = listOf("moonshot-v1-8k", "moonshot-v1-32k", "moonshot-v1-128k"),
        defaultModel = "moonshot-v1-8k",
        supportsTools = true,
        docsUrl = "https://platform.moonshot.cn/docs",
        notes = "模型 ID 以 Kimi 开放平台控制台为准。",
    )

    val custom = ProviderPreset(
        id = "custom",
        displayName = "自定义 OpenAI 兼容 API",
        baseUrl = "",
        apiPath = "/chat/completions",
        models = emptyList(),
        defaultModel = "",
        supportsTools = true,
        notes = "填写完整的 Base URL（含 /v1 等版本路径）与模型 ID。",
        modelHint = "例如 my-model",
    )

    val all: List<ProviderPreset> = listOf(deepSeek, openAi, qwen, volcengine, zhipu, moonshot, custom)

    fun byId(id: String?): ProviderPreset? = all.firstOrNull { it.id == id }
}
