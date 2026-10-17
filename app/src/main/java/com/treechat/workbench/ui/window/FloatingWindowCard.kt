package com.treechat.workbench.ui.window

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.ArrowOutward
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.treechat.workbench.data.model.ConversationNode
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.treechat.workbench.ui.tree.EdgeAutoScroller
import com.treechat.workbench.ui.theme.AccentIceBlue
import com.treechat.workbench.ui.theme.AccentNeonGreen
import com.treechat.workbench.ui.theme.DangerRed
import com.treechat.workbench.ui.theme.DarkSurface
import com.treechat.workbench.ui.theme.DarkSurfaceVariant
import com.treechat.workbench.ui.theme.TextMuted
import com.treechat.workbench.ui.theme.TextPrimary
import com.treechat.workbench.ui.theme.TextSecondary
import com.treechat.workbench.ui.theme.WarningYellow
import com.treechat.workbench.ui.theme.WindowBorderColor
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** 停在吸附位多久才真的贴靠（毫秒）。用户要求：**停稳一秒**即吸附；没停住就不吸附。 */
private const val DWELL_SNAP_MS = 1_000L

/**
 * 悬浮小窗卡片。
 *
 * 这一层是**纯测量壳**：它唯一做的事是把"父容器实际给到的最大尺寸"
 * 读出来传给 [FloatingWindowCardImpl]，让"全屏"= 填满真实父容器。
 *
 * 为什么不直接在里面用 `LocalConfiguration.screenWidthDp/HeightDp`：
 * 那是整块设备屏幕的尺寸，而本卡片挂在 MainActivity 的 `Column` 里，
 * 上方还压着应用级 TopAppBar 与药丸导航。用整屏尺寸会把窗口撑出父容器、
 * 被裁掉一条 —— 而选词器的命中检测依赖"可见位置与布局坐标同源"，
 * 一旦被裁就整体错位、全屏下彻底选不中文字。
 * 详见 [FloatingWindowCardImpl] 内 screenWidth/screenHeight 处的说明。
 */
@Composable
fun FloatingWindowCard(
    windowItem: FloatingWindowItem,
    manager: FloatingWindowManager,
    onRename: (String) -> Unit = {},
    onDelete: () -> Unit = {},
    onQuoteClick: ((String?) -> Unit)? = null,
    /**
     * 正文里的**同文本超链接**被点中（点开 → 该分支的小窗）。
     *
     * ## 为什么它与 [onQuoteClick] 是两件事
     * [onQuoteClick] 走的是"来源消息 id"，服务的是**面包屑与引用卡片**——
     * 参数是 ConversationNode.sourceMessageId，可空。
     * 而正文里的同文本超链接是 [SelectableMarkdownText] 内部用
     * `QuoteLinkMatcher` 命中后回调的，拿到的是**完整的 ConversationNode**。
     *
     * 早先本卡片没有这个形参，于是它落到 ChatMessageView 的默认空实现 ——
     * 表现就是"思维导图里打开的小窗，正文超链接怎么点都没反应"，
     * 而主对话界面里同一个链接却是好的（那条路径由 ChatMessageView 直接提供）。
     */
    onOpenQuoteWindow: (ConversationNode) -> Unit = {},
    onBreadcrumbClick: ((WindowCrumb) -> Unit)? = null,
    ancestors: List<WindowCrumb>? = null,
    positionedByParent: Boolean = false,
    contentScale: Float = 1f,
    /**
     * "全屏"时应当填满的尺寸。传 null 表示由卡片自己测量父容器。
     *
     * 两种情况必须区分，这是本参数存在的唯一理由：
     *  · **SCREEN 空间**（MainActivity 的浮层）：父容器就是真实可见区域，
     *    自己测量（BoxWithConstraints）即可 → 传 null；
     *  · **GRAPH 空间**（画布内的小窗）：外层 Box **自身没有尺寸**（只做
     *    offset 与 graphicsLayer 缩放），测量的结果是"内容固有尺寸"而非
     *    画布视口，直接测会算出错误的全屏尺寸。因此由画布把
     *    `视口尺寸 / scale` 换算成内容坐标后显式传进来。
     */
    fullscreenSize: DpSize? = null,
    /**
     * 边缘自动平移器。只有**画布内的小窗**需要传（SCREEN 空间的全局浮层没有父画布）。
     *
     * 拖动小窗时指针贴到画布边缘，就让画布缓慢顺势平移，
     * 便于把窗口拖到当前视口之外的位置。速度为"越靠边越快 + 上限"，见 [EdgeAutoScroller]。
     */
    edgeScroller: EdgeAutoScroller? = null,
    /** 画布视口尺寸（px），供边缘感应判定用。仅在传了 edgeScroller 时有意义。 */
    edgeViewportPx: Size = Size.Zero,
    /** 边缘感应区宽度（px）。 */
    edgeZonePx: Float = 44f,
    content: @Composable () -> Unit
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        FloatingWindowCardImpl(
            windowItem = windowItem,
            manager = manager,
            onRename = onRename,
            onDelete = onDelete,
            onQuoteClick = onQuoteClick,
    onOpenQuoteWindow = onOpenQuoteWindow,
            onBreadcrumbClick = onBreadcrumbClick,
            ancestors = ancestors,
            positionedByParent = positionedByParent,
            contentScale = contentScale,
            // 显式给了尺寸就用它；否则退回"父容器实测"（SCREEN 空间的路径）
            fillWidth = fullscreenSize?.width ?: maxWidth,
            fillHeight = fullscreenSize?.height ?: maxHeight,
            edgeScroller = edgeScroller,
            edgeViewportPx = edgeViewportPx,
            edgeZonePx = edgeZonePx,
            content = content
        )
    }
}

@Composable
private fun FloatingWindowCardImpl(
    windowItem: FloatingWindowItem,
    manager: FloatingWindowManager,
    onRename: (String) -> Unit = {},
    onDelete: () -> Unit = {},
    /**
     * 点了顶部那张"引用上级讨论"的卡片。
     *
     * 参数是发起这次分叉的那条消息 id（ConversationNode.sourceMessageId），
     * 可能为 null：早期数据与"整段分叉"都没有记录来源。
     */
    onQuoteClick: ((String?) -> Unit)? = null,
    onOpenQuoteWindow: (ConversationNode) -> Unit = {},
    /**
     * 点了顶部面包屑上的某个**祖先**节点。
     *
     * 由调用方决定"定位回母窗口对应段落"的具体做法：树画布上聚焦并高亮那张
     * 卡片，对话列表里滚动到对应消息。为 null 时退回 [onQuoteClick] 的
     * 来源消息跳转（那样至少也能回到母段落）。
     */
    onBreadcrumbClick: ((WindowCrumb) -> Unit)? = null,
    /**
     * 渲染期覆盖的祖先链（面包屑路径）。
     *
     * 与 [FloatingWindowItem.ancestors]（开窗时写入）二选一：渲染者若持有完整的
     * 节点树（如工作台画布），可在渲染期现算祖先链传进来，从而覆盖所有开窗入口，
     * 不必逐个调用点改签名。为 null 时用 [FloatingWindowItem.ancestors]。
     */
    ancestors: List<WindowCrumb>? = null,
    /**
     * 窗口位置是否**已经由父级施加**。
     *
     * 画布（TreeTopologyView）渲染 GRAPH 窗时，外层 Box 已经做过
     * "内容坐标 → 屏幕坐标"的变换（`offset*scale + pan`）。此卡内部若再按
     * `windowItem.offset` 偏移一次，就是**双重施加**，窗口会明显偏离正确落点，
     * 而画布的命中矩形用的却是内容坐标 —— 于是"看得见的窗"和"点得到的窗"
     * 不是同一个位置，表现为小窗拖不动、缩放手柄抓不住。
     *
     * 因此画布传 true；屏幕浮层（MainActivity）保持默认 false，行为不变。
     */
    positionedByParent: Boolean = false,
    /**
     * 外层容器的缩放比（画布的 graphState.scale）。
     *
     * 画布把整扇窗按 scale 缩放了，手势回调拿到的却是**未乘 scale** 的局部像素。
     * 不除回去就会"放大后拖不动、缩小后飞出去"。
     * 卡片侧早已这么做（TreeTopologyView 的 `raw.x / s`），小窗侧此前漏了。
     */
    contentScale: Float = 1f,
    /** 父容器实际给到的最大宽度 —— 即"全屏"时应当填满的宽度。 */
    fillWidth: Dp,
    /** 父容器实际给到的最大高度 —— 即"全屏"时应当填满的高度。 */
    fillHeight: Dp,
    edgeScroller: EdgeAutoScroller? = null,
    edgeViewportPx: Size = Size.Zero,
    edgeZonePx: Float = 44f,
    content: @Composable () -> Unit
) {
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current

    // ⚠️ 全屏尺寸**不能**取 LocalConfiguration.screenWidthDp/HeightDp。
    //
    // 那是**整块设备屏幕**的尺寸，而本卡片的父节点是 MainActivity 的
    // `Column`：它上面还压着一条应用级 TopAppBar 与 34dp 药丸导航，且外层
    // 根 Box 已经扣过 innerPadding（状态栏/导航栏）。于是"整屏高"比
    // "本卡片实际可用的高"高出一条顶栏 —— 多出来的部分被父级裁掉。
    //
    // 后果不是"看着溢出"，而是**选词器整段失效**：
    // SelectableMarkdownText 的命中检测约定"块位置与手势坐标都在同一个
    // 根 Box 坐标系里"（见 ui/selection/SelectableMarkdown.kt 的坐标系约定）。
    // 窗口被裁后，可见文字与其布局 y 坐标不再同源，hitTest 里
    // `positionInRoot - snapshot.originInRoot` 整体偏移；偏移量远超
    // SelectionModel 的 HIT_SLOP_PX(12f)，精确命中与"最近块"退化分支
    // 双双落空 → 全屏小窗里按住哪里都选不中。
    //
    // 正确的"全屏"语义是**填满真实父容器**：由外层 FloatingWindowCard 用
    // BoxWithConstraints 把父级给自己的最大约束读出来传进来，可见尺寸与布局
    // 尺寸因此恒等，坐标系重新重合。这也顺带保证了药丸导航始终可见
    // （窗口不会盖到顶栏上）。
    val screenWidth = fillWidth
    val screenHeight = fillHeight

    // 开窗"生长"动画：从略小、透明的状态弹出来
    var appeared by remember { mutableStateOf(false) }
    LaunchedEffect(windowItem.id) { appeared = true }

    /**
     * 卡片就地开窗的"原地长大"动画。
     *
     * windowItem.width/height 的初始值是**卡片当前尺寸**（见
     * FloatingWindowManager.openInPlaceWindow 的 spawnFrom 参数）。
     * 这里第一帧先按卡片尺寸渲染，下一帧再切到目标尺寸 ——
     * animateDpAsState 于是从卡片尺寸插值到全尺寸，
     * 视觉上就是"这张卡片自己扩大成了小窗"。
     *
     * 用独立的 grown 标志而不是 appeared：appeared 同时驱动缩放与透明度，
     * 若共用，窗口会在还很小时就开始淡入，反而看不到"长大"。
     */
    // ⚠️ 尺寸只有**一个**真相：windowItem.width / windowItem.height。
    //
    // 旧实现这里有个 grown 分支，且稳定后**恒读** windowItem.growTargetWidth/Height。
    // 但 growTarget* 只在开窗那一刻被赋值（FloatingWindowManager 的构造参数），
    // resizeWindow() 从头到尾不写它 —— 于是拖右下角把手时 window.width 一直在变、
    // 渲染读的却始终是那个不变的 growTarget，**窗口视觉尺寸纹丝不动**。
    // 这就是"小窗无法调节大小"的根因，也解释了为什么它和画布卡片互相牵连：
    // 命中矩形、边缘夹取、卡片让位全都读权威值（width/height），
    // 只有渲染读的是另一个字段 → 看得见的窗和算得着的窗不是同一个大小。
    //
    // 现在：生长动画不再靠第二个尺寸字段，而是"第一帧渲染卡片尺寸，
    // 下一帧把**权威尺寸**改成目标尺寸"，由 animateDpAsState 从旧值插值到新值。
    // 动画观感与原来一致，但结束后权威值就是可见值，缩放自然生效。
    LaunchedEffect(windowItem.id) {
        if (windowItem.isGrowing) {
            // 让"起始尺寸"（= 卡片尺寸）实打实渲染一帧，
            // 否则动画会从目标尺寸开始，看不到"原地长大"
            withFrameNanos { }
            windowItem.width = windowItem.growTargetWidth
            windowItem.height = windowItem.growTargetHeight
            windowItem.isGrowing = false
        }
    }

    // ⚠️ 尺寸**不能**用低阻尼弹簧：`DampingRatioLowBouncy` 会让尺寸冲过头再收回来，
    // 贴靠/改变大小时表现为整扇窗"弹一下"—— 用户反馈的"大幅回弹"有一半来自这里。
    // 改成 NoBouncy：仍然平滑过渡，但绝不越过目标值。
    val springSpec = spring<Dp>(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMedium
    )

    // ⚠️ 全屏时窗口必须**下移到顶栏下沿**（topInsetPx），不能停在 y=0。
    //
    // 窗口层挂在根 Box 上，y=0 正是 Scaffold 顶栏（标题栏 + 标签行）占的位置。
    // 旧代码全屏后 offset 归零、高度取整屏 → 最大化的小窗正好**盖住顶栏**：
    // 用户切不了标签、回不到上一层（"返回不了"），而且窗口自己的标题栏被顶到
    // 状态栏底下，连关闭按钮都点不到 —— 就是"放大后被顶部栏位遮挡"。
    //
    // ⚠️ 判据不能只看 space：图空间（GRAPH）的小窗在进入**真全屏**后，
    // 会被 MainActivity 接管到屏幕浮层渲染（见 MainActivity 的渲染筛选），
    // 此时它已经不在画布里了 —— y=0 正好落在 Scaffold 顶栏底下，
    // 标题栏（唯一的拖拽把手与关闭按钮）会被顶栏盖住、点不到，
    // 也就是历史上"放大后返回不了"的成因。
    //
    // 只有**画布全屏**（仍留在画布内、坐标是画布坐标、不受顶栏影响）才保持 0。
    val fullscreenTopPx =
        if (windowItem.space == WindowSpace.SCREEN || windowItem.isFullScreen) {
            manager.topInsetPx
        } else {
            0f
        }
    val fullscreenTopDp =
        with(androidx.compose.ui.platform.LocalDensity.current) { fullscreenTopPx.toDp() }

    // 「是否撑满」= 真全屏 **或** 画布全屏。
    //
    // 两者在**卡片自身**的渲染上完全一样（都占满可用区域、都去掉圆角与阴影），
    // 差别只在于外层坐标系：真全屏由 MainActivity 画在屏幕浮层（脱离画布），
    // 画布全屏仍留在画布里随 pan/scale 缩放。所以这里合成一个量，
    // 下面所有"全屏时如何"的分支只需判它一处，不会漏改。
    val isAnyFullScreen = windowItem.isFullScreen || windowItem.isCanvasFullScreen

    val animatedWidth by animateDpAsState(
        targetValue = if (isAnyFullScreen) screenWidth else windowItem.width,
        animationSpec = springSpec,
        label = "windowWidth"
    )
    val animatedHeight by animateDpAsState(
        targetValue = if (isAnyFullScreen) {
            // 扣掉顶栏高度，最大化后仍能看到并可点顶栏与自己的标题栏
            (screenHeight - fullscreenTopDp).coerceAtLeast(160.dp)
        } else windowItem.height,
        animationSpec = springSpec,
        label = "windowHeight"
    )
    // ⚠️ 位置插值**只在进入全屏时保留**，其余一切（拖动、贴靠、还原）
    // 一律用 snap 瞬时生效。
    //
    // 为什么把之前那版再收紧：上一版只在"手指还按着"时去插值，
    // 但松手之后的**贴靠**仍走 spring —— 于是窗口先滑到边界、再被弹簧"弹"到
    // 贴靠位，用户看到的就是"鼠标拖完大幅回弹"。贴靠本身是直接操控的结果，
    // 不该有二次动画；需要弹性的地方（开窗生长、进出全屏）各自有专门动画。
    //
    // （这里原本还有一个 zeroLatency 局部量，条件里包含"正在惯性滑行"；
    //  惯性甩动已按用户要求整段删除，那条分支永远为假、该局部量也无人使用，
    //  连同 Manager 里的 beginFling/endFling/activeFlingingWindowId 一并移除。）
    val offsetSpec = if (isAnyFullScreen) {
        spring(stiffness = Spring.StiffnessMedium)
    } else {
        snap<Float>()
    }
    val animatedOffsetX by animateFloatAsState(
        targetValue = if (isAnyFullScreen) 0f else windowItem.offset.x,
        animationSpec = offsetSpec,
        label = "offsetX"
    )
    val animatedOffsetY by animateFloatAsState(
        // 全屏时落到顶栏下沿（见 fullscreenTopPx 的说明），否则用窗口自己的坐标
        targetValue = if (isAnyFullScreen) fullscreenTopPx else windowItem.offset.y,
        animationSpec = offsetSpec,
        label = "offsetY"
    )

    val growScale by animateFloatAsState(
        targetValue = if (appeared) 1f else 0.80f,
        animationSpec = spring(dampingRatio = 0.48f, stiffness = 420f),
        label = "growScale"
    )
    val growAlpha by animateFloatAsState(
        targetValue = if (appeared) 1f else 0f,
        animationSpec = tween(170),
        label = "growAlpha"
    )

    AnimatedVisibility(
        visible = !windowItem.isMinimized,
        enter = scaleIn(initialScale = 0.85f) + fadeIn(tween(180)),
        exit = scaleOut(targetScale = 0.62f) + fadeOut(tween(150)),
        modifier = Modifier
            .offset {
                // 位置已由父级施加（画布）时返回零位移：
                // 外层 Box 已按 (offset*scale + pan) 定位过，这里再偏一次就是双重施加。
                if (positionedByParent) {
                    IntOffset.Zero
                } else {
                    IntOffset(animatedOffsetX.roundToInt(), animatedOffsetY.roundToInt())
                }
            }
            .zIndex(windowItem.zIndex)
    ) {
        Box(
            modifier = Modifier
                .size(animatedWidth, animatedHeight)
                .graphicsLayer {
                    scaleX = growScale
                    scaleY = growScale
                    alpha = growAlpha
                    // 从左上角生长：父窗口通常位于左上方，视觉上形成"衍生"关系
                    transformOrigin = TransformOrigin(0.08f, 0f)
                }
                // 碰谁谁置顶：在任何交互发生前先提升层级
                .pointerInput(windowItem.id) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(
                                androidx.compose.ui.input.pointer.PointerEventPass.Initial
                            )
                            if (event.changes.any { it.pressed }) {
                                manager.bringToFront(windowItem.id)
                            }
                        }
                    }
                }
                .shadow(
                    elevation = if (isAnyFullScreen) 0.dp else 20.dp,
                    shape = RoundedCornerShape(if (isAnyFullScreen) 0.dp else 16.dp),
                    spotColor = Color.Black.copy(alpha = 0.65f)
                )
                .clip(RoundedCornerShape(if (isAnyFullScreen) 0.dp else 16.dp))
                .background(DarkSurface)
                .border(
                    width = if (isAnyFullScreen) 0.dp else 1.dp,
                    color = if (manager.activeDraggingWindowId == windowItem.id) {
                        AccentNeonGreen
                    } else {
                        WindowBorderColor
                    },
                    shape = RoundedCornerShape(if (isAnyFullScreen) 0.dp else 16.dp)
                )
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                WindowTitleBar(
                    windowItem = windowItem,
                    manager = manager,
                    onRename = onRename,
                    onDelete = onDelete,
                    // 标题栏拖动也要按容器缩放比换算，见 WindowTitleBar 的说明
                    contentScale = contentScale,
                    positionedByParent = positionedByParent,
                    edgeScroller = edgeScroller,
                    edgeViewportPx = edgeViewportPx,
                    edgeZonePx = edgeZonePx
                )

                // 顶部可点击面包屑（视觉系带 / 溯源）。放在标题栏正下方：
                // 这是"我在树的哪一层、从哪句话长出来"的位置语义，应当紧贴标题。
                WindowBreadcrumb(
                    windowItem = windowItem,
                    ancestors = ancestors ?: windowItem.ancestors,
                    onQuoteClick = onQuoteClick,
                    onBreadcrumbClick = onBreadcrumbClick
                )

                if (!windowItem.originQuote.isNullOrBlank()) {
                    QuoteOriginCard(
                        quote = windowItem.originQuote,
                        depth = windowItem.depth,
                        onClick = onQuoteClick?.let { callback ->
                            { callback(windowItem.sourceMessageId) }
                        }
                    )
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    // 工具窗（如阅读器的"选句翻译"）：内容挂在窗口项上，直接渲染它，
                    // **不调用**宿主传入的 content —— 那一份是对话渲染，对工具窗无意义。
                    val custom = windowItem.customContent
                    if (custom != null) custom() else content()
                }
            }

            // 悬浮小窗多向拉伸边缘与把手（右侧边缘调宽、底侧边缘调高、右下角双向缩放）
            if (!isAnyFullScreen) {
                // 1. 右侧边缘手势热区 (只调宽度，宽 24dp，带优雅微指示条)
                Box(
                    modifier = Modifier
                        // ⚠️ 热区必须**窄**：这条竖条叠在内容之上，24dp 时正文右侧
                        // 24dp 内的文字按不中 —— 一按就变成"拉窗口宽度"，用户看到的就是
                        // "小窗里选不中词"（表格最宽，必然命中）。8dp 仍抓得住边缘，
                        // 但基本不再侵入正文。
                        .align(Alignment.CenterEnd)
                        .width(8.dp)
                        .fillMaxHeight()
                        .padding(top = 48.dp, bottom = 32.dp)
                        .background(Color.Transparent)
                        .pointerHoverIcon(PointerIcon.Crosshair)
                        .pointerInput(windowItem.id, contentScale) {
                            detectDragGestures { change, dragAmount ->
                                change.consume()
                                val s = contentScale.coerceAtLeast(0.05f)
                                with(density) {
                                    manager.resizeWindow(
                                        windowItem.id,
                                        (dragAmount.x / s).toDp(),
                                        0.dp
                                    )
                                }
                            }
                        },
                    contentAlignment = Alignment.CenterEnd
                ) {
                    // 右侧边缘微胶囊指示器
                    Box(
                        modifier = Modifier
                            .padding(end = 3.dp)
                            .width(3.dp)
                            .height(28.dp)
                            .clip(RoundedCornerShape(1.5.dp))
                            .background(TextMuted.copy(alpha = 0.5f))
                    )
                }

                // 2. 底侧边缘手势热区 (只调高度，高 24dp，带优雅微指示条)
                Box(
                    modifier = Modifier
                        // 同理：底部横条由 24dp 收到 8dp，避免吃掉最后一行正文的选词
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(8.dp)
                        .padding(start = 32.dp, end = 32.dp)
                        .background(Color.Transparent)
                        .pointerHoverIcon(PointerIcon.Crosshair)
                        .pointerInput(windowItem.id, contentScale) {
                            detectDragGestures { change, dragAmount ->
                                change.consume()
                                val s = contentScale.coerceAtLeast(0.05f)
                                with(density) {
                                    manager.resizeWindow(
                                        windowItem.id,
                                        0.dp,
                                        (dragAmount.y / s).toDp()
                                    )
                                }
                            }
                        },
                    contentAlignment = Alignment.BottomCenter
                ) {
                    // 底侧边缘微胶囊指示器
                    Box(
                        modifier = Modifier
                            .padding(bottom = 3.dp)
                            .height(3.dp)
                            .width(28.dp)
                            .clip(RoundedCornerShape(1.5.dp))
                            .background(TextMuted.copy(alpha = 0.5f))
                    )
                }

                // 3. 右下角双向拉伸把手 (同时调宽高)
                Box(
                    modifier = Modifier
                        // 右下角双向缩放把手：32dp -> 16dp（原来会盖住正文右下角一小块，
                        // 在表格/长段落上表现为"右下角那一片选不中"）
                        .align(Alignment.BottomEnd)
                        .size(16.dp)
                        .background(Color.Transparent)
                        .pointerHoverIcon(PointerIcon.Crosshair)
                        .pointerInput(windowItem.id, contentScale) {
                            detectDragGestures { change, dragAmount ->
                                change.consume()
                                val s = contentScale.coerceAtLeast(0.05f)
                                with(density) {
                                    manager.resizeWindow(
                                        windowItem.id,
                                        (dragAmount.x / s).toDp(),
                                        (dragAmount.y / s).toDp()
                                    )
                                }
                            }
                        },
                    contentAlignment = Alignment.BottomEnd
                ) {
                    Box(
                        modifier = Modifier
                            .padding(6.dp)
                            .size(10.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(TextMuted.copy(alpha = 0.8f))
                    )
                }
            }
        }
    }
}

// ============================================================================
// 标题栏
// ============================================================================

@Composable
private fun WindowTitleBar(
    windowItem: FloatingWindowItem,
    manager: FloatingWindowManager,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
    /**
     * 外层容器的缩放比（画布传 graphState.scale，屏幕浮层为 1）。
     * 画布把整扇窗缩放后，拖动回调给的是未缩放的局部像素，必须除回去。
     */
    contentScale: Float = 1f,
    /**
     * 该窗口是否由父级（画布）定位。
     *
     * 只有图空间小窗为 true：它拖动时应当触发父画布的边缘自动平移，
     * 全局浮层（SCREEN 空间）没有可平移的父画布，传 false 即不参与。
     */
    positionedByParent: Boolean = false,
    /** 边缘自动平移器（只在图空间小窗传入）。 */
    edgeScroller: EdgeAutoScroller? = null,
    /** 画布视口尺寸（px），供边缘感应判定用。 */
    edgeViewportPx: Size = Size.Zero,
    /** 边缘感应区宽度（px）。 */
    edgeZonePx: Float = 44f
) {
    val clipboard = LocalClipboardManager.current

    // 惯性滑行改由组合层**逐帧**驱动（见 onDragEnd）：旧实现在 Manager 里用同步
    // for 循环一次性写完全部位移，鼠标快速甩动时直接撞视口边界被截断，就是"回弹"。

    // ---- 停住一秒才吸附（Windows 式：先出预览，停稳才落位）----
    //
    // 为什么用"令牌 + LaunchedEffect"而不是计时器对象：令牌在"换了吸附位"或
    // "同一区域内明显移动"时自增，协程随即被取消重启，计时天然从头再来 ——
    // 不需要自己维护取消与竞态。
    //
    // ⚠️ 这是**唯一**的吸附入口：松手不再贴（见 onDragEnd）。
    // 用户明确要求"停留一秒分屏，否则不吸附"。
    val dwellToken = manager.snapDwellToken
    val dwellTarget = manager.snapPreview?.target
    LaunchedEffect(dwellToken, dwellTarget) {
        if (dwellTarget == null) return@LaunchedEffect
        kotlinx.coroutines.delay(DWELL_SNAP_MS)
        // 走到这里说明这两秒内既没换区域、也没明显移动
        manager.applySnap(windowItem.id, dwellTarget)
        manager.clearSnapPreview()
    }

    var menuExpanded by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }

    LaunchedEffect(copied) {
        if (copied) {
            kotlinx.coroutines.delay(1400)
            copied = false
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(46.dp)
            .background(DarkSurfaceVariant)
            .padding(horizontal = 10.dp)
            .pointerHoverIcon(PointerIcon.Hand)
            // 双击标题栏 = 全屏 / 还原
            .pointerInput(windowItem.id) {
                detectTapGestures(
                    onDoubleTap = { manager.toggleFullScreen(windowItem.id) }
                )
            }
            // 金科玉律 1：单例拖拽控制器，只有一个窗口能被拖动
            //
            // ⚠️ key 必须同时含两种全屏状态：pointerInput 的 lambda 会被记住，
            // key 不变就不重启协程 —— 于是"切到画布全屏后"里面的守卫
            // 仍按旧的 key 跑，要么还能拖（已全屏却拖得动）、
            // 要么拖不动（已还原却仍被锁）。
            .pointerInput(
                windowItem.id,
                windowItem.isFullScreen,
                windowItem.isCanvasFullScreen,
                contentScale
            ) {
                // 与 FloatingWindowCardImpl 里的同名量同义（标题栏是独立 composable，
                // 取不到那边的局部量，故就地算一次）。
                val isAnyFullScreen =
                    windowItem.isFullScreen || windowItem.isCanvasFullScreen
                if (!isAnyFullScreen) {
                    detectDragGestures(
                        onDragStart = {
                            // 新的一次拖拽要接管：先把上一轮惯性停掉，
                            // 否则两股力量同时写 offset，窗口会"自己跑"。
                            // 一拖就解除贴靠：把窗口从"钉在角上"的形态还原成
                            // 贴靠前的大小，之后随手指自由移动（含往下拖）。
                            manager.clearSnap(windowItem.id)
                            manager.startDrag(windowItem.id)
                        },
                        onDragEnd = {
                            // 拖拽结束：必须停掉边缘自动平移（否则松手后画布还自己挪）
                            edgeScroller?.stop()
                            // ---- 松手**不贴**（用户明确要求：停留一秒才吸附，否则不吸附）----
                            //
                            // 之前这里是"松手即贴"（Windows 规则：看到影子松手就落位）。
                            // 用户后来要求改为**只有停稳一秒**才吸附 —— 松手即贴会在
                            // "只是路过边缘"时误贴，而停留判定对用户是可见的意图表达。
                            // 因此这里只清掉预览：真吸附一律由上面的 dwell LaunchedEffect 触发。
                            manager.clearSnapPreview()
                            // ⚠️ 惯性甩动（fling）**已整段删除**。
                            //
                            // 用户明确要求"永远不会弹"。甩动的本质是松手后窗口继续
                            // 自己滑行：一旦撞到屏幕边界就被 clamp 截断，观感就是
                            // "弹回去"或"滑走一段"。鼠标用户期待的是**松手即停**，
                            // 与手指甩动的习惯不同。
                            // 现在松手只做一件事：放开拖拽锁（贴靠改由"停住一秒"触发）。
                            manager.endDrag()
                        },
                        onDragCancel = {
                            edgeScroller?.stop()
                            manager.endDrag()
                            manager.clearSnapPreview()
                        },
                        onDrag = { change, dragAmount ->
                            // 已被贴靠时，任何移动都先解除贴靠（恢复原尺寸），
                            // 然后继续跟手拖动 —— 这正是"吸附后往下拖能取消吸附"。
                            if (windowItem.isSnapped) manager.clearSnap(windowItem.id)
                            // 每次移动都重算吸附预览：进入感应区就显示"要贴到哪儿"，
                            // 离开就消失；同一区域内的明显移动会重置停留计时。
                            manager.updateSnapPreview(windowItem.id, change.position)
                            // 画布内的小窗拖到画布边缘时，缓慢顺势挪动画布
                            // （越靠边越快，有上限）。只对图空间小窗生效：
                            // 全局浮层（SCREEN 空间）没有可平移的父画布。
                            if (positionedByParent && edgeScroller != null) {
                                edgeScroller.update(
                                    position = change.position,
                                    viewport = edgeViewportPx,
                                    edgeZonePx = edgeZonePx
                                )
                            }
                            change.consume()
                            // 位移除以缩放比，放大/缩小时才跟手
                            val s = contentScale.coerceAtLeast(0.05f)
                            manager.onDrag(dragAmount / s)
                        }
                    )
                }
            },
        verticalAlignment = Alignment.CenterVertically
    ) {
        // ---- 三个 macOS 圆点 + 第四个「画布全屏」点 ----
        //
        // 第 3 个绿点 = **真全屏**：屏幕级，与对话界面的小窗完全一致
        // （脱离画布、铺满可读区域、不受画布 pan/scale 影响）。
        // 第 4 个点 = **画布全屏**：把窗口撑满画布可见区（此前绿点就是这个行为，
        // 用户视角上仍不是全屏，故拆分到独立按钮保留）。
        //
        // 为什么必须拆成两个独立状态而不是一个开关的两档：两个按钮要能各自
        // 进出，且退出其中一个不能把另一个也带出来。
        Row(
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            MacDotButton(DangerRed) { manager.closeWindow(windowItem.id) }
            MacDotButton(WarningYellow) { manager.toggleMinimize(windowItem.id) }
            MacDotButton(
                AccentNeonGreen,
                description = "全屏（与对话界面一致，铺满整个屏幕）",
            ) { manager.toggleFullScreen(windowItem.id) }
            // 第 4 个点：画布全屏。屏幕空间的小窗没有"画布"可撑，
            // 此时它退化为真全屏（见 manager 的 toggleCanvasFullScreen）。
            MacDotButton(
                AccentIceBlue,
                description = "画布全屏（撑满画布可见区，不脱离画布）",
            ) { manager.toggleCanvasFullScreen(windowItem.id) }
        }

        // 标题
        Row(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Default.AccountTree,
                contentDescription = null,
                tint = AccentNeonGreen,
                modifier = Modifier.size(13.dp)
            )
            Spacer(modifier = Modifier.width(5.dp))
            Text(
                text = "L${windowItem.depth} · ${windowItem.title}",
                color = TextPrimary,
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Medium,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        // 溢出菜单
        Box {
            Icon(
                imageVector = Icons.Default.MoreVert,
                contentDescription = "更多操作",
                tint = TextMuted,
                modifier = Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { menuExpanded = true }
            )

            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false }
            ) {
                DropdownMenuItem(
                    text = { Text("重命名分支", fontSize = 13.sp) },
                    leadingIcon = {
                        Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(17.dp))
                    },
                    onClick = {
                        menuExpanded = false
                        showRenameDialog = true
                    }
                )
                DropdownMenuItem(
                    text = {
                        Text(if (copied) "已复制" else "复制全文", fontSize = 13.sp)
                    },
                    leadingIcon = {
                        Icon(
                            Icons.Default.ContentCopy,
                            contentDescription = null,
                            modifier = Modifier.size(17.dp)
                        )
                    },
                    onClick = {
                        menuExpanded = false
                        clipboard.setText(AnnotatedString(windowItem.originQuote.orEmpty()))
                        copied = true
                    }
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                DropdownMenuItem(
                    text = { Text("删除此分支", fontSize = 13.sp, color = DangerRed) },
                    leadingIcon = {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = null,
                            tint = DangerRed,
                            modifier = Modifier.size(17.dp)
                        )
                    },
                    onClick = {
                        menuExpanded = false
                        showDeleteConfirm = true
                    }
                )
            }
        }

        Spacer(modifier = Modifier.width(4.dp))

        Icon(
            imageVector = if (windowItem.isFullScreen) {
                Icons.Default.FullscreenExit
            } else {
                Icons.Default.Fullscreen
            },
            contentDescription = "全屏（与对话界面一致）",
            tint = TextMuted,
            modifier = Modifier
                .size(17.dp)
                .clip(CircleShape)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { manager.toggleFullScreen(windowItem.id) }
        )
    }

    // 重命名对话框
    if (showRenameDialog) {
        var draft by remember { mutableStateOf(windowItem.title) }
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            containerColor = DarkSurface,
            title = { Text("重命名分支", color = TextPrimary, fontSize = 15.sp) },
            text = {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    singleLine = true,
                    shape = RoundedCornerShape(8.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AccentNeonGreen,
                        unfocusedBorderColor = WindowBorderColor,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onRename(draft)
                    showRenameDialog = false
                }) {
                    Text("确定", color = AccentNeonGreen)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) {
                    Text("取消", color = TextMuted)
                }
            }
        )
    }

    // 删除确认
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            containerColor = DarkSurface,
            title = { Text("删除此分支？", color = TextPrimary, fontSize = 15.sp) },
            text = {
                Text(
                    text = "该分支及其所有下级子分支的对话记录都会被永久删除，此操作不可撤销。",
                    color = TextSecondary,
                    fontSize = 12.5.sp,
                    lineHeight = 18.sp
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    onDelete()
                }) {
                    Text("删除", color = DangerRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("取消", color = TextMuted)
                }
            }
        )
    }
}

/**
 * macOS 风格圆点按钮（红=关闭 / 黄=最小化 / 绿=全屏）。
 *
 * ## 为什么这里只留一个手势检测器
 * 旧实现是「一个 pointerInput 在 Initial 阶段把按下事件全部 `consume()`」+
 * 「再挂一个 clickable」，注释说前者的用途是"阻断冒泡到标题栏拖拽"。
 * 但那样内层的 clickable 等到的 `down` 已经被消费，按 Compose 的约定
 * 它不再把这次按压当作一次点击 —— 圆点就成了点不动的死按钮。
 * （这正是项目铁律 5：一个可交互区域只放一个手势检测器。）
 *
 * 现在换成一个 `detectTapGestures`：它在收到 down 时**立即消费**，
 * 本身就能阻断冒泡到标题栏的拖拽；而标题栏的拖拽需要先越过 touch slop，
 * 不会被一次轻点触发。一个检测器同时满足两个需求。
 *
 * ## 为什么命中区是 24dp 而圆点只有 12dp
 * 12dp 在手机上极难命中（远低于 48dp 的最小推荐触摸目标）。
 * 外面套一层 24dp 的透明命中区：视觉不变，可点性翻倍。
 */
@Composable
private fun MacDotButton(
    color: Color,
    /**
     * 无障碍描述。
     *
     * 为什么必须给：第 4 个点承载的是**画布全屏**，它和绿点的"真全屏"视觉上
     * 都是一个小圆点、位置只差一个。没有描述时读屏软件只能报"按钮"，
     * 用户无从知道这两个点分别做什么。
     */
    description: String = "",
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(24.dp)
            .pointerInput(Unit) {
                detectTapGestures(onTap = { onClick() })
            },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .clip(CircleShape)
                .background(color)
        )
        if (description.isNotEmpty()) {
            // 描述以语义形式挂在这枚点的位置上：不画文字（会破坏标题栏布局），
            // 但读屏与长按提示都能取到。
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .semantics { contentDescription = description }
            )
        }
    }
}

/**
 * 小窗顶部的可点击面包屑路径（视觉系带 / 溯源）。
 *
 * ## 为什么需要它
 * 递归开窗到第三、四层之后，用户在小窗里读着读着就"忘了这扇窗是从哪句话长出来的"，
 * 也不容易回到来源。面包屑把"层级 + 各层标题"摊平成一条可点击的路径
 * （如「真题解析 › 语音流变 › 同化」），点任意一层即可定位回母窗口对应位置。
 *
 * ## 祖先链未知时的降级（诚实原则）
 * [FloatingWindowItem.ancestors] 由开窗的调用点传入。若调用点没有提供
 * （例如从对话列表直接开的窗），这里**不虚构**祖先标题，而是退化为
 * 「来源 › 当前」两级：来源一节点它走 [onQuoteClick]（跳回来源消息），
 * 这正好复用已有的"引用卡片"通路。
 *
 * ## 点击优先级
 * 有 [onBreadcrumbClick]（画布能把节点真正聚焦/高亮）时优先用它；
 * 否则退回该节自带的 sourceMessageId 跳转。
 */
@Composable
private fun WindowBreadcrumb(
    windowItem: FloatingWindowItem,
    ancestors: List<WindowCrumb>,
    onQuoteClick: ((String?) -> Unit)?,
    onBreadcrumbClick: ((WindowCrumb) -> Unit)?
) {
    val hasSource = !windowItem.sourceMessageId.isNullOrBlank()
    // 既没有祖先链、也没有来源消息、还是根层：没有可展示的路径，整条不画。
    if (ancestors.isEmpty() && !hasSource && windowItem.depth <= 1) return

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .background(DarkSurfaceVariant.copy(alpha = 0.55f))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.AccountTree,
            contentDescription = null,
            tint = AccentNeonGreen,
            modifier = Modifier.size(11.dp)
        )
        Spacer(modifier = Modifier.width(5.dp))

        if (ancestors.isEmpty()) {
            BreadcrumbNode(
                label = if (windowItem.depth > 1) "L${windowItem.depth - 1} 来源" else "来源",
                enabled = hasSource && onQuoteClick != null,
                current = false,
                onClick = { onQuoteClick?.invoke(windowItem.sourceMessageId) }
            )
            BreadcrumbSeparator()
        } else {
            ancestors.forEach { crumb ->
                BreadcrumbNode(
                    label = crumb.title,
                    enabled = true,
                    current = false,
                    onClick = {
                        if (onBreadcrumbClick != null) {
                            onBreadcrumbClick(crumb)
                        } else if (crumb.sourceMessageId != null) {
                            onQuoteClick?.invoke(crumb.sourceMessageId)
                        }
                    }
                )
                BreadcrumbSeparator()
            }
        }

        // 当前层（不可点，只作"你在这里"的定位）
        BreadcrumbNode(
            label = windowItem.title,
            enabled = false,
            current = true,
            onClick = {}
        )
    }
}

@Composable
private fun BreadcrumbNode(
    label: String,
    enabled: Boolean,
    current: Boolean,
    onClick: () -> Unit
) {
    val color = when {
        current -> TextPrimary
        enabled -> AccentNeonGreen
        else -> TextMuted
    }
    Text(
        text = label,
        color = color,
        fontSize = 9.5.sp,
        fontWeight = if (current) FontWeight.SemiBold else FontWeight.Medium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .then(
                if (enabled) {
                    Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { onClick() }
                } else {
                    Modifier
                }
            )
            .padding(horizontal = 3.dp, vertical = 1.dp)
    )
}

@Composable
private fun BreadcrumbSeparator() {
    Text(
        text = "›",
        color = TextMuted,
        fontSize = 9.5.sp,
        modifier = Modifier.padding(horizontal = 3.dp)
    )
}

@Composable
private fun QuoteOriginCard(
    quote: String,
    depth: Int,
    onClick: (() -> Unit)? = null
) {
    val clickable = onClick != null
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .clickable(enabled = clickable) { onClick?.invoke() },
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = AccentNeonGreen.copy(alpha = 0.07f)
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .width(2.5.dp)
                    .height(26.dp)
                    .clip(CircleShape)
                    .background(AccentNeonGreen)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    // 可点击时才提"跳回原文"：旧数据没有来源消息 id，
                    // 此时这张卡片点不动，写了提示反而让人以为坏了
                    text = if (clickable) {
                        "引用上级讨论（L${depth - 1} 来源）· 点击跳回原文"
                    } else {
                        "引用上级讨论（L${depth - 1} 来源）"
                    },
                    color = AccentNeonGreen,
                    fontSize = 9.5.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = quote,
                    color = TextSecondary,
                    fontSize = 10.5.sp,
                    lineHeight = 14.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (clickable) {
                Spacer(modifier = Modifier.width(6.dp))
                Icon(
                    imageVector = Icons.Default.ArrowOutward,
                    contentDescription = "跳回原文",
                    tint = AccentNeonGreen,
                    modifier = Modifier.size(13.dp)
                )
            }
        }
    }
}
