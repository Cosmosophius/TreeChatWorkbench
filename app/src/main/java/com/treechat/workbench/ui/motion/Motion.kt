package com.treechat.workbench.ui.motion

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.delay
import kotlin.math.min

/**
 * 统一的运动参数。
 *
 * 把曲线集中在一处，是为了让整个 App 的"手感"一致 ——
 * 零散的 spring(dampingRatio = ...) 调用会让不同组件的回弹力度互不协调，
 * 这正是界面显得"不灵动"的常见原因之一。
 */
object Motion {
    /** 列表项错峰入场的间隔。40ms 左右接近人眼能分辨但不觉拖沓的下限。 */
    const val STAGGER_MS = 40L

    /** 最多累加几档错峰 —— 超过之后同时入场，避免长列表末尾等待过久。 */
    const val MAX_STAGGER_STEPS = 6

    /** 常规过渡：略带阻尼，用于尺寸与位置变化。 */
    fun <T> smooth() = spring<T>(
        dampingRatio = 0.82f,
        stiffness = Spring.StiffnessMediumLow
    )

    /** 弹跳过渡：用于"出现/生长"这类需要一点生命力的场景。 */
    fun <T> bouncy() = spring<T>(
        dampingRatio = 0.46f,
        stiffness = Spring.StiffnessMedium
    )
}

/**
 * 入场动画：轻微上浮 + 淡入，并按 index 错峰。
 *
 * 之所以要按 index 错峰而不是全部同时淡入，是因为同时出现会让整屏
 * 视觉重量一次性砸下来，缺少"逐条铺开"的节奏感。
 */
@Composable
fun Modifier.staggeredAppear(
    index: Int,
    delayPerItemMs: Long = Motion.STAGGER_MS,
    riseDistance: Float = 22f
): Modifier {
    var visible by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val steps = min(index.coerceAtLeast(0), Motion.MAX_STAGGER_STEPS)
        if (steps > 0) delay(steps * delayPerItemMs)
        visible = true
    }

    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = 240),
        label = "staggerAlpha"
    )
    val translationY by animateFloatAsState(
        targetValue = if (visible) 0f else riseDistance,
        animationSpec = spring(
            dampingRatio = 0.72f,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "staggerOffset"
    )

    return this.graphicsLayer {
        this.alpha = alpha
        this.translationY = translationY
    }
}
