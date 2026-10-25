package com.mobileagent.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mobileagent.app.ui.InfoCard
import com.mobileagent.app.ui.KeyValueRow
import com.mobileagent.app.ui.SectionTitle
import com.mobileagent.app.ui.StatusPill
import com.mobileagent.app.ui.StatusTone
import com.mobileagent.app.ui.vm.DashboardViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DashboardScreen(
    onOpenChat: () -> Unit,
    onOpenTasks: () -> Unit,
    onOpenDevice: () -> Unit,
    onNavigate: (String) -> Unit,
) {
    val viewModel: DashboardViewModel = viewModel()
    val status by viewModel.status.collectAsStateWithLifecycle()
    val tasks by viewModel.tasks.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.refresh() }

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(onClick = onOpenChat, modifier = Modifier.weight(1f)) { Text("新建任务") }
                OutlinedButton(onClick = { onNavigate("terminal") }) { Text("终端") }
            }
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("系统状态", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatusPill(
                            text = if (status.modelLabel != null) "模型就绪" else "未配置模型",
                            tone = if (status.modelLabel != null) StatusTone.OK else StatusTone.ERROR,
                        )
                        StatusPill(
                            text = when {
                                status.shizuku == null -> "Shizuku 未知"
                                status.shizuku?.connected == true -> "Shizuku 已连接"
                                status.shizuku?.running == true -> "Shizuku 未授权"
                                else -> "Shizuku 未运行"
                            },
                            tone = when {
                                status.shizuku?.connected == true -> StatusTone.OK
                                status.shizuku?.running == true -> StatusTone.WARN
                                else -> StatusTone.WARN
                            },
                        )
                        StatusPill(
                            text = if (status.accessibilityConnected) "界面自动化开启" else "界面自动化关闭",
                            tone = if (status.accessibilityConnected) StatusTone.OK else StatusTone.UNKNOWN,
                        )
                    }
                    HorizontalDivider()
                    KeyValueRow("当前模型", status.modelLabel ?: "not configured")
                    KeyValueRow("服务商数量", status.providerCount.toString())
                    KeyValueRow("MCP 服务", if (status.mcpTotal == 0) "none configured" else "${status.mcpConnected}/${status.mcpTotal} connected")
                    KeyValueRow("已启用技能", status.skillsEnabled.toString())
                    KeyValueRow("失败任务", status.failedTasks.toString())
                    if (status.providerCount == 0) {
                        OutlinedButton(onClick = { onNavigate("models") }) { Text("配置模型") }
                    }
                }
            }
        }

        if (status.runningTaskId != null) {
            item {
                InfoCard(
                    title = "正在执行：${status.runningTaskTitle ?: "任务"}",
                    subtitle = "打开任务查看执行过程，或在任务页停止。",
                    onClick = onOpenTasks,
                ) {
                    StatusPill("执行中", StatusTone.OK)
                }
            }
        }

        item {
            SectionTitle("最近任务")
        }

        if (tasks.isEmpty()) {
            item {
                InfoCard(
                    title = "还没有任务",
                    subtitle = "让 Agent 检查设备、查询 API 或自动操作界面。",
                    onClick = onOpenChat,
                ) { Text("用自然语言描述目标即可。", style = MaterialTheme.typography.bodySmall) }
            }
        } else {
            items(tasks.take(8)) { task ->
                InfoCard(
                    title = task.title,
                    subtitle = "${task.status.name} · ${formatTime(task.updatedAt)} · ${task.toolCallCount} tool call(s)",
                    onClick = onOpenTasks,
                )
            }
        }

        item {
            SectionTitle("快捷入口")
        }
        item {
            InfoCard(title = "设备控制", subtitle = "Shizuku, accessibility, UI tree and screenshots", onClick = onOpenDevice)
        }
        item {
            InfoCard(title = "诊断", subtitle = "模型、权限、数据库与 MCP 健康状态", onClick = { onNavigate("diagnostics") })
        }
        item {
            InfoCard(title = "技能", subtitle = "内置与导入的能力", onClick = { onNavigate("skills") })
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

fun formatTime(timestamp: Long): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(timestamp))
