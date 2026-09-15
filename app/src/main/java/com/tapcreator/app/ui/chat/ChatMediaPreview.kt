package com.tapcreator.app.ui.chat

import android.graphics.BitmapFactory
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil.compose.AsyncImage
import com.tapcreator.app.data.db.CardEntity
import com.tapcreator.app.data.model.MediaKind
import com.tapcreator.app.ui.theme.CardButton
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 成品卡全屏预览：图片放大查看 / 视频播放，并提供下载到相册。
 * 从 ChatScreen 抽出，避免 God File。图片用 Coil 全屏铺满；视频用自建 SurfaceView+Media3 播放（带音）。
 */
@Composable
internal fun MediaPreview(
    card: CardEntity,
    onDismiss: () -> Unit,
    onDownload: () -> Unit,
) {
    val file = card.mediaPath?.let { File(it) }?.takeIf { it.exists() }
        ?: card.previewPath?.let { File(it) }?.takeIf { it.exists() }
    val context = LocalContext.current
    // 提示词区可折叠：增强卡默认展开全文直接看到优化后的提示词，普通卡默认收起
    var promptExpanded by remember { mutableStateOf(card.promptEnhanced) }
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = Color.Black.copy(alpha = 0.95f),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
            ) {
                // 顶部：标题 + 增强标志 + 关闭
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = card.title.ifBlank { card.kind.name },
                        style = MaterialTheme.typography.titleSmall,
                        color = Color.White,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (card.promptEnhanced) {
                        Text(
                            text = "增强提示词",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.padding(end = 8.dp),
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "关闭", tint = Color.White)
                    }
                }
                // 预览区：图片按原图宽高比自适应大小（框刚好放下图片，不再占满整屏）；
                // 视频保持 16:9 播放器。
                BoxWithConstraints(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    when {
                        file != null && card.kind == MediaKind.VIDEO -> VideoPlayer(file = file)
                        file != null && card.kind == MediaKind.IMAGE -> {
                            // 只解码图片边界拿宽高（不加载整图），IO 下沉到后台线程避免阻塞主线程
                            val bounds by produceState<Pair<Int, Int>?>(initialValue = null, file) {
                                value = withContext(Dispatchers.IO) {
                                    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                                    runCatching { BitmapFactory.decodeFile(file.absolutePath, opts) }
                                    if (opts.outWidth > 0 && opts.outHeight > 0) opts.outWidth to opts.outHeight else null
                                }
                            }
                            val ratio = bounds?.let { it.first.toFloat() / it.second.toFloat() } ?: 1f
                            // 宽度不超过可用宽、高度不超过 480dp，同时保持图片宽高比，让框刚好贴合图片
                            val previewW = min(maxWidth, 480.dp * ratio)
                            val previewH = if (ratio > 0f) previewW / ratio else 480.dp
                            Box(
                                modifier = Modifier.size(previewW, previewH),
                                contentAlignment = Alignment.Center,
                            ) {
                                AsyncImage(
                                    model = file,
                                    contentDescription = card.title,
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Fit,
                                )
                            }
                        }
                        file == null -> Text("文件不存在", color = Color.White, modifier = Modifier.padding(16.dp))
                    }
                }
                // 提交提示词区：文案区分——经增强的卡展示「优化后的提示词」，普通卡展示「提交提示词」
                if (card.content.isNotBlank() && card.kind != MediaKind.TEXT) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                        shape = RoundedCornerShape(8.dp),
                        color = Color.White.copy(alpha = 0.08f),
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = if (card.promptEnhanced) "优化后的提示词" else "提交提示词",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = Color.White.copy(alpha = 0.7f),
                                    modifier = Modifier.weight(1f),
                                )
                                if (card.promptEnhanced) {
                                    Text(
                                        text = "经 LLM 增强",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.tertiary,
                                        modifier = Modifier.padding(end = 8.dp),
                                    )
                                }
                                CardButton(if (promptExpanded) "收起" else "展开", onClick = { promptExpanded = !promptExpanded }, tint = MaterialTheme.colorScheme.tertiaryContainer, contentColor = MaterialTheme.colorScheme.onTertiaryContainer)
                                // 复制提示词到剪贴板，便于再次使用
                                CardButton(
                                    "复制",
                                    onClick = {
                                        val clip = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                        clip.setPrimaryClip(android.content.ClipData.newPlainText("提交提示词", card.content))
                                        android.widget.Toast.makeText(context, "提示词已复制", android.widget.Toast.LENGTH_SHORT).show()
                                    },
                                    tint = MaterialTheme.colorScheme.tertiaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                                )
                            }
                            // 收起时只显一行预览，展开显全文
                            Text(
                                text = card.content,
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White,
                                maxLines = if (promptExpanded) Int.MAX_VALUE else 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .padding(top = 4.dp)
                                    .then(if (promptExpanded) Modifier else Modifier.fillMaxWidth()),
                            )
                        }
                    }
                }
                // 底部：下载按钮
                if (file != null) {
                    Button(
                        onClick = onDownload,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp),
                    ) {
                        Text("下载到相册")
                    }
                }
            }
        }
    }
}

/**
 * 全屏视频播放（带音）。自建 SurfaceView 绑定 Media3 ExoPlayer，避免引入 media3-ui。
 * 退出时释放 player，后台时暂停，避免后台持有解码器资源。
 */
@Composable
internal fun VideoPlayer(file: File) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val player = remember(file.absolutePath) {
        androidx.media3.exoplayer.ExoPlayer.Builder(context).build().apply {
            setMediaItem(androidx.media3.common.MediaItem.fromUri(android.net.Uri.fromFile(file)))
            playWhenReady = true
            prepare()
        }
    }
    DisposableEffect(player, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> player.pause()
                Lifecycle.Event.ON_RESUME -> player.play()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            player.release()
        }
    }
    AndroidView(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f),
        factory = { ctx ->
            android.view.SurfaceView(ctx).apply { player.setVideoSurfaceView(this) }
        },
    )
}
