package com.tapcreator.app.ui.library

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.tapcreator.app.data.db.AssetEntity
import com.tapcreator.app.data.db.AssetFolderEntity
import com.tapcreator.app.ui.theme.Dimens
import java.io.File

/** 特殊选中态：全部 */
private val SENTINEL_ALL: String? = null
/** 特殊选中态：未归档 */
private const val SENTINEL_UNASSIGNED = "__unassigned__"

/** 素材库 —— 素材/角色的多视角资产，支持上传、文件夹分类、角色/产品身份绑定（M6）。
 *  底部导航固定 tab 页：无返回按钮，由全局底部栏切换。 */
@Composable
fun LibraryScreen(
    vm: LibraryViewModel = hiltViewModel(),
) {
    val assets by vm.assets.collectAsState()
    val folders by vm.folders.collectAsState()
    val context = LocalContext.current

    // 当前浏览的文件夹：null=全部, SENTINEL_UNASSIGNED=未归档, 其余=文件夹id
    var selected by remember { mutableStateOf<String?>(SENTINEL_ALL) }

    val shownAssets: List<AssetEntity> = when (selected) {
        SENTINEL_ALL -> assets
        SENTINEL_UNASSIGNED -> assets.filter { it.folderId == null }
        else -> assets.filter { it.folderId == selected }
    }

    // 上传目标文件夹：真实文件夹 id（全部/未归档时上传到未归档）
    val uploadFolderId = folders.firstOrNull { it.id == selected }?.id

    // 新建文件夹弹窗
    var showNewFolder by remember { mutableStateOf(false) }
    // 移动弹窗
    var movingAsset by remember { mutableStateOf<AssetEntity?>(null) }
    // 删除素材确认
    var deletingAsset by remember { mutableStateOf<AssetEntity?>(null) }
    // 删除文件夹确认
    var deletingFolder by remember { mutableStateOf<AssetFolderEntity?>(null) }

    // 保存到相册逻辑（保留）
    val legacyWriteNeeded = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
    var pendingSave by remember { mutableStateOf<AssetEntity?>(null) }
    val legacyWriteLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) pendingSave?.let { saveToGallery(it, vm, context) } else Toast.makeText(context, "未授予存储权限", Toast.LENGTH_SHORT).show()
        pendingSave = null
    }
    fun onSave(asset: AssetEntity) {
        if (legacyWriteNeeded &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            pendingSave = asset
            legacyWriteLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            saveToGallery(asset, vm, context)
        }
    }

    // 本地文件选择器：图片/视频 → 导入到当前文件夹
    val pickLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) vm.importUri(uri, uploadFolderId)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("素材库", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = { pickLauncher.launch("image/*,video/*") }) { Text("上传") }
                TextButton(onClick = { showNewFolder = true }) { Text("新建夹") }
            }

            // 文件夹切换条
            FolderChips(
                folders = folders,
                counts = assets.groupingBy { it.folderId }.eachCount(),
                total = assets.size,
                selected = selected,
                onSelect = { selected = it },
                onDeleteRequest = { deletingFolder = it },
            )

            if (shownAssets.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("没有素材，点右上角「上传」导入本地文件", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    contentPadding = PaddingValues(Dimens.PagePadding),
                    horizontalArrangement = Arrangement.spacedBy(Dimens.Sm),
                    verticalArrangement = Arrangement.spacedBy(Dimens.Sm),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(shownAssets, key = { it.id }) { asset ->
                        AssetCell(
                            asset = asset,
                            onShare = {
                                val file = asset.mediaPath ?: return@AssetCell
                                if (File(file).exists()) shareFile(context, File(file), asset.mime)
                            },
                            onSave = { onSave(asset) },
                            onMove = { movingAsset = asset },
                            onDelete = { deletingAsset = asset },
                        )
                    }
                }
            }
        }
    }

    if (showNewFolder) {
        NewFolderDialog(
            onConfirm = { name, kind -> vm.createFolder(name, kind) },
            onDismiss = { showNewFolder = false },
        )
    }

    movingAsset?.let { asset ->
        MoveAssetDialog(
            folders = folders,
            onPick = { folderId ->
                vm.moveAsset(asset.id, folderId)
                movingAsset = null
            },
            onDismiss = { movingAsset = null },
        )
    }

    deletingAsset?.let { asset ->
        AlertDialog(
            onDismissRequest = { deletingAsset = null },
            title = { Text("删除素材") },
            text = { Text("确定删除「${asset.title}」吗？磁盘文件和记录将一并删除，不可恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteAsset(asset)
                    deletingAsset = null
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deletingAsset = null }) { Text("取消") } },
        )
    }

    deletingFolder?.let { folder ->
        AlertDialog(
            onDismissRequest = { deletingFolder = null },
            title = { Text("删除文件夹") },
            text = { Text("删除「${folder.name}」？其中素材会移回未归档，不会被删除。") },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteFolder(folder.id)
                    if (selected == folder.id) selected = SENTINEL_ALL
                    deletingFolder = null
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { deletingFolder = null }) { Text("取消") }
            },
        )
    }
}

/** 文件夹切换条：全部 / 未归档 / 各文件夹（长按角色·产品可删除） */
@Composable
private fun FolderChips(
    folders: List<AssetFolderEntity>,
    counts: Map<String?, Int>,
    total: Int,
    selected: String?,
    onSelect: (String?) -> Unit,
    onDeleteRequest: (AssetFolderEntity) -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = Dimens.PagePadding, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(Dimens.Sm),
    ) {
        item {
            Chip(label = "全部 $total", selected = selected == SENTINEL_ALL) { onSelect(SENTINEL_ALL) }
        }
        item {
            Chip(
                label = "未归档 ${counts[null] ?: 0}",
                selected = selected == SENTINEL_UNASSIGNED,
            ) { onSelect(SENTINEL_UNASSIGNED) }
        }
        items(folders, key = { it.id }) { folder ->
            Chip(
                label = "${folderBadge(folder.kind)}${folder.name} ${counts[folder.id] ?: 0}",
                selected = selected == folder.id,
                onLongClick = { onDeleteRequest(folder) },
            ) { onSelect(folder.id) }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Chip(label: String, selected: Boolean, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick),
        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
        colors = FilterChipDefaults.filterChipColors(),
    )
}

/** 新建文件夹：名字 + 类型（素材/角色/产品）。角色·产品用于绑定多视角素材固定身份。 */
@Composable
private fun NewFolderDialog(
    onConfirm: (String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf("role") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建文件夹") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                SingleChoiceSegmentedButtonRow(modifier = Modifier.padding(top = Dimens.Md)) {
                    listOf("role" to "角色", "product" to "产品", "folder" to "素材").forEach { (k, label) ->
                        SegmentedButton(
                            selected = kind == k,
                            onClick = { kind = k },
                            shape = SegmentedButtonDefaults.itemShape(index = when (k) {
                                "role" -> 0; "product" -> 1; else -> 2
                            }, count = 3),
                        ) { Text("$label", style = MaterialTheme.typography.labelSmall) }
                    }
                }
                Text(
                    "角色/产品文件夹用于固定身份：把同一人物（或产品）的多视角图片/视频放进来，Agent 会引用它们保持一致性。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Dimens.Sm),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (name.isNotBlank()) onConfirm(name, kind)
                    if (name.isNotBlank()) onDismiss()
                }
            ) { Text("创建") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 把素材移入某个文件夹（或未归档） */
@Composable
private fun MoveAssetDialog(
    folders: List<AssetFolderEntity>,
    onPick: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("移动到文件夹") },
        text = {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPick(null) }
                        .padding(vertical = Dimens.Sm),
                ) {
                    Text("未归档", style = MaterialTheme.typography.bodyMedium)
                }
                folders.forEach { folder ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(folder.id) }
                            .padding(vertical = Dimens.Sm),
                    ) {
                        Text(
                            "${folderBadge(folder.kind)}${folder.name}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

private fun folderBadge(kind: String): String = when (kind) {
    "role" -> "[角色] "
    "product" -> "[产品] "
    else -> ""
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AssetCell(asset: AssetEntity, onShare: () -> Unit, onSave: () -> Unit, onMove: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Box(modifier = Modifier.aspectRatio(1f)) {
        Box(
            modifier = Modifier
                .aspectRatio(1f)
                // 单击：分享；长按：打开操作菜单（分享/保存/移动/删除）
                .combinedClickable(onClick = onShare, onLongClick = { menu = true }),
        ) {
            val path = asset.previewPath ?: asset.mediaPath
            if (path != null && File(path).exists()) {
                if (asset.kind == com.tapcreator.app.data.model.MediaKind.VIDEO) {
                    com.tapcreator.app.ui.components.VideoThumb(file = File(path), modifier = Modifier.fillMaxSize())
                } else if (asset.kind == com.tapcreator.app.data.model.MediaKind.AUDIO) {
                    com.tapcreator.app.ui.components.AudioThumb(file = File(path), modifier = Modifier.fillMaxSize())
                } else {
                    AsyncImage(model = File(path), contentDescription = asset.title, modifier = Modifier.fillMaxSize())
                }
            } else {
                Text(
                    text = asset.title,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text("分享") },
                onClick = { menu = false; onShare() },
            )
            DropdownMenuItem(
                text = { Text("保存到相册") },
                onClick = { menu = false; onSave() },
            )
            DropdownMenuItem(
                text = { Text("移动到文件夹") },
                onClick = { menu = false; onMove() },
            )
            DropdownMenuItem(
                text = { Text("删除", color = MaterialTheme.colorScheme.error) },
                onClick = { menu = false; onDelete() },
            )
        }
    }
}

private fun saveToGallery(asset: AssetEntity, vm: LibraryViewModel, context: android.content.Context) {
    vm.saveToGallery(asset) { ok ->
        Toast.makeText(context, if (ok) "已保存到相册" else "保存失败", Toast.LENGTH_SHORT).show()
    }
}


private fun shareFile(context: android.content.Context, file: File, mime: String?) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = mime ?: "*/*"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "分享素材"))
}