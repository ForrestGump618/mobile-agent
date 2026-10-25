package com.mobileagent.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mobileagent.app.R
import com.mobileagent.app.ui.EmptyState
import com.mobileagent.app.ui.InfoCard
import com.mobileagent.app.ui.StatusPill
import com.mobileagent.app.ui.StatusTone
import com.mobileagent.app.ui.vm.ModelsViewModel
import com.mobileagent.core.llm.ProviderPresets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ModelsScreen(
    onAdd: () -> Unit,
    onEdit: (String) -> Unit,
) {
    val viewModel: ModelsViewModel = viewModel()
    val providers by viewModel.providers.collectAsStateWithLifecycle()
    val activeId by viewModel.activeProviderId.collectAsStateWithLifecycle()
    val testingId by viewModel.testingId.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    var pendingDelete by remember { mutableStateOf<String?>(null) }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.models_title), style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "只有真正测试通过的能力才会显示为已支持。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Button(onClick = onAdd) { Text(stringResource(R.string.models_add)) }
            }
        }

        if (message != null) {
            item {
                InfoCard(title = "状态", subtitle = message) {
                    TextButton(onClick = { viewModel.clearMessage() }) { Text("知道了") }
                }
            }
        }

        if (providers.isEmpty()) {
            item {
                EmptyState(stringResource(R.string.models_none))
            }
        }

        items(providers) { provider ->
            val preset = ProviderPresets.byId(provider.presetId)
            val isActive = activeId == provider.id
            InfoCard(
                title = provider.name + if (isActive) "  ·  " + stringResource(R.string.models_default) else "",
                subtitle = provider.modelId,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusPill(
                            text = when {
                                provider.lastError != null -> "连接失败"
                                provider.verifiedCapabilities.isNotEmpty() -> "已验证"
                                else -> stringResource(R.string.models_untested)
                            },
                            tone = when {
                                provider.lastError != null -> StatusTone.ERROR
                                provider.verifiedCapabilities.isNotEmpty() -> StatusTone.OK
                                else -> StatusTone.UNKNOWN
                            },
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            preset?.displayName ?: "自定义 API",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        provider.baseUrl,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (provider.verifiedCapabilities.isNotEmpty()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            provider.verifiedCapabilities.forEach { capability ->
                                AssistChip(
                                    onClick = { },
                                    label = { Text(viewModel.capabilityName(capability)) },
                                )
                            }
                        }
                    } else {
                        Text(
                            stringResource(R.string.models_no_caps),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    provider.lastTestedAt?.let { testedAt ->
                        Text(
                            stringResource(R.string.models_tested_at) + ": " +
                                SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(testedAt)),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    provider.lastError?.let {
                        Text(
                            it.take(200),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = isActive,
                            onClick = { viewModel.setDefault(provider.id) },
                        )
                        Text(
                            if (isActive) stringResource(R.string.models_default) else stringResource(R.string.models_set_default),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(Modifier.weight(1f))
                        if (testingId == provider.id) {
                            CircularProgressIndicator(modifier = Modifier.height(18.dp), strokeWidth = 2.dp)
                        } else {
                            OutlinedButton(onClick = { viewModel.test(provider.id) }) {
                                Text(stringResource(R.string.action_test))
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { onEdit(provider.id) }) { Text(stringResource(R.string.action_edit)) }
                        TextButton(onClick = { pendingDelete = provider.id }) {
                            Text(stringResource(R.string.action_delete))
                        }
                    }
                }
            }
        }

        item {
            Text(
                "提示：切换默认模型后，新对话会立即使用该模型；正在生成的消息不受影响。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item { Spacer(Modifier.height(24.dp)) }
    }

    pendingDelete?.let { id ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.action_delete)) },
            text = { Text(stringResource(R.string.models_delete_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(id)
                    pendingDelete = null
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}
