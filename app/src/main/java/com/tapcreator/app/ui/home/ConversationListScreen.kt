package com.tapcreator.app.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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

/** 会话列表首页 —— huashu：暖纸底、单强调色、卡片近乎直角、克制间隔 */
@Composable
fun ConversationListScreen(
    vm: ConversationListViewModel,
    onOpenChat: (String) -> Unit,
    onNewChat: (String) -> Unit,
    onSettings: () -> Unit,
    onLibrary: () -> Unit,
    onProfile: () -> Unit,
) {
    val conversations by vm.conversations.collectAsState()
    val user by vm.user.collectAsState()
    val needsSetup by vm.needsSetup.collectAsState()
    // 进入动效：淡入 + 轻微上移，营造品牌入场氛围
    var revealed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { revealed = true }

    Scaffold(bottomBar = { HomeBottomBar(onSettings, onLibrary, onProfile) }) { pad ->
        AnimatedVisibility(
            visible = revealed,
            enter = fadeIn(tween(280)) + slideInVertically(tween(280)) { it / 12 },
            modifier = Modifier.fillMaxSize(),
        ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(pad)
                .padding(bottom = 24.dp),
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
                    user?.let {
                        Text(
                            text = it.username,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
                Button(onClick = { onNewChat("") }) {
                    Text("＋ 新建项目", style = MaterialTheme.typography.titleSmall)
                }
            }

            HorizontalDivider(modifier = Modifier.padding(horizontal = Dimens.PagePadding), color = MaterialTheme.colorScheme.outlineVariant)

            // 首次配置引导：尚无任何渠道配好 API Key 时提示去设置页
            if (needsSetup) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Dimens.PagePadding, vertical = 10.dp),
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
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "还没有项目\n点击右上角「新建项目」或下方按钮开始创作",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(Dimens.PagePadding),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(conversations, key = { it.id }) { conv ->
                        ConversationRow(conv = conv, onClick = { onOpenChat(conv.id) })
                    }
                }
            }

            Button(
                onClick = { onNewChat("") },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Dimens.PagePadding),
                contentPadding = PaddingValues(vertical = 12.dp),
            ) {
                Text("＋ 新建项目", style = MaterialTheme.typography.titleSmall)
            }
        }
        } // AnimatedVisibility
    }
}

@Composable
private fun ConversationRow(conv: ConversationEntity, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = Dimens.Sm),
    ) {
        Text(
            text = conv.title,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
        )
        Text(
            text = timeLabel(conv.updatedAt),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

@Composable
private fun HomeBottomBar(
    onSettings: () -> Unit,
    onLibrary: () -> Unit,
    onProfile: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.PagePadding, vertical = 12.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            NavEntry("素材库", onLibrary)
            NavEntry("设置", onSettings)
            NavEntry("我的", onProfile)
        }
    }
}

/** 首页底部的模式入口：加大点击热区与文字，避免误触与拥挤 */
@Composable
private fun NavEntry(label: String, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
    ) {
        Text(label, style = MaterialTheme.typography.titleMedium)
    }
}

private fun timeLabel(epoch: Long): String {
    val d = java.util.Date(epoch)
    val fmt = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
    return fmt.format(d)
}