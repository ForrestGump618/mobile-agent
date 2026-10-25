package com.mobileagent.execution

import com.mobileagent.core.security.RuntimeCapabilities
import com.mobileagent.execution.accessibility.AccessibilityBridge
import com.mobileagent.execution.shell.LocalShellExecutor
import com.mobileagent.execution.shell.ShizukuShellExecutor

/** Live capability reporting used by the policy engine and the prompt composer. */
class ExecutionCapabilities(
    private val localShell: LocalShellExecutor,
    private val shizuku: ShizukuShellExecutor,
) : RuntimeCapabilities {

    override fun shizukuAvailable(): Boolean = runCatching { shizuku.isRunning() }.getOrDefault(false)

    override fun shizukuPermissionGranted(): Boolean = runCatching { shizuku.hasPermission() }.getOrDefault(false)

    override fun accessibilityConnected(): Boolean = AccessibilityBridge.isConnected()

    override fun localShellAvailable(): Boolean = localShell.isAvailable()
}
