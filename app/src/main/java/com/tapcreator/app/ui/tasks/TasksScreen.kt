package com.tapcreator.app.ui.tasks

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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

/** 任务状态页（M7）：展示会话下所有上游子任务的生成状态 */
@Composable
fun TasksScreen(
    onBack: () -> Unit,
    vm: TasksViewModel = hiltViewModel(),
) {
    val tasks by vm.tasks.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("←") }
            Text("任务状态", style = MaterialTheme.typography.titleMedium)
        }

        if (tasks.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("暂无任务", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(Dimens.PagePadding),
            ) {
                items(tasks, key = { it.id }) { task -> TaskRow(task) }
            }
        }
    }
}

@Composable
private fun TaskRow(task: TaskEntity) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(task.title, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = "${kindLabel(task.type)} · ${statusLabel(task.status)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = statusLabel(task.status),
            style = MaterialTheme.typography.labelMedium,
            color = statusColor(task.status),
        )
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
    TaskStatus.FAILED -> com.tapcreator.app.ui.theme.StatusErr
    TaskStatus.CANCELLED -> com.tapcreator.app.ui.theme.StatusWarn
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}