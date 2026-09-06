package com.tapcreator.app.backend.media

import android.content.Context
import android.graphics.BitmapFactory
import com.tapcreator.app.data.db.AppDatabase
import com.tapcreator.app.data.db.AssetEntity
import com.tapcreator.app.data.model.UpstreamResult
import com.tapcreator.app.data.model.TapcreatorException
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.ResponseBody

/**
 * 媒体登记与本地存储 —— 对齐原作 Media/object-storage：
 * 新媒体一律写 App 私有 media/ 目录并存稳定 storageKey；业务只存 storageKey 不存临时 URL。
 */
@Singleton
class MediaStore @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context,
    private val db: AppDatabase,
    private val sharedClient: okhttp3.OkHttpClient,
) {
    private val root: File get() = File(context.filesDir, "media").apply { mkdirs() }

    fun mediaRoot(): File = root

    fun fileFor(storageKey: String): File = File(root, storageKey)

    fun internalPathFor(storageKey: String): String? = fileFor(storageKey).takeIf { it.exists() }?.absolutePath

    /**
     * 从系统文件选择器选中的本地 URI 导入为素材并落库（上传本地图片/视频作为项目输入）。
     * 复制到 App 私有 media/ 目录并登记 AssetEntity，成功后即可作为参考素材被选中使用。
     */
    suspend fun importFromUri(
        conversationId: String,
        uri: android.net.Uri,
        kind: com.tapcreator.app.data.model.MediaKind,
        title: String,
        folderId: String? = null,
    ): AssetEntity {
        val ext = when (kind) {
            com.tapcreator.app.data.model.MediaKind.IMAGE -> "webp"
            com.tapcreator.app.data.model.MediaKind.VIDEO -> "mp4"
            com.tapcreator.app.data.model.MediaKind.AUDIO -> "mp3"
            com.tapcreator.app.data.model.MediaKind.TEXT -> "txt"
        }
        val storageKey = "${System.currentTimeMillis()}-${UUID.randomUUID().toString().substring(0, 8)}.$ext"
        val target = fileFor(storageKey)
        target.parentFile?.mkdirs()
        val copied = context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
        if (copied == null || !target.exists()) throw TapcreatorException("无法读取所选文件，请重试", "IMPORT_URI")

        // 图片本地文件往往是 jpg/png，统一提取真实后缀便于预览；视频保持原文件
        val realExt = when (kind) {
            com.tapcreator.app.data.model.MediaKind.IMAGE -> {
                val e = android.webkit.MimeTypeMap.getSingleton()
                    .getExtensionFromMimeType(context.contentResolver.getType(uri)).orEmpty()
                if (e in setOf("jpg", "jpeg", "png", "webp")) e else "webp"
            }
            else -> null
        }
        val finalTarget = if (realExt != null) {
            val renamed = fileFor("${storageKey.substringBeforeLast('.')}.$realExt")
            target.copyTo(renamed, overwrite = true).also { renamed.exists() }.let { target.delete(); renamed }
        } else target

        val previewKey = if (kind == com.tapcreator.app.data.model.MediaKind.IMAGE) {
            generatePreview(finalTarget, storageKey.substringBeforeLast('.') + ".webp")
        } else null
        val mime = when (kind) {
            com.tapcreator.app.data.model.MediaKind.IMAGE -> "image/*"
            com.tapcreator.app.data.model.MediaKind.VIDEO -> "video/mp4"
            com.tapcreator.app.data.model.MediaKind.AUDIO -> "audio/mpeg"
            else -> "text/plain"
        }
        val asset = AssetEntity(
            id = UUID.randomUUID().toString(),
            conversationId = conversationId,
            runId = null,
            kind = kind,
            storageKey = finalTarget.name,
            mediaPath = finalTarget.absolutePath,
            previewPath = previewKey?.let { fileFor(it).absolutePath },
            mime = mime,
            title = title,
            createdAt = System.currentTimeMillis(),
            folderId = folderId,
        )
        db.assetDao().insert(asset)
        return asset
    }

    /** 登记一个已存在的本地媒体文件（视频拼接产物等）为素材并落库 */
    suspend fun persistFromLocal(
        conversationId: String,
        runId: String,
        file: File,
        title: String,
        kind: com.tapcreator.app.data.model.MediaKind,
    ): AssetEntity {
        val storageKey = "arch-${System.currentTimeMillis()}-${UUID.randomUUID().toString().substring(0, 8)}.mp4"
        val target = fileFor(storageKey)
        target.parentFile?.mkdirs()
        file.copyTo(target, overwrite = true)

        val asset = AssetEntity(
            id = UUID.randomUUID().toString(),
            conversationId = conversationId,
            runId = runId,
            kind = kind,
            storageKey = storageKey,
            mediaPath = target.absolutePath,
            previewPath = null,
            mime = "video/mp4",
            title = title,
            createdAt = System.currentTimeMillis(),
        )
        db.assetDao().insert(asset)
        return asset
    }

    /** 由上游媒体 URL 下载并登记为素材 */
    suspend fun persistFrom(
        conversationId: String,
        runId: String,
        result: UpstreamResult,
        title: String,
        kind: com.tapcreator.app.data.model.MediaKind,
    ): AssetEntity {
        val storageKey = generateKey(result)
        val target = fileFor(storageKey)
        target.parentFile?.mkdirs()

        if (result.mediaBytes != null) {
            target.writeBytes(result.mediaBytes)
        } else if (result.mediaUrl != null) {
            safeDownload(result.mediaUrl, target)
        }

        val previewKey = if (kind == com.tapcreator.app.data.model.MediaKind.IMAGE && target.exists()) {
            generatePreview(target, storageKey)
        } else null

        val asset = AssetEntity(
            id = UUID.randomUUID().toString(),
            conversationId = conversationId,
            runId = runId,
            kind = kind,
            storageKey = storageKey,
            mediaPath = target.absolutePath,
            previewPath = previewKey?.let { fileFor(it).absolutePath },
            mime = result.mime,
            title = title,
            createdAt = System.currentTimeMillis(),
        )
        db.assetDao().insert(asset)
        return asset
    }

    private fun generateKey(result: UpstreamResult): String {
        val ext = when (result.kind) {
            com.tapcreator.app.data.model.MediaKind.IMAGE -> "webp"
            com.tapcreator.app.data.model.MediaKind.VIDEO -> "mp4"
            com.tapcreator.app.data.model.MediaKind.AUDIO -> "mp3"
            com.tapcreator.app.data.model.MediaKind.TEXT -> "txt"
        }
        return "${System.currentTimeMillis()}-${UUID.randomUUID().toString().substring(0, 8)}.$ext"
    }

    /** 图片统一转 WebP 压缩，沉淀占空间更小 —— WebP 预览已公开。
     *  显式 IO 调度：解码/缩放/压缩是重 IO+CPU，防御性隔离，避免调用方上下文变化时阻塞。
     *  两段式解码防 OOM：先 inJustDecodeBounds 读尺寸，再按采样率解码，避免 20MP+ 图整幅落内存。 */
    private suspend fun generatePreview(source: File, baseKey: String): String =
        withContext(Dispatchers.IO) {
            runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(source.absolutePath, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext baseKey

                val maxDim = 1024
                val opts = BitmapFactory.Options().apply {
                    // 采样到不超过 2048 再缩放：像素总量限制在 ~4M，远离内存峰值
                    inJustDecodeBounds = false
                    inSampleSize = computeSampleSize(bounds.outWidth, bounds.outHeight, 2 * maxDim)
                }
                val bmp = BitmapFactory.decodeFile(source.absolutePath, opts) ?: return@withContext baseKey
                val scaled = if (bmp.width > maxDim) {
                    val h = (bmp.height * maxDim.toFloat() / bmp.width).toInt()
                    android.graphics.Bitmap.createScaledBitmap(bmp, maxDim, h, true)
                } else bmp
                val key = "preview/${System.currentTimeMillis()}-${UUID.randomUUID().toString().substring(0, 6)}.webp"
                val out = fileFor(key)
                out.parentFile?.mkdirs()
                java.io.FileOutputStream(out).use {
                    scaled.compress(android.graphics.Bitmap.CompressFormat.WEBP, 82, it)
                }
                if (scaled !== bmp) scaled.recycle()
                bmp.recycle()
                key
            }.getOrDefault(baseKey)
        }

    /** 计算 2 的幂采样率，使解码后最大边长 ≤ maxDim（防 4 倍缓冲 OOM） */
    private fun computeSampleSize(width: Int, height: Int, maxDim: Int): Int {
        var sample = 1
        while (width / (sample * 2) >= maxDim || height / (sample * 2) >= maxDim) sample *= 2
        return sample
    }

    private fun safeDownload(url: String, target: File) {
        // 复用 Hilt 全局单例 client（读超时 300s，满足大体积视频下载），
        // 不再各处自建 OkHttpClient，统一连接池与超时行为。
        sharedClient.newCall(okhttp3.Request.Builder().url(url).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw TapcreatorException("媒体下载失败: HTTP ${resp.code}", "UPSTREAM_HTTP")
            resp.body?.byteStream()?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
        }
    }
}