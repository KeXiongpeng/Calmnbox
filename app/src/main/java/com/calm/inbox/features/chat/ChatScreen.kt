package com.calm.inbox.features.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.calm.inbox.core.database.entity.ChatMessageEntity

@Composable
fun ChatScreen(
    onCitationClick: (Long) -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: ChatViewModel = hiltViewModel()
) {
    val history by viewModel.history.collectAsStateWithLifecycle()
    val streamingAnswer by viewModel.streamingAnswer.collectAsStateWithLifecycle()
    val citations by viewModel.citations.collectAsStateWithLifecycle()
    val isStreaming by viewModel.isStreaming.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val notificationAccessGranted by viewModel.notificationAccessGranted
        .collectAsStateWithLifecycle()
    var question by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        if (!notificationAccessGranted) {
            PermissionLostBanner(onOpenSettings = onOpenSettings)
        }
        RecoveryBanner(
            state = uiState,
            isStreaming = isStreaming,
            onOpenSettings = onOpenSettings,
            onRetry = viewModel::retry
        )

        if (history.isEmpty() && streamingAnswer.isBlank()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "问我今天的通知，例如：我的验证码是多少？",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(history, key = { it.id }) { message ->
                    MessageBubble(message = message, onCitationClick = onCitationClick)
                }
                if (streamingAnswer.isNotBlank()) {
                    item(key = "streaming-answer") {
                        StreamingBubble(
                            answer = streamingAnswer,
                            citations = citations,
                            onCitationClick = onCitationClick
                        )
                    }
                }
            }
        }

        if (isStreaming) {
            CircularProgressIndicator(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(vertical = 8.dp)
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = question,
                onValueChange = { question = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("问我今天的通知") },
                singleLine = true
            )
            IconButton(
                onClick = {
                    viewModel.ask(question)
                    question = ""
                },
                enabled = !isStreaming && question.isNotBlank()
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.Send,
                    contentDescription = "发送"
                )
            }
        }
    }
}

@Composable
private fun PermissionLostBanner(onOpenSettings: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "通知访问权限已关闭",
                style = MaterialTheme.typography.titleSmall
            )
            Text(
                text = "重新授权后，CalmInbox 才能继续读取本地通知。",
                style = MaterialTheme.typography.bodySmall
            )
            Button(onClick = onOpenSettings) {
                Text("去设置")
            }
        }
    }
}

@Composable
private fun RecoveryBanner(
    state: ChatUiState,
    isStreaming: Boolean,
    onOpenSettings: () -> Unit,
    onRetry: () -> Unit
) {
    if (state.precondition == ChatPrecondition.Ready) return
    val message = state.userMessage ?: return

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (state.precondition == ChatPrecondition.NeedsModel) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.errorContainer
            },
            contentColor = if (state.precondition == ChatPrecondition.NeedsModel) {
                MaterialTheme.colorScheme.onSecondaryContainer
            } else {
                MaterialTheme.colorScheme.onErrorContainer
            }
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(message, style = MaterialTheme.typography.bodyMedium)
            Button(
                onClick = if (state.precondition == ChatPrecondition.NeedsModel) {
                    onOpenSettings
                } else {
                    onRetry
                },
                enabled = !isStreaming
            ) {
                Text(
                    if (state.precondition == ChatPrecondition.NeedsModel) {
                        "下载模型"
                    } else {
                        "重试"
                    }
                )
            }
        }
    }
}

@Composable
private fun MessageBubble(
    message: ChatMessageEntity,
    onCitationClick: (Long) -> Unit
) {
    val isUser = message.role == "user"
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Card(
            modifier = Modifier.widthIn(max = 304.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (isUser) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
                contentColor = if (isUser) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(message.content, style = MaterialTheme.typography.bodyMedium)
                CitationChips(
                    citationIds = message.citationIds,
                    onCitationClick = onCitationClick
                )
            }
        }
    }
}

@Composable
private fun StreamingBubble(
    answer: String,
    citations: List<Citation>,
    onCitationClick: (Long) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start
    ) {
        Card(
            modifier = Modifier.widthIn(max = 304.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
            )
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(answer, style = MaterialTheme.typography.bodyMedium)
                if (citations.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        citations.forEach { citation ->
                            FilterChip(
                                selected = false,
                                onClick = { onCitationClick(citation.notificationId) },
                                label = { Text(citation.title) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CitationChips(
    citationIds: String,
    onCitationClick: (Long) -> Unit
) {
    val ids = citationIds.split(',')
        .mapNotNull { it.toLongOrNull() }
    if (ids.isEmpty()) return

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ids.forEach { id ->
            FilterChip(
                selected = false,
                onClick = { onCitationClick(id) },
                label = { Text("通知 " + id) }
            )
        }
    }
}
