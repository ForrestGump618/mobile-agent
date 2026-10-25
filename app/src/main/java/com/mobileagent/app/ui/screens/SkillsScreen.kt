package com.mobileagent.app.ui.screens

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mobileagent.app.ui.EmptyState
import com.mobileagent.app.ui.InfoCard
import com.mobileagent.app.ui.StatusPill
import com.mobileagent.app.ui.StatusTone
import com.mobileagent.app.ui.vm.SkillsViewModel

@Composable
fun SkillsScreen() {
    val viewModel: SkillsViewModel = viewModel()
    val skills by viewModel.skills.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            viewModel.install(uri, uri.lastPathSegment?.substringAfterLast('/') ?: "skill.zip")
        }
    }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Skills", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Prompt skills guide the agent; script skills run python/node entry points when a runtime exists.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Button(onClick = { picker.launch(arrayOf("application/zip", "application/octet-stream")) }, enabled = !busy) {
                    Text("Import")
                }
            }
        }

        if (message != null) {
            item {
                InfoCard(title = "Status", subtitle = message) {
                    TextButton(onClick = { viewModel.clearMessage() }) { Text("Dismiss") }
                }
            }
        }

        if (skills.isEmpty()) {
            item { EmptyState("No skill installed. Built-in skills are seeded on first launch; if this list is empty, restart the app.") }
        }

        items(skills) { skill ->
            var expanded by remember(skill.id) { mutableStateOf(false) }
            InfoCard(
                title = "${skill.name} ${skill.version}",
                subtitle = skill.description.ifBlank { skill.id },
            ) {
                Column {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        StatusPill(
                            if (skill.enabled) "enabled" else "disabled",
                            if (skill.enabled) StatusTone.OK else StatusTone.UNKNOWN,
                        )
                        AssistChip(onClick = { }, label = { Text(skill.manifest.runtime) })
                        AssistChip(onClick = { }, label = { Text(skill.source.take(18)) })
                        Spacer(Modifier.weight(1f))
                        Switch(checked = skill.enabled, onCheckedChange = { viewModel.setEnabled(skill.id, it) })
                    }
                    if (skill.manifest.permissions.let {
                            it.network || it.files || it.shell || it.shizuku || it.accessibility
                        }
                    ) {
                        Text(
                            "Permissions: " + listOfNotNull(
                                "network".takeIf { skill.manifest.permissions.network },
                                "files".takeIf { skill.manifest.permissions.files },
                                "shell".takeIf { skill.manifest.permissions.shell },
                                "shizuku".takeIf { skill.manifest.permissions.shizuku },
                                "accessibility".takeIf { skill.manifest.permissions.accessibility },
                            ).joinToString(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (skill.manifest.requiredTools.isNotEmpty()) {
                        Text(
                            "Tools: " + skill.manifest.requiredTools.joinToString(),
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Hide instructions" else "Instructions") }
                        TextButton(onClick = { viewModel.delete(skill.id) }) { Text("Uninstall") }
                    }
                    if (expanded) {
                        Text(
                            skill.manifest.instructions.take(2_000),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 40,
                        )
                    }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}
