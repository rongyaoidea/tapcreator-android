package com.tapcreator.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.tapcreator.app.data.db.CardEntity
import com.tapcreator.app.data.model.MediaKind
import com.tapcreator.app.data.model.RunStatus
import com.tapcreator.app.ui.theme.Dimens
import java.io.File

/** 媒体缩略图最大边长：居中一块小方图，避免把卡片撑得过大 */
private val MediaThumbSize = 224.dp

/** 结果卡片 —— 既是消息流的产品，也是关系图的节点 */
@Composable
fun ResultCard(
    card: CardEntity,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
    textMaxLines: Int = 8,
    onToggleExpanded: (() -> Unit)? = null,
) {
    val fileName = card.mediaPath ?: card.previewPath
    val border = when {
        selected -> MaterialTheme.colorScheme.primary
        card.status == RunStatus.FAILED -> MaterialTheme.colorScheme.error
        card.status == RunStatus.CANCELLED -> com.tapcreator.app.ui.theme.StatusWarn
        else -> MaterialTheme.colorScheme.outlineVariant
    }
    // 出现动效：淡入 + 轻微放大，让生成结果「长出来」
    var appear by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { appear = true }

    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(Dimens.RadiusCard))
            .border(
                BorderStroke(if (selected) 2.dp else 1.dp, border),
                RoundedCornerShape(Dimens.RadiusCard),
            ),
        color = MaterialTheme.colorScheme.surface,
        onClick = onClick ?: {},
        shape = RoundedCornerShape(Dimens.RadiusCard),
    ) {
        AnimatedVisibility(
            visible = appear,
            enter = fadeIn(tween(320)) + scaleIn(initialScale = 0.96f, animationSpec = tween(320)),
        ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            if (card.kind == MediaKind.TEXT) {
                Column(modifier = Modifier.fillMaxWidth().padding(Dimens.Md)) {
                    Text(
                        text = card.content.ifBlank { card.title },
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = textMaxLines,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (onToggleExpanded != null) {
                        Text(
                            text = if (textMaxLines < Int.MAX_VALUE) "展开全文" else "收起",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .padding(top = 4.dp)
                                .align(Alignment.Start)
                                .clickable(onClick = onToggleExpanded),
                        )
                    }
                }
            } else if (fileName != null && File(fileName).exists()) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .width(MediaThumbSize)
                        .aspectRatio(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    if (card.kind == MediaKind.VIDEO) {
                        VideoThumb(file = File(fileName), modifier = Modifier.fillMaxSize())
                    } else if (card.kind == MediaKind.AUDIO) {
                        AudioThumb(file = File(fileName), modifier = Modifier.fillMaxWidth())
                    } else {
                        AsyncImage(
                            model = File(fileName),
                            contentDescription = card.title,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            } else {
                Text(
                    text = if (card.status == RunStatus.FAILED) "生成失败" else "渲染中…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .padding(Dimens.Md),
                )
            }
            Text(
                text = card.title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = Dimens.Md, vertical = Dimens.Sm),
            )
        }
        } // AnimatedVisibility
    }
}