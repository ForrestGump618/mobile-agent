package com.mobileagent.app.ui.screens

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mobileagent.app.ui.InfoCard
import com.mobileagent.app.ui.KeyValueRow
import com.mobileagent.app.ui.MonoText
import com.mobileagent.app.ui.SectionTitle
import com.mobileagent.app.ui.StatusPill
import com.mobileagent.app.ui.StatusTone
import com.mobileagent.app.ui.vm.DeviceViewModel

@Composable
fun DeviceScreen(onRequestShizuku: () -> Unit) {
    val viewModel: DeviceViewModel = viewModel()
    val snapshot by viewModel.snapshot.collectAsStateWithLifecycle()
    val shizuku by viewModel.shizukuStatus.collectAsStateWithLifecycle()
    val accessibility by viewModel.accessibilityConnected.collectAsStateWithLifecycle()
    val uiSnapshot by viewModel.uiSnapshot.collectAsStateWithLifecycle()
    val screenshot by viewModel.screenshot.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(Unit) { viewModel.refresh() }

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { viewModel.refresh() }) { Text("Refresh") }
                OutlinedButton(onClick = onRequestShizuku) { Text("Grant Shizuku") }
            }
        }

        snapshot?.let { device ->
            item {
                InfoCard(title = "${device.manufacturer} ${device.model}", subtitle = "Android ${device.androidVersion} (SDK ${device.sdk})") {
                    Column {
                        KeyValueRow("ABIs", device.abis)
                        KeyValueRow("Storage", "${device.freeStorageMb} MB free / ${device.totalStorageMb} MB")
                        KeyValueRow("Battery", if (device.batteryPercent >= 0) "${device.batteryPercent}%" else "unknown")
                        KeyValueRow("Locale", device.locale)
                        KeyValueRow("Screen", device.screen)
                        KeyValueRow("App version", device.appVersion)
                        KeyValueRow("Root", if (device.rooted) "su binary present" else "not rooted")
                    }
                }
            }
        }

        item {
            InfoCard(
                title = "Shizuku",
                subtitle = shizuku?.message,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    StatusPill(
                        text = when {
                            shizuku?.connected == true -> "connected"
                            shizuku?.running == true -> "running"
                            shizuku?.installed == true -> "installed, stopped"
                            else -> "not installed"
                        },
                        tone = when {
                            shizuku?.connected == true -> StatusTone.OK
                            shizuku?.installed == true -> StatusTone.WARN
                            else -> StatusTone.ERROR
                        },
                    )
                    if (shizuku?.installed == true && shizuku?.connected != true) {
                        OutlinedButton(onClick = onRequestShizuku) { Text("Allow") }
                    }
                }
            }
        }

        item {
            InfoCard(
                title = "Accessibility automation",
                subtitle = if (accessibility) "Service connected." else "Service not connected.",
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusPill(
                        if (accessibility) "connected" else "off",
                        if (accessibility) StatusTone.OK else StatusTone.WARN,
                    )
                    OutlinedButton(onClick = {
                        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }) { Text("Open settings") }
                }
            }
        }

        item {
            InfoCard(title = "UI tree", subtitle = "Node ids are valid only for one snapshot.") {
                Column {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { viewModel.captureTree() }) { Text("Capture tree") }
                        OutlinedButton(onClick = { viewModel.captureScreen() }) { Text("Screenshot") }
                    }
                    uiSnapshot?.let { snapshot ->
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "${snapshot.packageName} · ${snapshot.allNodes().size} nodes · ${snapshot.screenWidth}×${snapshot.screenHeight}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        snapshot.allNodes().filter { it.text.isNotBlank() || it.clickable }.take(25).forEach { node ->
                            Text(
                                "${node.nodeId}  ${node.label().take(60)}  ${if (node.clickable) "[tap]" else ""}${if (node.editable) "[edit]" else ""}",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                lineHeight = 15.sp,
                            )
                        }
                    }
                }
            }
        }

        screenshot?.let { bitmap ->
            item {
                InfoCard(title = "Screenshot preview", subtitle = "Captured through the accessibility API.") {
                    Image(
                        bitmap = bitmap,
                        contentDescription = "Screen capture",
                        modifier = Modifier.fillMaxWidth(),
                        contentScale = ContentScale.Fit,
                    )
                }
            }
        }

        if (message != null) {
            item {
                InfoCard(title = "Notice", subtitle = message) {
                    TextButton(onClick = { viewModel.clearMessage() }) { Text("Dismiss") }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
fun ShizukuScreen(onRequestPermission: () -> Unit) {
    val viewModel: DeviceViewModel = viewModel()
    val shizuku by viewModel.shizukuStatus.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        InfoCard(title = "Shizuku status", subtitle = shizuku?.message) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusPill(
                    if (shizuku?.connected == true) "connected" else "not connected",
                    if (shizuku?.connected == true) StatusTone.OK else StatusTone.WARN,
                )
                OutlinedButton(onClick = { viewModel.refresh() }) { Text("Refresh") }
            }
        }

        SectionTitle("Setup guide")
        val steps = listOf(
            "Install Shizuku from the official source (package moe.shizuku.privileged.api).",
            "Enable Developer options on the phone.",
            "Enable Wireless debugging and keep the phone on the same Wi-Fi.",
            "Open Shizuku and pair with wireless debugging when it asks.",
            "Start Shizuku and wait until it reports that the service is running.",
            "Return here and tap Grant Shizuku to authorize Mobile Agent.",
            "Wait for the status to show connected, then run a read-only command from Terminal.",
            "If the phone reboots, Shizuku must be started again; Mobile Agent never bypasses that step.",
        )
        steps.forEachIndexed { index, step ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text("${index + 1}.", modifier = Modifier.padding(end = 8.dp), style = MaterialTheme.typography.bodySmall)
                Text(step, style = MaterialTheme.typography.bodyMedium)
            }
        }

        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onRequestPermission) { Text("Grant permission") }
            OutlinedButton(onClick = {
                runCatching {
                    val intent = context.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
                    if (intent != null) context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }) { Text("Open Shizuku") }
        }

        Spacer(Modifier.height(18.dp))
        InfoCard(
            title = "What Shizuku can and cannot do",
            subtitle = "Shizuku gives Mobile Agent the ADB shell UID, not root. App sandboxing, lock screen, " +
                "biometrics, permission dialogs and protected windows still apply.",
        ) { Text("Every Shizuku command asks for your confirmation under the default policy.", style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
fun AccessibilityScreen() {
    val viewModel: DeviceViewModel = viewModel()
    val connected by viewModel.accessibilityConnected.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        InfoCard(title = "Accessibility service", subtitle = if (connected) "Connected." else "Not connected.") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusPill(if (connected) "connected" else "off", if (connected) StatusTone.OK else StatusTone.WARN)
                OutlinedButton(onClick = {
                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }) { Text("Open settings") }
            }
        }
        SectionTitle("How it is used")
        Text(
            "Mobile Agent uses the official AccessibilityService API to read the visible UI tree, tap elements, " +
                "type text, scroll and capture the screen when a task explicitly requires UI automation. " +
                "It never taps while you operate the phone without an active task, and it cannot read protected windows.",
            style = MaterialTheme.typography.bodyMedium,
        )
        SectionTitle("Limits")
        Text(
            "· Node ids are only valid within one snapshot.\n" +
                "· Apps that block accessibility will return an empty tree.\n" +
                "· Screenshots may be refused for secure windows.\n" +
                "· Lock screen, biometric prompts and permission dialogs are never bypassed.",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
