package com.mobileagent.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.mobileagent.app.ui.StatusPill
import com.mobileagent.app.ui.StatusTone
import com.mobileagent.app.ui.vm.TerminalMode
import com.mobileagent.app.ui.vm.TerminalTone
import com.mobileagent.app.ui.vm.TerminalViewModel

@Composable
fun TerminalScreen() {
    val viewModel: TerminalViewModel = viewModel()
    val mode by viewModel.mode.collectAsStateWithLifecycle()
    val lines by viewModel.lines.collectAsStateWithLifecycle()
    val running by viewModel.running.collectAsStateWithLifecycle()
    val history by viewModel.history.collectAsStateWithLifecycle()
    val shizuku by viewModel.shizukuStatus.collectAsStateWithLifecycle()
    var command by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.animateScrollToItem(lines.size - 1)
    }

    Column(modifier = Modifier.fillMaxSize().imePadding()) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = mode == TerminalMode.LOCAL,
                    onClick = { viewModel.setMode(TerminalMode.LOCAL) },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                ) { Text("应用 Shell") }
                SegmentedButton(
                    selected = mode == TerminalMode.SHIZUKU,
                    onClick = { viewModel.setMode(TerminalMode.SHIZUKU) },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                ) { Text("Shizuku（ADB）") }
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                StatusPill(
                    text = when {
                        mode == TerminalMode.LOCAL -> "应用 UID，无 root"
                        shizuku?.connected == true -> "Shell UID，已连接"
                        shizuku?.running == true -> "未授权"
                        else -> "未运行"
                    },
                    tone = when {
                        mode == TerminalMode.LOCAL -> StatusTone.UNKNOWN
                        shizuku?.connected == true -> StatusTone.OK
                        else -> StatusTone.WARN
                    },
                )
                Text(
                    "  " + (if (mode == TerminalMode.LOCAL) "仅能访问应用可访问的文件。" else shizuku?.message.orEmpty()),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(lines) { line ->
                Text(
                    text = line.text,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    color = when (line.tone) {
                        TerminalTone.COMMAND -> MaterialTheme.colorScheme.primary
                        TerminalTone.ERROR -> MaterialTheme.colorScheme.error
                        TerminalTone.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
                        TerminalTone.OUTPUT -> MaterialTheme.colorScheme.onSurface
                    },
                )
            }
            if (lines.isEmpty()) {
                item {
                    Text(
                        "输入命令，例如：getprop ro.build.version.release、ls -la、df -h /data",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (history.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(onClick = { command = history.last() }) { Text("↑ 上一条") }
                TextButton(onClick = { viewModel.clear() }) { Text("清空") }
                TextButton(onClick = { viewModel.refreshShizuku() }) { Text("刷新") }
            }
        }

        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.Bottom) {
            OutlinedTextField(
                value = command,
                onValueChange = { command = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("命令") },
                maxLines = 3,
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            )
            Spacer(Modifier.width(8.dp))
            if (running) {
                OutlinedButton(onClick = { viewModel.cancel() }) { Text("取消") }
            } else {
                Button(
                    onClick = {
                        viewModel.run(command)
                        command = ""
                    },
                    enabled = command.isNotBlank(),
                ) { Text("运行") }
            }
        }
        if (running) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                CircularProgressIndicator(modifier = Modifier.width(14.dp), strokeWidth = 2.dp)
                Text("  执行中…", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
