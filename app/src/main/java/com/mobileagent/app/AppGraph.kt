package com.mobileagent.app

import android.content.Context
import com.mobileagent.core.agent.AgentOrchestrator
import com.mobileagent.core.agent.AgentRunRegistry
import com.mobileagent.core.agent.AgentRuntime
import com.mobileagent.core.agent.CheckpointManager
import com.mobileagent.core.agent.ContextManager
import com.mobileagent.core.agent.DiagnosticsService
import com.mobileagent.core.agent.Planner
import com.mobileagent.core.agent.ResultVerifier
import com.mobileagent.core.chat.ChatEngine
import com.mobileagent.core.chat.ChatRunRegistry
import com.mobileagent.core.chat.TaskChatStore
import com.mobileagent.core.llm.ConnectionTester
import com.mobileagent.core.data.IntegrationRepository
import com.mobileagent.core.data.ProviderRepository
import com.mobileagent.core.data.TaskRepository
import com.mobileagent.core.data.db.AgentDatabase
import com.mobileagent.core.llm.ModelRouter
import com.mobileagent.core.llm.OpenAiCompatibleClient
import com.mobileagent.core.memory.MemoryManager
import com.mobileagent.core.security.ApprovalBus
import com.mobileagent.core.security.PolicyEngine
import com.mobileagent.core.security.SecretVault
import com.mobileagent.core.settings.SettingsStore
import com.mobileagent.core.tools.ToolRegistry
import com.mobileagent.execution.ExecutionCapabilities
import com.mobileagent.execution.shell.LocalShellExecutor
import com.mobileagent.execution.shell.ShizukuShellExecutor
import com.mobileagent.execution.tools.AppLaunchTool
import com.mobileagent.execution.tools.AppListTool
import com.mobileagent.execution.tools.DeviceInfoTool
import com.mobileagent.execution.tools.FileDeleteTool
import com.mobileagent.execution.tools.FileImportTool
import com.mobileagent.execution.tools.FileListTool
import com.mobileagent.execution.tools.FileMoveTool
import com.mobileagent.execution.tools.FileReadTool
import com.mobileagent.execution.tools.FileWriteTool
import com.mobileagent.execution.tools.HttpTool
import com.mobileagent.execution.tools.LocalShellTool
import com.mobileagent.execution.tools.ScriptRuntimeTool
import com.mobileagent.execution.tools.ShizukuShellTool
import com.mobileagent.execution.tools.UiBackTool
import com.mobileagent.execution.tools.UiClickTool
import com.mobileagent.execution.tools.UiScreenshotTool
import com.mobileagent.execution.tools.UiScrollTool
import com.mobileagent.execution.tools.UiSetTextTool
import com.mobileagent.execution.tools.UiSnapshotTool
import com.mobileagent.execution.tools.UiWaitForTextTool
import com.mobileagent.execution.tools.VisionTool
import com.mobileagent.execution.workspace.Workspace
import com.mobileagent.integrations.mcp.McpManager
import com.mobileagent.integrations.skills.SkillLoader
import com.mobileagent.integrations.skills.SkillScriptTool
import com.mobileagent.app.tools.MemorySaveTool
import com.mobileagent.app.tools.MemorySearchTool
import com.mobileagent.app.tools.ScheduleCreateTool
import com.mobileagent.app.work.ScheduleScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Manual dependency graph. Everything is application scoped so the harness survives Activity
 * recreation, and the UI only ever observes state.
 */
class AppGraph(val context: Context) {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val settings = SettingsStore(context)
    val database = AgentDatabase.create(context)
    val tasks = TaskRepository(database)
    val providers = ProviderRepository(database)
    val integrations = IntegrationRepository(database)
    val secrets = SecretVault(context)
    val workspace = Workspace(context)
    val approvals = ApprovalBus()

    val localShell = LocalShellExecutor(context)
    val shizuku = ShizukuShellExecutor(context)
    val capabilities = ExecutionCapabilities(localShell, shizuku)

    val registry = ToolRegistry()
    val llm = OpenAiCompatibleClient()
    val router = ModelRouter(providers, settings, secrets)
    val policy = PolicyEngine(settings, capabilities, approvals, tasks)
    val memory = MemoryManager(integrations, settings)
    val runs = AgentRunRegistry()
    val contextManager = ContextManager(tasks)
    val checkpoints = CheckpointManager(tasks)
    val planner = Planner(llm, tasks)
    val verifier = ResultVerifier(tasks)
    val mcp = McpManager(integrations, secrets, registry)
    val skills = SkillLoader(context, integrations, registry, workspace)
    val scheduler = ScheduleScheduler(context, integrations)

    val chatRuns = ChatRunRegistry()
    val chatStore = TaskChatStore(tasks)
    val chatEngine = ChatEngine(
        store = chatStore,
        llm = llm,
        modelProvider = { router.resolve(com.mobileagent.core.model.ModelRole.CHAT) },
        settingsProvider = { settings.current() },
        runs = chatRuns,
    )
    val connectionTester = ConnectionTester(llm)

    val orchestrator: AgentOrchestrator
    val runtime: AgentRuntime
    val diagnostics: DiagnosticsService

    init {
        registerTools()
        orchestrator = AgentOrchestrator(
            tasks = tasks,
            tools = registry,
            policy = policy,
            llm = llm,
            router = router,
            settings = settings,
            contextManager = contextManager,
            checkpoints = checkpoints,
            planner = planner,
            verifier = verifier,
            memory = memory,
            capabilities = capabilities,
            runs = runs,
            skillGuidance = { skills.guidanceBlock() },
        )
        runtime = AgentRuntime(tasks, orchestrator, runs, router)
        diagnostics = DiagnosticsService(
            context = context,
            tasks = tasks,
            providers = providers,
            integrations = integrations,
            settings = settings,
            capabilities = capabilities,
            registry = registry,
            runs = runs,
            router = router,
        )
    }

    private fun registerTools() {
        registry.registerAll(
            listOf(
                LocalShellTool(localShell),
                ShizukuShellTool(shizuku),
                ScriptRuntimeTool(localShell),
                DeviceInfoTool(context),
                AppListTool(context),
                AppLaunchTool(context),
                FileListTool(workspace),
                FileReadTool(workspace),
                FileWriteTool(workspace),
                FileMoveTool(workspace),
                FileDeleteTool(workspace),
                FileImportTool(context, workspace),
                HttpTool(),
                UiSnapshotTool(),
                UiClickTool(),
                UiSetTextTool(),
                UiScrollTool(),
                UiBackTool(),
                UiWaitForTextTool(),
                UiScreenshotTool(workspace),
                VisionTool(llm, router, settings, workspace),
                SkillScriptTool(integrations, workspace, localShell),
                MemorySaveTool(integrations),
                MemorySearchTool(integrations),
                ScheduleCreateTool(integrations, scheduler),
            ),
        )
    }

    /** Called once when the process starts. Nothing here runs automatically in a loop. */
    suspend fun bootstrap() {
        runCatching { skills.seedBuiltins() }
        runCatching { scheduler.restoreAll() }
        val recovered = runCatching { runtime.recoverInterrupted() }.getOrDefault(emptyList())
        if (recovered.isNotEmpty()) {
            tasks.addEvent(
                taskId = null,
                executionId = null,
                type = com.mobileagent.core.model.AgentEventType.TASK_RECOVERED,
                message = "${recovered.size} interrupted task(s) were paused; resume them from Tasks.",
            )
        }
        appScope.launch {
            runCatching { mcp.connectEnabled() }
        }
    }
}
