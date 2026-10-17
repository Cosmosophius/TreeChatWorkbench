package com.treechat.workbench.ui.selection

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.treechat.workbench.data.prefs.InputMode
import com.treechat.workbench.ui.markdown.IncrementalMarkdownParser
import com.treechat.workbench.ui.selection.projectSourceRanges
import com.treechat.workbench.ui.markdown.MdColors
import com.treechat.workbench.ui.markdown.MdRichBlock
import com.treechat.workbench.ui.markdown.rememberMdColors
import com.treechat.workbench.ui.markdown.parseTableCellSpans
import com.treechat.workbench.ui.markdown.tableCellDisplayText
import com.treechat.workbench.ui.markdown.toAnnotated

/**
 * 一个持久高亮在**显示维度**上的范围（左闭右开）。
 *
 * [blockIndex] 是**必需**的，不是可选信息：投影出来的偏移是"块内局部下标"
 * （见 MdRichBlock.toDisplay），而不是整条消息的全局下标。
 * 早先这里不带块号，整份区间列表被原样发给每一个块，于是
 * "在第 5 段高亮了第 3~7 个字"会连带把其余每一段的第 3~7 个字一起点亮 ——
 * 用户看到的就是"高亮污染了很多多余文本"。
 */
data class HighlightRange(val blockIndex: Int, val start: Int, val endExclusive: Int) {
    val asIntRange: IntRange get() = start until endExclusive
}

/**
 * 可选中的 Markdown 正文。
 *
 * ## 为什么不用 SelectionContainer
 *
 * 用户明确要求"长按文本强制改为不调用系统选取，改为可以自行选择文本
 * 并针对其提问、复制、高亮"。系统的 SelectionContainer 只提供
 * 系统托管的复制/全选，既插不进自定义动作，也给不出
 * "选中的字对应源 Markdown 的哪个区间"（高亮要按源偏移存库）。
 * 因此这里彻底不挂 SelectionContainer，选区自己管。
 *
 * ## 取舍：为什么保留块级 Text，而不是合并成一个 Text
 *
 * 合并成单个 Text 会让偏移映射简单很多，但会丢掉现有排版：
 * 代码块的横向滚动与等宽字体、表格的单元格对齐、列表缩进，
 * 都依赖"每块一个组件"。合并后这些要么重写要么退化。
 * 所以保留块结构，代价是命中检测要跨多个 TextLayoutResult ——
 * 该复杂度集中在 MessageSelectionController，有界且可读。
 *
 * ## 坐标系约定（这段是整块最容易出错的地方，务必对齐）
 *
 * 命中检测与块位置**都在最外层 Box 的坐标系**里：
 *
 * - 手势回调给的是"最外层 Box 本地坐标"，直接可用；
 * - 每个块的位置用 `positionInParent()` 取，而它们的父节点就是那个 Box
 *   （内容列不引入偏移，内边距由最外层 Box 自己的 padding 承担）。
 *
 * 之所以把内边距放在最外层 Box 上而不是内容列上：若内容列带 padding，
 * 块坐标就落在"内容坐标系"里，而手势落在"Box 坐标系"里，两者相差一个
 * padding，需要在每次命中检测里补偿。补偿一旦算错（例如这次最初写成
 * 减去 padding，方向反了），症状是整条消息的选区**整体偏上或偏下几像素**，
 * 单行文本上就表现为"点这一行、选中了上一行"。让两个坐标系重合，
 * 这类错误在结构上就不可能发生。
 *
 * @param baseFontSize 正文字号
 * @param plain 是否按纯文本渲染（用户自己输入的消息）。
 *   用户随手打的 `*`、`#`、反引号不该被当成 Markdown 语法 ——
 *   旧实现就是这个行为，必须保持：否则用户输入 `2*3*4` 会被渲染成斜体。
 */
@Composable
fun SelectableMarkdownText(
    markdown: String,
    controller: MessageSelectionController,
    modifier: Modifier = Modifier,
    plain: Boolean = false,
    contentPadding: PaddingValues = PaddingValues(horizontal = 14.dp, vertical = 11.dp),
    highlights: List<HighlightRange> = emptyList(),
    selectionColor: Color = Color.Unspecified,
    /**
     * 是否**始终**显示水滴手柄（默认 false，即只在触屏路径显示）。
     *
     * 为什么要有这个开关：默认策略是"桌面级指针不画手柄"（浏览器惯例），
     * 但阅读器里用户明确要的是**能选的字＋能拖的手柄**同时在 ——
     * 那里既要鼠标按下即拖划选，又要选完能抓手柄微调边界。
     * 与其在阅读器里绕开整个组件，不如给组件一个显式开关：
     * 对话界面不动（默认值），阅读器传 true。
     */
    alwaysShowHandles: Boolean = false,
    highlightColor: Color = Color.Unspecified,
    baseFontSize: TextUnit = MaterialTheme.typography.bodyMedium.fontSize,
    onSelectionChanged: (Boolean) -> Unit = {},
    /**
     * 手势诊断回调（排查用）。
     *
     * 把"按下 / 长按是否通过 / 命中结果 / 是否选中"这几步的判定结果
     * 交给上层显示。默认空实现，不影响正常路径。
     */
    onGestureDiagnostic: (String) -> Unit = {},
    /**
     * 当前会话中已分叉的同文本超链接目标列表。
     *
     * 命中的文本会自动带有超链接下划线与强调色，轻点即可调出对应分支小窗。
     */
    quoteLinkTargets: List<QuoteLinkTarget> = emptyList(),
    /** 点击同文本超链接时的回调，参数为目标分支节点。 */
    onQuoteClick: (com.treechat.workbench.data.model.ConversationNode) -> Unit = {},
    /**
     * 多色源标记（**整段 markdown 源坐标**）。
     *
     * 生词（玫红）/ 页内查找（琥珀）/ 当前查找命中（深琥珀）共用一条通道；
     * 组件按色分组投影到块级再叠样式（HighlightProjection.projectSourceMarks）。
     */
    sourceMarks: List<com.treechat.workbench.ui.selection.SourceMark> = emptyList(),
    /**
     * 文本选择方案（设置 → 文本选择）。
     *
     * 默认 [SelectionScheme.TOUCH_LONG_PRESS]，即项目原有行为。
     * 见 [SelectionScheme] 的类文档：触屏与鼠标期望的手势判据不同，
     * 用单一方案无法兼顾，因此做成显式可切换。
     */
    selectionScheme: SelectionScheme = SelectionScheme.TOUCH_LONG_PRESS,
    /**
     * 粗体呈现偏好（颜色 / 底色 / 字形）。
     *
     * 为什么由调用方注入：这是**应用级用户偏好**（存在 AppSettings），
     * 而 SelectableMarkdown 是纯 UI 组件、刻意不依赖 ViewModel。
     * 保持这个边界，组件在任何宿主里都可直接复用。
     *
     * 全部字段有"原样"的默认值，因此不传时逐字等同于改动前。
     */
    boldColorStyle: com.treechat.workbench.data.prefs.BoldColorStyle =
        com.treechat.workbench.data.prefs.BoldColorStyle.DEFAULT,
    boldHighlightColor: com.treechat.workbench.data.prefs.BoldHighlightColor =
        com.treechat.workbench.data.prefs.BoldHighlightColor.NONE,
    boldFontStyle: com.treechat.workbench.data.prefs.BoldFontStyle =
        com.treechat.workbench.data.prefs.BoldFontStyle.DEFAULT,
    /**
     * 全局输入设备模式（设置 → 外观与偏好 → 键鼠 / 触控）。
     *
     * - [InputMode.AUTO]：指针是鼠标/触控板即走桌面级拖选（按下即拖、双击选句），
     *   触屏仍走 [selectionScheme] 的长按；
     * - [InputMode.FORCE_DESKTOP]：一律按桌面级处理（部分 ROM 把鼠标错报成 Touch 时用）；
     * - [InputMode.FORCE_TOUCH]：一律长按，忽略指针类型。
     */
    inputMode: InputMode = InputMode.AUTO,
    onAsk: ((String) -> Unit)? = null,
    onCopy: ((String) -> Unit)? = null,
    onHighlight: (() -> Unit)? = null,
    onUndoHighlight: (() -> Unit)? = null,
    highlightActive: Boolean = false,
    onEtymology: ((String) -> Unit)? = null,
    /**
     * 命中本地权威词库的概念名集合（来自 `[[概念名]]` 的概念链接判定）。
     *
     * 判定用集合而不是"回调里回问"：本组件每个块每次重组都要判断一批概念名，
     * 集合命中是 O(1)，避免把渲染期变成 N 次外部查询。
     */
    knownConcepts: Set<String> = emptySet(),
    /**
     * 点了正文里的概念链接（`[[概念名]]`）。
     *
     * @param term 概念名（已去掉双方括号）
     * @param known 是否命中本地词库
     *   命中 → 调用方打开词典/词源；未命中 → 调用方触发一次轻量追问。
     */
) {
    val colors = rememberMdColors()
    // 粗体的强调色取主题 primary：跟随明暗主题自动适配，用户换主题不用重配。
    val boldAccent = MaterialTheme.colorScheme.primary
    // 与高亮投影共用同一份解析结果（blocksForMessage），
    // 避免两处解析参数不一致导致"高亮位置与文字对不上"
    // 流式渲染的性能关键：**只解析没写完的那一小块**。
    //
    // 旧实现是 remember(markdown) { 解析整篇 }，文本每 16ms 变一次就整篇重解析，
    // 长回答下每帧代价随长度线性增长（模拟器实测每帧 500ms ≈ 2 FPS）。
    // 增量解析器把已完结的块缓存住按引用复用，单帧代价只与"尾巴"有关。
    // 用户消息（plain）不流式，也不需要富文本，保持单块纯文本路径。
    val incrementalParser = remember(plain) {
        if (plain) null else IncrementalMarkdownParser()
    }
    val blocks = remember(markdown, plain) {
        incrementalParser?.blocks(markdown) ?: blocksForMessage(markdown, plain)
    }
    val currentOnSelectionChanged by rememberUpdatedState(onSelectionChanged)
    val currentOnDiagnostic by rememberUpdatedState(onGestureDiagnostic)
    val currentOnQuoteClick by rememberUpdatedState(onQuoteClick)

    // 多色标记：源坐标按色分组投影成块级（与高亮同规则），逐块分发
    val projectedMarks = remember(blocks, sourceMarks) {
        projectSourceMarks(blocks, sourceMarks)
    }

    // 为每个块预计算命中的同文本超链接列表
    val blockQuoteLinks = remember(blocks, quoteLinkTargets) {
        blocks.map { block ->
            QuoteLinkMatcher.findLinks(block.displayText, quoteLinkTargets)
        }
    }

    // 为每个块预计算概念链接（`[[概念名]]`）区间。区间直接从行内片段里取，
    // 不靠"在 displayText 里再搜一遍名字" —— 名字可能在别处也出现，
    // 那样会把非概念的普通同名文本也点亮。

    /**
     * 记录一次诊断：同时写进控制器状态（供本组件的 logcat）与上层显示。
     * 用一个局部函数收口，避免到处重复写两行。
     */
    fun diagnose(message: String) {
        controller.noteGesture(message)
        currentOnDiagnostic(message)
    }

    // 位置表与布局表都由控制器持有（原因见 MessageSelectionController 的字段说明）：
    // 组件实例被重建时这两张表不会丢，避免"位置表有、布局表空"导致
    // 注册逻辑永远凑不齐快照（这是选字反复失效的结构性根因）。
    val origins = controller.origins
    val layouts = controller.layouts

    // 同步注册块快照：只要某一块的 origin 和 layout 都已经就绪，立即构建快照写入控制器。
    // 绝不依赖 LaunchedEffect(origins.size) 那种必须等到数量变化的脆弱机制！
    fun registerBlockIfReady(index: Int) {
        val block = blocks.getOrNull(index) ?: return

        // ============ 表格：完全独立的登记路径，必须放在**最前** ============
        //
        // ⚠️⚠️ 这个位置是**必须**的，不是随手排的。
        //
        // 表格没有"整块的一个 Text"：SelectableBlock 的 TABLE 分支只把
        // onCellOrigin / onCellLayout 往下传，**从来没有传 onOrigin / onLayout**
        // （其它所有块都传了）。因此对表格来说：
        //     origins[index] ≡ null，layouts[index] ≡ null
        //
        // 旧代码把 `val origin = origins[index] ?: return` 放在最前面，
        // 于是表格一进来就在那一行 return 掉了 —— 下面那段表格登记逻辑
        // **一次都没有执行过**，是不可达的死代码。后果是：
        //     controller.blocks 里永远不存在表格块
        //     ⇒ hitTest 永远匹配不到任何一格
        //     ⇒ 循环落到"最近块"的退化路径，
        //       点到表格文字时选中的是**旁边那个段落**，或干脆什么都没选中。
        // 这就是"表格文本依旧无法选中 / 有概率选不中正常位置"的根因。
        if (block.kind == MdRichBlock.Kind.TABLE) {
            val texts = tableCellTexts(block)
            if (texts.isEmpty()) return
            controller.initTableCells(index, texts.size)
            val cellOriginList = controller.cellOrigins[index] ?: return
            val cellLayoutList = controller.cellLayouts[index] ?: return
            if (cellOriginList.size != texts.size || cellLayoutList.size != texts.size) return
            // 少一格就先不注册 —— 注册残缺快照会让命中算到错位的偏移上，
            // 表现成"能选中但选出来的不是点到的字"，比选不中更难查。
            if (cellOriginList.any { it == null } || cellLayoutList.any { it == null }) return

            val starts = tableCellTextStarts(texts)
            val cells = texts.indices.map { i ->
                val cellOrigin = cellOriginList[i] ?: return
                val cellLayout = cellLayoutList[i] ?: return
                CellLayoutSnapshot(
                    originInRoot = cellOrigin,
                    layout = cellLayout,
                    textStart = starts[i],
                    textLength = texts[i].length
                )
            }
            // ⚠️ 跳过注册的判据必须覆盖**全部格子**的坐标与排版结果。
            //
            // 曾经只比 `cells.first().originInRoot`，那是错的，而且是**必然会错**：
            // 表格是"第一格钉在左上、后面几格依次向右"的结构，窗口变宽、改字号、
            // 列宽随内容变化时，**第一格原地不动、后面几格整体右移**。
            // 只比首格就会认定"快照仍有效"直接 return，控制器里留下的是后面几格的
            // **旧坐标** —— 命中检测于是算出错位的偏移，表现是表格里从第二列开始
            // "选出来的不是你点到的那些字"，或干脆选不中。
            //
            // 这跟下面正文块那条教训是同一类错误（见 291 行起的长注释）：
            // 判据必须同时包含**坐标**与**排版结果**，只比坐标（更别说只比其中一个格子的
            // 坐标）会留下致命漏洞。表格没有单一 origin/layout，所以逐个格子比：
            // 只要有一个格子的坐标或 layout 身份变了，就整张表重建。
            val tableExisting = controller.snapshotAt(index)
            if (tableExisting != null && tableExisting.cells.size == cells.size) {
                val unchanged = tableExisting.cells.indices.all { i ->
                    val old = tableExisting.cells[i]
                    val new = cells[i]
                    old.originInRoot == new.originInRoot && old.layout === new.layout
                }
                if (unchanged) return
            }

            controller.upsertBlock(
                BlockLayoutSnapshot(
                    blockIndex = index,
                    block = block,
                    // 单一 origin/layout 对表格只是占位，所有判定都走 cells
                    originInRoot = cells.first().originInRoot,
                    layout = cells.first().layout,
                    cells = cells
                )
            )
            return
        }

        // ============ 其它块：一个 Text 对应一块 ============
        val origin = origins[index] ?: return
        val layout = layouts[index] ?: return

        // ⚠️ 跳过注册的判据是「快照里记的坐标仍等于当前坐标」。
        //
        // 这里**刻意不**改用"块对象身份是否相同"来做判据。历史教训：
        // 曾经用 `registeredBlocks[index] === block` 跳过重建（理由是按引用复用的块
        // 位置没变，重建只是白花 N 次 localPositionOf）。但块对象可以被增量解析器
        // 与 compose 复用，而它**在屏幕上的位置已经变了** —— 列表滚动、上一条消息
        // 增高、窗口尺寸变化都会移动它。此时直接 return，控制器里留下的就是**旧坐标**，
        // 命中检测整体错位，表现正是：
        //     "选词突然全线失效（点哪儿都命中不到任何一行），
        //      点一下别的 tab 触发整棵树重组，才自己恢复。"
        // 而且鼠标与触屏**都会**中招，因为问题在坐标快照、不在手势。
        //
        // 另一个必须避免的坑：不能靠一个"已注册过哪些块"的组件级 map 来判断。
        // 那种 map 是 `remember`（条目重建即清空），而 origins/layouts 挂在控制器上
        // （跨条目重建存活），两者生命周期不同步 —— 一旦不同步，就会出现
        // "map 说注册过了、实际坐标表是空的"这种自相矛盾的状态。
        //
        // 现在的判据只依赖一个事实：**当前 origin 与快照里的 origin 是否相等**。
        // 相等 ⇒ 快照仍然有效，跳过（保住原优化）；不等 ⇒ 重建。
        // 代价只是两次 Offset 相等比较，远低于重新查一次 localPositionOf。
        // ⚠️ 判据必须**同时**包含坐标与排版结果，只比坐标会留下一个致命漏洞：
        //
        // 窗口放大/分屏/旋转/改字号时，文字会**重新排版（宽度变了）**，而这个块的
        // 左上角坐标往往没动。此时若只看 origin 就判定"快照仍然有效"，快照里攥着的
        // 还是**旧的窄排版**。而 hitTest 正是用 `layout.size.width` 判断"这个点是否
        // 属于本块"（见 SelectionModel.hitTest 的块级边界检查）—— 超过旧宽度的落点
        // 会被判成"不在任何块内"，返回 null，于是：
        //     · 拖选停在某个 x 上，表现为"选到某个字就再也选不过去"；
        //     · 单行段落最明显（一行铺满宽度，边界就是那行字的中段）；
        //     · **小窗最严重**（窗口尺寸变化最频繁：生长动画、贴靠、手动缩放）。
        // 这份"旧布局"还可能把命中算到错字符上（长宽比不对时行列都错）。
        //
        // 所以 layout 也必须比：onTextLayout 在文本或约束变化时都会给出**新的**
        // TextLayoutResult，比引用即可捕获"重新排版过"这件事，开销只是一次引用比较。
        val existing = controller.snapshotAt(index)
        if (existing != null &&
            existing.originInRoot == origin &&
            existing.layout === layout
        ) {
            return
        }

        controller.upsertBlock(
            BlockLayoutSnapshot(
                blockIndex = index,
                block = block,
                originInRoot = origin,
                layout = layout
            )
        )
    }

    // 内容变化时更新 renderedMarkdown 并重新为已有布局做注册
    //
    // ⚠️ 这里同时负责**清理上一次组合会话留下的陈旧快照**。
    //
    // 控制器是按消息 id 缓存的（ChatMessageView 的 selectionControllers），
    // 因此它会**跨 LazyColumn 回收、跨条目离屏**存活：同一条消息滚出视野再滚回来，
    // 拿到的是同一个控制器，里面还留着上一轮布局的块坐标。
    //
    // 旧的判断条件是 `previous != null && ...`——它有两个窟窿：
    //   1. **首次组合**（previous == null）时**从不清**，于是复用的控制器带着
    //      上轮的块快照进入新布局，坐标早已失效；
    //   2. 内容"没变"时也不清，可是**坐标可能已经变了**（列表滚动、输入框增高、
    //      窗口尺寸变化都会移动块的位置），而块快照只在 onGloballyPositioned /
    //      onTextLayout 回调里刷新——那些回调在"纯位置变化"时不一定重新派发。
    // 两者叠加的结果就是：选中突然全线失效（点哪儿都命中不到任何一行），
    // 点一下别的 tab 触发整棵树重组，才把快照重建回来。
    //
    // 现在的语义是"**每次进入组合都确认一遍快照归属**"：只有当
    // renderedMarkdown 与本次 markdown 完全一致（即这份快照确实是本次布局产生的）
    // 才跳过清理；其余一律清空重建。
    LaunchedEffect(markdown) {
        val previous = controller.renderedMarkdown
        // 「只追加」与「换内容」必须分开处理：
        //  - 换内容（重新生成、切换消息）：全部清空重建，语义与旧实现一致；
        //  - 只追加（流式增长）：**绝不能清**。清了有两个后果：一是用户正在生成的
        //    消息上选区每 16ms 被清一次（选不中、气泡闪没），二是所有块都要重新
        //    查布局并重建快照 —— 这正是"越写越卡"的另一半原因。
        val appendOnly = previous != null &&
            markdown.length > previous.length &&
            markdown.startsWith(previous)
        if (previous != markdown && !appendOnly) {
            controller.clearBlocks()
            controller.clear()
            origins.clear()
            layouts.clear()
            // 表格的逐格几何同样是"这份内容"的，换内容必须一起清，
            // 否则上一份表格的格数会和新内容对不上，注册永远凑不齐。
            controller.clearCellGeometry()
            currentOnSelectionChanged(false)
        }
        controller.renderedMarkdown = markdown
        blocks.indices.forEach { registerBlockIfReady(it) }
    }

    // 块解析结果更新时，重新同步快照
    LaunchedEffect(blocks) {
        blocks.indices.forEach { registerBlockIfReady(it) }
    }

    // 鼠标/触控板接入时给出 Mac/Windows 原生工字竖条（I-Beam）文本光标反馈，
    // 触屏手指操作时无光标、零副作用；只要插上鼠标或触控板悬停在文本上即自动变竖条。
    //
    // ⚠️ `overrideDescendants = true` 是**必须**的，否则竖条会"经常失效"。
    //
    // 原因（对照 Compose UI 1.6.8 的实现）：`pointerHoverIcon` 的光标只在
    // **PointerEventType.Enter / Exit** 这两个事件上被求值（见
    // PointerHoverIconModifierNode.onPointerEvent 的字节码），并且默认
    // `overrideDescendants = false` 时，**子节点设的光标优先级更高**。
    // 于是有三种很容易踩中的失效路径：
    //   1. 任何子节点自己设了光标（表格行的 horizontalScroll、
    //      代码块的横向滚动、链接/按钮角标……），悬停到子节点上就换成它的光标；
    //   2. 指针**已经停在文字上**时组件被重建/重组（切换会话、流式刷新、
    //      AnimatedContent 换页），新节点不会收到 Enter（指针没"进入"过它），
    //      于是竖条一直不出现 —— 这解释了"点一下别的 tab 再切回来就好了"；
    //   3. 同一处反复进出时事件被别的层吃掉。
    // 置 true 后本层的光标覆盖所有后代：文本区域**永远**是竖条，
    // 不管底下是表格、代码块还是链接。
    //
    // 为什么用 A 方案（覆盖后代）而不是给每个子节点分别补光标：
    // 子节点数量与种类会继续增加（以后再加图表、公式……），
    // 逐个补必然漏；而"文本区一律竖条"本就是唯一正确的语义。
    val hoverIconModifier = Modifier
        .pointerHoverIcon(
            icon = PointerIcon.Text,
            overrideDescendants = true
        )
        // ⚠️ 再加一道**命令式**兜底：Compose 的 hover 光标只在 Enter/Exit 上求值，
        // 节点 detach 时会 setIcon(null) 把光标打回箭头，之后没有任何事件会补回来
        //（见 assureTextCursor 顶部对字节码的说明）。这一层就是那个"补回来"。
        .assureTextCursor()
    // ⚠️ 曾在此断言"命令式设置光标在本项目依赖的 Compose 版本里走不通"，因为
    // `PointerIconService` 与 `LocalPointerIconService` 是 file-internal
    // （外部引用会报 "it is internal in file"）。**那个结论只对了一半**：
    // 内部服务确实拿不到，但等效效果可以通过**公开**的 `View.setPointerIcon` 达到 ——
    // Compose 自己最终也是走这条路。现在由 hoverIconModifier 里的
    // `assureTextCursor()` 承担那一层兜底（成因与边界见 TextCursorGuard.kt 的说明）。

    /**
     * 本次交互是否由**桌面级指针**（鼠标/触控板，或强制键鼠模式）驱动。
     *
     * 用途只有一个但很关键：**决定要不要画水滴手柄**。
     * 手柄是给手指用的（指尖粗、需要抓住一个明确的点去拨）；键鼠下它是纯干扰，
     * 而且浏览器里的桌面级选词根本没有手柄 —— 按下拖动、双击选词就够了。
     * 手势里一旦走桌面路径就把它置真，切回触屏长按路径时置假。
     */
    // ⚠️ 必须把 inputMode 作为 remember 的 key：
    // 无 key 时这个初值只在首次组合时算一遍，之后用户把档位从
    // "强制键鼠"改成"强制触屏"，desktopPointerActive 会**一直停在 true**，
    // 结果就是触屏档位下水滴手柄被永久藏掉（"切回触屏却摸不到手柄"）。
    // 换成 key 后切档位会重新求初值，再叠加手势循环里按次修正，两条路都收敛。
    var desktopPointerActive by remember(inputMode) {
        mutableStateOf(
            inputMode == InputMode.FORCE_DESKTOP || inputMode == InputMode.FORCE_TRACKPAD
        )
    }

    // 桌面级连击判定（浏览器行为：双击选词、三击选句）
    val lastDownAt = remember { mutableLongStateOf(0L) }
    val lastDownPos = remember { mutableStateOf<Offset?>(null) }
    val clickCount = remember { mutableIntStateOf(0) }

    var rootCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val blockCoords = remember { mutableMapOf<Int, LayoutCoordinates>() }

    /**
     * 用手里的**活坐标**（`LayoutCoordinates`）就地刷新所有块的 origin。
     *
     * ## 为什么必须有这一步
     *
     * `origins` 是一份**快照**，只在 `onGloballyPositioned` 回调里更新。而这个回调
     * 只在"节点位置发生变化"时派发；一旦因为列表复用、流式重组、切 Tab 等把节点
     * 换了新实例，或者位置变化没触发回调，快照就会**停在旧值**上。旧坐标 + 新的
     * 屏幕位置 = 命中检测整体错位，表现就是你反复遇到的：
     *
     *   · 鼠标悬停在正文上，工字光标（I-Beam）无故变回箭头；
     *   · 紧接着**选不中词**（点哪儿都命中不到任何一行）；
     *   · 切到别的 Tab 再切回来又好 —— 因为整棵树重组，快照被重建。
     *
     * 光标与选词同时失效不是巧合：两者都建立在"框架认为指针仍在文本节点内"这个
     * 判断上。节点被换掉后 Enter 事件不会再补发，`pointerHoverIcon` 于是回退到
     * 祖先/默认光标（箭头），而已缓存的坐标也不再刷新。所以核心不是补光标，
     * 而是**每次手势开始前把坐标重新取一遍**。
     *
     * `LayoutCoordinates` 是绑定在布局节点上的活句柄，`localPositionOf` 随时调用
     * 都会返回**当前**位置，不受回调是否派发过影响。代价是一次父链遍历（每块一次），
     * 只在按下时执行，不在每帧执行。
     */
    fun syncLiveGeometry() {
        val root = rootCoordinates?.takeIf { it.isAttached } ?: return
        var moved = false
        blocks.indices.forEach { idx ->
            val child = blockCoords[idx]?.takeIf { it.isAttached } ?: return@forEach
            val live = root.localPositionOf(child, Offset.Zero)
            if (origins[idx] != live) {
                origins[idx] = live
                moved = true
            }
        }
        // 只要坐标动过、或注册数还差着，就重新走一遍注册（内部自带去重判断）。
        if (moved || controller.registeredBlockCount < blocks.size) {
            blocks.indices.forEach { idx -> registerBlockIfReady(idx) }
        }
    }

    fun refreshOriginAndRegister(index: Int) {
        val root = rootCoordinates?.takeIf { it.isAttached }
        val child = blockCoords[index]?.takeIf { it.isAttached }
        if (root != null && child != null) {
            origins[index] = root.localPositionOf(child, Offset.Zero)
        }
        registerBlockIfReady(index)
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(contentPadding)
            .then(hoverIconModifier)
            .onGloballyPositioned {
                rootCoordinates = it
                blocks.indices.forEach { idx -> refreshOriginAndRegister(idx) }
            }
            // ⚠️ `inputMode` **必须**是 key（这是"强制触屏失效"的真正根因）。
            //
            // pointerInput 的 lambda 会被 Compose 记住；只有当**某个 key 变了**
            // 才会取消旧协程、用新闭包重启手势循环。旧代码只挂了
            // (markdown, selectionScheme)，于是：
            //   在设置里把输入设备从"强制键鼠"改成"强制触屏"——
            //   markdown 没变、selectionScheme 也没变 → 手势循环**根本不会重启**，
            //   里面捕获的 inputMode 还是启动那一刻的 FORCE_DESKTOP，
            //   于是"切到强制触屏了，还是滑动取词"。
            // 这同时解释了为什么换个**选词方案**就好了：方案是 key，能触发重启。
            // 也解释了它的偶发性：是否失效取决于切换时闭包是否恰好被重建。
            .pointerInput(markdown, selectionScheme, inputMode) {
                val viewConfig = viewConfiguration
                // "抖动点击"容差：位移小于它就不算拖选，按一次点击处理。
                //
                // 为什么必须有：判"开始拖选"用的是 viewConfig.touchSlop（约 8dp），
                // 而触控板/鼠标**按下时几乎不可能零位移**，轻轻一点就跨过 slop。
                // 于是这次点击被当成"拖了一小段" → 走拖选收尾 → 链接连判都不判，
                // 表现就是"有时候超链接点不开，多点几次才行"。
                // 8dp 到 12dp 之间这段位移，用户意图一定是"点一下"，不是"选几个字"。
                val clickTolerancePx = 12.dp.toPx()
                awaitPointerEventScope {
                    // 诊断：进入手势检测器即记录一次。
                    diagnose("手势循环已启动（块=${controller.registeredBlockCount}）")
                    var lastClickUptime = 0L
                    while (true) {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        // ⚠️ 每次手势开始前用活坐标重取一遍位置（见 syncLiveGeometry 的说明）。
                        // 这一步就是"光标变箭头 + 选不中词"的根治点：不依赖任何回调是否派发过，
                        // 按下瞬间的位置一定是当前真实位置。
                        syncLiveGeometry()
                        val downPos = down.position
                        diagnose(
                            "按下 @(${downPos.x.toInt()},${downPos.y.toInt()}) 块=${controller.registeredBlockCount}"
                        )

                        // ⚠️ 核心修复一：手柄拖拽绝对优先 + 锚点相对位移补偿（grabOffset）！
                        // 只要当前已存在选区、且触点落在手柄热区内，立即启动手柄拖动。
                        // 记录按下瞬间手指与字符行中心锚点的差值 grabOffset，移动时用
                        // change.position - grabOffset 探测目标字符，既消除水滴悬挂在行底的高度差，
                        // 又保证抓取手柄边缘时选区零跳变、1:1 跟手扩展！
                        //
                        // ⚠️ 但**只有在手柄真的画出来时**才允许它抢按下事件！
                        //
                        // 桌面级路径（鼠标 / 触控板 / 强制键鼠）不画手柄（见 desktopPointerActive），
                        // 而手柄的热区相当大（SelectionModel.HANDLE_TOUCH_SLOP = 72px，
                        // 竖向上从行顶一直盖到水滴球下方）。
                        // 于是"看不见的手柄"会**静默吞掉**落在它附近的点击：
                        // 用户点选区旁的一个超链接，按下却落在隐藏手柄的热区里，
                        // 这一下就变成了"拖动手柄"，链接连判都不判 ——
                        // 这正是"有时候超链接点不开"的一大成因（选区还在时就更容易撞上）。
                        // 手柄既然没画出来，它就不该有交互热区。
                        val handleOnDown = if (controller.hasSelection &&
                            (!desktopPointerActive || alwaysShowHandles)
                        ) {
                            controller.handleAt(downPos)
                        } else {
                            null
                        }
                        if (handleOnDown != null) {
                            down.consume()
                            controller.beginHandleDrag(handleOnDown)
                            val initialCharAnchor = controller.charAnchorFor(handleOnDown) ?: downPos
                            val grabOffset = downPos - initialCharAnchor
                            diagnose("命中手柄 $handleOnDown，开始调整选区")
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Main)
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) break
                                val probePos = change.position - grabOffset
                                val hit = controller.hitTest(probePos)
                                if (hit != null) {
                                    controller.moveHandle(handleOnDown, hit)
                                    currentOnSelectionChanged(controller.hasSelection)
                                }
                                change.consume()
                            }
                            controller.endHandleDrag()
                            diagnose("手柄拖拽完成，当前选区=${controller.selectedText().length}字")
                            continue
                        }

                        // ============ 桌面式路径（鼠标拖选 / 双击选句） ============
                        //
                        // 触发条件（二选一）：
                        //   · 方案「鼠标/触控板增强」且这次按压来自**鼠标**
                        //     （触控板在 Android 上同样报成 PointerType.Mouse）；
                        //   · 方案「双击选句（全平台）」。
                        //
                        // 为什么必须绕开下面的 awaitLongPressOrCancellation：
                        //   那个 API 要求指针**按住约 500ms 且几乎不移动**。
                        //   而鼠标/触控板的自然操作是"按下 → 直接拖 → 松开"，
                        //   中途的位移会取消长按判定 → 永远产生不了选区。
                        //   这是结构性原因，调阈值无法解决。
                        val isMousePointer = down.type == PointerType.Mouse
                        val isFreeSwipe = selectionScheme == SelectionScheme.FREE_SWIPE
                        val isDoubleTap = selectionScheme == SelectionScheme.DOUBLE_TAP
                        // 用户在设置里显式声明"我在用触控板"。
                        // 只有这一档才会启用"纵向滑动 = 滚动"的保护规则 ——
                        // 因为触控板的双指滚动与鼠标拖动在事件类型上无法区分
                        // （系统都报成 PointerType.Mouse + 带位移），
                        // 自动判断一定会在某些设备上判错，只能由用户声明。
                        val isTrackpad = inputMode == InputMode.FORCE_TRACKPAD ||
                            inputMode == InputMode.FORCE_TRACKPAD_BUBBLE
                        // 触控板 + 气泡手柄：桌面级即时拖选，但**保留水滴手柄/选区气泡**。
                        // 这是 [InputMode.FORCE_TRACKPAD_BUBBLE] 的唯一特征 ——
                        // 其余路径与 FORCE_TRACKPAD 完全一致。
                        val isBubbleTrackpad = inputMode == InputMode.FORCE_TRACKPAD_BUBBLE
                        // 桌面级拖选的四档判定（见 inputMode 的说明）：
                        //   强制键鼠   → 一律桌面级；
                        //   强制触控板 → 桌面级 + 纵向滑动视为滚动；
                        //   触控板气泡 → 桌面级 + 手柄气泡保留 + 纵向滑动视为滚动；
                        //   强制触控   → 一律长按；
                        //   自动       → 指针确为鼠标/触控板时桌面级（这是"插着鼠标却还是长按"的修复）。
                        val isMouseDesktop = when (inputMode) {
                            InputMode.FORCE_TOUCH -> false
                            InputMode.FORCE_DESKTOP -> true
                            InputMode.FORCE_TRACKPAD -> true
                            InputMode.FORCE_TRACKPAD_BUBBLE -> true
                            InputMode.AUTO -> isMousePointer &&
                                selectionScheme != SelectionScheme.FREE_SWIPE &&
                                selectionScheme != SelectionScheme.DOUBLE_TAP
                        }

                        // ⚠️ 优先级（这是"强制档位不生效"的修复点）：
                        //   **输入设备模式（强制档位）> 选词方案**
                        //
                        // 旧写法是 `useFastPath = isFreeSwipe || isDoubleTap || isMouseDesktop`，
                        // 于是只要方案是 FREE_SWIPE / DOUBLE_TAP，`useFastPath` 就恒为 true ——
                        // 用户把输入设备切成"强制触屏"也**照样走桌面拖选**，
                        // 表现为"切了强制触屏还是滑动取词"（切换方案到自绘才正常，
                        // 因为那才让 isFreeSwipe/isDoubleTap 双双为 false）。
                        //
                        // 现在：强制键鼠/强制触控板 → 一律桌面级（不看方案）；
                        //       强制触屏 → 一律长按（不看方案）；
                        //       只有"自动"档才允许方案去决定走哪条路。
                        // 语义上这也是对的：用户显式声明了输入设备，
                        // 这个声明比"选词方案"更具体，不该被方案覆盖。
                        val useFastPath = when (inputMode) {
                            InputMode.FORCE_DESKTOP,
                            InputMode.FORCE_TRACKPAD,
                            InputMode.FORCE_TRACKPAD_BUBBLE -> true
                            InputMode.FORCE_TOUCH -> false
                            InputMode.AUTO -> isFreeSwipe || isDoubleTap || isMouseDesktop
                        }

                        // 走触屏路径（长按/双击方案）时把桌面标记复位：
                        // 手柄必须重新出现，否则"触屏却摸不到手柄"。
                        //
                        // ⚠️ 只在"由于用户意图而回到触屏路径"时复位。
                        // 触控板档位下即便某次手势被判定为滚动而放行，也**不能**把
                        // desktopPointerActive 打回 false —— 那会让触控板在一次滚动之后
                        // 退化成触屏手感（冒出水滴手柄、长按选句），
                        // 而用户全程都在用触控板，从没切过设备。
                        if (!isMouseDesktop && !isTrackpad) desktopPointerActive = false

                        if (useFastPath) {
                            diagnose(
                                when {
                                    isBubbleTrackpad -> "触控板气泡模式（桌面级即时拖选 + 水滴手柄）"
                                    isFreeSwipe -> "自由滑选模式（即按即划）"
                                    isDoubleTap -> "双击模式（触屏/键鼠）"
                                    isTrackpad -> "触控板模式（桌面级选词 + 纵向滑动手势不拦截）"
                                    else -> "键鼠/触控板增强模式"
                                }
                            )

                            // 鼠标/触控板，或在自由滑选模式下：按下即消费，保证拉选跟手。
                            //
                            // ⚠️ 必须把 FORCE_TRACKPAD / FORCE_DESKTOP 一起算进来：
                            // 这两档是用户**显式声明**的桌面级意图，不该再依赖
                            // 系统上报的指针类型（部分 ROM 会把触控板报成 Touch）。
                            // 漏掉它们会导致强制档位下拖选完全不跟手。
                            //
                            // ⚠️ 强制触屏档位要**主动放行**（不消费）：那表示用户明确
                            // 要求走长按路径，此时若因为系统把指针报成 Mouse 而顺手消费掉，
                            // 表格行/代码块的 horizontalScroll、外层列表的纵向滚动
                            // 都会拿不到这次手势 —— 表现成"在表格里按住不放，什么都没发生"。
                            val wantsDesktopGesture = when (inputMode) {
                                InputMode.FORCE_TOUCH -> false
                                InputMode.FORCE_DESKTOP,
                                InputMode.FORCE_TRACKPAD,
                                InputMode.FORCE_TRACKPAD_BUBBLE -> true
                                InputMode.AUTO -> isMousePointer || isFreeSwipe || isTrackpad
                            }
                            if (wantsDesktopGesture) {
                                down.consume()
                            }

                            // 手柄只在触屏路径出现；桌面路径把它收掉（见 desktopPointerActive 说明）。
                            //
                            // ⚠️ 例外：「触控板气泡」档要**保留**手柄（isBubbleTrackpad），
                            // 它的卖点就是"桌面级即时拖选 + 手柄气泡"，
                            // 若在这里也把 desktopPointerActive 置真，手柄会被藏掉，
                            // 这一档就退化成和"强制触控板"一模一样了。
                            if (isMouseDesktop && !isBubbleTrackpad) desktopPointerActive = true

                            // ---- 桌面级连击：双击选词 / 三击选句（与浏览器一致）----
                            // 判定用"同一位置 + 320ms 内"，阈值与桌面系统的双击设置同量级。
                            if (isMouseDesktop) {
                                val nowMs = System.currentTimeMillis()
                                val nearLast = lastDownPos.value?.let {
                                    (down.position - it).getDistance() < 24.dp.toPx()
                                } ?: false
                                clickCount.intValue =
                                    if (nowMs - lastDownAt.longValue < 320L && nearLast) {
                                        clickCount.intValue + 1
                                    } else {
                                        1
                                    }
                                lastDownAt.longValue = nowMs
                                lastDownPos.value = down.position

                                val multiHit = controller.hitTest(downPos)
                                when {
                                    clickCount.intValue >= 3 && multiHit != null -> {
                                        // 三击：选中整句（浏览器是选整段，这里按本项目的"句"粒度）
                                        controller.selectSentenceAt(multiHit)
                                        diagnose("桌面级三击：选中整句")
                                        continue
                                    }
                                    clickCount.intValue == 2 && multiHit != null -> {
                                        // 双击：选中光标下的词，不进入拖动（与浏览器一致）
                                        controller.selectWordAt(multiHit)
                                        diagnose("桌面级双击：选中一个词")
                                        continue
                                    }
                                }
                            }

                            // 锚点 = 按下时命中的位置；拖动全程以它为另一端。
                            // 存 HitResult 本身而不是 BlockPoint：锚点的归一化与
                            // 清理由控制器收口（见 beginRangeSelection）。
                            val anchorHit = controller.hitTest(downPos)

                            // ⚠️ 落点是否在「网格型块」（表格 / 代码块）里。
                            //
                            // 这类块的阅读手势就是**任意方向按下滑动**：表格要沿行横向拉、
                            // 也可能跨行斜拉；代码块本身就是 softWrap=false 的横向长条。
                            // 而下面两道"把拖动交还给列表滚动"的防线是按**纵向占主导**判的，
                            // 于是用户在表格里按住往下一划 → 被判成"想滚动列表" →
                            // 手势被中止、列表开始滚 → 内容在指针底下移动。
                            // 用户看到的就是"表格选不中，而且会漂移"。
                            //
                            // 落点在网格块里时豁免这两道防线：这是用户明确要选这块内容，
                            // 不该当成滚动意图。表格/代码块在消息里占比有限，
                            // 豁免不会影响正常的列表滚动（从旁边正文起手仍是滚动）。
                            val anchorInGrid = anchorHit != null &&
                                controller.snapshotAt(anchorHit.block)?.let {
                                    it.cells.isNotEmpty() ||
                                        it.block.kind == MdRichBlock.Kind.CODE
                                } == true

                            var dragging = false
                            var releasePos = downPos
                            var aborted = false

                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Main)

                                // ⚠️ 触控板双指滚动**必须**在这里放行，否则会被当成拖选。
                                //
                                // 旧实现（以及本项目上一轮）认为"触控板双指滚动 = 两个 pointer"，
                                // 于是只判断 `event.changes.size > 1`。这个假设对**触屏**双指成立，
                                // 对**触控板**不成立：
                                //   · 触屏双指 → 真的是两个 pointer id（changes.size == 2）；
                                //   · 触控板双指 → 被系统合成为**一个鼠标指针的滚轮事件**
                                //     （PointerEventType.Scroll + scrollDelta），changes.size == 1。
                                // 滚轮事件在 Android 上不做成"第二根手指"，它走的是
                                // MotionEvent 的 ACTION_SCROLL / AXIS_VSCROLL 通道
                                // （见 androidx.compose.ui.input.pointer.MotionEventAdapter）。
                                //
                                // 于是触控板双指一划就落进了下面的拖选分支：位移超过 touchSlop →
                                // dragging = true → 一路 consume 并扩展选区，表现为"双指滑动变成选句"。
                                //
                                // 这解释了为什么它是**偶发**的：取决于触控板驱动/系统把这次操作
                                // 报成滚轮事件还是带位移的鼠标拖动。判断事件类型而不是猜 pointer 个数，
                                // 就不再依赖设备行为。
                                //
                                // 关键：这里**不 consume**，只是退出并交还给外层滚动容器。
                                if (event.isScrollEvent()) {
                                    aborted = true
                                    break
                                }

                                // 触屏双指手势一律放行，不拦截
                                //（让外层消息列表能够自由顺畅地用双指上下滚动）
                                if (event.changes.size > 1) {
                                    aborted = true
                                    break
                                }

                                val change = event.changes
                                    .firstOrNull { it.id == down.id } ?: break
                                releasePos = change.position
                                if (!change.pressed) break

                                if (!dragging) {
                                    val moved = change.position - downPos
                                    if (moved.getDistance() <= viewConfig.touchSlop) {
                                        continue
                                    }
                                    // ⚠️ 豁免名单必须同时含 FORCE_DESKTOP 与 FORCE_TRACKPAD。
                                    //
                                    // 用户之所以手动切到"强制触控板"，往往正是因为系统把触控板
                                    // 报成了 Touch（`isMousePointer == false`）—— 那时旧判据成立、
                                    // 手势被直接中止，表现为"按下没反应、一个字都选不中"。
                                    // 这两档都是用户**显式声明**的桌面级意图，不该再受指针类型左右。
                                    // 触控板真正的"双指滚动"由另外两道防线负责（Scroll 事件判定 +
                                    // 纵向占主导判定），不依赖这一条。
                                    if (!anchorInGrid && !isMousePointer && !isFreeSwipe &&
                                        inputMode != InputMode.FORCE_DESKTOP &&
                                        inputMode != InputMode.FORCE_TRACKPAD &&
                                        inputMode != InputMode.FORCE_TRACKPAD_BUBBLE
                                    ) {
                                        // 纯双击/非滑选模式下，触屏滑动拖拽交给消息列表滚动
                                        // （但落点在表格/代码块里时不交还 —— 见 anchorInGrid 说明）
                                        aborted = true
                                        break
                                    }

                                    // ⚠️ 第二道防线：**起点没有可选文本**的纵向滑动才交还给滚动。
                                    //
                                    // 第一道防线（isScrollEvent）能拦住"系统报成滚轮"的触控板双指；
                                    // 但有些触控板驱动把双指滑动报成"鼠标按下 + 移动"（没有 Scroll 事件），
                                    // 那种情况事件类型看不出来。
                                    //
                                    // 旧判据"纵向占主导就交还"的问题：**竖着拉多行选段**也是纵向占主导
                                    // —— 用户按在文字上想竖着扩选，结果页面滚走了，选区当场作废。
                                    // 这种"选字与滚动打架"的真判据其实与方向无关：
                                    // **起点底下有没有可选文本**。按在文字上，任何方向都是选字
                                    // （容器本来就不会滚——我们消费了移动）；按在空白处，
                                    // 存在的意图只有滚动。
                                    //
                                    // 判据：`anchorHit == null`（起点没落在任何可选块里）+ 纵向占主导
                                    // （斜拖一点时仍算滚动）。不 consume，让外层滚动容器接手。
                                    //
                                    // 自由滑选（FREE_SWIPE）是"即按即划"方案，不受此规则约束；
                                    // 落点在表格/代码块里时同样豁免（见 anchorInGrid 说明）：
                                    // 那两种块本来就该任意方向拉选，判成滚动会让内容在指针下漂移。
                                    val verticalDominant =
                                        kotlin.math.abs(moved.y) > kotlin.math.abs(moved.x) * 1.5f
                                    val scrollGuardActive = !anchorInGrid && !isFreeSwipe &&
                                        anchorHit == null &&
                                        (isTrackpad || isMousePointer)
                                    if (verticalDominant && scrollGuardActive) {
                                        aborted = true
                                        diagnose("起点无文本 + 纵向滑动 → 交还滚动")
                                        break
                                    }

                                    dragging = true
                                    if (anchorHit != null) {
                                        // 起锚：选区先收成锚点处的空选区，
                                        // 之后每次移动再扩展另一端。
                                        controller.beginRangeSelection(anchorHit)
                                    }
                                }

                                if (dragging) {
                                    val moveHit = controller.hitTest(change.position)
                                    if (moveHit != null) {
                                        controller.extendRangeSelectionTo(moveHit)
                                        currentOnSelectionChanged(controller.hasSelection)
                                    }
                                }
                                change.consume()
                            }

                            if (aborted) {
                                diagnose("桌面路径：触屏拖动 → 交还列表滚动")
                                continue
                            }

                            if (dragging) {
                                // ⚠️ 抖动型"拖选"：位移小到只是一次点击的抖动 → 按点击处理。
                                // （成因见 clickTolerancePx 的说明；这是"链接点不开"的另一大成因。）
                                val totalMove = (releasePos - downPos).getDistance()
                                if (totalMove <= clickTolerancePx) {
                                    controller.clear()
                                    currentOnSelectionChanged(false)
                                    val hit = controller.hitTest(releasePos)
                                    val clicked = hit?.let { h ->
                                        QuoteLinkMatcher.findHit(
                                            h.offset,
                                            blockQuoteLinks.getOrNull(h.block).orEmpty()
                                        )
                                    }
                                    if (clicked != null) {
                                        currentOnQuoteClick(clicked.target.node)
                                        diagnose("抖动点击 → 命中超链接，已跳转")
                                    } else {
                                        diagnose("抖动点击 → 未命中链接，按取消选区处理")
                                    }
                                    continue
                                }
                                controller.endRangeSelection()
                                // 拖选结束：**保留**选区
                                //（桌面惯例：松手后选区还在，可以直接复制）
                                diagnose(
                                    "拖选结束 选中=${controller.hasSelection}" +
                                        " 长度=${controller.selectedText().length}"
                                )
                                continue
                            }

                            // ---- 没有拖动 = 一次点击：按「双击 / 单击」分流 ----
                            val now = android.os.SystemClock.uptimeMillis()
                            val isDouble = lastClickUptime > 0L &&
                                now - lastClickUptime <= viewConfig.doubleTapTimeoutMillis
                            lastClickUptime = now

                            if (isDouble) {
                                val hit = controller.hitTest(releasePos)
                                if (hit != null) {
                                    controller.selectSentenceAt(hit)
                                    currentOnSelectionChanged(controller.hasSelection)
                                    diagnose(
                                        "双击选句 → 选中=${controller.hasSelection}" +
                                            " 长度=${controller.selectedText().length}"
                                    )
                                }
                            } else {
                                // 单击：判同文本超链接（与触屏路径的轻点分支一致）
                                //
                                // ⚠️ 顺序很重要：**先判链接，再判"取消选区"**。
                                //
                                // 旧代码把 `controller.hasSelection` 放在前面：
                                // 只要当前有选区，这次点击就被当作"点空白取消选区"吃掉，
                                // 链接连判都不判。而选区的寿命比想象中长得多 ——
                                // 长按选句、拖选之后选区会一直留着（桌面惯例是松手保留），
                                // 于是用户"选了一段文字 → 再去点链接"必然失败；
                                // 更糟的是失败点击只清掉选区、不发生跳转，用户再点一次才可能成功，
                                // 表现就是"极其频繁点不出来，失败率比成功率高"。
                                //
                                // 现在：点到链接就跳转（并顺手收掉选区，视觉上更像"打开了它"）；
                                // 没点到链接才按原逻辑取消选区。
                                val hit = controller.hitTest(releasePos)
                                val clicked = hit?.let { h ->
                                    QuoteLinkMatcher.findHit(
                                        h.offset,
                                        blockQuoteLinks.getOrNull(h.block).orEmpty()
                                    )
                                }
                                if (clicked != null) {
                                    controller.clear()
                                    currentOnSelectionChanged(false)
                                    currentOnQuoteClick(clicked.target.node)
                                } else if (controller.hasSelection) {
                                    // 单击且已有选区、又不是链接 = 取消选择
                                    controller.clear()
                                    currentOnSelectionChanged(false)
                                    diagnose("单击取消选区")
                                }
                            }
                            continue
                        }

                        // --- 1) 长按：本组件的主要入口，选中"长按处的那一句" ---
                        val longPress = awaitLongPressOrCancellation(down.id)
                        if (longPress == null) {
                            diagnose("长按未通过（可能被判定为拖动/抬起）")
                        }
                        if (longPress != null) {
                            // ⚠️ 核心自愈：若控制器中的已注册块数少于解析块数，立即就地自愈补齐注册！
                            // 彻底消除由于列表滚动重组、流式增量或切屏后 onTextLayout 暂未重新派发导致
                            // registeredBlockCount==0 造成的"长按必空"死锁。
                            if (controller.registeredBlockCount < blocks.size) {
                                blocks.indices.forEach { idx -> refreshOriginAndRegister(idx) }
                            }

                            // 双点探测：先用长按释放点，若未命中则回退最初按下点，彻底消除长按微小漂移落空
                            var hit = controller.hitTest(longPress.position)
                            if (hit == null) {
                                hit = controller.hitTest(downPos)
                            }
                            // 把两张源表的规模一并报出来：
                            // 因为"块注册"那条诊断与本条共用同一个显示位，会被覆盖，
                            // 所以这里必须自带完整上下文，才能一眼分级：
                            //   控制器块数=0 且布局表=0 → onTextLayout 没回调
                            //   控制器块数=0 且位置表=0 → onGloballyPositioned 没回调
                            //   控制器块数>0 却命中为空 → 坐标空间不一致
                            val sizes = "块=${controller.registeredBlockCount}" +
                                " 布局表=${layouts.size} 位置表=${origins.size}" +
                                " 解析块=${blocks.size}"
                            diagnose(
                                if (hit == null) {
                                    "长按成功但命中为空（$sizes）"
                                } else {
                                    "长按成功 → 块${hit.block} 偏移${hit.offset}（$sizes）"
                                }
                            )
                            if (hit != null) {
                                // ⚠️ 长按选**词**，不是选整句。
                                //
                                // 旧实现是"长按 → 选中整句"，用户的原话是
                                // 「只能选句，不能选词，每次一选选一整句又调不了」——
                                // 而读外文文献时最常用的动作恰恰是"按住一个词、查它"，
                                // 选一整句把词的边界淹没了，想缩到词还得靠手柄一点点拉。
                                //
                                // 现在统一按"落点处的一个词"起步（CJK 取相邻两字、
                                // 西文按字母数字串切整词，见 [wordBounds]），
                                // 需要更大范围时**拖手柄**往外扩 —— 这是微信/QQ
                                // 与浏览器一致的粒度，也是"能选词又能调"的做法。
                                // 整句仍然可达：桌面级三击、或把手柄拖到句末。
                                controller.selectWordAt(hit)
                                currentOnSelectionChanged(controller.hasSelection)
                                diagnose(
                                    "已选中=${controller.hasSelection} 文本长度=${controller.selectedText().length}"
                                )
                            }

                            // 长按后继续拖 = 调整离按下点更近的那一端。
                            // 用"块距离优先、块内距离次之"：跨块选区时只比块内偏移
                            // 是不可比的（两次比较的偏移属于不同的块）。
                            val sel = controller.selection?.normalized()
                            if (hit != null && sel != null) {
                                val target = BlockPoint(hit.block, hit.offset)
                                val dStart = pointDistance(target, sel.start)
                                val dEnd = pointDistance(target, sel.end)
                                controller.beginHandleDrag(
                                    if (dStart <= dEnd) {
                                        MessageSelectionController.START_HANDLE
                                    } else {
                                        MessageSelectionController.END_HANDLE
                                    }
                                )
                            }

                            var finished = false
                            while (!finished) {
                                val event = awaitPointerEvent(PointerEventPass.Main)
                                val change = event.changes
                                    .firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) {
                                    // ⚠️ 抬手必须**消费**，这是"长按选中后立刻消失、
                                    // 必须拖一下才锁得住"的根因。
                                    //
                                    // 成因：宿主（阅读器页卡 / 浏览器页卡）在正文外层挂着
                                    // `detectTapGestures`。它用 `awaitFirstDown()` 启动，
                                    // 而本路径全程**没有消费过 DOWN**（只有 MOVE 被消费），
                                    // 于是它的 tap 判定照常开始；随后
                                    // `waitForUpOrCancellation()` 只要看到"有任何 change
                                    // 被消费"就取消 —— 拖动时 MOVE 被消费，它自然取消；
                                    // 但**按住不动直接抬手**时，只有 UP 到达且未被消费，
                                    // 于是它判定为一次「轻点正文」→ dispatchBodyTap →
                                    // consumeTapClear() 把刚选好的选区清掉。
                                    //
                                    // 用户看到的就是"长按能选出字，一松手就没了"，
                                    // 而"按住拖一下"反而能留住（因为拖动消费了 MOVE）。
                                    change.consume()
                                    finished = true
                                    break
                                }
                                val dragging = controller.draggingHandle
                                if (dragging != null) {
                                    val moveHit = controller.hitTest(change.position)
                                    if (moveHit != null) {
                                        controller.moveHandle(dragging, moveHit)
                                        currentOnSelectionChanged(controller.hasSelection)
                                    }
                                }
                                change.consume()
                            }
                            controller.endHandleDrag()
                            continue
                        }

                        // --- 2) 没长按成（一次轻点）：因手柄已在循环顶部优先处理，此时落入本分支说明点击在手柄之外 ---
                        //
                        // ⚠️ 与桌面分支同样的问题：必须**先判链接、再判取消选区**。
                        // 旧代码在 `controller.hasSelection` 成立时直接吃掉这次点击，
                        // 链接连判都不判 —— 而长按选句之后选区是**保留**的，
                        // 于是"先选中一段、再点链接"这条路必然失败，
                        // 表现就是"极其频繁点不出来"。
                        //
                        // ⚠️ 轻点自愈：若控制器中的已注册块数少于解析块数，立即补齐注册！
                        // 必须放在链接判定**之前**：命中依赖已注册的几何，
                        // 不先补齐就会"几何缺失 → hitTest 返回 null → 链接点不中"。
                        if (controller.registeredBlockCount < blocks.size) {
                            blocks.indices.forEach { idx -> refreshOriginAndRegister(idx) }
                        }
                        // 轻点只判一种东西：同文本超链接（点开 → 该分支的小窗）。
                        // 概念链接（`[[概念名]]` → 词源/追问）已按用户要求停用，
                        // 正文里不再有第二种可点标记。
                        //
                        // 命中点：本分支只知道按下点（走到这里说明长按没成立，
                        // longPress 必为 null，没有"释放点"可用 —— 那是桌面拖选
                        // 循环里的局部量）。对轻点来说两者相差只有几像素，
                        // 用 downPos 足够；再叠加一次"最近块"退化命中即可覆盖边缘情况。
                        val tapHit = controller.hitTest(downPos)
                        val tapLink = tapHit?.let { h ->
                            QuoteLinkMatcher.findHit(
                                h.offset,
                                blockQuoteLinks.getOrNull(h.block).orEmpty()
                            )
                        }
                        if (tapLink != null) {
                            controller.clear()
                            currentOnSelectionChanged(false)
                            currentOnQuoteClick(tapLink.target.node)
                        } else if (controller.hasSelection) {
                            // 已有选区、点到的又不是链接：轻点 = 取消选择
                            controller.clear()
                            currentOnSelectionChanged(false)
                        }
                    }
                }
            }
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            blocks.forEachIndexed { index, block ->
                val linkRanges = blockQuoteLinks.getOrNull(index)?.map { it.range }.orEmpty()

                // ---- 选区范围：只让"范围真的变了"的块重组 ----
                //
                // 旧写法是 `selectionRangeFor(controller, index, block)` 直接求值。
                // 那会在组合期读 `controller.selection`，于是**每一个块**都订阅了它；
                // 而拖选时 selection 每帧都被替换成新对象 → 每帧、每个块都被判定为
                // 需要重组，每个块还得重建自己的 AnnotatedString 高亮。
                // 一条消息二十个块，拖一次选字就是每帧二十次重组 —— 块越多、字号越大越卡。
                //
                // 包成 derivedStateOf 之后：selection 变化时只有**范围确实改变**的那一到两个块
                // 会拿到不同的值并失效重组，其余块算出的结果与上一帧相同（通常都是 null），
                // Compose 因此跳过它们。计算本身仍会跑，但那是廉价的 IntRange 比较，
                // 省掉的是重组 + AnnotatedString 重建，这才是贵的部分。
                //
                // key 里带 index 与 block：块的解析结果被换新实例时（增量解析、流式输出）
                // 必须重建这个 derived 状态，否则会读到旧块的文本长度。
                val selectionRange = remember(controller, index, block) {
                    derivedStateOf { selectionRangeFor(controller, index, block) }
                }.value

            val blockMarks = projectedMarks.mapNotNull { (global, color) ->
                val local = global.filter { it.blockIndex == index }.map { it.asIntRange }
                if (local.isEmpty()) null else local to color
            }

                SelectableBlock(
                    block = block,
                    index = index,
                    colors = colors,
                    baseFontSize = baseFontSize,
                    highlights = highlights,
                    highlightColor = highlightColor,
                    selectionColor = selectionColor,
                    selectionRange = selectionRange,
                    quoteLinkRanges = linkRanges,
                    markGroups = blockMarks,
                    knownConcepts = knownConcepts,
                    rootCoordinates = rootCoordinates,
                    onCoordinates = { coords ->
                        blockCoords[index] = coords
                        refreshOriginAndRegister(index)
                    },
                    onOrigin = {
                        origins[index] = it
                        registerBlockIfReady(index)
                    },
                    onLayout = {
                        layouts[index] = it
                        refreshOriginAndRegister(index)
                    },
                    // 表格块专用：一格一个 Text，坐标与排版按格登记进控制器，
                    // 每次就绪都顺手重试注册 —— 格数凑齐那一刻快照才成立。
                    onCellOrigin = { cellIndex, origin ->
                        controller.setCellOrigin(index, cellIndex, origin)
                        registerBlockIfReady(index)
                    },
                    onCellLayout = { cellIndex, layoutResult ->
                        controller.setCellLayout(index, cellIndex, layoutResult)
                        registerBlockIfReady(index)
                    }
                )
            }
        }

        // ---- 选区拖拽手柄 ----
        // 手柄画在文本**之上**，因此必须放在 Column 之后（Compose 按顺序绘制）。
        // 位置直接取自 controller.handleCenterFor，与 handleAt 命中检测完全同源。
        val startCenter = controller.handleCenterFor(MessageSelectionController.START_HANDLE)
        val endCenter = controller.handleCenterFor(MessageSelectionController.END_HANDLE)
        // 桌面级指针下**不画手柄**：键鼠调整选区的方式是"重新按下再拖"或双击，
        // 与浏览器一致；手柄是触屏专属的辅助（见 desktopPointerActive 的说明）。
        if ((!desktopPointerActive || alwaysShowHandles) &&
            startCenter != null && endCenter != null
        ) {
            SelectionHandle(
                center = startCenter,
                color = selectionColor,
                dragging = controller.draggingHandle == MessageSelectionController.START_HANDLE
            )
            SelectionHandle(
                center = endCenter,
                color = selectionColor,
                dragging = controller.draggingHandle == MessageSelectionController.END_HANDLE
            )
        }

        // ---- Word 级就地浮动操作栏（针对此段提问/复制/高亮）----
        // 精准定位于选区文字正上方 8dp，彻底告别屏幕底部固定栏，小窗选字也能原地开窗
        val boundingRect = controller.selectionBoundingRect()
        if (controller.hasSelection && boundingRect != null && onAsk != null) {
            FloatingSelectionToolbar(
                visible = true,
                anchorRect = boundingRect,
                onAsk = {
                    val text = controller.selectedText()
                    if (text.isNotBlank()) onAsk(text)
                    controller.clear()
                    currentOnSelectionChanged(false)
                },
                onCopy = {
                    val text = controller.selectedText()
                    if (text.isNotBlank()) onCopy?.invoke(text)
                    controller.clear()
                    currentOnSelectionChanged(false)
                },
                onHighlight = {
                    onHighlight?.invoke()
                    controller.clear()
                    currentOnSelectionChanged(false)
                },
                onSelectAll = {
                    controller.selectAll()
                    currentOnSelectionChanged(controller.hasSelection)
                },
                onDismiss = {
                    controller.clear()
                    currentOnSelectionChanged(false)
                },
                highlightActive = highlightActive,
                onUndoHighlight = onUndoHighlight?.let { undo ->
                    {
                        undo()
                        controller.clear()
                        currentOnSelectionChanged(false)
                    }
                },
                onEtymology = onEtymology?.let { etymAction ->
                    {
                        val text = controller.selectedText()
                        if (text.isNotBlank()) etymAction(text)
                        controller.clear()
                        currentOnSelectionChanged(false)
                    }
                }
            )
        }
    }
}

/**
 * 经典水滴手柄（Android 官方规范：直径 25dp，触控热区 48dp）。
 */
@Composable
private fun SelectionHandle(
    center: Offset,
    color: Color,
    dragging: Boolean
) {
    val density = LocalDensity.current
    val radiusPx = MessageSelectionController.HANDLE_RADIUS
    val sizeDp = with(density) { (radiusPx * 2f).toDp() }
    val centerX = with(density) { center.x.toDp() }
    val centerY = with(density) { center.y.toDp() }
    val visualSize = if (dragging) sizeDp * 1.15f else sizeDp
    val handleColor = if (color == Color.Unspecified) {
        MaterialTheme.colorScheme.primary
    } else {
        color
    }

    // 48dp 规范化无形触控热区（精确以 centerX, centerY 为中心），内含 25dp 高清水滴游标
    Box(
        modifier = Modifier
            .offset(x = centerX - 24.dp, y = centerY - 24.dp)
            .size(48.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(visualSize)
                .clip(CircleShape)
                .background(handleColor)
                .border(
                    width = 2.dp,
                    color = MaterialTheme.colorScheme.surface,
                    shape = CircleShape
                )
        )
    }
}

/**
 * 这次指针事件是不是一次**滚动/滚轮**事件（触控板双指、鼠标滚轮、外接滚轮都算）。
 *
 * 为什么必须显式判类型，而不能靠 `changes.size > 1`：
 * 触屏双指确实是两个 pointer，但**触控板双指被系统合成成一个鼠标指针的滚轮事件**，
 * `changes.size` 仍是 1。后者若落进拖选分支，表现就是"双指上下滑动变成了选句"。
 *
 * 两道判据取或：
 *  1. [PointerEventType.Scroll] —— 滚轮通道（Android 上是 ACTION_SCROLL）；
 *  2. `scrollDelta != Offset.Zero` —— 个别设备把滚动挂在 Move 事件上。
 */
private fun PointerEvent.isScrollEvent(): Boolean {
    if (type == PointerEventType.Scroll) return true
    return changes.any { it.scrollDelta != Offset.Zero }
}

/** 两个点之间的加权距离：先比块，再比块内偏移。 */
private fun pointDistance(a: BlockPoint, b: BlockPoint): Long {    val blockGap = (a.block - b.block).toLong()
    val offsetGap = (a.offset - b.offset).toLong()
    // 块间距权重取得远大于任何块内偏移差，保证"块更近的一端"总胜出
    return blockGap * blockGap * 1_000_000_000L + offsetGap * offsetGap
}

/**
 * 纯文本消息由 blocksForMessage(content, plain = true) 整条包成**一个段落块**：
 * 不切块、不做行内解析，于是换行与 `*`、`#` 都原样显示，
 * 同时又拿到字符级选择所需的恒等映射与 TextLayoutResult。
 * 这是「用户消息也要能选中提问」与「用户输入不该被当 Markdown」
 * 两个要求同时满足的最简做法。
 */

/** 当前块在显示维度上的选区范围（仅当选区与该块相交时非空）。 */
private fun selectionRangeFor(
    controller: MessageSelectionController,
    index: Int,
    block: MdRichBlock
): IntRange? {
    val sel = controller.selection?.normalized() ?: return null
    if (index < sel.start.block || index > sel.end.block) return null
    val from = if (index == sel.start.block) sel.start.offset else 0
    val to = if (index == sel.end.block) sel.end.offset else block.displayText.length
    if (from >= to) return null
    return from until to
}

/** 块内的一个概念链接：显示维度上的区间、概念名、以及是否命中本地词库。 */

@Composable
private fun SelectableBlock(
    block: MdRichBlock,
    index: Int,
    colors: MdColors,
    baseFontSize: TextUnit,
    highlights: List<HighlightRange>,
    highlightColor: Color,
    selectionColor: Color,
    selectionRange: IntRange?,
    quoteLinkRanges: List<IntRange> = emptyList(),
    /** 命中本地词库的概念名集合，用于概念链接（`[[概念名]]`）的实线/弱化样式。 */
    knownConcepts: Set<String> = emptySet(),
    /** 多色标记组（块内显示坐标区间 + 颜色），见 toAnnotated 同名参数。 */
    markGroups: List<Pair<List<IntRange>, Color>> = emptyList(),
    // ---- 粗体呈现偏好（由调用方从 AppSettings 注入，见 SelectableMarkdownText 的同名注释）
    boldColorStyle: com.treechat.workbench.data.prefs.BoldColorStyle =
        com.treechat.workbench.data.prefs.BoldColorStyle.DEFAULT,
    boldHighlightColor: com.treechat.workbench.data.prefs.BoldHighlightColor =
        com.treechat.workbench.data.prefs.BoldHighlightColor.NONE,
    boldFontStyle: com.treechat.workbench.data.prefs.BoldFontStyle =
        com.treechat.workbench.data.prefs.BoldFontStyle.DEFAULT,
    accentColor: Color = Color.Unspecified,
    rootCoordinates: LayoutCoordinates? = null,
    onCoordinates: (LayoutCoordinates) -> Unit = {},
    onOrigin: (Offset) -> Unit,
    onLayout: (TextLayoutResult) -> Unit,
    /** 表格块：第 [Int] 格的左上角坐标就绪（根坐标系）。非表格块不会调用。 */
    onCellOrigin: (cellIndex: Int, Offset) -> Unit = { _, _ -> },
    /** 表格块：第 [Int] 格的排版结果就绪。非表格块不会调用。 */
    onCellLayout: (cellIndex: Int, TextLayoutResult) -> Unit = { _, _ -> }
) {
    @Suppress("UNUSED_EXPRESSION")
    index
    // 外层容器仅负责宽度，绝不挂 onGloballyPositioned（否则父容器后触发会覆盖内层 Text 的真实坐标）
    val base = Modifier.fillMaxWidth()

    // 唯一坐标探针：直接挂在产出 onTextLayout 的那个 Text 节点上，确保坐标与字符排版 100% 同源
    val textPositionModifier = Modifier.onGloballyPositioned { coords ->
        onCoordinates(coords)
        // ⚠️ 这里**没有**退化路径。
        //
        // 旧实现在 rootCoordinates 为空/未 attach 时退回 `coords.positionInParent()`，
        // 但那两者的原点**不同**（一个是本组件外层 Box，一个是直接父节点）。一旦某个
        // 块走这条路径登记过，它的坐标就整体偏了一截；而"是否需要重建"的判据是
        // "origin 是否与快照相等"，后续回调若继续走同一条路径就会永远相等 ——
        // 于是偏移**不会自愈**，该块永久命中错位。
        // 宁可本次不登记（hitTest 会对没有快照的块直接跳过），等真正的 root 到位后
        // 由 syncLiveGeometry / onGloballyPositioned 补上。
        val root = rootCoordinates?.takeIf { it.isAttached } ?: return@onGloballyPositioned
        onOrigin(root.localPositionOf(coords, Offset.Zero))
    }

    // 只取属于**本块**的高亮：投影出来的 start/end 是块内局部下标，
    // 不带块号地全量渲染会把别的块的同一段字也点亮（见 HighlightRange 的注释）。
    val ranges = remember(highlights, index) {
        highlights.filter { it.blockIndex == index }.map { it.asIntRange }
    }

    when (block.kind) {
        MdRichBlock.Kind.CODE -> SelectableCodeBlock(
            block = block,
            colors = colors,
            ranges = ranges,
            highlightColor = highlightColor,
            selectionColor = selectionColor,
            selectionRange = selectionRange,
            modifier = base,
            textModifier = textPositionModifier,
            onLayout = onLayout
        )

        MdRichBlock.Kind.TABLE -> TableBlockView(
            block = block,
            colors = colors,
            baseFontSize = baseFontSize,
            selectionRange = selectionRange,
            selectionColor = selectionColor,
            // ⚠️ 这两项以前**没有传**，是"表格文字永远点不开链接"的直接原因。
            // 表格是逐格自绘的，不像其它块那样走 toAnnotated，
            // 所以 links 必须在这里显式传下去，否则整条链路到此断开。
            quoteLinkRanges = quoteLinkRanges,
            quoteLinkColor = colors.quoteLink,
            // 粗体偏好必须透传：否则用户在设置里选的粗体颜色/字体只对正文生效，
            // 表格里的 `**粗体**` 仍是老样子，两处看起来像两套渲染。
            boldColorStyle = boldColorStyle,
            boldHighlightColor = boldHighlightColor,
            boldFontStyle = boldFontStyle,
            accentColor = accentColor,
            rootCoordinates = rootCoordinates,
            onCellOrigin = onCellOrigin,
            onCellLayout = onCellLayout,
            modifier = base
        )

        MdRichBlock.Kind.DIVIDER -> Box(
            modifier = base
                .height(1.dp)
                .background(colors.divider)
        )

        MdRichBlock.Kind.HEADING -> {
            val size = when (block.headingLevel) {
                1 -> baseFontSize * 1.45f
                2 -> baseFontSize * 1.28f
                3 -> baseFontSize * 1.15f
                else -> baseFontSize * 1.06f
            }
            Column(modifier = base) {
                if (block.headingLevel <= 2) {
                    Box(
                        modifier = Modifier
                            .width(24.dp)
                            .height(3.dp)
                            .clip(CircleShape)
                            .background(colors.accent)
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                }
                Text(
                    text = block.toAnnotated(
                        colors = colors,
                        baseFontSize = size,
                        highlightRanges = ranges,
                        highlightColor = highlightColor,
                        selectionRange = selectionRange,
                        selectionColor = selectionColor,
                        quoteLinkRanges = quoteLinkRanges,
                        knownConcepts = knownConcepts,
                        markGroups = markGroups
                    ),
                    modifier = textPositionModifier,
                    fontSize = size,
                    fontWeight = FontWeight.Bold,
                    lineHeight = size * 1.4f,
                    onTextLayout = onLayout
                )
            }
        }

        MdRichBlock.Kind.QUOTE -> Row(
            modifier = base.height(IntrinsicSize.Min)
        ) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .fillMaxHeight()
                    .clip(CircleShape)
                    .background(colors.quoteBar)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = block.toAnnotated(
                    colors = colors,
                    baseFontSize = baseFontSize,
                    color = colors.textSecondary,
                    highlightRanges = ranges,
                    highlightColor = highlightColor,
                    selectionRange = selectionRange,
                    selectionColor = selectionColor,
                    quoteLinkRanges = quoteLinkRanges,
                        knownConcepts = knownConcepts,
                    markGroups = markGroups
                ),
                modifier = textPositionModifier,
                fontSize = baseFontSize * 0.97f,
                lineHeight = baseFontSize * 1.55f,
                onTextLayout = onLayout
            )
        }

        MdRichBlock.Kind.LIST -> Text(
            text = block.toAnnotated(
                colors = colors,
                baseFontSize = baseFontSize,
                highlightRanges = ranges,
                highlightColor = highlightColor,
                selectionRange = selectionRange,
                selectionColor = selectionColor,
                quoteLinkRanges = quoteLinkRanges,
                        knownConcepts = knownConcepts,
                markGroups = markGroups
            ),
            modifier = base.then(textPositionModifier),
            fontSize = baseFontSize,
            lineHeight = baseFontSize * 1.55f,
            onTextLayout = onLayout
        )

        else -> Text(
            text = block.toAnnotated(
                colors = colors,
                baseFontSize = baseFontSize,
                highlightRanges = ranges,
                highlightColor = highlightColor,
                selectionRange = selectionRange,
                selectionColor = selectionColor,
                quoteLinkRanges = quoteLinkRanges,
                        knownConcepts = knownConcepts,
                markGroups = markGroups
            ),
            modifier = base.then(textPositionModifier),
            fontSize = baseFontSize,
            lineHeight = baseFontSize * 1.62f,
            onTextLayout = onLayout
        )
    }
}

/**
 * 代码块：横向滚动 + 等宽字体。
 *
 * 与旧实现的差别：文本改用 `AnnotatedString(displayText)` 直接构造，
 * 而不是走 toAnnotated。原因是代码内容里 `*`、`_`、反引号都是代码本身，
 * 过一遍行内解析会把它们当成标记吃掉，**代码会被改坏**。
 * 代码的映射本来就是恒等映射，不需要行内解析。
 */
@Composable
private fun SelectableCodeBlock(
    block: MdRichBlock,
    colors: MdColors,
    ranges: List<IntRange>,
    highlightColor: Color,
    selectionColor: Color,
    selectionRange: IntRange?,
    modifier: Modifier,
    textModifier: Modifier = Modifier,
    onLayout: (TextLayoutResult) -> Unit
) {
    Column(
        modifier = modifier
            .clip(MaterialTheme.shapes.medium)
            .background(colors.codeBlockBg)
            .border(1.dp, colors.divider, MaterialTheme.shapes.medium)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.codeBlockHeaderBg)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = block.codeLang.ifBlank { "code" }.lowercase(),
                style = MaterialTheme.typography.labelSmall,
                color = colors.textMuted,
                fontFamily = FontFamily.Monospace
            )
        }
        Text(
            text = plainAnnotated(block.displayText, colors, ranges, highlightColor, selectionRange, selectionColor),
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(12.dp)
                .then(textModifier),
            color = colors.text,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.5.sp,
            lineHeight = 19.sp,
            softWrap = false,
            onTextLayout = onLayout
        )
    }
}

/** 不经行内解析的纯文本 AnnotatedString，仅叠加高亮与选择背景。 */
private fun plainAnnotated(
    text: String,
    colors: MdColors,
    ranges: List<IntRange>,
    highlightColor: Color,
    selectionRange: IntRange?,
    selectionColor: Color
): AnnotatedString = androidx.compose.ui.text.buildAnnotatedString {
    append(text)
    if (selectionRange != null && selectionColor != Color.Unspecified) {
        val s = selectionRange.first.coerceIn(0, text.length)
        val e = (selectionRange.last + 1).coerceIn(s, text.length)
        if (s < e) addStyle(androidx.compose.ui.text.SpanStyle(background = selectionColor), s, e)
    }
    if (highlightColor != Color.Unspecified) {
        ranges.forEach { range ->
            val s = range.first.coerceIn(0, text.length)
            val e = (range.last + 1).coerceIn(s, text.length)
            if (s < e) addStyle(androidx.compose.ui.text.SpanStyle(background = highlightColor), s, e)
        }
    }
    @Suppress("UNUSED_EXPRESSION")
    colors
}

/**
 * 表格各单元格的**显示文本**，顺序与解析时拼 `rawText` 的顺序完全一致：
 * 表头各格 → 各数据行各格。**顺序一旦不一致，命中的偏移就会错格**，
 * 因为块内偏移是按这个顺序累加出来的（见 [tableCellTextStarts]）。
 *
 * 用的是解析侧同一个 [tableCellDisplayText]：渲染、选区几何、块显示串
 * 三者共用一条规则。这是"表格里选中的是别的字"的根治 ——
 * 三处只要有一处不剥，长度就对不上。
 */
private fun tableCellTexts(block: MdRichBlock): List<String> {
    val header = block.tableHeader?.cells.orEmpty().map { tableCellDisplayText(it.rawText) }
    val body = block.tableRows.orEmpty().flatMap { it.cells }.map { tableCellDisplayText(it.rawText) }
    return header + body
}

/**
 * 每一格文本在 `block.displayText` 里的起始下标。
 *
 * 解析侧是 `allCells.joinToString(" ")`（单个空格分隔），所以第 k 格之前
 * 累计的长度 = 前 k 格长度之和 + k 个分隔空格。这里按同一规则算回去，
 * 保证 `显示偏移 ⇄ 格内偏移` 与 `rawText` 的构造方式逐字对应。
 */
private fun tableCellTextStarts(texts: List<String>): List<Int> {
    var acc = 0
    return texts.map { text ->
        val start = acc
        acc += text.length + 1
        start
    }
}

/**
 * 表格渲染：一张真正的网格 —— 表头吸顶、斑马纹、数字列右对齐、
 * 列宽按内容自适应，**每一格都把自己的坐标与排版登记给选词控制器**。
 *
 * ## 三条不能动的红线（改版时最容易踩）
 *
 * 1. **逐格登记 `onCellOrigin` / `onCellLayout` 必须保留**。
 *    表格没有"整块的一个 Text"，命中解析、选区高亮、手柄定位分别依赖
 *    [CellLayoutSnapshot]、`MessageSelectionController.cellRectFor`
 *    与这里的登记。少一个就退化成"表格选不中"。
 *    旧实现曾以"单元格坐标映射复杂"为由直接把表格排除在选中之外，
 *    那是错的 —— 逐格登记之后问题根治。
 *
 * 2. **横向滚动状态必须整表共享一个**。
 *    旧实现给表头和每一行各 `rememberScrollState()`，于是每行能独立滚动、
 *    彼此对不齐（表头停在左边、数据行滑到右边）。
 *    现在整张表共用一个状态，横向滚动时列永远对齐。
 *
 * 3. **格内文本必须与 `tableCellTexts` 同源**（都过 `tableCellDisplayText`）。
 *    几何按"各格显示文本长度"累加算 `textStart`，渲染画的是同一份文本。
 *    两处规则不同步 ⇒ 选中范围整体错位（"选出来的不是点到的字"）。
 */
@Composable
private fun TableBlockView(
    block: MdRichBlock,
    colors: MdColors,
    baseFontSize: TextUnit,
    /** 本块的选区区间（显示维度，`from until to`，与其它块同一语义）。 */
    selectionRange: IntRange? = null,
    selectionColor: Color = Color.Unspecified,
    /**
     * 本块的同文本超链接区间（显示维度），与其它块同一语义。
     *
     * ⚠️ 这一项是**补上的**：表格路径此前完全没有这条参数，
     * 于是"引用原文"在表格里永远不会被点开 —— 表格文字不是不能选，
     * 而是**从未被标记为链接**（其它块走 toAnnotated 的 quoteLinkRanges，
     * 表格是逐格自绘的，两条渲染路径各写各的，漏了一边）。
     */
    quoteLinkRanges: List<IntRange> = emptyList(),
    /** 同文本超链接的颜色，由上层从 MdColors.quoteLink 传入。 */
    quoteLinkColor: Color = Color.Unspecified,
    // ---- 粗体呈现偏好：与正文走同一套逻辑，表格里的 `**粗体**` 也吃这套
    boldColorStyle: com.treechat.workbench.data.prefs.BoldColorStyle =
        com.treechat.workbench.data.prefs.BoldColorStyle.DEFAULT,
    boldHighlightColor: com.treechat.workbench.data.prefs.BoldHighlightColor =
        com.treechat.workbench.data.prefs.BoldHighlightColor.NONE,
    boldFontStyle: com.treechat.workbench.data.prefs.BoldFontStyle =
        com.treechat.workbench.data.prefs.BoldFontStyle.DEFAULT,
    /** 主题强调色（`boldColorStyle` 为 ACCENT 时取值）。 */
    accentColor: Color = Color.Unspecified,
    rootCoordinates: LayoutCoordinates? = null,
    onCellOrigin: (cellIndex: Int, Offset) -> Unit = { _, _ -> },
    onCellLayout: (cellIndex: Int, TextLayoutResult) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier
) {
    val rows = block.tableRows
    val header = block.tableHeader ?: return

    // 逐格的选区：整块选区可能横跨多格，这里把它裁到"属于本格"的那一段
    val texts = remember(header, rows) { tableCellTexts(block) }
    val starts = remember(texts) { tableCellTextStarts(texts) }

    // 整表**共用**一个横向滚动状态 —— 见文件头红线 2
    val horizontalScroll = rememberScrollState()

    // 列宽：按各列最长内容 + 表头长度分配，再按可用宽度整体缩放。
    //
    // 为什么不用固定宽度（旧实现写死 116.dp）：表格列的内容长度差异极大
    // （"是/否"两字 vs 一整句说明），固定宽要么截断要么大片留白，
    // 横屏平板上尤其明显 —— 右边空一大块。
    val columnCount = header.cells.size
    val density = LocalDensity.current

    // 数字列右对齐：某列若**整列**（除表头）都是数字/百分比，就右对齐，
    // 竖着扫一眼数值大小关系，不用逐行比对。
    val numericColumns = remember(header, rows) {
        computeNumericColumns(
            header = header.cells.map { it.rawText },
            rows = rows.map { r -> r.cells.map { it.rawText } },
            columnCount = columnCount
        )
    }

    var cellIndex = -1
    // 用 BoxWithConstraints 拿**实测**可用宽度，而不是写死上限：
    // 写死的上限在横屏平板上会留下一大片空白（用户明确抱怨过这一点），
    // 而"填满父容器宽度"在窄屏手机上又正好。
    BoxWithConstraints(modifier = modifier) {
        val available = maxWidth
        val columnWidths = remember(header, rows, baseFontSize, density, available) {
            computeTableColumnWidths(
                header = header.cells.map { it.rawText },
                rows = rows.map { r -> r.cells.map { it.rawText } },
                columnCount = columnCount,
                baseFontSize = baseFontSize,
                available = available,
                density = density
            )
        }
        fun widthOf(index: Int): Dp =
            columnWidths.getOrElse(index) { columnWidths.lastOrNull() ?: 116.dp }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.small)
                .border(1.dp, colors.divider, MaterialTheme.shapes.small)
        ) {
            // ---- 表头：吸顶。放在横向滚动**之外**，纵向滚动时不会跟着跑掉。
            //
            // 注意这里用的是整表共享的滚动状态：表头与数据行横向严格对齐。
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.tableHeaderBg)
                    .horizontalScroll(horizontalScroll)
            ) {
                header.cells.forEachIndexed { j, cell ->
                    cellIndex++
                TableGridCell(
                    text = cell.rawText,
                    cellIndex = cellIndex,
                    textStart = starts.getOrElse(cellIndex) { 0 },
                    blockRange = selectionRange,
                    blockQuoteLinkRanges = quoteLinkRanges,
                    quoteLinkColor = quoteLinkColor,
                    color = colors.accent,
                    fontSize = baseFontSize,
                    fontWeight = FontWeight.Bold,
                    // 表头不吃"数字列右对齐"：它是列名不是数值
                    selectionColor = selectionColor,
                    boldColorStyle = boldColorStyle,
                    boldHighlightColor = boldHighlightColor,
                    boldFontStyle = boldFontStyle,
                    accentColor = accentColor,
                    colors = colors,
                    rootCoordinates = rootCoordinates,
                    onCellOrigin = onCellOrigin,
                    onCellLayout = onCellLayout,
                    modifier = Modifier.width(widthOf(j))
                )
            }
        }

        // ---- 数据行：斑马纹 + 细横线
        rows.forEachIndexed { rowIndex, row ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(colors.divider)
            )
            val zebra = if (rowIndex % 2 == 1) {
                // 斑马纹只加一点点底色：深浅要克制，否则表格像斑马报纸。
                // 用 divider 的极低透明度，保证明暗主题下都只是"微微有色"。
                colors.divider.copy(alpha = 0.18f)
            } else {
                Color.Transparent
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(zebra)
                    .horizontalScroll(horizontalScroll)
            ) {
                row.cells.forEachIndexed { j, cell ->
                    cellIndex++
                    TableGridCell(
                        text = cell.rawText,
                        cellIndex = cellIndex,
                        textStart = starts.getOrElse(cellIndex) { 0 },
                        blockRange = selectionRange,
                        blockQuoteLinkRanges = quoteLinkRanges,
                        quoteLinkColor = quoteLinkColor,
                        color = colors.text,
                        // 字号回到正文，不再 * 0.92f：
                        // 旧实现乘 0.92 是为了塞进 116dp 的窄列，
                        // 列宽自适应之后这个理由已不存在，
                        // 继续缩小只会让表格"字比正文小一号"，用户明确抱怨过这点。
                        fontSize = baseFontSize,
                        lineHeight = baseFontSize * 1.5f,
                        textAlign = if (numericColumns.getOrElse(j) { false }) {
                            TextAlign.End
                        } else {
                            TextAlign.Start
                        },
                        selectionColor = selectionColor,
                        boldColorStyle = boldColorStyle,
                        boldHighlightColor = boldHighlightColor,
                        boldFontStyle = boldFontStyle,
                        accentColor = accentColor,
                        colors = colors,
                        rootCoordinates = rootCoordinates,
                        onCellOrigin = onCellOrigin,
                        onCellLayout = onCellLayout,
                        modifier = Modifier.width(widthOf(j))
                    )
                }
            }
        }
        }
    }
}

/**
 * 按各列内容长度分配列宽。
 *
 * 做法（刻意保持简单、可预测）：
 * 1. 每列取"最长一格"的**视觉宽度**（中文按 2 个单位、其余按 1 个），
 *    至少给一个下限（太窄的列连表头两个字都放不下）；
 * 2. 表头单独占一份下限，保证表头永远不被压扁；
 * 3. 若总宽超过可用宽度，按比例整体缩小（下限之内不缩）；
 * 4. 若总宽小于可用宽度，**按内容比例分摊剩余空间** ——
 *    这一步是"横屏平板右侧不再大片留白"的关键。
 *
 * 不用 TextMeasurer 的原因：它要测量每一格的排版结果，
 * 而表格格子的排版结果**本身就是**选词几何的输入，
 * 测量与登记互相触发会引入多余的重组与一帧延迟。
 * 按字符数估算对"哪列该宽一点"这个判断已经足够。
 */
private fun computeTableColumnWidths(
    header: List<String>,
    rows: List<List<String>>,
    columnCount: Int,
    baseFontSize: TextUnit,
    /** 实测可用宽度（由 BoxWithConstraints 的 maxWidth 给出）。 */
    available: Dp,
    density: androidx.compose.ui.unit.Density
): List<Dp> {
    if (columnCount <= 0) return emptyList()
    val fontPx = with(density) { baseFontSize.toPx() }
    // 一个"字符单位"的像素宽度：中文约等于一个字宽，英文/数字约 0.55 倍。
    val latinUnit = fontPx * 0.55f
    val cjkUnit = fontPx

    fun visualWidth(text: String): Float {
        var w = 0f
        text.forEach { c ->
            w += if (isCjk(c)) cjkUnit else latinUnit
        }
        return w
    }

    // 每列最长内容（表头与所有数据行一起比）
    val maxWidths = FloatArray(columnCount)
    val consider: (List<String>) -> Unit = { cells ->
        cells.forEachIndexed { j, raw ->
            if (j >= columnCount) return@forEachIndexed
            val text = tableCellDisplayText(raw)
            val w = visualWidth(text)
            if (w > maxWidths[j]) maxWidths[j] = w
        }
    }
    consider(header)
    rows.forEach { consider(it) }

    // 换算成"含内边距"的列宽，并给每列一个下限
    val paddingPx = with(density) { TABLE_CELL_H_PADDING.toPx() * 2f }
    val minColumnPx = with(density) { TABLE_MIN_COLUMN_WIDTH.toPx() }
    val natural = FloatArray(columnCount) { j ->
        maxOf(maxWidths[j] + paddingPx, minColumnPx)
    }

    val totalNatural = natural.sum()
    val availablePx = with(density) { available.toPx() }
    if (totalNatural <= 0f) {
        return List(columnCount) { with(density) { minColumnPx.toDp() } }
    }

    if (totalNatural <= availablePx) {
        // 有富余：按各列自然宽度的比例分摊，让表格刚好铺满可用宽度。
        //
        // 为什么不是"等分剩余空间"：等分会把"备注"这种长列和"序号"这种
        // 短列拉成一样宽，短列里一大片空白、长列里换行变多，两头都不讨好。
        val extra = availablePx - totalNatural
        return List(columnCount) { j ->
            val share = extra * (natural[j] / totalNatural)
            with(density) { (natural[j] + share).toDp() }
        }
    }

    // 超宽：按比例压缩，但不压到下限以下（压不下去就交给横向滚动）
    val scale = availablePx / totalNatural
    return List(columnCount) { j ->
        val shrunk = natural[j] * scale
        with(density) { maxOf(shrunk, minColumnPx).toDp() }
    }
}

/** 是否是中日韩表意文字（按全角计宽）。 */
private fun isCjk(c: Char): Boolean {
    val code = c.code
    return code in 0x2E80..0xA4CF ||      // CJK 部首 … 彝文基本区
        code in 0xAC00..0xD7AF ||          // 谚文
        code in 0xF900..0xFAFF ||          // 兼容表意文字
        code in 0xFE30..0xFE4F ||          // 兼容形式
        code in 0xFF00..0xFF60 ||          // 全角 ASCII
        code in 0xFFE0..0xFFE6 ||          // 全角符号
        code in 0x20000..0x2FA1F           // 扩展 B..F
}

/** 纯数字列（含小数、负号、千分位、百分号）判定。 */
private val TABLE_NUMERIC_RE =
    Regex("^[+-]?[0-9][0-9,，_.\\s]*%?$")

/**
 * 哪些列整列都是数字（表头不算）。
 *
 * 只有**整列**都是数值才右对齐：混排的列右对齐会把文字也推到右边，
 * 比左对齐更难读。要求"整列"而不是"多数行"，避免个别空行/占位符误判。
 */
private fun computeNumericColumns(
    header: List<String>,
    rows: List<List<String>>,
    columnCount: Int
): List<Boolean> {
    if (columnCount <= 0) return emptyList()
    val out = ArrayList<Boolean>(columnCount)
    for (j in 0 until columnCount) {
        var sawNumber = false
        var allNumeric = true
        var anyText = false
        rows.forEach { cells ->
            val raw = cells.getOrNull(j) ?: return@forEach
            val text = tableCellDisplayText(raw).trim()
            if (text.isEmpty()) return@forEach
            anyText = true
            if (TABLE_NUMERIC_RE.matches(text)) {
                sawNumber = true
            } else {
                allNumeric = false
            }
        }
        out.add(allNumeric && sawNumber && anyText)
    }
    return out
}

/** 单元格左右内边距。 */
private val TABLE_CELL_H_PADDING = 12.dp

/** 列宽下限：低于这个值，表头两三个字就换行，表格会显得碎。 */
private val TABLE_MIN_COLUMN_WIDTH = 72.dp

/**
 * 表格里的一格。
 *
 * 三件事一起做：渲染这一格、把它的**坐标**登记到根坐标系、把它的**排版**
 * 登记给控制器 —— 缺任何一个 `MessageSelectionController` 都凑不齐快照
 * （表格就选不中）。
 *
 * 文本在渲染前先过 [tableCellDisplayText] 剥掉 `**` `` ` `` 等行内标记：
 * 解析器有意把单元格原文（含标记）存进 `rawText`，而渲染直接画原文
 * 就是用户看到的"表格里一堆星号"。
 */
@Composable
private fun TableGridCell(
    text: String,
    cellIndex: Int,
    /** 本格文本在块显示文本里的起始下标（用于把块级选区裁到格内）。 */
    textStart: Int,
    blockRange: IntRange?,
    /**
     * 本块的链接区间（块级、显示维度）。同理需要裁到本格 ——
     * 直接用块级下标去标注格内文本会把高亮画到错误的字符上。
     */
    blockQuoteLinkRanges: List<IntRange> = emptyList(),
    /** 同文本超链接的颜色（与正文块共用 MdColors.quoteLink）。 */
    quoteLinkColor: Color = Color.Unspecified,
    color: Color,
    fontSize: TextUnit,
    fontWeight: FontWeight? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
    /** 数字列右对齐；默认左对齐。 */
    textAlign: TextAlign? = null,
    selectionColor: Color,
    boldColorStyle: com.treechat.workbench.data.prefs.BoldColorStyle =
        com.treechat.workbench.data.prefs.BoldColorStyle.DEFAULT,
    boldHighlightColor: com.treechat.workbench.data.prefs.BoldHighlightColor =
        com.treechat.workbench.data.prefs.BoldHighlightColor.NONE,
    boldFontStyle: com.treechat.workbench.data.prefs.BoldFontStyle =
        com.treechat.workbench.data.prefs.BoldFontStyle.DEFAULT,
    accentColor: Color = Color.Unspecified,
    colors: MdColors,
    rootCoordinates: LayoutCoordinates?,
    onCellOrigin: (cellIndex: Int, Offset) -> Unit,
    onCellLayout: (cellIndex: Int, TextLayoutResult) -> Unit,
    modifier: Modifier = Modifier
) {
    // 显示文本：与 tableCellTexts / tableCellTextStarts 同源（同一函数），
    // 否则格内偏移与块内偏移会错位。
    //
    // ⚠️ 片段必须由**同一份解析**产出（见 parseTableCellSpans 的说明）：
    // 单独调一次 parseTableCellSpans(display) 会把已剥好的标记再剥一遍，
    // 片段拼起来就对不上 display 了。所以这里传**原文**。
    val cellInline = remember(text) { parseTableCellSpans(text) }
    val display = remember(text) { tableCellDisplayText(text) }

    // 选区是**块级**的，裁到本格：交集非空才染色
    val cellSelection = blockRange?.let { range ->
        val start = (range.first - textStart).coerceIn(0, display.length)
        val end = (range.last + 1 - textStart).coerceIn(start, display.length)
        if (end > start) start until end else null
    }
    // 链接同理裁到本格。注意用 `endExclusive` 而不是 `last`：
    // 变量名与 IntRange 都是左闭右开的语义约定（见 HighlightRange），
    // 直接用 range.last + 1 在空区间上会算错。
    val cellQuoteLinks = blockQuoteLinkRanges.mapNotNull { range ->
        val start = (range.first - textStart).coerceIn(0, display.length)
        val end = (range.last + 1 - textStart).coerceIn(start, display.length)
        if (end > start) start until end else null
    }

    // 单元格文本的样式装配。
    //
    // ⚠️ 这里**不复用**正文的 toAnnotated：那需要先有一个 MdRichBlock，
    // 而表格的选区/链接是"块级下标裁到格内"，与块的语义不同。
    // 但样式规则（粗体四维、代码底衬、链接下划线）必须与正文**完全一致**，
    // 所以下面这段 SpanStyle 的取值直接照搬 MarkdownOffsets 的规则，
    // 而不是另发明一套 —— 两处各写各的正是"表格看起来像另一种东西"的原因。
    val content = remember(display, cellQuoteLinks, cellSelection, selectionColor, color,
        boldColorStyle, boldHighlightColor, boldFontStyle, accentColor) {
        AnnotatedString.Builder().apply {
            append(display)
            // 1) 行内样式：按 tableCellDisplayText 解析出的片段逐段套用
            var cursor = 0
            cellInline.forEach { span ->
                val start = cursor
                val end = cursor + span.text.length
                cursor = end
                if (span.text.isEmpty() || start >= end) return@forEach
                addStyle(
                    SpanStyle(
                        // plain（无标记的普通文本）不加样式，只给底色下的默认色
                        color = if (span.code) colors.inlineCodeText else color,
                        fontWeight = if (span.bold) FontWeight.Bold else null,
                        fontFamily = if (span.code) FontFamily.Monospace else null,
                        fontSize = if (span.code) fontSize * 0.94f else TextUnit.Unspecified,
                        background = when {
                            span.code -> colors.inlineCodeBg
                            span.bold && boldHighlightColor.argb != 0L ->
                                Color(boldHighlightColor.argb)
                            else -> Color.Unspecified
                        },
                        textDecoration = if (span.strike) TextDecoration.LineThrough else null
                    ),
                    start,
                    end
                )
            }
            // 2) 同文本超链接：与其它块**完全同一套样式**（MdColors.quoteLink
            //    + 实线下划线 + 12% 底衬，见 MarkdownOffsets 的说明），
            //    否则表格里的链接看起来会像另一种东西。
            cellQuoteLinks.forEach { range ->
                addStyle(
                    SpanStyle(
                        color = quoteLinkColor,
                        textDecoration = TextDecoration.Underline,
                        background = quoteLinkColor.copy(alpha = 0.12f)
                    ),
                    range.first,
                    range.last + 1
                )
            }
            // 3) 选择态背景压在最后：选中时要盖过链接底衬，语义更清晰
            if (cellSelection != null && selectionColor != Color.Unspecified) {
                addStyle(
                    SpanStyle(background = selectionColor),
                    cellSelection.first,
                    cellSelection.last + 1
                )
            }
        }.toAnnotatedString()
    }

    Text(
        text = content,
        fontSize = fontSize,
        fontWeight = fontWeight,
        lineHeight = lineHeight,
        color = color,
        textAlign = textAlign,
        onTextLayout = { onCellLayout(cellIndex, it) },
        modifier = modifier
            .padding(horizontal = TABLE_CELL_H_PADDING, vertical = 8.dp)
            // 探针必须是**最内层**（Text 自身），否则拿到的是含 padding 的框，
            // 命中会整体偏出 padding 的距离
            .onGloballyPositioned { coords ->
                val root = rootCoordinates?.takeIf { it.isAttached }
                onCellOrigin(
                    cellIndex,
                    root?.localPositionOf(coords, Offset.Zero) ?: coords.positionInParent()
                )
            }
    )
}

