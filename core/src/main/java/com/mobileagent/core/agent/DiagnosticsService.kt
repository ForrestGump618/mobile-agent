package com.mobileagent.core.agent

import com.mobileagent.core.data.IntegrationRepository
import com.mobileagent.core.data.ProviderRepository
import com.mobileagent.core.data.TaskRepository
import com.mobileagent.core.llm.ModelRouter
import com.mobileagent.core.security.RuntimeCapabilities
import com.mobileagent.core.settings.SettingsStore
import com.mobileagent.core.tools.ToolRegistry

enum class DiagnosticStatus { OK, WARNING, ERROR, UNKNOWN }

data class DiagnosticItem(
    val key: String,
    val label: String,
    val status: DiagnosticStatus,
    val detail: String,
)

/** Aggregated, honest health snapshot used by the Diagnostics screen and the support export. */
class DiagnosticsService(
    private val context: android.content.Context,
    private val tasks: TaskRepository,
    private val providers: ProviderRepository,
    private val integrations: IntegrationRepository,
    private val settings: SettingsStore,
    private val capabilities: RuntimeCapabilities,
    private val registry: ToolRegistry,
    private val runs: AgentRunRegistry,
    private val router: ModelRouter,
) {

    suspend fun snapshot(): List<DiagnosticItem> {
        val items = mutableListOf<DiagnosticItem>()

        val taskCount = runCatching { tasks.taskCount() }.getOrDefault(-1)
        val eventCount = runCatching { tasks.eventCount() }.getOrDefault(-1)
        items += DiagnosticItem(
            key = "core",
            label = "Agent core & database",
            status = if (taskCount >= 0) DiagnosticStatus.OK else DiagnosticStatus.ERROR,
            detail = "tasks=$taskCount, auditEvents=$eventCount, tools=${registry.all().size}",
        )

        val providerList = runCatching { providers.list() }.getOrDefault(emptyList())
        val activeLabel = runCatching { router.activeLabel() }.getOrNull()
        items += DiagnosticItem(
            key = "models",
            label = "Language models",
            status = when {
                providerList.isEmpty() -> DiagnosticStatus.WARNING
                activeLabel == null -> DiagnosticStatus.ERROR
                else -> DiagnosticStatus.OK
            },
            detail = if (providerList.isEmpty()) {
                "No provider configured. Add one in Settings > Models."
            } else {
                "providers=${providerList.size}, active=${activeLabel ?: "none usable"}"
            },
        )

        items += DiagnosticItem(
            key = "shizuku",
            label = "Shizuku (ADB shell)",
            status = when {
                !capabilities.shizukuAvailable() -> DiagnosticStatus.WARNING
                !capabilities.shizukuPermissionGranted() -> DiagnosticStatus.WARNING
                else -> DiagnosticStatus.OK
            },
            detail = when {
                !capabilities.shizukuAvailable() -> "Shizuku is not running or not installed."
                !capabilities.shizukuPermissionGranted() -> "Shizuku is running; permission not granted to Mobile Agent."
                else -> "Connected with shell UID."
            },
        )

        val accessibility = capabilities.accessibilityConnected()
        val accessibilityEnabled = runCatching { settings.current().allowAccessibility }.getOrDefault(false)
        items += DiagnosticItem(
            key = "accessibility",
            label = "Accessibility automation",
            status = if (accessibility) DiagnosticStatus.OK else DiagnosticStatus.WARNING,
            detail = if (accessibility) {
                "Service connected; automation allowed=$accessibilityEnabled."
            } else {
                "Service not connected. Enable Mobile Agent in Android accessibility settings."
            },
        )

        val mcpServers = runCatching { integrations.mcpServers() }.getOrDefault(emptyList())
        val failingMcp = mcpServers.filter { it.lastError != null }
        items += DiagnosticItem(
            key = "mcp",
            label = "MCP servers",
            status = when {
                failingMcp.isNotEmpty() -> DiagnosticStatus.WARNING
                mcpServers.isEmpty() -> DiagnosticStatus.UNKNOWN
                else -> DiagnosticStatus.OK
            },
            detail = when {
                mcpServers.isEmpty() -> "No MCP server configured."
                failingMcp.isNotEmpty() -> "${failingMcp.size}/${mcpServers.size} server(s) report errors: ${failingMcp.first().name}"
                else -> "${mcpServers.size} server(s), ${mcpServers.sumOf { it.tools.size }} tool(s) discovered."
            },
        )

        val skills = runCatching { integrations.skills() }.getOrDefault(emptyList())
        items += DiagnosticItem(
            key = "skills",
            label = "Skills",
            status = if (skills.isEmpty()) DiagnosticStatus.WARNING else DiagnosticStatus.OK,
            detail = "${skills.size} skill(s), ${skills.count { it.enabled }} enabled.",
        )

        val running = runs.runs.value.values.count { it.status.isRunning }
        items += DiagnosticItem(
            key = "background",
            label = "Running tasks",
            status = DiagnosticStatus.OK,
            detail = "$running task(s) currently executing in this process.",
        )

        val (freeMb, totalMb) = storageInfo()
        items += DiagnosticItem(
            key = "storage",
            label = "Free storage",
            status = if (freeMb < 200) DiagnosticStatus.WARNING else DiagnosticStatus.OK,
            detail = "$freeMb MB free of $totalMb MB.",
        )

        val network = networkAvailable()
        items += DiagnosticItem(
            key = "network",
            label = "Network",
            status = if (network) DiagnosticStatus.OK else DiagnosticStatus.WARNING,
            detail = if (network) "A network is available." else "No active network; model and HTTP tools will fail.",
        )

        val failures = runCatching { tasks.tasksInStatus(com.mobileagent.core.model.TaskStatus.FAILED) }.getOrDefault(emptyList())
        items += DiagnosticItem(
            key = "failures",
            label = "Failed tasks",
            status = if (failures.isEmpty()) DiagnosticStatus.OK else DiagnosticStatus.WARNING,
            detail = if (failures.isEmpty()) "No failed task." else "${failures.size} failed task(s), latest: ${failures.first().title}",
        )

        return items
    }

    private fun storageInfo(): Pair<Long, Long> {
        val stat = android.os.StatFs(context.filesDir.absolutePath)
        val totalMb = stat.blockCountLong * stat.blockSizeLong / (1024 * 1024)
        val freeMb = stat.availableBlocksLong * stat.blockSizeLong / (1024 * 1024)
        return freeMb to totalMb
    }

    private fun networkAvailable(): Boolean {
        val manager = context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
            ?: return false
        val network = manager.activeNetwork ?: return false
        val caps = manager.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
