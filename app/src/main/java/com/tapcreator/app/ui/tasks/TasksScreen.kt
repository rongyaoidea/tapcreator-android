package com.tapcreator.app.ui.tasks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.tapcreator.app.data.db.TaskEntity
import com.tapcreator.app.data.model.TaskStatus
import com.tapcreator.app.ui.theme.Dimens

/** 任务状态页：展示会话下所有上游子任务的生成状态，含模型/时间/进度 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TasksScreen(
    onBack: () -> Unit,
    vm: TasksViewModel = hiltViewModel(),
) {
    val tasks by vm.tasks.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("任务状态") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { pad ->
        if (tasks.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
                Text("暂无任务", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(pad),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = Dimens.PagePadding, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(tasks, key = { it.id }) { task ->
                    TaskRow(task)
                }
            }
        }
    }
}

@Composable
private fun TaskRow(task: TaskEntity) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(task.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                Text(
                    text = "${kindLabel(task.type)} · ${timeLabel(task.createdAt)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // 状态 Badge：用色块区分，比纯文字醒目
            Text(
                text = statusLabel(task.status),
                style = MaterialTheme.typography.labelMedium,
                color = statusColor(task.status),
                modifier = Modifier
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
        // 模型 id（若有）
        if (task.modelId.isNotBlank()) {
            Text(
                text = "模型：${task.modelId}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        // RUNNING 时显示进度条（indeterminate，子任务粒度无精确进度）
        if (task.status == TaskStatus.RUNNING) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
            )
        }
        // 失误原因
        task.error?.takeIf { it.isNotBlank() }?.let { err ->
            Text(
                text = err,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

private fun kindLabel(kind: com.tapcreator.app.data.model.MediaKind): String = when (kind) {
    com.tapcreator.app.data.model.MediaKind.TEXT -> "文本"
    com.tapcreator.app.data.model.MediaKind.IMAGE -> "图像"
    com.tapcreator.app.data.model.MediaKind.VIDEO -> "视频"
    com.tapcreator.app.data.model.MediaKind.AUDIO -> "音频"
}

private fun statusLabel(status: TaskStatus): String = when (status) {
    TaskStatus.READY -> "就绪"
    TaskStatus.RUNNING -> "进行中"
    TaskStatus.COMPLETED -> "已完成"
    TaskStatus.FAILED -> "失败"
    TaskStatus.CANCELLED -> "已取消"
}

@Composable
private fun statusColor(status: TaskStatus): androidx.compose.ui.graphics.Color = when (status) {
    TaskStatus.COMPLETED -> com.tapcreator.app.ui.theme.StatusOk
    TaskStatus.RUNNING -> MaterialTheme.colorScheme.primary
    TaskStatus.FAILED -> MaterialTheme.colorScheme.error
    TaskStatus.CANCELLED -> MaterialTheme.colorScheme.onSurfaceVariant
    TaskStatus.READY -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun timeLabel(epoch: Long): String {
    val d = java.util.Date(epoch)
    val fmt = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
    return fmt.format(d)
}
