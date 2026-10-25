package com.mobileagent.app.ui.screens

import android.app.Activity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mobileagent.app.R
import com.mobileagent.app.i18n.LocaleHelper
import com.mobileagent.app.ui.InfoCard
import com.mobileagent.app.ui.SectionTitle
import com.mobileagent.app.ui.vm.SettingsViewModel
import com.mobileagent.core.settings.ApprovalMode

@Composable
fun SettingsScreen(onNavigate: (String) -> Unit) {
    val viewModel: SettingsViewModel = viewModel()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showLanguage by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        SectionTitle("语言")
        InfoCard(
            title = stringResource(R.string.settings_language),
            subtitle = when (settings.languageTag) {
                "zh-CN" -> stringResource(R.string.language_zh)
                "en" -> stringResource(R.string.language_en)
                else -> stringResource(R.string.language_system)
            },
            onClick = { showLanguage = true },
        )
        SectionTitle("通用")
        InfoCard(title = "主题", subtitle = "跟随系统，或强制浅色/深色。") {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf("system" to "系统", "light" to "浅色", "dark" to "深色").forEachIndexed { index, (value, label) ->
                    SegmentedButton(
                        selected = settings.themeMode == value,
                        onClick = { viewModel.update { it.copy(themeMode = value) } },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = 3),
                    ) { Text(label) }
                }
            }
        }
        ToggleRow("动态取色", "使用系统壁纸配色（Android 12+）。", settings.dynamicColor) {
            viewModel.update { current -> current.copy(dynamicColor = it) }
        }

        SectionTitle("模型")
        InfoCard(
            title = "服务商与模型",
            subtitle = "添加 OpenAI 兼容服务，选择默认模型并验证真实能力。",
            onClick = { onNavigate("models") },
        ) { Text("能力来自真实测试，不根据模型名称猜测。", style = MaterialTheme.typography.bodySmall) }

        SectionTitle("Agent 行为")
        InfoCard(title = "审批策略", subtitle = "任何工具执行前都由安全策略强制确认。") {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(
                    selected = settings.approvalMode == ApprovalMode.HIGH_RISK_ASK,
                    onClick = { viewModel.update { it.copy(approvalMode = ApprovalMode.HIGH_RISK_ASK) } },
                    label = { Text("仅高风险操作需要确认（推荐）") },
                )
                FilterChip(
                    selected = settings.approvalMode == ApprovalMode.ALWAYS_ASK,
                    onClick = { viewModel.update { it.copy(approvalMode = ApprovalMode.ALWAYS_ASK) } },
                    label = { Text("每次工具调用都确认") },
                )
                FilterChip(
                    selected = settings.approvalMode == ApprovalMode.AUTO_APPROVE_SAFE,
                    onClick = { viewModel.update { it.copy(approvalMode = ApprovalMode.AUTO_APPROVE_SAFE) } },
                    label = { Text("自动批准；关键操作仍需确认") },
                )
            }
        }
        StepperRow(
            label = "最大迭代次数",
            value = settings.maxIterations,
            suffix = "",
            onDec = { viewModel.update { it.copy(maxIterations = (it.maxIterations - 1).coerceAtLeast(1)) } },
            onInc = { viewModel.update { it.copy(maxIterations = (it.maxIterations + 1).coerceAtMost(40)) } },
        )
        StepperRow(
            label = "最大输出 Token",
            value = settings.maxOutputTokens,
            suffix = "",
            onDec = { viewModel.update { it.copy(maxOutputTokens = (it.maxOutputTokens - 256).coerceAtLeast(256)) } },
            onInc = { viewModel.update { it.copy(maxOutputTokens = (it.maxOutputTokens + 256).coerceAtMost(32_000)) } },
        )
        StepperRow(
            label = "上下文窗口（Token）",
            value = settings.contextWindowTokens,
            suffix = "",
            onDec = { viewModel.update { it.copy(contextWindowTokens = (it.contextWindowTokens - 2_000).coerceAtLeast(4_000)) } },
            onInc = { viewModel.update { it.copy(contextWindowTokens = (it.contextWindowTokens + 2_000).coerceAtMost(200_000)) } },
        )
        StepperRow(
            label = "模型重试次数",
            value = settings.retries,
            suffix = "",
            onDec = { viewModel.update { it.copy(retries = (it.retries - 1).coerceAtLeast(0)) } },
            onInc = { viewModel.update { it.copy(retries = (it.retries + 1).coerceAtMost(5)) } },
        )

        SectionTitle("执行")
        ToggleRow("本机 Shell", "允许以应用 UID 执行命令（无 root、无 ADB）。", settings.allowLocalShell) {
            viewModel.update { current -> current.copy(allowLocalShell = it) }
        }
        ToggleRow("Shizuku Shell", "允许通过 Shizuku 以 ADB shell UID 执行命令（需要确认）。", settings.allowShizuku) {
            viewModel.update { current -> current.copy(allowShizuku = it) }
        }
        ToggleRow("无障碍自动化", "任务需要时允许读取界面与点击。", settings.allowAccessibility) {
            viewModel.update { current -> current.copy(allowAccessibility = it) }
        }
        StepperRow(
            label = "工具超时（秒）",
            value = (settings.toolTimeoutMs / 1_000).toInt(),
            suffix = "s",
            onDec = { viewModel.update { it.copy(toolTimeoutMs = (it.toolTimeoutMs - 15_000).coerceAtLeast(5_000)) } },
            onInc = { viewModel.update { it.copy(toolTimeoutMs = (it.toolTimeoutMs + 15_000).coerceAtMost(600_000)) } },
        )
        InfoCard(title = "Shizuku 设置", subtitle = "安装、配对、启动并授权。", onClick = { onNavigate("shizuku") })
        InfoCard(title = "无障碍设置", subtitle = "启用服务并查看使用边界。", onClick = { onNavigate("accessibility") })

        SectionTitle("集成")
        InfoCard(title = "MCP 服务", subtitle = "Streamable HTTP 端点与发现的工具。", onClick = { onNavigate("mcp") })
        InfoCard(title = "技能", subtitle = "已安装技能、权限与脚本运行时。", onClick = { onNavigate("skills") })

        SectionTitle("记忆与工作区")
        ToggleRow("长期记忆", "相关时把已保存的事实加入提示词。", settings.memoryEnabled) {
            viewModel.update { current -> current.copy(memoryEnabled = it) }
        }
        InfoCard(title = "记忆", subtitle = "查看、停用或删除已保存的事实。", onClick = { onNavigate("memory") })
        InfoCard(title = "工作区", subtitle = "任务产生的文件与产物。", onClick = { onNavigate("workspace") })

        SectionTitle("安全")
        ToggleRow(
            "允许截图发送到云端模型",
            "默认关闭。开启后，vision.analyze 可能把当前屏幕上传给视觉模型。",
            settings.allowCloudScreenshots,
        ) { viewModel.update { current -> current.copy(allowCloudScreenshots = it) } }
        InfoCard(
            title = "密钥存储",
            subtitle = "API Key 与 MCP Token 使用 Android Keystore 的 AES/GCM 加密，不写入数据库。",
        )

        SectionTitle("后台")
        ToggleRow("定时任务", "允许 WorkManager 执行在任务页创建的定时任务。", settings.backgroundEnabled) {
            viewModel.update { current -> current.copy(backgroundEnabled = it) }
        }
        InfoCard(title = "定时计划", subtitle = "重复检查与最近执行时间。", onClick = { onNavigate("tasks") })

        SectionTitle("诊断")
        InfoCard(title = "健康与日志", subtitle = "模型、权限、数据库、MCP 与存储。", onClick = { onNavigate("diagnostics") })
        InfoCard(title = "审计日志", subtitle = "每个任务与工具调用的结构化事件。", onClick = { onNavigate("logs") })
        ToggleRow("详细诊断", "在审计日志中记录更多细节。", settings.diagnosticsVerbose) {
            viewModel.update { current -> current.copy(diagnosticsVerbose = it) }
        }

        SectionTitle("关于")
        Text(
            "Mobile Agent 0.1.0 — 原生 Android 智能体，所有工具调用都经过权限策略。无 Root；" +
                "Shizuku、无障碍与后台执行均遵循 Android 规则。",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(bottom = 24.dp),
        )
    }

    if (showLanguage) {
        AlertDialog(
            onDismissRequest = { showLanguage = false },
            title = { Text(stringResource(R.string.settings_language)) },
            text = {
                Column {
                    listOf(
                        "" to stringResource(R.string.language_system),
                        "zh-CN" to stringResource(R.string.language_zh),
                        "en" to stringResource(R.string.language_en),
                    ).forEach { (tag, label) ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            androidx.compose.material3.TextButton(onClick = {
                                LocaleHelper.save(context, tag)
                                viewModel.setLanguage(tag)
                                showLanguage = false
                                (context as? Activity)?.recreate()
                            }) { Text(label) }
                            if (settings.languageTag == tag) Text("✓")
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showLanguage = false }) { Text(stringResource(R.string.action_close)) }
            },
        )
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun StepperRow(label: String, value: Int, suffix: String, onDec: () -> Unit, onInc: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        OutlinedButton(onClick = onDec) { Text("−") }
        Text(
            "$value$suffix",
            modifier = Modifier.padding(horizontal = 12.dp),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
        OutlinedButton(onClick = onInc) { Text("+") }
    }
}

@Composable
fun SimpleHeader(title: String, subtitle: String? = null) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        if (subtitle != null) {
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(4.dp))
        HorizontalDivider()
    }
}
