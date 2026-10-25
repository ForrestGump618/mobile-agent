package com.mobileagent.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mobileagent.core.model.RiskLevel
import com.mobileagent.core.security.ApprovalRequest

@Composable
fun ApprovalDialog(request: ApprovalRequest, onDecision: (Boolean) -> Unit) {
    AlertDialog(
        onDismissRequest = { onDecision(false) },
        title = { Text("Confirmation required") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusPill(
                        text = request.risk.name,
                        tone = when (request.risk) {
                            RiskLevel.LOW, RiskLevel.MEDIUM -> StatusTone.OK
                            RiskLevel.HIGH -> StatusTone.WARN
                            RiskLevel.CRITICAL -> StatusTone.ERROR
                        },
                    )
                    Text(
                        "  ${request.toolName}",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Text(request.title, style = MaterialTheme.typography.titleSmall)
                Text(request.summary, style = MaterialTheme.typography.bodySmall)
                Text("Target", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(request.target, style = MaterialTheme.typography.bodySmall)
                Text(
                    "Arguments",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    request.argumentsJson.take(1_500),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                )
                Text(
                    if (request.canUndo) {
                        "This action can be undone or repeated if it was wrong."
                    } else {
                        "This action is marked irreversible. Review it carefully."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onDecision(true) }) { Text("Approve") }
        },
        dismissButton = {
            TextButton(onClick = { onDecision(false) }) { Text("Reject") }
        },
    )
}
