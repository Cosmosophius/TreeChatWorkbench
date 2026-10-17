package com.treechat.workbench.ui.selection

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.text.TextLayoutResult
import com.treechat.workbench.ui.markdown.MdRichBlock

/**
 * 命中判定的容差（像素）：手指本身有宽度，严格贴边会导致点不中。
 *
 * 放在**文件级**而不是控制器内部：表格的逐格命中（[BlockLayoutSnapshot] 的
 * 两个 hit 方法）也必须用同一个容差，否则正文与表格会有一套宽一套严的判定。
 * 控制器内的 `SLOP_PX` 直接取这里，避免两处各写一个数字日后对不上。
 */
private const val HIT_SLOP_PX = 12f

/**
 * 在一行之内，用**逐字符包围盒**找出离落点最近的字符偏移。
 *
 * ## 为什么不用 `TextLayoutResult.getOffsetForPosition`
 *
 * 这是"某些位置怎么都选不中 / 选中的位置不对"这一整类问题的根治点。
 *
 * `getOffsetForPosition(x, y)` 内部要先由 y 推定行、再由 x 在行内做水平二分。
 * 它在两个字面上的前提不成立时就会给出**越界或错行**的结果：
 *
 * 1. **CJK 的悬挂标点（禁则处理）**：`，`、`）`、`"` 这类收尾标点会被排版引擎
 *    允许"悬挂"到行尾右侧边界之外。此时行的 `lineRight` **不包含**那个悬挂字符，
 *    而它的实际包围盒却在 lineRight 右边。任何"把 x 夹进 [lineLeft, lineRight]"
 *    的写法都会把 x 夹到这个悬挂字符**左边**，于是那个字永远点不中 ——
 *    屏幕上明明有字，鼠标放上去却选不到。
 * 2. **折行处的行归属**：y 落在两行之间的行距缝隙里时，"这一 y 属于哪一行"
 *    本身没有唯一答案，推定结果会随字体度量微小差异而变，同一视觉位置时灵时不灵。
 *
 * 逐字符包围盒没有这两个前提：它直接拿每个字符在屏幕上的真实矩形，
 * 用"点到矩形的距离"（在矩形内距离为 0，在外为到最近边的距离）取最小。
 * x、y 一起参与距离计算，所以行距缝隙里的落点会自然归属**视觉上最近的那个字**；
 * 悬挂标点的盒子是真的，也就能被正常命中。
 *
 * ## 插入符（caret）语义
 *
 * 选中偏移有两种含义："落在第 i 个字符上"与"插在第 i 个字符前"。
 * 这里按所有编辑器的惯例：落点在字符**中点右侧** ⇒ 偏移取 i+1（插到该字之后），
 * 否则取 i。这样点一个字的左半边与右半边会得到相邻的两个偏移，拖选才跟手。
 *
 * ## 开销
 *
 * 只扫描**当前这一行**的字符（通常几十个），每次命中一次行级扫描，
 * 而不是整块的数千字符。拖选时每帧调用也在预算内。
 *
 * @param line 已经由 y 定好的行号（调用方负责夹进 `[0, lineCount-1]`）
 */
private fun offsetNearestInLine(
    layout: TextLayoutResult,
    line: Int,
    x: Float,
    y: Float
): Int {
    val start = layout.getLineStart(line)
    // visibleEnd = true：排除行尾换行符/尾随空白，避免它们的退化包围盒干扰。
    val end = layout.getLineEnd(line, visibleEnd = true)
    if (start >= end) return start

    var bestIdx = start
    var bestDist = Float.MAX_VALUE
    for (i in start until end) {
        val b = layout.getBoundingBox(i)
        val dx = when {
            x < b.left -> b.left - x
            x > b.right -> x - b.right
            else -> 0f
        }
        val dy = when {
            y < b.top -> b.top - y
            y > b.bottom -> y - b.bottom
            else -> 0f
        }
        val d = dx * dx + dy * dy
        if (d < bestDist) {
            bestDist = d
            bestIdx = i
        }
    }

    // 中点判定：落在该字右半边 ⇒ 插到它后面
    val best = layout.getBoundingBox(bestIdx)
    val mid = (best.left + best.right) / 2f
    return if (x > mid) (bestIdx + 1).coerceAtMost(end) else bestIdx
}

/**
 * 选区内的一个点：第几个块 + 块内第几个可见字符。
 *
 * 用"块下标 + 块内偏移"而不是全局字符偏移，是因为渲染层是**块级**的
 * （每块一个 Text 组件、各自有独立的 TextLayoutResult）。
 * 若用全局偏移，每次命中检测都要把全局偏移换算回"哪一块的第几个字"，
 * 而块与块的显示文本长度之和并不等于源串长度 —— 来回换算极易错位。
 */
data class BlockPoint(val block: Int, val offset: Int)

/**
 * 一次文本选择。
 *
 * start / end 不保证大小顺序：用户可以从后往前拖。所有取值都必须先过
 * [normalized]，这一点集中处理，避免每个使用方各自记得排序。
 */
data class TextSelection(val start: BlockPoint, val end: BlockPoint) {

    fun normalized(): TextSelection = if (cmp(start, end) <= 0) this else TextSelection(end, start)

    /** 只有一个插入点、没有选中任何字符。 */
    fun isCollapsed(): Boolean = cmp(start, end) == 0

    companion object {
        fun cmp(a: BlockPoint, b: BlockPoint): Int =
            if (a.block != b.block) a.block - b.block else a.offset - b.offset
    }
}

/**
 * 落位命中的结果：块下标 + 块内字符偏移 + 该行文本在根坐标系里的矩形。
 *
 * 矩形是给选择手柄定位用的：手柄要画在"选中那句文字"的左右两端，
 * 只有 TextLayoutResult 知道那一行的实际 x/y。
 */
data class HitResult(
    val block: Int,
    val offset: Int,
    val lineRect: Rect,
    val isEndOfLine: Boolean
)

/**
 * 表格里一格的几何快照。
 *
 * ## 为什么表格要按格登记
 *
 * 表格渲染成的是「一格一个 `Text`」（表头行与每个数据行各自套了
 * `horizontalScroll`），**整块不存在任何一个 `TextLayoutResult` 能代表它的
 * 几何** —— 旧行为正是因此把表格整个排除在选中之外（见 TableBlockView 原注释
 * 「表格沿用不可选中的渲染」），用户反馈就是"表格里的文本我选不中"。
 *
 * 现在改成按格登记：命中时先落进具体某一格，再把**格内偏移加上该格在
 * [BlockLayoutSnapshot.text] 中的起始下标 [textStart]**，还原成块内偏移。
 * 这样选区、复制、手柄定位仍然沿用原来那套"块下标 + 块内偏移"语义，
 * 下游（`selectedText()`、工具条、剪贴板）一行都不用改。
 *
 * @property textStart 该格文本在 `block.displayText`（=各格原文用单个空格拼接）里的起始下标
 * @property textLength 该格文本长度
 */
class CellLayoutSnapshot(
    /** 该格左上角在最外层 Box 坐标系里的位置。 */
    val originInRoot: Offset,
    /** 该格自己的排版结果。 */
    val layout: TextLayoutResult,
    val textStart: Int,
    val textLength: Int
)

/**
 * 一个块渲染后的几何与文本快照。
 *
 * 每次布局完成后由 UI 层重建（块内容变了、滚动导致位置变了都要重建）。
 * 命中检测完全建立在这份快照上，因此**它必须与当前屏幕上真正渲染的一致**，
 * 否则会出现"点到的地方和选中的字不是同一个"。
 */
class BlockLayoutSnapshot(
    val blockIndex: Int,
    val block: MdRichBlock,
    /** 本块左上角在根坐标系中的位置。 */
    val originInRoot: Offset,
    val layout: TextLayoutResult,
    /**
     * 表格块的逐格几何（阅读顺序：表头各格 → 各数据行各格），**非表格恒为空**。
     *
     * 非空时 [originInRoot] 与 [layout] 只是占位（取自第一格），
     * 因为表格的所有几何判定都改走 [cells] —— 单一 layout 无法描述网格。
     */
    val cells: List<CellLayoutSnapshot> = emptyList()
) {
    val text: String get() = block.displayText

    /** 表格所有单元格的外接矩形（根坐标系）；非表格返回 null。 */
    fun cellsBounds(): Rect? {
        if (cells.isEmpty()) return null
        var left = Float.MAX_VALUE
        var top = Float.MAX_VALUE
        var right = -Float.MAX_VALUE
        var bottom = -Float.MAX_VALUE
        cells.forEach { cell ->
            val o = cell.originInRoot
            left = minOf(left, o.x)
            top = minOf(top, o.y)
            right = maxOf(right, o.x + cell.layout.size.width)
            bottom = maxOf(bottom, o.y + cell.layout.size.height)
        }
        return Rect(left, top, right, bottom)
    }

    /**
     * 表格：把落点精确解析到某一格的某一行上。
     *
     * 判据与正文一致 —— 先要求落在某一格的实际矩形内，再要求落在该格的
     * 某一行文字行内；格与格之间的内边距、分隔线不算命中（会继续找下一格）。
     */
    fun hitCellExact(positionInRoot: Offset): HitResult? {
        for (cell in cells) {
            val local = positionInRoot - cell.originInRoot
            if (
                local.x < -HIT_SLOP_PX || local.y < -HIT_SLOP_PX ||
                local.x > cell.layout.size.width + HIT_SLOP_PX ||
                local.y > cell.layout.size.height + HIT_SLOP_PX
            ) continue
            // 与正文 hitTest 完全同源：行号夹进有效区间（不再因"落在行间空隙"
            // 就 `continue` 跳过本格），偏移用逐字符包围盒求（不受悬挂标点、
            // 行尾空白、折行归属影响）。格宽只有 116dp、折行更密，
            // 旧写法在表格里丢掉的区段比正文更多。
            val box = cell.layout.getLineForVerticalPosition(local.y.coerceAtLeast(0f))
                .coerceIn(0, cell.layout.lineCount - 1)
            return cellHit(
                cell,
                offsetNearestInLine(cell.layout, box, local.x, local.y),
                box,
                cell.layout.getLineEnd(box, visibleEnd = true)
            )
        }
        return null
    }

    /**
     * 表格：落点没落进任何一格的文字行（落在格间空隙/分隔线上）时，
     * 退化到**最近的一格**，好让拖选能穿过空隙继续扩展。
     */
    fun hitCellNearest(positionInRoot: Offset): HitResult? {
        var best: CellLayoutSnapshot? = null
        var bestDistance = Float.MAX_VALUE
        cells.forEach { cell ->
            val local = positionInRoot - cell.originInRoot
            val dx = when {
                local.x < 0f -> -local.x
                local.x > cell.layout.size.width -> local.x - cell.layout.size.width
                else -> 0f
            }
            val dy = when {
                local.y < 0f -> -local.y
                local.y > cell.layout.size.height -> local.y - cell.layout.size.height
                else -> 0f
            }
            val distance = dx * dx + dy * dy
            if (distance < bestDistance) {
                bestDistance = distance
                best = cell
            }
        }
        val cell = best ?: return null
        val local = positionInRoot - cell.originInRoot
        val box = cell.layout.getLineForVerticalPosition(local.y.coerceAtLeast(0f))
            .coerceIn(0, cell.layout.lineCount - 1)
        // 与 hitCellExact 同源：逐字符包围盒求偏移。
        return cellHit(
            cell,
            offsetNearestInLine(cell.layout, box, local.x, local.y),
            box,
            cell.layout.getLineEnd(box, visibleEnd = true)
        )
    }

    /** 把格内偏移换算成块内偏移，并按块文本长度夹紧。 */
    private fun cellHit(
        cell: CellLayoutSnapshot,
        localOffset: Int,
        box: Int,
        lineEnd: Int
    ): HitResult = HitResult(
        block = blockIndex,
        offset = (cell.textStart + localOffset).coerceIn(0, text.length),
        // 与正文分支保持一致：返回的是**相对该 Text 节点**的行矩形
        lineRect = Rect(
            left = cell.layout.getLineLeft(box),
            top = cell.layout.getLineTop(box),
            right = cell.layout.getLineRight(box),
            bottom = cell.layout.getLineBottom(box)
        ),
        isEndOfLine = localOffset >= lineEnd
    )

    /**
     * 表格：把块内偏移还原回它所在的那一格，返回该字符边缘的**根坐标系**矩形。
     * 语义（左闭右开、trailing 取前一个字符的右缘）与正文分支完全一致。
     */
    fun cellRectFor(offset: Int, trailing: Boolean): Rect? {
        val textLen = text.length
        if (textLen == 0 || cells.isEmpty()) return null
        val charIdx = if (!trailing) {
            offset.coerceIn(0, textLen - 1)
        } else {
            (offset - 1).coerceIn(0, textLen - 1)
        }
        val cell = cells.firstOrNull { charIdx >= it.textStart && charIdx < it.textStart + it.textLength }
            ?: cells.last()
        // 空单元格没有字符可取包围盒，退化成该格的左边缘
        if (cell.textLength <= 0) {
            return Rect(
                left = cell.originInRoot.x,
                top = cell.originInRoot.y,
                right = cell.originInRoot.x,
                bottom = cell.originInRoot.y + cell.layout.size.height
            )
        }
        val safeLocal = (charIdx - cell.textStart).coerceIn(0, cell.textLength - 1)
        val line = cell.layout.getLineForOffset(safeLocal)
        val charBox = cell.layout.getBoundingBox(safeLocal)
        val x = if (!trailing) charBox.left else charBox.right
        return Rect(
            left = cell.originInRoot.x + x,
            top = cell.originInRoot.y + cell.layout.getLineTop(line),
            right = cell.originInRoot.x + x,
            bottom = cell.originInRoot.y + cell.layout.getLineBottom(line)
        )
    }
}

/**
 * 一条消息的文本选择状态与命中检测。
 *
 * 职责边界（刻意划清）：
 * - 本类只管"选了什么、手指落在第几个字上"，不碰剪贴板、不碰数据库、不碰主题色。
 * - 高亮落库、提问开窗等动作由 UI 层拿 [TextSelection] 去做。
 * 这样换渲染实现（例如将来换成整块单 Text）时，动作层完全不用改。
 */
class MessageSelectionController {

    var selection by mutableStateOf<TextSelection?>(null)
        private set

    /** 正在拖动的手柄：null / "start" / "end"。 */
    var draggingHandle by mutableStateOf<String?>(null)
        private set

    /**
     * 拖选锚点（鼠标/触控板按下拖动期间有效）。
     *
     * 放在控制器里而不是让 UI 层自己记：调用方一旦忘了归一化或忘了清锚点，
     * 就会出现"下一次拖选从上次的位置开始"这类很难查的错。
     */
    private var rangeAnchor: BlockPoint? = null

    /** 是否处于"已选中、等待用户选操作"的状态（决定工具条显不显示）。 */
    val hasSelection: Boolean get() = selection?.let { !it.isCollapsed() } == true

    /**
     * 当前所有块的布局快照。
     *
     * 用 mutableStateListOf 而不是普通 List：块数量/位置变化要触发工具条与
     * 手柄重新定位，普通 List 改了不会触发重组。
     */
    val blocks = mutableStateListOf<BlockLayoutSnapshot>()

    /**
     * 各块左上角在最外层 Box 坐标系里的位置（块序号 -> 坐标）。
     *
     * ## ⚠️ 为什么放在控制器里，而不是 `SelectableMarkdownText` 内部 remember
     *
     * 这是本项目选字"反复修不好"的**结构性根因**，改了就别挪回去。
     *
     * 原先 `origins` / `layouts` 存在渲染组件内部的 `remember` 里。实测
     * （模拟器 + release 包）拿到过这样的日志：
     *
     *     onTextLayout 触发 块0..块7        ← 布局回调确实跑过，写进了"某张表"
     *     长按成功但命中为空（块=0 布局表=0 位置表=8 解析块=8）
     *
     * 即：位置表有 8 条、布局表却是 0 —— 说明写入落到了**另一个已经失效的
     * 组件实例**的 map 上，而当前实例的两张表并不同时齐全，注册逻辑
     * （要求两张表都有该块）于是永远凑不齐，`blocks` 恒为 0，命中必然失败。
     *
     * 把两张表**提升到控制器**后，控制器由上层（ChatMessageView）按消息
     * 缓存持有，生命周期长于渲染组件：组件被重建也不会丢布局数据。
     */
    val origins = mutableStateMapOf<Int, Offset>()

    /** 各块的排版结果（`TextLayoutResult` 才知道"第 37 个字在哪一行哪一列"）。 */
    val layouts = mutableStateMapOf<Int, TextLayoutResult>()

    /**
     * 表格块的**逐格**几何（块下标 → 按阅读顺序的每一格）。
     *
     * 与 [origins] / [layouts] 同样放在控制器里，理由也一样（见那两处的说明）：
     * 组件被重建时若表还留在组件内部的 remember 里，就会丢一半数据，
     * 注册逻辑凑不齐 → 表格重新变回"选不中"。
     *
     * 值是**整个列表整体替换**（不是原地改）：原地改 List 不会触发重组。
     * `null` 槽位表示该格还没拿到布局，注册时按"格数必须齐"判定就绪。
     */
    val cellOrigins = mutableStateMapOf<Int, List<Offset?>>()
    val cellLayouts = mutableStateMapOf<Int, List<TextLayoutResult?>>()

    /**
     * 表格某一格的坐标就绪（整体替换列表以触发重组）。
     *
     * 槽位表还没开出时**按需生长**而不是丢弃：子节点的 `onGloballyPositioned`
     * 可能早于注册逻辑跑到，丢弃就会让那一格永远是 null、表格永远凑不齐 → 又变回"选不中"。
     */
    fun setCellOrigin(blockIndex: Int, cellIndex: Int, origin: Offset) {
        val next = growCellList(cellOrigins[blockIndex], cellIndex) { List(it) { null } }
        next[cellIndex] = origin
        cellOrigins[blockIndex] = next
    }

    /** 表格某一格的排版就绪（整体替换列表以触发重组）。 */
    fun setCellLayout(blockIndex: Int, cellIndex: Int, layout: TextLayoutResult) {
        val next = growCellList(cellLayouts[blockIndex], cellIndex) { List(it) { null } }
        next[cellIndex] = layout
        cellLayouts[blockIndex] = next
    }

    /**
     * 拿到一份**至少能装下 [cellIndex]** 的可变列表：
     * 已有就复制，没有/太短就补 null 扩到 `cellIndex + 1`。
     *
     * @param fresh 造一张全新的空表（两张表的元素类型不同，故由调用方给模板）
     */
    private fun <T> growCellList(
        current: List<T?>?,
        cellIndex: Int,
        fresh: (Int) -> List<T?>
    ): MutableList<T?> {
        val list = (current ?: fresh(cellIndex + 1)).toMutableList()
        while (list.size <= cellIndex) list.add(null)
        return list
    }

    /**
     * 为某个表格块准备两张槽位表（格数由调用方按解析结果算好）。
     *
     * ## 只在"缺表"或"表比新内容大"时才动手
     *
     * 格子是**逐个**上报的：第 1 格报完就可能触发一次注册。若这里见到
     * 格数对不上就无脑重置，会把**刚报上来的那几格清成 null**，而它们不会
     * 再上报一次（布局没变就不会重跑回调）→ 那一格永远凑不齐 → 表格又选不中。
     *
     * 所以：
     * - 表不存在 → 建一张全 null 的 `cellCount` 长表；
     * - 表比新内容**大**（上一份内容残留）→ 收缩重建；
     * - 表比新内容**小** → 说明正在逐格生长，**别动**，交给 [setCellOrigin]/[setCellLayout] 继续扩。
     */
    fun initTableCells(blockIndex: Int, cellCount: Int) {
        val currentOrigins = cellOrigins[blockIndex]
        if (currentOrigins == null || currentOrigins.size > cellCount) {
            cellOrigins[blockIndex] = List<Offset?>(cellCount) { null }
        }
        val currentLayouts = cellLayouts[blockIndex]
        if (currentLayouts == null || currentLayouts.size > cellCount) {
            cellLayouts[blockIndex] = List<TextLayoutResult?>(cellCount) { null }
        }
    }

    /** 内容变化时一并清掉表格几何（否则旧表的格数对不上新表，永远凑不齐）。 */
    fun clearCellGeometry() {
        cellOrigins.clear()
        cellLayouts.clear()
    }

    /**
     * 本控制器最近一次渲染的 markdown。
     *
     * 用来判断"内容到底有没有变"：`LaunchedEffect(markdown)` 在组件**首次**
     * 组合时同样会执行，若不加判断就会在新实例上把已有布局清空 ——
     * 上一条日志里的"位置表=8 布局表=0"正是这种清空造成的。
     */
    var renderedMarkdown by mutableStateOf<String?>(null)

    /**
     * 选择手势最近一次的结果（诊断用）。
     *
     * ## 为什么要有它
     * "长按没反应"这类问题无法只靠读代码定位：可能的原因至少有四种，
     * 而且表现**完全一样**（界面毫无变化）：
     *   A. 手势检测器根本没被执行（被父级滚动容器抢走了事件）；
     *   B. 长按判定没通过（手指在计时内移动超过阈值 → 被判成拖动）；
     *   C. 长按通过了，但 [hitTest] 返回 null → 没有可命中的块；
     *   D. 命中了也选中了，但选区/手柄没被渲染出来。
     * 这四种的**修法完全不同**，所以必须先分清是哪一种。
     *
     * 本项目已有先例：闪退就是靠"让崩溃堆栈落盘"才定位的，
     * 之前靠静态读代码猜崩溃点连续多轮全部失败。
     * 这里用同样的思路 —— 把判定结果记下来，让现象可见。
     *
     * 界面只在用户打开诊断开关时显示它（见 ChatMessageView）。
     */
    var lastGestureResult by mutableStateOf<String?>(null)

    /** 当前已注册的块数量，配合 [lastGestureResult] 判断"C 还是 D"。 */
    val registeredBlockCount: Int get() = blocks.size

    /** 记录一次手势结果，同时输出到 logcat（接 adb 时也能看到）。 */
    fun noteGesture(message: String) {
        lastGestureResult = message
        android.util.Log.d("TreeChatSelection", message)
    }

    // ------------------------------------------------------------ 快照维护

    fun upsertBlock(snapshot: BlockLayoutSnapshot) {
        val existing = blocks.indexOfFirst { it.blockIndex == snapshot.blockIndex }
        if (existing >= 0) {
            blocks[existing] = snapshot
        } else {
            blocks.add(snapshot)
            blocks.sortBy { it.blockIndex }
        }
    }

    /**
     * 取某个块当前已注册的快照（没有则 null）。
     *
     * 供渲染层判断"这块的快照是否仍然有效"：块对象可以被复用，
     * 但它在屏幕上的位置会变（列表滚动、上一条消息增高、窗口改尺寸），
     * 此时旧快照里的 origin 已经失效，必须重建 —— 否则命中检测整体错位，
     * 表现为"选词突然全线失效，点一下别的 tab 又恢复"。
     */
    fun snapshotAt(blockIndex: Int): BlockLayoutSnapshot? =
        blocks.firstOrNull { it.blockIndex == blockIndex }

    fun removeBlock(blockIndex: Int) {
        blocks.removeAll { it.blockIndex == blockIndex }
    }

    fun clearBlocks() {
        blocks.clear()
    }

    // ------------------------------------------------------------ 选区操作

    fun clear() {
        selection = null
        draggingHandle = null
    }

    fun selectAll() {
        if (blocks.isEmpty()) return
        val first = blocks.first()
        val last = blocks.last()
        selection = TextSelection(
            start = BlockPoint(first.blockIndex, 0),
            end = BlockPoint(last.blockIndex, last.text.length)
        )
    }

    /**
     * 长按句读切分：把长按位置所在的一句话选出来。
     *
     * 为什么不整段选：整段选会把"提问引用"变成一大坨，
     * 而用户长按通常是想针对某一句话发问。切句规则用中英文标点一起判，
     * 命中不到标点时就退回整块。
     */
    fun selectSentenceAt(hit: HitResult) {
        val snapshot = blocks.firstOrNull { it.blockIndex == hit.block } ?: return
        val bounds = sentenceBounds(snapshot.text, hit.offset)
        selection = TextSelection(
            start = BlockPoint(hit.block, bounds.first),
            end = BlockPoint(hit.block, bounds.second)
        )
    }

    /** 系统原生模式下的长按：精准选中落点所在的单个词语或单词 */
    fun selectWordAt(hit: HitResult) {
        val snapshot = blocks.firstOrNull { it.blockIndex == hit.block } ?: return
        val bounds = wordBounds(snapshot.text, hit.offset)
        selection = TextSelection(
            start = BlockPoint(hit.block, bounds.first),
            end = BlockPoint(hit.block, bounds.second)
        )
    }

    /**
     * 开始一次"锚点拖选"（鼠标/触控板：按下即锚点、拖动扩选）。
     *
     * 与 [selectSentenceAt] 的区别：那个选的是**整个句子**，
     * 而拖选要的是**任意范围** —— 所以先把选区收成锚点处的一个空选区，
     * 再由 [extendRangeSelectionTo] 逐帧扩展另一端。
     */
    fun beginRangeSelection(anchor: HitResult) {
        val point = BlockPoint(anchor.block, anchor.offset)
        rangeAnchor = point
        selection = TextSelection(point, point)
    }

    /**
     * 拖选中：把焦点一端移到 focus，另一端固定为锚点。
     *
     * [TextSelection.normalized] 不可省：拖选时焦点可能在锚点**之前**
     * （从右往左拖），不归一化的话手柄位置与高亮投影都会算反。
     */
    fun extendRangeSelectionTo(focus: HitResult) {
        val anchor = rangeAnchor ?: return
        selection = TextSelection(
            anchor,
            BlockPoint(focus.block, focus.offset)
        ).normalized()
    }

    /** 结束拖选：清锚点，**保留选区**（桌面惯例：松手后还能直接复制）。 */
    fun endRangeSelection() {
        rangeAnchor = null
    }

    /** 双击/长按后用户拖动手柄：把手柄移到新的落点，拖拽途中绝不坍缩为空选区。 */
    fun moveHandle(handle: String, hit: HitResult) {
        val current = selection ?: return
        val target = BlockPoint(hit.block, hit.offset)
        val raw = when (handle) {
            START_HANDLE -> TextSelection(target, current.end)
            END_HANDLE -> TextSelection(current.start, target)
            else -> current
        }
        // 拖拽过程中若两端重合在同一块同一偏移，自动保留至少 1 个字符宽度，
        // 防止 isCollapsed() 瞬间为 true 导致 hasSelection 变 false 而中断拖拽！
        selection = if (raw.isCollapsed()) {
            val snap = blocks.firstOrNull { it.blockIndex == target.block }
            val maxLen = snap?.text?.length ?: 0
            if (maxLen > 0) {
                if (target.offset < maxLen) {
                    TextSelection(target, BlockPoint(target.block, target.offset + 1))
                } else {
                    TextSelection(BlockPoint(target.block, (target.offset - 1).coerceAtLeast(0)), target)
                }
            } else {
                raw
            }
        } else {
            raw
        }
    }

    fun beginHandleDrag(handle: String) {
        selection = selection?.normalized()
        draggingHandle = handle
    }

    fun endHandleDrag() {
        selection = selection?.normalized()
        draggingHandle = null
    }

    // ------------------------------------------------------------ 命中检测

    /**
     * 把根坐标系里的一个点解析成"第几块第几个字"。
     *
     * 判定顺序（这个顺序是有意的）：
     * 1. **先在同一条文本行内找**。只有落在某一行文字的实际矩形内，才算直接命中；
     *    行间空白、块与块之间的间距不算命中 —— 否则在段落间隙轻点也会命中上一行。
     * 2. 都不在行内时，退化为"离得最近的一块"，并把偏移夹到该块范围内。
     *    这一步是为了让"拖到段落外面"仍能继续扩展选区，而不是突然跳回原处。
     */
    fun hitTest(positionInRoot: Offset): HitResult? {
        if (blocks.isEmpty()) return null

        // 1. 优先精确命中某一行
        for (snapshot in blocks) {
            // 表格块没有"整块的一个 Text"，必须逐格解析（见 CellLayoutSnapshot）
            if (snapshot.cells.isNotEmpty()) {
                snapshot.hitCellExact(positionInRoot)?.let { return it }
                continue
            }
            val local = positionInRoot - snapshot.originInRoot
            val layout = snapshot.layout
            // 块级边界检查：**只认落在本块外接矩形内（含 slop）的点**。
            // 这是"不要在段落之间的空隙里把命中抢给隔壁段落"的那道闸。
            // 注意用 layout.size（含行距的整块高度），不是某一行的 lineBottom ——
            // 后者是"行间缝隙被误判成块外"的根源。
            if (
                local.x < -SLOP_PX || local.y < -SLOP_PX ||
                local.x > layout.size.width + SLOP_PX ||
                local.y > layout.size.height + SLOP_PX
            ) continue

            val rawY = local.y.coerceAtLeast(0f)
            val rawBox = layout.getLineForVerticalPosition(rawY)
            // ⚠️ 行号取到之后**先夹进本块有效的行区间**，再判上下界。
            //
            // 旧代码是：取行 → 判 y 是否落在这行的 [lineTop, lineBottom] 内 → 不落就 `continue`
            // 跳到**下一个块**。这有两个系统性缺陷：
            //
            // 1. **行间空隙被整块丢弃**。lineHeight 取的是 1.62 倍字号，
            //    文字行本身只占其中一部分，行与行之间有几十像素的缝隙。
            //    鼠标落在缝隙里（尤其平板上行距更大）时，本块被直接跳过，
            //    逻辑掉到"最近块"那条退化路径上 —— 那条路径算出的偏移
            //    与"点在某一行上"不是同一种语义，表现就是"这一段里有些位置选得中、
            //    有些位置选不中，而且选中的字还差几个"。
            //
            // 2. **`continue` 会误伤后续全部块**。一旦某个块的 y 落在缝隙里被跳过，
            //    循环继续去试后面的块；若后面的块恰好 x 也覆盖该点（宽度都是 fillMaxWidth），
            //    就会命中**错误的那一块**，选区跳到别的段落。
            //
            // 现在改成：只要这个点**在块的外接矩形内**（上面的 x/y 检查已经保证），
            // 就一定要在本块内给出一个答案 —— 用"离得最近的文字行"来定行，
            // 再把 x 夹进该行有字的范围。这样块内任何一点都必然解析出偏移，
            // 不会再出现"某些位置选不中"。
            val box = rawBox.coerceIn(0, layout.lineCount - 1)
            val lineTop = layout.getLineTop(box)
            val lineBottom = layout.getLineBottom(box)

            // ⚠️ 用逐字符包围盒求偏移，**不再**用 getOffsetForPosition + 夹取。
            //
            // 前一版是把 x 夹进 [lineLeft, lineRight] 再交给 getOffsetForPosition。
            // 那仍然解决不了，因为在 CJK 排版里 `，` `）` `"` 这类收尾标点会被
            // **悬挂**（禁则处理）到行尾右边界**之外**：lineRight 不包含它们，
            // 而它们的真实包围盒在 lineRight 右边。于是"夹进 lineLeft..lineRight"
            // 恰好把 x 夹到了悬挂字符的**左边**，那个字永远点不中 ——
            // 屏幕上明明是可见的字，鼠标放上去却选不到。
            // 这正是用户反复报告的「到"合理内核"，/ 抛弃 这一带就选不中」：
            // 报错点两侧紧邻的正是悬挂标点。
            //
            // offsetNearestInLine 直接比较每个字符的真实矩形，与行边界无关，
            // 悬挂标点、行距缝隙、折行归属这三个坑一次性全部绕开。
            val offset = offsetNearestInLine(layout, box, local.x, local.y)
            val end = layout.getLineEnd(box, visibleEnd = true)
            return HitResult(
                block = snapshot.blockIndex,
                offset = offset.coerceIn(0, snapshot.text.length),
                lineRect = Rect(
                    left = layout.getLineLeft(box),
                    top = lineTop,
                    right = layout.getLineRight(box),
                    bottom = lineBottom
                ),
                isEndOfLine = offset >= end
            )
        }

        // 1.5 表格的退化命中：点落在格间空隙/分隔线上时认最近的一格，
        //     但只认**落在表格外接矩形附近**的点，免得隔老远就把命中抢走。
        for (snapshot in blocks) {
            if (snapshot.cells.isEmpty()) continue
            val bounds = snapshot.cellsBounds() ?: continue
            val margin = SLOP_PX * 8f
            if (
                positionInRoot.x < bounds.left - margin ||
                positionInRoot.y < bounds.top - margin ||
                positionInRoot.x > bounds.right + margin ||
                positionInRoot.y > bounds.bottom + margin
            ) continue
            snapshot.hitCellNearest(positionInRoot)?.let { return it }
        }

        // 2. 退化为最近块
        var best: BlockLayoutSnapshot? = null
        var bestDistance = Float.MAX_VALUE
        for (snapshot in blocks) {
            // 表格块的 layout 只是占位（第一格），拿它算距离会得到错误的最近块
            if (snapshot.cells.isNotEmpty()) continue
            val local = positionInRoot - snapshot.originInRoot
            val layout = snapshot.layout
            val dx = when {
                local.x < 0f -> -local.x
                local.x > layout.size.width -> local.x - layout.size.width
                else -> 0f
            }
            val dy = when {
                local.y < 0f -> -local.y
                local.y > layout.size.height -> local.y - layout.size.height
                else -> 0f
            }
            val distance = dx * dx + dy * dy
            if (distance < bestDistance) {
                bestDistance = distance
                best = snapshot
            }
        }
        val snapshot = best ?: return null
        val local = positionInRoot - snapshot.originInRoot
        val box = snapshot.layout.getLineForVerticalPosition(local.y.coerceAtLeast(0f))
            .coerceIn(0, snapshot.layout.lineCount - 1)
        // 与 hitTest 同源：逐字符包围盒求偏移（拖到行尾/悬挂标点附近也不会跳行）。
        val offset = offsetNearestInLine(snapshot.layout, box, local.x, local.y)
        return HitResult(
            block = snapshot.blockIndex,
            offset = offset.coerceIn(0, snapshot.text.length),
            lineRect = Rect(
                left = snapshot.layout.getLineLeft(box),
                top = snapshot.layout.getLineTop(box),
                right = snapshot.layout.getLineRight(box),
                bottom = snapshot.layout.getLineBottom(box)
            ),
            isEndOfLine = false
        )
    }

    /**
     * 某个块内偏移在根坐标系里的字符边缘矩形（手柄与浮动工具栏定位用）。
     *
     * 注意：TextSelection 是**左闭右开区间 [start, end)**！
     * - 当 trailing = false（起始端）：取第 safe 个字符的左缘与所在行上下界；
     * - 当 trailing = true（末端）：最后一个被选中的字符实际是 safe - 1，
     *   必须取 safe - 1 字符的右缘与它所在的行！
     *   旧代码对已经是开区间的 end 又做了 safe + 1，不仅多算了一个字，
     *   在行尾折行处还会跳到下一行的行首 x = 0！
     */
    fun handleRectFor(blockIndex: Int, offset: Int, trailing: Boolean): Rect? {
        val snapshot = blocks.firstOrNull { it.blockIndex == blockIndex } ?: return null
        // 表格块：块内偏移还原到它所在的那一格去取字符边缘（单一 layout 描述不了网格）
        if (snapshot.cells.isNotEmpty()) return snapshot.cellRectFor(offset, trailing)
        val textLen = snapshot.text.length
        if (textLen == 0) {
            val top = snapshot.layout.getLineTop(0)
            val bottom = snapshot.layout.getLineBottom(0)
            return Rect(
                left = snapshot.originInRoot.x,
                top = snapshot.originInRoot.y + top,
                right = snapshot.originInRoot.x,
                bottom = snapshot.originInRoot.y + bottom
            )
        }
        val charIdx = if (!trailing) {
            offset.coerceIn(0, textLen - 1)
        } else {
            (offset - 1).coerceIn(0, textLen - 1)
        }
        val line = snapshot.layout.getLineForOffset(charIdx)
        val charBox = snapshot.layout.getBoundingBox(charIdx)
        val x = if (!trailing) {
            charBox.left
        } else {
            charBox.right
        }
        val top = snapshot.layout.getLineTop(line)
        val bottom = snapshot.layout.getLineBottom(line)
        return Rect(
            left = snapshot.originInRoot.x + x,
            top = snapshot.originInRoot.y + top,
            right = snapshot.originInRoot.x + x,
            bottom = snapshot.originInRoot.y + bottom
        )
    }

    /**
     * 两个拖拽手柄对应的字符边缘矩形（在内容坐标系里）。
     * 不再对 x 强加 ±HANDLE_RADIUS 的水平硬偏移，防止把行首/行尾手柄推到屏幕外。
     */
    fun handleRects(): Pair<Rect, Rect>? {
        val sel = selection?.normalized() ?: return null
        val startRect = handleRectFor(sel.start.block, sel.start.offset, trailing = false)
        val endRect = handleRectFor(sel.end.block, sel.end.offset, trailing = true)
        return if (startRect != null && endRect != null) startRect to endRect else null
    }

    /**
     * 手柄水滴圆球在组件坐标系中的视觉中心：
     * 悬挂在对应字符底边正下方（bottom + HANDLE_RADIUS * 0.55f），既不遮挡正文，又符合原生直觉。
     */
    fun handleCenterFor(handle: String): Offset? {
        val (startRect, endRect) = handleRects() ?: return null
        val rect = if (handle == START_HANDLE) startRect else endRect
        return Offset(rect.left, rect.bottom + HANDLE_RADIUS * 0.55f)
    }

    /**
     * 手柄对应的字符行内中心锚点（拖拽时用于将手指位移精确还原回文字行中心）。
     */
    fun charAnchorFor(handle: String): Offset? {
        val (startRect, endRect) = handleRects() ?: return null
        val rect = if (handle == START_HANDLE) startRect else endRect
        return Offset(rect.left, (rect.top + rect.bottom) / 2f)
    }

    /**
     * 计算当前选区在组件坐标系内的包围矩形。
     * 用于像 Word / 系统原生浮动菜单一样，将工具栏精准挂在选区文字上方。
     */
    fun selectionBoundingRect(): Rect? {
        val (startRect, endRect) = handleRects() ?: return null
        val minTop = kotlin.math.min(startRect.top, endRect.top)
        val maxBottom = kotlin.math.max(startRect.bottom, endRect.bottom)
        val minLeft = kotlin.math.min(startRect.left, endRect.left)
        val maxRight = kotlin.math.max(startRect.right, endRect.right)
        return Rect(minLeft, minTop, maxRight, maxBottom)
    }

    /**
     * 判断某个点是否落在某个手柄的抓取范围内。
     * 同时覆盖字符竖线本身与下方悬挂的水滴圆球热区。
     */
    fun handleAt(position: Offset): String? {
        val startCenter = handleCenterFor(START_HANDLE) ?: return null
        val endCenter = handleCenterFor(END_HANDLE) ?: return null
        val (startRect, endRect) = handleRects() ?: return null

        val startZone = Rect(
            left = startCenter.x - HANDLE_TOUCH_SLOP,
            top = startRect.top - HANDLE_TOUCH_SLOP * 0.5f,
            right = startCenter.x + HANDLE_TOUCH_SLOP,
            bottom = startCenter.y + HANDLE_TOUCH_SLOP
        )
        val endZone = Rect(
            left = endCenter.x - HANDLE_TOUCH_SLOP,
            top = endRect.top - HANDLE_TOUCH_SLOP * 0.5f,
            right = endCenter.x + HANDLE_TOUCH_SLOP,
            bottom = endCenter.y + HANDLE_TOUCH_SLOP
        )
        val startHit = startZone.contains(position)
        val endHit = endZone.contains(position)
        return when {
            startHit && endHit -> {
                val dStart = (position - startCenter).getDistanceSquared()
                val dEnd = (position - endCenter).getDistanceSquared()
                if (dStart <= dEnd) START_HANDLE else END_HANDLE
            }
            startHit -> START_HANDLE
            endHit -> END_HANDLE
            else -> null
        }
    }

    private fun expand(rect: Rect, slop: Float): Rect = Rect(
        left = rect.left - slop,
        top = rect.top - slop,
        right = rect.right + slop,
        bottom = rect.bottom + slop
    )

    // ------------------------------------------------------------ 文本提取

    /**
     * 取出选区对应的**可见文本**（不含 Markdown 标记）。
     *
     * 跨块时按块拼接：首块取尾部、中间块取整块、末块取头部。
     * 块与块之间补一个换行 —— 直接用空串拼会让"标题+段落"变成一行，
     * 复制出去可读性很差。
     */
    fun selectedText(): String {
        val sel = selection?.normalized() ?: return ""
        val sb = StringBuilder()
        val relevant = blocks.filter { it.blockIndex in sel.start.block..sel.end.block }
            .sortedBy { it.blockIndex }
        relevant.forEachIndexed { index, snapshot ->
            val from = if (snapshot.blockIndex == sel.start.block) sel.start.offset else 0
            val to = if (snapshot.blockIndex == sel.end.block) sel.end.offset else snapshot.text.length
            val safeFrom = from.coerceIn(0, snapshot.text.length)
            val safeTo = to.coerceIn(safeFrom, snapshot.text.length)
            if (index > 0) sb.append('\n')
            sb.append(snapshot.text, safeFrom, safeTo)
        }
        return sb.toString()
    }

    /**
     * 选区对应到**源 Markdown** 的区间。
     *
     * 只在同一块内能给出单一连续区间；跨块时返回 null，
     * 由调用方决定怎么存（当前实现：跨块高亮拆成多条，见 ChatMessageView）。
     * 之所以不硬凑一个大区间：跨块区间的源范围会包含中间的标题井号、
     * 列表符号等标记，用它存高亮会在下次渲染时把不该亮的标记也亮上。
     */
    fun sourceRangeIn(blockIndex: Int, from: Int, to: Int): Pair<Int, Int>? {
        val snapshot = blocks.firstOrNull { it.blockIndex == blockIndex } ?: return null
        val a = snapshot.block.toSource(from)
        val b = snapshot.block.toSource(to)
        return if (a <= b) a to b else b to a
    }

    companion object {
        const val START_HANDLE = "start"
        const val END_HANDLE = "end"

        /** 命中判定的容差（像素）：见文件级 [HIT_SLOP_PX]。 */
        private const val SLOP_PX = HIT_SLOP_PX

        /**
         * 手柄可见半径（像素）：按 Android 官方与行业规范标准对齐为约 12~13dp（直径 25dp）。
         * 旧实现的 9px（仅 2~3dp）小如米粒，是触屏极难抓取的根因。
         */
        const val HANDLE_RADIUS = 36f

        /**
         * 手柄抓取热区半径（像素）：扩大至约 24~26dp（热区直径约 50dp），
         * 满足 Android 48dp 最小触控规范，手指一压即中。
         */
        private const val HANDLE_TOUCH_SLOP = 72f

        private const val SENTENCE_BREAKS = "。！？；!?;\n"

        /**
         * 找出 offset 所在句子的起止（左闭右开区间）。
         *
         * 中英文标点一起判；一段里找不到任何标点时返回整段，
         * 这与"长按选一句"的直觉一致（没有句读的整块本身就是一句）。
         */
        fun sentenceBounds(text: String, offset: Int): Pair<Int, Int> {
            if (text.isEmpty()) return 0 to 0
            val safe = offset.coerceIn(0, text.length)
            var start = 0
            var i = safe - 1
            while (i >= 0) {
                if (SENTENCE_BREAKS.indexOf(text[i]) >= 0) {
                    start = i + 1
                    break
                }
                i--
            }
            var end = text.length
            var j = safe
            while (j < text.length) {
                if (SENTENCE_BREAKS.indexOf(text[j]) >= 0) {
                    end = j + 1
                    break
                }
                j++
            }
            // 跳过句首空白，避免选中范围以空格开头
            while (start < end && text[start] == ' ') start++
            return start to end
        }

        /**
         * 找出 offset 所在词或词组的起止（精准选词逻辑）。
         *
         * 西文/数字取整串；CJK 交给系统 ICU 分词器按**词**切。
         *
         * ⚠️ 旧实现对汉字是"落点起固定取 2 个字"，只是经验近似，错得很明显：
         * 在「语法新解」的「法」上长按会得到「法新」—— 既不是词，还吃掉了下一个词的头。
         * 而读外文文献时最常用的动作恰恰是"按住一个词、查它"，切错边界等于功能不可用。
         * ICU 的 `BreakIterator` 自带中日韩词典，切出来是真正的词（「语法」「新解」），
         * 这也是系统文本选择器与微信/QQ 用的同一套分词。
         */
        fun wordBounds(text: String, offset: Int): Pair<Int, Int> {
            if (text.isEmpty()) return 0 to 0
            val safe = offset.coerceIn(0, (text.length - 1).coerceAtLeast(0))
            if (safe >= text.length) return 0 to text.length
            val c = text[safe]
            if (c.isWhitespace()) return safe to (safe + 1).coerceAtMost(text.length)

            if (c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '_') {
                var start = safe
                while (start > 0 && (text[start - 1].let { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '_' })) {
                    start--
                }
                var end = safe + 1
                while (end < text.length && (text[end].let { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '_' })) {
                    end++
                }
                return start to end
            }

            // CJK：按词典切词。分词失败（极少数异常）时退回固定取字，
            // 保证"宁可粒度粗一点"也不出现没有选区。
            val segmented = runCatching {
                val breaker = android.icu.text.BreakIterator.getWordInstance(java.util.Locale.CHINESE)
                breaker.setText(text)
                val start = breaker.preceding(safe + 1)
                val end = breaker.next()
                if (start in 0 until end && end <= text.length) start to end else null
            }.getOrNull()
            if (segmented != null) return segmented

            val start = safe
            val end = (safe + 2).coerceAtMost(text.length)
            return start to end
        }
    }
}
