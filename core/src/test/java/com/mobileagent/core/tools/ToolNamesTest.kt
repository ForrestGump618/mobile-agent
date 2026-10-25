package com.mobileagent.core.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolNamesTest {

    @Test
    fun `dots are replaced for the wire`() {
        assertEquals("file_read", ToolNames.wireName("file.read"))
        assertEquals("device_info", ToolNames.wireName("device.info"))
        assertEquals("mcp_demo_echo", ToolNames.wireName("mcp.demo.echo"))
    }

    @Test
    fun `wire names always satisfy the provider regex`() {
        listOf("shell.exec", "ui.set_text", "memory.search", "skill.run", "http.request").forEach {
            val wire = ToolNames.wireName(it)
            assertTrue("$wire must be valid", ToolNames.isValidWireName(wire))
            assertFalse(wire.contains('.'))
        }
    }

    @Test
    fun `empty names fall back and length is bounded`() {
        assertEquals("tool", ToolNames.wireName("..."))
        assertTrue(ToolNames.wireName("x".repeat(200)).length <= ToolNames.MAX_LENGTH)
    }
}
