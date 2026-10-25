package com.mobileagent.core.security

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RedactorTest {

    @Test
    fun `api keys are masked`() {
        val text = "Authorization: Bearer sk-abcdefghijklmnop and api_key=supersecret12345"
        val redacted = Redactor.redact(text)
        assertFalse(redacted.contains("sk-abcdefghijklmnop"))
        assertFalse(redacted.contains("supersecret12345"))
        assertTrue(redacted.contains("[redacted]"))
    }

    @Test
    fun `fingerprint never reveals the whole secret`() {
        val fingerprint = Redactor.fingerprint("abcdefghijklmnop")
        assertTrue(fingerprint.endsWith("mnop"))
        assertFalse(fingerprint.contains("abcdefghij"))
    }
}

class ApprovalBusTest {

    @Test
    fun `approval resolves when the user answers`() = runTest {
        val bus = ApprovalBus()
        val request = ApprovalRequest(
            taskId = "t1",
            toolName = "file.write",
            title = "Write file",
            risk = com.mobileagent.core.model.RiskLevel.MEDIUM,
            summary = "write",
            argumentsJson = "{}",
            target = "exports/a.md",
            canUndo = true,
        )
        val pending = async { bus.request(request, timeoutMs = 5_000) }
        delay(50)
        bus.respond(request.id, true)
        assertTrue(pending.await())
        assertTrue(bus.pending.value == null)
    }

    @Test
    fun `timeout is treated as rejection`() = runTest {
        val bus = ApprovalBus()
        val request = ApprovalRequest(
            taskId = "t2",
            toolName = "adb.exec",
            title = "Run ADB command",
            risk = com.mobileagent.core.model.RiskLevel.HIGH,
            summary = "pm list packages",
            argumentsJson = "{}",
            target = "device",
            canUndo = false,
        )
        val result = bus.request(request, timeoutMs = 100)
        assertEquals(false, result)
    }
}
