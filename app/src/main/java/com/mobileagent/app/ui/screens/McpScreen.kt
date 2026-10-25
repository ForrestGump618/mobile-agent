package com.mobileagent.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mobileagent.app.ui.EmptyState
import com.mobileagent.app.ui.InfoCard
import com.mobileagent.app.ui.SectionTitle
import com.mobileagent.app.ui.StatusPill
import com.mobileagent.app.ui.StatusTone
import com.mobileagent.app.ui.vm.McpViewModel
import com.mobileagent.core.model.McpServerConfig

@Composable
fun McpScreen() {
    val viewModel: McpViewModel = viewModel()
    val servers by viewModel.servers.collectAsStateWithLifecycle()
    val busyId by viewModel.busyServerId.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<McpServerConfig?>(null) }
    var showDialog by remember { mutableStateOf(false) }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("MCP servers", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Streamable HTTP JSON-RPC. Tools are registered in the same policy-gated registry as built-ins.",
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

        if (servers.isEmpty()) {
            item {
                EmptyState("No MCP server yet. Add an endpoint that implements the MCP Streamable HTTP transport.")
            }
        }

        items(servers) { server ->
            InfoCard(
                title = server.name,
                subtitle = server.endpoint.ifBlank { "(no endpoint)" },
            ) {
                Column {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        StatusPill(
                            when {
                                server.lastError != null -> "error"
                                server.lastConnectedAt != null -> "connected"
                                else -> "not connected"
                            },
                            when {
                                server.lastError != null -> StatusTone.ERROR
                                server.lastConnectedAt != null -> StatusTone.OK
                                else -> StatusTone.UNKNOWN
                            },
                        )
                        Text(
                            "${server.tools.size} tool(s)" + (server.protocolVersion?.let { " · protocol $it" } ?: ""),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    server.lastError?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    if (server.tools.isNotEmpty()) {
                        Text(
                            server.tools.joinToString { it.name }.take(400),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            lineHeight = 15.sp,
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(
                            onClick = { viewModel.connect(server.id) },
                            enabled = busyId == null,
                        ) {
                            if (busyId == server.id) {
                                CircularProgressIndicator(modifier = Modifier.height(14.dp), strokeWidth = 2.dp)
                            } else {
                                Text("Connect")
                            }
                        }
                        OutlinedButton(
                            onClick = {
                                viewModel.test(server.id, server.name, server.endpoint, server.authHeaderName.orEmpty(), "")
                            },
                        ) { Text("Test") }
                        TextButton(onClick = {
                            editing = server
                            showDialog = true
                        }) { Text("Edit") }
                        TextButton(onClick = { viewModel.delete(server.id) }) { Text("Delete") }
                    }
                }
            }
        }

        item {
            SectionTitle("stdio transport")
            Text(
                "stdio MCP servers need a local Python/Node runtime that Android does not ship. " +
                    "Mobile Agent keeps the transport type in the model but reports Runtime Unavailable instead of pretending to connect.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }

    if (showDialog) {
        McpDialog(
            initial = editing,
            onDismiss = { showDialog = false },
            onSave = { id, name, endpoint, header, token, enabled ->
                viewModel.save(id, name, endpoint, header, token, enabled, emptySet())
                showDialog = false
            },
        )
    }
}

@Composable
private fun McpDialog(
    initial: McpServerConfig?,
    onDismiss: () -> Unit,
    onSave: (String?, String, String, String, String, Boolean) -> Unit,
) {
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var endpoint by remember { mutableStateOf(initial?.endpoint.orEmpty()) }
    var header by remember { mutableStateOf(initial?.authHeaderName.orEmpty()) }
    var token by remember { mutableStateOf("") }
    var enabled by remember { mutableStateOf(initial?.enabled ?: true) }

    Dialog(onDismissRequest = onDismiss) {
        androidx.compose.material3.Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp)) {
            Column(
                Modifier
                    .heightIn(max = 620.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    if (initial == null) "Add MCP server" else "Edit MCP server",
                    style = MaterialTheme.typography.titleMedium,
                )
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true)
                OutlinedTextField(
                    value = endpoint,
                    onValueChange = { endpoint = it },
                    label = { Text("Endpoint URL") },
                    placeholder = { Text("https://example.com/mcp") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = header,
                    onValueChange = { header = it },
                    label = { Text("Auth header (optional)") },
                    placeholder = { Text("Authorization") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    label = { Text(if (initial?.authSecretId != null) "New token (leave blank to keep)" else "Token (optional)") },
                    singleLine = true,
                )
                Text(
                    "The token is stored with AES/GCM in the Android Keystore, never in the database or logs.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { onSave(initial?.id, name, endpoint, header, token, enabled) },
                        enabled = endpoint.startsWith("http"),
                    ) { Text("Save") }
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                }
            }
        }
    }
}
