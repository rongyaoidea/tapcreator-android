package com.tapcreator.app.ui.chat

import com.tapcreator.app.data.model.MediaKind

/**
 * 画布布局与命中测试的纯函数集合（不依赖 Compose，可在 JVM 单测中直接验证）。
 *
 * - [layered]：按引用边把节点排成从左到右的 DAG 分层，替代固定网格，让「上游→下游」一眼可读。
 * - [snap]：拖动时的对齐吸附，返回吸附后的坐标与命中的参考线（供画布画参考线）。
 * - [hitTest]：矩形命中测试，供点选/框选复用。
 */
object CanvasLayout {

    data class Node(val id: String, val kind: MediaKind)

    data class Placement(val id: String, val x: Float, val y: Float)

    data class Rect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        fun intersect(other: Rect): Boolean =
            left < other.right && other.left < right && top < other.bottom && other.top < bottom
    }

    data class SnapResult(val x: Float, val y: Float, val guideX: Float?, val guideY: Float?)

    /**
     * DAG 分层布局：
     * 层号 = 从任一入度为 0 的源点出发的最长路径长度（Kahn 拓扑）。
     * 环中的节点（剩余）追加到最后一层之后，避免死循环。
     */
    fun layered(
        nodes: List<Node>,
        edges: List<Pair<String, String>>,
        colW: Float = 200f,
        rowH: Float = 210f,
        originX: Float = 24f,
        originY: Float = 24f,
    ): List<Placement> {
        if (nodes.isEmpty()) return emptyList()
        val ids = nodes.map { it.id }.toSet()
        val validEdges = edges.filter { it.first in ids && it.second in ids && it.first != it.second }
        val outgoing = HashMap<String, MutableList<String>>()
        val indegree = HashMap<String, Int>()
        ids.forEach { indegree[it] = 0 }
        validEdges.forEach { (from, to) ->
            outgoing.getOrPut(from) { mutableListOf() }.add(to)
            indegree[to] = (indegree[to] ?: 0) + 1
        }

        val layer = HashMap<String, Int>()
        val queue = ArrayDeque<String>()
        ids.filter { (indegree[it] ?: 0) == 0 }.sorted().forEach { queue.addLast(it) }
        var processed = 0
        while (queue.isNotEmpty()) {
            val cur = queue.removeFirst()
            processed++
            outgoing[cur].orEmpty().forEach { nxt ->
                layer[nxt] = maxOf(layer[nxt] ?: 0, (layer[cur] ?: 0) + 1)
                val d = (indegree[nxt] ?: 1) - 1
                indegree[nxt] = d
                if (d == 0) queue.addLast(nxt)
            }
        }
        // 环内节点：按 id 稳定排序后追加到最大层之后
        if (processed < ids.size) {
            val maxLayer = layer.values.maxOrNull() ?: 0
            ids.filter { !layer.containsKey(it) }.sorted().forEachIndexed { i, id ->
                layer[id] = maxLayer + 1 + (i / 4)
            }
        }
        // 同层按 kind 再按 id 稳定排序，纵向堆叠
        val byLayer = nodes.groupBy { layer[it.id] ?: 0 }.toSortedMap()
        val out = mutableListOf<Placement>()
        byLayer.forEach { (l, list) ->
            list.sortedWith(compareBy({ it.kind.ordinal }, { it.id })).forEachIndexed { row, n ->
                out += Placement(
                    id = n.id,
                    x = originX + l * colW,
                    y = originY + row * rowH,
                )
            }
        }
        return out
    }

    /** 拖动吸附：目标坐标与其他节点某个轴对齐（间距 < 阈值）时吸附，并返回参考线坐标。 */
    fun snap(
        x: Float,
        y: Float,
        selfId: String,
        others: List<Triple<String, Float, Float>>,
        threshold: Float = 8f,
    ): SnapResult {
        var bestX: Float? = null
        var bestY: Float? = null
        var dx = Float.MAX_VALUE
        var dy = Float.MAX_VALUE
        others.forEach { (id, ox, oy) ->
            if (id == selfId) return@forEach
            val ddx = kotlin.math.abs(ox - x)
            if (ddx < threshold && ddx < dx) {
                dx = ddx; bestX = ox
            }
            val ddy = kotlin.math.abs(oy - y)
            if (ddy < threshold && ddy < dy) {
                dy = ddy; bestY = oy
            }
        }
        return SnapResult(bestX ?: x, bestY ?: y, bestX, bestY)
    }

    /** 矩形命中测试（节点框与选择框相交即命中） */
    fun hitTest(selection: Rect, nodes: List<Pair<String, Rect>>): List<String> =
        nodes.filter { it.second.intersect(selection) }.map { it.first }
}
