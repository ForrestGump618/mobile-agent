package com.mobileagent.integrations.skills

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MiniYamlTest {

    private val sample = """
        id: device-diagnostics
        name: Device Diagnostics
        version: 1.0.0
        description: Collect real device facts # trailing comment
        runtime: prompt
        required_tools:
          - device.info
          - app.list
        permissions:
          files: false
          shell: true
          network: false
    """.trimIndent()

    @Test
    fun `parses manifest fields and list`() {
        val map = MiniYaml.parse(sample)
        assertEquals("device-diagnostics", map["id"])
        assertEquals("prompt", map["runtime"])
        val tools = map["required_tools"] as List<*>
        assertEquals(listOf("device.info", "app.list"), tools)
    }

    @Test
    fun `builds a manifest with permissions`() {
        val manifest = MiniYaml.toManifest(MiniYaml.parse(sample), instructions = "# Skill")
        assertEquals("device-diagnostics", manifest.id)
        assertEquals("Device Diagnostics", manifest.name)
        assertTrue(manifest.permissions.shell)
        assertFalse(manifest.permissions.network)
        assertEquals(listOf("device.info", "app.list"), manifest.requiredTools)
    }

    @Test
    fun `inline comments are not kept in values`() {
        val map = MiniYaml.parse("description: hello # ignore me")
        assertEquals("hello", map["description"])
    }

    @Test
    fun `deep indentation is rejected instead of guessed`() {
        val bad = """
            id: test
            permissions:
                nested:
                  too: deep
        """.trimIndent()
        assertTrue(runCatching { MiniYaml.parse(bad) }.isFailure)
    }
}
