package com.mobileagent.core.tools

import com.mobileagent.core.model.AgentTool
import com.mobileagent.core.model.RiskLevel
import com.mobileagent.core.model.ToolCategory
import com.mobileagent.core.model.ToolContext
import com.mobileagent.core.model.ToolResult
import com.mobileagent.core.model.ToolSpec
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolRegistryTest {

    private fun tool(name: String): AgentTool = object : AgentTool {
        override val spec = ToolSpec(
            name = name,
            title = name,
            description = name,
            parametersSchema = buildJsonObject {
                put("type", JsonPrimitive("object"))
                put("properties", buildJsonObject { })
            },
            risk = RiskLevel.LOW,
            category = ToolCategory.SYSTEM,
        )

        override suspend fun execute(context: ToolContext, arguments: JsonObject): ToolResult =
            ToolResult(context.callId, name, true, JsonObject(emptyMap()), "ok")
    }

    @Test
    fun `wire names are valid and map back to internal names`() {
        val registry = ToolRegistry()
        registry.register(tool("file.read"))
        registry.register(tool("device.info"))

        val specs = registry.llmTools()
        assertTrue(specs.all { ToolNames.isValidWireName(it.name) })
        assertTrue(specs.none { it.name.contains('.') })
        assertEquals("file.read", registry.resolveWireName("file_read"))
        assertEquals("device.info", registry.resolveWireName("device_info"))
    }

    @Test
    fun `colliding wire names stay unique`() {
        val registry = ToolRegistry()
        registry.register(tool("file.read"))
        registry.register(tool("file_read"))
        val specs = registry.llmTools()
        assertEquals(2, specs.size)
        assertEquals(2, specs.map { it.name }.toSet().size)
        assertTrue(specs.all { ToolNames.isValidWireName(it.name) })
    }

    @Test
    fun `registering a tool keeps lookup by internal name`() {
        val registry = ToolRegistry()
        registry.register(tool("shell.exec"))
        assertNotNull(registry.get("shell.exec"))
        assertTrue(registry.contains("shell.exec"))
    }
}
