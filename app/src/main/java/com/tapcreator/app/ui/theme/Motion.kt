package com.tapcreator.app.ui.theme

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ══════════════════════════════════════════════════════════════════
// 迪士尼动画原则落地
// ══════════════════════════════════════════════════════════════════

/**
 * 统一动效规格：时长/缓动集中管理，避免各处魔法数字不一致（Material 3 motion tokens 语义命名）。
 *  - fast(180ms)：状态切换、按压缩放、淡出
 *  - medium(280ms)：展开/收起、列表项进出、面板上滑
 * 缓动统一 FastOutSlowInEasing（缓入缓出），退出用 FastOutLinearInEasing（快速离场）。
 */
object MotionSpec {
    /** 快节奏时长（毫秒） */
    const val FastMs = 180

    /** 中节奏时长（毫秒） */
    const val MediumMs = 280

    /** 附加信息/引用行的展开淡入（原则 2 缓入缓出） */
    fun expandEnter(): EnterTransition =
        expandVertically(expandFrom = Alignment.Top) + fadeIn(tween(MediumMs, easing = FastOutSlowInEasing))

    /** 附加信息/引用行的收起淡出（原则 5 跟随：退出比进入快） */
    fun expandExit(): ExitTransition =
        shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut(tween(FastMs, easing = FastOutLinearInEasing))

    /** 全屏面板/弹层自底部上滑进入（原则 6 关键姿势） */
    fun slideUpEnter(offsetDivisor: Int = 8): EnterTransition =
        slideInVertically { it / offsetDivisor } + fadeIn(tween(MediumMs, easing = FastOutSlowInEasing))
}

/**
 * 弹簧按压反馈（原则 1 弹簧不设时长 + 原则 4 夸张：低阻尼冲过头再回弹）。
 * 按下时缩放到 0.92，松开弹回 1.0 时有轻微过冲。
 * 用法：给可点击元素加 .pressSpring(interactionSource) 即可，需与 clickable 共享同一 source。
 */
fun Modifier.pressSpring(
    interactionSource: androidx.compose.foundation.interaction.InteractionSource,
    pressedScale: Float = 0.92f,
): Modifier = composed {
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) pressedScale else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "pressScale",
    )
    this.scale(scale)
}

/**
 * 入场动画：缩放 + 淡入（原则 6 关键姿势 + 原则 2 缓入缓出）。
 * 元素从 0.8 缩放 + 0 透明度 → 1.0 + 1.0，用 tween 缓入缓出曲线。
 * @param delayMs 延迟入场（原则 5 跟随与重叠：附属部分延迟，避免同时运动生硬）
 */
@Composable
fun enterAnimation(
    delayMs: Int = 0,
): Modifier {
    val scaleAnim = remember { Animatable(0.8f) }
    val alphaAnim = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        if (delayMs > 0) delay(delayMs.toLong())
        coroutineScope {
            launch { scaleAnim.animateTo(1f, tween(300)) }
            launch { alphaAnim.animateTo(1f, tween(300)) }
        }
    }
    return Modifier
        .scale(scaleAnim.value)
        .alpha(alphaAnim.value)
}
