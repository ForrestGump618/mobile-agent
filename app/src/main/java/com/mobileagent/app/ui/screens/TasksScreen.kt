package com.mobileagent.app.ui.screens

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mobileagent.app.ui.EmptyState
import com.mobileagent.app.ui.InfoCard
import com.mobileagent.app.ui.MonoText
import com.mobileagent.app.ui.SectionTitle
import com.mobileagent.app.ui.StatusPill
import com.mobileagent.app.ui.StatusTone
import com.mobileagent.app.ui.vm.TasksViewModel
import com.mobileagent.core.model.StepStatus
import com.mobileagent.core.model.TaskStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun TasksScreen() {
    val viewModel: TasksViewModel = viewModel()
    val tasks by viewModel.tasks.collectAsStateWithLifecycle()
    val selected by viewModel.selected.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val toolCalls by viewModel.toolCalls.collectAsStateWithLifecycle()
    val events by viewModel.events.collectAsStateWithLifecycle()
    val checkpoints by viewModel.checkpoints.collectAsStateWithLifecycle()
    val artifacts by viewModel.artifacts.collectAsStateWithLifecycle()

    if (selected == null) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Text("任务", style = MaterialTheme.typography.titleLarge)
                Text(
                    "每个任务都会保存执行计划、工具调用、事件与检查点。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (tasks.isEmpty()) {
                item { EmptyState("还没有任务，去「对话」页发起一个。") }
            }
            items(tasks) { task ->
                InfoCard(
                    title = task.title,
                    subtitle = "${task.status.name.replace('_', ' ')} · ${formatTimestamp(task.updatedAt)} · " +
                        "${task.toolCallCount} tool call(s)" +
                        (task.lastError?.let { " · ${it.take(80)}" } ?: ""),
                    onClick = { viewModel.select(task.id) },
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        StatusPill(task.status.name.replace('_', ' '), taskTone(task.status))
                        if (task.plan != null) {
                            AssistChip(onClick = { viewModel.select(task.id) }, label = { Text("计划 ${task.plan?.steps?.size} 步") })
                        }
                    }
                }
            }
        }
        return
    }

    val task = selected ?: return
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        TextButton(onClick = { viewModel.select(null) }) { Text("← 全部任务") }
        Text(task.title, style = MaterialTheme.typography.titleLarge)
        Text(task.goal, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusPill(task.status.name.replace('_', ' '), taskTone(task.status))
            OutlinedButton(onClick = { viewModel.export(task.id) }) { Text("导出") }
            if (task.status.isRunning) {
                OutlinedButton(onClick = { viewModel.cancel(task.id) }) { Text("停止") }
            } else if (task.status == TaskStatus.FAILED || task.status == TaskStatus.PAUSED) {
                Button(onClick = { viewModel.resume(task.id) }) { Text("恢复") }
            }
            OutlinedButton(onClick = { viewModel.delete(task.id) }) { Text("删除") }
        }

        if (message != null) {
            Spacer(Modifier.height(8.dp))
            InfoCard(title = "提示", subtitle = message) {
                TextButton(onClick = { viewModel.clearMessage() }) { Text("关闭") }
            }
        }

        if (task.lastError != null) {
            Spacer(Modifier.height(10.dp))
            InfoCard(title = "最近错误", subtitle = task.lastError)
        }

        SectionTitle("执行计划")
        if (task.plan == null) {
            Text("没有显式计划（简单任务）。", style = MaterialTheme.typography.bodySmall)
        } else {
            task.plan?.steps?.forEachIndexed { index, step ->
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                    Text("${index + 1}.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(end = 6.dp))
                    Column(Modifier.weight(1f)) {
                        Text(step.title, style = MaterialTheme.typography.bodyMedium)
                        if (step.detail.isNotBlank()) {
                            Text(step.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    StatusPill(stepStatusLabel(step.status), stepTone(step.status))
                }
            }
        }

        SectionTitle("工具调用（${toolCalls.size}）")
        toolCalls.takeLast(30).forEach { call ->
            var expanded by remember(call.id) { mutableStateOf(false) }
            Column(Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(vertical = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(call.toolName, fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.weight(1f))
                    StatusPill(
                        text = call.ok?.let { if (it) "成功" else "失败" } ?: "未知",
                        tone = when (call.ok) {
                            true -> StatusTone.OK
                            false -> StatusTone.ERROR
                            null -> StatusTone.WARN
                        },
                    )
                }
                Text(
                    "risk=${call.risk} · ${formatTimestamp(call.startedAt)}" +
                        (call.finishedAt?.let { " · ${it - call.startedAt} ms" } ?: ""),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (expanded) {
                    Spacer(Modifier.height(4.dp))
                    MonoText("args: ${call.argumentsJson.take(1_500)}", maxLines = 20)
                    call.resultJson?.let { resultJson ->
                        if (resultJson.isNotBlank()) {
                            MonoText("result: ${resultJson.take(1_500)}", maxLines = 20)
                        }
                    }
                    call.error?.let { Text("error: $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
                HorizontalDivider(Modifier.padding(top = 4.dp))
            }
        }

        SectionTitle("审计事件（${events.size}）")
        events.takeLast(25).forEach { event ->
            Text(
                "${formatTimestamp(event.createdAt)}  ${event.type}  ${event.message}",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                modifier = Modifier.padding(vertical = 1.dp),
            )
        }

        SectionTitle("检查点（${checkpoints.size}）")
        if (checkpoints.isEmpty()) {
            Text("尚未保存检查点。", style = MaterialTheme.typography.bodySmall)
        } else {
            checkpoints.takeLast(5).forEach { checkpoint ->
                Text(
                    "${formatTimestamp(checkpoint.createdAt)} · ${checkpoint.payloadJson.length} bytes",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        SectionTitle("产物文件（${artifacts.size}）")
        if (artifacts.isEmpty()) {
            Text("该任务没有生成文件。", style = MaterialTheme.typography.bodySmall)
        } else {
            artifacts.forEach { artifact ->
                Column(Modifier.padding(vertical = 3.dp)) {
                    Text("${artifact.name} · ${artifact.sizeBytes} bytes", style = MaterialTheme.typography.bodyMedium)
                    Text(artifact.path, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        if (task.resultText != null) {
            SectionTitle("最终回复")
            MonoText(task.resultText.orEmpty(), maxLines = 60)
        }
        Spacer(Modifier.height(24.dp))
    }
}

private fun taskTone(status: TaskStatus): StatusTone = when (status) {
    TaskStatus.COMPLETED -> StatusTone.OK
    TaskStatus.FAILED, TaskStatus.CANCELLED -> StatusTone.ERROR
    TaskStatus.PAUSED, TaskStatus.WAITING_FOR_CONFIRMATION, TaskStatus.WAITING_FOR_PERMISSION, TaskStatus.WAITING_FOR_UI -> StatusTone.WARN
    else -> StatusTone.OK
}

private fun stepTone(status: StepStatus): StatusTone = when (status) {
    StepStatus.DONE -> StatusTone.OK
    StepStatus.FAILED, StepStatus.BLOCKED -> StatusTone.ERROR
    StepStatus.RUNNING -> StatusTone.WARN
    else -> StatusTone.UNKNOWN
}

private fun formatTimestamp(timestamp: Long): String =
    SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(timestamp))

private fun taskStatusLabel(status: TaskStatus): String = when (status) {
    TaskStatus.CREATED -> "已创建"
    TaskStatus.PLANNING -> "规划中"
    TaskStatus.READY -> "就绪"
    TaskStatus.RUNNING_HEADLESS -> "执行中"
    TaskStatus.RUNNING_INTERACTIVE -> "界面操作中"
    TaskStatus.WAITING_FOR_PERMISSION -> "等待权限"
    TaskStatus.WAITING_FOR_CONFIRMATION -> "等待确认"
    TaskStatus.WAITING_FOR_UI -> "等待界面"
    TaskStatus.WAITING_FOR_NETWORK -> "等待网络"
    TaskStatus.PAUSED -> "已暂停"
    TaskStatus.RECOVERING -> "恢复中"
    TaskStatus.COMPLETED -> "已完成"
    TaskStatus.FAILED -> "失败"
    TaskStatus.CANCELLED -> "已取消"
}

private fun stepStatusLabel(status: StepStatus): String = when (status) {
    StepStatus.PENDING -> "待执行"
    StepStatus.RUNNING -> "执行中"
    StepStatus.DONE -> "完成"
    StepStatus.FAILED -> "失败"
    StepStatus.BLOCKED -> "受阻"
    StepStatus.SKIPPED -> "跳过"
}