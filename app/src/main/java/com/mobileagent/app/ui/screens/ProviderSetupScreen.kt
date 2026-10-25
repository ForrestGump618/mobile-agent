package com.mobileagent.app.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mobileagent.app.R
import com.mobileagent.app.ui.InfoCard
import com.mobileagent.app.ui.StatusPill
import com.mobileagent.app.ui.StatusTone
import com.mobileagent.app.ui.vm.ProviderSetupViewModel
import com.mobileagent.core.llm.CheckStatus
import com.mobileagent.core.llm.ProviderPreset
import com.mobileagent.core.llm.ProviderPresets

@Composable
fun ProviderSetupScreen(
    providerId: String? = null,
    onboarding: Boolean = false,
    onDone: () -> Unit,
    onSkip: () -> Unit = {},
) {
    val viewModel: ProviderSetupViewModel = viewModel()
    val form by viewModel.form.collectAsStateWithLifecycle()
    val testing by viewModel.testing.collectAsStateWithLifecycle()
    val saving by viewModel.saving.collectAsStateWithLifecycle()
    val result by viewModel.testResult.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val savedId by viewModel.savedId.collectAsStateWithLifecycle()
    var showKey by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val preset = ProviderPresets.byId(form.presetId)

    LaunchedEffect(providerId) {
        if (!providerId.isNullOrBlank()) viewModel.load(providerId)
    }
    LaunchedEffect(savedId) {
        if (savedId != null) {
            viewModel.clearSaved()
            onDone()
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (onboarding) {
            Text(stringResource(R.string.setup_welcome_title), style = MaterialTheme.typography.headlineSmall)
            Text(
                stringResource(R.string.setup_welcome_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                if (form.editingId == null) stringResource(R.string.models_add) else stringResource(R.string.models_edit_title),
                style = MaterialTheme.typography.headlineSmall,
            )
        }

        Text(stringResource(R.string.setup_choose_provider), style = MaterialTheme.typography.titleSmall)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(ProviderPresets.all) { item ->
                FilterChip(
                    selected = form.presetId == item.id,
                    onClick = { viewModel.selectPreset(item) },
                    label = { Text(item.displayName) },
                )
            }
        }

        preset?.let { current ->
            if (!current.notes.isNullOrBlank()) {
                Text(
                    current.notes.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            current.docsUrl?.let { url ->
                TextButton(onClick = {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                }) { Text(stringResource(R.string.setup_docs)) }
            }
        }

        OutlinedTextField(
            value = form.apiKey,
            onValueChange = { value -> viewModel.update { it.copy(apiKey = value) } },
            label = { Text(stringResource(R.string.setup_api_key)) },
            placeholder = {
                Text(
                    if (form.hasStoredKey) stringResource(R.string.setup_api_key_stored)
                    else stringResource(R.string.setup_api_key_hint),
                )
            },
            singleLine = true,
            visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { showKey = !showKey }) {
                    Icon(
                        if (showKey) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        contentDescription = null,
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = form.modelId,
            onValueChange = { value -> viewModel.update { it.copy(modelId = value) } },
            label = { Text(stringResource(R.string.setup_model)) },
            placeholder = { Text(preset?.modelHint ?: stringResource(R.string.setup_model_hint)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        if (preset != null && preset.models.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(preset.models) { model ->
                    AssistChip(
                        onClick = { viewModel.update { it.copy(modelId = model) } },
                        label = { Text(model) },
                    )
                }
            }
        }
        Text(
            stringResource(R.string.setup_note),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { viewModel.test() }, enabled = !testing) {
                if (testing) {
                    CircularProgressIndicator(modifier = Modifier.height(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.height(0.dp))
                    Text("  " + stringResource(R.string.setup_testing))
                } else {
                    Text(stringResource(R.string.setup_test))
                }
            }
            Button(onClick = { viewModel.save() }, enabled = !saving) {
                if (saving) {
                    CircularProgressIndicator(modifier = Modifier.height(16.dp), strokeWidth = 2.dp)
                } else {
                    Text(stringResource(R.string.setup_save))
                }
            }
        }

        error?.let {
            InfoCard(title = stringResource(R.string.setup_test_failed), subtitle = it)
        }

        result?.let { test ->
            InfoCard(
                title = if (test.ok) stringResource(R.string.setup_test_success) else stringResource(R.string.setup_test_failed),
                subtitle = "${test.latencyMs} ms",
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    test.checks.forEach { check ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Text(checkLabel(check.id), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            StatusPill(
                                text = when (check.status) {
                                    CheckStatus.OK -> stringResource(R.string.status_ok)
                                    CheckStatus.FAILED -> stringResource(R.string.status_failed)
                                    CheckStatus.SKIPPED -> stringResource(R.string.status_skipped)
                                },
                                tone = when (check.status) {
                                    CheckStatus.OK -> StatusTone.OK
                                    CheckStatus.FAILED -> StatusTone.ERROR
                                    CheckStatus.SKIPPED -> StatusTone.UNKNOWN
                                },
                            )
                        }
                        check.technical?.takeIf { check.status == CheckStatus.FAILED && it.isNotBlank() }?.let {
                            Text(
                                it.take(240),
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (test.verified.isNotEmpty()) {
                        Text(
                            stringResource(R.string.models_caps) + ": " +
                                test.verified.joinToString { viewModel.capabilityLabel(it) },
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
        }

        HorizontalDivider()
        TextButton(onClick = { viewModel.update { it.copy(advanced = !it.advanced) } }) {
            Text(stringResource(R.string.setup_advanced) + if (form.advanced) " ▲" else " ▼")
        }

        if (form.advanced) {
            OutlinedTextField(
                value = form.baseUrl,
                onValueChange = { value -> viewModel.update { it.copy(baseUrl = value) } },
                label = { Text(stringResource(R.string.setup_base_url)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = form.apiPath,
                onValueChange = { value -> viewModel.update { it.copy(apiPath = value) } },
                label = { Text(stringResource(R.string.setup_api_path)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = form.temperature,
                    onValueChange = { value -> viewModel.update { it.copy(temperature = value) } },
                    label = { Text(stringResource(R.string.setup_temperature)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = form.timeoutSec,
                    onValueChange = { value -> viewModel.update { it.copy(timeoutSec = value) } },
                    label = { Text(stringResource(R.string.setup_timeout)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = form.maxOutputTokens,
                    onValueChange = { value -> viewModel.update { it.copy(maxOutputTokens = value) } },
                    label = { Text(stringResource(R.string.setup_max_tokens)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = form.contextWindow,
                    onValueChange = { value -> viewModel.update { it.copy(contextWindow = value) } },
                    label = { Text(stringResource(R.string.setup_context_window)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
            }
            SwitchRow(stringResource(R.string.setup_streaming), form.streaming) { value ->
                viewModel.update { it.copy(streaming = value) }
            }
            SwitchRow(stringResource(R.string.setup_tools), form.toolCalling) { value ->
                viewModel.update { it.copy(toolCalling = value) }
            }
            SwitchRow(stringResource(R.string.setup_vision), form.vision) { value ->
                viewModel.update { it.copy(vision = value) }
            }
            if (preset != null && preset.id == "custom") {
                Text(
                    stringResource(R.string.setup_preset_note),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (onboarding) {
            TextButton(onClick = onSkip) { Text(stringResource(R.string.action_skip)) }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun checkLabel(id: String): String = when (id) {
    "config" -> stringResource(R.string.check_config)
    "network" -> stringResource(R.string.check_network)
    "auth" -> stringResource(R.string.check_auth)
    "model" -> stringResource(R.string.check_model)
    "text" -> stringResource(R.string.check_text)
    "stream" -> stringResource(R.string.check_stream)
    else -> id
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 44.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
