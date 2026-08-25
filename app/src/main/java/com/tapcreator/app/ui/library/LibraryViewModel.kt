package com.tapcreator.app.ui.library

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tapcreator.app.data.db.AppDatabase
import com.tapcreator.app.data.db.AssetEntity
import com.tapcreator.app.data.db.AssetFolderEntity
import com.tapcreator.app.data.model.MediaKind
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val db: AppDatabase,
    private val media: com.tapcreator.app.backend.media.MediaStore,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    val assets: StateFlow<List<AssetEntity>> =
        db.assetDao().observeAll()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 素材文件夹（普通素材夹 / 角色 / 产品） */
    val folders: StateFlow<List<AssetFolderEntity>> =
        db.assetFolderDao().observeAll()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    var lastError by androidx.compose.runtime.mutableStateOf<String?>(null)
        private set

    /** 新建文件夹。kind ∈ folder/role/product */
    fun createFolder(name: String, kind: String) {
        val n = name.trim()
        if (n.isEmpty()) return
        viewModelScope.launch {
            if (db.assetFolderDao().byName(n) != null) {
                lastError = "已存在同名文件夹：$n"
                return@launch
            }
            db.assetFolderDao().insert(
                AssetFolderEntity(
                    id = "fld-${java.util.UUID.randomUUID().toString().substring(0, 8)}",
                    name = n,
                    kind = kind,
                    createdAt = System.currentTimeMillis(),
                )
            )
        }
    }

    fun deleteFolder(folderId: String) {
        viewModelScope.launch {
            // 先解除其下资产归档，再删文件夹
            db.assetFolderDao().byId(folderId)?.let { }
            db.assetDao().byFolder(folderId).forEach { db.assetDao().setFolder(it.id, null) }
            db.assetFolderDao().deleteById(folderId)
        }
    }

    fun deleteAsset(asset: AssetEntity) {
        viewModelScope.launch {
            // 删除磁盘文件 + 数据库记录
            runCatching {
                asset.mediaPath?.let { java.io.File(it).takeIf { f -> f.exists() }?.delete() }
                asset.previewPath?.let { java.io.File(it).takeIf { f -> f.exists() }?.delete() }
            }
            db.assetDao().deleteById(asset.id)
        }
    }

    /** 从系统文件选择器导入本地图片/视频到指定文件夹（null=未归档） */
    fun importUri(uri: Uri, folderId: String?) {
        val scheme = uri.scheme
        if (scheme != "content" && scheme != "file") return
        val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull()?.lowercase() ?: ""
        val kind = when {
            mime.startsWith("video") -> MediaKind.VIDEO
            mime.startsWith("audio") -> MediaKind.AUDIO
            else -> MediaKind.IMAGE
        }
        viewModelScope.launch {
            try {
                media.importFromUri("", uri, kind, kind.name.lowercase(), folderId)
            } catch (e: Exception) {
                lastError = e.message ?: "导入失败"
            }
        }
    }

    /** 把已有素材移入/移出文件夹 */
    fun moveAsset(assetId: String, folderId: String?) {
        viewModelScope.launch { db.assetDao().setFolder(assetId, folderId) }
    }

    suspend fun assetsIn(folderId: String?): List<AssetEntity> =
        if (folderId == null) db.assetDao().byFolder(folderId.let { "" }).let { emptyList() }
        else db.assetDao().byFolder(folderId)

    /** 把单个素材导出到系统相册（图片→Pictures，视频→Movies），返回是否成功 */
    fun saveToGallery(asset: AssetEntity, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                val source = asset.mediaPath?.let(::File)?.takeIf { it.exists() } ?: return@withContext false
                runCatching { export(source, asset.kind) }.getOrDefault(false)
            }
            onResult(ok)
        }
    }

    private fun export(source: File, kind: MediaKind): Boolean {
        val mime = when (kind) {
            MediaKind.IMAGE -> "image/webp"
            MediaKind.VIDEO -> "video/mp4"
            MediaKind.AUDIO -> "audio/mpeg"
            else -> return false
        }
        val collection = when (kind) {
            MediaKind.IMAGE -> MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            MediaKind.VIDEO -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            MediaKind.AUDIO -> MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            else -> return false
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, source.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    when (kind) {
                        MediaKind.IMAGE -> "Pictures/Tapcreator"
                        MediaKind.VIDEO -> "Movies/Tapcreator"
                        MediaKind.AUDIO -> "Music/Tapcreator"
                        else -> return false
                    },
                )
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(collection, values) ?: return false
        return try {
            resolver.openOutputStream(uri)?.use { out ->
                source.inputStream().use { it.copyTo(out) }
            } ?: return false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
            true
        } catch (_: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            false
        }
    }
}