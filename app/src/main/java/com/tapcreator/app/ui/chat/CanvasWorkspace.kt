package com.tapcreator.app.ui.chat

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Redo
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material.icons.filled.Warning
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
import androidx.compose.material3.Icon
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
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
import com.tapcreator.app.data.model.NodeParams
import com.tapcreator.app.data.model.RunStatus
import com.tapcreator.app.ui.theme.kindColor
import com.tapcreator.app.ui.theme.kindColorStatic
import com.tapcreator.app.ui.theme.kindIcon
import com.tapcreator.app.ui.theme.pressSpring
import com.tapcreator.app.ui.theme.enterAnimation
import java.io.File
import kotlin.math.max
import kotlin.math.min

/** 画布节点窗宽（dp） */
private val NODE_W = 160.dp

/** 节点估算高度（dp）：用于小地图与视口剔除 */
private const val NODE_H_GUESS = 150f
private const val MIN_SCALE = 0.3f
private const val MAX_SCALE = 4f

/**
 * 可缩放平移的创作画布（节点式工作台）：
 *  - 平移/缩放：空白拖动平移、双指捏合缩放（节点布局基于 card.x/y 持久坐标）
 *  - 连线：从节点右侧输出端口拖到另一节点，按类型矩阵校验后写入 CardLinkEntity
 *  - 节点菜单：节点右上角「⋯」打开（重跑/变体/参数/删除等）
 *  - 框选：工具栏切换「框选」后在空白拖拽橡皮筋批量选中
 *  - 对齐：拖动时与其它节点对齐吸附并显示参考线
 *  - 过滤：filterKind 只显示该类型节点（按生成类型着色 kindColor）
 *  - 小地图：左下角缩略图 + 视口框
 *  - 视口剔除：仅组合可见范围内的节点，支撑大画布性能
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
    onOpenPreview: (CardEntity) -> Unit,
    onOpenNodeMenu: (CardEntity) -> Unit,
    onRunNode: (CardEntity) -> Unit,
    onConnect: (fromId: String, toId: String) -> Unit,
    onDoubleTapEmpty: () -> Unit,
    onOpenCanvasMenu: () -> Unit,
    onRerunSelected: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    canUndo: Boolean,
    canRedo: Boolean,
    canConnect: (from: CardEntity, to: CardEntity) -> Boolean,
    isCharacterNode: (CardEntity) -> Boolean,
    onCommitPositions: (updates: List<Pair<String, Pair<Float, Float>>>) -> Unit,
    onToggleFilter: (MediaKind?) -> Unit,
    onAutoLayout: () -> Unit,
    onDeleteSelected: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val nodeW = with(density) { NODE_W.toPx() }
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    // 供 Canvas drawScope 使用的颜色（drawScope 内不能调 @Composable）
    val tertiaryColor = MaterialTheme.colorScheme.tertiary
    val primaryColor = MaterialTheme.colorScheme.primary
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    var scale by remember { mutableStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    val drags = remember { androidx.compose.runtime.mutableStateMapOf<String, Offset>() }
    val snapOver = remember { androidx.compose.runtime.mutableStateMapOf<String, Offset>() }
    val nodeSizes = remember { androidx.compose.runtime.mutableStateMapOf<String, IntSize>() }
    var multiSelect by remember { mutableStateOf(false) }
    var selectMode by remember { mutableStateOf(false) }
    var rectStart by remember { mutableStateOf<Offset?>(null) }
    var rectEnd by remember { mutableStateOf<Offset?>(null) }
    var connectFrom by remember { mutableStateOf<String?>(null) }
    var connectPoint by remember { mutableStateOf<Offset?>(null) }
    var guides by remember { mutableStateOf<Pair<Float?, Float?>?>(null) }

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

    fun measureSizeOf(id: String): IntSize = sizeOf(id)

    val gestureModifier: Modifier = if (selectMode) {
        Modifier.pointerInput(cards, selectMode) {
            detectDragGestures(
                onDragStart = { rectStart = it; rectEnd = it },
                onDragEnd = {
                    val a = rectStart
                    val b = rectEnd
                    if (a != null && b != null) {
                        val c1 = containerOf(a)
                        val c2 = containerOf(b)
                        val rect = CanvasLayout.Rect(
                            min(c1.x, c2.x), min(c1.y, c2.y),
                            max(c1.x, c2.x), max(c1.y, c2.y),
                        )
                        val nodeRects = cards
                            .filter { filterKind == null || it.kind == filterKind }
                            .map { c ->
                                val p = basePos(c)
                                val s = measureSizeOf(c.id)
                                c.id to CanvasLayout.Rect(p.x, p.y, p.x + s.width.toFloat(), p.y + s.height.toFloat())
                            }
                        CanvasLayout.hitTest(rect, nodeRects).forEach { onToggleNode(it, true) }
                    }
                    rectStart = null
                    rectEnd = null
                },
                onDragCancel = { rectStart = null; rectEnd = null },
            ) { change, _ ->
                rectEnd = change.position
                change.consume()
            }
        }
    } else {
        Modifier.pointerInput(cards) {
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
    }

    Box(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.18f))
            .onSizeChanged { viewport = it }
            .then(gestureModifier)
            .pointerInput(cards) {
                detectTapGestures(
                    onTap = { pos ->
                        val hit = hitNode(containerOf(pos))
                        if (hit == null) {
                            multiSelect = false
                            onClearSelection()
                        }
                    },
                    onDoubleTap = { pos ->
                        val hit = hitNode(containerOf(pos))
                        if (hit == null && !selectMode) onDoubleTapEmpty()
                    },
                )
            },
    ) {
        // 关系边 + 连线预览 + 对齐参考线
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
                val color = if (link.role == "parent") {
                    tertiaryColor
                } else {
                    kindColorStatic(from.kind, dark)
                }
                drawPath(path = path, color = color.copy(alpha = 0.55f), style = Stroke(width = 2f))
            }
            // 拖拽连线预览
            val cf = connectFrom
            val cp = connectPoint
            if (cf != null && cp != null) {
                val card = cards.firstOrNull { it.id == cf }
                if (card != null) {
                    val s = sizeOf(card.id)
                    val a = screenPos(basePos(card)) + Offset(s.width.toFloat(), s.height / 2f) * scale
                    val b = screenPos(cp)
                    drawLine(
                        color = primaryColor.copy(alpha = 0.8f),
                        start = a,
                        end = b,
                        strokeWidth = 2f,
                    )
                }
            }
            // 对齐参考线
            guides?.let { (gx, gy) ->
                gx?.let {
                    val x = screenPos(Offset(it, 0f)).x
                    drawLine(Color(0xFF64B5F6), Offset(x, 0f), Offset(x, size.height), strokeWidth = 1.5f)
                }
                gy?.let {
                    val y = screenPos(Offset(0f, it)).y
                    drawLine(Color(0xFF64B5F6), Offset(0f, y), Offset(size.width, y), strokeWidth = 1.5f)
                }
            }
            // 橡皮筋选择框
            val a = rectStart
            val b = rectEnd
            if (selectMode && a != null && b != null) {
                val left = min(a.x, b.x)
                val top = min(a.y, b.y)
                drawRect(
                    color = Color(0x3364B5F6),
                    topLeft = Offset(left, top),
                    size = Size(kotlin.math.abs(b.x - a.x), kotlin.math.abs(b.y - a.y)),
                )
                drawRect(
                    color = Color(0xFF64B5F6),
                    topLeft = Offset(left, top),
                    size = Size(kotlin.math.abs(b.x - a.x), kotlin.math.abs(b.y - a.y)),
                    style = Stroke(width = 1.5f),
                )
            }
        }

        // 节点（视口剔除：只组合可见范围）
        for ((idx, card) in cards.withIndex()) {
            if (filterKind != null && card.kind != filterKind) continue
            val drag = drags[card.id] ?: Offset.Zero
            val base = snapOver[card.id] ?: (basePos(card) + drag * density.density)
            val selected = card.id in selectedIds
            val isDraft = card.runId == "draft"
            val failedBlank = isDraft && card.status == RunStatus.FAILED
            val s = sizeOf(card.id)
            val sp = screenPos(base)
            val margin = 240f
            val visible = sp.x > -margin - s.width * scale &&
                sp.x < viewport.width.toFloat() + margin &&
                sp.y > -margin - s.height * scale &&
                sp.y < viewport.height.toFloat() + margin
            if (!visible) continue
            CanvasNode(
                card = card,
                selected = selected,
                isDraft = isDraft,
                isCharacter = isCharacterNode(card),
                connectOrigin = connectFrom,
                enterDelayMs = idx * 50,
                basePx = base,
                scale = scale,
                pan = pan,
                onSize = { sz -> nodeSizes[card.id] = sz },
                onClick = {
                    if (isDraft) {
                        multiSelect = false
                        onOpenDraft(card.id, card.kind)
                    } else if (multiSelect) {
                        onToggleNode(card.id, true)
                    } else {
                        onOpenPreview(card)
                    }
                },
                onLongClick = {
                    if (!isDraft || failedBlank) {
                        multiSelect = true
                        onToggleNode(card.id, true)
                    }
                },
                onMenu = { onOpenNodeMenu(card) },
                onRun = if (!isDraft && card.runId != "film" && !isCharacterNode(card) &&
                    NodeParams.fromJson(card.paramsJson)?.prompt?.isNotBlank() == true
                ) {
                    { onRunNode(card) }
                } else null,
                onDoubleTap = {
                    val p = basePos(card)
                    val sz = sizeOf(card.id)
                    scale = 1.5f
                    pan = Offset(
                        viewport.width / 2f - (p.x + sz.width / 2f) * scale,
                        viewport.height / 2f - (p.y + sz.height / 2f) * scale,
                    )
                    if (isDraft) {
                        multiSelect = false
                        onOpenDraft(card.id, card.kind)
                    } else {
                        onDoubleTapNode(card.id)
                        onToggleNode(card.id, false)
                    }
                },
                onMoveStart = { drags[card.id] = Offset.Zero },
                onMove = { deltaPx ->
                    val deltaDp = Offset(deltaPx.x / (scale * density.density), deltaPx.y / (scale * density.density))
                    val grp = if (selected) selectedIds else setOf(card.id)
                    grp.forEach { id -> drags[id] = (drags[id] ?: Offset.Zero) + deltaDp }
                    // 单节点拖动：与其它节点对齐吸附（阈值 8dp），显示参考线
                    if (grp.size == 1) {
                        val cur = basePos(card) + (drags[card.id] ?: Offset.Zero) * density.density
                        val others = cards
                            .filter { it.id != card.id && (filterKind == null || it.kind == filterKind) }
                            .map { Triple(it.id, it.x * density.density, it.y * density.density) }
                        val snap = CanvasLayout.snap(cur.x, cur.y, card.id, others, threshold = 8f * density.density)
                        snapOver[card.id] = Offset(snap.x, snap.y)
                        guides = snap.guideX to snap.guideY
                    }
                },
                onMoveEnd = {
                    val grp = if (selected) selectedIds else setOf(card.id)
                    val updates = grp.mapNotNull { id ->
                        val c = cards.firstOrNull { it.id == id } ?: return@mapNotNull null
                        val over = snapOver[id]
                        if (over != null) {
                            id to ((over.x / density.density) to (over.y / density.density))
                        } else {
                            val d = drags[id] ?: return@mapNotNull null
                            id to ((c.x + d.x) to (c.y + d.y))
                        }
                    }
                    onCommitPositions(updates)
                    grp.forEach { drags.remove(it) }
                    snapOver.clear()
                    guides = null
                },
            )
        }

        // 连线端口拖拽：由节点端口回调驱动
        CanvasPortLayer(
            cards = cards,
            connectFrom = connectFrom,
            scale = scale,
            pan = pan,
            basePos = { c -> basePos(c) },
            sizeOf = { id -> sizeOf(id) },
            viewport = viewport,
            onConnectStart = { id -> connectFrom = id },
            onConnectMove = { pt -> connectPoint = pt },
            onConnectDrop = { from, endContainer ->
                val target = hitNode(endContainer)
                if (target != null && target != from) {
                    val fromCard = cards.firstOrNull { it.id == from }
                    val toCard = cards.firstOrNull { it.id == target }
                    if (fromCard != null && toCard != null && canConnect(fromCard, toCard)) {
                        onConnect(from, target)
                    }
                }
                connectFrom = null
                connectPoint = null
            },
            onConnectCancel = { connectFrom = null; connectPoint = null },
        )

        CanvasToolbar(
            filterKind = filterKind,
            hasSelection = selectedIds.isNotEmpty(),
            selectMode = selectMode,
            canUndo = canUndo,
            canRedo = canRedo,
            onToggleFilter = onToggleFilter,
            onAutoLayout = onAutoLayout,
            onUndo = onUndo,
            onRedo = onRedo,
            onRerunSelected = onRerunSelected,
            onDelete = {
                onDeleteSelected()
                multiSelect = false
            },
            onToggleSelectMode = {
                selectMode = !selectMode
                if (!selectMode) {
                    rectStart = null
                    rectEnd = null
                }
            },
            onOpenCanvasMenu = onOpenCanvasMenu,
        )

        // 小地图
        if (cards.size >= 4) {
            CanvasMinimap(
                cards = cards,
                filterKind = filterKind,
                scale = scale,
                pan = pan,
                densityPx = density.density,
                viewport = viewport,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(12.dp)
                    .size(120.dp, 84.dp),
            )
        }

        // 缩放控制
        Row(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ZoomButton("zoom_in") { scale = (scale * 1.25f).coerceAtMost(MAX_SCALE) }
            ZoomButton("zoom_out") { scale = (scale / 1.25f).coerceAtLeast(MIN_SCALE) }
            ZoomButton("reset") {
                scale = 1f
                pan = Offset.Zero
            }
        }
    }
}

/**
 * 端口拖拽层：在可见节点左右两侧渲染输入/输出端口。
 * 端口上的拖拽只负责连线，不移动节点（消费事件，避免冒泡到节点拖动）。
 */
@Composable
private fun CanvasPortLayer(
    cards: List<CardEntity>,
    connectFrom: String?,
    scale: Float,
    pan: Offset,
    basePos: (CardEntity) -> Offset,
    sizeOf: (String) -> IntSize,
    viewport: IntSize,
    onConnectStart: (String) -> Unit,
    onConnectMove: (Offset) -> Unit,
    onConnectDrop: (String, Offset) -> Unit,
    onConnectCancel: () -> Unit,
) {
    for (card in cards) {
        val s = sizeOf(card.id)
        val base = basePos(card)
        val screen = Offset(base.x * scale + pan.x, base.y * scale + pan.y)
        // 视口剔除：屏幕外节点不渲染端口
        if (screen.x < -240f - s.width * scale || screen.x > viewport.width + 240f ||
            screen.y < -240f || screen.y > viewport.height + 240f
        ) continue
        // 输出端口在「屏幕」上的中心点
        val portCenter = Offset(screen.x + s.width * scale, screen.y + s.height * scale / 2f)
        Box(
            modifier = Modifier
                .offset { IntOffset((portCenter.x - 24f).toInt(), (portCenter.y - 24f).toInt()) }
                .size(48.dp)
                .pointerInput(card.id, scale, pan.x, pan.y, s, base) {
                    var acc = Offset.Zero
                    detectDragGestures(
                        onDragStart = {
                            acc = Offset.Zero
                            onConnectStart(card.id)
                            onConnectMove(Offset((portCenter.x - pan.x) / scale, (portCenter.y - pan.y) / scale))
                        },
                        onDragEnd = {
                            val scr = portCenter + acc
                            onConnectDrop(card.id, Offset((scr.x - pan.x) / scale, (scr.y - pan.y) / scale))
                        },
                        onDragCancel = { onConnectCancel() },
                    ) { change, amount ->
                        acc += amount
                        val scr = portCenter + acc
                        onConnectMove(Offset((scr.x - pan.x) / scale, (scr.y - pan.y) / scale))
                        change.consume()
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(14.dp)
                    .background(
                        if (connectFrom == card.id) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
                        CircleShape,
                    )
                    .border(1.dp, MaterialTheme.colorScheme.surface, CircleShape),
            )
        }
    }
}

@Composable
private fun CanvasMinimap(
    cards: List<CardEntity>,
    filterKind: MediaKind?,
    scale: Float,
    pan: Offset,
    densityPx: Float,
    viewport: IntSize,
    modifier: Modifier = Modifier,
) {
    val shown = cards.filter { filterKind == null || it.kind == filterKind }
    if (shown.isEmpty()) return
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
        tonalElevation = 2.dp,
    ) {
        Canvas(modifier = Modifier.fillMaxSize().padding(6.dp)) {
            val minX = shown.minOf { it.x } - 40f
            val maxX = shown.maxOf { it.x } + NODE_W.value + 40f
            val minY = shown.minOf { it.y } - 40f
            val maxY = shown.maxOf { it.y } + NODE_H_GUESS + 40f
            val spanX = (maxX - minX).coerceAtLeast(1f)
            val spanY = (maxY - minY).coerceAtLeast(1f)
            val k = min(size.width / spanX, size.height / spanY)
            fun mx(x: Float): Float = (x - minX) * k
            fun my(y: Float): Float = (y - minY) * k

            shown.forEach { c ->
                drawRoundRect(
                    color = kindColorStatic(c.kind, false).copy(alpha = 0.8f),
                    topLeft = Offset(mx(c.x), my(c.y)),
                    size = Size(NODE_W.value * k, NODE_H_GUESS * k),
                    cornerRadius = CornerRadius(2f),
                )
            }
            // 视口框（容器 dp 坐标）
            val vx = -pan.x / (scale * densityPx)
            val vy = -pan.y / (scale * densityPx)
            val vw = viewport.width / (scale * densityPx)
            val vh = viewport.height / (scale * densityPx)
            drawRect(
                color = Color(0xFF64B5F6),
                topLeft = Offset(mx(vx), my(vy)),
                size = Size(vw * k, vh * k),
                style = Stroke(width = 1.5f),
            )
        }
    }
}

@Composable
private fun ZoomButton(text: String, onClick: () -> Unit) {
    val icon = when (text) {
        "zoom_in" -> Icons.Filled.Add
        "zoom_out" -> Icons.Filled.Remove
        "reset" -> Icons.Filled.CenterFocusStrong
        else -> Icons.Filled.Add
    }
    val desc = when (text) {
        "zoom_in" -> "放大"
        "zoom_out" -> "缩小"
        "reset" -> "重置视图"
        else -> text
    }
    val interactionSource = androidx.compose.runtime.remember { MutableInteractionSource() }
    Surface(
        onClick = onClick,
        modifier = Modifier
            .size(40.dp)
            .pressSpring(interactionSource),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
        tonalElevation = 3.dp,
        interactionSource = interactionSource,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = desc,
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun CanvasToolbar(
    filterKind: MediaKind?,
    hasSelection: Boolean,
    selectMode: Boolean,
    canUndo: Boolean,
    canRedo: Boolean,
    onToggleFilter: (MediaKind?) -> Unit,
    onAutoLayout: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onRerunSelected: () -> Unit,
    onDelete: () -> Unit,
    onToggleSelectMode: () -> Unit,
    onOpenCanvasMenu: () -> Unit,
) {
    val kinds = listOf<MediaKind?>(null, MediaKind.IMAGE, MediaKind.VIDEO, MediaKind.AUDIO)
    val labels = mapOf<MediaKind?, String>(
        null to "全部", MediaKind.IMAGE to "图", MediaKind.VIDEO to "视频",
        MediaKind.AUDIO to "音频",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
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
        ToolbarIcon(
            icon = Icons.Filled.CropFree,
            desc = "框选",
            active = selectMode,
            onClick = onToggleSelectMode,
        )
        TooltipText(onAutoLayout, "整理")
        ToolbarIcon(
            icon = Icons.Filled.Undo,
            desc = "撤销",
            active = false,
            enabled = canUndo,
            onClick = onUndo,
        )
        ToolbarIcon(
            icon = Icons.Filled.Redo,
            desc = "重做",
            active = false,
            enabled = canRedo,
            onClick = onRedo,
        )
        if (hasSelection) {
            TooltipText(onRerunSelected, "重跑选中")
            TooltipText(onDelete, "删除选中")
        }
        ToolbarIcon(
            icon = Icons.Filled.MoreVert,
            desc = "更多",
            active = false,
            onClick = onOpenCanvasMenu,
        )
    }
}

@Composable
private fun ToolbarIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    desc: String,
    active: Boolean,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    Surface(
        onClick = { if (enabled) onClick() },
        shape = CircleShape,
        color = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = desc,
            tint = (if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
                .copy(alpha = if (enabled) 1f else 0.35f),
            modifier = Modifier.padding(6.dp).size(18.dp),
        )
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
    isCharacter: Boolean,
    connectOrigin: String?,
    enterDelayMs: Int = 0,
    basePx: Offset,
    scale: Float,
    pan: Offset,
    onSize: (IntSize) -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onMenu: () -> Unit,
    onRun: (() -> Unit)?,
    onDoubleTap: () -> Unit,
    onMoveStart: () -> Unit,
    onMove: (Offset) -> Unit,
    onMoveEnd: () -> Unit,
) {
    val screen = Offset(basePx.x * scale + pan.x, basePx.y * scale + pan.y)
    val shape = RoundedCornerShape(12.dp)
    val borderModifier = when {
        selected -> Modifier.border(2.dp, kindColor(card.kind), shape)
        isCharacter -> Modifier.border(2.dp, MaterialTheme.colorScheme.tertiary, shape)
        isDraft -> Modifier.border(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.6f), shape)
        else -> Modifier.border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f), shape)
    }
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
                .then(borderModifier),
            shape = shape,
            color = if (selected) kindColor(card.kind).copy(alpha = 0.14f) else MaterialTheme.colorScheme.surface,
            tonalElevation = if (selected) 6.dp else 2.dp,
            shadowElevation = if (selected) 6.dp else 2.dp,
        ) {
            Box {
                CanvasNodeContent(
                    card = card,
                    selected = selected,
                    isDraft = isDraft,
                    isCharacter = isCharacter,
                    modifier = enterAnimation(delayMs = enterDelayMs),
                )
                // 节点操作入口：运行 + 更多
                Row(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    if (onRun != null) {
                        Surface(
                            onClick = onRun,
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.9f),
                            modifier = Modifier.size(22.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.PlayArrow,
                                contentDescription = "运行",
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(2.dp),
                            )
                        }
                    }
                    Surface(
                        onClick = onMenu,
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                        modifier = Modifier.size(22.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.MoreVert,
                            contentDescription = "节点操作",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(2.dp),
                        )
                    }
                }
            }
        }
        // 输入端口（左）
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .offset(x = (-5).dp)
                .size(10.dp)
                .background(MaterialTheme.colorScheme.outline, CircleShape),
        )
        // 输出端口（右）：连线拖拽由 CanvasPortLayer 负责
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .offset(x = 5.dp)
                .size(10.dp)
                .background(
                    if (connectOrigin == card.id) MaterialTheme.colorScheme.tertiary else kindColor(card.kind),
                    CircleShape,
                ),
        )
    }
}

@Composable
private fun CanvasNodeContent(
    card: CardEntity,
    selected: Boolean,
    isDraft: Boolean,
    isCharacter: Boolean,
    modifier: Modifier = Modifier,
) {
    val params = NodeParams.fromJson(card.paramsJson)
    Column(modifier = modifier.fillMaxWidth()) {
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
                isCharacter -> Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Person,
                        contentDescription = "角色",
                        tint = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.size(30.dp),
                    )
                    Text(
                        text = "角色锚点",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
                isDraft -> {
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
                            Icon(
                                imageVector = Icons.Filled.Warning,
                                contentDescription = "失败",
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(28.dp),
                            )
                            Text(
                                text = "生成失败",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        else -> Icon(
                            imageVector = kindIcon(card.kind),
                            contentDescription = card.kind.name,
                            tint = kindColor(card.kind),
                            modifier = Modifier.size(28.dp),
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
                else -> Icon(
                    imageVector = kindIcon(card.kind),
                    contentDescription = card.kind.name,
                    tint = kindColor(card.kind),
                    modifier = Modifier.size(32.dp),
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
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            if (card.variantOf != null) {
                Text(
                    text = "v${card.version}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
            if (!isDraft && card.promptEnhanced) {
                Icon(
                    imageVector = Icons.Filled.AutoAwesome,
                    contentDescription = "增强",
                    tint = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.size(14.dp),
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
        // 参数摘要角标：模型 / 比例 / 分辨率 / 时长
        params?.summary()?.takeIf { it.isNotBlank() }?.let { summary ->
            Text(
                text = summary,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 8.dp, end = 8.dp, bottom = 6.dp),
            )
        }
    }
}

// kindColor 已迁移至 ui/theme/Theme.kt，从主题读取并自动适配深色模式
