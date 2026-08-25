package com.tapcreator.app.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tapcreator.app.data.db.ConversationEntity
import com.tapcreator.app.ui.theme.Dimens

/** 会话列表首页 —— 卡片化 + 时间分组 + 相对时间 + 置顶 + FAB */
@Composable
fun ConversationListScreen(
    vm: ConversationListViewModel,
    onOpenChat: (String) -> Unit,
    onNewChat: (String) -> Unit,
    onSettings: () -> Unit,
    onLibrary: () -> Unit,
) {
    val conversations by vm.conversations.collectAsState()
    val needsSetup by vm.needsSetup.collectAsState()
    var revealed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { revealed = true }

    AnimatedVisibility(
        visible = revealed,
        enter = fadeIn(tween(280)) + slideInVertically(tween(280)) { it / 12 },
        modifier = Modifier.fillMaxSize(),
    ) {
        Scaffold(
            floatingActionButton = {
                FloatingActionButton(
                    onClick = { onNewChat("") },
                    containerColor = MaterialTheme.colorScheme.primary,
                ) {
                    Icon(Icons.Filled.Add, contentDescription = "新建项目", tint = MaterialTheme.colorScheme.onPrimary)
                }
            },
        ) { pad ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(pad),
            ) {
                // 头部
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(Dimens.PagePadding),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "tapcreator",
                            style = MaterialTheme.typography.headlineLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(horizontal = Dimens.PagePadding), color = MaterialTheme.colorScheme.outlineVariant)

                if (needsSetup) {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Dimens.PagePadding, vertical = 10.dp),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "先配置 API Key 才能开始生成",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                            TextButton(onClick = onSettings) { Text("去设置") }
                        }
                    }
                }

                if (conversations.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "还没有项目\n点击右下角 + 新建项目开始创作",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                    }
                } else {
                    // 分组：今天/昨天/本周/更早
                    val groups = groupByRelativeTime(conversations)
                    LazyColumn(
                        contentPadding = PaddingValues(Dimens.PagePadding),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        groups.forEach { (label, items) ->
                            item(key = "header_$label") {
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                                )
                            }
                            items(items, key = { it.id }) { conv ->
                                ConversationCard(
                                    conv = conv,
                                    onClick = { onOpenChat(conv.id) },
                                    onDelete = { vm.deleteConversation(conv.id) },
                                    onRename = { title -> vm.renameConversation(conv.id, title) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 按相对时间分组：今天/昨天/本周/更早 */
private fun groupByRelativeTime(conversations: List<ConversationEntity>): List<Pair<String, List<ConversationEntity>>> {
    val now = java.util.Calendar.getInstance()
    val today = conversations.filter {
        val c = java.util.Calendar.getInstance().apply { timeInMillis = it.updatedAt }
        now.get(java.util.Calendar.YEAR) == c.get(java.util.Calendar.YEAR) &&
            now.get(java.util.Calendar.DAY_OF_YEAR) == c.get(java.util.Calendar.DAY_OF_YEAR)
    }
    val yesterday = conversations.filter {
        val c = java.util.Calendar.getInstance().apply { timeInMillis = it.updatedAt }
        now.get(java.util.Calendar.DAY_OF_YEAR) - c.get(java.util.Calendar.DAY_OF_YEAR) == 1 &&
            now.get(java.util.Calendar.YEAR) == c.get(java.util.Calendar.YEAR)
    }
    val todayIds = (today + yesterday).map { it.id }.toSet()
    val thisWeek = conversations.filter {
        it.id !in todayIds &&
            (now.timeInMillis - it.updatedAt) < 7L * 24 * 60 * 60 * 1000
    }
    val earlier = conversations.filter {
        it.id !in todayIds && it.id !in thisWeek.map { c -> c.id }.toSet()
    }
    val result = mutableListOf<Pair<String, List<ConversationEntity>>>()
    if (today.isNotEmpty()) result.add("今天" to today)
    if (yesterday.isNotEmpty()) result.add("昨天" to yesterday)
    if (thisWeek.isNotEmpty()) result.add("本周" to thisWeek)
    if (earlier.isNotEmpty()) result.add("更早" to earlier)
    return result
}

/** 相对时间描述：刚刚/2小时前/昨天 HH:mm/MM-dd/yyyy-MM-dd */
private fun relativeTime(epoch: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - epoch
    return when {
        diff < 60_000 -> "刚刚"
        diff < 3600_000 -> "${diff / 60_000}分钟前"
        diff < 7200_000 -> "${diff / 3600_000}小时前"
        else -> {
            val target = java.util.Calendar.getInstance().apply { timeInMillis = epoch }
            val nowCal = java.util.Calendar.getInstance()
            val timeFmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
            val isSameDay = nowCal.get(java.util.Calendar.YEAR) == target.get(java.util.Calendar.YEAR) &&
                nowCal.get(java.util.Calendar.DAY_OF_YEAR) == target.get(java.util.Calendar.DAY_OF_YEAR)
            val isYesterday = nowCal.get(java.util.Calendar.DAY_OF_YEAR) - target.get(java.util.Calendar.DAY_OF_YEAR) == 1 &&
                nowCal.get(java.util.Calendar.YEAR) == target.get(java.util.Calendar.YEAR)
            when {
                isSameDay -> "今天 ${timeFmt.format(target.time)}"
                isYesterday -> "昨天 ${timeFmt.format(target.time)}"
                nowCal.get(java.util.Calendar.YEAR) == target.get(java.util.Calendar.YEAR) ->
                    java.text.SimpleDateFormat("MM-dd", java.util.Locale.getDefault()).format(target.time)
                else -> java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(target.time)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationCard(
    conv: ConversationEntity,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onRename: (String) -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = { showMenu = true }),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Text(
                text = conv.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            Text(
                text = relativeTime(conv.updatedAt),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
    androidx.compose.material3.DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
        androidx.compose.material3.DropdownMenuItem(
            text = { Text("重命名") },
            onClick = { showMenu = false; showRenameDialog = true },
        )
        androidx.compose.material3.DropdownMenuItem(
            text = { Text("删除", color = MaterialTheme.colorScheme.error) },
            onClick = { showMenu = false; showDeleteConfirm = true },
        )
    }
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("删除项目") },
            text = { Text("确定删除「${conv.title}」吗？其中所有卡片和消息将一并删除，不可恢复。") },
            confirmButton = {
                TextButton(onClick = { onDelete(); showDeleteConfirm = false }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { showDeleteConfirm = false }) { Text("取消") } },
        )
    }
    if (showRenameDialog) {
        var name by remember { mutableStateOf(conv.title) }
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("重命名项目") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("项目名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (name.isNotBlank()) onRename(name)
                    showRenameDialog = false
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { showRenameDialog = false }) { Text("取消") } },
        )
    }
}
