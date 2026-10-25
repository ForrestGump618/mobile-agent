package com.mobileagent.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.mobileagent.app.i18n.LocaleHelper
import com.mobileagent.app.ui.ApprovalDialog
import com.mobileagent.app.ui.screens.AccessibilityScreen
import com.mobileagent.app.ui.screens.ChatScreen
import com.mobileagent.app.ui.screens.DashboardScreen
import com.mobileagent.app.ui.screens.DeviceScreen
import com.mobileagent.app.ui.screens.DiagnosticsScreen
import com.mobileagent.app.ui.screens.LogsScreen
import com.mobileagent.app.ui.screens.McpScreen
import com.mobileagent.app.ui.screens.MeScreen
import com.mobileagent.app.ui.screens.MemoryScreen
import com.mobileagent.app.ui.screens.ModelsScreen
import com.mobileagent.app.ui.screens.ProviderSetupScreen
import com.mobileagent.app.ui.screens.SettingsScreen
import com.mobileagent.app.ui.screens.ShizukuScreen
import com.mobileagent.app.ui.screens.SkillsScreen
import com.mobileagent.app.ui.screens.TasksScreen
import com.mobileagent.app.ui.screens.TerminalScreen
import com.mobileagent.app.ui.screens.WorkspaceScreen
import com.mobileagent.app.ui.theme.MobileAgentTheme
import com.mobileagent.app.ui.vm.AppViewModel
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku

class MainActivity : ComponentActivity() {

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val permissionListener = Shizuku.OnRequestPermissionResultListener { _, grantResult ->
        if (grantResult == PackageManager.PERMISSION_GRANTED) {
            (application as? MobileAgentApplication)?.graph?.let { graph ->
                graph.appScope.launch {
                    graph.tasks.addEvent(
                        taskId = null,
                        executionId = null,
                        type = com.mobileagent.core.model.AgentEventType.DIAGNOSTIC,
                        message = "Shizuku permission granted.",
                    )
                }
            }
        }
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            runCatching { notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }
        }
        runCatching { Shizuku.addRequestPermissionResultListener(permissionListener) }
        setContent {
            val appViewModel: AppViewModel = viewModel()
            val settings by appViewModel.settings.collectAsStateWithLifecycle()
            MobileAgentTheme(themeMode = settings.themeMode, dynamicColor = settings.dynamicColor) {
                AppRoot(appViewModel)
            }
        }
    }

    override fun onDestroy() {
        runCatching { Shizuku.removeRequestPermissionResultListener(permissionListener) }
        super.onDestroy()
    }

    fun requestShizukuPermission() {
        runCatching { Shizuku.requestPermission(SHIZUKU_REQUEST_CODE) }
    }

    companion object {
        const val SHIZUKU_REQUEST_CODE = 4311
    }
}

private data class Destination(
    val route: String,
    @StringRes val labelRes: Int,
    val icon: ImageVector,
)

private val tabDestinations = listOf(
    Destination("chat", R.string.nav_chat, Icons.Filled.ChatBubble),
    Destination("tasks", R.string.nav_tasks, Icons.Filled.List),
    Destination("terminal", R.string.nav_terminal, Icons.Filled.Build),
    Destination("dashboard", R.string.nav_workbench, Icons.Filled.Dashboard),
    Destination("me", R.string.nav_me, Icons.Filled.Person),
)

private val drawerDestinations = listOf(
    Destination("chat", R.string.nav_chat, Icons.Filled.ChatBubble),
    Destination("tasks", R.string.nav_tasks, Icons.Filled.List),
    Destination("terminal", R.string.nav_terminal, Icons.Filled.Build),
    Destination("dashboard", R.string.nav_workbench, Icons.Filled.Dashboard),
    Destination("me", R.string.nav_me, Icons.Filled.Person),
    Destination("models", R.string.nav_models, Icons.Filled.Settings),
    Destination("device", R.string.nav_device, Icons.Filled.Build),
    Destination("mcp", R.string.nav_mcp, Icons.Filled.Memory),
    Destination("skills", R.string.nav_skills, Icons.Filled.Build),
    Destination("memory", R.string.nav_memory, Icons.Filled.Memory),
    Destination("workspace", R.string.nav_workspace, Icons.Filled.List),
    Destination("logs", R.string.nav_logs, Icons.Filled.List),
    Destination("diagnostics", R.string.nav_diagnostics, Icons.Filled.Build),
    Destination("settings", R.string.nav_settings, Icons.Filled.Settings),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppRoot(appViewModel: AppViewModel) {
    val navController = rememberNavController()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val settings by appViewModel.settings.collectAsStateWithLifecycle()
    val providers by appViewModel.providers.collectAsStateWithLifecycle()
    val approval by appViewModel.approval.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // First run: do not block on Shizuku/accessibility; only a model is needed for chat.
    if (providers.isEmpty() && !settings.onboardingCompleted) {
        androidx.compose.material3.Surface(Modifier.fillMaxSize()) {
            ProviderSetupScreen(
                onboarding = true,
                onDone = { appViewModel.completeOnboarding() },
                onSkip = { appViewModel.completeOnboarding() },
            )
        }
        return
    }

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route ?: "chat"
    val titleRes = drawerDestinations.firstOrNull { it.route == currentRoute }?.labelRes
        ?: R.string.models_title
    val showBack = currentRoute.startsWith("provider_setup") || currentRoute == "shizuku" || currentRoute == "accessibility"

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Text(
                    text = "Mobile Agent",
                    modifier = Modifier.padding(20.dp),
                    style = androidx.compose.material3.MaterialTheme.typography.titleLarge,
                )
                drawerDestinations.forEach { destination ->
                    NavigationDrawerItem(
                        label = { Text(stringResource(destination.labelRes)) },
                        icon = { Icon(destination.icon, contentDescription = null) },
                        selected = currentRoute == destination.route,
                        onClick = {
                            scope.launch { drawerState.close() }
                            navController.navigate(destination.route) { launchSingleTop = true }
                        },
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                }
            }
        },
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(titleRes)) },
                    navigationIcon = {
                        if (showBack) {
                            IconButton(onClick = { navController.popBackStack() }) {
                                Icon(
                                    androidx.compose.material.icons.Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = stringResource(R.string.action_back),
                                )
                            }
                        } else {
                            IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                Icon(Icons.Filled.Menu, contentDescription = "Menu")
                            }
                        }
                    },
                )
            },
            bottomBar = {
                NavigationBar {
                    tabDestinations.forEach { destination ->
                        NavigationBarItem(
                            selected = currentRoute == destination.route,
                            onClick = {
                                navController.navigate(destination.route) {
                                    launchSingleTop = true
                                    popUpTo("chat") { saveState = true }
                                    restoreState = true
                                }
                            },
                            icon = { Icon(destination.icon, contentDescription = null) },
                            label = { Text(stringResource(destination.labelRes)) },
                        )
                    }
                }
            },
        ) { padding ->
            AppNavHost(navController, padding, appViewModel, context)
        }
    }

    approval?.let { request ->
        ApprovalDialog(
            request = request,
            onDecision = { approved -> appViewModel.respondApproval(request.id, approved) },
        )
    }
}

@Composable
private fun AppNavHost(
    navController: NavHostController,
    padding: androidx.compose.foundation.layout.PaddingValues,
    appViewModel: AppViewModel,
    context: Context,
) {
    NavHost(
        navController = navController,
        startDestination = "chat",
        modifier = Modifier.padding(padding),
    ) {
        composable("chat") {
            ChatScreen(
                onOpenTasks = { navController.navigate("tasks") },
                onOpenModels = { navController.navigate("models") },
            )
        }
        composable("tasks") { TasksScreen() }
        composable("terminal") { TerminalScreen() }
        composable("dashboard") {
            DashboardScreen(
                onOpenChat = { navController.navigate("chat") },
                onOpenTasks = { navController.navigate("tasks") },
                onOpenDevice = { navController.navigate("device") },
                onNavigate = { route -> navController.navigate(route) },
            )
        }
        composable("me") { MeScreen(onNavigate = { route -> navController.navigate(route) }) }
        composable("models") {
            ModelsScreen(
                onAdd = { navController.navigate("provider_setup") },
                onEdit = { id -> navController.navigate("provider_setup?providerId=$id") },
            )
        }
        composable(
            route = "provider_setup?providerId={providerId}",
            arguments = listOf(navArgument("providerId") {
                type = NavType.StringType
                defaultValue = ""
            }),
        ) { entry ->
            val providerId = entry.arguments?.getString("providerId").orEmpty().ifBlank { null }
            ProviderSetupScreen(
                providerId = providerId,
                onDone = { navController.popBackStack() },
            )
        }
        composable("device") {
            DeviceScreen(onRequestShizuku = { (context as? MainActivity)?.requestShizukuPermission() })
        }
        composable("mcp") { McpScreen() }
        composable("skills") { SkillsScreen() }
        composable("settings") { SettingsScreen(onNavigate = { route -> navController.navigate(route) }) }
        composable("shizuku") {
            ShizukuScreen(onRequestPermission = { (context as? MainActivity)?.requestShizukuPermission() })
        }
        composable("accessibility") { AccessibilityScreen() }
        composable("diagnostics") { DiagnosticsScreen() }
        composable("memory") { MemoryScreen() }
        composable("workspace") { WorkspaceScreen() }
        composable("logs") { LogsScreen() }
    }
}
