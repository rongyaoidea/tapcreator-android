package com.tapcreator.app.ui.chat

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.tapcreator.app.data.db.CardEntity
import com.tapcreator.app.data.db.CardLinkEntity
import com.tapcreator.app.data.model.MediaKind
import com.tapcreator.app.data.model.RunStatus
import java.io.File

/** 画布节点窗宽（dp） */
private val NODE_W = 160.dp
private const val MIN_SCALE = 0.3f
private const val MAX_SCALE = 4f

/**
 * 真正可缩放平移的创作画布。
 *  - 平移/缩放：空白单指拖动平移、双指捏合缩放（节点布局基于 card.x/y 持久坐标）
 *  - 单击空白：清除选择/退出多选
 *  - 单击空白卡（runId='draft'）：回调 onOpenDraft 打开提交面板填写内容
 *  - 单击/长按结果卡：进入/追加多选（长按进入多选），选中后点「删除」批量清掉，线自动随选择保留
 *  - 参考连线：在提交面板里选参考卡时自动写入 CardLinkEntity，画布据此自动画线
 *  - 过滤：filterKind 只显示该类型节点（按生成类型着色 kindColor）
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CanvasWorkspace(
    cards: List<CardEntity>,
    links: List<CardLinkEntity>,
    selectedIds: Set<String>,
    filterKind: MediaKind?,
    onToggleNode: (id: String, multi: Boolean) -> Unit,
    onClearSelection: () -> Unit,
    onDoubleTapNode: (id: String) -> Unit,
    onOpenDraft: (cardId: String, kind: MediaKind) -> Unit,
    // 非 draft 成品卡单击：打开全屏预览（图片放大/视频播放+下载），与选中/多选分离
    onOpenPreview: (CardEntity) -> Unit,
    onCommitPositions: (updates: List<Pair<String, Pair<Float, Float>>>) -> Unit,
    onToggleFilter: (MediaKind?) -> Unit,
    onAutoLayout: () -> Unit,
    onDeleteSelected: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val nodeW = with(density) { NODE_W.toPx() }
    // 画布视口尺寸（px，非缩放坐标系）
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    var scale by remember { mutableStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    // 拖动中的实时偏移（画布坐标 dp）：card.x + drags[id].x
    var drags by remember { mutableStateOf<Map<String, Offset>>(emptyMap()) }
    // 节点实测布局尺寸（px）
    var nodeSizes by remember { mutableStateOf<Map<String, IntSize>>(emptyMap()) }
    // 多选删除状态：长按任意结果卡进入，进入后点其它卡追加/取消多选，点「删除」统一删除
    var multiSelect by remember { mutableStateOf(false) }

    fun basePos(card: CardEntity): Offset = Offset(card.x * density.density, card.y * density.density)

    fun screenPos(c: Offset): Offset = Offset(c.x * scale + pan.x, c.y * scale + pan.y)

    fun sizeOf(id: String): IntSize = nodeSizes[id] ?: IntSize(nodeW.toInt(), nodeW.toInt())

    fun hitNode(container: Offset): String? {
        for (card in cards) {
            val p = basePos(card)
            val s = sizeOf(card.id)
            if (container.x in p.x..(p.x + s.width) && container.y in p.y..(p.y + s.height)) return card.id
        }
        return null
    }

    fun containerOf(screen: Offset): Offset =
        Offset((screen.x - pan.x) / scale, (screen.y - pan.y) / scale)

    Box(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.18f))
            .onSizeChanged { viewport = it }
            // 平移 + 缩放
            .pointerInput(cards) {
                detectTransformGestures { centroid, panChange, zoomChange, _ ->
                    val newScale = (scale * zoomChange).coerceIn(MIN_SCALE, MAX_SCALE)
                    val k = newScale / scale
                    pan = Offset(
                        (pan.x - centroid.x) * k + centroid.x + panChange.x,
                        (pan.y - centroid.y) * k + centroid.y + panChange.y,
                    )
                    scale = newScale
                }
            }
            // 单击空白清除选择 / 退出多选（双击空白不再新建卡，类型由底部按钮明确选择）
            .pointerInput(cards) {
                detectTapGestures(
                    onTap = { pos ->
                        val hit = hitNode(containerOf(pos))
                        if (hit == null) {
                            multiSelect = false
                            onClearSelection()
                        }
                    },
                )
            },
    ) {
        // 关系边：选自卡参考后自动出现
        Canvas(modifier = Modifier.fillMaxSize()) {
            for (link in links) {
                val from = cards.firstOrNull { it.id == link.fromCardId } ?: continue
                val to = cards.firstOrNull { it.id == link.toCardId } ?: continue
                if (filterKind != null && from.kind != filterKind && to.kind != filterKind) continue
                val sf = sizeOf(from.id)
                val st = sizeOf(to.id)
                val a = screenPos(basePos(from)) + Offset(sf.width / 2f, sf.height / 2f) * scale
                val b = screenPos(basePos(to)) + Offset(st.width / 2f, st.height / 2f) * scale
                val mid = Offset((a.x + b.x) / 2f, (a.y + b.y) / 2f)
                val path = Path().apply {
                    moveTo(a.x, a.y)
                    quadraticTo(mid.x, a.y, mid.x, mid.y)
                    quadraticTo(mid.x, b.y, b.x, b.y)
                }
                drawPath(
                    path = path,
                    color = kindColor(from.kind).copy(alpha = 0.55f),
                    style = Stroke(width = 2f),
                )
            }
        }

        // 节点
        for (card in cards) {
            if (filterKind != null && card.kind != filterKind) continue
            val drag = drags[card.id] ?: Offset.Zero
            val selected = card.id in selectedIds
            val isDraft = card.runId == "draft"
            CanvasNode(
                card = card,
                selected = selected,
                isDraft = isDraft,
                basePx = basePos(card) + drag * density.density,
                scale = scale,
                pan = pan,
                onSize = { s -> nodeSizes = nodeSizes + (card.id to s) },
                onClick = {
                    if (isDraft) {
                        // 点空白卡=打开提交面板；从此进入单卡编辑，退出多选
                        multiSelect = false
                        onOpenDraft(card.id, card.kind)
                    } else if (multiSelect) {
                        // 多选模式下点成品卡：追加/取消选中
                        onToggleNode(card.id, true)
                    } else {
                        // 单击成品卡：打开全屏预览（图片放大/视频播放+下载）
                        onOpenPreview(card)
                    }
                },
                onLongClick = {
                    if (!isDraft) {
                        // 长按进入多选删除状态，并把该卡纳入选中
                        multiSelect = true
                        onToggleNode(card.id, true)
                    }
                },
                onDoubleTap = {
                    val p = basePos(card)
                    val s = sizeOf(card.id)
                    scale = 1.5f
                    pan = Offset(
                        viewport.width / 2f - (p.x + s.width / 2f) * scale,
                        viewport.height / 2f - (p.y + s.height / 2f) * scale,
                    )
                    if (isDraft) {
                        multiSelect = false
                        onOpenDraft(card.id, card.kind)
                    } else {
                        onDoubleTapNode(card.id)
                        onToggleNode(card.id, false)
                    }
                },
                onMoveStart = { drags = drags + (card.id to Offset.Zero) },
                onMove = { deltaPx ->
                    val deltaDp = Offset(
                        deltaPx.x / (scale * density.density),
                        deltaPx.y / (scale * density.density),
                    )
                    val grp = if (selected) selectedIds else setOf(card.id)
                    drags = drags + grp.map { it to (drags[it] ?: Offset.Zero) + deltaDp }
                },
                onMoveEnd = {
                    val grp = if (selected) selectedIds else setOf(card.id)
                    val updates = grp.mapNotNull { id ->
                        val c = cards.firstOrNull { it.id == id } ?: return@mapNotNull null
                        val d = drags[id] ?: return@mapNotNull null
                        id to (c.x + d.x to c.y + d.y)
                    }
                    onCommitPositions(updates)
                    drags = drags - grp
                },
            )
        }

        // 工具栏（不随画布缩放）
        CanvasToolbar(
            filterKind = filterKind,
            showDelete = selectedIds.isNotEmpty(),
            onToggleFilter = onToggleFilter,
            onAutoLayout = onAutoLayout,
            onDelete = {
                onDeleteSelected()
                multiSelect = false
            },
        )

        // 缩放控制
        Row(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ZoomButton("＋") { scale = (scale * 1.25f).coerceAtMost(MAX_SCALE) }
            ZoomButton("－") { scale = (scale / 1.25f).coerceAtLeast(MIN_SCALE) }
            ZoomButton("⌖") {
                scale = 1f
                pan = Offset.Zero
            }
        }
    }
}

@Composable
private fun ZoomButton(text: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.size(40.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
        tonalElevation = 3.dp,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(text, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun CanvasToolbar(
    filterKind: MediaKind?,
    showDelete: Boolean,
    onToggleFilter: (MediaKind?) -> Unit,
    onAutoLayout: () -> Unit,
    onDelete: () -> Unit,
) {
    val kinds = listOf<MediaKind?>(null, MediaKind.IMAGE, MediaKind.VIDEO, MediaKind.AUDIO)
    val labels = mapOf<MediaKind?, String>(
        null to "全部", MediaKind.IMAGE to "图", MediaKind.VIDEO to "视频",
        MediaKind.AUDIO to "音频",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        kinds.forEach { kind ->
            FilterChip(
                selected = filterKind == kind,
                onClick = { onToggleFilter(kind) },
                label = { Text(labels[kind] ?: "全部", style = MaterialTheme.typography.labelSmall) },
                leadingIcon = if (kind == null) null else {
                    { Box(Modifier.size(8.dp).background(kindColor(kind), CircleShape)) }
                },
                colors = FilterChipDefaults.filterChipColors(),
            )
        }
        TooltipText(onAutoLayout, "整理")
        if (showDelete) TooltipText(onDelete, "删除选中")
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.TooltipText(onClick: () -> Unit, text: String) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CanvasNode(
    card: CardEntity,
    selected: Boolean,
    isDraft: Boolean,
    basePx: Offset,
    scale: Float,
    pan: Offset,
    onSize: (IntSize) -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onDoubleTap: () -> Unit,
    onMoveStart: () -> Unit,
    onMove: (Offset) -> Unit,
    onMoveEnd: () -> Unit,
) {
    val screen = Offset(basePx.x * scale + pan.x, basePx.y * scale + pan.y)
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier = Modifier
            .offset { IntOffset(screen.x.toInt(), screen.y.toInt()) }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                transformOrigin = TransformOrigin(0f, 0f)
            }
            .onSizeChanged { s -> onSize(s) }
            .pointerInput(card.id) {
                detectDragGestures(
                    onDragStart = { onMoveStart() },
                    onDragEnd = { onMoveEnd() },
                    onDragCancel = { onMoveEnd() },
                ) { change, amount ->
                    onMove(amount)
                    change.consume()
                }
            }
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
                onDoubleClick = { onDoubleTap() },
            ),
    ) {
        Surface(
            modifier = Modifier
                .width(NODE_W)
                .then(
                    when {
                        selected -> Modifier.border(2.dp, kindColor(card.kind), shape)
                        isDraft -> Modifier.border(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.6f), shape)
                        else -> Modifier.border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f), shape)
                    }
                ),
            shape = shape,
            color = if (selected) kindColor(card.kind).copy(alpha = 0.14f) else MaterialTheme.colorScheme.surface,
            tonalElevation = if (selected) 6.dp else 2.dp,
            shadowElevation = if (selected) 6.dp else 2.dp,
        ) {
            CanvasNodeContent(card = card, selected = selected, isDraft = isDraft)
        }
    }
}

@Composable
private fun CanvasNodeContent(card: CardEntity, selected: Boolean, isDraft: Boolean) {
    Column(modifier = Modifier.fillMaxWidth()) {
        // 预览区
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(96.dp)
                .background(kindColor(card.kind).copy(alpha = if (selected) 0.28f else 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            val file = card.mediaPath?.let { File(it) }
                ?: card.previewPath?.let { File(it) }
            when {
                isDraft -> {
                    // 提交后 status 升级为 RUNNING 的「生成中」占位卡：显示加载动画，避免静默消失
                    when (card.status) {
                        RunStatus.RUNNING -> Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(28.dp),
                                strokeWidth = 3.dp,
                                color = kindColor(card.kind),
                            )
                            Text(
                                text = "生成中…",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            // indeterminate 进度条：增强等待态的"进行中"感知
                            LinearProgressIndicator(
                                modifier = Modifier
                                    .width(100.dp)
                                    .padding(top = 2.dp),
                                color = kindColor(card.kind),
                            )
                        }
                        RunStatus.FAILED -> Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = "⚠️",
                                style = MaterialTheme.typography.displaySmall,
                            )
                            Text(
                                text = "生成失败",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        else -> Text(
                            text = when (card.kind) {
                                MediaKind.IMAGE -> "🖼"
                                MediaKind.VIDEO -> "🎬"
                                MediaKind.AUDIO -> "♫"
                                MediaKind.TEXT -> "🅃"
                                else -> "▧"
                            },
                            style = MaterialTheme.typography.displaySmall,
                        )
                    }
                }
                file != null && file.exists() && card.kind == MediaKind.IMAGE -> AsyncImage(
                    model = file,
                    contentDescription = card.title,
                    modifier = Modifier.fillMaxSize(),
                )
                file != null && file.exists() && card.kind == MediaKind.VIDEO -> AsyncImage(
                    model = file,
                    contentDescription = card.title,
                    modifier = Modifier.fillMaxSize(),
                )
                else -> Text(
                    text = when (card.kind) {
                        MediaKind.IMAGE -> "🖼"
                        MediaKind.VIDEO -> "🎬"
                        MediaKind.AUDIO -> "♫"
                        MediaKind.TEXT -> "🅃"
                        else -> "▧"
                    },
                    style = MaterialTheme.typography.headlineMedium,
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(Modifier.size(8.dp).background(kindColor(card.kind), CircleShape))
            Text(
                text = card.title.ifBlank { when (card.kind) {
                    MediaKind.IMAGE -> "图片卡"
                    MediaKind.VIDEO -> "视频卡"
                    MediaKind.AUDIO -> "音频卡"
                    else -> "卡片"
                } },
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            // 增强提示词角标：非 draft 成品卡且 promptEnhanced 时显示
            if (!isDraft && card.promptEnhanced) {
                Text(
                    text = "⚡增强",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
            if (isDraft) {
                Text(
                    text = when (card.status) {
                        RunStatus.RUNNING -> "·生成中"
                        RunStatus.FAILED -> "·失败"
                        else -> "·待生成"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (card.status == RunStatus.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/** 生成类型 → 主题色 */
private fun kindColor(kind: MediaKind): Color = when (kind) {
    MediaKind.IMAGE -> Color(0xFF7C4DFF)
    MediaKind.VIDEO -> Color(0xFF00B8D4)
    MediaKind.AUDIO -> Color(0xFFFFB300)
    MediaKind.TEXT -> Color(0xFF66BB6A)
    else -> Color(0xFF90A4AE)
}