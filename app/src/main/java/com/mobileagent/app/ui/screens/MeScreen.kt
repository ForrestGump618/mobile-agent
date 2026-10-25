package com.mobileagent.app.ui.screens

import android.app.Activity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mobileagent.app.BuildConfig
import com.mobileagent.app.R
import com.mobileagent.app.i18n.LocaleHelper
import com.mobileagent.app.ui.InfoCard
import com.mobileagent.app.ui.vm.AppViewModel

@Composable
fun MeScreen(onNavigate: (String) -> Unit) {
    val appViewModel: AppViewModel = viewModel()
    val settings by appViewModel.settings.collectAsStateWithLifecycle()
    val providers by appViewModel.providers.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var languageDialog by remember { mutableStateOf(false) }

    val activeProvider = providers.firstOrNull { it.id == settings.activeProviderId } ?: providers.firstOrNull()

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Text(stringResource(R.string.nav_me), style = MaterialTheme.typography.headlineSmall)
        }

        item {
            InfoCard(
                title = activeProvider?.name ?: "尚未配置模型",
                subtitle = activeProvider?.let { it.modelId } ?: stringResource(R.string.models_none),
                onClick = { onNavigate("models") },
            ) {
                Text(
                    if (providers.isEmpty()) "点击添加模型" else "${providers.size} 个模型 · 管理默认模型与连接测试",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { languageDialog = true }
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.settings_language), modifier = Modifier.weight(1f))
                Text(
                    when (settings.languageTag) {
                        "zh-CN" -> stringResource(R.string.language_zh)
                        "en" -> stringResource(R.string.language_en)
                        else -> stringResource(R.string.language_system)
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            HorizontalDivider()
        }

        item { MenuRow(stringResource(R.string.nav_models), "服务商、API Key、连接测试") { onNavigate("models") } }
        item { MenuRow(stringResource(R.string.nav_device), "设备信息、UI 树、截图") { onNavigate("device") } }
        item { MenuRow(stringResource(R.string.nav_shizuku), "ADB Shell 权限") { onNavigate("shizuku") } }
        item { MenuRow(stringResource(R.string.nav_accessibility), "界面自动化开关") { onNavigate("accessibility") } }
        item { MenuRow(stringResource(R.string.nav_mcp), "外部工具服务") { onNavigate("mcp") } }
        item { MenuRow(stringResource(R.string.nav_skills), "内置与导入技能") { onNavigate("skills") } }
        item { MenuRow(stringResource(R.string.nav_memory), "长期记忆") { onNavigate("memory") } }
        item { MenuRow(stringResource(R.string.nav_workspace), "任务产物与文件") { onNavigate("workspace") } }
        item { MenuRow(stringResource(R.string.nav_logs), "事件与工具调用记录") { onNavigate("logs") } }
        item { MenuRow(stringResource(R.string.nav_diagnostics), "自检与诊断导出") { onNavigate("diagnostics") } }
        item { MenuRow("高级设置", "审批策略、执行开关、安全") { onNavigate("settings") } }

        item {
            Spacer(Modifier.height(12.dp))
            Text(
                "Mobile Agent ${BuildConfig.VERSION_NAME} · Android ${android.os.Build.VERSION.RELEASE}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            )
        }
    }

    if (languageDialog) {
        AlertDialog(
            onDismissRequest = { languageDialog = false },
            title = { Text(stringResource(R.string.settings_language)) },
            text = {
                Column {
                    listOf(
                        "" to stringResource(R.string.language_system),
                        "zh-CN" to stringResource(R.string.language_zh),
                        "en" to stringResource(R.string.language_en),
                    ).forEach { (tag, label) ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    LocaleHelper.save(context, tag)
                                    appViewModel.setLanguage(tag)
                                    languageDialog = false
                                    (context as? Activity)?.recreate()
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(label, modifier = Modifier.weight(1f))
                            if (settings.languageTag == tag) {
                                Text("✓", color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { languageDialog = false }) { Text(stringResource(R.string.action_close)) }
            },
        )
    }
}

@Composable
private fun MenuRow(title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text("›", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    HorizontalDivider()
}
