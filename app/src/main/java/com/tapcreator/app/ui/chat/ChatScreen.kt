package com.tapcreator.app.ui.chat

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FloatingActionButton
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
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    onBack: () -> Unit,
    viewModel: ChatViewModel = hiltViewModel(),
    onOpenTasks: (String) -> Unit = {},
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
        floatingActionButton = {
            // 自主 Agent：同为创作界面的一部分，以悬浮按钮呼出参与工作
            FloatingActionButton(onClick = { agentOpen = true }) {
                Text("Agent", style = MaterialTheme.typography.labelLarge)
            }
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
                    selectedIds = viewModel.selectedCanvasIds,
                    filterKind = canvasFilter,
                    onToggleNode = viewModel::toggleCanvasNode,
                    onClearSelection = viewModel::clearCanvasSelection,
                    onDoubleTapNode = { viewModel.selectCanvasNode(it) },
                    onOpenDraft = { id, kind ->
                        viewModel.openDraftEditor(id, kind)
                        draftEditorOpen = true
                    },
                    onOpenPreview = { card -> previewCard = card },
                    onCommitPositions = viewModel::commitNodePositions,
                    onToggleFilter = { canvasFilter = it },
                    onAutoLayout = viewModel::autoLayoutCanvas,
                    onDeleteSelected = viewModel::deleteSelectedNodes,
                    modifier = Modifier.fillMaxSize(),
                )
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
                        TextButton(onClick = { viewModel.cancel() }) { Text("取消") }
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
                    TextButton(onClick = viewModel::retry) { Text("重试") }
                    TextButton(onClick = viewModel::clearError) { Text("忽略") }
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
                BottomActionButton(text = "🖼 生图", tint = MaterialTheme.colorScheme.primary) {
                    viewModel.startDraft(MediaKind.IMAGE)
                }
                BottomActionButton(text = "🎬 生视频", tint = MaterialTheme.colorScheme.tertiary) {
                    viewModel.startDraft(MediaKind.VIDEO)
                }
            }
            }
        }
    }

    // 自主 Agent 浮层：呼出输入面板参与工作
    if (agentOpen) {
        AgentSheet(viewModel, onDismiss = { agentOpen = false })
    }

    // 空白卡片编辑浮层：点时间线上的空白卡片后弹出「输入 + 参数」，提交后生成
    if (draftEditorOpen && viewModel.draftKind != null) {
        ModalBottomSheet(onDismissRequest = {
            // 关浮层=放弃这次创作，一并清掉草稿卡与 draftKind，避免残留导致下一次打开/输入异常
            viewModel.clearDraft()
            draftEditorOpen = false
        }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text(
                    text = if (viewModel.draftKind == MediaKind.IMAGE) "🖼 生图" else "🎬 生视频",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                CreationCard(viewModel, onSent = {
                    viewModel.clearDraft()
                    draftEditorOpen = false
                })
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
        TextButton(onClick = onBack) { Text("←") }
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
                text = "输出参考（我→其他卡引用）",
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
                        Text(
                            text = if (connected) "●" else "○",
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
                text = "输入参考（其他卡→我引用）",
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
                        Text(
                            text = if (connected) "●" else "○",
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
private fun androidx.compose.foundation.layout.RowScope.BottomActionButton(text: String, tint: Color, onClick: () -> Unit) {
    // 底部仅保留的「生图/生视频」入口：一次点击生成一张待创作卡片
    Surface(
        modifier = Modifier
            .weight(1f)
            .clip(RoundedCornerShape(Dimens.RadiusCard))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(Dimens.RadiusCard),
        color = tint,
    ) {
        Box(
            modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimary,
            )
        }
    }
}

// Agent 流式输出的单个对话气泡：think=大脑实时输出（弱化灰字）、assistant=Agent 动作/思考（左）、user=观察/反馈（右）
@Composable
private fun AgentStreamBubble(role: String, text: String) {
    if (role == "think") {
        Text(
            text = "思考 · " + text,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        )
        return
    }
    val isAgent = role == "assistant"
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isAgent) Arrangement.Start else Arrangement.End,
    ) {
        Surface(
            modifier = Modifier.widthIn(max = 320.dp),
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
                Text(text = text, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AgentSheet(vm: ChatViewModel, onDismiss: () -> Unit) {
    val libraryAssets by vm.libraryAssets.collectAsState()
    var showAgentLibrary by remember { mutableStateOf(false) }
    // Agent 执行对话的滚动状态：新输出到达时跟随到底部
    val agentListState = rememberLazyListState()
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text(
                text = "自主 Agent",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            Text(
                text = "以自然语言描述目标，Agent 自动规划并调用 图片→视频→音频 等步骤完成。选中结果卡片或素材库参考仍有效。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            // Agent 执行进度
            vm.agentProgress?.let { (done, total) ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LinearProgressIndicator(
                        progress = { if (total == 0) 0f else done.toFloat() / total },
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "Agent $done/$total 步",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                    TextButton(onClick = vm::cancelAgent) { Text("取消") }
                }
            }
            // MadStory 分镜优化开关
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
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
            // Agent 文本模型选择（规划大脑 & 对话模型）
            if (vm.visibleAgentTextModels.isNotEmpty()) {
                Column(modifier = Modifier.padding(bottom = 8.dp)) {
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
            // 参考素材 & 上传附件
            if (vm.selectedReferenceCards.isNotEmpty() || vm.selectedReferenceAssets.isNotEmpty()) {
                Text(
                    text = "已选参考：卡片 ${vm.selectedReferenceCards.size} · 素材 ${vm.selectedReferenceAssets.size}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                UploadButton(vm)
                TextButton(onClick = { showAgentLibrary = !showAgentLibrary }) {
                    Text(if (showAgentLibrary) "收起素材库" else "从素材库引用")
                }
            }
            if (showAgentLibrary) {
                if (libraryAssets.isEmpty()) {
                    Text(
                        text = "素材库为空，可点「上传附件」导入本地图片/视频",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                } else {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(bottom = 6.dp),
                    ) {
                        items(libraryAssets, key = { it.id }) { asset ->
                            AssetRefChip(
                                asset = asset,
                                selected = asset.mediaPath != null &&
                                    vm.selectedReferenceAssets.any { it.mediaPath == asset.mediaPath },
                            ) { vm.toggleReferenceAsset(asset) }
                        }
                    }
                }
            }
            // Agent 执行错误（sheet 内可见，避免被浮层盖住）
            vm.lastError?.let { err ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 6.dp),
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
            // Agent 实时对话：大脑输出/动作/观察按发生时间合并为单一流，新输出自动跟随到底部
            if (vm.agentStream.isNotEmpty()) {
                Text(
                    text = "执行对话（实时）",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
                )
                LazyColumn(
                    state = agentListState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(vm.agentStream.size) { idx ->
                        val (role, text) = vm.agentStream[idx]
                        AgentStreamBubble(role = role, text = text)
                    }
                }
                // 新输出到达时平滑跟随到底部，避免停留在历史位置被弹回顶部
                LaunchedEffect(vm.agentStream.size) {
                    val count = agentListState.layoutInfo.totalItemsCount
                    if (count > 0) agentListState.animateScrollToItem(count - 1)
                }
            }
            // Agent 输入 + 执行
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                OutlinedTextField(
                    value = vm.agentInput,
                    onValueChange = vm::onAgentInputChange,
                    placeholder = { Text("例如：画一只赛博狐狸，再用它生成 10 秒夜景视频，最后配上温柔的旁白…") },
                    modifier = Modifier.weight(1f),
                    minLines = 1,
                    maxLines = 4,
                    enabled = !vm.agentBusy,
                )
                val context = LocalContext.current
                IconButton(
                    onClick = {
                        if (vm.agentInput.isBlank() || vm.agentBusy) {
                            Toast.makeText(context, if (vm.agentBusy) "Agent 正在执行" else "请输入内容", Toast.LENGTH_SHORT).show()
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
private fun CreationCard(vm: ChatViewModel, onSent: () -> Unit = {}) {
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
            if (vm.supportsAudio) add("音频生成")
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
            // 能力标签驱动：仅当模型具备 audio 能力时展示音频开关
            if (vm.supportsAudio) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "生成音频（音画同步）",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(checked = vm.audioEnabled, onCheckedChange = vm::toggleAudio)
                }
            }
        }
        }
        }
        // 提示词/参考输入与提交（属于该类型卡片的一部分）
        InputBar(vm, onSent)
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
private fun InputBar(vm: ChatViewModel, onSent: () -> Unit = {}) {
    val context = LocalContext.current
    val libraryAssets by vm.libraryAssets.collectAsState()
    val cards by vm.cards.collectAsState()
    var showLibrary by remember { mutableStateOf(false) }
    var showCardRef by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            if (vm.selectedReferenceCards.isNotEmpty()) {
                Text(
                    text = if (vm.supportsReference) {
                        "已选 ${vm.selectedReferenceCards.size} 张参考卡片，将作为参考素材生效"
                    } else {
                        "已选 ${vm.selectedReferenceCards.size} 张参考卡片（当前模型不支持 reference，仅作文字提示）"
                    },
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            if (vm.selectedReferenceAssets.isNotEmpty()) {
                Text(
                    text = "已选 ${vm.selectedReferenceAssets.size} 条素材库参考，将作为参考素材生效",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
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
                    onClick = { showCardRef = !showCardRef },
                    modifier = Modifier.padding(top = 2.dp),
                ) {
                    Text(if (showCardRef) "收起引用卡片" else "引用其他卡片")
                }
                if (showCardRef) {
                    Text(
                        text = "选择本会话已生成的图文卡作为参考输入",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            // 跨会话素材参考：从全局素材库选图/音频
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    onClick = { showLibrary = !showLibrary },
                    modifier = Modifier.padding(top = 2.dp),
                ) {
                    Text(if (showLibrary) "收起素材库" else "从素材库引用")
                }
                UploadButton(vm)
                if (vm.supportsReference) {
                    Text(
                        text = "图/音频可跨会话复用",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (showCardRef) {
                val target = vm.selectedKind
                // 仅列出「能作为当前生成类型的参考」的已完成结果卡（排除空白草稿卡 runId='draft'）
                val eligible = cards.filter { it.runId != "draft" }
                    .filter { vm.canServeAsReference(it.kind, target) }
                if (eligible.isEmpty()) {
                    Text(
                        text = "暂无可用卡片作为参考（需先生成图/视频卡，且其类型可作当前生成的输入）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                } else {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(vertical = 4.dp),
                    ) {
                        items(eligible, key = { it.id }) { card ->
                            CardRefChip(
                                card = card,
                                selected = vm.selectedReferenceCards.any { it.id == card.id },
                            ) { vm.toggleReference(card) }
                        }
                    }
                }
            }
            if (showLibrary) {
                if (libraryAssets.isEmpty()) {
                    Text(
                        text = "素材库为空，先去会话里生成几张图或音频再回来选",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                } else {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(vertical = 4.dp),
                    ) {
                        items(libraryAssets, key = { it.id }) { asset ->
                            AssetRefChip(asset, selected = asset.mediaPath != null &&
                                vm.selectedReferenceAssets.any { it.mediaPath == asset.mediaPath }
                            ) { vm.toggleReferenceAsset(asset) }
                        }
                    }
                }
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
    val mimeTypes: Array<String>
    val label: String
    when (kind) {
        MediaKind.IMAGE -> {
            mimeTypes = arrayOf("image/*")
            label = "上传图片"
        }
        MediaKind.VIDEO -> {
            mimeTypes = arrayOf("image/*", "audio/*", "video/*")
            label = "上传图/音/视频"
        }
        MediaKind.AUDIO -> {
            mimeTypes = arrayOf("audio/*")
            label = "上传音频"
        }
        else -> {
            mimeTypes = arrayOf("image/*", "audio/*", "video/*")
            label = "上传附件"
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importLocalMedia(uri)
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
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = Color.Black.copy(alpha = 0.95f),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // 顶部：标题 + 关闭
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
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "关闭", tint = Color.White)
                    }
                }
                // 预览区
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false),
                    contentAlignment = Alignment.Center,
                ) {
                    if (file == null) {
                        Text("文件不存在", color = Color.White)
                    } else if (card.kind == MediaKind.VIDEO) {
                        VideoPlayer(file = file)
                    } else if (card.kind == MediaKind.IMAGE) {
                        AsyncImage(
                            model = file,
                            contentDescription = card.title,
                            modifier = Modifier.fillMaxWidth(),
                            contentScale = ContentScale.Fit,
                        )
                    } else if (card.kind == MediaKind.TEXT) {
                        Text(
                            text = card.content.ifBlank { card.title },
                            color = Color.White,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(8.dp),
                        )
                    } else {
                        Text(card.kind.name, color = Color.White)
                    }
                }
                // 底部：下载按钮（仅图片/视频/音频有可下载文件时显示）
                if (file != null && card.kind != MediaKind.TEXT) {
                    Button(
                        onClick = onDownload,
                        modifier = Modifier.padding(top = 8.dp),
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
