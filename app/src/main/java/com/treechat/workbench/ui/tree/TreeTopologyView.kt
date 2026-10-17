package com.treechat.workbench.ui.tree

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CallMerge
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.UnfoldLess
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.treechat.workbench.data.model.ConversationNode
import com.treechat.workbench.data.prefs.GraphLayoutSnapshot
import com.treechat.workbench.data.prefs.GraphLayoutStore
import com.treechat.workbench.data.prefs.GraphNodeLayout
import com.treechat.workbench.data.prefs.GraphWheelMode
import com.treechat.workbench.ui.chat.BranchMergeJob
import com.treechat.workbench.ui.chat.ChatNodeViewModel
import com.treechat.workbench.ui.chat.NodeChatContent
import com.treechat.workbench.ui.theme.AccentIceBlue
import com.treechat.workbench.ui.theme.AccentNeonGreen
import com.treechat.workbench.ui.theme.DangerRed
import com.treechat.workbench.ui.theme.DarkBackground
import com.treechat.workbench.ui.theme.DarkSurface
import com.treechat.workbench.ui.theme.DarkSurfaceVariant
import com.treechat.workbench.ui.theme.TextMuted
import com.treechat.workbench.ui.theme.TextPrimary
import com.treechat.workbench.ui.theme.TextSecondary
import com.treechat.workbench.ui.theme.WindowBorderColor
import com.treechat.workbench.ui.window.FloatingWindowCard
import com.treechat.workbench.ui.window.FloatingWindowManager
import com.treechat.workbench.ui.window.WindowCrumb
import com.treechat.workbench.ui.window.WindowDefaults
import com.treechat.workbench.ui.window.WindowSpace
import com.treechat.workbench.ui.window.WindowTetherState
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

private val CARD_WIDTH = 186.dp
private val CARD_HEIGHT = 76.dp

/**
 * 安卓桌面式拖放的落点门槛：被拖卡片与目标卡片的交叠面积
 * 至少要达到**被拖卡片面积**的这个比例，才算"放上去了"。
 *
 * 为什么不判"面积 > 0 即可"：布局的列距只比卡宽大几十像素，
 * 随手挪一下就会压到邻卡，而那会误触发挂载（改树结构的操作）。
 * 0.18 这个值让"顺带一挪（约 58px 交叠，≈15%）"够不着，
 * 而"真把卡片盖上去（≈100%）"稳稳命中。
 */
private const val DROP_OVERLAP_RATIO = 0.18f

/**
 * 语义缩放（Semantic Zoom / LOD）阈值。
 *
 * 画布 `scale` 低于此值时，卡片进入"仅标题"层：隐藏 originQuote 正文长文本，
 * 只保留层级徽标（ROOT / Lxx）与节点标题。放大回阈值以上自动恢复。
 *
 * 依据：外部 HCI 审阅意见给定 `scale < 0.6f`。低于六成缩放时，卡片正文
 * （10sp × 0.6 ≈ 6pt）在屏幕上已不可读，继续逐字渲染只是徒增绘制开销、
 * 并在密集的思维导图上制造视觉噪点 —— 此时用户关心的是"整棵树的形状"，
 * 而不是某张卡片的正文。
 */
private const val CARD_LOD_SCALE_THRESHOLD = 0.6f

/**
 * 边缘自动平移的感应区宽度（视口像素）。
 *
 * 取 44px ≈ 一个小按钮的触摸目标。太窄会"顶到边了却还不挪"，
 * 太宽则会在还没靠近边缘时就乱挪（尤其在半屏窗口里，视口本来就不大，
 * 感应区过大等于整片都是边缘区 —— EdgeAutoScroller.update 里另外做了
 * "不超过半个视口"的夹取作为兜底）。
 */
private const val EDGE_SCROLL_ZONE_PX = 44f

/**
 * 图空间小窗"全屏"时四周留出的边距。
 *
 * 不留边距的话，全屏后卡片会严丝合缝盖住整块画布：
 * 用户既看不到画布背景（失去"我在画布里"的空间感），
 * 也点不到画布空白区域 —— 而点空白是平移/touch 取消的入口。
 */
private val GRAPH_FULLSCREEN_INSET = 12.dp

/** 卡片内容区的内边距（与 NodeCard 的 `.padding(CARD_PAD)` 保持一致）。 */
private val CARD_PAD = 10.dp

/**
 * 卡片两个角标按钮（右上角"更多"、左下角"折叠"）的命中尺寸。
 *
 * 比可见图形（约 14dp）大一圈，手指才容易点到；命中区与绘制共用这一常量，
 * 所以不会出现"看得见却点不到"或反过来。
 */
private val CORNER_BUTTON = 24.dp

/**
 * 一次拖动开始于卡片的哪个部位 —— 决定这次拖动是"改大小"还是"改位置"。
 *
 * 由画布在按下时判定一次，整个拖动过程中不再改变。
 * 之所以要在按下时就定死：拖动中途手指可能滑出把手区，若每帧重判，
 * 会变成"拖着拖着一半改大小一半改位置"，手感彻底乱掉。
 */
private enum class ResizeRole {
    /** 卡片本体 —— 拖动改变位置。 */
    NONE,
    /** 右边缘 —— 只改宽。 */
    EDGE_RIGHT,
    /** 下边缘 —— 只改高。 */
    EDGE_BOTTOM,
    /** 右下角 —— 同时改宽高。 */
    CORNER
}

/**
 * 画布命中测试所需的快照（在组合作用域里算好，手势里只读不算）。
 *
 * 为什么要把命中数据集中成一个对象：卡片的触摸判定必须在**与绘制完全相同的
 * 坐标数学**下进行。若手势在卡片自己的 pointerInput 里、用卡片本地坐标判断，
 * 而卡片又被 graphicsLayer 缩放过，两套坐标就会不一致 —— 这正是"边缘改不了
 * 大小、卡片拖不动"的根源。集中到画布层后，判定与绘制共用同一份数据。
 *
 * 坐标一律用**内容坐标**（未乘 scale、未加 pan），手势里先做逆变换再查这张表。
 */
private class CanvasHitState(
    /** nodeId -> 内容坐标下的矩形（含挤出偏移）。 */
    val cardRects: Map<String, Rect>,
    /**
     * nodeId -> 内容坐标下的**基准**矩形（不含挤出偏移）。
     *
     * 专供"安卓桌面式拖放"判定落点用。为什么不复用 [cardRects]：
     * 拖动时邻居卡片会沿最小分离轴**一直躲开**（`maxPush` 高达 1400dp，等于不设上限），
     * 所以被拖的卡片与任何邻居**永远不会在视觉上重叠** —— 拿含位移的矩形做交叠判定
     * 永远判不出落点。基准矩形不受挤出影响，判定因此稳定、不会在边界上反复闪。
     */
    val baseCardRects: Map<String, Rect>,
    /** 图空间小窗的内容坐标矩形：压在上面时不应把触摸透给卡片。 */
    val graphWindowRects: List<Rect>,
    /** 卡片右上角「更多」(⋮) 按钮的命中区（内容坐标）。 */
    val moreRects: Map<String, Rect>,
    /** 卡片左下角「折叠 / 展开」角标的命中区（内容坐标，仅含确有子节点的卡片）。 */
    val collapseRects: Map<String, Rect>
)

/**
 * 命中测试结果。
 */
private class CardHit(
    val nodeId: String,
    val role: ResizeRole
)

/**
 * 两个矩形的**交叠面积**（无交叠时为 0）。
 *
 * 安卓桌面式拖放用它挑落点：取交叠面积最大的那张卡，
 * 天然消解"同时压住两张"的歧义，也避免"压住一点边就抢走落点"。
 */
private fun overlapArea(a: Rect, b: Rect): Float {
    val w = minOf(a.right, b.right) - maxOf(a.left, b.left)
    val h = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)
    if (w <= 0f || h <= 0f) return 0f
    return w * h
}


/**
 * 多维工作台：可拖拽卡片 + 贝塞尔连线的对话树画布。
 *
 * 交互设计（本轮按用户要求重做）：
 * - 空白处拖拽 = 平移画布，双指捏合 = 缩放
 * - 按住卡片拖拽 = 移动该节点（增量已除以 scale，保证跟手）
 * - **点击卡片 = 就地开一个完整小窗**（可以在里面直接对话）
 * - **被小窗盖住的卡片会被挤到旁边**，小窗收起后自动弹回原位
 *
 * 用"点击"开窗：
 * 用户明确要求"还是改成点按卡片小窗打开吧"。
 * 判据是"按下后手没有移动过"（也就是一次轻点），不是长按超时 ——
 * 画布上"轻点开窗 / 按住拖动改位置 / 拖边缘改大小"三者的区别本来就只在于
 * "手有没有动、按在哪个区域"，所以用同一套坐标判定即可，不需要计时。
 * 卡片上的提示文案与此保持一致（"点击可在原地开窗对话"）。
 */
@Composable
fun TreeTopologyCanvas(
    viewModel: ChatNodeViewModel,
    graphState: GraphViewState,
    windowManager: FloatingWindowManager,
    onNodeSelected: (ConversationNode) -> Unit,
    /** 点了某张卡片的引用卡片：跳到这段引用出现的原文位置。 */
    onJumpToSourceMessage: (messageId: String) -> Unit = {},
    /**
     * 正文同文本超链接被点中（目标分支）。
     *
     * 由上层决定"打开/置顶哪扇窗"：画布自己不该知道窗口管理器的存在。
     * 与 [onJumpToSourceMessage]（跳到来源**消息**）是两件事，都不可省。
     */
    onQuoteTargetSelected: (ConversationNode) -> Unit = {},
    /**
     * 点"导出"时回调（由上层弹出格式选择与分享）。
     *
     * 不在画布里直接导出：导出需要 Activity/Context 去落盘与调起分享，
     * 而且格式选择是一个对话框 —— 那些都不该由画布组件承担。
     */
    onRequestExport: () -> Unit = {},
    /**
     * 一键将当前工作台的思维导图（含所有卡片当前坐标与宽高）快照至旁边的「手写笔记」界面。
     */
    onSnapshotToNotes: ((Map<String, Offset>, Map<String, Size>) -> Unit)? = null,
    /**
     * 画布布局持久化。传 null 表示不持久化（例如预览/测试场景）。
     *
     * 为什么需要它：位置只放在内存里的话，进程被杀就回到自动布局，
     * 用户摆好的思维导图全丢 —— 那就不算一张图了。
     */
    layoutStore: GraphLayoutStore? = null,
    /**
     * 画布边缘自动平移器（拖动卡片/小窗/在小窗里拖选时用）。
     *
     * 由上层持有而不是画布内部 remember：画布内的小窗（graph 空间）也需要
     * 在拖动时触发同一套自动平移，两份状态会各挪各的、互相打架。
     */
    edgeScroller: EdgeAutoScroller? = null,
    /**
     * 当前是否处于"画布全屏"。
     *
     * 由上层持有并在点击时翻转（见 [onToggleCanvasFullscreen]）：
     * "全屏"要收起的是**画布之外**的文件夹栏，那不在画布的控制范围内。
     * 画布只负责显示对应图标与发起切换请求。
     */
    canvasFullscreen: Boolean = false,
    /** 点画布右下角"全屏"按钮时回调；传 null 则不显示该按钮（如预览场景）。 */
    onToggleCanvasFullscreen: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val nodes by viewModel.nodes.collectAsState()
    val density = LocalDensity.current
    val activeConversationId by viewModel.activeConversationId.collectAsState()

    var viewport by remember { mutableStateOf(IntSize.Zero) }
    var hasFitted by remember { mutableStateOf(false) }

    /** 卡片手势诊断文案（仅在设置里打开诊断时显示）。 */
    var cardDiagnostic by remember { mutableStateOf<String?>(null) }

    // ---- 视觉系带（Visual Tether）：一次性高亮闪烁 ----
    //
    // 两种触发都汇到同一套状态：
    //   1. 小窗被激活（WindowTetherState.pulseToken 变化）→ 闪它的来源节点卡片；
    //   2. 点了面包屑的某一层（聚焦）→ 闪目标节点卡片。
    // flashAmount 是"已经衰减到多少"的瞬时强度，由一次 1→0 的动画驱动；
    // 用 animate（可挂起的挂起式动画）而不是 animateFloatAsState：
    // 后者表达的是"持续状态"，而闪烁是一次性事件。
    var flashNodeId by remember { mutableStateOf<String?>(null) }
    var flashTick by remember { mutableIntStateOf(0) }
    var flashAmount by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(WindowTetherState.pulseToken) {
        val id = WindowTetherState.activeNodeId ?: return@LaunchedEffect
        flashNodeId = id
        flashTick++
    }
    LaunchedEffect(flashTick) {
        if (flashTick == 0) return@LaunchedEffect
        animate(
            initialValue = 1f,
            targetValue = 0f,
            animationSpec = tween(durationMillis = 1000)
        ) { value, _ -> flashAmount = value }
        flashNodeId = null
    }

    val cardWidthPx = with(density) { CARD_WIDTH.toPx() }
    val cardHeightPx = with(density) { CARD_HEIGHT.toPx() }

    // ========================================================================
    // 折叠状态（只活在内存里，对齐旧实现 collapsedNodeIds）
    // ========================================================================
    //
    // 读取 BranchCollapseState.ids 会在组合期建立订阅：折叠/展开后本组合函数重组，
    // 可见集、布局、命中表与卡片层随之重算。
    val collapsedIds = BranchCollapseState.ids
    // 可见节点 = 全部节点 −「被折叠节点的所有后代」。被折叠节点自身仍然可见 ——
    // 这正是「收起该卡片的所有下级分支」的字面含义。
    // 后代会**整体退出**布局与命中测试（见下方 geometry / hitState 只吃 visibleNodes）。
    val visibleNodes = remember(nodes, collapsedIds) { computeVisibleNodes(nodes, collapsedIds) }
    val visibleNodeIdList = remember(visibleNodes) { visibleNodes.map { it.id } }
    val visibleIdSet = remember(visibleNodeIdList) { visibleNodeIdList.toSet() }

    // 有直接子节点时才显示折叠徽标；计数用直接子节点数（"子节点计数"）。
    val childCounts = remember(nodes) { nodes.groupingBy { it.parentId }.eachCount() }

    // 关联/挂载成功后节点集合不变（只是改了 parentId），不会触发下面的增量布局
    // effect，所以用一个 tick 手动要求重排一次。
    var relayoutTick by remember { mutableIntStateOf(0) }

    // 画布内的瞬时提示（全 Compose，不用系统 Toast）。自动消失。
    var banner by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(banner) {
        if (banner != null) {
            kotlinx.coroutines.delay(2600)
            banner = null
        }
    }

    // 三个交互的目标：节点操作菜单 / 关联已有卡片（画布就地勾选）/ 分支结论合并
    var menuNodeId by remember { mutableStateOf<String?>(null) }
    /**
     * 待删除确认的节点 id。
     *
     * 单独一个状态、而不是在菜单里直接删：删除会连带**所有下级分支的对话记录**，
     * 不可撤销，必须有一次显式确认（与浮层小窗的删除行为保持一致：
     * 那里同样是"删除此分支？"+ 红字确认）。
     */
    var pendingDeleteNodeId by remember { mutableStateOf<String?>(null) }
    var attachTargetId by remember { mutableStateOf<String?>(null) }
    var attachCandidates by remember { mutableStateOf<List<ConversationNode>>(emptyList()) }
    var attachSelectionMode by remember { mutableStateOf(false) }
    val selectedAttachIds = remember { mutableStateMapOf<String, Boolean>() }

    /**
     * 安卓桌面式拖放：拖动卡片时，指针**当前压在**哪张卡片上（null = 不压在任何卡上）。
     *
     * 只作为"落点高亮 + 松手挂载"的判据，不参与任何布局/排版计算 ——
     * 卡片挤出（geometry.displaced）与排序完全不受它影响，这一点是刻意的：
     * 用户明确要求"新增的东西不能影响卡片排序时的挤出"。
     */
    var dragDropTargetId by remember { mutableStateOf<String?>(null) }
    var mergeJob by remember { mutableStateOf<BranchMergeJob?>(null) }
    var mergeDraft by remember { mutableStateOf("") }
    val actionScope = rememberCoroutineScope()

    // ---- 恢复上次保存的布局（按会话区分）----
    //
    // 依赖里刻意同时带上 nodeIds：会话 id 变成非空时节点可能**还没加载完**，
    // 若只依赖 conversationId，恢复会漏掉那批迟到的节点。带上 nodeIds 后，
    // 节点到齐会再跑一次，把遗漏的坐标补上。
    //
    // 已标记 userPlaced 的节点不会被这里覆盖 —— 那是用户当前正摆着的，
    // 覆盖它会导致"正拖着，位置突然跳回上次保存的值"。
    val nodeIdsForRestore = remember(nodes) { nodes.map { it.id }.sorted() }
    LaunchedEffect(activeConversationId, nodeIdsForRestore) {
        val saved = layoutStore?.load(activeConversationId) ?: return@LaunchedEffect
        val layouts = saved.toLayouts()
        if (layouts.isEmpty()) return@LaunchedEffect
        layouts.forEach { (id, layout) ->
            if (graphState.userPlaced.contains(id)) return@forEach
            graphState.positions[id] = Offset(layout.x, layout.y)
            if (layout.width > 0f && layout.height > 0f) {
                graphState.nodeSizes[id] = Size(layout.width, layout.height)
                // 诊断：记录"从磁盘恢复尺寸"的动作。
                // 若用户在拖动之后看到这条日志，说明恢复逻辑在拖动期间又跑了一次，
                // 那它就是"拖完变大又缩回"的覆盖源（恢复用的是旧数据）。
                android.util.Log.d(
                    "TreeChatCard",
                    "恢复尺寸 $id <- ${layout.width.toInt()}x${layout.height.toInt()}"
                )
            }
            // 恢复出来的也算"用户摆放过的"：自动布局从此不得覆盖它，
            // 这正是"重启后位置不挪动"能成立的关键。
            graphState.markUserPlaced(id)
        }
        hasFitted = true
    }

    // ---- 增量补齐布局：已有手工摆放的坐标不动，只给新节点安排位置 ----
    //
    // 只用**可见**节点参与布局：被折叠子树既不参与自动排版，也不会被 fitContent 算进去。
    // relayoutTick 是"关联/挂载后强制重排"的手动触发（节点集合不变时 key 不会变）。
    LaunchedEffect(visibleNodeIdList, relayoutTick) {
        if (visibleNodes.isEmpty()) return@LaunchedEffect
        val computed = computeGraphLayout(visibleNodes)
        computed.forEach { (id, pos) ->
            // userPlaced 的节点绝不覆盖：那是用户亲手摆的位置，
            // 也是"重启后不挪动"能成立的前提。
            if (!graphState.positions.containsKey(id) && !graphState.userPlaced.contains(id)) {
                graphState.positions[id] = pos
            }
        }
        graphState.hasLaidOut = true
        if (!hasFitted && viewport != IntSize.Zero) {
            fitContent(
                graphState,
                visibleNodes.mapNotNull { graphState.positions[it.id] },
                cardWidthPx, cardHeightPx, viewport
            )
            hasFitted = true
        }
    }

    // ---- 自动保存布局 ----
    //
    // 只保存"用户摆放过的"节点：把自动布局也算进去的话，下次启动会把
    // 一份自动算出来的坐标当成用户意图，新节点就再也排不齐了。
    //
    // ⚠️ 这里刻意"先把数据摊成不可变值、再 remember"，而不是直接拿
    // positions.toMap() 当 key：
    // `toMap()` 每次组合都产生**新实例**，而 Compose 的 remember key 比较用的是
    // `equals`，Map 的 equals 是结构比较——看似没问题，但拖动时每帧坐标都在变，
    // key 必然不等，于是快照每帧重算、写盘 effect 每帧触发，
    // 持续抖动还可能把布局写坏。
    // 改成用"摊平后的坐标列表 + 其 hashCode"做 key：内容不变就不重算。
    val positionsNow = graphState.positions.toMap()
    val sizedSnapshot = graphState.userPlaced.toList()
    val placedFlat = sizedSnapshot.map { id ->
        val pos = positionsNow[id]
        val size = graphState.nodeSizes[id]
        Triple(id, pos, size)
    }
    val placedKey = placedFlat.hashCode()

    val placedSnapshot: Map<String, GraphNodeLayout> = remember(placedKey) {
        val out = mutableMapOf<String, GraphNodeLayout>()
        placedFlat.forEach { (id, pos, size) ->
            // 位置缺失的节点直接跳过：它还没被布局安排，存进去只会写一个假坐标
            if (pos == null) return@forEach
            out[id] = GraphNodeLayout(
                x = pos.x,
                y = pos.y,
                // 尺寸缺省填 0，表示"用默认尺寸"，不虚构一个具体数值
                width = size?.width ?: 0f,
                height = size?.height ?: 0f
            )
        }
        out
    }
    LaunchedEffect(activeConversationId, placedSnapshot) {
        if (placedSnapshot.isEmpty()) return@LaunchedEffect
        // 诊断：记录"写给磁盘的尺寸"。
        // 与 applyCardDrag 的"尺寸写入"日志对照，就能判断尺寸是在
        // 写盘这一步丢失的，还是写盘后被恢复逻辑覆盖的。
        android.util.Log.d(
            "TreeChatCard",
            "保存布局：节点数=${placedSnapshot.size} " +
                placedSnapshot.entries.joinToString(" ") { (id, l) ->
                    "${id.take(4)}=${l.width.toInt()}x${l.height.toInt()}"
                }
        )
        // 存盘失败**绝不能让应用闪退**：知识库/布局都是次要数据，
        // 为用户一个坐标没写进去而崩掉整个对话是不可接受的。
        runCatching {
            layoutStore?.save(activeConversationId, GraphLayoutSnapshot.from(placedSnapshot))
        }
    }

    // ========================================================================
    // 几何与命中测试（提升到 Box 之外）
    // ========================================================================
    //
    // 为什么这些原本在 Box **内容里**的东西必须提到外面：
    // 画布的手势处理器写在 Box 的 modifier 上，而 modifier 的求值发生在
    // 内容 lambda 之前 —— 内容里的局部变量在那里根本不可见。
    // 之前手势只能写在卡片自己身上（本地坐标 + graphicsLayer 缩放 → 坐标错位），
    // 现在提到外层后，手势与绘制共用同一份几何数据，不可能再错位。

    val defaultCardSize = Size(cardWidthPx, cardHeightPx)
    val minCardW = with(density) { 150.dp.toPx() }
    val minCardH = with(density) { 66.dp.toPx() }
    val maxCardW = with(density) { 460.dp.toPx() }
    val maxCardH = with(density) { 380.dp.toPx() }

    // ---- 图空间小窗（属于当前会话的那些） ----
    //
    // ⚠️ **真全屏**的图空间窗要排除掉，交给 MainActivity 的屏幕浮层渲染
    // （那里才有"铺满整个可读区域、不受画布 pan/scale 影响"的效果）。
    //
    // 两层同时渲染的话，用户会看到同一个窗画了两次 —— 一份跟着画布缩放，
    // 一份铺满屏幕，两者还会互相穿插，正是"点一下弹出两个小窗"那类现象的同源问题。
    // 判据只看 `isFullScreen`（真全屏）；`isCanvasFullScreen`（画布全屏）
    // **仍要留在这一层** —— 它的定义就是"撑满画布但不脱离画布"。
    val graphWindows = windowManager.windows.filter {
        it.space == WindowSpace.GRAPH &&
            !it.isMinimized &&
            !it.isFullScreen &&
            nodes.any { node -> node.id == it.nodeId }
    }
    // 布局/命中一律只吃**可见**节点：被折叠子树整体退出布局与命中。
    val nodeIds = visibleNodeIdList
    val conversationKey = viewModel.activeConversationId.collectAsState().value

    // 布局与挤出：同一次计算里得出"卡片最终位置"与"被挤开的节点"，
    // 避免出现"算连线用一套坐标、画卡片用另一套"的错位。
    val geometry = computeGeometry(
        graphState = graphState,
        nodes = nodeIds,
        defaultSize = defaultCardSize,
        windows = graphWindows,
        densityScale = density.density,
        // 正在被拖动的卡片：邻居据此自动让开（松手后为空 → 回位）
        activeNodeId = graphState.activeCardDrag
    )
    val cardSizes = remember(nodeIds, geometry) {
        nodeIds.associateWith { geometry.sizeOf(it, defaultCardSize) }
    }

    // 命中表：卡片与小窗都在**内容坐标**下，供画布手势做逆变换后查询
    val cardPadPx = with(density) { CARD_PAD.toPx() }
    val cornerPx = with(density) { CORNER_BUTTON.toPx() }
    val cardRects: Map<String, Rect> = cardSizes.mapValues { (id, size) ->
        val base = graphState.positions[id] ?: Offset.Zero
        val displaced = geometry.displaced[id]?.offset ?: Offset.Zero
        val topLeft = base + displaced
        Rect(
            left = topLeft.x,
            top = topLeft.y,
            right = topLeft.x + size.width,
            bottom = topLeft.y + size.height
        )
    }
    // 基准矩形（不含挤出偏移）：只给"安卓桌面式拖放"判定落点用，
    // 成因见 CanvasHitState.baseCardRects 的说明。
    val baseCardRects: Map<String, Rect> = cardSizes.mapValues { (id, size) ->
        val base = graphState.positions[id] ?: Offset.Zero
        Rect(
            left = base.x,
            top = base.y,
            right = base.x + size.width,
            bottom = base.y + size.height
        )
    }
    // 卡片右上角「更多」(⋮) 与左下角「折叠」角标的命中区。
    // 与 NodeCard 的绘制共用 CARD_PAD / CORNER_BUTTON 常量，保证"看得见"与"点得到"一致。
    val moreRects: Map<String, Rect> = cardRects.mapValues { (_, r) ->
        Rect(
            left = r.right - cardPadPx - cornerPx,
            top = r.top + cardPadPx,
            right = r.right - cardPadPx,
            bottom = r.top + cardPadPx + cornerPx
        )
    }
    val collapseRects: Map<String, Rect> = cardRects
        .filterKeys { (childCounts[it] ?: 0) > 0 }
        .mapValues { (_, r) ->
            Rect(
                left = r.left + cardPadPx,
                top = r.bottom - cardPadPx - cornerPx,
                right = r.left + cardPadPx + cornerPx,
                bottom = r.bottom - cardPadPx
            )
        }
    val hitState = CanvasHitState(
        cardRects = cardRects,
        baseCardRects = baseCardRects,
        graphWindowRects = graphWindows.map { item ->
            val w = item.width.value * density.density
            val h = item.height.value * density.density
            Rect(item.offset.x, item.offset.y, item.offset.x + w, item.offset.y + h)
        },
        moreRects = moreRects,
        collapseRects = collapseRects
    )
    // 手势闭包要读"最新"的命中表，而 pointerInput 的 key 是 Unit（不重启），
    // 所以必须用 rememberUpdatedState 把最新快照喂进去。
    val hitStateLatest by rememberUpdatedState(hitState)
    val edgeZonePx = with(density) { 30.dp.toPx() }

    /**
     * 把屏幕坐标（手势给的 position）换算成内容坐标。
     *
     * 与绘制用的是**同一组**变换（见下方 Canvas 的 withTransform：
     * 先 translate(pan) 再 scale(scale, pivot=0)），取其逆变换即可：
     *     screen = content * scale + pan
     *     content = (screen - pan) / scale
     */
    fun toContent(screen: Offset): Offset {
        val s = graphState.scale.coerceAtLeast(0.05f)
        return Offset((screen.x - graphState.pan.x) / s, (screen.y - graphState.pan.y) / s)
    }

    /** 命中某张卡片，并判定这次拖动属于"改大小"还是"改位置"。 */
    fun hitCard(contentPos: Offset): CardHit? {        val state = hitStateLatest
        // 小窗压在卡片上时，触摸应归小窗（它自己处理拖动/点击），不穿透给卡片
        if (state.graphWindowRects.any { it.contains(contentPos) }) return null
        // 逆序遍历：后画的在上面，命中优先给最上层
        val entry = state.cardRects.entries.lastOrNull { it.value.contains(contentPos) }
            ?: return null
        val rect = entry.value
        val role = when {
            contentPos.x >= rect.right - edgeZonePx &&
                contentPos.y >= rect.bottom - edgeZonePx -> ResizeRole.CORNER
            contentPos.x >= rect.right - edgeZonePx -> ResizeRole.EDGE_RIGHT
            contentPos.y >= rect.bottom - edgeZonePx -> ResizeRole.EDGE_BOTTOM
            else -> ResizeRole.NONE
        }
        return CardHit(entry.key, role)
    }

    /**
     * 按 role 把内容坐标下的增量分发到"改位置"或"改大小"。
     *
     * `applyResize` 直接内联在这里而不是拆成另一个局部函数：
     * Kotlin 的局部函数**必须先声明后使用**，拆开会让调用顺序变得脆弱
     * （这正是上一版编译报 "Unresolved reference 'applyResize'" 的原因）。
     */
    fun applyCardDrag(nodeId: String, role: ResizeRole, delta: Offset) {
        if (role == ResizeRole.NONE) {
            // ⚠️ 必须读 graphState.positions（权威状态），不能读组合期快照。
            //
            // 拖动循环在**同一帧内连续消费指针事件**，组合根本来不及重算。
            // 若用组合期取来的值做基准再叠加增量，每一帧都会从同一个旧基准
            // 加上一个很小的增量，位移/尺寸**永远不累积** ——
            // 用户看到的就是"只能动一点点、然后弹回原处"。
            // 这正是本卡片改大小失效的根因。
            val current = graphState.positions[nodeId] ?: return
            graphState.positions[nodeId] = current + delta
            graphState.markUserPlaced(nodeId)
            return
        }
        // 改大小：右边缘只改宽、下边缘只改高、右下角两者都改
        val resizeDelta = when (role) {
            ResizeRole.EDGE_RIGHT -> Offset(delta.x, 0f)
            ResizeRole.EDGE_BOTTOM -> Offset(0f, delta.y)
            ResizeRole.CORNER -> delta
            ResizeRole.NONE -> Offset.Zero
        }
        // 同理：基准必须取自 graphState.nodeSizes（会随每次写入立即更新），
        // 而不是 cardSizes（那是 `remember(nodeIds, geometry)` 的组合期快照，
        // 在拖动过程中保持旧值 —— 实测 current 一直停在默认尺寸，
        // 于是每次拖动只能"变一点点"随即被下一帧的旧基准抹掉）。
        val current = graphState.nodeSizes[nodeId] ?: defaultCardSize
        graphState.nodeSizes[nodeId] = Size(
            (current.width + resizeDelta.x).coerceIn(minCardW, maxCardW),
            (current.height + resizeDelta.y).coerceIn(minCardH, maxCardH)
        )
        graphState.markUserPlaced(nodeId)
        // 诊断：记录每次尺寸写入的输入与结果（排查用，可随时删除）
        android.util.Log.d(
            "TreeChatCard",
            "尺寸写入 $nodeId: current=${current.width.toInt()}x${current.height.toInt()}" +
                " delta=${resizeDelta.x.toInt()}x${resizeDelta.y.toInt()}" +
                " -> ${graphState.nodeSizes[nodeId]?.width?.toInt()}x" +
                "${graphState.nodeSizes[nodeId]?.height?.toInt()}"
        )
        graphState.markUserPlaced(nodeId)
    }

    // 层级深度只用可见节点算：被折叠子树不参与，深度不会因为隐藏的中间层而错位
    // （可见节点的祖先必然也可见，所以父链仍然完整）。
    val depths = remember(visibleNodes) { computeDepths(visibleNodes) }

    // 连线上的流动光点相位
    val flow by rememberInfiniteTransition(label = "flow").animateFloat(
        initialValue = 0f,
        targetValue = 28f,
        animationSpec = infiniteRepeatable(tween(1100), RepeatMode.Restart),
        label = "flowPhase"
    )

    var spacebarPressed by remember { mutableStateOf(false) }
    var lastSelectedNodeId by remember { mutableStateOf<String?>(null) }
    val canvasFocusRequester = remember { FocusRequester() }

    /**
     * **画布本身**是否持有焦点。
     *
     * 为什么必须有这个判断：键盘微调与空格抓手挂在画布的 onPreviewKeyEvent 上，
     * 而画布是小窗的父节点 —— 小窗里的对话输入框也是画布的后代。若不加判断，
     * 在输入框里打空格会被当成"抓手"、按左右方向键会去挪卡片而不是移动光标。
     * 只有焦点确实落在画布自己身上（不是它的子节点）时才接管按键。
     */
    var canvasFocused by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        canvasFocusRequester.requestFocus()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(DarkBackground)
            .clipToBounds()
            .focusRequester(canvasFocusRequester)
            .focusable()
            .onFocusChanged { canvasFocused = it.isFocused }
            .onPreviewKeyEvent { event ->
                // 焦点不在画布自己身上（例如落在小窗的输入框里）→ 一律不接管，
                // 空格与方向键照常交给输入框使用。
                if (!canvasFocused) return@onPreviewKeyEvent false
                if (event.key == Key.Spacebar) {
                    spacebarPressed = (event.type == KeyEventType.KeyDown)
                    return@onPreviewKeyEvent true
                }
                if (event.type == KeyEventType.KeyDown) {
                    val targetId = lastSelectedNodeId ?: visibleNodes.firstOrNull()?.id
                    if (targetId != null) {
                        val step = if (event.isShiftPressed) 64f else 16f
                        val delta = when (event.key) {
                            Key.DirectionLeft -> Offset(-step, 0f)
                            Key.DirectionRight -> Offset(step, 0f)
                            Key.DirectionUp -> Offset(0f, -step)
                            Key.DirectionDown -> Offset(0f, step)
                            else -> null
                        }
                        if (delta != null) {
                            val current = graphState.positions[targetId] ?: Offset.Zero
                            graphState.positions[targetId] = current + delta
                            graphState.markUserPlaced(targetId)
                            return@onPreviewKeyEvent true
                        }
                    }
                }
                false
            }
            .pointerHoverIcon(if (spacebarPressed) PointerIcon.Hand else PointerIcon.Default)
            .onSizeChanged { viewport = it }
            // ---- 桌面级键鼠体验：滚轮缩放/平移（两套方案可对调） ----
            //
            // 滚轮语义由 viewModel.settings.graphWheelMode 决定，两套方案互为对调：
            //   · WHEEL_ZOOM（默认，导图习惯）：滚轮 = 以光标为中心的缩放，
            //     Ctrl+滚轮 = 上下平移；
            //   · WHEEL_PAN（文档习惯）：滚轮 = 上下平移，Ctrl+滚轮 = 缩放。
            //
            // 为什么默认给"滚轮缩放"：这块画布是思维导图（空间化浏览），
            // 不是长文档。Figma / Miro / 地图 / macOS 触控板一律是滚轮缩放，
            // 而"滚轮上下滚"是浏览器与 PDF 阅读器的约定。用户反馈的
            // "在画布里滚轮变成了上下滚动"正是两套肌肉记忆撞车。
            // 右下角有切换按钮，选择会持久化。
            //
            // key 里必须带上 wheelMode：pointerInput 的闭包在 key 不变时**不会重建**，
            // 若只写 Unit，切换按钮改了设置而画布仍用旧闭包里的旧值 ——
            // 表现为"按钮点了没反应（要切走再切回来才生效）"。
            .pointerInput(viewModel.settings.graphWheelMode) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        val scrollChange = event.changes.firstOrNull { it.scrollDelta != Offset.Zero }
                        if (scrollChange != null) {
                            val delta = scrollChange.scrollDelta
                            val isCtrl = event.keyboardModifiers.isCtrlPressed
                            // 当前这次滚动该做哪件事：Ctrl 与否 + 模式，共同决定。
                            // 两套方案是"对调"，所以这里用异或语义表达最清楚：
                            //   zoom   = 模式要缩放  XNOR 按了 Ctrl
                            val modeWantsZoom = viewModel.settings.graphWheelMode == GraphWheelMode.WHEEL_ZOOM
                            val doZoom = modeWantsZoom != isCtrl
                            if (doZoom) {
                                val zoomFactor = if (delta.y < 0) 1.10f else 0.91f
                                val nextScale = (graphState.scale * zoomFactor).coerceIn(0.35f, 2.6f)
                                // 以**光标位置**为锚点缩放：光标下的内容保持不动，
                                // 这是所有地图/白板工具的一致行为。
                                val mousePos = scrollChange.position
                                val contentMouse = (mousePos - graphState.pan) / graphState.scale
                                graphState.scale = nextScale
                                graphState.pan = mousePos - contentMouse * nextScale
                            } else {
                                graphState.pan -= Offset(delta.x * 32f, delta.y * 32f)
                            }
                            scrollChange.consume()
                        }
                    }
                }
            }
            // ---- 画布唯一的指针处理器：卡片手势 + 画布平移缩放 ----
            //
            // 架构说明（这是本轮最重要的改动）：
            //
            // 之前判断"点到了哪张卡片"是在**卡片自己的** pointerInput 里做的，
            // 用的是卡片本地坐标；而卡片被 graphicsLayer 按 scale 缩放过，
            // 于是"视觉位置"与"布局坐标"不一致 —— 边缘判定整体偏移，
            // 表现就是"边缘改不了大小、卡片也拖不动"。
            // 更糟的是外面还有这层 Box 的 detectTransformGestures 与铺满全屏的
            // 连线 Canvas 在同层竞争事件。
            //
            // 现在把**所有**指针处理收敛到这一个闭包里：
            //   1. 按下先做命中测试（用与绘制完全相同的坐标数学）；
            //   2. 命中卡片 → 按 role 决定改大小/改位置，全程 consume；
            //   3. 没命中 → 才把这次手势当画布平移/缩放。
            // 只有一条判定路径，就不存在"两套坐标系打架"的可能。
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val contentStart = toContent(down.position)
                        val hit = hitCard(contentStart)

                        // ⚠️ 按下点落在**小窗**上时，画布完全不接管这次手势。
                        //
                        // 这是用户实测反馈的问题："小窗与画布抢事件，点小窗就拖了画布"。
                        // 成因：小窗是画布的子级，它的标题栏拖动会消费事件；
                        // 但画布这一层此前不管三七二十一，只要没命中卡片就按"空白区域"
                        // 去平移，于是同一次触摸被两边同时处理 —— 小窗要拖、画布要移。
                        // 这里显式让路：命中窗口就 continue（不消费、不平移、不缩放），
                        // 交给小窗自己的手势处理。
                        val onWindow = hitStateLatest.graphWindowRects.any { it.contains(contentStart) }
                        if (hit == null && onWindow) {
                            continue
                        }

                        down.consume()

                        if (hit != null) {
                            // ---------- 卡片手势 ----------
                            // 标记"哪张卡正在被拖/缩放"，邻居卡片据此让开
                            graphState.activeCardDrag = hit.nodeId
                            // 诊断：把按下当刻的判定结果记下来。
                            // 边缘判定是在按下时一次性决定的，这个值能直接说明
                            // "为什么拖不动大小"：
                            //   角色 = 改位置  → 边缘命中没生效（要改 hitCard 判定）
                            //   角色 = 改大小* → 判定对了，那问题在尺寸写回/夹取
                            if (viewModel.settings.selectionDiagnostics) {
                                val sz = cardSizes[hit.nodeId]
                                cardDiagnostic = buildString {
                                    append(
                                        when (hit.role) {
                                            ResizeRole.NONE -> "改位置"
                                            ResizeRole.EDGE_RIGHT -> "改宽"
                                            ResizeRole.EDGE_BOTTOM -> "改高"
                                            ResizeRole.CORNER -> "改宽高"
                                        }
                                    )
                                    append(" · 尺寸=")
                                    append(sz?.let { "${it.width.toInt()}x${it.height.toInt()}" } ?: "默认")
                                    append(" · 边缘区=${edgeZonePx.toInt()}")
                                    append(" · 卡=${hit.nodeId.take(6)}")
                                }
                                android.util.Log.d("TreeChatCard", "命中卡片：$cardDiagnostic")
                            }
                            var moved = false
                            var finish = false
                            // 每次新手势开始先清掉落点：上一次拖放的残留高亮不能带进来。
                            dragDropTargetId = null
                            while (!finish) {
                                val event = awaitPointerEvent(PointerEventPass.Main)
                                val change = event.changes
                                    .firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) {
                                    finish = true
                                    break
                                }

                                // 边缘自动平移：**在读到位移之前**先更新一次，
                                // 且不能放在下面 `raw == Offset.Zero` 的 continue 之后 ——
                                // 用户把指针顶在边缘不动时 positionChange 恒为零，
                                // 那条 continue 会让"顶住不动"这条最关键的情形永远不触发。
                                edgeScroller?.update(
                                    position = change.position,
                                    viewport = Size(viewport.width.toFloat(), viewport.height.toFloat()),
                                    edgeZonePx = EDGE_SCROLL_ZONE_PX
                                )

                                val raw = change.positionChange()
                                if (raw == Offset.Zero) continue
                                // 增量换算到内容坐标：除以缩放比，
                                // 这样放大后拖动才跟手（不会"飞出去"）
                                val s = graphState.scale.coerceAtLeast(0.05f)
                                moved = true
                                change.consume()
                                applyCardDrag(
                                    nodeId = hit.nodeId,
                                    role = hit.role,
                                    delta = Offset(raw.x / s, raw.y / s)
                                )

                                // ---- 安卓桌面式拖放：实时判定落点卡 ----
                                //
                                // 判据是"**被拖的卡片**压住了哪张卡的基准矩形"，
                                // 而不是"指针压住了哪张卡"。两个原因：
                                //   1. 指针始终在被拖卡片内部（按在哪儿就抓着哪儿），
                                //      拿指针去判会永远命中被拖的自己那一块；
                                //   2. 邻卡在被拖时会一直躲开，视觉上永不重叠，
                                //      所以必须用不含挤出位移的基准矩形（见 baseCardRects）。
                                // 取交叠**面积最大**的那张，天然消解"同时压住两张"的歧义。
                                // 只在"改位置"角色下判定：拖边缘改大小时不该触发挂载。
                                if (hit.role == ResizeRole.NONE) {
                                    // 位置取**权威状态** graphState.positions（手势循环内组合期快照是旧的），
                                    // 尺寸取命中表里的（本次拖的是位置，尺寸不会变）。
                                    val draggedPos = graphState.positions[hit.nodeId]
                                    val draggedBox = hitStateLatest.baseCardRects[hit.nodeId]
                                    val target = if (draggedPos == null || draggedBox == null) {
                                        null
                                    } else {
                                        val draggedRect = Rect(
                                            left = draggedPos.x,
                                            top = draggedPos.y,
                                            right = draggedPos.x + draggedBox.width,
                                            bottom = draggedPos.y + draggedBox.height
                                        )
                                        // ⚠️ 必须要求**足够大的交叠比例**，不能"沾一点边就算落点"。
                                        //
                                        // 布局的列距（430px）只比卡宽（186dp≈372px）大 58px 左右，
                                        // 行距同样只多几十像素。若只判"面积 > 0"，用户随手把卡片
                                        // 挪几十像素就会压到邻卡，一松手就误挂载 —— 而挂载是改树结构
                                        // 的操作，误触代价很高。
                                        // 这里要求交叠面积达到**被拖卡片面积**的这一比例：
                                        // 顺带一挪（约 58px）只有 ~15%，够不着；真把它盖上去是 100%。
                                        val draggedArea = draggedRect.width * draggedRect.height
                                        val minOverlap = draggedArea * DROP_OVERLAP_RATIO
                                        hitStateLatest.baseCardRects.entries
                                            .asSequence()
                                            .filter { it.key != hit.nodeId }
                                            .map { it.key to overlapArea(it.value, draggedRect) }
                                            .filter { it.second >= minOverlap }
                                            .maxByOrNull { it.second }
                                            ?.first
                                    }
                                    // 只在真的变了才写状态：这个循环每帧都跑，
                                    // 无条件赋值会让画布每帧重组一次。
                                    if (target != dragDropTargetId) dragDropTargetId = target
                                }
                            }
                            // 拖拽循环结束（抬手或指针丢失）：**必须**停掉边缘自动平移，
                            // 否则松手后画布还会自己继续挪 —— 那是明显的 bug。
                            edgeScroller?.stop()

                            // ---- 安卓桌面式拖放：松手落在另一张卡上就挂载过去 ----
                            //
                            // 语义与"关联其他卡片至当前对话"完全一致：**被拖的卡片成为
                            // 落点卡片的下一级**（子分支）。复用同一个
                            // attachNodeToParent，因此回合校验、跨会话校验都是现成的，
                            // 不存在另一套挂载规则。
                            //
                            // 落点为空则什么都不做 —— 卡片就停在用户放下的地方
                            //（applyCardDrag 已经写好了坐标并标了 userPlaced），
                            // 这也保证了"随手拖一下"不会被误当成挂载。
                            val dropTarget = dragDropTargetId
                            dragDropTargetId = null
                            if (moved && hit.role == ResizeRole.NONE && dropTarget != null) {
                                val dropTitle = nodes.firstOrNull { it.id == dropTarget }
                                    ?.title?.take(10) ?: "目标卡片"
                                actionScope.launch {
                                    viewModel.attachNodeToParent(hit.nodeId, dropTarget)
                                        .onSuccess {
                                            // 挂载后该卡片的坐标与"用户亲手摆放"标记都要清掉，
                                            // 否则它会被留在旧位置、与新父节点错位
                                            //（与上方"完成关联"同一条处理）。
                                            graphState.positions.remove(hit.nodeId)
                                            graphState.userPlaced.remove(hit.nodeId)
                                            relayoutTick++
                                            banner = "已挂载为「$dropTitle」的下级分支"
                                        }
                                        .onFailure { banner = it.message ?: "挂载失败" }
                                }
                                graphState.activeCardDrag = null
                                continue
                            }
                            // 点击卡片（按下后未移动即抬手）分三种去向：
                            //   1) 命中左下角折叠角标 → 切换 折叠/展开（对齐旧实现 toggleCollapse）
                            //   2) 命中右上角"更多"角标 → 打开节点操作菜单（对齐旧实现 showCardActionMenu）
                            //   3) 其余 → 就地开窗对话（原有行为，用户明确要求保留）
                            //
                            // 角标判定读**最新**的命中表（hitStateLatest）：
                            // 这个指针闭包是 long-lived 的（pointerInput(Unit)），直接读组合期的
                            // moreRects/collapseRects 会拿到过期快照。
                            if (!moved && attachSelectionMode && attachTargetId != null) {
                                val hitId = hit.nodeId
                                if (hitId != attachTargetId && attachCandidates.any { it.id == hitId }) {
                                    if (selectedAttachIds.containsKey(hitId)) {
                                        selectedAttachIds.remove(hitId)
                                    } else {
                                        selectedAttachIds[hitId] = true
                                    }
                                }
                                continue
                            }

                            if (!moved) {
                                val state = hitStateLatest
                                val point = contentStart
                                val collapseRect = state.collapseRects[hit.nodeId]
                                val moreRect = state.moreRects[hit.nodeId]
                                when {
                                    collapseRect != null && collapseRect.contains(point) -> {
                                        banner = toggleCollapse(hit.nodeId)
                                    }

                                    moreRect != null && moreRect.contains(point) -> {
                                        menuNodeId = hit.nodeId
                                    }

                                    hit.role == ResizeRole.NONE -> {
                                        val node = nodes.firstOrNull { it.id == hit.nodeId }
                                        if (node != null) {
                                            val size = cardSizes[hit.nodeId] ?: defaultCardSize
                                            val topLeft = (graphState.positions[hit.nodeId] ?: Offset.Zero) +
                                                (geometry.displaced[hit.nodeId]?.offset ?: Offset.Zero)
                                            windowManager.openInPlaceWindow(
                                                nodeId = node.id,
                                                title = node.title,
                                                originQuote = node.originQuote,
                                                depth = (depths[node.id] ?: 0) + 1,
                                                offsetPx = topLeft,
                                                widthDp = WindowDefaults.WIDTH,
                                                heightDp = WindowDefaults.HEIGHT,
                                                spawnFromWidth = with(density) { size.width.toDp() },
                                                spawnFromHeight = with(density) { size.height.toDp() }
                                            )
                                        }
                                    }
                                }
                            }
                            // 手势结束：清除标记 → 邻居卡片的让位位移随之消失（回位）
                            graphState.activeCardDrag = null
                            continue
                        }

                        // ---------- 画布平移 / 缩放 ----------
                        // 未命中卡片才走到这里，所以"拖卡片"与"平移画布"永不冲突
                        //
                        // 诊断：记录"按下没有命中任何卡片"以及当时的可用信息。
                        // 这条与上面"命中卡片"那条互为对照，能一次分清两种情况：
                        //   未命中 → 按下的坐标落在了卡片矩形之外（先查坐标/矩形）
                        //   已命中但角色是"改位置" → 边缘区判定没生效
                        if (viewModel.settings.selectionDiagnostics) {
                            val rects = hitStateLatest.cardRects.entries
                                .joinToString("; ") { (id, r) ->
                                    "${id.take(4)}[${r.left.toInt()},${r.top.toInt()}," +
                                        "${r.right.toInt()},${r.bottom.toInt()}]"
                                }
                            cardDiagnostic = "未命中卡片 · 按下=(${contentStart.x.toInt()}," +
                                "${contentStart.y.toInt()}) · 缩放=${"%.2f".format(graphState.scale)} · 卡片=$rects"
                            android.util.Log.d("TreeChatCard", "未命中卡片：$cardDiagnostic")
                        }
                        var prev = down.position
                        var finish = false
                        while (!finish) {
                            val event = awaitPointerEvent(PointerEventPass.Main)
                            if (event.changes.size > 1) {
                                // 多指：交给画布缩放
                                val zoom = event.calculateZoom()
                                val pan = event.calculatePan()
                                if (zoom != 1f) {
                                    graphState.scale =
                                        (graphState.scale * zoom).coerceIn(0.35f, 2.6f)
                                }
                                graphState.pan += pan
                                event.changes.forEach { it.consume() }
                                continue
                            }
                            val change = event.changes
                                .firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) {
                                finish = true
                                break
                            }
                            val delta = change.position - prev
                            prev = change.position
                            if (delta != Offset.Zero) {
                                change.consume()
                                graphState.pan += delta
                            }
                        }
                    }
                }
            }
    ) {
        // 把画布内容范围交给窗口管理器，供图空间小窗的拖动边界使用
        LaunchedEffect(geometry.bounds, density.density) {
            windowManager.graphContentBounds = geometry.bounds
        }

        // ---- 边缘自动平移的**帧循环** ----
        //
        // 只在 edgeScroller.active 时做事：每帧按 (方向 × 强度) 挪一点 pan。
        //
        // 为什么必须是独立的帧循环，而不能只在上面的 pointer 回调里平移：
        // 自动平移的关键情形是**指针顶着边缘不动、等画面挪过来** ——
        // 此时没有任何 pointer 事件到达，只在事件回调里做平移就会"顶住就不挪"。
        //
        // 用 withFrameNanos 而不是定时器：它与渲染帧对齐，掉帧时不会攒出一串
        // "补帧位移"（那看起来就是画面突然窜一下）。
        LaunchedEffect(edgeScroller) {
            val scroller = edgeScroller ?: return@LaunchedEffect
            var lastFrame = 0L
            while (true) {
                withFrameNanos { now ->
                    val dt = if (lastFrame == 0L) 0f else (now - lastFrame) / 1_000_000_000f
                    lastFrame = now
                    if (scroller.active) {
                        val step = scroller.tick(dt)
                        if (step != Offset.Zero) graphState.pan += step
                    }
                }
            }
        }

        // ---- 连线层（跟随 pan / scale） ----
        // Canvas 的绘制 lambda 不是 Composable 上下文，读不了 MaterialTheme，
        // 所以颜色必须先在组合作用域取值再传进去。
        val linkPrimary = AccentNeonGreen
        val linkSecondary = AccentIceBlue

        // 点阵背景改为「一张小贴图 + 平铺着色器」：
        // 每帧只发一次 drawRect，不再逐点画圆（原因见 rememberDotGridTile 注释）
        val dotTile = rememberDotGridTile(MaterialTheme.colorScheme.outline)
        val dotShader = remember(dotTile) {
            ImageShader(dotTile, TileMode.Repeated, TileMode.Repeated)
        }

        val visibleLeft = -graphState.pan.x / graphState.scale
        val visibleTop = -graphState.pan.y / graphState.scale
        val visibleWidth = viewport.width / graphState.scale
        val visibleHeight = viewport.height / graphState.scale

        Canvas(modifier = Modifier.fillMaxSize()) {
            withTransform({
                translate(left = graphState.pan.x, top = graphState.pan.y)
                scale(scaleX = graphState.scale, scaleY = graphState.scale, pivot = Offset.Zero)
            }) {
                // 点阵背景：一次 draw call，且画在变换内所以随内容平移
                if (visibleWidth > 0f && visibleHeight > 0f) {
                    drawRect(
                        brush = ShaderBrush(dotShader),
                        topLeft = Offset(visibleLeft, visibleTop),
                        size = Size(visibleWidth, visibleHeight)
                    )
                }

                // 只画**可见**边：被折叠子树一边连线立即消失（卡片本身另有渐隐动效）。
                visibleNodes.forEach { child ->
                    val parentId = child.parentId ?: return@forEach
                    val baseParent = graphState.positions[parentId] ?: return@forEach
                    val baseChild = graphState.positions[child.id] ?: return@forEach
                    // 用挤出后的实际位置画线：否则卡片被推开后连线还钉在原处，
                    // 看起来像"线和卡片脱钩了"
                    val p = baseParent + (geometry.displaced[parentId]?.offset ?: Offset.Zero)
                    val c = baseChild + (geometry.displaced[child.id]?.offset ?: Offset.Zero)

                    val parentSize = geometry.sizeOf(parentId, defaultCardSize)
                    val childSize = geometry.sizeOf(child.id, defaultCardSize)

                    val start = Offset(p.x + parentSize.width, p.y + parentSize.height / 2f)
                    val end = Offset(c.x, c.y + childSize.height / 2f)
                    val path = bezierPath(start, end)

                    drawPath(
                        path = path,
                        color = linkPrimary.copy(alpha = 0.10f),
                        style = Stroke(width = 7f, cap = StrokeCap.Round)
                    )
                    drawPath(
                        path = path,
                        color = linkSecondary.copy(alpha = 0.42f),
                        style = Stroke(width = 1.7f, cap = StrokeCap.Round)
                    )
                    drawPath(
                        path = path,
                        color = linkPrimary.copy(alpha = 0.72f),
                        style = Stroke(
                            width = 1.7f,
                            cap = StrokeCap.Round,
                            pathEffect = PathEffect.dashPathEffect(
                                intervals = floatArrayOf(3f, 25f),
                                phase = flow
                            )
                        )
                    )
                    drawCircle(
                        color = linkPrimary.copy(alpha = 0.85f),
                        radius = 3f,
                        center = start
                    )
                }
            }
        }

        // ---- 节点卡片层 ----
        //
        // 这里刻意遍历**全部**节点（而非 visibleNodes）：被折叠子树需要留在组合里
        // 把"渐隐 + 缩放"的退场动效播完，否则只会瞬间硬切。
        // 但它们从折叠那一刻起就已经退出了布局与命中 —— 位置/尺寸来自下面这份
        // geometry（只算可见节点），且命中表（hitState）里根本没有它们。
        // 换句话说：动效只是"看得见的收尾"，不代表它们还参与布局或能被点到。
        nodes.forEach { node ->
            val visible = node.id in visibleIdSet
            val base = graphState.positions[node.id]
            // 折叠期间若该节点从未被排版过（没有坐标），直接在原位渐隐；不虚构坐标。
            if (base == null && !visible) return@forEach
            val resolvedBase = base ?: Offset.Zero
            val displacement = geometry.displaced[node.id]
            val targetOffset = resolvedBase + (displacement?.offset ?: Offset.Zero)
            val sizePx = geometry.sizeOf(node.id, defaultCardSize)
            val openWindow = graphWindows.firstOrNull { it.nodeId == node.id }
            val childCount = childCounts[node.id] ?: 0
            val collapsed = node.id in collapsedIds

            key(node.id) {
                NodeCard(
                    node = node,
                    depth = depths[node.id] ?: 0,
                    sizePx = sizePx,
                    contentOffset = targetOffset,
                    screenOffset = Offset(
                        x = targetOffset.x * graphState.scale + graphState.pan.x,
                        y = targetOffset.y * graphState.scale + graphState.pan.y
                    ),
                    scale = graphState.scale,
                    hasOpenWindow = openWindow != null,
                    visible = visible,
                    childCount = childCount,
                    isCollapsed = collapsed,
                    tetherFlash = if (node.id == flashNodeId) flashAmount else 0f,
                    isAttachTarget = attachSelectionMode && node.id == attachTargetId,
                    isAttachCandidate = attachSelectionMode && attachCandidates.any { it.id == node.id },
                    isAttachSelected = attachSelectionMode && selectedAttachIds.containsKey(node.id),
                    // 安卓桌面式拖放的两种态：正在被拖的那张、以及它当前压着的落点卡。
                    isBeingDragged = graphState.activeCardDrag == node.id,
                    isDropTarget = dragDropTargetId == node.id && node.id != graphState.activeCardDrag,
                    isSensitive = viewModel.isNodeSensitive(node.id)
                )
            }
        }

        // ---- 图空间小窗层 ----
        // 在画布坐标里渲染，因此与卡片共用同一套 pan/scale；
        // 但**不参与挤出计算**（它自己是推人的那一方）。
        graphWindows.forEach { item ->
            key(item.id, conversationKey) {
                Box(
                    modifier = Modifier
                        .offset {
                            IntOffset(
                                (item.offset.x * graphState.scale + graphState.pan.x).roundToInt(),
                                (item.offset.y * graphState.scale + graphState.pan.y).roundToInt()
                            )
                        }
                        .graphicsLayer {
                            scaleX = graphState.scale
                            scaleY = graphState.scale
                            transformOrigin = TransformOrigin(0f, 0f)
                        }
                        .zIndex(500f + (item.zIndex % 100f))
                ) {
                    FloatingWindowCard(
                        windowItem = item,
                        manager = windowManager,
                        onRename = { newTitle -> viewModel.renameNode(item.nodeId, newTitle) },
                        onDelete = {
                            viewModel.deleteNode(item.nodeId)
                            windowManager.closeWindow(item.id)
                        },
                        onQuoteClick = { sourceMessageId ->
                            // null = 这条引用没记录来源（旧数据 / 整段分叉），此时卡片点不动
                            if (sourceMessageId != null) onJumpToSourceMessage(sourceMessageId)
                        },
                        // 正文里的**同文本超链接**（点开 → 该分支的小窗）。
                        //
                        // ⚠️ 这一条早先整个漏掉了：画布小窗只接了 onQuoteClick（来源消息），
                        // 而正文链接走的是另一条回调。漏接时它落到 ChatMessageView 的
                        // 默认空实现 —— 表现就是"思维导图里开着的小窗，正文超链接怎么点都没反应，
                        // 而把那个小窗关掉、主对话里同一个链接却是好的"。
                        onOpenQuoteWindow = { targetNode ->
                            onQuoteTargetSelected(targetNode)
                        },
                        // 面包屑：由画布从当前会话节点树现算祖先链（见 ancestorTrail）。
                        // 放在渲染期算而不是开窗时传，是为了覆盖**所有**开窗入口
                        // （画布双击、卡片分叉、同文本超链接……）而不用逐个调用点改签名。
                        ancestors = remember(item.nodeId, nodes) {
                            ancestorTrail(nodes, item.nodeId)
                        },
                        onBreadcrumbClick = { crumb ->
                            // 点面包屑某一层：把画布平移到该节点居中，并闪一下它
                            graphState.positions[crumb.nodeId]?.let { pos ->
                                val s = graphState.scale
                                graphState.pan = Offset(
                                    x = viewport.width / 2f - (pos.x + cardWidthPx / 2f) * s,
                                    y = viewport.height / 2f - (pos.y + cardHeightPx / 2f) * s
                                )
                                flashNodeId = crumb.nodeId
                                flashTick++
                            }
                        },
                        // 外层 Box 已经按 (offset*scale + pan) 定位过，
                        // 卡内部不能再偏移一次（双重施加会让看得见的窗和点得到的窗错位）
                        positionedByParent = true,
                        // 手势增量要除以画布缩放比才跟手
                        contentScale = graphState.scale,
                        // 图空间小窗拖到画布边缘时也触发画布自动平移（与拖卡片一致）
                        edgeScroller = edgeScroller,
                        edgeViewportPx = Size(viewport.width.toFloat(), viewport.height.toFloat()),
                        edgeZonePx = EDGE_SCROLL_ZONE_PX,
                        // 图空间小窗的"全屏"尺寸：外层 Box 自身没有尺寸（只做 offset 与
                        // 缩放），卡片自己测到的是"内容固有尺寸"而不是画布视口。
                        // 这里把**视口尺寸换算到内容坐标**（除以 scale）后显式告诉卡片：
                        // 因为整张卡随后会被 graphicsLayer 乘回 scale，
                        // 内容坐标 × scale == 屏幕上的视口大小，正好铺满画布可见区。
                        //
                        // 还要留出一点边距，否则全屏后卡片四边紧贴画布边缘，
                        // 用户看不到画布背景、也点不到画布空白（无法平移/取消）。
                        fullscreenSize = if (viewport.width > 0 && viewport.height > 0) {
                            val s = graphState.scale.coerceAtLeast(0.05f)
                            with(density) {
                                DpSize(
                                    (viewport.width / s).toDp() - GRAPH_FULLSCREEN_INSET * 2,
                                    (viewport.height / s).toDp() - GRAPH_FULLSCREEN_INSET * 2
                                )
                            }
                        } else {
                            null
                        }
                    ) {
                        NodeChatContentCompact(
                            nodeId = item.nodeId,
                            viewModel = viewModel,
                            windowManager = windowManager,
                            depth = item.depth
                        )
                    }
                }
            }
        }

        // ---- 左上角信息 ----
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(14.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(DarkSurface.copy(alpha = 0.92f))
                .border(1.dp, WindowBorderColor, RoundedCornerShape(20.dp))
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Text(
                text = "${visibleNodes.size} 个节点 · ${(graphState.scale * 100).roundToInt()}%" +
                    if (graphWindows.isNotEmpty()) " · ${graphWindows.size} 窗" else "",
                color = TextMuted,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace
            )
        }

        // ---- 关联已有卡片：画布就地勾选模式工具条 ----
        val attachTargetNode = attachTargetId?.let { id -> nodes.firstOrNull { it.id == id } }
        if (attachSelectionMode && attachTargetNode != null) {
            Surface(
                color = DarkSurfaceVariant,
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.5.dp, AccentNeonGreen),
                shadowElevation = 8.dp,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 14.dp)
                    .zIndex(1000f)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Link,
                        contentDescription = null,
                        tint = AccentNeonGreen,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "点选卡片挂载至「${attachTargetNode.title.take(10)}」· 已选 ${selectedAttachIds.size} 张",
                        color = TextPrimary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.width(10.dp))
                    Button(
                        onClick = {
                            val toAttach = selectedAttachIds.keys.toList()
                            actionScope.launch {
                                toAttach.forEach { candId ->
                                    viewModel.attachNodeToParent(candId, attachTargetNode.id)
                                    graphState.positions.remove(candId)
                                    graphState.userPlaced.remove(candId)
                                }
                                relayoutTick++
                                banner = "已成功关联 ${toAttach.size} 张卡片为下级分支"
                                attachSelectionMode = false
                                attachTargetId = null
                                selectedAttachIds.clear()
                            }
                        },
                        enabled = selectedAttachIds.isNotEmpty(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AccentNeonGreen,
                            contentColor = Color.Black
                        ),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.height(30.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)
                    ) {
                        Text("完成关联", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.width(6.dp))
                    TextButton(
                        onClick = {
                            attachSelectionMode = false
                            attachTargetId = null
                            selectedAttachIds.clear()
                        },
                        modifier = Modifier.height(30.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text("取消", color = TextMuted, fontSize = 11.sp)
                    }
                }
            }
        }

        // ---- 卡片手势诊断（仅在设置里打开"显示文本选择诊断"时出现）----
        //
        // 为什么复用同一个开关：它本质是"显示内部判定结果"的总开关，
        // 用户已经知道去哪里打开，不必再教一个新入口。
        //
        // 为什么需要它：「卡片能拖但不能改大小」有两种修法完全不同的成因：
        //   A. 按下时判定成了 NONE（边缘命中没生效）→ 改 hitCard 的判定
        //   B. 判定成了 CORNER/EDGE 但尺寸没变 → 改 applyCardDrag 的夹取/写回
        // 看这一行就能分清，不必再"改一次赌一次"。
        if (viewModel.settings.selectionDiagnostics) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(top = 48.dp, start = 14.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(DarkSurface.copy(alpha = 0.94f))
                    .border(1.dp, WindowBorderColor, RoundedCornerShape(12.dp))
                    .padding(horizontal = 10.dp, vertical = 5.dp)
            ) {
                Text(
                    // 常驻显示：没有值时也给占位，否则"未命中卡片"这类结果
                    // 反而看不到，等于诊断失效
                    text = "卡片诊断：" + (cardDiagnostic ?: "尚未按下卡片"),
                    color = AccentNeonGreen,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }

        // ---- 右下角控制 ----
        //
        // zIndex 必须高于图空间小窗（小窗用 500f 起），否则开一个小窗正好落在
        // 右下角时，会把这几个按钮**整片盖住**，用户就再也点不到"适应屏幕/
        // 重新布局/收起全部小窗"了 —— 这是实际发生过的反馈。
        // 控制按钮是画布的常驻操作入口，必须永远可点、可见。
        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .zIndex(900f)
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.End
        ) {
            GraphControlButton(Icons.Default.Add, "放大") {
                graphState.scale = (graphState.scale * 1.25f).coerceAtMost(2.6f)
            }
            GraphControlButton(Icons.Default.Remove, "缩小") {
                graphState.scale = (graphState.scale / 1.25f).coerceAtLeast(0.35f)
            }

            // 滚轮语义切换：滚轮缩放 ⇄ 滚轮平移（两者与 Ctrl+滚轮 互为对调）。
            // 图标随当前方案变化，用户一眼能看出"现在滚轮是干什么的"，
            // 而不是点下去才知道。选择会持久化（settings.updateGraphWheelMode）。
            val wheelZoom = viewModel.settings.graphWheelMode == GraphWheelMode.WHEEL_ZOOM
            GraphControlButton(
                icon = if (wheelZoom) Icons.Default.ZoomIn else Icons.Default.SwapVert,
                label = if (wheelZoom) {
                    "滚轮：缩放（按住 Ctrl 平移）—— 点击切换为「滚轮平移」"
                } else {
                    "滚轮：平移（按住 Ctrl 缩放）—— 点击切换为「滚轮缩放」"
                }
            ) {
                viewModel.settings.toggleGraphWheelMode()
            }

            // 画布全屏：收起左侧文件夹栏，让画布铺满**内容区**。
            // 语义与对话界面小窗的全屏一致 —— 不覆盖顶部药丸导航（那是回不去的入口）。
            // 图标随状态切换，让用户一眼看出点下去是"进全屏"还是"退出全屏"。
            if (onToggleCanvasFullscreen != null) {
                GraphControlButton(
                    icon = if (canvasFullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                    label = if (canvasFullscreen) "退出画布全屏" else "画布全屏（收起文件夹栏，铺满内容区）"
                ) {
                    onToggleCanvasFullscreen()
                }
            }

            GraphControlButton(Icons.Default.CenterFocusStrong, "适应屏幕") {
                fitContent(
                    graphState,
                    visibleNodes.mapNotNull { graphState.positions[it.id] },
                    cardWidthPx, cardHeightPx, viewport
                )
            }
            // 布局存档：命名快照的开关与面板入口
    var showLayoutArchives by remember { mutableStateOf(false) }
    val archiveContext = androidx.compose.ui.platform.LocalContext.current
    val layoutArchiveStore = remember { LayoutArchiveStore(archiveContext) }

    if (showLayoutArchives) {
        LayoutArchiveDialog(
            store = layoutArchiveStore,
            graphState = graphState,
            conversationId = activeConversationId,
            defaultTitle = LayoutArchiveStore.suggestTitle(activeConversationId),
            onDismiss = { showLayoutArchives = false },
            // 复用画布自己的横幅提示，不另起一套 toast
            onMessage = { banner = it }
        )
    }

    val canRestore = graphState.canRestoreLayout
            GraphControlButton(
                icon = if (canRestore) Icons.AutoMirrored.Filled.Undo else Icons.Default.AccountTree,
                label = if (canRestore) "二次点击复原（恢复之前自己拖拽的位置与尺寸）" else "重新布局（自动排版，再次点击可复原）"
            ) {
                if (canRestore) {
                    // 二次点击复原：将刚才自动布局打乱前的所有手工拖拽坐标与尺寸完全还原！
                    graphState.restoreLayout()
                } else {
                    // 首次点击：先备份当前所有手工位置与尺寸，然后再执行自动布局
                    graphState.backupCurrentLayout()
                    graphState.clearLayout()
                    val fresh = computeGraphLayout(visibleNodes)
                    graphState.positions.putAll(fresh)
                    graphState.hasLaidOut = true
                    graphState.userPlaced.clear()
                    fitContent(
                        graphState,
                        visibleNodes.mapNotNull { graphState.positions[it.id] },
                        cardWidthPx, cardHeightPx, viewport
                    )
                }
            }
            // 布局存档：与上面的"复原"是**两种不同的东西** ——
            // 复原只撤销一次、只在内存、用完即消；存档是命名快照、多份并存、跨重启。
            // 两个都留着，因为它们解决的是不同频次的问题。
            GraphControlButton(
                icon = Icons.Default.Inventory2,
                label = "布局存档：保存当前排版为快照，或恢复到某一份"
            ) {
                showLayoutArchives = true
            }
            GraphControlButton(Icons.Default.CropFree, "把工作台小窗全部收进侧栏") {
                graphWindows.forEach { windowManager.toggleMinimize(it.id) }
            }
            if (onSnapshotToNotes != null) {
                GraphControlButton(Icons.Default.Edit, "快照至手写笔记（卡片可继续拖动缩放并支持手写笔批注）") {
                    onSnapshotToNotes(graphState.positions.toMap(), graphState.nodeSizes.toMap())
                }
            }
            // 导出：把当前会话的分支树导成 PDF / PNG / Markdown。
            // 放在控件列的最下方（离手指最近、且不会误触上面的布局类按钮）。
            GraphControlButton(Icons.Default.Share, "导出这张思维导图") {
                onRequestExport()
            }
        }

        // ====================================================================
        // 节点操作：菜单 / 关联已有卡片 / 分支结论合并
        // ====================================================================
        // 均以 menuNodeId / attachTargetId / mergeJob 三个状态为唯一入口，
        // 弹窗渲染在画布 Box 的最后一层。

        // 画布内瞬时提示（全 Compose，不用系统 Toast）。
        banner?.let { message ->
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .zIndex(950f)
                    .padding(bottom = 96.dp)
            ) {
                Snackbar(
                    containerColor = DarkSurfaceVariant,
                    contentColor = TextPrimary
                ) {
                    Text(message, fontSize = 12.5.sp)
                }
            }
        }

        // ---- ① 节点操作菜单 ----
        val menuNode = menuNodeId?.let { wanted -> nodes.firstOrNull { it.id == wanted } }
        if (menuNode != null) {
            NodeActionMenuDialog(
                nodeTitle = menuNode.title,
                isCollapsed = menuNode.id in collapsedIds,
                onDismiss = { menuNodeId = null },
                onAction = { index ->
                    val targetId = menuNode.id
                    menuNodeId = null
                    when (index) {
                        // 0：新建下级分支卡片（对齐旧实现 createChildNode）
                        0 -> actionScope.launch {
                            val child = viewModel.createChildNode(targetId)
                            if (child == null) {
                                banner = "创建子节点失败：找不到当前节点"
                            } else {
                                // 新卡片没有历史摆放位置，清掉再触发重排，
                                // 否则它可能带着上次的坐标出现在奇怪的地方。
                                graphState.positions.remove(child.id)
                                graphState.userPlaced.remove(child.id)
                                relayoutTick++
                                banner = "已成功创建下级分支卡片"
                            }
                        }
                        // 1：新建上级分支卡片（在该卡片与它原来的父节点之间插一层）
                        1 -> actionScope.launch {
                            val parent = viewModel.createParentNode(targetId)
                            if (parent == null) {
                                banner = "创建父节点失败：找不到当前节点"
                            } else {
                                // 新父节点与新增的下级卡片同理：清掉旧坐标并重排。
                                // 布局按"深度 = 列"排布（见 computeGraphLayout），父节点在左、
                                // 下级在右，所以插一层之后新父节点会占住被插卡片原来那一列，
                                // 被插卡片本身连同其整棵子树一起右移一列 ——
                                // 因此这里**两张卡的旧坐标都要清掉**，否则它们会留在原地错位。
                                //
                                // 副作用（已知并接受）：若这张卡片此前被用户手动摆过，
                                // 它的手工坐标会一并失效、改由自动布局重排。
                                // 这是"插一层会让整棵子树右移一列"的必然结果 ——
                                // 只挪它自己会把它的下级留在原地、连线交叉，反而更糟。
                                // 若只做局部平移（x += 一列宽），子树同样会错位，故不做。
                                graphState.positions.remove(parent.id)
                                graphState.userPlaced.remove(parent.id)
                                graphState.positions.remove(targetId)
                                graphState.userPlaced.remove(targetId)
                                relayoutTick++
                                banner = "已成功插入上级分支卡片（父节点）"
                            }
                        }
                        // 2：折叠 / 展开（对齐旧实现 toggleCollapse）
                        2 -> banner = toggleCollapse(targetId)
                        // 3：提炼结论并合并至父节点（对齐旧实现 mergeBranchInsightToParent）
                        3 -> actionScope.launch {
                            viewModel.prepareBranchMerge(targetId)
                                .onSuccess { job ->
                                    mergeDraft = job.draft
                                    mergeJob = job
                                }
                                .onFailure { banner = it.message ?: "无法发起结论合并" }
                        }
                        // 4：关联其他卡片至当前对话（画布就地勾选模式，免弹窗打断，直接在导图上点选卡片建立下级关联）
                        4 -> {
                            attachCandidates = emptyList()
                            attachTargetId = targetId
                            attachSelectionMode = true
                            selectedAttachIds.clear()
                        }
                    }
                },
                onDelete = {
                    // 只**记下**要删谁，不在这里删：确认对话框需要独立的可取消状态，
                    // 直接删就没有"反悔"的余地了。
                    pendingDeleteNodeId = menuNode.id
                    menuNodeId = null
                }
            )
        }

        // ---- ①-b 删除确认（不可撤销，必须先问一次）----
        //
        // 文案与浮层小窗的删除确认保持一致（见 FloatingWindowCard 的
        // "删除此分支？" / "该分支及其所有下级子分支的对话记录都会被永久删除，
        // 此操作不可撤销。"）：同一件事在两处必须说同样的话。
        pendingDeleteNodeId?.let { deleteId ->
            val deleteNode = nodes.firstOrNull { it.id == deleteId }
            AlertDialog(
                onDismissRequest = { pendingDeleteNodeId = null },
                containerColor = DarkSurface,
                title = { Text("删除此分支？", color = TextPrimary, fontSize = 15.sp) },
                text = {
                    Column {
                        Text(
                            text = "该分支及其所有下级子分支的对话记录都会被永久删除，此操作不可撤销。",
                            color = TextSecondary,
                            fontSize = 13.sp
                        )
                        if (deleteNode != null) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = deleteNode.title.ifBlank { "未命名节点" },
                                color = TextMuted,
                                fontSize = 12.sp,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        // 确认删除：删节点 + 关掉它可能开着的窗（否则会留下
                        // 一扇"内容已被删除"的孤儿窗口）。
                        //
                        // ⚠️ deleteNode 对**根节点**是静默拒绝的
                        // （ChatNodeViewModel.deleteNode 里 `if (node.parentId == null) return`）：
                        // 根节点是整棵树的锚，删掉就没有"当前会话"了。
                        // 这里必须先自己判一次并给出真实反馈，否则会显示
                        // "已删除"而实际什么都没发生 —— 那比不删更糟。
                        if (deleteNode != null && deleteNode.parentId == null) {
                            banner = "这是对话树的根节点，不能删除"
                        } else {
                            viewModel.deleteNode(deleteId)
                            windowManager.windows
                                .filter { it.nodeId == deleteId }
                                .forEach { windowManager.closeWindow(it.id) }
                            banner = "已删除该分支及其下级分支"
                        }
                        pendingDeleteNodeId = null
                    }) {
                        Text("删除", color = DangerRed, fontWeight = FontWeight.SemiBold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { pendingDeleteNodeId = null }) {
                        Text("取消", color = TextMuted)
                    }
                }
            )
        }

        // ---- ② 关联已有卡片：候选列表（同一会话、排除自身 / 后代 / 祖先）----
        LaunchedEffect(attachTargetId) {
            val wanted = attachTargetId
            attachCandidates = if (wanted == null) {
                emptyList()
            } else {
                viewModel.attachableCandidates(wanted)
            }
        }

        // ---- ③ 分支结论提炼与合并（Branch Merge） ----
        mergeJob?.let { job ->
            BranchMergeDialog(
                job = job,
                draft = mergeDraft,
                onDraftChange = { mergeDraft = it },
                onDismiss = { mergeJob = null },
                onConfirm = {
                    val insight = mergeDraft
                    mergeJob = null
                    actionScope.launch {
                        viewModel.mergeBranchInsight(job, insight)
                            .onSuccess { banner = "已成功提炼关键结论并合并反哺至父节点" }
                            .onFailure { banner = it.message ?: "合并反哺失败" }
                    }
                }
            )
        }
    }
}

/**
 * 卡片排版与小窗挤出的**同一次**计算结果。
 *
 * 把两者放在一次计算里是刻意的：若连线用"基准坐标"、卡片用"挤出后坐标"，
 * 就会出现连线钉在原处而卡片已被推开的脱钩现象。同一次算完就不可能有这种不一致。
 */
internal class GraphGeometry(
    /** 节点 id -> 最终内容坐标（基准位置 + 挤出偏移）。 */
    val displaced: Map<String, Displacement>,
    private val sizes: Map<String, Size>,
    val bounds: Rect
) {
    fun sizeOf(nodeId: String, fallback: Size): Size = sizes[nodeId] ?: fallback
}

@Composable
private fun NodeChatContentCompact(
    nodeId: String,
    viewModel: ChatNodeViewModel,
    windowManager: FloatingWindowManager,
    depth: Int
) {
    // 分叉要写库（forkChildNode 是 suspend），而手势回调不是协程上下文，
    // 所以这里取一个组合作用域来启动它 —— 直接在回调里调用会编译不过。
    val scope = rememberCoroutineScope()
    NodeChatContent(
        nodeId = nodeId,
        viewModel = viewModel,
        onForkBranch = { quote ->
            // 在工作台里再分叉：仍然开在图空间，落在当前窗旁边，
            // 这样整棵子树的讨论都留在同一张画布上
            val source = windowManager.windows.firstOrNull { it.nodeId == nodeId }
            scope.launch {
                val child = viewModel.forkChildNode(
                    parentNodeId = nodeId,
                    selectedQuote = quote,
                    sourceMessageId = viewModel.latestMessageIdOfNode(nodeId)
                )
                windowManager.openInPlaceWindow(
                    nodeId = child.id,
                    title = child.title,
                    originQuote = quote,
                    depth = depth + 1,
                    offsetPx = (source?.offset ?: Offset.Zero) + Offset(48f, 64f),
                    widthDp = WindowDefaults.WIDTH,
                    heightDp = WindowDefaults.HEIGHT
                )
            }
        },
        onOpenQuoteWindow = { targetNode ->
            val existing = windowManager.windows.find { it.nodeId == targetNode.id }
            if (existing != null) {
                existing.isMinimized = false
                windowManager.bringToFront(existing.id)
            } else {
                val source = windowManager.windows.firstOrNull { it.nodeId == nodeId }
                windowManager.openInPlaceWindow(
                    nodeId = targetNode.id,
                    title = targetNode.title,
                    originQuote = targetNode.originQuote,
                    depth = depth + 1,
                    offsetPx = (source?.offset ?: Offset.Zero) + Offset(48f, 64f),
                    widthDp = WindowDefaults.WIDTH,
                    heightDp = WindowDefaults.HEIGHT
                )
            }
        }
    )
}

// ============================================================================
// 节点卡片
// ============================================================================

/**
 * 画布上的节点卡片。
 *
 * ## 这是**纯视觉**组件，不挂任何手势
 *
 * 触摸判定（拖动改位置 / 拖边缘改大小 / 长按开窗）全部由外层
 * `TreeTopologyCanvas` 的那一个指针处理器完成，它用与绘制**完全相同**的
 * 坐标数学做命中测试。
 *
 * 为什么必须这样分工：卡片是用 `graphicsLayer` 按画布 scale 缩放渲染的，
 * 而手势回调给的是未缩放的卡片本地坐标。若在卡片内判断"按在哪个部位"，
 * 两套坐标不一致 —— 边缘判定整体偏移，表现就是"边缘改不了大小、
 * 卡片也拖不动"。这个 bug 连续两轮都以这个形态出现，根因就在这里。
 *
 * ⚠️ 不要把 `pointerInput` 加回这个组件。要加交互，请加到画布的指针处理器里。
 */
@Composable
private fun NodeCard(
    node: ConversationNode,
    depth: Int,
    sizePx: Size,
    /** 卡片在**内容坐标系**里的目标位置（含挤出偏移），用于动画插值。 */
    contentOffset: Offset,
    /** 换算到屏幕后的偏移（px），布局用。 */
    screenOffset: Offset,
    scale: Float,
    hasOpenWindow: Boolean,
    /**
     * 是否可见（未被折叠子树隐藏）。
     *
     * 被折叠的后代会以 `false` 传进来：它们**已退出布局与命中**（位置/尺寸不参与
     * geometry 计算，也不在 hitState 里），这里只负责把"离场/入场"演成渐隐渐显 + 缩放，
     * 避免子树的消失变成瞬间硬切。
     */
    visible: Boolean,
    /** 直接子节点数（>0 时才画折叠徽标）。 */
    childCount: Int,
    /** 当前是否处于折叠态（决定徽标图标与颜色）。 */
    isCollapsed: Boolean = false,
    /**
     * 视觉系带强度（0f..1f）。>0 时边框与一层内发光按此强度强调，
     * 用于"某扇小窗刚被激活 / 面包屑刚聚焦到这张卡片"时的一次性闪烁。
     */
    tetherFlash: Float = 0f,
    /** 画布就地勾选模式：该节点是否为当前挂载目标 */
    isAttachTarget: Boolean = false,
    /** 画布就地勾选模式：该节点是否为可勾选的候选分支 */
    isAttachCandidate: Boolean = false,
    /** 画布就地勾选模式：该节点是否已被勾选 */
    isAttachSelected: Boolean = false,
    /**
     * 是否**正在被拖动**（安卓桌面式拖放）。
     *
     * 刻意**不用缩放**表达"被拿起来"：卡片的命中矩形、连线端点、挤出计算
     * 全都基于"布局尺寸"，在这个组件里再叠一次视觉缩放会让
     * "看得见的位置"与"点得到的位置"错开 —— 文件里多处注释都记着这个教训
     *（见 ResizeRole 与其上方"两套坐标系打架"的说明）。
     * 所以"拿起来"只用**阴影 + 加粗边框**表达，完全不碰几何。
     */
    isBeingDragged: Boolean = false,
    /** 安卓桌面式拖放：当前是否为拖放的落点（被拖卡片将挂到它下面）。 */
    isDropTarget: Boolean = false,
    /** 该分支是否标记为敏感内容 */
    isSensitive: Boolean = false
) {
    val isRoot = node.parentId == null
    val accent = if (isRoot) AccentNeonGreen else AccentIceBlue
    val density = LocalDensity.current

    val widthDp = with(density) { sizePx.width.toDp() }
    val heightDp = with(density) { sizePx.height.toDp() }

    // 卡片拉高之后给出更多行，否则拉大了还是只显示两行，白拉
    val quoteMaxLines = if (sizePx.height > 150f) 6 else 2
    val titleMaxLines = if (sizePx.width > 300f) 1 else 1

    // 被挤开时用一个带回弹的弹簧动画：瞬间跳位会让用户以为是列表重排了，
    // 而带阻尼的位移能明确表达"它被那张小窗推开了"
    val animatedOffsetX by animateFloatAsState(
        targetValue = screenOffset.x,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "cardOffsetX"
    )
    val animatedOffsetY by animateFloatAsState(
        targetValue = screenOffset.y,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "cardOffsetY"
    )

    // 折叠/展开的入场与离场：透明度 + 缩放一起动画（M3 风格），不做瞬间硬切。
    // 初值直接落在目标值上（animateFloatAsState 的行为），所以首次组合不会闪一下。
    val appear by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = 220),
        label = "cardAppear"
    )
    val appearScale = 0.88f + 0.12f * appear

    // ---- 语义缩放（LOD）：缩得太小时隐去正文长文本 ----
    //
    // 由 scale 派生、并经过一次 tween 平滑：直接 if(scale<threshold) 会在
    // 用户连续捏合缩放、恰好跨过阈值的那一帧发生"正文整段瞬间消失"的硬切。
    // 这里把它做成一个 0/1 的可动画浮点（0=仅标题层，1=完整层），
    // 用它同时驱动正文透明度，阈值两侧来回穿越时是平滑淡入淡出。
    val lodDetail by animateFloatAsState(
        targetValue = if (scale < CARD_LOD_SCALE_THRESHOLD) 0f else 1f,
        animationSpec = tween(durationMillis = 180),
        label = "cardLodDetail"
    )

    @Suppress("UNUSED_EXPRESSION")
    contentOffset

    Box(
        modifier = Modifier
            .offset { IntOffset(animatedOffsetX.roundToInt(), animatedOffsetY.roundToInt()) }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                transformOrigin = TransformOrigin(0f, 0f)
            }
            // 第二层 graphicsLayer：只负责"入场/离场"的透明度与缩放。
            // 与上面那层分开是因为两者的变换原点不同 —— 画布缩放要从 (0,0) 放大，
            // 而入场缩放要从卡片中心放大；合在一层里没法同时满足。
            .graphicsLayer {
                alpha = appear
                scaleX = appearScale
                scaleY = appearScale
                transformOrigin = TransformOrigin(0.5f, 0.5f)
            }
            .size(widthDp, heightDp)
            // ⚠️ 阴影必须挂在 `.clip` **之前**（链上更靠外）。
            // Compose 的 modifier 链，后面写的都在前面的裁剪范围内 ——
            // 放在 `.clip` 之后，阴影会被裁掉，等于完全没画（而且不报错，很难发现）。
            // 这里让它包住整张卡，形状与卡片一致。
            .then(
                if (isBeingDragged) {
                    Modifier.shadow(
                        elevation = 14.dp,
                        shape = RoundedCornerShape(12.dp),
                        ambientColor = AccentNeonGreen.copy(alpha = 0.45f),
                        spotColor = AccentNeonGreen.copy(alpha = 0.55f)
                    )
                } else {
                    Modifier
                }
            )
            .clip(RoundedCornerShape(12.dp))
            .background(DarkSurface)
            // 视觉系带闪烁：内层再叠一层强调色底，强度随 flash 衰减，
            // 让"来源卡片"在一秒内亮起再淡去，而不是瞬间硬变。
            .background(AccentNeonGreen.copy(alpha = 0.16f * tetherFlash))
            // 拖放落点：整张卡铺一层强调色底，配合下面的加粗边框，
            // 让"松手会挂到这里"一眼可见（安卓桌面把图标拖到文件夹上就是这个观感）。
            .background(AccentNeonGreen.copy(alpha = if (isDropTarget) 0.20f else 0f))
            .border(
                width = when {
                    isDropTarget -> 3.dp
                    isBeingDragged -> 2.dp
                    tetherFlash > 0.01f -> 1.dp + 1.4.dp * tetherFlash
                    isAttachTarget -> 2.dp
                    isAttachSelected -> 2.dp
                    isRoot -> 1.5.dp
                    else -> 1.dp
                },
                color = when {
                    isDropTarget -> AccentNeonGreen
                    isBeingDragged -> AccentNeonGreen.copy(alpha = 0.9f)
                    isAttachTarget -> AccentNeonGreen
                    isAttachSelected -> AccentNeonGreen
                    isSensitive -> Color(0xFFFFB74D).copy(alpha = 0.9f)
                    isAttachCandidate -> AccentIceBlue.copy(alpha = 0.85f)
                    tetherFlash > 0.01f -> AccentNeonGreen.copy(alpha = 0.55f + 0.45f * tetherFlash)
                    hasOpenWindow -> AccentNeonGreen.copy(alpha = 0.95f)
                    else -> accent.copy(alpha = if (isRoot) 0.85f else 0.45f)
                },
                shape = RoundedCornerShape(12.dp)
            )
            .padding(CARD_PAD)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 画布就地勾选模式：候选卡片显示清晰的勾选图标
                if (isAttachCandidate) {
                    Icon(
                        imageVector = if (isAttachSelected) Icons.Filled.CheckCircle else Icons.Outlined.Circle,
                        contentDescription = if (isAttachSelected) "已勾选" else "未勾选",
                        tint = if (isAttachSelected) AccentNeonGreen else TextMuted,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                }

                // 敏感枝条盾牌标识
                if (isSensitive) {
                    Icon(
                        imageVector = Icons.Outlined.Shield,
                        contentDescription = "敏感枝条",
                        tint = Color(0xFFFFB74D),
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                }

                // 层级徽章
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(accent.copy(alpha = 0.16f))
                        .padding(horizontal = 5.dp, vertical = 1.dp)
                ) {
                    Text(
                        text = if (isRoot) "ROOT" else "L$depth",
                        color = accent,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = node.title,
                    color = TextPrimary,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = titleMaxLines,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (hasOpenWindow) {
                    Icon(
                        imageVector = Icons.Default.CropFree,
                        contentDescription = "此卡片已开窗",
                        tint = AccentNeonGreen,
                        modifier = Modifier.size(11.dp)
                    )
                }
                // 给右上角的「更多」(⋮) 角标留出位置，避免标题行末端的图标压在它下面
                Spacer(modifier = Modifier.width(16.dp))
            }

            Spacer(modifier = Modifier.height(6.dp))

            // 语义缩放：正文（originQuote / 占位提示）在 scale < 阈值 时淡出并跳过渲染。
            // lodDetail 完全归零时不再组合这行 Text —— 密集画布上省下的正是
            // 排版与绘制开销最大的一块（每张卡片一段多行省略文本）。
            if (lodDetail > 0.02f) {
                Text(
                    text = node.originQuote?.takeIf { it.isNotBlank() }
                        ?.let { "“${it.trim()}”" }
                        ?: "点击可在原地开窗对话",
                    color = TextSecondary,
                    fontSize = 10.sp,
                    lineHeight = 14.sp,
                    maxLines = quoteMaxLines,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.graphicsLayer { alpha = lodDetail }
                )
            }
        }

        // ---- 缩放手柄（纯视觉，不挂手势） ----
        //
        // 这三个把手现在**只是画出来的提示**，不再各自挂 detectDragGestures。
        // 原因：子级检测器 + 父级检测器同抢一批事件，会让"卡片拖不动、边缘也
        // 改不了大小"（两者都失效，而不是其中一方生效）。
        // 现在触摸判定完全由父级那一个手势处理器负责，它按按下位置决定这次拖动
        // 是改大小还是改位置（见 ResizeRole），子级一次事件都不消费。
        //
        // 可见部分依旧只画右下角那个 L 形把手（边缘画东西会让卡片显脏），
        // 但**判定区**由父级用 34dp 计算，比可见图形大得多，手指容易命中。
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .size(13.dp)
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .width(10.dp)
                    .height(1.5.dp)
                    .background(accent.copy(alpha = 0.55f))
            )
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .width(1.5.dp)
                    .height(10.dp)
                    .background(accent.copy(alpha = 0.55f))
            )
        }

        // ---- 右上角「更多」入口（⋮）----
        //
        // 只画图标，不挂手势：点击由画布上那个唯一指针处理器按 moreRects 命中区
        // 判定后打开节点操作菜单（对齐旧实现 CardMoreButton 的右上角入口）。
        Icon(
            imageVector = Icons.Default.MoreVert,
            contentDescription = "节点操作菜单",
            tint = TextSecondary,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(14.dp)
        )

        // ---- 左下角「折叠 / 展开」角标（有直接子节点时才画）----
        //
        // 同样只画不挂手势：点击由画布的 collapseRects 命中区判定。
        // 图标 + 子节点计数，让用户一眼看出"这里还藏着多少分支"。
        if (childCount > 0) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .clip(RoundedCornerShape(6.dp))
                    .background(accent.copy(alpha = 0.14f))
                    .padding(horizontal = 3.dp, vertical = 1.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (isCollapsed) Icons.Default.UnfoldMore else Icons.Default.UnfoldLess,
                    contentDescription = if (isCollapsed) "展开下级分支" else "收起下级分支",
                    tint = accent,
                    modifier = Modifier.size(11.dp)
                )
                Spacer(modifier = Modifier.width(2.dp))
                Text(
                    text = childCount.toString(),
                    color = accent,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

/**
 * 卡片缩放手柄（**已废弃**）。
 *
 * 保留为注释而不是代码：上一版这里挂 `detectDragGestures` 的子级检测器，
 * 与父级手势处理器抢同一批指针事件，导致"卡片拖不动、边缘也改不了大小"。
 * 现在触点判定集中在 NodeCard 的唯一手势处理器里（见 ResizeRole 与
 * dispatchDrag），把手只是画出来的提示。
 *
 * 不要再把子级拖动检测加回来 —— 那正是两者同时失效的根因。
 */

// ============================================================================
// 节点操作弹窗（对齐旧实现 CardActionController 的菜单 / 关联 / 合并）
// ============================================================================

/**
 * 节点操作菜单。
 *
 * 文案与旧实现 `CardActionController.ShowMenuRunnable` 逐字对齐（顺手去掉了
 * 旧实现里"实况选词研读 / 打开沉浸阅读器"两项 —— 那两项通往阅读器与浏览器，
 * 不属于本次要补的三个对话树功能，且相关模块不在改动范围内）：
 *
 *  - 新建下级分支卡片
 *  - 新建上级分支卡片（在卡片与它原来的父节点之间插一层，见 createParentNode）
 *  - 收起 / 展开已有下级分支卡片（文案随当前折叠态切换）
 *  - 提炼结论并合并至父节点
 *  - 关联其他卡片至当前对话
 *
 * ⚠️ 术语：本对话框此前把父节点写成"父节点"，用户明确要求统一改成**父节点**
 *（树结构里的标准叫法），故全部改写。新增/调整菜单项时请保持这个用词。
 *
 * @param onAction 收到菜单项下标（0..4），由调用方分发。
 */
@Composable
private fun NodeActionMenuDialog(
    nodeTitle: String,
    isCollapsed: Boolean,
    onDismiss: () -> Unit,
    onAction: (Int) -> Unit,
    /**
     * 点"删除此分支"。由调用方负责二次确认与真正的删除 ——
     * 这个对话框只呈现菜单，不持有 ViewModel，也不该自己弹第二个对话框
     * （嵌套对话框在 Compose 里容易出现"关掉一个还剩一个"的层级问题）。
     */
    onDelete: () -> Unit
) {
    val toggleTitle = if (isCollapsed) "展开已有下级分支卡片" else "收起已有下级分支卡片"
    val toggleDesc =
        if (isCollapsed) "恢复显示本节点下挂载的所有子分支" else "折叠隐藏下级分支以精简画布视觉"
    val rows: List<Pair<ImageVector, Pair<String, String>>> = listOf(
        Icons.Default.Add to ("新建下级分支卡片" to "派生新的下级思考分支与对话推演"),
        Icons.Default.Add to ("新建上级分支卡片" to "在本卡片与它原来的父节点之间插入一层新的思考节点"),
        (if (isCollapsed) Icons.Default.UnfoldMore else Icons.Default.UnfoldLess) to
            (toggleTitle to toggleDesc),
        Icons.Default.CallMerge to
            ("提炼结论并合并至父节点" to "提炼分支核心学术结论并反哺父节点上下文 (Branch Merge)"),
        Icons.Default.AccountTree to ("关联其他卡片至当前对话" to "挂载孤立或暂未入树的已有思考卡片")
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(
                    text = "节点操作 · 分支推演",
                    color = AccentIceBlue,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = nodeTitle.ifBlank { "当前思考节点" },
                    color = TextPrimary,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .heightIn(max = 380.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                rows.forEachIndexed { index, (icon, labels) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { onAction(index) }
                            .padding(horizontal = 8.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = labels.first,
                            tint = AccentIceBlue,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = labels.first,
                                color = TextPrimary,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = labels.second,
                                color = TextMuted,
                                fontSize = 11.sp
                            )
                        }
                    }
                }

                // ---- 删除此分支（危险操作，单独一档）----
                //
                // 为什么不放进上面的 rows：那 4 项是同一层级的中性操作，
                // 而删除会造成不可逆的数据损失，必须靠**颜色 + 分隔线**在视觉上
                // 隔开，避免误点（"连点几下菜单"时最危险）。
                // 文案与浮层小窗的删除逐字一致（见 FloatingWindowCard 的
                // "删除此分支" / "删除此分支？"），同类操作在两处不该有两套说法。
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 6.dp),
                    color = WindowBorderColor
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { onDelete() }
                        .padding(horizontal = 8.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "删除此分支",
                        tint = DangerRed,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "删除此分支",
                            color = DangerRed,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "永久删除本卡片及其所有下级分支的对话记录（不可撤销）",
                            color = TextMuted,
                            fontSize = 11.sp
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消", color = TextMuted) }
        }
    )
}

/**
 * 关联已有卡片：列出可挂载为当前节点下级分支的候选。
 *
 * 候选由 [ChatNodeViewModel.attachableCandidates] 算好（同一会话、排除自身 /
 * 后代 / 祖先）。列表为空时给出与旧实现一致的提示文案。
 */
@Composable
private fun AttachCardPickerDialog(
    candidates: List<ConversationNode>,
    onDismiss: () -> Unit,
    onPick: (ConversationNode) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "选择要关联至本节点的卡片",
                color = TextPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .heightIn(max = 400.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (candidates.isEmpty()) {
                    Text(text = "暂无其他可关联的卡片", color = TextMuted, fontSize = 13.sp)
                }
                candidates.forEach { node ->
                    // 标题兜底与旧实现一致：空标题显示"思考卡片 (前6位 id)"
                    val label = node.title.ifBlank { "思考卡片 (" + node.id.take(6) + ")" }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { onPick(node) }
                            .padding(horizontal = 8.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.AccountTree,
                            contentDescription = null,
                            tint = AccentIceBlue,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = label,
                                color = TextPrimary,
                                fontSize = 13.5.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            node.originQuote?.takeIf { it.isNotBlank() }?.let { quote ->
                                Text(
                                    text = quote,
                                    color = TextMuted,
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消", color = TextMuted) }
        }
    )
}

/**
 * 分支结论提炼与合并 (Branch Merge)。
 *
 * 与旧实现一致：草稿是**预填后交给用户编辑**的（分支 summary 或最后一条助手消息），
 * 这里不调用任何模型，因此不会凭空生成用户没写过的结论。
 * 确认后由 [ChatNodeViewModel.mergeBranchInsight] 落库。
 */
@Composable
private fun BranchMergeDialog(
    job: BranchMergeJob,
    draft: String,
    onDraftChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "分支结论提炼与合并 (Branch Merge)",
                color = TextPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column {
                Text(
                    text = "来自探索分支：「${job.branchTitle}」" +
                        (job.parentTitle?.takeIf { it.isNotBlank() }?.let { " → 反哺父节点「$it」" } ?: ""),
                    color = TextMuted,
                    fontSize = 11.5.sp
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = draft,
                    onValueChange = onDraftChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 96.dp),
                    placeholder = {
                        Text(
                            text = "输入或微调提炼出的核心学术结论（用于反哺父节点上下文）",
                            fontSize = 12.sp
                        )
                    },
                    minLines = 3
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("确认合并入主干", color = AccentNeonGreen)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消", color = TextMuted) }
        }
    )
}

/**
 * 切换某节点的折叠状态并返回提示文案。
 *
 * 文案与旧实现 `toggleCollapse` 的两条 Toast 逐字一致。
 * 写成顶层函数（而非组合内的局部函数）是为了让**画布的指针闭包**也能直接调用它 ——
 * 局部函数必须先声明后使用，而指针处理器写在 modifier 里、位于函数体之前。
 */
private fun toggleCollapse(nodeId: String): String {
    val nowCollapsed = BranchCollapseState.toggle(nodeId)
    return if (nowCollapsed) "已收起该卡片的所有下级分支" else "已展开下级分支卡片"
}

/**
 * 计算可见节点：全部节点减去"被折叠节点的所有后代"。
 *
 * - 被折叠节点**自身仍可见**（"收起该卡片的所有下级分支"字面语义）；
 * - 用 visited 集合兜底：即使历史数据里出现成环，也不会死循环。
 */
private fun computeVisibleNodes(
    nodes: List<ConversationNode>,
    collapsedIds: Set<String>
): List<ConversationNode> {
    if (collapsedIds.isEmpty()) return nodes
    val byParent = nodes.groupBy { it.parentId }
    val hidden = mutableSetOf<String>()
    collapsedIds.forEach { rootId ->
        val stack = ArrayDeque(byParent[rootId].orEmpty())
        while (stack.isNotEmpty()) {
            val child = stack.removeFirst()
            if (hidden.add(child.id)) {
                stack.addAll(byParent[child.id].orEmpty())
            }
        }
    }
    if (hidden.isEmpty()) return nodes
    return nodes.filterNot { it.id in hidden }
}

// ============================================================================
// 辅助
// ============================================================================

@Composable
private fun GraphControlButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(DarkSurfaceVariant.copy(alpha = 0.95f))
            .border(1.dp, WindowBorderColor, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = TextPrimary,
            modifier = Modifier.size(19.dp)
        )
    }
}

private fun bezierPath(start: Offset, end: Offset): Path {
    val dx = abs(end.x - start.x).coerceAtLeast(60f) * 0.5f
    return Path().apply {
        moveTo(start.x, start.y)
        cubicTo(
            start.x + dx, start.y,
            end.x - dx, end.y,
            end.x, end.y
        )
    }
}

/**
 * 生成一张 36×36 的点阵贴图，用作平铺背景。
 *
 * 为什么不用"逐点 drawCircle"：
 * 旧实现按「全部内容包围盒 + 260px 余量」逐点画圆。对话树稍一铺开，
 * 内容区就能到 3000×2000 像素，按 34px 间距就是每帧约 7600 次 drawCircle，
 * 每次还要分配一个 Offset —— 单这一项就足以把帧率打到 20 以下。
 * 改成一张贴图 + TileMode.Repeated，每帧只剩一次 drawRect，且零分配。
 */
@Composable
private fun rememberDotGridTile(color: Color): ImageBitmap {
    val tileColor = color.copy(alpha = 0.20f)
    return remember(tileColor) {
        val tile = 36
        val bitmap = ImageBitmap(tile, tile)
        val canvas = androidx.compose.ui.graphics.Canvas(bitmap)
        val paint = Paint().apply {
            this.color = tileColor
            isAntiAlias = true
        }
        canvas.drawCircle(Offset(tile / 2f, tile / 2f), 1.2f, paint)
        bitmap
    }
}

/** 计算每个节点的层级深度（根为 0）。 */
private fun computeDepths(nodes: List<ConversationNode>): Map<String, Int> {
    val byId = nodes.associateBy { it.id }
    val cache = mutableMapOf<String, Int>()

    fun depthOf(id: String): Int {
        cache[id]?.let { return it }
        val node = byId[id] ?: return 0
        val parentId = node.parentId ?: return 0
        if (parentId == id) return 0
        val d = depthOf(parentId) + 1
        cache[id] = d
        return d
    }

    return nodes.associate { it.id to depthOf(it.id) }
}

/**
 * 由节点树现算某个节点的祖先链（根在前、直接父节点在后），用于小窗面包屑。
 *
 * 只沿 parentId 上溯，不臆造顺序；链上的每一节都带上该节点的 sourceMessageId，
 * 这样点面包屑既能"在画布上聚焦这张卡片"，也能"跳回母段落对应消息"。
 * 环或超深（脏数据）由 guard 兜底 —— 绝不死循环。
 */
private fun ancestorTrail(
    nodes: List<ConversationNode>,
    nodeId: String
): List<WindowCrumb> {
    val byId = nodes.associateBy { it.id }
    val chain = ArrayDeque<WindowCrumb>()
    var cursor = byId[nodeId]?.parentId
    var guard = 0
    while (cursor != null && guard++ < 64) {
        val node = byId[cursor] ?: break
        chain.addFirst(WindowCrumb(node.id, node.title, node.sourceMessageId))
        if (node.parentId == node.id) break
        cursor = node.parentId
    }
    return chain.toList()
}

/** 缩放到刚好容纳全部内容并居中。 */
private fun fitContent(
    graphState: GraphViewState,
    positions: Collection<Offset>,
    cardWidthPx: Float,
    cardHeightPx: Float,
    viewport: IntSize
) {
    if (positions.isEmpty() || viewport.width == 0 || viewport.height == 0) return

    val minX = positions.minOf { it.x }
    val maxX = positions.maxOf { it.x } + cardWidthPx
    val minY = positions.minOf { it.y }
    val maxY = positions.maxOf { it.y } + cardHeightPx

    val contentW = (maxX - minX).coerceAtLeast(1f)
    val contentH = (maxY - minY).coerceAtLeast(1f)

    val padding = 90f
    val scaleX = (viewport.width - padding * 2) / contentW
    val scaleY = (viewport.height - padding * 2) / contentH
    val newScale = min(scaleX, scaleY).coerceIn(0.35f, 1.3f)

    graphState.scale = newScale
    graphState.pan = Offset(
        x = (viewport.width - contentW * newScale) / 2f - minX * newScale,
        y = (viewport.height - contentH * newScale) / 2f - minY * newScale
    )
}
