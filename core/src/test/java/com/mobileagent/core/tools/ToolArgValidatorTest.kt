package com.mobileagent.core.tools

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.add
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolArgValidatorTest {

    private val schema = buildJsonObject {
        put("type", JsonPrimitive("object"))
        put("properties", buildJsonObject {
            put("path", buildJsonObject { put("type", JsonPrimitive("string")) })
            put("mode", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("enum", buildJsonArray { add(JsonPrimitive("fast")); add(JsonPrimitive("safe")) })
            })
            put("count", buildJsonObject { put("type", JsonPrimitive("integer")) })
        })
        put("required", buildJsonArray { add(JsonPrimitive("path")) })
    }

    @Test
    fun `missing required argument is reported`() {
        val errors = ToolArgValidator.validate(schema, buildJsonObject { })
        assertEquals(1, errors.size)
        assertTrue(errors.first().contains("path"))
    }

    @Test
    fun `wrong primitive types are reported`() {
        val args = buildJsonObject {
            put("path", JsonPrimitive(3))
            put("count", JsonPrimitive("many"))
        }
        val errors = ToolArgValidator.validate(schema, args)
        assertTrue(errors.any { it.contains("path") })
        assertTrue(errors.any { it.contains("count") })
    }

    @Test
    fun `enum values are enforced`() {
        val errors = ToolArgValidator.validate(schema, buildJsonObject {
            put("path", JsonPrimitive("exports/a.md"))
            put("mode", JsonPrimitive("turbo"))
        })
        assertTrue(errors.any { it.contains("mode") })
    }

    @Test
    fun `valid arguments pass`() {
        val errors = ToolArgValidator.validate(schema, buildJsonObject {
            put("path", JsonPrimitive("exports/a.md"))
            put("mode", JsonPrimitive("safe"))
            put("count", JsonPrimitive(2))
        })
        assertTrue(errors.isEmpty())
    }

    @Test
    fun `invalid json arguments are rejected by parse`() {
        val result = ToolArgValidator.parseArguments("{not json")
        assertTrue(result.isFailure)
    }
}
