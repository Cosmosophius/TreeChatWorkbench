package com.treechat.workbench.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.launch
import kotlin.math.abs

/** 滑走的方向。 */
enum class SwipeDirection { LEFT, RIGHT }

/**
 * 可滑动的卡片堆（单词本 / 收藏夹的展示模式）。
 *
 * 交互与动效：
 *  1. **双向滑动**：手指向左滑动（deltaX < -threshold）切换至下一张；
 *     手指向右滑动（deltaX > threshold）返回上一张，绝不永久丢失卡片；
 *  2. **可见的层叠底层**：把后几张按固定间距上移、缩小、降透明度画出装饰底板；
 *  3. **点击展开**：点顶层卡片或操作按钮放大进入全屏详情，背景渐隐，点遮罩或关闭按钮收回。
 */
@Composable
fun <T> SwipeableCardStack(
    items: List<T>,
    modifier: Modifier = Modifier,
    /** 可见的堆叠层数（不含顶层卡片本身）。 */
    stackDepth: Int = 2,
    /** 每深一层的上移量：露出的"边"有多宽。 */
    stackPeek: androidx.compose.ui.unit.Dp = 12.dp,
    /** 每深一层的缩放。 */
    stackScaleStep: Float = 0.045f,
    /**
     * 手指左滑（下一张）或右滑（上一张）飞出后的回调。
     * - LEFT: 向左滑出（下一张）
     * - RIGHT: 向右滑出（上一张）
     */
    onSwiped: (SwipeDirection, T) -> Unit = { _, _ -> },
    /** 右滑返回上一张时的专用回调（可选）。 */
    onSwipeBack: ((T) -> Unit)? = null,
    /** 点开卡片后的详情内容；为 null 表示不提供展开。附带关闭回调。 */
    expandedContent: (@Composable (T, onCollapse: () -> Unit) -> Unit)? = null,
    /** 卡片本体。附带显式触发展开详情的回调。 */
    card: @Composable (item: T, onExpand: () -> Unit) -> Unit
) {
    if (items.isEmpty()) return

    val scope = rememberCoroutineScope()
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    val flyOutX = (screenWidthDp * 2.5f).toFloat()

    // 顶层卡片的位移与旋转
    val offsetX = remember { Animatable(0f) }
    val offsetY = remember { Animatable(0f) }
    var expandedItem by remember { mutableStateOf<T?>(null) }

    val top = items.first()

    // 数据换了（滑走或切换）就把位移与旋转归零，否则新卡会"带着上一张的偏移"出现
    LaunchedEffect(top) {
        offsetX.snapTo(0f)
        offsetY.snapTo(0f)
    }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        // 关键：卡片本身占满可用空间，若直接上移会跑到容器外、看不见层叠的边。
        // 所以先**在顶部预留出整个堆叠所需的高度**（reserve = 每层露出的边 × 层数），
        // 所有卡片都画在预留区之下，底层卡再向上平移，露出的边就正好落在预留区里。
        val reserve = stackPeek * stackDepth

        // ---- 假堆叠底层 ----
        val plateShape = MaterialTheme.shapes.large
        for (f in stackDepth downTo 1) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = reserve - stackPeek * f)
                    .padding(horizontal = stackPeek * 0.25f * f)
                    .zIndex((stackDepth - f).toFloat())
                    .clip(plateShape)
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, plateShape)
            )
        }

        // ---- 顶层卡片：可拖动、可点击展开 ----
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = reserve)
                .zIndex(stackDepth + 1f)
                .graphicsLayer {
                    translationX = offsetX.value
                    translationY = offsetY.value
                    // 横向拖多少就转多少，留一点阻尼手感
                    rotationZ = (offsetX.value / 55f).coerceIn(-18f, 18f)
                }
                .pointerInput(top, expandedContent) {
                    if (expandedContent != null) {
                        detectTapGestures(
                            onTap = { expandedItem = top }
                        )
                    }
                }
                .pointerInput(top) {
                    detectDragGestures(
                        onDragEnd = {
                            scope.launch {
                                val threshold = flyOutX / 4f
                                if (abs(offsetX.value) < threshold) {
                                    // 不到阈值：回弹（用弹簧而不是线性，收回时更活）
                                    launch { offsetX.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow)) }
                                    launch { offsetY.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow)) }
                                } else {
                                    // 超过阈值：根据左右方向飞出屏幕
                                    // 左滑（offsetX < 0）：下一张
                                    // 右滑（offsetX > 0）：返回上一张
                                    val isRight = offsetX.value > 0
                                    val dir = if (isRight) 1f else -1f
                                    val direction = if (isRight) SwipeDirection.RIGHT else SwipeDirection.LEFT

                                    launch {
                                        offsetX.animateTo(dir * flyOutX, tween(320))
                                        offsetY.animateTo(offsetY.value + 200f, tween(320))
                                        offsetX.snapTo(0f)
                                        offsetY.snapTo(0f)
                                    }

                                    onSwiped(direction, top)
                                    if (isRight) {
                                        onSwipeBack?.invoke(top)
                                    }
                                }
                            }
                        },
                        onDragCancel = {
                            scope.launch {
                                launch { offsetX.animateTo(0f, tween(260)) }
                                launch { offsetY.animateTo(0f, tween(260)) }
                            }
                        }
                    ) { change, drag ->
                        change.consume()
                        scope.launch {
                            launch { offsetX.snapTo(offsetX.value + drag.x) }
                            launch { offsetY.snapTo(offsetY.value + drag.y) }
                        }
                    }
                }
        ) {
            card(top) { expandedItem = top }
        }

        // ---- 展开层：放大进入全屏详情，点遮罩或关闭收回 ----
        if (expandedContent != null) {
            val expanded = expandedItem
            AnimatedVisibility(
                visible = expanded != null,
                enter = fadeIn(tween(180)) + scaleIn(
                    initialScale = 0.92f,
                    animationSpec = spring(dampingRatio = 0.78f, stiffness = Spring.StiffnessLow)
                ),
                exit = fadeOut(tween(150)) + scaleOut(tween(150), targetScale = 0.95f),
                modifier = Modifier.zIndex(stackDepth + 2f)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.42f))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { expandedItem = null },
                    contentAlignment = Alignment.Center
                ) {
                    if (expanded != null) {
                        Surface(
                            modifier = Modifier
                                .fillMaxSize()
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null
                                ) { /* 消费内部点击，防止误触发 scrim 关闭 */ },
                            shape = MaterialTheme.shapes.extraLarge,
                            // 素扉古典宣纸纸色与仿古微边框
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            tonalElevation = 1.dp,
                            shadowElevation = 4.dp
                        ) {
                            expandedContent(expanded) { expandedItem = null }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 兼容旧签名的重载。
 */
@Composable
fun <T> SwipeableCardStack(
    items: List<T>,
    modifier: Modifier = Modifier,
    stackDepth: Int = 2,
    stackPeek: androidx.compose.ui.unit.Dp = 12.dp,
    stackScaleStep: Float = 0.045f,
    onSwiped: (SwipeDirection, T) -> Unit = { _, _ -> },
    onSwipeBack: ((T) -> Unit)? = null,
    expandedContent: (@Composable (T) -> Unit)? = null,
    card: @Composable (T) -> Unit
) = SwipeableCardStack(
    items = items,
    modifier = modifier,
    stackDepth = stackDepth,
    stackPeek = stackPeek,
    stackScaleStep = stackScaleStep,
    onSwiped = onSwiped,
    onSwipeBack = onSwipeBack,
    expandedContent = expandedContent?.let { content -> { item, _ -> content(item) } },
    card = { item, _ -> card(item) }
)
