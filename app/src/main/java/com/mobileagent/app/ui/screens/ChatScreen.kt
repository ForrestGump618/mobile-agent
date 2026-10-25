package com.mobileagent.app.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mobileagent.app.R
import com.mobileagent.app.ui.EmptyState
import com.mobileagent.app.ui.InfoCard
import com.mobileagent.app.ui.MarkdownText
import com.mobileagent.app.ui.vm.ChatViewModel
import com.mobileagent.core.model.ChatMessage
import com.mobileagent.core.model.ChatMode
import com.mobileagent.core.model.TaskRecord
import com.mobileagent.core.model.TaskStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    onOpenTasks: () -> Unit = {},
    onOpenModels: () -> Unit = {},
) {
    val viewModel: ChatViewModel = viewModel()
    val conversations by viewModel.conversations.collectAsStateWithLifecycle()
    val conversation by viewModel.conversation.collectAsStateWithLifecycle()
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val draft by viewModel.draft.collectAsStateWithLifecycle()
    val running by viewModel.running.collectAsStateWithLifecycle()
    val input by viewModel.input.collectAsStateWithLifecycle()
    val mode by viewModel.mode.collectAsStateWithLifecycle()
    val providers by viewModel.providers.collectAsStateWithLifecycle()
    val providerLabel by viewModel.activeProviderLabel.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val toast by viewModel.toast.collectAsStateWithLifecycle()
    val agentTaskId by viewModel.agentTaskId.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val listState = rememberLazyListState()
    var showHistory by remember { mutableStateOf(false) }

    LaunchedEffect(messages.size, draft.length) {
        val target = messages.size + if (draft.isNotBlank()) 1 else 0
        if (target > 0) listState.animateScrollToItem((target - 1).coerceAtLeast(0))
    }
    LaunchedEffect(toast) {
        toast?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.clearToast()
        }
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterChip(
                selected = mode == ChatMode.CHAT,
                onClick = { viewModel.setMode(ChatMode.CHAT) },
                label = { Text(stringResource(R.string.chat_mode_chat)) },
            )
            Spacer(Modifier.width(6.dp))
            FilterChip(
                selected = mode == ChatMode.AGENT,
                onClick = { viewModel.setMode(ChatMode.AGENT) },
                label = { Text(stringResource(R.string.chat_mode_agent)) },
            )
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { showHistory = true }) {
                Icon(Icons.Filled.History, contentDescription = stringResource(R.string.chat_history))
            }
            IconButton(onClick = { viewModel.newChat() }) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.chat_new))
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onOpenModels() }
                .padding(horizontal = 16.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(7.dp)
                    .background(
                        if (providers.isEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        CircleShape,
                    ),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                providerLabel ?: stringResource(R.string.chat_no_model),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            Text(
                stringResource(R.string.chat_switch_model),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (mode == ChatMode.AGENT) {
            Text(
                stringResource(R.string.chat_agent_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            )
        }

        if (agentTaskId != null) {
            InfoCard(
                title = stringResource(R.string.chat_agent_created),
                subtitle = agentTaskId?.take(8),
                onClick = onOpenTasks,
            ) {
                TextButton(onClick = onOpenTasks) { Text(stringResource(R.string.chat_open_tasks)) }
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (messages.isEmpty() && draft.isBlank()) {
                item {
                    Column(
                        Modifier.fillMaxWidth().padding(vertical = 20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            stringResource(R.string.chat_empty_title),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            stringResource(R.string.chat_empty_body),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(14.dp))
                        listOf(
                            stringResource(R.string.chat_suggestion_1),
                            stringResource(R.string.chat_suggestion_2),
                            stringResource(R.string.chat_suggestion_3),
                        ).forEach { suggestion ->
                            TextButton(onClick = {
                                if (providers.isEmpty()) {
                                    onOpenModels()
                                } else {
                                    viewModel.setInput(suggestion)
                                    viewModel.send()
                                }
                            }) { Text(suggestion) }
                        }
                    }
                }
            }

            items(messages) { message ->
                ChatBubble(
                    message = message,
                    onCopy = { content -> copy(context, content) },
                    onRegenerate = if (!running && message.role == ChatMessage.ROLE_ASSISTANT &&
                        message.id == messages.lastOrNull { it.role == ChatMessage.ROLE_ASSISTANT }?.id
                    ) {
                        { viewModel.regenerate() }
                    } else {
                        null
                    },
                )
            }

            if (draft.isNotBlank()) {
                item {
                    ChatBubble(
                        message = ChatMessage(
                            id = "draft",
                            taskId = conversation?.id.orEmpty(),
                            role = ChatMessage.ROLE_ASSISTANT,
                            content = draft,
                            createdAt = System.currentTimeMillis(),
                        ),
                        streaming = true,
                        onCopy = { content -> copy(context, content) },
                        onRegenerate = null,
                    )
                }
            }
        }

        val failure = error ?: conversation?.takeIf { it.status == TaskStatus.FAILED }?.lastError
        if (failure != null) {
            Surface(color = MaterialTheme.colorScheme.errorContainer) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Text(
                        stringResource(R.string.chat_error_title),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(failure.take(500), style = MaterialTheme.typography.bodySmall)
                    Row {
                        if (providers.isNotEmpty()) {
                            TextButton(onClick = {
                                viewModel.clearError()
                                viewModel.regenerate()
                            }) { Text(stringResource(R.string.chat_retry)) }
                        }
                        TextButton(onClick = onOpenModels) { Text(stringResource(R.string.chat_open_models)) }
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { viewModel.clearError() }) { Text(stringResource(R.string.action_close)) }
                    }
                }
            }
        }

        if (providers.isEmpty()) {
            Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.chat_no_model),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onOpenModels) { Text(stringResource(R.string.models_add)) }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = viewModel::setInput,
                modifier = Modifier.weight(1f),
                placeholder = { Text(stringResource(R.string.chat_input_hint)) },
                maxLines = 5,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { viewModel.send() }),
            )
            Spacer(Modifier.width(8.dp))
            if (running) {
                FilledIconButton(onClick = { viewModel.stop() }) {
                    Icon(Icons.Filled.Stop, contentDescription = stringResource(R.string.chat_stop))
                }
            } else {
                FilledIconButton(
                    onClick = { viewModel.send() },
                    enabled = input.isNotBlank() && providers.isNotEmpty(),
                ) {
                    Icon(Icons.Filled.ArrowUpward, contentDescription = "Send")
                }
            }
        }
    }

    if (showHistory) {
        ModalBottomSheet(onDismissRequest = { showHistory = false }) {
            Text(
                stringResource(R.string.chat_history),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                items(conversations) { item ->
                    ConversationRow(
                        item = item,
                        onClick = {
                            viewModel.open(item.id)
                            showHistory = false
                        },
                        onDelete = { viewModel.deleteConversation(item.id) },
                    )
                }
                if (conversations.isEmpty()) {
                    item { EmptyState(stringResource(R.string.chat_untitled)) }
                }
            }
        }
    }
}

@Composable
private fun ConversationRow(item: TaskRecord, onClick: () -> Unit, onDelete: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(item.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
            Text(
                SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(item.updatedAt)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.chat_delete))
        }
    }
    HorizontalDivider()
}

@Composable
private fun ChatBubble(
    message: ChatMessage,
    streaming: Boolean = false,
    onCopy: (String) -> Unit,
    onRegenerate: (() -> Unit)?,
) {
    val isUser = message.role == ChatMessage.ROLE_USER
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
    ) {
        Surface(
            color = if (isUser) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth(if (isUser) 0.86f else 1f),
        ) {
            Column(Modifier.padding(12.dp)) {
                MarkdownText(message.content)
                if (streaming) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                        CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.chat_streaming), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
        if (!isUser && !streaming) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { onCopy(message.content) }) {
                    Icon(
                        Icons.Filled.ContentCopy,
                        contentDescription = stringResource(R.string.action_copy),
                        modifier = Modifier.size(16.dp),
                    )
                }
                onRegenerate?.let {
                    IconButton(onClick = it) {
                        Icon(
                            Icons.Filled.Refresh,
                            contentDescription = stringResource(R.string.chat_regenerate),
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
        }
    }
}

private fun copy(context: Context, text: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("message", text))
    Toast.makeText(context, context.getString(R.string.chat_copied), Toast.LENGTH_SHORT).show()
}
