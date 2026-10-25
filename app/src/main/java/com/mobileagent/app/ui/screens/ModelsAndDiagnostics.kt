package com.mobileagent.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.launch
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mobileagent.app.ui.EmptyState
import com.mobileagent.app.ui.InfoCard
import com.mobileagent.app.ui.KeyValueRow
import com.mobileagent.app.ui.SectionTitle
import com.mobileagent.app.ui.StatusPill
import com.mobileagent.app.ui.StatusTone
import com.mobileagent.app.ui.vm.SettingsViewModel
import com.mobileagent.core.model.ProviderConfig

@Composable
private fun LegacyModelsScreen() {
    val viewModel: SettingsViewModel = viewModel()
    val providers by viewModel.providers.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val probing by viewModel.probingProviderId.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<ProviderConfig?>(null) }
    var showDialog by remember { mutableStateOf(false) }

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Models", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "OpenAI-compatible endpoints. The API key is encrypted in the Android Keystore.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Button(onClick = {
                    editing = null
                    showDialog = true
                }) { Text("Add") }
            }
        }

        if (message != null) {
            item {
                InfoCard(title = "Status", subtitle = message) {
                    TextButton(onClick = { viewModel.clearMessage() }) { Text("Dismiss") }
                }
            }
        }

        if (providers.isEmpty()) {
            item { EmptyState("No provider configured. Add one to let the agent reason and call tools.") }
        }

        items(providers) { provider ->
            InfoCard(title = provider.name, subtitle = provider.baseUrl) {
                Column {
                    KeyValueRow("Model id", provider.modelId, mono = true)
                    KeyValueRow(
                        "Context / max out",
                        "${provider.contextWindow} / ${provider.maxOutputTokens}",
                    )
                    KeyValueRow(
                        "Capabilities",
                        buildString {
                            append(if (provider.toolCalling) "tools " else "")
                            append(if (provider.vision) "vision " else "")
                            append(if (provider.streaming) "stream " else "")
                            append(if (provider.reasoning) "reasoning" else "")
                        }.trim().ifBlank { "text" },
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = settings.activeProviderId == provider.id,
                            onClick = { viewModel.setActiveProvider(provider.id) },
                        )
                        Text("Active model", style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.weight(1f))
                        if (probing == provider.id) {
                            CircularProgressIndicator(modifier = Modifier.height(16.dp), strokeWidth = 2.dp)
                        } else {
                            OutlinedButton(onClick = { viewModel.probe(provider.id) }) { Text("Probe") }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = {
                            editing = provider
                            showDialog = true
                        }) { Text("Edit") }
                        TextButton(onClick = { viewModel.deleteProvider(provider.id) }) { Text("Delete") }
                    }
                }
            }
        }

        item {
            SectionTitle("Role routing")
            Text(
                "Chat, planning, execution and summary use the active provider unless you pin a different one. " +
                    "Vision requests only use providers flagged as vision-capable; when none is available the vision tool reports it.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        item { Spacer(Modifier.height(24.dp)) }
    }

    if (showDialog) {
        ProviderDialog(
            initial = editing,
            onDismiss = { showDialog = false },
            onSave = { id, name, baseUrl, modelId, apiKey, vision, tools, streaming, reasoning, temp, maxOut, context, timeout ->
                viewModel.saveProvider(
                    id = id,
                    name = name,
                    baseUrl = baseUrl,
                    modelId = modelId,
                    apiKey = apiKey,
                    vision = vision,
                    toolCalling = tools,
                    streaming = streaming,
                    reasoning = reasoning,
                    temperature = temp,
                    maxOutputTokens = maxOut,
                    contextWindow = context,
                    timeoutMs = timeout,
                )
                showDialog = false
            },
        )
    }
}

@Composable
private fun ProviderDialog(
    initial: ProviderConfig?,
    onDismiss: () -> Unit,
    onSave: (String?, String, String, String, String, Boolean, Boolean, Boolean, Boolean, Double, Int, Int, Long) -> Unit,
) {
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var baseUrl by remember { mutableStateOf(initial?.baseUrl ?: "https://api.openai.com/v1") }
    var modelId by remember { mutableStateOf(initial?.modelId.orEmpty()) }
    var apiKey by remember { mutableStateOf("") }
    var vision by remember { mutableStateOf(initial?.vision ?: false) }
    var toolCalling by remember { mutableStateOf(initial?.toolCalling ?: true) }
    var streaming by remember { mutableStateOf(initial?.streaming ?: true) }
    var reasoning by remember { mutableStateOf(initial?.reasoning ?: false) }
    var temperature by remember { mutableStateOf((initial?.temperature ?: 0.2).toString()) }
    var maxOut by remember { mutableStateOf((initial?.maxOutputTokens ?: 2_048).toString()) }
    var contextWindow by remember { mutableStateOf((initial?.contextWindow ?: 32_768).toString()) }
    var timeout by remember { mutableStateOf(((initial?.timeoutMs ?: 60_000) / 1000).toString()) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(18.dp)) {
            Column(
                Modifier
                    .heightIn(max = 620.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(if (initial == null) "Add provider" else "Edit provider", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                OutlinedTextField(baseUrl, { baseUrl = it }, label = { Text("Base URL") }, singleLine = true)
                OutlinedTextField(modelId, { modelId = it }, label = { Text("Model id") }, singleLine = true)
                OutlinedTextField(
                    apiKey,
                    { apiKey = it },
                    label = { Text(if (initial?.apiKeyId != null) "New API key (blank = keep current)" else "API key") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                )
                SwitchRow("Vision capable", vision) { vision = it }
                SwitchRow("Tool calling", toolCalling) { toolCalling = it }
                SwitchRow("Streaming", streaming) { streaming = it }
                SwitchRow("Reasoning model", reasoning) { reasoning = it }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(temperature, { temperature = it }, label = { Text("Temp") }, modifier = Modifier.weight(1f), singleLine = true)
                    OutlinedTextField(timeout, { timeout = it }, label = { Text("Timeout s") }, modifier = Modifier.weight(1f), singleLine = true)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(maxOut, { maxOut = it }, label = { Text("Max out") }, modifier = Modifier.weight(1f), singleLine = true)
                    OutlinedTextField(contextWindow, { contextWindow = it }, label = { Text("Context") }, modifier = Modifier.weight(1f), singleLine = true)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            onSave(
                                initial?.id,
                                name,
                                baseUrl,
                                modelId,
                                apiKey,
                                vision,
                                toolCalling,
                                streaming,
                                reasoning,
                                temperature.toDoubleOrNull() ?: 0.2,
                                maxOut.toIntOrNull() ?: 2_048,
                                contextWindow.toIntOrNull() ?: 32_768,
                                (timeout.toLongOrNull() ?: 60L) * 1_000L,
                            )
                        },
                        enabled = baseUrl.startsWith("http") && modelId.isNotBlank(),
                    ) { Text("Save") }
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                }
            }
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
fun DiagnosticsScreen() {
    val viewModel: SettingsViewModel = viewModel()
    val diagnostics by viewModel.diagnostics.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()

    androidx.compose.runtime.LaunchedEffect(Unit) { viewModel.refreshDiagnostics() }

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Diagnostics", style = MaterialTheme.typography.titleLarge)
                    Text("Live health, no cached or invented values.", style = MaterialTheme.typography.bodySmall)
                }
                OutlinedButton(onClick = { viewModel.refreshDiagnostics() }) { Text("Refresh") }
            }
        }
        if (message != null) {
            item {
                InfoCard(title = "Notice", subtitle = message) {
                    TextButton(onClick = { viewModel.clearMessage() }) { Text("Dismiss") }
                }
            }
        }
        items(diagnostics) { item ->
            InfoCard(title = item.label, subtitle = item.detail) {
                StatusPill(
                    item.status.name,
                    when (item.status.name) {
                        "OK" -> StatusTone.OK
                        "WARNING" -> StatusTone.WARN
                        "ERROR" -> StatusTone.ERROR
                        else -> StatusTone.UNKNOWN
                    },
                )
            }
        }
        item {
            Button(onClick = { viewModel.exportLogs() }) { Text("Export diagnostics") }
        }
        item {
            Text(
                "Exports are written to the app workspace with credentials redacted.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun MemoryScreen() {
    val viewModel: SettingsViewModel = viewModel()
    val memories by viewModel.memories.collectAsStateWithLifecycle()

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text("Memory", style = MaterialTheme.typography.titleLarge)
            Text(
                "Facts saved by the agent or by you. Retrieval is keyword based (no embedding provider is configured); " +
                    "sensitive entries are excluded from prompts.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (memories.isEmpty()) {
            item { EmptyState("No memory yet. Ask the agent to remember a preference.") }
        }
        items(memories) { memory ->
            InfoCard(title = memory.key, subtitle = memory.value) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusPill(if (memory.enabled) "enabled" else "disabled", if (memory.enabled) StatusTone.OK else StatusTone.UNKNOWN)
                    Spacer(Modifier.weight(1f))
                    Switch(
                        checked = memory.enabled,
                        onCheckedChange = { viewModel.setMemoryEnabled(memory.id, it) },
                    )
                    TextButton(onClick = { viewModel.deleteMemory(memory.id) }) { Text("Delete") }
                }
            }
        }
    }
}

@Composable
fun WorkspaceScreen() {
    val viewModel: SettingsViewModel = viewModel()
    val artifacts by viewModel.artifacts.collectAsStateWithLifecycle()
    var files by remember { mutableStateOf<List<java.io.File>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    androidx.compose.runtime.LaunchedEffect(Unit) {
        files = runCatching { viewModel.workspaceFiles() }.getOrDefault(emptyList())
        loading = false
    }

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Workspace", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "App-private files. The agent cannot read anything outside this directory.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                OutlinedButton(onClick = {
                    scope.launch {
                        loading = true
                        files = runCatching { viewModel.workspaceFiles() }.getOrDefault(emptyList())
                        loading = false
                    }
                }) { Text("Refresh") }
            }
        }
        if (loading) {
            item { CircularProgressIndicator() }
        }
        items(files) { file ->
            InfoCard(
                title = file.name,
                subtitle = "${file.length()} bytes | ${file.absolutePath}",
            )
        }
        if (!loading && files.isEmpty()) {
            item { EmptyState("No file in the workspace yet. Ask the agent to write a report.") }
        }
        item { SectionTitle("Artifacts index (${artifacts.size})") }
        items(artifacts) { artifact ->
            InfoCard(title = artifact.name, subtitle = artifact.path) {
                Text("${artifact.mime} | ${artifact.sizeBytes} bytes", style = MaterialTheme.typography.labelSmall)
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
fun LogsScreen() {
    val viewModel: SettingsViewModel = viewModel()
    val events by viewModel.events.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    val filtered = remember(events, query) {
        if (query.isBlank()) events.reversed() else events.reversed().filter {
            it.message.contains(query, true) || it.type.name.contains(query, true) || (it.taskId?.contains(query) == true)
        }
    }

    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Audit log", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = { viewModel.exportLogs() }) { Text("Export") }
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Filter by task id, type or text") },
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
            singleLine = true,
        )
        if (message != null) {
            Text(message.orEmpty(), style = MaterialTheme.typography.bodySmall)
        }
        LazyColumn(contentPadding = PaddingValues(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(filtered.take(300)) { event ->
                Column(Modifier.fillMaxWidth()) {
                    Text(
                        "${formatDateTime(event.createdAt)}  ${event.type}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(event.message, style = MaterialTheme.typography.bodySmall)
                    event.taskId?.let { taskId ->
                        Text(
                            "task ${taskId.take(8)}" + (event.executionId?.let { " | exec ${it.take(8)}" } ?: ""),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (filtered.isEmpty()) {
                item { EmptyState("No matching event.") }
            }
        }
    }
}

private fun formatDateTime(timestamp: Long): String =
    java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(timestamp))
