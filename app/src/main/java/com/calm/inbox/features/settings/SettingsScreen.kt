package com.calm.inbox.features.settings

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val blacklist by viewModel.blacklist.collectAsStateWithLifecycle()
    val noiseThreshold by viewModel.noiseThreshold.collectAsStateWithLifecycle()
    val permissionGranted by viewModel.notificationAccessGranted.collectAsStateWithLifecycle()
    val modelState by viewModel.modelState.collectAsStateWithLifecycle()
    val latencyStats by viewModel.firstTokenLatency.collectAsStateWithLifecycle()
    var newPackage by remember { mutableStateOf("") }
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        PermissionCard(
            granted = permissionGranted,
            onOpenSettings = {
                context.startActivity(
                    Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                )
            }
        )

        ModelManagerCard(
            state = modelState,
            onDownload = viewModel::downloadModel,
            onDelete = viewModel::deleteModel
        )

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("通知黑名单", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "黑名单应用的通知不会写入本地收件箱。CalmInbox 自身始终被过滤。",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newPackage,
                        onValueChange = { newPackage = it },
                        modifier = Modifier.weight(1f),
                        label = { Text("应用包名") },
                        singleLine = true
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            viewModel.addPackage(newPackage)
                            newPackage = ""
                        },
                        enabled = newPackage.isNotBlank()
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null)
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                blacklist.forEach { pkg ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AssistChip(
                            onClick = { viewModel.removePackage(pkg) },
                            label = { Text(pkg) },
                            enabled = pkg != SettingsRepository.SELF_PACKAGE,
                            trailingIcon = if (pkg == SettingsRepository.SELF_PACKAGE) {
                                null
                            } else {
                                { Icon(Icons.Filled.Close, contentDescription = "移除 $pkg") }
                            }
                        )
                    }
                }
            }
        }

        BenchmarkCard(latencyStats)

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("降噪阈值", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "重要度 ≤ 阈值的营销/低价值通知会折叠展示。",
                    style = MaterialTheme.typography.bodySmall
                )
                Slider(
                    value = noiseThreshold.toFloat(),
                    onValueChange = { viewModel.setNoiseThreshold(it.toInt()) },
                    valueRange = 1f..5f,
                    steps = 3
                )
                Text("当前阈值：$noiseThreshold")
            }
        }
    }
}

@Composable
private fun PermissionCard(
    granted: Boolean,
    onOpenSettings: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (granted) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.errorContainer
            }
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = if (granted) "通知访问已开启" else "需要通知访问权限",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = if (granted) {
                    "CalmInbox 可以在本机读取并整理通知。"
                } else {
                    "请在系统设置中允许 CalmInbox 读取通知。数据不会上传。"
                },
                style = MaterialTheme.typography.bodyMedium
            )
            if (!granted) {
                Spacer(modifier = Modifier.height(12.dp))
                Button(onClick = onOpenSettings) { Text("打开系统设置") }
            }
        }
    }
}

@Composable
private fun ModelManagerCard(
    state: ModelUiState,
    onDownload: () -> Unit,
    onDelete: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("\u672c\u5730\u6a21\u578b", style = MaterialTheme.typography.titleMedium)
            Text(
                text = if (state.isReady) {
                    "Qwen2.5-1.5B \u5df2\u4e0b\u8f7d\uff0c\u53ef\u5728\u672c\u673a\u5b8c\u6210\u5206\u7c7b\u3001\u7b80\u62a5\u548c\u95ee\u7b54\u3002"
                } else {
                    "\u4e0b\u8f7d Qwen2.5-1.5B Instruct int4 \u540e\uff0c\u624d\u80fd\u4f7f\u7528\u672c\u5730 AI \u80fd\u529b\u3002\u6a21\u578b\u7ea6 1GB\uff0c\u53ea\u5728\u4e3b\u52a8\u4e0b\u8f7d\u65f6\u8bbf\u95ee\u7f51\u7edc\u3002"
                },
                style = MaterialTheme.typography.bodySmall
            )
            if (state.isDownloading) {
                LinearProgressIndicator(
                    progress = { state.progress },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = "\u4e0b\u8f7d\u4e2d " + (state.progress * 100).toInt() + "%",
                    style = MaterialTheme.typography.labelSmall
                )
            }
            state.error?.let { error ->
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            if (state.isReady) {
                OutlinedButton(onClick = onDelete) {
                    Text("\u5220\u9664\u6a21\u578b")
                }
            } else {
                Button(
                    onClick = onDownload,
                    enabled = !state.isDownloading
                ) {
                    Text(if (state.isDownloading) "\u4e0b\u8f7d\u4e2d\u2026" else "\u4e0b\u8f7d\u6a21\u578b")
                }
            }
        }
    }
}

@Composable
private fun BenchmarkCard(stats: LatencyStats?) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("模型基准（首 token 延迟）", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            if (stats == null) {
                Text(
                    "暂无样本。模型加载并完成一次推理后，这里会显示最近 20 次首 token 延迟。",
                    style = MaterialTheme.typography.bodySmall
                )
            } else {
                Text(
                    "最近：" + stats.latestMs + " ms｜平均：" + stats.averageMs + " ms｜样本：" + stats.sampleCount,
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    "目标：< 3000 ms",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}
