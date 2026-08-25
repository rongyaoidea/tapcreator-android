package com.tapcreator.app.ui.nav

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/**
 * 极简线条图标集：手绘 Canvas 线条，无填充，仅描边。
 * 用于底部导航栏，替代 emoji 文本图标。
 */

@Composable
fun LineIconHome(
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 24.dp,
    tint: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val strokeWidth = w * 0.07f
        // 房屋：三角顶 + 矩形底
        val p = drawContext.canvas
        // 屋顶三角
        drawLine(tint, Offset(w * 0.2f, h * 0.45f), Offset(w * 0.5f, h * 0.15f), strokeWidth, StrokeCap.Round)
        drawLine(tint, Offset(w * 0.5f, h * 0.15f), Offset(w * 0.8f, h * 0.45f), strokeWidth, StrokeCap.Round)
        // 屋身矩形
        drawLine(tint, Offset(w * 0.25f, h * 0.45f), Offset(w * 0.25f, h * 0.85f), strokeWidth, StrokeCap.Round)
        drawLine(tint, Offset(w * 0.75f, h * 0.45f), Offset(w * 0.75f, h * 0.85f), strokeWidth, StrokeCap.Round)
        drawLine(tint, Offset(w * 0.25f, h * 0.85f), Offset(w * 0.75f, h * 0.85f), strokeWidth, StrokeCap.Round)
        drawLine(tint, Offset(w * 0.25f, h * 0.45f), Offset(w * 0.75f, h * 0.45f), strokeWidth, StrokeCap.Round)
    }
}

@Composable
fun LineIconLibrary(
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 24.dp,
    tint: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val strokeWidth = w * 0.07f
        // 文件夹：梯形顶 + 矩形底
        drawLine(tint, Offset(w * 0.15f, h * 0.3f), Offset(w * 0.4f, h * 0.3f), strokeWidth, StrokeCap.Round)
        drawLine(tint, Offset(w * 0.4f, h * 0.3f), Offset(w * 0.5f, h * 0.2f), strokeWidth, StrokeCap.Round)
        drawLine(tint, Offset(w * 0.5f, h * 0.2f), Offset(w * 0.85f, h * 0.2f), strokeWidth, StrokeCap.Round)
        drawLine(tint, Offset(w * 0.85f, h * 0.2f), Offset(w * 0.85f, h * 0.8f), strokeWidth, StrokeCap.Round)
        drawLine(tint, Offset(w * 0.85f, h * 0.8f), Offset(w * 0.15f, h * 0.8f), strokeWidth, StrokeCap.Round)
        drawLine(tint, Offset(w * 0.15f, h * 0.8f), Offset(w * 0.15f, h * 0.3f), strokeWidth, StrokeCap.Round)
    }
}

@Composable
fun LineIconSettings(
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 24.dp,
    tint: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val cx = w * 0.5f
        val cy = h * 0.5f
        val strokeWidth = w * 0.06f
        // 齿轮：外圆 + 内圆 + 8 根辐条
        val outerR = w * 0.35f
        val innerR = w * 0.15f
        // 外圆
        drawCircle(tint, outerR, Offset(cx, cy), style = Stroke(width = strokeWidth))
        // 内圆
        drawCircle(tint, innerR, Offset(cx, cy), style = Stroke(width = strokeWidth))
        // 8 根辐条
        for (i in 0..7) {
            val angle = i * 45f
            val rad = Math.toRadians(angle.toDouble()).toFloat()
            val innerEdge = innerR + strokeWidth * 0.5f
            val outerEdge = outerR - strokeWidth * 0.5f
            drawLine(
                tint,
                Offset(cx + innerEdge * kotlin.math.cos(rad), cy + innerEdge * kotlin.math.sin(rad)),
                Offset(cx + outerEdge * kotlin.math.cos(rad), cy + outerEdge * kotlin.math.sin(rad)),
                strokeWidth,
                StrokeCap.Round,
            )
        }
    }
}
