package com.treechat.workbench.ui.reader

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.cos

/**
 * 翻页动画三档，存进 [ReaderTypographyStore]（键：`reader_page_turn`），
 * 在 Aa 面板里用一排 FilterChip 切换，改动即时生效并被记住。
 *
 * 为什么自己写而不是引第三方：GitHub 上的真·3D 卷页（ReaderPageCurl / paperbook）
 * 全是 C++/OpenGL 实现，与本工程"非底层引擎一律 Kotlin"的约定冲突；
 * Compose 系开源阅读器（book-story / LightNovelReader / Quill）也没有可直接复用的卷页。
 * 于是用 graphicsLayer 的 rotationY + cameraDistance 自己做，效果足以乱真且零新依赖。
 */
enum class PageTurnMode(val label: String, val description: String) {
    OFF(
        label = "关闭",
        description = "不播动画：点按左右分区、方向键与底栏按钮立即换页，也不响应横向拖动"
    ),
    SLIDE(
        label = "滑页",
        description = "整页平移：旧页推走、新页推进来，跟手拖动，过半即完成"
    ),
    CURL(
        label = "仿真翻页",
        description = "旧页绕书脊翻起、露出下一页，带透视与折痕阴影，跟手拖动，过半即完成"
    )
}

/** 点按 / 按键触发翻页时的短动画时长（用户要求"约 250ms"）。 */
private const val TAP_TURN_MILLIS = 250

/** 松手后的收尾动画时长（完成或回弹）。 */
private const val SETTLE_TURN_MILLIS = 220

/** 横向拖动越过页宽的这个比例才算"起翻"，避免手抖被当成翻页。 */
private const val DRAG_START_FRACTION = 0.02f

/** 90° 的弧度值：翻页最大翻到"侧立"，永远不把旧页镜像面露出来。 */
private const val HALF_PI = (Math.PI / 2.0).toFloat()

/** 一次翻页的现场：由点按/按键/拖动三种入口共用（内部类型，不进任何公开签名）。 */
internal class TurnSession(
    /** 被翻走的旧页（基础页号）。 */
    val from: Int,
    /** 底下露出的新页（基础页号）。 */
    val to: Int,
    /** true = 下一页（旧页绕**左**书脊边向后翻）；false = 上一页（绕右书脊边）。 */
    val forward: Boolean,
    /** true = 由点按/按键触发，进场就自己播完；false = 拖动触发，进度完全跟手。 */
    val autoPlay: Boolean
)

/**
 * 翻页动画的现场状态。
 *
 * 刻意把 [progress] 做成**只在绘制阶段读**的普通状态：拖动/动画每帧都在变，
 * 若在组合期读它，整棵正文树每秒会跟着重组 60 次（正文里还有 filter 出来的新列表，
 * 参数不稳定就必然全量重排）。只在 `graphicsLayer` / `drawBehind` 里读，
 * 每帧就只有重绘，没有重组。
 */
@Stable
class PageTurnState {

    /** 当前正在进行的翻页；null = 空闲。 */
    internal var session: TurnSession? by mutableStateOf(null)

    /** 翻页进度 0..1：0 = 纹丝未动，1 = 旧页翻到侧立（此刻提交新页）。 */
    var progress: Float by mutableStateOf(0f)

    /**
     * 开一场翻页。正在翻页时返回 false，调用方直接忽略本次请求 ——
     * 短动画只有 250ms，连点期间吞掉后一次请求比叠两场动画更稳。
     */
    fun start(from: Int, to: Int, forward: Boolean, autoPlay: Boolean): Boolean {
        if (session != null) return false
        session = TurnSession(from, to, forward, autoPlay)
        progress = 0f
        return true
    }

    /** 结束现场（提交或回弹之后调用）。 */
    fun clear() {
        session = null
        progress = 0f
    }

    /**
     * 把进度动画到 [target]。
     *
     * 只在两个地方调用：点按/按键的进场动画（组合期的 LaunchedEffect），
     * 以及拖动收尾（手势协程里）。两者都是挂起上下文，播完才继续。
     */
    internal suspend fun animateTo(target: Float, durationMillis: Int) {
        animate(
            initialValue = progress,
            targetValue = target,
            animationSpec = tween(durationMillis, easing = FastOutSlowInEasing)
        ) { value, _ -> progress = value }
    }
}

/**
 * 读一份翻页现场。[key] 传文档 id：换书必须把上一本没播完的翻页丢掉。
 */
@Composable
fun rememberPageTurnState(key: Any?): PageTurnState = remember(key) { PageTurnState() }

/**
 * 承载正文行的翻页容器：底层放"翻完要停下的那一页"，顶层把旧页翻走。
 *
 * ## 动画怎么做出来的（纯 Compose，无 NDK / OpenGL / 新底层库）
 * - **仿真翻页**：旧页用 `graphicsLayer` 的 `rotationY` 绕书脊边（下一页 = 左边缘、
 *   上一页 = 右边缘）翻到 90°（侧立，投影宽度收缩到 0），透视由 `cameraDistance`
 *   提供（取页宽的 3 倍：看得出纸在翻，自由边又不会被放大到冲出屏幕），翻到
 *   最后 5% 淡出吃掉"侧立成一条线"的细线；旧页的自由边渐暗模拟"纸背吃不到
 *   正面的光"，新页上再叠一道跟随折痕移动的阴影。
 * - **滑页**：旧页推出、新页推进，同一份进度驱动。
 * - **跟手**：横向拖动实时写 [PageTurnState.progress]；松手过半播完、不到半回弹。
 * - **点按/按键**：250ms 短动画播到 1，播完才提交页号（中途翻页现场只有一份，
 *   不会出现"页码已经变了、动画还在放旧页"的错位）。
 *
 * 只有文本渲染路径会挂这个容器；实况选词（PDF/扫描件）仍走即时换页，
 * 见 [AdaptiveDocumentView] 里的说明。
 *
 * @param columns 一次翻页跨几页（电脑模式对开 = 2，扫描件多列同理）。
 * @param content 按**基础页号**渲染整行正文；动画期间会同时要旧页与新页两份。
 */
@Composable
fun PageTurnBox(
    state: PageTurnState,
    mode: PageTurnMode,
    currentPage: Int,
    columns: Int,
    pageCount: Int,
    onPageChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (pageBase: Int) -> Unit
) {
    val scope = rememberCoroutineScope()
    val session = state.session

    /** 提交：页号与翻页现场一起收，同一帧完成，底下那页无缝变成"当前页"。 */
    fun commit(target: Int) {
        onPageChange(target)
        state.clear()
    }

    // 点按/按键触发的进场动画：播满 1 之后提交。播的过程中若被 clear()（改设置、
    // 换书、切到实况选词），LaunchedEffect 会随 session 变 null 而取消，不会提交。
    LaunchedEffect(session) {
        val s = session ?: return@LaunchedEffect
        if (s.autoPlay) {
            state.animateTo(1f, TAP_TURN_MILLIS)
            if (state.session === s) commit(s.to)
        }
    }

    // 兜底：翻页途中页号被外部改掉（书被 OCR 重写、进度被别处推进等），
    // 直接丢掉这场动画显示新页 —— 宁可少放一次动画，也不显示一张不属于当前页的纸。
    LaunchedEffect(currentPage) {
        val s = state.session ?: return@LaunchedEffect
        if (s.from != currentPage) state.clear()
    }

    // ---------- 拖动：跟手进度 + 松手收尾 ----------
    val dragModifier = Modifier.pointerInput(currentPage, columns, pageCount, mode) {
        if (mode == PageTurnMode.OFF) return@pointerInput
        var dragAccum = 0f
        // 动画播放期间按住屏幕不接管，否则拖拽会和正在播的动画抢同一份进度。
        var accepting = false
        detectHorizontalDragGestures(
            onDragStart = {
                dragAccum = 0f
                accepting = state.session == null
            },
            onHorizontalDrag = { _, dragAmount ->
                if (!accepting) return@detectHorizontalDragGestures
                dragAccum += dragAmount
                if (state.session == null) {
                    // 左滑 = 下一页，右滑 = 上一页；没起翻阈值先按兵不动。
                    if (abs(dragAccum) < size.width * DRAG_START_FRACTION) {
                        return@detectHorizontalDragGestures
                    }
                    val forward = dragAccum < 0f
                    val target = if (forward) currentPage + columns else currentPage - columns
                    if (target < 1 || target > pageCount) {
                        // 已经到第一页/最后一页：这次手势整段作废，别让人空拖到底。
                        accepting = false
                        return@detectHorizontalDragGestures
                    }
                    state.start(currentPage, target, forward, autoPlay = false)
                }
                val s = state.session ?: return@detectHorizontalDragGestures
                // 进度按"沿翻页方向滑了多远"算：下一页看左滑量，上一页看右滑量。
                val raw = if (s.forward) -dragAccum / size.width else dragAccum / size.width
                if (raw < 0f) {
                    // 手指反向拖回了原位：撤销这场翻页，等方向重新明确再开新的。
                    state.clear()
                } else {
                    state.progress = raw.coerceIn(0f, 1f)
                }
            },
            onDragEnd = {
                accepting = false
                val s = state.session ?: return@detectHorizontalDragGestures
                scope.launch {
                    if (state.progress > 0.5f) {
                        state.animateTo(1f, SETTLE_TURN_MILLIS)
                        if (state.session === s) commit(s.to)
                    } else {
                        state.animateTo(0f, SETTLE_TURN_MILLIS)
                        if (state.session === s) state.clear()
                    }
                }
            },
            onDragCancel = {
                accepting = false
                val s = state.session ?: return@detectHorizontalDragGestures
                scope.launch {
                    state.animateTo(0f, SETTLE_TURN_MILLIS)
                    if (state.session === s) state.clear()
                }
            }
        )
    }

    // ---------- 视觉 ----------
    // 折痕阴影与纸背渐暗都只在**绘制阶段**读 progress（见 PageTurnState 的说明）。
    val foldShadow = Modifier.drawBehind {
        if (mode != PageTurnMode.CURL) return@drawBehind
        val s = state.session ?: return@drawBehind
        val p = state.progress
        if (p <= 0.001f || p >= 0.999f) return@drawBehind
        val w = size.width
        if (w <= 0f) return@drawBehind
        // 旧页的投影宽度 = 页宽 × cos(翻起角)：阴影就画在投影边缘（折痕）的一侧。
        val cosA = cos(p * HALF_PI)
        val foldX = if (s.forward) w * cosA else w - w * cosA
        val band = w * 0.22f
        val bandLeft = (if (s.forward) foldX else foldX - band).coerceIn(0f, w)
        val bandRight = (bandLeft + band).coerceIn(0f, w)
        if (bandRight - bandLeft <= 1f) return@drawBehind
        val shade = Color.Black.copy(alpha = 0.34f * (1f - p * 0.5f))
        drawRect(
            brush = Brush.horizontalGradient(
                colors = if (s.forward) listOf(shade, Color.Transparent)
                else listOf(Color.Transparent, shade),
                startX = bandLeft,
                endX = bandRight
            ),
            topLeft = Offset(bandLeft, 0f),
            size = Size(bandRight - bandLeft, size.height)
        )
    }

    val backShade = Modifier.drawBehind {
        if (mode != PageTurnMode.CURL) return@drawBehind
        if (state.session == null) return@drawBehind
        val p = state.progress
        if (p <= 0.02f) return@drawBehind
        val forward = state.session?.forward ?: return@drawBehind
        val shade = Color.Black.copy(alpha = 0.24f * p)
        drawRect(
            brush = Brush.horizontalGradient(
                // 越靠自由边（离书脊越远）越暗，翻起的纸背吃不到正面的光。
                colors = if (forward) listOf(Color.Transparent, shade)
                else listOf(shade, Color.Transparent),
                startX = 0f,
                endX = size.width
            ),
            size = size
        )
    }

    Box(
        modifier = modifier
            // 翻页全程限在正文区里：透视会把翻起的那页投影到卡片之外，
            // 不裁就会压到底部翻页栏/顶栏。
            .clipToBounds()
            .then(dragModifier)
    ) {
        val s = session
        if (s == null || mode == PageTurnMode.OFF) {
            content(currentPage)
        } else {
            // 底层：翻完之后停下的那一页（滑页模式下它同时从另一侧推进来）。
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        if (mode == PageTurnMode.SLIDE) {
                            translationX = (if (s.forward) 1f else -1f) *
                                (1f - state.progress) * size.width
                        }
                    }
                    .then(foldShadow)
            ) {
                content(s.to)
            }

            // 顶层：正在翻走的旧页。
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        if (mode == PageTurnMode.SLIDE) {
                            translationX = (if (s.forward) -1f else 1f) *
                                state.progress * size.width
                        } else {
                            // 仿真翻页：绕书脊边翻到侧立。下一页绕左边缘、上一页绕右边缘，
                            // 这样缺口永远开在正确的一侧，旧页镜像面也不会被看到。
                            transformOrigin = if (s.forward) {
                                TransformOrigin(0f, 0.5f)
                            } else {
                                TransformOrigin(1f, 0.5f)
                            }
                            rotationY = (if (s.forward) -90f else 90f) * state.progress
                            // 相机距离取页宽的 3 倍：透视足以看出"纸在翻"（自由边
                            // 会朝观者鼓起来），又不会近到把自由边放大到冲出屏幕。
                            cameraDistance = size.width * 3f
                            // 收尾淡出：翻到 90° 时旧页投影成一条竖线，透视还会把它
                            // 进一步放大，最后 5% 淡掉就吃掉了这条细线，不闪一下。
                            alpha = ((1f - state.progress) / 0.05f).coerceIn(0f, 1f)
                        }
                    }
                    .then(backShade)
            ) {
                content(s.from)
            }
        }
    }
}
