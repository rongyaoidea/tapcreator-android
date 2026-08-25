package com.tapcreator.app.ui.chat

import android.widget.Toast
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.tapcreator.app.data.db.AssetEntity
import com.tapcreator.app.data.db.CardEntity
import com.tapcreator.app.data.db.MessageEntity
import com.tapcreator.app.data.model.MediaKind
import com.tapcreator.app.ui.theme.Dimens
import com.tapcreator.app.ui.theme.CardButton
import com.tapcreator.app.ui.theme.pressSpring
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    onBack: () -> Unit,
    viewModel: ChatViewModel = hiltViewModel(),
    onOpenTasks: (String) -> Unit = {},
    onPickFromLibrary: () -> Unit = {},
) {
    val messages by viewModel.messages.collectAsState()
    val cards by viewModel.cards.collectAsState()
    val runs by viewModel.runs.collectAsState()
    val activeRun by viewModel.activeRun.collectAsState()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    // 长按删除确认：持有待执行删除动作
    var pendingDelete by remember { mutableStateOf<(() -> Unit)?>(null) }
    // 自主 Agent：浮动按钮呼出
    var agentOpen by rememberSaveable { mutableStateOf(false) }
    // 「关联」入口：在工作界面直接编辑该卡与其它卡的输入/输出参考关系
    var linkTarget by rememberSaveable { mutableStateOf<String?>(null) }
    // 会话名称手动编辑
    var renameOpen by rememberSaveable { mutableStateOf(false) }
    // 空白卡片编辑浮层开关
    var draftEditorOpen by rememberSaveable { mutableStateOf(false) }
    // 画布：按生成类型过滤（null=全部）
    var canvasFilter by rememberSaveable { mutableStateOf<MediaKind?>(null) }
    // 成品卡全屏预览：单击卡打开（图片放大/视频播放+下载），null=关闭
    var previewCard by remember { mutableStateOf<CardEntity?>(null) }
    // 画布选取参考卡模式：点击「引用其他卡片」后关闭面板进入画布选卡，选中后回到面板
    var canvasPickingRef by remember { mutableStateOf(false) }

    LaunchedEffect(messages.size, cards.size) {
        if (listState.layoutInfo.totalItemsCount > 0) {
            listState.animateScrollToItem(listState.layoutInfo.totalItemsCount - 1)
        }
    }

    Scaffold(
        topBar = {
            ChatTopBar(
                title = viewModel.conversationTitle,
                onBack = onBack,
                onRename = { renameOpen = true },
                onOpenTasks = { onOpenTasks(viewModel.conversationId) },
            )
        },
    ) { pad ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(pad)
                .imePadding(),
        ) {
            // 画布式工作空间：可平移/缩放、双击新建/聚焦、拖线连引用、按类型过滤、框选批量管理
            val cardLinks by viewModel.cardLinks.collectAsState()
            Box(modifier = Modifier.weight(1f)) {
                CanvasWorkspace(
                    cards = cards,
                    links = cardLinks,
                    selectedIds = if (canvasPickingRef) viewModel.selectedReferenceCards.map { it.id }.toSet() else viewModel.selectedCanvasIds,
                    filterKind = canvasFilter,
                    onToggleNode = viewModel::toggleCanvasNode,
                    onClearSelection = viewModel::clearCanvasSelection,
                    onDoubleTapNode = { viewModel.selectCanvasNode(it) },
                    onOpenDraft = if (canvasPickingRef) ({ _, _ -> }) else ({ id, kind ->
                        viewModel.openDraftEditor(id, kind)
                        draftEditorOpen = true
                    }),
                    onOpenPreview = if (canvasPickingRef) {
                        { card -> viewModel.toggleReference(card) }
                    } else {
                        { card -> previewCard = card }
                    },
                    onCommitPositions = viewModel::commitNodePositions,
                    onToggleFilter = { canvasFilter = it },
                    onAutoLayout = viewModel::autoLayoutCanvas,
                    onDeleteSelected = viewModel::deleteSelectedNodes,
                    modifier = Modifier.fillMaxSize(),
                )
                if (canvasPickingRef) {
                    // 画布选卡模式：顶部提示 + 底部确认按钮
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.TopCenter),
                        color = MaterialTheme.colorScheme.primaryContainer,
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "点击卡片选取/取消参考（已选 ${viewModel.selectedReferenceCards.size} 张）",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.weight(1f),
                            )
                            CardButton("完成", onClick = { canvasPickingRef = false; draftEditorOpen = true })
                        }
                    }
                }
                if (cards.isEmpty()) {
                    Text(
                        text = "画布为空 —— 点下方「生图/生视频」新建卡片开始创作",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.align(Alignment.Center).padding(horizontal = 24.dp),
                    )
                }
            }

            // 底部创作区：仅保留「生图/生视频」两个入口；点选后先生成一张待创作卡片，点卡片才弹出输入与参数
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(androidx.compose.material3.MaterialTheme.colorScheme.surface),
            ) {
            // 进行中 / 失败提示
            activeRun?.let { run ->
                if (run.status == com.tapcreator.app.data.model.RunStatus.RUNNING ||
                    run.status == com.tapcreator.app.data.model.RunStatus.PLANNING
                ) {
                    val (done, total) = viewModel.activeRunProgress()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        LinearProgressIndicator(
                            progress = { if (total == 0) 0f else done.toFloat() / total },
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = "生成中 $done/$total",
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 8.dp),
                        )
                        CardButton("取消", onClick = { viewModel.cancel() })
                    }
                }
            }
            if (viewModel.lastError != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = viewModel.lastError.orEmpty(),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    CardButton("重试", onClick = { viewModel.retry() })
                    CardButton("忽略", onClick = { viewModel.clearError() })
                }
            }

            // 引导：点下方按钮在时间线生成一张该类型空白卡片
            Text(
                text = "点击下方选择「生图」或「生视频」，卡片出现后点卡片即可填写内容并生成",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )

            // 底部仅保留两个入口：生图 / 生视频
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                BottomActionButton(text = "生图", tint = MaterialTheme.colorScheme.primary, icon = Icons.Filled.Image, onClick = { viewModel.startDraft(MediaKind.IMAGE) })
                BottomActionButton(text = "生视频", tint = MaterialTheme.colorScheme.primary, icon = Icons.Filled.Movie, onClick = { viewModel.startDraft(MediaKind.VIDEO) })
                BottomActionButton(text = "Agent", tint = MaterialTheme.colorScheme.primary, icon = Icons.Filled.AutoAwesome, onClick = { agentOpen = true })
            }
            }
        }
    }

    // 自主 Agent：全屏页面（覆盖创作工作区），带返回按钮关闭
    if (agentOpen) {
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { agentOpen = false },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                AgentSheet(viewModel, onDismiss = { agentOpen = false }, onPickFromLibrary = onPickFromLibrary)
            }
        }
    }

    // 空白卡片编辑浮层：点时间线上的空白卡片后弹出「输入 + 参数」，提交后生成
    // 用独立 Dialog 框替代 ModalBottomSheet：避免面板从下滑出时跳动、按钮点击刷新跳动
    if (draftEditorOpen && viewModel.draftKind != null) {
        androidx.compose.ui.window.Dialog(
            onDismissRequest = {
                // 关浮层=放弃这次创作，一并清掉草稿卡与 draftKind，避免残留导致下一次打开/输入异常
                viewModel.clearDraft()
                draftEditorOpen = false
            },
            properties = androidx.compose.ui.window.DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false, // 让输入法正确触发窗口调整
            ),
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 24.dp),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp,
                shadowElevation = 8.dp,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .imePadding()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Text(
                        text = if (viewModel.draftKind == MediaKind.IMAGE) "生图" else "生视频",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    CreationCard(viewModel, onSent = {
                        draftEditorOpen = false
                    }, onPickFromCanvas = {
                        draftEditorOpen = false
                        canvasPickingRef = true
                    }, onPickFromLibrary = {
                        draftEditorOpen = false
                        onPickFromLibrary()
                    })
                }
            }
        }
    }

    if (renameOpen) {
        RenameDialog(
            title = viewModel.conversationTitle,
            onConfirm = { name ->
                viewModel.renameConversation(name)
                renameOpen = false
            },
            onDismiss = { renameOpen = false },
        )
    }

    // 「关联」浮层：在工作界面直接设置卡片的输入/输出参考关系，无需切到关系图
    linkTarget?.let { targetId ->
        LinkSheet(
            vm = viewModel,
            targetId = targetId,
            onDismiss = { linkTarget = null },
        )
    }

    pendingDelete?.let { action ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除") },
            text = { Text("确定删除这一条吗？删除后不可恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    action()
                    pendingDelete = null
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            },
        )
    }

    // 成品卡全屏预览：图片放大查看 / 视频播放 + 下载到相册
    previewCard?.let { card ->
        MediaPreview(
            card = card,
            onDismiss = { previewCard = null },
            onDownload = { viewModel.saveCardToGallery(card) { } },
        )
    }
}

@Composable
private fun ChatTopBar(
    title: String,
    onBack: () -> Unit,
    onRename: () -> Unit,
    onOpenTasks: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = MaterialTheme.colorScheme.primary)
        }
        TextButton(
            onClick = onRename,
            modifier = Modifier.weight(1f),
        ) {
            Text(
                text = title.take(20),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // 标题旁加可见的编辑铅笔图标，替代「点标题改名」的隐藏交互
            Icon(
                imageVector = Icons.Filled.Edit,
                contentDescription = "重命名",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp).padding(start = 4.dp),
            )
        }
        TextButton(onClick = onOpenTasks) { Text("任务") }
    }
}

@Composable
private fun RenameDialog(title: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember(title) { mutableStateOf(title) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("重命名会话") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("会话名称") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name) }) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LinkSheet(vm: ChatViewModel, targetId: String, onDismiss: () -> Unit) {
    // 在工作界面直接设置卡片间参考关系（role=reference），无需切到关系图
    val cards by vm.cards.collectAsState()
    val links by vm.cardLinks.collectAsState()
    val target = cards.firstOrNull { it.id == targetId }
    if (target == null) { onDismiss(); return }
    // 按类型矩阵筛选可连候选：
    //  - 输出区（我→其它）：候选 to 需接受我(target)作参考
    //  - 输入区（其它→我）：候选 from 需能作为 target 的输入参考
    // 只允许把已完成的结果卡作为连线候选：排除未完成的空白草稿卡（runId='draft'）
    val cardPool = cards.filter { it.runId != "draft" }
    val outCandidates = cardPool.filter { it.id != targetId && vm.canServeAsReference(target.kind, it.kind) }
    val inCandidates = cardPool.filter { it.id != targetId && vm.canServeAsReference(it.kind, target.kind) }
    // 已存在的边集合：in(源头→本卡)、out(本卡→目标)
    val inSet = remember(links) { links.filter { it.toCardId == targetId }.map { it.fromCardId }.toSet() }
    val outSet = remember(links) { links.filter { it.fromCardId == targetId }.map { it.toCardId }.toSet() }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 600.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text(
                text = "卡片关联：${target.title.ifBlank { target.kind.name }}",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            Text(
                text = "提示词文本是每次生成的固有输入，不在此选卡。此处连图/视频卡的相互参考：图像卡可作图·视频参考，视频卡仅作视频参考；文本/音频不参与图·视频参考连线。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            // 本卡作为下游卡片的输入（我输出 → 其它卡引用）
            Text(
                text = "输出参考（我到其他卡引用）",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            if (outCandidates.isEmpty()) {
                Text("没有可作为本卡输出目标的有效卡片", style = MaterialTheme.typography.labelSmall)
            } else {
                outCandidates.forEach { card ->
                    val connected = card.id in outSet
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(Dimens.RadiusCard))
                            .clickable {
                                if (connected) vm.unlinkReference(targetId, card.id)
                                else vm.linkReference(targetId, card.id)
                            }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // 连接状态用色块显示
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(
                                    if (connected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                    androidx.compose.foundation.shape.CircleShape,
                                ),
                        )
                        Text(
                            text = if (connected) "已连接" else "未连接",
                            color = if (connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(end = 8.dp),
                        )
                        Text(
                            text = card.title.ifBlank { card.kind.name },
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = if (connected) "已关联" else "未关联",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
            // 其它卡片作为我的输入（其它卡 → 我引用）
            Text(
                text = "输入参考（其他卡到我引用）",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            if (inCandidates.isEmpty()) {
                Text("没有可作为本卡输入参考的有效卡片", style = MaterialTheme.typography.labelSmall)
            } else {
                inCandidates.forEach { card ->
                    val connected = card.id in inSet
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(Dimens.RadiusCard))
                            .clickable {
                                if (connected) vm.unlinkReference(card.id, targetId)
                                else vm.linkReference(card.id, targetId)
                            }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // 连接状态用色块显示
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(
                                    if (connected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                    androidx.compose.foundation.shape.CircleShape,
                                ),
                        )
                        Text(
                            text = if (connected) "已连接" else "未连接",
                            color = if (connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(end = 8.dp),
                        )
                        Text(
                            text = card.title.ifBlank { card.kind.name },
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = if (connected) "已引用" else "未引用",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun androidx.compose.foundation.layout.RowScope.BottomActionButton(
    text: String,
    tint: Color,
    onClick: () -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Surface(
        modifier = Modifier
            .weight(1f)
            .clip(RoundedCornerShape(Dimens.RadiusCard))
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .pressSpring(interactionSource),
        shape = RoundedCornerShape(Dimens.RadiusCard),
        color = tint,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(18.dp).padding(end = 4.dp),
                )
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimary,
            )
        }
    }
}

// Agent 流式输出的单个对话气泡：think=大脑实时输出（弱化灰字）、assistant=Agent 动作/思考（左）、user=观察/反馈（右）
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AgentStreamBubble(role: String, text: String) {
    if (role == "think" || role == "reasoning") {
        // 推理/思考过程流已移除——不再显示
        return
    }
    val isAgent = role == "assistant"
    val context = LocalContext.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isAgent) Arrangement.Start else Arrangement.End,
    ) {
        Surface(
            modifier = Modifier
                .widthIn(max = 320.dp)
                .combinedClickable(
                    onClick = {},
                    onLongClick = {
                        val displayText = if (isAgent) formatActionText(text) else text
                        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Agent 对话", displayText))
                        android.widget.Toast.makeText(context, "已复制", android.widget.Toast.LENGTH_SHORT).show()
                    },
                ),
            shape = RoundedCornerShape(Dimens.RadiusCard),
            color = if (isAgent) MaterialTheme.colorScheme.secondaryContainer
            else MaterialTheme.colorScheme.primaryContainer,
        ) {
            Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                Text(
                    text = if (isAgent) "Agent" else "观察",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isAgent) MaterialTheme.colorScheme.onSecondaryContainer
                    else MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(bottom = 2.dp),
                )
                // assistant 输出原是 JSON 动作文本（含符号）：解析成可读描述，去掉裸 JSON
                val displayText = if (isAgent) formatActionText(text) else text
                Text(text = displayText, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** 把大脑输出的 JSON 动作文本转成可读描述，避免用户看到裸 JSON 符号。
 *  解析失败（非 JSON）则原样返回（兼容模型偶尔输出的纯文本）。 */
private fun formatActionText(raw: String): String {
    val json = Json { ignoreUnknownKeys = true; isLenient = true }
    val cleaned = raw.trim().let {
        // 剥 markdown 代码围栏
        if (it.startsWith("```")) it.substringAfter("\n").substringBeforeLast("```").removePrefix("json").trim() else it
    }
    val start = cleaned.indexOf('{')
    if (start < 0) return raw.trim()
    // 取第一个完整 JSON 对象
    var depth = 0; var inStr = false; var esc = false; var end = -1
    for (i in start until cleaned.length) {
        val c = cleaned[i]
        when {
            esc -> esc = false
            inStr -> if (c == '\\' ) esc = true else if (c == '"') inStr = false
            c == '"' -> inStr = true
            c == '{' -> depth++
            c == '}' -> { depth--; if (depth == 0) { end = i; break } }
        }
    }
    if (end < 0) return raw.trim()
    val obj = runCatching { json.parseToJsonElement(cleaned.substring(start, end + 1)).jsonObject }.getOrNull() ?: return raw.trim()
    // 整段解析取值包 runCatching：?.jsonPrimitive 在值存在但非 primitive（嵌套对象/数组）时会抛异常，
    // 模型输出畸形 JSON 时不应崩掉整个 AgentStreamBubble Composable，失败回退原文
    return runCatching {
        val action = obj["action"]?.jsonPrimitive?.contentOrNull ?: return raw.trim()
        when (action) {
            "generate" -> {
                val tool = obj["tool"]?.jsonPrimitive?.contentOrNull?.removePrefix("GENERATE_")?.lowercase() ?: "内容"
                val prompt = obj["prompt"]?.jsonPrimitive?.contentOrNull.orEmpty().ifBlank { "（无提示词）" }
                val ref = obj["reference"]?.jsonPrimitive?.contentOrNull
                val refCard = obj["reference_card"]?.jsonPrimitive?.contentOrNull
                val refs = listOfNotNull(
                    ref?.let { "引用本轮#$it" },
                    refCard?.let { "引用卡$it" },
                    obj["reference_folder"]?.jsonPrimitive?.contentOrNull?.let { "文件夹「$it」" },
                ).joinToString("，")
                "生成${tool}：${prompt.take(80)}${if (refs.isNotEmpty()) "\n参考：$refs" else ""}"
            }
            "finish" -> {
                val raw = obj["summary"]?.jsonPrimitive?.contentOrNull?.ifBlank { null } ?: "本轮结束"
                // 清理转义符号
                val clean = raw
                    .replace("\\n", "\n").replace("\\\"", "\"")
                    .replace("\\t", "\t").replace("\\\\", "\\")
                    .trim()
                "完成：$clean"
            }
            "list_cards", "read_card", "list_assets", "list_runs", "read_trace", "list_skills", "layout_canvas" -> "查看：$action"
            "update_card", "update_asset", "move_asset", "delete_card", "delete_asset", "delete_folder",
            "create_folder", "memorize", "recall", "apply_skill", "skill_creator", "uninstall_skill", "link_cards",
            "unlink_cards", "web_search", "fetch_url", "configure_resolution" -> {
                val target = obj["card_id"]?.jsonPrimitive?.contentOrNull
                    ?: obj["asset_id"]?.jsonPrimitive?.contentOrNull
                    ?: obj["query"]?.jsonPrimitive?.contentOrNull
                    ?: obj["url"]?.jsonPrimitive?.contentOrNull
                    ?: obj["memory"]?.jsonPrimitive?.contentOrNull
                    ?: obj["content"]?.jsonPrimitive?.contentOrNull
                    ?: obj["model_name"]?.jsonPrimitive?.contentOrNull
                    ?: ""
                "$action${if (target.isNotBlank()) "：${target.take(40)}" else ""}"
            }
            else -> action
        }
    }.getOrNull() ?: raw.trim()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AgentSheet(vm: ChatViewModel, onDismiss: () -> Unit, onPickFromLibrary: () -> Unit = {}) {
    // 参数折叠：默认收起，聚焦对话；点「参数」展开模型/开关/参考
    var showParams by remember { mutableStateOf(false) }
    // Agent 执行对话的滚动状态：新输出到达时跟随到底部
    val agentListState = rememberLazyListState()
    // 全屏页面：对话占主体，参数/开关放输入框上方（可折叠），输入框固定底部
    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding(),
    ) {
        // 顶部栏：返回 + 标题 + 进度/取消
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onDismiss) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = MaterialTheme.colorScheme.primary)
            }
            Text(
                text = "自主 Agent",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            vm.agentProgress?.let { (_done, _total) ->
                CardButton("取消", onClick = { vm.cancelAgent() })
            }
        }
        // 对话流：占满主体（weight 1f），无高度上限——参考主流 Agent 应用，对话是页面主角
        if (vm.agentStream.isEmpty() && !vm.agentBusy) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "以自然语言描述目标，Agent 自动规划多步生成。\n例如：画一只赛博狐狸，再用它生成 10 秒夜景视频，最后配上温柔旁白。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
            }
        } else {
            LazyColumn(
                state = agentListState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(vertical = 8.dp),
            ) {
                items(vm.agentStream.size) { idx ->
                    val (role, text) = vm.agentStream[idx]
                    AgentStreamBubble(role = role, text = text)
                }
            }
            // 新输出到达时平滑跟随到底部
            LaunchedEffect(vm.agentStream.size) {
                val count = agentListState.layoutInfo.totalItemsCount
                if (count > 0) agentListState.animateScrollToItem(count - 1)
            }
        }
        // 错误条（输入框上方）
        vm.lastError?.let { err ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = err,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = vm::retry) { Text("重试") }
                TextButton(onClick = vm::clearError) { Text("忽略") }
            }
        }
        // 参数区（输入框上方，可折叠）：模型 / 开关 / 参考素材
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CardButton(if (showParams) "收起参数" else "参数", onClick = { showParams = !showParams })
            if (vm.selectedReferenceCards.isNotEmpty() || vm.selectedReferenceAssets.isNotEmpty()) {
                Text(
                    text = "  参考 ${vm.selectedReferenceCards.size + vm.selectedReferenceAssets.size}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
            UploadButton(vm)
            CardButton("素材库", onClick = onPickFromLibrary)
        }
        if (showParams) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 200.dp)
                    .padding(horizontal = 12.dp, vertical = 4.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                // MadStory 分镜优化开关
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 6.dp)) {
                    Switch(
                        checked = vm.cinematicEnabled,
                        onCheckedChange = { vm.setCinematic(it) },
                    )
                    Text(
                        text = "镜头分镜优化（MadStory）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // 推理开关已移除——始终使用 ThinkingLevel.AUTO，不发送 reasoning_effort
                // Agent 文本模型选择（规划大脑 & 对话模型）
                if (vm.visibleAgentTextModels.isNotEmpty()) {
                    Text(
                        text = "文本模型",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        vm.visibleAgentTextModels.forEach { model ->
                            FilterChip(
                                selected = vm.selectedAgentModelId == model.id,
                                onClick = { vm.setAgentModel(model.id) },
                                label = { Text(model.name) },
                                colors = FilterChipDefaults.filterChipColors(),
                            )
                        }
                    }
                }
            }
        }
        // 输入框 + 发送（固定底部）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            OutlinedTextField(
                value = vm.agentInput,
                onValueChange = vm::onAgentInputChange,
                placeholder = { Text("描述你想要的创作…") },
                modifier = Modifier.weight(1f),
                minLines = 1,
                maxLines = 4,
                enabled = !vm.agentBusy,
            )
            val context = LocalContext.current
            var showStopConfirm by remember { mutableStateOf(false) }
            if (vm.agentBusy) {
                // 等待回复期间：显示停止按钮 + 橙色光流环绕动画
                val infiniteTransition = rememberInfiniteTransition(label = "glow")
                val glowAlpha by infiniteTransition.animateFloat(
                    initialValue = 0.3f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(800, easing = LinearEasing),
                        repeatMode = RepeatMode.Reverse,
                    ),
                    label = "glowAlpha",
                )
                IconButton(
                    onClick = { showStopConfirm = true },
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .size(56.dp)
                        .then(
                            Modifier.border(
                                width = 2.dp,
                                color = MaterialTheme.colorScheme.primary.copy(alpha = glowAlpha),
                                shape = RoundedCornerShape(50),
                            ),
                        ),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Stop,
                        contentDescription = "停止 Agent",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                if (showStopConfirm) {
                    androidx.compose.material3.AlertDialog(
                        onDismissRequest = { showStopConfirm = false },
                        title = { Text("停止 Agent") },
                        text = { Text("确定停止当前 Agent 执行吗？已生成的产出会保留。") },
                        confirmButton = {
                            TextButton(onClick = {
                                vm.cancelAgent()
                                showStopConfirm = false
                            }) { Text("停止", color = MaterialTheme.colorScheme.error) }
                        },
                        dismissButton = {
                            TextButton(onClick = { showStopConfirm = false }) { Text("继续") }
                        },
                    )
                }
            } else {
                IconButton(
                    onClick = {
                        if (vm.agentInput.isBlank()) {
                            Toast.makeText(context, "请输入内容", Toast.LENGTH_SHORT).show()
                        } else {
                            vm.sendAgent()
                        }
                    },
                    modifier = Modifier.padding(start = 8.dp).size(56.dp),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Agent 执行",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CreationCard(vm: ChatViewModel, onSent: () -> Unit = {}, onPickFromCanvas: () -> Unit = {}, onPickFromLibrary: () -> Unit = {}) {
    // 选类型后弹出的该类型创作卡片：文本/参考输入与参数选项在同一张卡片内
    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(Dimens.RadiusCard),
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (vm.visibleModels.isEmpty()) {
                Text(
                    text = "未配置可用模型——请到「设置」配置渠道、API Key 与模型目录",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            } else {
                // 模型
                vm.visibleModels.forEach { model ->
                    FilterChip(
                        selected = vm.selectedModelId == model.id,
                        onClick = { vm.setModel(model.id) },
                        label = { Text(model.name) },
                        colors = FilterChipDefaults.filterChipColors(),
                    )
                }
            }
        }
        val caps = buildList {
            if (vm.supportsReference) add("参考素材")
            if (isEmpty()) add("基础生成")
        }
        Text(
            text = "能力：" + caps.joinToString("、"),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        // 画幅（横竖/方）：图像与视频通用
        if (vm.selectedKind == MediaKind.IMAGE || vm.selectedKind == MediaKind.VIDEO) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("画幅", style = MaterialTheme.typography.labelSmall)
                RATIOS.forEach { r ->
                    FilterChip(
                        selected = vm.ratio == r,
                        onClick = { vm.onRatioChange(r) },
                        label = { Text(r, style = MaterialTheme.typography.labelSmall) },
                        colors = FilterChipDefaults.filterChipColors(),
                    )
                }
            }
        }
        // 分辨率：始终提供手动输入框（自定义分辨率）；若模型声明了已知档位，另排一批快捷 chips 点选填充
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("分辨率", style = MaterialTheme.typography.labelSmall)
                OutlinedTextField(
                    value = vm.resolution ?: "",
                    onValueChange = { vm.onResolutionChange(it) },
                    placeholder = {
                        Text(
                            if (vm.selectedKind == com.tapcreator.app.data.model.MediaKind.VIDEO) "如 768P / 2K" else "如 1024x1024",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        )
                    },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.labelMedium,
                    modifier = Modifier
                        .width(150.dp)
                        .heightIn(max = 44.dp),
                )
            }
            if (vm.selectedModelResolutions.isNotEmpty()) {
                FlowRow(
                    modifier = Modifier.padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    vm.selectedModelResolutions.forEach { res ->
                        FilterChip(
                            selected = vm.resolution == res,
                            onClick = { vm.onResolutionChange(res) },
                            label = { Text(res, style = MaterialTheme.typography.labelSmall) },
                            colors = FilterChipDefaults.filterChipColors(),
                        )
                    }
                }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("数量", style = MaterialTheme.typography.labelSmall)
            SliderFake(vm, count = true)
        }
        if (vm.selectedKind == com.tapcreator.app.data.model.MediaKind.VIDEO) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("时长 ${vm.videoSeconds}s", style = MaterialTheme.typography.labelSmall)
                Slider(
                    value = vm.videoSeconds.toFloat().coerceIn(MIN_SECONDS.toFloat(), MAX_SECONDS.toFloat()),
                    onValueChange = { vm.onVideoSecondsChange(it.toInt()) },
                    valueRange = MIN_SECONDS.toFloat()..MAX_SECONDS.toFloat(),
                    steps = 0,
                    modifier = Modifier.weight(1f),
                )
            }
            // 音频默认随视频同步生成，不再提供单独开关
        }
        }
        }
        // 提示词/参考输入与提交（属于该类型卡片的一部分）
        InputBar(vm, onSent, onPickFromCanvas = onPickFromCanvas, onPickFromLibrary = onPickFromLibrary)
    }
}

/** 可选画幅（图像与视频通用） */
private val RATIOS = listOf("1:1", "16:9", "9:16", "4:3", "3:4", "21:9")

/** 视频时长滑动条范围（秒） */
private const val MIN_SECONDS = 4
private const val MAX_SECONDS = 30

@Composable
private fun SliderFake(vm: ChatViewModel, count: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        for (n in 1..4) {
            val selected = vm.count == n
            Text(
                text = "$n",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier
                    .padding(horizontal = 6.dp)
                    .clickable { vm.onCountChange(n) },
                color = if (selected) MaterialTheme.colorScheme.primary else Color.Unspecified,
            )
        }
    }
}

@Composable
private fun InputBar(vm: ChatViewModel, onSent: () -> Unit = {}, onPickFromCanvas: () -> Unit = {}, onPickFromLibrary: () -> Unit = {}) {
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            if (vm.selectedReferenceCards.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (vm.supportsReference) {
                            "已选 ${vm.selectedReferenceCards.size} 张参考卡片"
                        } else {
                            "已选 ${vm.selectedReferenceCards.size} 张参考卡片（仅文字提示）"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.weight(1f),
                    )
                    CardButton(
                        text = "取消引用",
                        onClick = { vm.clearAllReferences() },
                        tint = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
            if (vm.selectedReferenceAssets.isNotEmpty()) {
                Column(modifier = Modifier.padding(bottom = 4.dp)) {
                    Text(
                        text = "已选 ${vm.selectedReferenceAssets.size} 条素材参考：",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    // 逐个显示文件名，确认上传成功
                    vm.selectedReferenceAssets.forEach { asset ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(start = 8.dp, top = 2.dp),
                        ) {
                            Text(
                                text = "· ${asset.title}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(
                                onClick = { vm.toggleReferenceAsset(asset) },
                                modifier = Modifier.size(24.dp).padding(0.dp),
                                contentPadding = PaddingValues(0.dp),
                            ) { Text("×", color = MaterialTheme.colorScheme.error) }
                        }
                    }
                }
            }
            // 提示词优化开关：开启后提交前用默认文本模型润色提示词
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "优化提示词（文本模型润色）",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Switch(checked = vm.promptOptimize, onCheckedChange = { vm.togglePromptOptimize() })
            }
            OutlinedTextField(
                value = vm.input,
                onValueChange = vm::onInputChange,
                placeholder = { Text("描述你要创作的内容…") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 1,
                maxLines = 4,
            )
            // 本会话结果卡参考：把已生成的卡片作为参考素材喂给当前生成
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                TextButton(
                    onClick = { onPickFromCanvas() },
                    modifier = Modifier.padding(top = 2.dp),
                ) {
                    Text(if (vm.selectedReferenceCards.isNotEmpty()) "已选 ${vm.selectedReferenceCards.size} 张，点此在画布选取" else "引用其他卡片")
                }
            }
            // 跨会话素材参考：跳转到素材库页面选取（不再内嵌列表）
            Row(verticalAlignment = Alignment.CenterVertically) {
                CardButton("从素材库引用", onClick = onPickFromLibrary)
                UploadButton(vm)
            }
        }
        IconButton(
            onClick = {
                if (vm.input.isBlank()) {
                    Toast.makeText(context, "请输入内容", Toast.LENGTH_SHORT).show()
                } else {
                    vm.send()
                    onSent()
                }
            },
            modifier = Modifier
                .padding(start = 8.dp)
                .size(56.dp),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Send,
                contentDescription = "发送",
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/** 引用本会话一张已完成结果卡（图/视频）作为当前生成的参考素材 */
@Composable
private fun CardRefChip(card: CardEntity, selected: Boolean, onToggle: () -> Unit) {
    val label = when (card.kind) {
        MediaKind.IMAGE -> "图"
        MediaKind.VIDEO -> "视频"
        MediaKind.TEXT -> "文"
        else -> MediaKind.AUDIO.name
    }
    FilterChip(
        selected = selected,
        onClick = onToggle,
        label = { Text(card.title.ifBlank { "卡片 ${label}" }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = { Text(label, style = MaterialTheme.typography.labelSmall) },
        colors = FilterChipDefaults.filterChipColors(),
    )
}

@Composable
private fun AssetRefChip(asset: AssetEntity, selected: Boolean, onToggle: () -> Unit) {
    val label = when (asset.kind) {
        MediaKind.IMAGE -> "图"
        MediaKind.AUDIO -> "音"
        MediaKind.VIDEO -> "视频"
        else -> "文"
    }
    FilterChip(
        selected = selected,
        onClick = onToggle,
        label = { Text(asset.title.ifBlank { "无标题" }) },
        leadingIcon = { Text(label, style = MaterialTheme.typography.labelSmall) },
        colors = FilterChipDefaults.filterChipColors(),
    )
}

/** 上传附件：按当前生成类型过滤可上传的格式（图片卡只传图；视频卡可传图/音频/视频；音频卡只传音频） */
@Composable
private fun UploadButton(vm: ChatViewModel, kind: MediaKind = vm.selectedKind) {
    val context = LocalContext.current
    // 统一允许上传图片/音频/视频，不按 kind 限制
    val mimeTypes = arrayOf("image/*", "audio/*", "video/*")
    val label = "上传附件"
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            // 持久化 URI 读权限，避免某些设备回调后 URI 失效
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            vm.importLocalMedia(uri)
        }
    }
    TextButton(
        onClick = { picker.launch(mimeTypes) },
        modifier = Modifier.padding(top = 2.dp),
    ) {
        Text(label)
    }
}
/**
 * 成品卡全屏预览：图片放大查看 / 视频播放，并提供下载到相册。
 * 图片用 AsyncImage 全屏铺满；视频用自建 SurfaceView+ExoPlayer 播放（带音）。
 */
@Composable
private fun MediaPreview(
    card: CardEntity,
    onDismiss: () -> Unit,
    onDownload: () -> Unit,
) {
    val file = card.mediaPath?.let { File(it) }?.takeIf { it.exists() }
        ?: card.previewPath?.let { File(it) }?.takeIf { it.exists() }
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
                            // 只解码图片边界拿宽高（不加载整图），用于按图片比例缩放预览框
                            val bounds = remember(file) {
                                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                                runCatching { BitmapFactory.decodeFile(file.absolutePath, opts) }
                                if (opts.outWidth > 0 && opts.outHeight > 0) opts.outWidth to opts.outHeight else null
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
 * 全屏视频播放（带音）。自建 SurfaceView 绑定 ExoPlayer，避免引入 exoplayer-ui。
 * 退出时释放 player，避免后台持有解码器资源。
 */
@Composable
private fun VideoPlayer(file: File) {
    val context = LocalContext.current
    val player = remember(file.absolutePath) {
        com.google.android.exoplayer2.ExoPlayer.Builder(context).build().apply {
            setMediaItem(com.google.android.exoplayer2.MediaItem.fromUri(android.net.Uri.fromFile(file)))
            playWhenReady = true
            prepare()
        }
    }
    DisposableEffect(player) {
        onDispose { player.release() }
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
