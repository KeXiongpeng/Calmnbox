package com.calm.inbox.features.inbox

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.calm.inbox.core.database.entity.NotificationEntity
import java.time.Duration
import java.time.Instant

@Composable
fun InboxScreen(
    viewModel: InboxViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            InboxFilter.entries.forEach { filter ->
                FilterChip(
                    selected = state.selectedFilter == filter,
                    onClick = { viewModel.select(filter) },
                    label = { Text(filter.label) }
                )
            }
        }

        if (state.important.isEmpty() && state.noiseGroups.isEmpty() && !state.isLoading) {
            EmptyInbox()
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(state.important, key = { "important-" + it.id }) { notification ->
                    ImportantNotificationCard(notification)
                }
                items(state.noiseGroups, key = { "noise-" + it.category }) { group ->
                    NoiseGroupCard(
                        group = group,
                        expanded = group.category in state.expandedNoiseCategories,
                        onToggle = { viewModel.toggleNoise(group.category) }
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyInbox() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "暂无通知。授权通知访问后，CalmInbox 会在本地整理收件箱。",
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
private fun ImportantNotificationCard(notification: NotificationEntity) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(notification.appName, style = MaterialTheme.typography.labelMedium)
            Text(notification.title, style = MaterialTheme.typography.titleMedium)
            if (notification.summary.isNotBlank()) {
                Text(notification.summary, style = MaterialTheme.typography.bodyMedium)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(categoryLabel(notification.category), style = MaterialTheme.typography.labelSmall)
                Text("重要度 " + notification.importance, style = MaterialTheme.typography.labelSmall)
                Text(relativeTime(notification.postedAt), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun NoiseGroupCard(
    group: NoiseGroup,
    expanded: Boolean,
    onToggle: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(categoryLabel(group.category), style = MaterialTheme.typography.titleMedium)
                    Text(
                        group.count.toString() + " 条 · 最新：" + group.latestTitle,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                TextButton(onClick = onToggle) {
                    Text(if (expanded) "折叠" else "展开")
                }
            }
            if (expanded) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                group.notifications.forEach { notification ->
                    Column(
                        modifier = Modifier.padding(vertical = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(notification.title, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            notification.appName + " · " + relativeTime(notification.postedAt),
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }
        }
    }
}

private fun categoryLabel(category: String): String = when (category) {
    "VERIFICATION" -> "验证码"
    "EXPRESS" -> "快递"
    "FINANCE" -> "财务"
    "SOCIAL" -> "社交"
    "WORK" -> "工作"
    "SHOPPING" -> "购物"
    "SYSTEM" -> "系统"
    "MARKETING" -> "营销"
    "OTHER" -> "其他"
    else -> "未分类"
}

private fun relativeTime(postedAt: Long): String {
    val duration = Duration.between(Instant.ofEpochMilli(postedAt), Instant.now())
    return when {
        duration.toMinutes() < 1 -> "刚刚"
        duration.toMinutes() < 60 -> duration.toMinutes().toString() + " 分钟前"
        duration.toHours() < 24 -> duration.toHours().toString() + " 小时前"
        else -> duration.toDays().toString() + " 天前"
    }
}
