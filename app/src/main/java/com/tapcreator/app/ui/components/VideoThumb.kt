package com.tapcreator.app.ui.components

import android.net.Uri
import android.view.SurfaceView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.Player
import java.io.File

/**
 * 本地视频缩略播放（无音频系），用于聊天卡片 / 素材库网格的视频预览。
 * 自绘 SurfaceView 绑定 ExoPlayer，避免引入 exoplayer-ui。
 */
@Composable
fun VideoThumb(
    file: File,
    modifier: Modifier = Modifier,
    autoPlay: Boolean = true,
    loop: Boolean = true,
) {
    val context = LocalContext.current
    val player = remember(file.absolutePath) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
            repeatMode = if (loop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
            volume = 0f
            playWhenReady = autoPlay
            prepare()
        }
    }
    DisposableEffect(player) {
        onDispose { player.release() }
    }
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            SurfaceView(ctx).apply { player.setVideoSurfaceView(this) }
        },
    )
}