package com.treechat.workbench.ui.window

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import java.util.UUID
import kotlin.math.abs

enum class DockSide {
    LEFT,
    RIGHT
}

/**
 * 窗口所在的坐标空间。
 *
 * 这是"工作台卡片就地开窗"能成立的前提：同一个 FloatingWindowItem 类被复用，
 * 但**它的 offset 不是同一个坐标系里的数**。
 *
 * - SCREEN：offset 是视口像素，由 MainActivity 顶层浮层渲染，
 *   参与边缘吸附，并被 clampIntoViewport 约束在屏幕内（左右下可露出一部分，顶部不允许）。
 * - GRAPH ：offset 是**画布内容坐标**，由 TreeTopologyCanvas 在
 *   pan/scale 变换之后渲染。它随画布一起平移缩放，所以**不能**参与
 *   屏幕边缘吸附，也不能用视口尺寸去 clamp —— 那会把窗口错误地拽到屏幕边上。
 *
 * 把两者混成一个坐标系，症状就是"在工作台里开窗，一平移缩放就飞出去"。
 */
enum class WindowSpace {
    SCREEN,
    GRAPH
}

/**
 * 小窗的尺寸与形态默认值。
 *
 * 集中在这里而不是散落在各调用点：从工作台就地开窗、浮层里再分叉开窗、
 * 以及还原尺寸时要保证同一套值，否则"同一件事在不同入口开出来的窗不一样大"。
 */
object WindowDefaults {
    val WIDTH: Dp = 340.dp
    val HEIGHT: Dp = 470.dp

    /**
     * 工作台卡片的最小路尺寸（与 TreeTopologyView 的 CARD_WIDTH/HEIGHT 一致）。
     *
     * 用途只有一个：卡片就地开窗时，新窗要**从卡片当前尺寸开始生长**，
     * 这样视觉上是"卡片原地扩大变成小窗"，而不是凭空弹出一个全尺寸的窗。
     * 两个常量必须同步，否则会看到一次尺寸跳变。
     */
    val CARD_MIN_WIDTH: Dp = 186.dp
    val CARD_MIN_HEIGHT: Dp = 76.dp
}

/**
 * 面包屑路径上的一节（祖先节点）。
 *
 * 只带"足以定位"的最小信息：节点 id（画布聚焦用）、显示标题、以及该节点的
 * 来源消息 id（点它可跳回母窗口对应段落）。
 *
 * 为什么由**调用方**在开窗时算好、而不是窗口自己去查库：
 * FloatingWindowManager 没有、也不该有数据库/ViewModel 依赖（它只是纯 UI 状态）。
 * 开窗的调用点恰好都持有当前会话的节点列表，由它们把祖先链一次性摊平传进来，
 * 管理器与卡片都不再需要知道"树"。
 */
data class WindowCrumb(
    val nodeId: String,
    val title: String,
    val sourceMessageId: String? = null
)

/**
 * 单个悬浮小窗的 UI 状态。
 *
 * 注意单位约定：offset 是**像素**（px），width/height 是 **Dp**。
 * 之所以混用，是因为拖拽手势回调给的是 px，而布局尺寸用 Dp 更自然；
 * 换算点集中在 FloatingWindowManager 里，避免各处各自为政。
 */
class FloatingWindowItem(
    val id: String = UUID.randomUUID().toString(),
    val nodeId: String,
    val title: String,
    val originQuote: String? = null,
    /**
     * 发起这次分叉的那条消息 id（取自 ConversationNode.sourceMessageId）。
     *
     * 引用卡片靠它跳回原文：只有知道"引用的是哪条消息"，才能把视图滚到那一段。
     * 旧数据与"整段分叉"没有这个值，此时卡片点击退化为"切到来源分支"。
     */
    val sourceMessageId: String? = null,
    val depth: Int = 1,
    /**
     * 祖先链（由根到直接父节点），用于顶部可点击面包屑。
     *
     * 为空时卡片退化为"来源 › 当前"的两级简版面包屑（不虚构未知的祖先）。
     * 见 [WindowCrumb] 的说明：这条链由开窗的调用点算好传入。
     */
    val ancestors: List<WindowCrumb> = emptyList(),
    initialOffset: Offset = Offset.Zero,
    initialWidth: Dp = WindowDefaults.WIDTH,
    initialHeight: Dp = WindowDefaults.HEIGHT,
    initialZIndex: Float = 1f,
    /** 生成来源窗口，用于"从父窗口位置生长"的开窗动画。 */
    val spawnFrom: Offset? = null,
    /** 所在坐标空间。工作台里就地开出的窗是 GRAPH。 */
    val space: WindowSpace = WindowSpace.SCREEN,
    /**
     * 卡片就地开窗时的**生长目标**尺寸。
     *
     * 与 initialWidth/initialHeight（起始尺寸 = 卡片尺寸）分开是刻意的：
     * 起始值只是动画的第一帧，目标值才是窗口稳定后的尺寸。
     * 非 GRAPH 开窗不传，默认等于起始尺寸 —— 也就是没有生长过程，
     * 与原行为完全一致。
     */
    targetWidth: Dp = initialWidth,
    targetHeight: Dp = initialHeight,
    /**
     * **工具窗**的自定义内容（非空即为工具窗）。
     *
     * 阅读器的「选句翻译」等窗不属于任何对话节点：它没有 nodeId 意义上的
     * 分支、也没有消息可渲染，但拖拽/缩放/最小化/全屏这套小窗机制完全通用。
     * 于是这里允许挂一段内容进去，由 [FloatingWindowCard] 优先渲染它，
     * **忽略宿主（MainActivity）传入的对话内容** —— 宿主仍是同一处渲染循环，
     * 不需要为工具窗再写一套浮层。
     *
     * 默认 null：普通对话窗行为一字不变。
     */
    val customContent: (@Composable () -> Unit)? = null
) {
    var offset by mutableStateOf(initialOffset)
    var width by mutableStateOf(initialWidth)
    var height by mutableStateOf(initialHeight)

    /**
     * 窗口的稳定尺寸。卡片开窗时它是"长成之后"的大小；
     * 用户手动拖过缩放手柄后就由 width/height 承载实时值，
     * 这里只在**首次生长动画**期间被读取。
     */
    var growTargetWidth by mutableStateOf(targetWidth)
    var growTargetHeight by mutableStateOf(targetHeight)

    /** 生长动画是否还在进行（进行中才让卡片保持被覆盖的视觉）。 */
    var isGrowing by mutableStateOf(space == WindowSpace.GRAPH && targetWidth != initialWidth)
    var zIndex by mutableFloatStateOf(initialZIndex)

    /**
     * 「真全屏」：**屏幕级**全屏，与对话界面的小窗完全一致 ——
     * 脱离画布坐标系、铺满整个屏幕可读区域、不受画布 pan/scale 影响。
     *
     * 与 [isCanvasFullScreen] 是**两个独立状态**，而不是一个开关的两档：
     * 两者可以互斥、也可以先后叠加，退出其中一个不能影响另一个。
     * （早期只用一个 `isFullScreen`，导致画布上的"撑满画布"和"真正全屏"
     *  只能二选一，用户想要的却是"两个都要，各占一个按钮"。）
     */
    var isFullScreen by mutableStateOf(false)

    /**
     * 「画布全屏」：把窗口撑满**画布可见区**（原来的假全屏行为）。
     *
     * 对图空间（GRAPH）小窗而言，它仍然留在画布坐标系里，随画布缩放而缩放；
     * 对屏幕空间（SCREEN）小窗而言，它退化为普通全屏，避免两种状态行为不一致。
     */
    var isCanvasFullScreen by mutableStateOf(false)

    /**
     * 关闭任意一种全屏（真全屏 / 画布全屏）。
     *
     * 之所以要一次性清两个：拖动标题栏、恢复、贴靠等"退出"路径都只想回到
     * 正常形态，若只清其中一个，窗口会卡在另一种全屏里出不来。
     */
    fun clearAllFullScreen() {
        isFullScreen = false
        isCanvasFullScreen = false
    }

    /**
     * 是否处于「已贴靠」状态（由菜单里的贴靠项设置），以及贴靠前的尺寸。
     *
     * 为什么要记：贴靠会把窗口改成半屏/四分之一屏并钉到角上。用户随后**一拖标题栏**
     * 就应当解除贴靠、恢复正常尺寸继续自由拖动 —— 否则窗口被"卡"在贴靠形态，
     * 想挪走还得先进菜单。这两个字段就是"解除贴靠"所需的还原依据。
     */
    var isSnapped by mutableStateOf(false)
    var preSnapWidth by mutableStateOf(initialWidth)
    var preSnapHeight by mutableStateOf(initialHeight)
    var isMinimized by mutableStateOf(false)
    var dockSide by mutableStateOf(DockSide.RIGHT)

    /**
     * 停靠坞里这一枚胶囊的纵向位置，取值 0f..1f（相对视口高度的比例）。
     *
     * 用比例而不是像素：停靠坞是贴屏幕侧边的全高容器，横竖屏切换、
     * 分屏导致高度变化时，比例能让用户摆的位置保持在同一个相对高度；
     * 存像素则会在旋转后跑到可视区外或被挤回原位。
     */
    var dockFraction by mutableFloatStateOf(0.5f)

    /** 停靠坞标签是否正在被拖动（用于视觉反馈）。 */
    var isDockDragging by mutableStateOf(false)
}

/**
 * 全局应用内小窗管理器。
 *
 * 三大金科玉律之一的「单例拖拽控制器」就落在这里：
 * 全局只有 activeDraggingWindowId 一个锁，手指按在哪个窗口就锁哪个，
 * 移动事件只作用于被锁定的那个窗口，抬手立即释放。
 */
class FloatingWindowManager {

    val windows = mutableStateListOf<FloatingWindowItem>()

    /** 金科玉律 1：全局唯一的拖拽锁。 */
    var activeDraggingWindowId by mutableStateOf<String?>(null)
        private set

    /**
     * 屏幕密度与视口尺寸。
     *
     * 由 MainActivity 在组合中写入。没有它就只能拿裸像素当 Dp 用 ——
     * 这会导致新开的小窗在高分屏上全都挤到左上角。
     */
    var densityScale: Float = 1f
    var viewportSize: IntSize = IntSize.Zero

    /**
     * 顶栏下沿在窗口层坐标系里的 y（px）。
     *
     * 由 MainActivity 从 Scaffold 的 innerPadding 顶部写入。窗口层是 Scaffold
     * **内容**里的兄弟层，而 topBar 画在内容之上 —— 所以 y=0 是被顶栏盖住的区域。
     * 贴靠区与拖拽下界都必须从 [topInsetPx] 起算，否则窗口的标题栏（含关闭按钮）
     * 会藏到顶栏后面，用户"放大之后返回不了"。
     */
    var topInsetPx: Float = 0f

    /**
     * 工作台画布的当前内容范围（画布坐标，px）。
     *
     * 由 TreeTopologyCanvas 在每次布局后写入。图空间的小窗需要它来约束
     * "别被拖到离所有卡片几屏之外找不回来" —— 但**不能**用视口尺寸代替：
     * 画布可以远大于屏幕，两者是不同的坐标系（见 WindowSpace 的注释）。
     */
    var graphContentBounds: Rect = Rect.Zero

    private var nextZIndex = 10f

    // ---------------------------------------------------------------- 开窗

    fun openSubWindow(
        nodeId: String,
        title: String,
        originQuote: String?,
        depth: Int,
        spawnFromPx: Offset? = null,
        sourceMessageId: String? = null,
        ancestors: List<WindowCrumb> = emptyList()
    ): FloatingWindowItem {
        // ⚠️ 复用键必须带上坐标空间，不能只按 nodeId 查。
        //
        // 旧写法 `windows.find { it.nodeId == nodeId }` 会跨空间命中：
        // 用户在工作台里已经为某个节点开过一个 GRAPH 窗，之后在**对话界面**
        // 点同一个节点要开窗时，这里会复用那个 GRAPH 窗 —— 而
        // MainActivity 渲染浮层时按 `space == SCREEN` 过滤，画布又只画 GRAPH，
        // 于是这个窗在对话界面里根本不出现。用户看到的就是
        // 「对话里的小窗跑到工作台去了」/「点了没反应」。
        //
        // 两个空间是两套坐标系、两个渲染者（见 WindowSpace 的注释），
        // 所以"同一个节点的窗"也必须是"同一个空间里的窗"才算同一个。
        windows.find { it.nodeId == nodeId && it.space == WindowSpace.SCREEN }?.let { existing ->
            existing.isMinimized = false
            bringToFront(existing.id)
            return existing
        }

        // 阶梯偏移：用 dp 换算成 px，再按已开窗数量错开，形成物理层叠感
        val baseDp = 28f + (windows.size % 6) * 22f
        val stepPx = baseDp * densityScale
        val start = Offset(stepPx, stepPx + 150f * densityScale)

        val window = FloatingWindowItem(
            nodeId = nodeId,
            title = title,
            originQuote = originQuote,
            sourceMessageId = sourceMessageId,
            depth = depth,
            ancestors = ancestors,
            initialOffset = start,
            initialZIndex = ++nextZIndex,
            spawnFrom = spawnFromPx
        )
        windows.add(window)
        clampIntoViewport(window)
        return window
    }

    /**
     * 开一个**工具窗**：内容由调用方给出，不绑定任何对话节点。
     *
     * 与 [openSubWindow] 的唯一差别是内容来源。复用点：
     *  - 同一个 [nodeId] 已开着就只置顶展开，不重复开（键由调用方给，
     *    例如"选句翻译"用同一个键 → 反复点翻译不会堆出一摞一模一样的窗）；
     *  - 尺寸、层级、拖拽、缩放、边缘吸附全部沿用既有机制。
     *
     * ⚠️ 工具窗的 [nodeId] 不是真实节点 id。宿主（MainActivity）会给它接上
     * `renameNode` / `deleteNode`：前者查不到该节点、静默无操作；后者同样查不到、
     * 随后 `closeWindow` 会正常关窗 —— 语义可接受（关闭可用、重命名无效）。
     */
    fun openToolWindow(
        nodeId: String,
        title: String,
        originQuote: String?,
        content: @Composable () -> Unit,
        spawnFromPx: Offset? = null
    ): FloatingWindowItem {
        windows.find { it.nodeId == nodeId && it.space == WindowSpace.SCREEN }?.let { existing ->
            existing.isMinimized = false
            bringToFront(existing.id)
            return existing
        }

        val baseDp = 28f + (windows.size % 6) * 22f
        val stepPx = baseDp * densityScale
        val start = Offset(stepPx, stepPx + 150f * densityScale)

        val window = FloatingWindowItem(
            nodeId = nodeId,
            title = title,
            originQuote = originQuote,
            depth = 1,
            initialOffset = start,
            initialZIndex = ++nextZIndex,
            spawnFrom = spawnFromPx,
            customContent = content
        )
        windows.add(window)
        clampIntoViewport(window)
        return window
    }

    /**
     * 在工作台画布里**就地**开一个窗（待办 7.1-1 的核心）。
     *
     * 与 openSubWindow 的三点差别，每一点都对应一个真实的约束：
     *
     * 1. 空间是 GRAPH —— offset 是画布内容坐标，随 pan/scale 变换，
     *    因此不参与吸附、不按视口 clamp。
     * 2. 默认尺寸按**卡片当前尺寸**给，而不是固定 340dp —— 用户把卡片
     *    拉大过，说明他就要那么大；开出来的窗按原尺寸生长才自然。
     * 3. 落点就是卡片自己的位置（offsetPx），所以视觉上是"卡片原地展开"，
     *    不是从别处飞过来。
     *
     * 同一个节点已经开着窗时只置顶、不重复开 —— 与 openSubWindow 一致。
     *
     * @param spawnFromWidth/spawnFromHeight 传入卡片**当前**尺寸时，新窗会从这个
     *   尺寸开始生长到 widthDp/heightDp，做出"卡片原地扩大成小窗"的动效。
     *   不传则直接以目标尺寸出现（保持兼容）。
     */
    fun openInPlaceWindow(
        nodeId: String,
        title: String,
        originQuote: String?,
        depth: Int,
        offsetPx: Offset,
        widthDp: Dp,
        heightDp: Dp,
        sourceMessageId: String? = null,
        spawnFromWidth: Dp? = null,
        spawnFromHeight: Dp? = null,
        ancestors: List<WindowCrumb> = emptyList()
    ): FloatingWindowItem {
        windows.find { it.nodeId == nodeId && it.space == WindowSpace.GRAPH }?.let { existing ->
            existing.isMinimized = false
            bringToFront(existing.id)
            return existing
        }

        val window = FloatingWindowItem(
            nodeId = nodeId,
            title = title,
            originQuote = originQuote,
            sourceMessageId = sourceMessageId,
            depth = depth,
            ancestors = ancestors,
            initialOffset = offsetPx,
            // 关键：起始尺寸取卡片尺寸，让 animateDpAsState 动画到目标尺寸。
            // 卡片是被小窗盖住的一方，所以视觉上就是"卡片自己长大了"。
            initialWidth = spawnFromWidth ?: widthDp,
            initialHeight = spawnFromHeight ?: heightDp,
            initialZIndex = ++nextZIndex,
            spawnFrom = offsetPx,
            space = WindowSpace.GRAPH,
            targetWidth = widthDp,
            targetHeight = heightDp
        )
        windows.add(window)
        return window
    }

    /** 图空间里的小窗列表（供 TreeTopologyCanvas 渲染）。 */
    fun graphWindows(): List<FloatingWindowItem> =
        windows.filter { it.space == WindowSpace.GRAPH && !it.isMinimized }

    // ------------------------------------------------------------ 层级治理

    /** 碰谁谁置顶。 */
    fun bringToFront(windowId: String) {
        val window = windows.find { it.id == windowId } ?: return
        window.zIndex = ++nextZIndex
        // 视觉系带：被置顶（激活）的这扇窗，通知底层视口把它的来源短暂高亮。
        // 放在这里而不是各调用点：置顶是**唯一**的"激活"入口（点标题栏、
        // 点卡片、恢复最小化、全屏切换都汇到这一个方法），系带因此不会漏。
        WindowTetherState.activate(window.nodeId, window.sourceMessageId)
    }

    // ------------------------------------------------------------ 拖拽控制

    fun startDrag(windowId: String) {
        activeDraggingWindowId = windowId
        bringToFront(windowId)
    }

    /** 移动当前被锁定的窗口。 */
    fun onDrag(delta: Offset) {
        val lockedId = activeDraggingWindowId ?: return
        moveWindow(lockedId, delta)
    }

    /**
     * 释放拖拽锁。
     *
     * 这里刻意**不**做边缘吸附：吸附交给惯性滑行结束时统一执行（见
     * FloatingWindowCard 的 flingJob），否则松手瞬间先吸一次、滑行完再吸一次，
     * 会出现窗口先跳到边上又被拖走的来回抽动手感。
     */
    fun endDrag() {
        activeDraggingWindowId = null
    }

    /** 直接移动指定窗口（惯性甩动阶段用，此时已经没有锁了）。 */
    fun moveWindow(windowId: String, delta: Offset) {
        val window = windows.find { it.id == windowId } ?: return
        if (window.isFullScreen || window.isCanvasFullScreen) return
        window.offset = window.offset + delta
        // 图空间窗口的坐标是画布内容坐标，不受视口约束。
        // 这里若不判空间就直接 clamp，工作台里的小窗会在平移画布时被
        // 按屏幕像素范围反复拉回，表现为"拖不动 / 自己弹回去"。
        if (window.space == WindowSpace.SCREEN) {
            clampIntoViewport(window)
        } else {
            clampIntoGraphContent(window)
        }
    }

    // ------------------------------------------------------------ 吸附与约束

    /** 窗口最小宽度（dp），与 resizeWindow 的下限保持一致。 */
    private val MIN_WINDOW_WIDTH_DP = 260f

    /** 窗口最小高度（dp），与 resizeWindow 的下限保持一致。 */
    private val MIN_WINDOW_HEIGHT_DP = 280f

    /**
     * 窗口被挪出屏幕后，**必须留在屏内的最小可见宽度/高度**（dp）。
     *
     * 56dp 约等于一指宽：既能让用户一眼看到"窗还在那儿"，也足够抓住标题栏把它拖回来。
     * 调小到 30dp 以下就只剩一道细边，容易彻底找不回；调大会失去"挂到边上腾地方"的意义。
     */
    private val MIN_VISIBLE_DP = 56f

    /** 吸附感应区宽度（dp，从屏幕边缘算起）。28dp 约等于一指宽。 */
    private val DWELL_ZONE_DP = 28f

    /** 同一吸附位内移动超过这个距离（dp）就重置"停留计时"。 */
    private val DWELL_RESET_DP = 12f

    /** 贴靠目标（与 Windows 的 Snap 一致）。 */
    enum class SnapTarget(val label: String) {
        LEFT_HALF("左半屏"),
        RIGHT_HALF("右半屏"),
        TOP_FULLSCREEN("最大化"),
        TOP_LEFT("左上四分之一"),
        TOP_RIGHT("右上四分之一"),
        BOTTOM_LEFT("左下四分之一"),
        BOTTOM_RIGHT("右下四分之一")
    }

    /**
     * 拖到吸附位时的**预览**：告诉界面"松手/停住会贴到这里"。
     *
     * [rect] 是目标区域（视口像素），界面据此画半透明提示框 —— 这是 Windows
     * 拖到屏幕边缘时那块"要贴过去的影子"，用户在看得到去向之前不会误触。
     *
     * [from] 是被拖动窗口**当前**的矩形（视口像素）。界面用它做"从当前位置
     * 扩大/平移到目标位"的生长动画（Windows 同款观感）：影子不是凭空闪现，
     * 而是从窗口身上长出来。用户明确要求把这个动画加回来。
     */
    data class SnapPreview(val target: SnapTarget, val rect: Rect, val from: Rect)

    /** 当前吸附预览；null = 不在任何吸附位。 */
    var snapPreview by mutableStateOf<SnapPreview?>(null)
        private set

    /**
     * "停留计时"令牌：指针在同一吸附位内**累计**移动超过阈值时自增。
     *
     * 界面用 `LaunchedEffect(令牌) { delay(1s); applySnap() }` 实现
     * "停住一秒才吸附"：一旦移动就自增 → 协程重启 → 计时从头再来。
     */
    var snapDwellToken by mutableIntStateOf(0)
        private set

    /** 某个贴靠目标在视口里的矩形（视口像素）。 */
    private fun snapRectOf(target: SnapTarget): Rect {
        val w = viewportSize.width.toFloat()
        val h = viewportSize.height.toFloat()
        // ⚠️ 所有贴靠区都要从 `topInsetPx` 开始，不能用 0。
        //
        // 窗口层是 Scaffold **内容**里的兄弟层，而 Scaffold 的 topBar（标题栏 + 标签行）
        // 画在内容**之上**。于是 y=0 正好落在顶栏底下：最大化/上贴靠之后，
        // 小窗的标题栏（含关闭按钮）被顶栏盖住，用户既关不掉也拖不动 ——
        // 就是"放大后被顶部栏位遮挡，返回不了"。
        //
        // topInsetPx 由 MainActivity 从 Scaffold 的 innerPadding 顶部写入，
        // 也就是"顶栏下沿"在窗口层坐标系里的位置。贴靠区从它开始，标题栏就永远可见。
        val top = topInsetPx
        val usable = (h - top).coerceAtLeast(1f)
        val half = top + usable / 2f
        return when (target) {
            SnapTarget.LEFT_HALF -> Rect(0f, top, w / 2f, h)
            SnapTarget.RIGHT_HALF -> Rect(w / 2f, top, w, h)
            SnapTarget.TOP_FULLSCREEN -> Rect(0f, top, w, h)
            SnapTarget.TOP_LEFT -> Rect(0f, top, w / 2f, half)
            SnapTarget.TOP_RIGHT -> Rect(w / 2f, top, w, half)
            SnapTarget.BOTTOM_LEFT -> Rect(0f, half, w / 2f, h)
            SnapTarget.BOTTOM_RIGHT -> Rect(w / 2f, half, w, h)
        }
    }

    /**
     * 拖动过程中更新吸附预览。
     *
     * 命中判定用**窗口自身**贴边的程度（而不是手指位置）：把窗口往左推、直到它的
     * 左边缘压进吸附区，才认为"想去左边"。这样与 Windows 的观感一致 —— 是窗口
     * 在找位置，不是光标在找位置。
     *
     * ⚠️ **只认左右分屏**（用户要求："停留一秒分屏，否则不吸附"）。
     *
     * 早期版本还会在贴顶时给最大化、在四角给四分之一屏。但那是"拖到哪儿都可能
     * 弹出一个贴靠位"，用户觉得太灵敏、太容易误贴。现在只保留左右半屏这一种
     * 分屏语义：拖到左边出左半屏影子，拖到右边出右半屏影子，其余位置一律不吸附
     * （[SnapTarget] 里的其它取值仍保留，供将来由菜单等显式入口使用）。
     */
    fun updateSnapPreview(windowId: String, pointer: Offset) {
        val window = windows.find { it.id == windowId } ?: return
        if (window.space != WindowSpace.SCREEN ||
            viewportSize.width == 0 || viewportSize.height == 0
        ) {
            snapPreview = null
            return
        }
        val zone = DWELL_ZONE_DP * densityScale
        val w = viewportSize.width.toFloat()
        val left = window.offset.x
        val right = window.offset.x + window.width.value * densityScale

        val nearLeft = left <= zone
        val nearRight = right >= w - zone

        val target = when {
            nearLeft && !nearRight -> SnapTarget.LEFT_HALF
            nearRight && !nearLeft -> SnapTarget.RIGHT_HALF
            // 左右同时贴到（窗口比屏幕还宽）→ 不吸附，避免来回抖
            else -> null
        }
        val fromNow = Rect(
            left = window.offset.x,
            top = window.offset.y,
            right = right,
            bottom = window.offset.y + window.height.value * densityScale
        )
        val next = target?.let { SnapPreview(it, snapRectOf(it), fromNow) }
        if (next?.target != snapPreview?.target) {
            snapPreview = next
            dwellAnchor = pointer     // 换了吸附位 → 锚点归位、计时重来
            snapDwellToken++
        } else if (next != null) {
            // 同一吸附位内**累计**移动超过阈值 → 说明还没停住，锚点前移、计时重来。
            //
            // ⚠️ 必须比"从锚点算起的累计位移"，**不能**比每次事件的增量位移。
            // 旧实现拿 `pointer - lastPreviewPointer`（每帧增量）去比 12dp：
            // 鼠标每帧只动几像素，永远超不过阈值，于是这个计时器**从来不重置** ——
            // 用户一边慢慢拖着窗挪、计时一边照走，满一秒就吸附。
            // 观感正是"一贴上就吸住，根本没等我停"。
            val anchor = dwellAnchor
            if (anchor == null || (pointer - anchor).getDistance() > DWELL_RESET_DP * densityScale) {
                dwellAnchor = pointer
                snapDwellToken++
            }
            // 生长动画的起点要跟着窗口实时走，否则影子会从"进入吸附区那一刻"的
            // 旧位置长出来，与窗口当前所在对不上。
            snapPreview = next
        }
    }

    fun clearSnapPreview() {
        snapPreview = null
        dwellAnchor = null
    }

    /**
     * 停留计时的**锚点**：上一次判定"还没停住"时的指针位置。
     *
     * 判"停住"的方式是"从锚点起走够 [DWELL_RESET_DP] 就重置锚点并重新计时"，
     * 等价于要求指针在一个小范围内**真正静止**满一秒。
     * 这样一来，只要还在挪动，计时就永远到不了终点。
     */
    private var dwellAnchor: Offset? = null

    /**
     * 用户从菜单**显式**贴靠到指定位置。
     *
     * 与旧行为的关键差别：旧实现是在"松手/甩动结束"时按释放位置自动吸附，
     * 阈值一路收到 12dp 仍然"太灵敏"—— 因为按位置自动判定本身就不可控。
     * 现在贴靠只由这一处入口触发，日常拖动绝不会意外变成半屏/四分之一屏。
     */
    fun applySnap(windowId: String, target: SnapTarget) {
        val window = windows.find { it.id == windowId } ?: return
        if (viewportSize.width == 0 || viewportSize.height == 0) return
        if (window.space != WindowSpace.SCREEN) return

        // 首次贴靠前记下原尺寸，供"一拖即取消贴靠"还原
        if (!window.isSnapped) {
            window.preSnapWidth = window.width
            window.preSnapHeight = window.height
        }
        // 半屏宽度必须**不能小于窗口最小宽度**（260dp，与 resizeWindow 同一口径）。
        //
        // 实测：手机上视口较窄，半屏只有约 196dp —— 比最小宽度还窄，窗口内容会被
        // 挤成一列（输入框里的提示文字竖着排）。所以这里取 max(半屏, 最小宽度)：
        // 宁可略微超过半屏，也不能把内容挤坏。
        val halfRaw = (viewportSize.width / densityScale) * 0.5f
        val halfW = halfRaw.coerceAtLeast(MIN_WINDOW_WIDTH_DP).dp
        val halfH = ((viewportSize.height / densityScale) * 0.5f)
            .coerceAtLeast(MIN_WINDOW_HEIGHT_DP).dp
        val fullH = (viewportSize.height / densityScale).dp
        val halfWpx = halfW.value * densityScale
        val halfHpx = halfH.value * densityScale

        when (target) {
            SnapTarget.TOP_FULLSCREEN -> {
                // 与 Windows 一致：贴顶 = 最大化。这里直接进**真全屏**态（带进出动画），
                // 而不是把尺寸设成整屏 —— 后者会丢掉"还原回原尺寸"的能力。
                // 先清画布全屏，否则两者会并存（窗口卡在画布里，退不出来）。
                window.isCanvasFullScreen = false
                window.isFullScreen = true
                bringToFront(windowId)
                return
            }
            SnapTarget.LEFT_HALF -> {
                window.width = halfW
                window.height = fullH
                window.offset = Offset(0f, 0f)
            }
            SnapTarget.RIGHT_HALF -> {
                window.width = halfW
                window.height = fullH
                window.offset = Offset(viewportSize.width - halfWpx, 0f)
            }
            SnapTarget.TOP_LEFT -> {
                window.width = halfW; window.height = halfH
                window.offset = Offset(0f, 0f)
            }
            SnapTarget.TOP_RIGHT -> {
                window.width = halfW; window.height = halfH
                window.offset = Offset(viewportSize.width - halfWpx, 0f)
            }
            SnapTarget.BOTTOM_LEFT -> {
                window.width = halfW; window.height = halfH
                window.offset = Offset(0f, viewportSize.height - halfHpx)
            }
            SnapTarget.BOTTOM_RIGHT -> {
                window.width = halfW; window.height = halfH
                window.offset = Offset(
                    viewportSize.width - halfWpx,
                    viewportSize.height - halfHpx
                )
            }
        }
        window.clearAllFullScreen()
        window.isSnapped = true
        bringToFront(windowId)
        clampIntoViewport(window)
    }

    /**
     * 解除贴靠：恢复贴靠前的尺寸，位置留在原地（随后由拖动接管）。
     *
     * 由"开始拖动标题栏"调用 —— 用户一拖就撤销贴靠，符合直觉。
     */
    fun clearSnap(windowId: String) {
        val window = windows.find { it.id == windowId } ?: return
        // 退出**任何一种**全屏：一拖标题栏就回到正常形态。
        // 早先这里只在 isSnapped 时才返回，于是全屏中的窗口拖不动、也退不出 ——
        // 因为 isFullScreen 的窗口被 moveWindow 的守卫挡住，标题栏成了死的。
        window.clearAllFullScreen()
        if (!window.isSnapped) return
        window.width = window.preSnapWidth
        window.height = window.preSnapHeight
        window.isSnapped = false
        clampIntoViewport(window)
    }

    /**
     * 屏幕空间的窗口约束：**允许挪出屏幕，但左右下三边必须留一条能抓回来的边**。
     *
     * ## 为什么改成"可以挪出屏幕"
     * 旧实现的上界是 `viewport - 窗口尺寸`，也就是**窗口必须整整齐齐待在屏内**：
     * 往右推，窗右边缘一到屏幕右边缘就推不动了；往下同理。
     * 用户要的是"可以让它只剩一小部分留在屏幕里"——把不常用的小窗挂到边上腾地方，
     * 这是桌面系统的常规用法。因此这里把上界放宽为"至少留 [MIN_VISIBLE_DP]
     * 宽的可见条"。
     *
     * ## 为什么顶部是例外
     * 上界（y 的下界）仍然钉在 [topInsetPx]：y=0 正好被 Scaffold 顶栏盖住，
     * 窗口推到那里之后标题栏（唯一的拖拽把手和关闭按钮）就看不见也点不到了 ——
     * 这正是此前"放大后被顶栏遮挡、返回不了"的成因。
     * 挂到左/右/下三边时，露出来的那条要么是标题栏本身（下边），
     * 要么仍能通过横向拖动把窗拉回来，唯独顶边没有这个退路，故保持原样。
     */
    private fun clampIntoViewport(window: FloatingWindowItem) {
        if (viewportSize.width == 0 || viewportSize.height == 0) return
        if (window.isFullScreen || window.isCanvasFullScreen) return
        // 图空间窗口用的是画布内容坐标：画布本身可以比屏幕大得多，
        // 拿视口尺寸来 clamp 等于把内容坐标当成屏幕坐标，是错的。
        if (window.space != WindowSpace.SCREEN) return

        val widthPx = window.width.value * densityScale
        val minVisible = MIN_VISIBLE_DP * densityScale

        // 水平：左右都允许挪出屏幕，只留 minVisible 宽的一条在屏内。
        val minX = -(widthPx - minVisible)
        val maxX = (viewportSize.width - minVisible).coerceAtLeast(minX)
        // 垂直：上界以 topInsetPx 兜底（标题栏必须留在顶栏下方，见上面的说明）；
        // 下界放宽到"窗顶最低只能到距屏底 minVisible 处"——同样是留一条在屏内。
        val maxY = (viewportSize.height - minVisible).coerceAtLeast(topInsetPx)
        val minY = topInsetPx.coerceAtMost(maxY)

        window.offset = Offset(
            x = window.offset.x.coerceIn(minX, maxX),
            y = window.offset.y.coerceIn(minY, maxY)
        )
    }

    // ------------------------------------------------------------ 尺寸与形态

    fun resizeWindow(windowId: String, deltaWidth: Dp, deltaHeight: Dp) {
        val window = windows.find { it.id == windowId } ?: return
        if (window.isFullScreen || window.isCanvasFullScreen) return
        // 宽度上限**按视口动态算**：旧实现写死 620.dp，在平板/电脑模式（1280dp 视口）
        // 下连半屏都拉不到，用户想并排放两个窗看代码完全被卡住。
        // 高度上限维持原值（用户明确"长度上限可以"）。
        window.width = (window.width + deltaWidth).coerceIn(260.dp, maxWindowWidth())
        window.height = (window.height + deltaHeight).coerceIn(280.dp, 860.dp)
        clampIntoViewport(window)
        if (window.space == WindowSpace.GRAPH) clampIntoGraphContent(window)
    }

    /**
     * 窗口允许的最大宽度（Dp）= 视口宽度减去左右各留的边距，再夹在 [360, 1600] 之间。
     *
     * - 下界 360dp：视口还没由 MainActivity 写入、或分屏窄到 300dp 时，不至于把上限
     *   压成一个连最小宽 260dp 都放不下的怪值；
     * - 上界 1600dp：超宽屏不需要整屏宽的窗，再宽反而拖不回来；
     * - 视口未知时退回 1400dp 的保守值（比旧的 620dp 宽，且不会再被卡住）。
     */
    private fun maxWindowWidth(): Dp {
        val vp = viewportSize.width
        if (vp <= 0 || densityScale <= 0f) return 1400.dp
        val available = vp / densityScale - 40f
        return available.coerceIn(360f, 1600f).dp
    }

    /**
     * 把图空间窗口约束回画布内容范围附近。
     *
     * 留出 240px 的余量：允许窗口被摆到卡片群外侧一点（用户可能故意把窗
     * 挪到空白处看连线），但不能远到滚不回来。
     */
    private fun clampIntoGraphContent(window: FloatingWindowItem) {
        val bounds = graphContentBounds
        if (bounds.width <= 0f && bounds.height <= 0f) return
        val margin = 240f
        val widthPx = window.width.value * densityScale
        val heightPx = window.height.value * densityScale
        window.offset = Offset(
            x = window.offset.x.coerceIn(
                bounds.left - margin,
                (bounds.right + margin - widthPx).coerceAtLeast(bounds.left - margin)
            ),
            y = window.offset.y.coerceIn(
                bounds.top - margin,
                (bounds.bottom + margin - heightPx).coerceAtLeast(bounds.top - margin)
            )
        )
    }

    /** 绿点：全屏就地形变 / 还原。 */
    fun toggleFullScreen(windowId: String) {
        val window = windows.find { it.id == windowId } ?: return
        // 切真全屏前先清掉画布全屏：两个状态并存会让"退出其中一个"变得含糊
        // （用户以为退出了，结果窗口仍被另一种全屏撑满）。
        window.isCanvasFullScreen = false
        window.isFullScreen = !window.isFullScreen
        bringToFront(windowId)
    }

    /**
     * 绿点之后的**第四个点**：画布全屏（原来的假全屏）。
     *
     * 图空间窗口撑满画布可见区；屏幕空间窗口没有"画布"概念，直接退化为真全屏，
     * 否则这个按钮在对话界面的小窗上会点了没反应。
     */
    fun toggleCanvasFullScreen(windowId: String) {
        val window = windows.find { it.id == windowId } ?: return
        if (window.space != WindowSpace.GRAPH) {
            toggleFullScreen(windowId)
            return
        }
        window.isFullScreen = false
        window.isCanvasFullScreen = !window.isCanvasFullScreen
        bringToFront(windowId)
    }

    /** 黄点：最小化到停靠坞 / 还原。 */
    fun toggleMinimize(windowId: String) {
        val window = windows.find { it.id == windowId } ?: return
        window.isMinimized = !window.isMinimized
        if (window.isMinimized) {
            val rightCount = windows.count { it.isMinimized && it.dockSide == DockSide.RIGHT }
            window.dockSide = if (rightCount >= 10) DockSide.LEFT else DockSide.RIGHT
            avoidDockCollision(window)
        } else {
            bringToFront(windowId)
            clampIntoViewport(window)
        }
    }

    /** 红点：彻底关闭。 */
    fun closeWindow(windowId: String) {
        windows.removeAll { it.id == windowId }
    }

    // ------------------------------------------------------------ 停靠坞排序

    /** 某一侧正在停靠的小窗，按用户摆好的纵向比例排序。 */
    fun dockedOn(side: DockSide): List<FloatingWindowItem> =
        windows.filter { it.isMinimized && it.dockSide == side }
            .sortedBy { it.dockFraction }

    /**
     * 拖动停靠坞里的一枚标签，改变它的纵向位置。
     *
     * dyFraction 是**归一化比例增量**（像素增量 / 容器高度），由 UI 侧换算 ——
     * 管理器不碰视口尺寸以外的布局知识，保持"状态归状态、布局归布局"。
     * 夹在 0.04..0.96 是为了让标签不会有一半滑出容器被裁掉。
     */
    fun dragDockItem(windowId: String, dyFraction: Float) {
        val window = windows.find { it.id == windowId } ?: return
        window.dockFraction = (window.dockFraction + dyFraction).coerceIn(0.04f, 0.96f)
    }

    /** 把一枚停靠标签直接放到某个纵向比例（拖动开始时用于对齐手指位置）。 */
    fun setDockFraction(windowId: String, fraction: Float) {
        val window = windows.find { it.id == windowId } ?: return
        window.dockFraction = fraction.coerceIn(0.04f, 0.96f)
    }

    /**
     * 停靠时若同一位置附近已经有别的标签，把新来的错开。
     *
     * 没有这一步的话，最小化多个窗口后所有标签会在 0.5 处叠成一摞，
     * 看起来像"只停靠了一个"，用户会以为窗口丢了。
     */
    fun avoidDockCollision(window: FloatingWindowItem) {
        val peers = windows.filter {
            it.id != window.id && it.isMinimized && it.dockSide == window.dockSide
        }
        var guard = 0
        while (peers.any { kotlin.math.abs(it.dockFraction - window.dockFraction) < 0.05f } &&
            guard++ < 40
        ) {
            window.dockFraction = (window.dockFraction + 0.06f).let {
                if (it > 0.96f) 0.04f else it
            }
        }
    }
}
