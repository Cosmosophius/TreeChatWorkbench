package com.treechat.workbench.ui.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.height
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.BorderColor
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.ui.res.painterResource
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.treechat.workbench.ui.selection.MessageSelectionController
import androidx.compose.ui.unit.dp
import com.treechat.workbench.ui.selection.assureTextCursor
import kotlin.math.roundToInt

/**
 * 单个 OCR 识别词条的绝对几何包围盒与真实文字。
 * 兼容 OcrEngine 产出的几何数据结构（支持中文、俄文 Cyrillic 及拉丁文）。
 */
data class OcrWordBox(
    val id: String,
    val bounds: Rect,
    val text: String,
    val confidence: Float = 1.0f
)

/**
 * 纯 Jetpack Compose 原生实现的实况选词覆盖层 (LiveTextComposeOverlay)。
 *
 * 1. 采用 Compose 声明式 Canvas 绘制选区与多段包围盒合并；
 * 2. 选区支持 Material 3 柔和主色半透明高亮与 4dp 微倒角渲染；
 * 3. 选词即在选区上方悬浮展现 Material 3 原生工具栏（提问、划线、笔记、复制、词源）。
 *
 * ───────────────────────── 手势判定规则（最容易出错的一处） ─────────────────────────
 *
 * 这里**不再**用 `detectDragGestures`。它的问题是：一旦按下就进入拖拽，于是
 * 「双指放大」的第二根手指一落下，第一根手指早已开始拉框 —— 用户想放大，
 * 却看到屏幕上冒出一个选词框（这正是"扫描档完全不能双指外扩放大"的病因）。
 *
 * 现在的判定分两段，判据全部落在**指针数量**与**长按时长**上：
 *
 * ① 观察期（最长 [androidx.compose.ui.platform.ViewConfiguration.longPressTimeoutMillis]）：
 *    - `event.changes.size > 1`（出现第二根手指）→ **立刻放弃**，本层一个事件都不消费，
 *      缩放/平移原封不动交给外层的变换容器。**双指绝不会进入选词**。
 *    - 单指在观察期内移动超过 touchSlop → 只有**页面处于放大态**才判定为「平移/滚动」并放弃；
 *      未放大时外层单指平移被钳制为 0（见 LiveTextPageView 的 detectTransformGestures），
 *      让位等于把事件丢进黑洞 —— 手指轻微一抖就把 0.5 秒长按静默判死、屏幕上毫无反应，
 *      这正是「长按经常选不中」的病因之一。所以未放大时微动只表示「这不是点按」，
 *      长按继续等待；出现第二根手指仍然立刻让位给缩放。
 *    - 单指抬起且几乎没动 → 记作一次**点按**（见 ③）。
 *    - 观察期超时（单指按住不动约 0.5 秒）→ 进入 ②。
 *
 * ② 框选期：**长按成立的瞬间就把指头底下的词盒选上**（含 12dp 容差，压偏时取 40dp 内
 *    最近的一个词），于是「长按一个词」本身就是一次有可见结果的选中 —— 高亮立即出现，
 *    抬起即弹工具条；随后拖动则在命中词的基础上并集扩张矩形，既有框选能力不变。
 *    只有单指才继续；此后每一次移动都 `consume()`，
 *    外层变换容器看到已消费事件即退出本次手势，不会一边拉框一边平移。
 *    框选途中若冒出第二根手指 → 取消本次框选（清空选区），交还缩放。
 *
 * ③ 点按：**先看有没有选中** —— 有选区就清空并收起工具条（并回调
 *    [onClearSelection] 让宿主清掉它自己的选区状态），没有选区才回调
 *    [onTapBlank]（阅读器用它切换沉浸全屏）。这样「点一下空白取消选中」
 *    与「点一下唤出/收起组件」不会互相打架。
 *
 * @param autoSelect 直接把 [wordBoxes] 当作"已选中"并弹出工具条（内置浏览器用：
 *   网页选区是网页自己产生的，本浮层没有"框选"这个动作）。
 * @param syntaxMarks 当前生效的句法标注（命中的词框 → 层级/角色）。由宿主按
 *   [buildSyntaxBoxMarks] 算好后传入；这里只负责画，不解释句法。
 * @param onTranslate 工具条「翻译」：把选中文本交给当前模型译成目标语言。
 *   **为 null 时不显示该按钮** —— 内置浏览器用的是同一份工具条（见
 *   WebBrowserScreen），它没有模型通路，不该多出两个点不动的死按钮。
 * @param onAnalyzeSyntax 工具条「句法」：让模型把选中文本切成层级化句法单元。同上。
 * @param syntaxActive 当前是否存在可清除的句法标注（决定工具条是否显示"清除"）。
 * @param onClearSyntax 清除句法标注。
 * @param onClearSelection 用户在浮层上点按清空了内部选区时的回调。
 * @param onTapBlank 用户在浮层上点按、且本来就没有选区时的回调。
 * @param containerScale 本浮层所在容器当前被放大的倍数。工具条会按 1/倍率
 *   **反向缩放**，否则容器一放大，工具条上的字和图标会跟着一起变成巨无霸。
 * @param containerPan 容器当前的平移量（与 [containerScale] 同属那个
 *   `graphicsLayer` 变换）。工具条的位置同样要经这层变换，光反向缩放尺寸不够 ——
 *   否则一放大/平移，锚点偏移会被 zoom 再乘一遍，工具条直接飞出可视区。
 */
@Composable
fun LiveTextComposeOverlay(
    wordBoxes: List<OcrWordBox>,
    onAskQuestion: (selectedText: String) -> Unit,

    onOpenEtymology: (selectedText: String) -> Unit = {},
    syntaxMarks: List<SyntaxBoxMark> = emptyList(),
    /** 考纲生词命中的词盒 id（空 = 关闭考纲），命中盒画考纲色下划线。 */
    vocabBoxIds: Set<String> = emptySet(),
    onTranslate: ((selectedText: String) -> Unit)? = null,
    onAnalyzeSyntax: ((selectedText: String) -> Unit)? = null,
    syntaxActive: Boolean = false,
    onClearSyntax: () -> Unit = {},
    autoSelect: Boolean = false,
    onClearSelection: () -> Unit = {},
    onTapBlank: () -> Unit = {},
    containerScale: Float = 1f,
    containerPan: Offset = Offset.Zero,
    selectionMethod: LiveSelectionMethod = LiveSelectionMethod.TALKTREE,
    /**
     * 同文本超链接目标：命中段落会被画成明显的链接样式。
     *
     * ## 为什么用"下划线 + 淡底"而不是对话里那套颜色
     * 对话正文是深色气泡上的浅色字；OCR 是**扫描纸面**，底色可能是白的也可能是黄的，
     * 任何单一强调色都可能与纸色糊在一起、认不出来。所以这里避开颜色对比，
     * 改用两条在任意底色上都认得出的信号：**实下划线**（形状特征）与
     * **轻微提亮**（不是染色）。这也是网页上可点文本最通用的表达方式。
     */
    quoteLinkTargets: List<com.treechat.workbench.ui.selection.QuoteLinkTarget> = emptyList(),
    /** 点中同文本超链接 → 跳到对应分支。 */
    onQuoteLinkClick: (com.treechat.workbench.data.model.ConversationNode) -> Unit = {},
    /**
     * 本页已放置的「微型小窗标签」。
     *
     * 用途：**摆脱对文字的完全依赖**。OCR 认不出字的时候（潦草手写、表格线、
     * 竖排、旧版外文书的破格字），"按住这个词"这条路是断的 —— 一个字都选不中，
     * 于是整页都没法与它关联。标签把入口从"字"改成"位置"：
     * 在正文旁边任意空白处按一下就能放一个，点它直接开小窗。
     */
    pageTags: List<PageTagStore.PageTag> = emptyList(),
    /** 长按空白处 → 在该位置放一个标签。 */
    onAddTag: (Offset) -> Unit = {},
    /** 点已有标签 → 开它的小窗。 */
    onTagClick: (PageTagStore.PageTag) -> Unit = {},
    /**
     * "这是哪一页"的**稳定标识**（宿主给，形如 `文档id#页号#列序/列数`）。
     *
     * 只用于判断"是不是换页了"。它必须与坐标、尺寸无关 ——
     * 进出沉浸态、缩放、旋转都会改坐标但不会改它。
     */
    pageIdentity: String = "",
    /** 全局输入设备模式：自动检测鼠标 / 强制键鼠 / 强制触控。 */
    inputMode: com.treechat.workbench.data.prefs.InputMode =
        com.treechat.workbench.data.prefs.InputMode.AUTO,
    /** 页内查找关键词（Ctrl+F）：命中的词框会额外描一层琥珀色边，便于快速定位。 */
    findQuery: String = "",
    /** 当前定位到第几处命中（0 基）；该处用更醒目的描边标出。 */
    findActiveIndex: Int = 0,
    /** 把本页命中总数回报给宿主（用于"第 n / m 处"）。 */
    onFindCount: (Int) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme

    val selectedBoxes = remember { mutableStateListOf<OcrWordBox>() }
    var dragStart by remember { mutableStateOf<Offset?>(null) }
    // 松手后保留的选框（进入手柄微调态）；点空白才清掉。
    var settledBox by remember { mutableStateOf<Rect?>(null) }
    // 正在拖动的角：0..3 = 左上/右上/右下/左下，-1 = 无
    var activeHandle by remember { mutableStateOf(-1) }
    var dragCurrent by remember { mutableStateOf<Offset?>(null) }
    var toolbarPosition by remember { mutableStateOf<Offset?>(null) }
    /**
     * 条畅选词的选区：**字符区间**（与对话界面同一套模型）。
     * 电脑框选仍用上面那套矩形盒状态，两者互不干扰。
     */
    var charSel by remember { mutableStateOf<IntRange?>(null) }
    /**
     * 由词盒重建的**文字层**（行 → 词 → 字）。
     *
     * 它是"OCR 也能像对话界面那样选字"的关键：把只有整块矩形的词盒补成
     * 逐字几何，选区才谈得上字符区间、手柄才谈得上"两端"。
     * 识别没产出文字（或只有空文本回退盒）时为 null，调用方退回盒模型。
     */
    // ⚠️ 文字层的键用 **wordBoxes 的实例身份**，而清选区的键用 **pageIdentity**。
    // 这两件事必须拆开，用同一个键就一定会出下面某一种病：
    //
    //  · 都用实例身份（`LaunchedEffect(textLayer, …)`）：拖动过程中任何一次重组
    //    只要让 wordBoxes 换了新实例，选区就被当场清空 —— "划过一个字，闪一下就失效"。
    //
    //  · 都用内容签名（词盒数 + 首尾 id + 全文长度）：进出**沉浸态**时这些值全都不变，
    //    但页面已按新尺寸重排、词盒坐标全变了，文字层却还留着**旧坐标** ——
    //    点按与旧坐标对不上就命不中（"进沉浸后选不了字"），
    //    工具条锚点也跟着算错、画到正文上，一碰就误触"提问"弹出对话小窗。
    //
    // 现在：几何跟实例走（永远新鲜，缩放/沉浸/旋转都跟着变），
    // 清空跟页标识走（只有真的换页、换选词方式才清）。
    val textLayer = remember(wordBoxes) { buildOcrTextLayer(wordBoxes) }

    LaunchedEffect(pageIdentity, selectionMethod) {
        charSel = null
        selectedBoxes.clear()
        toolbarPosition = null
    }

    // ---- 手势轨迹：屏上可见 ----
    //
    // 这个功能的问题域已经反复来回好几轮了，全靠读代码猜根因，代价太高。
    // 这里把"这一次手势到底发生了什么"直接画在页角上：
    // 按在哪、命中第几个字、拖到哪、为什么被拒。
    // 复现一次就能把问题定死，不必再猜。
    var trace by remember { mutableStateOf<String?>(null) }
    fun say(msg: String) {
        trace = msg
        android.util.Log.i("OcrSel", msg)
    }
    // 本浮层与工具条的实测尺寸（像素）：工具条锚点要把"内容本地坐标"经容器的
    // graphicsLayer 换算回屏幕坐标并钳进可视区，没有真实尺寸就算不出会不会飞出屏幕。
    var overlaySize by remember { mutableStateOf(IntSize.Zero) }
    var barSize by remember { mutableStateOf(IntSize.Zero) }

    // 页内查找命中集合：大小写不敏感的词框匹配（Ctrl+F）。
    val findMatches = remember(wordBoxes, findQuery) {
        val q = findQuery.trim()
        if (q.isEmpty()) emptyList()
        else wordBoxes.filter { it.text.contains(q, ignoreCase = true) }
    }

    LaunchedEffect(findMatches.size) { onFindCount(findMatches.size) }

    // ---- 同文本超链接：找出被分支引用过的词盒 ----
    //
    // 复用对话那套 QuoteLinkMatcher，但匹配单位是**词盒**而不是字符下标：
    // OCR 拿到的是"这个词连同它在图上的位置"，没有连续正文下标可用。
    // 判据取"词文本出现在某条分支的引用原文中"——宁可略宽，也不要漏标：
    // 漏标用户根本不知道这里能点，多标只是多一次误点，而误点后仍会正确跳转到那条分支。
    val linkBoxes: List<Pair<OcrWordBox, com.treechat.workbench.data.model.ConversationNode>> =
        remember(wordBoxes, textLayer, quoteLinkTargets) {
            findOcrLinkBoxes(wordBoxes, textLayer, quoteLinkTargets)
        }
    // 命中词的 id 集合：绘制与点击都用它
    val linkBoxIds = remember(linkBoxes) { linkBoxes.map { it.first.id }.toSet() }

    // autoSelect：外部已经知道"选中了哪些词"，这里补上用户没做的那次框选，
    // 否则工具条（visible 依赖 selectedBoxes 非空）不会显示。
    LaunchedEffect(wordBoxes, autoSelect) {
        if (autoSelect && wordBoxes.isNotEmpty()) {
            selectedBoxes.clear()
            selectedBoxes.addAll(wordBoxes)
            toolbarPosition = toolbarAnchor(wordBoxes, barSize.height)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { overlaySize = it }
            // 桌面端体验：鼠标悬停在实况 OCR 文字图层上自动变 Mac 原生工字竖条（I-Beam）光标。
            //
            // overrideDescendants = true：本层的竖条要盖过所有后代。
            // 默认（false）时子节点设的光标优先，而这一层内部还叠着逐词高亮、
            // 选区矩形、语法标记等多个可绘制子层；一旦其中任何一个设了光标
            // （或将来有人加），悬停到它上面就会把竖条换掉。
            // 文本区一律竖条才是唯一正确的语义，所以这里显式覆盖。
            .pointerHoverIcon(
                icon = if (inputMode == com.treechat.workbench.data.prefs.InputMode.FORCE_TOUCH) {
                    PointerIcon.Default
                } else {
                    PointerIcon.Text
                },
                overrideDescendants = true
            )
            // 命令式兜底：翻页 / 缩放 / 重新 OCR 会让这一层 detach 再重建，
            // 而重建时指针没离开过 → 收不到 Enter → 光标被打回箭头且永不恢复。
            // 强制触控档下用户要的就是默认箭头，故那一档不补（见 assureTextCursor 说明）。
            .then(
                if (inputMode == com.treechat.workbench.data.prefs.InputMode.FORCE_TOUCH) {
                    Modifier
                } else {
                    Modifier.assureTextCursor()
                }
            )
            // containerScale 必须进 key：放大/复位之后要重新取值，
            // 否则闭包里攥着的是初始倍率，"移动要不要让位给平移"会永远按旧倍率判错。
            // ⚠️ inputMode 必须是 key：手势循环里用 inputMode 判"桌面级还是长按"
            // （见下方 isMouse），而 pointerInput 的 lambda 会被记住 ——
            // 不把它当 key，用户在设置里切换输入设备档位时循环不会重启，
            // 闭包里攥着的还是旧档位，"切到强制触屏却仍按桌面级选词"就是这么来的。
            //
            // ⚠️ 键里必须有 wordBoxes（实例身份）与 containerScale：
            // 沉浸态切换、缩放、旋转都会改词盒坐标，键不跟着变，
            // 手势闭包就会攥着**旧几何**做命中判定 ——
            // 症状是"进了沉浸就选不中 / 缩放后选不准"。
            .pointerInput(wordBoxes, containerScale, containerPan, inputMode, pageIdentity) {
                // 落点命中容差：只在**字缝之间**才启用，取最近词盒 40dp 内者。
                // 不再有"12dp 相交即全选"那条：中文被逐字切盒时，
                // 12dp 的判定框会一次压住四五个字，用户看到的就是"一选选一整句"。
                // 现在的粒度是"落点下那一个词"，要更大范围请拖手柄或直接拖选。
                val nearPadSq = 40.dp.toPx().let { it * it }

                /**
                 * 按压点命中的**一个**词盒；压不中就在容差内取最近的一个。
                 *
                 * ⚠️ 只取一个，这是"每次一选选一整句、想调又调不了"的根因。
                 *
                 * 旧实现返回的是"与 12dp 判定框**相交的全部**词盒"：
                 * 汉字被 ML Kit 逐字切盒、字与字之间只有一两像素的缝，
                 * 12dp 的判定框横跨过去就会同时压住四五个字，
                 * 于是一次点按/长按直接选中一大片 —— 用户看到的就是"一选选一整句"，
                 * 而粒度太粗又让手柄无从调起（想往里收要先跨过好几个字）。
                 *
                 * 现在的粒度与系统文本选择器一致：**先选中落点下的那一个词**，
                 * 需要更大范围就拖手柄，或直接按住拖动（拖动那条路按矩形扩张，
                 * 不受这里影响）。
                 */
                fun hitBoxesAt(at: Offset): List<OcrWordBox> {
                    if (wordBoxes.isEmpty()) return emptyList()
                    // ① 落点确实压在某盒内 → 就是它，唯一且精确。
                    wordBoxes.firstOrNull { it.bounds.contains(at) }?.let { return listOf(it) }
                    // ② 落在字缝里（指头有肉、压偏一两像素很常见）→ 取最近的一个。
                    var best: OcrWordBox? = null
                    var bestSq = Float.MAX_VALUE
                    for (box in wordBoxes) {
                        val cx = (box.bounds.left + box.bounds.right) / 2f
                        val cy = (box.bounds.top + box.bounds.bottom) / 2f
                        val dx = cx - at.x
                        val dy = cy - at.y
                        val sq = dx * dx + dy * dy
                        if (sq < bestSq) {
                            bestSq = sq
                            best = box
                        }
                    }
                    return if (best != null && bestSq <= nearPadSq) listOf(best) else emptyList()
                }

                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val downTimeMs = android.os.SystemClock.uptimeMillis()

                    // ---- 标签：**最先判**，命中就立刻结束本次手势 ----
                    // 标签压在正文之上，是位置锚点；若让它位给选词，
                    // "点标签"会被解释成"点这一小片空白"而永远点不中。
                    val tagHit = pageTags.firstOrNull { t ->
                        val c = Offset(t.x * overlaySize.width, t.y * overlaySize.height)
                        (c - down.position).getDistance() <= TAG_HIT_DP.dp.toPx()
                    }
                    if (tagHit != null) {
                        onTagClick(tagHit)
                        return@awaitEachGesture
                    }

                    // ===== 条畅选词：走「文字层」，与对话界面同构 =====
                    //
                    //   · 点一下  = 锁定落点所在的那个**词**（按 ICU 分词，与对话界面同一个函数）；
                    //   · 拖动    = 从更近的一端按**字**扩选 —— 能选到半个词，这才是文本选择；
                    //   · 两端各一只**水滴手柄**（起点左下、终点右下），与对话界面同形；
                    //   · 全部几何都在页内容坐标里，缩放不参与判定，放大后不会错选。
                    //
                    // 电脑框选（BOX_DRAG）不走这里，保持原来那套矩形框选。
                    // 文字层为空（本页还没识别出文字）时也不走这里，退回盒模型。
                    if (selectionMethod == LiveSelectionMethod.TALKTREE && textLayer != null) {
                        val layer = textLayer
                        val hit = layer.indexAt(down.position)
                        say(
                            "按下(${down.position.x.toInt()},${down.position.y.toInt()}) " +
                                "命中字符=$hit 字数=${layer.text.length} 层=${layer.lineCount}"
                        )

                        if (hit == null) {
                            // 落在**空白**处：抬手时决定是"清选区/交给宿主"还是"放一个标签"。
                            //
                            // ⚠️ 观察期间**一个移动都不能消费**！
                            //
                            // 这里要做的只有"看它动没动、停多久"，观察本身没有任何
                            // 理由要吃掉事件。旧实现把每次移动 `consume()` 掉，后果是：
                            // 平移层判 `isConsumed` 立刻让位、滑动翻页层见 `isConsumed`
                            // 马上退出 —— 两层被同时饿死，按下空白再拖，
                            // 页面纹丝不动。这就是"选词和纸张拖动打架"最重的那类：
                            // 按住文字能选词，按住空白却连纸都挪不动。
                            //
                            // 结算规则也随之一变：**只有"没动过"的抬手才算点按**
                            // （放标签 / 清选区 / 交宿主）；动过就把这次手势整个让给
                            // 平移层，不做任何结算 —— 否则每次平移抬手都会顺手
                            // 把选区清掉，用户想挪着看时就一直白选。
                            var moved = false
                            var lifted = false
                            var ended = false
                            while (!ended) {
                                val ev = awaitPointerEvent()
                                if (ev.changes.count { it.pressed } > 1) break
                                val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                                if (!ch.pressed) {
                                    lifted = true
                                    ended = true
                                    break
                                }
                                if ((ch.position - down.position).getDistance() >
                                    viewConfiguration.touchSlop
                                ) moved = true
                            }
                            if (lifted && !moved) {
                                // 纯点按（含长按停留）：才轮到标签 / 清选区 / 空白点按
                                val held = android.os.SystemClock.uptimeMillis() - downTimeMs
                                if (held >= TAG_HOLD_MS &&
                                    overlaySize.width > 0 && overlaySize.height > 0
                                ) {
                                    onAddTag(
                                        Offset(
                                            down.position.x / overlaySize.width,
                                            down.position.y / overlaySize.height
                                        )
                                    )
                                } else if (charSel != null) {
                                    charSel = null
                                    toolbarPosition = null
                                    onClearSelection()
                                } else {
                                    onTapBlank()
                                }
                            }
                            return@awaitEachGesture
                        }

                        // 按下即锁词：这一下同时解决"点不动"与"看不到选中"。
                        val word = layer.wordRange(hit)
                        charSel = word
                        toolbarPosition = layer.boundsFor(word)
                            ?.let { anchorForRect(it, barSize.height) }
                        // 锚点取离落点更近的那一端：拖动就从这一端往外扩，
                        // 与对话界面"抓住更近的那只水滴"完全同语义。
                        val anchor = if (hit - word.first <= word.last - hit) {
                            word.first
                        } else {
                            word.last
                        }

                        var moved = false
                        var ended = false
                        while (!ended) {
                            val ev = awaitPointerEvent()
                            if (ev.changes.count { it.pressed } > 1) break
                            val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                            if (!ch.pressed) {
                                ch.consume()
                                ended = true
                                break
                            }
                            ch.consume()
                            val cur = layer.indexAt(ch.position)
                            if (cur == null) {
                                say("拖动命中空白(${ch.position.x.toInt()},${ch.position.y.toInt()}) 选区保持")
                            } else {
                                val r = if (cur >= anchor) anchor..cur else cur..anchor
                                if (r != charSel) {
                                    charSel = r
                                    moved = true
                                    toolbarPosition = layer.boundsFor(r)
                                        ?.let { anchorForRect(it, barSize.height) }
                                    say("扩选 ${r.first}..${r.last} =「${layer.textOf(r)}」")
                                } else {
                                    say("仍在字符 $cur 内，选区不变")
                                }
                            }
                        }

                        // 抬手时若是"没拖动过"的一次点击：命中同文本超链接就开窗，
                        // 否则保留刚锁定的那个词 —— 这就是"点一下就锁住一个词"。
                        val locked = charSel
                        // ⚠️ 点击是否算"打开同文本超链接"，判据必须与**画下划线时**完全一致：
                        // 只认 `linkBoxes` 里那些**真的被画了标识**的词盒。
                        //
                        // 两个坑都在这里踩过：
                        //  1. 曾用"选中文字出现在某条引用里"（`quote.contains(w)`）——
                        //     那比标记判据宽得多，中文单字几乎必然命中，
                        //     于是页面上大量无关的字点一下就弹小窗。
                        //  2. 后来改成比对"选词后的字符文本"与"标记时的词盒文本"——
                        //     两者根本不是一回事：中文逐字切盒时，选中一个字取到「语」，
                        //     而标记它的盒文本是「语法新解」，永不相等 ——
                        //     结果就是"看得见下划线，却永远点不中"。
                        //
                        // 正确做法：**按词盒 id 命中**。落点压中哪个盒，
                        // 就看那个盒有没有被标记 —— 与画标识时用的是同一批 id。
                        val markedLink = if (!moved && locked != null) {
                            val hitBox = hitBoxesAt(down.position).firstOrNull()
                            hitBox?.let { box ->
                                linkBoxes.firstOrNull { (marked, _) -> marked.id == box.id }
                            }
                        } else {
                            null
                        }
                        say(
                            (if (moved) "拖动结束" else "点击结束") +
                                " charSel=$locked 选中「${locked?.let { layer.textOf(it) } ?: ""}」" +
                                (if (markedLink != null) " → 命中链接" else " → 非链接")
                        )
                        if (markedLink != null) {
                            charSel = null
                            toolbarPosition = null
                            onClearSelection()
                            onQuoteLinkClick(markedLink.second)
                        }
                        return@awaitEachGesture
                    }

                    say(
                        "未走条畅文字层：方式=$selectionMethod 有文字层=${textLayer != null}"
                    )
                    // ── ① 观察期 ──────────────────────────────────────────────
                    var tapped = false
                    var drifted = false
                    // 这一次手势有没有真的移动过。用来区分"按住不动"（可放标签）
                    // 与"拖动"（在空白处拖是**平移页面**，不该留下标签）。
                    var movedFar = false
                    // 只有放大后单指平移才真的可用（未放大时外层把它钳制为 0），
                    // 所以"移动让位给平移"只在放大态有意义；未放大时微动只说明
                    // "这不是点按"，长按继续等待 —— 否则手指一抖就把 0.5 秒长按
                    // 静默判死，屏幕上一点反应都没有，这正是"长按经常选不中"的病因。
                    val panHandoff = containerScale > 1.001f
                    // 桌面级判定：自动模式看指针类型，强制键鼠/强制触控板一律桌面级，
                    // 强制触控一律长按。
                    // 触控板档位在这里与强制键鼠同义（都是桌面级取词手感）——
                    // 两者的差别只在"纵向滑动是否视为滚动"，那条规则属于聊天正文的
                    // 手势层（SelectableMarkdown），与这里的 OCR 文字层无关。
                    val isMouse = when (inputMode) {
                        com.treechat.workbench.data.prefs.InputMode.FORCE_DESKTOP -> true
                        com.treechat.workbench.data.prefs.InputMode.FORCE_TRACKPAD -> true
                        // 触控板气泡档在 OCR 文字层同样按桌面级处理：
                        // 这里是"按下即拖划选"，手柄气泡的差异属于正文手势层
                        // （SelectableMarkdown），与本层的取词颗粒度无关。
                        com.treechat.workbench.data.prefs.InputMode.FORCE_TRACKPAD_BUBBLE -> true
                        com.treechat.workbench.data.prefs.InputMode.FORCE_TOUCH -> false
                        com.treechat.workbench.data.prefs.InputMode.AUTO ->
                            down.type == PointerType.Mouse
                    }
                    // 桌面级鼠标划词体验：如果是鼠标操作，按下拖拽直接开始划选，不用傻等 500ms！
                    // ⚠️ 桌面级（鼠标/触控板）的观察期**不设超时**，这是"链接标识看得见、
                    // 点下去毫无反应"的根因。
                    //
                    // 旧实现给鼠标也套了 100ms 超时，于是判据变成"按得够快才算点击"：
                    //   · 按下 → 100ms 内没动 → 超时 → `finishedEarly == null`
                    //     → longPressed = true → 走**选词分支**，链接根本不判；
                    //   · 而真人用触控板点一下，按下到抬起常常就是 100~200ms。
                    // 结果就是"点了没反应、还莫名其妙选了个词"，越用力按住越必然失败。
                    //
                    // 正确的判据是**有没有移动**，而不是按了多久：
                    // 桌面惯例里"按住不动再松开"仍然是点击。所以这里给一个实际到不了的
                    // 上限，让窗口只由"抬起"或"位移超过 touchSlop"结束。
                    // 触屏仍用系统长按超时——那是长按选词的正统判据，不能动。
                    val timeoutMs = if (isMouse) MOUSE_OBSERVE_NEVER_MS
                    else viewConfiguration.longPressTimeoutMillis
                    val finishedEarly = withTimeoutOrNull(timeoutMs) {
                        var pending = true
                        while (pending) {
                            val event = awaitPointerEvent()

                            // ⚠️ 滚轮/触控板双指滚动：立刻退出，一个都不消费 → 交还外层滚动。
                            //
                            // 只判 `changes.size > 1` 是不够的：触屏双指确实是两根手指，
                            // 但**触控板双指滚动会被系统合成成一个鼠标指针的滚轮事件**
                            // （PointerEventType.Scroll / scrollDelta），changes.size 仍是 1。
                            // 那种事件若不在这里放行，就会掉进下面的"鼠标拖拽 → 直接划选"分支，
                            // 表现为"双指上下滑变成了划词"（与聊天正文曾经的病因完全相同）。
                            if (event.isScrollLike()) {
                                pending = false
                            } else if (event.changes.size > 1) {
                                // 多指：立刻退出，且**一个都不消费** → 双指缩放照常工作
                                pending = false
                            } else {
                                val change = event.changes.firstOrNull { it.id == down.id }
                                when {
                                    change == null -> pending = false
                                    !change.pressed -> {
                                        if (!drifted) tapped = true
                                        // 抬手**一律消费**：否则同一个手势还会被外层
                                        // （滑动翻页 / 双击放大）再判一次，
                                        // 表现就是"刚选好的字被另一次判定冲掉"。
                                        change.consume()
                                        pending = false
                                    }
                                    (change.position - down.position).getDistance() >
                                        viewConfiguration.touchSlop -> {
                                        movedFar = true
                                        if (isMouse) {
                                            // 鼠标拖拽：直接划选
                                            drifted = false
                                            pending = false
                                        } else if (panHandoff) {
                                            pending = false
                                        } else {
                                            drifted = true
                                        }
                                    }
                                }
                            }
                        }
                    }
                    // 鼠标拖动或超时成立均算选词成立
                    val longPressed = (isMouse && !tapped) || (finishedEarly == null)

                    if (!longPressed) {
                        // ── ③ 点按 ────────────────────────────────────────────
                        if (tapped) {
                            // ⚠️ **先判链接**：链接的下划线与淡底已经画出来了，
                            // 但旧代码把这次点按只用于"清选区 / 交给宿主"，
                            // 全文件**没有任何一处调用 onQuoteLinkClick** ——
                            // 于是标识看得见、点下去毫无反应（用户原话"点不开"）。
                            //
                            // 命中用与长按同一套 hitBoxesAt（含 12dp 容差与"最近词盒"
                            // 退化命中）：手指点链接时压偏一两个像素很常见，
                            // 用精确 contains 会漏掉边缘上的字。
                            val tappedBoxes = hitBoxesAt(down.position)
                            val linkHit = linkBoxes.firstOrNull { (box, _) ->
                                tappedBoxes.any { it.id == box.id }
                            }
                            if (linkHit != null) {
                                selectedBoxes.clear()
                                toolbarPosition = null
                                dragStart = null
                                dragCurrent = null
                                settledBox = null
                                onClearSelection()
                                onQuoteLinkClick(linkHit.second)
                            } else {
                                // ---- 点按落在**词上** = 锁定这一个词 ----
                                //
                                // 这是鼠标/触控板最自然的取词方式：点一下就锁定，
                                // 不必"按住再拖"。此前这里只有"清选区 / 交给宿主"两条腿，
                                // 于是用鼠标点字只会把已有选区**清掉** ——
                                // 用户看到的正是"每个字都不能锁定、完全不持久化"。
                                val wordHit = hitBoxesAt(down.position)
                                    .map { refineToWord(it, down.position) }
                                if (wordHit.isNotEmpty()) {
                                    selectedBoxes.clear()
                                    selectedBoxes.addAll(wordHit)
                                    settledBox = wordHit.first().bounds
                                    dragStart = null
                                    dragCurrent = null
                                    toolbarPosition = toolbarAnchor(wordHit, barSize.height)
                                    // 注意：**不要**在这里回调 onClearSelection ——
                                    // 那条通路是"告诉宿主取消选区"，此刻我们恰恰是**建立**选区。
                                    // 工具条上的每个动作都会自己带上当前选中的文字，
                                    // 宿主不需要提前知道选了哪些字。
                                } else if (selectedBoxes.isNotEmpty()) {
                                    selectedBoxes.clear()
                                    toolbarPosition = null
                                    dragStart = null
                                    dragCurrent = null
                                    settledBox = null
                                    onClearSelection()
                                } else {
                                    settledBox = null
                                    // ⚠️ 鼠标没有"长按"这条路（桌面惯例里按住不动仍是点击），
                                // 所以用**按住时长**来区分：轻点 = 交给宿主（翻页/清标注），
                                // 按住一会儿再松 = 在空白处放标签。
                                // 判定顺序放在最后一条腿：链接与取消选区都判过了才轮到它，
                                // 不会抢掉已有的两条语义。
                                val held = android.os.SystemClock.uptimeMillis() - downTimeMs
                                val blank = hitBoxesAt(down.position).isEmpty()
                                if (blank && held >= TAG_HOLD_MS &&
                                    overlaySize.width > 0 && overlaySize.height > 0
                                ) {
                                    onAddTag(
                                        Offset(
                                            down.position.x / overlaySize.width,
                                            down.position.y / overlaySize.height
                                        )
                                    )
                                    } else {
                                        onTapBlank()
                                    }
                                }
                            }
                        }
                        return@awaitEachGesture
                    }

                    // ── ② 手柄微调期 ─────────────────────────────────────────
                    // 按在已有选框的角上 → 直接拖那个角，不重新框。
                    // 这是对话界面水滴手柄的等价物，让框选也能"框完再微调"。
                    //
                    // ⚠️ 长按选词（TALKTREE）也必须走这条路：手柄不该是"框选模式专属"。
                    // 长按选中之后用户最自然的下一步就是"边界差一个字，拖一下"，
                    // 而旧实现只认框选模式，长按选完抬手即丢框 → **一个手柄都看不到**，
                    // 想调整只能从头再长按一次，与对话界面的手感完全脱节。
                    val existing = settledBox
                    if (existing != null) {
                        val touch = 28.dp.toPx()
                        val corners = listOf(
                            existing.topLeft,
                            Offset(existing.right, existing.top),
                            existing.bottomRight,
                            Offset(existing.left, existing.bottom)
                        )
                        val hitIdx = corners.indexOfFirst { (it - down.position).getDistance() <= touch }
                        if (hitIdx >= 0) {
                            activeHandle = hitIdx
                            var moving = true
                            while (moving) {
                                val ev = awaitPointerEvent()
                                val ch = ev.changes.firstOrNull { it.id == down.id }
                                if (ev.changes.size > 1 || ch == null) {
                                    moving = false
                                } else if (!ch.pressed) {
                                    ch.consume()
                                    moving = false
                                } else {
                                    ch.consume()
                                    val c = corners[hitIdx]
                                    val p = ch.position
                                    // 只允许该角沿两轴各自移动，保持矩形不塌陷
                                    settledBox = when (hitIdx) {
                                        0 -> Rect(p.x, p.y, existing.right, existing.bottom)
                                        1 -> Rect(existing.left, p.y, p.x, existing.bottom)
                                        2 -> Rect(existing.left, existing.top, p.x, p.y)
                                        else -> Rect(p.x, existing.top, existing.right, p.y)
                                    }
                                    dragStart = settledBox?.topLeft
                                    dragCurrent = settledBox?.bottomRight
                                }
                            }
                            activeHandle = -1
                            // 手柄拖完，把新框内的词重新选中并刷新工具条位置
                            settledBox?.let { nb ->
                                // Compose 的 Rect 有 center（centerX/centerY 是
                                // android.graphics.Rect 才有的，别混用），contains 收 Offset
                                val inside = wordBoxes.filter { nb.contains(it.bounds.center) }
                                if (inside.isNotEmpty()) {
                                    selectedBoxes.clear()
                                    selectedBoxes.addAll(inside)
                                    toolbarPosition = toolbarAnchor(inside, barSize.height)
                                } else {
                                    // ⚠️ 框里一个字都不剩时**不要清掉已有选区**。
                                    // 拖手柄是"微调边界"，把它做成"清空"就是另一条
                                    // "选区凭空消失"的路径（用户会认为选区不持久）。
                                    // 这里只把框收回原来的范围。
                                    settledBox = boundsOf(selectedBoxes)
                                }
                            }
                            return@awaitEachGesture
                        }
                    }

                    // ── ③ 取词 / 框选期 ──────────────────────────────────────
                    //
                    // ⚠️ 两种模式的**手势语义在这里分叉**。此前它们完全一样
                    //（selectionMethod 只参与绘制、不参与判定），所以用户看到的
                    // 「条畅选词」与「电脑框选」是同一套东西 —— 这就是
                    // "条畅选词依然不行、两个模式没区别"的根因。
                    //
                    //  · 条畅选词（TALKTREE）：按下一个词 → 拖动 = 从锚点按**阅读顺序**
                    //    扩选（与对话界面拖手柄扩选同一语义），不画矩形；
                    //  · 电脑框选（BOX_DRAG）：画一个矩形，框住谁选谁（桌面截图工具手感）。
                    val isTalktree = selectionMethod == LiveSelectionMethod.TALKTREE
                    if (!isTalktree) {
                        dragStart = down.position
                        dragCurrent = down.position
                    } else {
                        dragStart = null
                        dragCurrent = null
                    }
                    toolbarPosition = null
                    // 长按成立的瞬间就把指头底下的词选上：这是"长按选词"最直接的
                    // 反馈 —— 高亮立即出现，抬起即弹工具条。旧实现先清空选区、
                    // 只在"移动"事件里做命中判定，于是按住不动就抬起时
                    // selectedBoxes 恒为空 → 既没高亮也没工具条，看起来像没选中。
                    // 把按压点细化到**词**：ML Kit 对汉字常把一整行只给一个 element，
                    // 不细化的话"按一下"永远是一整行（这正是"条畅选词选了句"的根因）。
                    val hit = hitBoxesAt(down.position).map { refineToWord(it, down.position) }

                    // ---- 长按在**空白处** = 在这儿放一个标签 ----
                    //
                    // 这是"摆脱对文字完全依赖"的入口：OCR 一个字都没认出来的地方
                    //（表格线、手写、竖排、旧版外文书的破格字），按住词选不中任何东西，
                    // 过去这一下就是白按。现在改成放一个"位置锚点"，
                    // 之后点它就能开小窗说事 —— 入口从"字"换成了"位置"。
                    if (hit.isEmpty()) {
                        if (!movedFar && overlaySize.width > 0 && overlaySize.height > 0) {
                            onAddTag(
                                Offset(
                                    down.position.x / overlaySize.width,
                                    down.position.y / overlaySize.height
                                )
                            )
                        }
                        return@awaitEachGesture
                    }

                    selectedBoxes.clear()
                    selectedBoxes.addAll(hit)
                    // 条畅选词的锚点：本次按下的那个词在**阅读顺序**里的下标。
                    // 后续拖动就按这个下标前后扩选，扩出来的永远是连续的一段正文，
                    // 不会像矩形那样把上一行末尾和下一行开头一起框进来。
                    val ordered = orderedReadingOrder(wordBoxes)
                    val anchorIdx = hit.firstOrNull()
                        ?.let { h -> ordered.indexOfFirst { it.id == h.id } }
                        ?: -1
                    // 首次命中词盒的并集：后续拖动的矩形以它为底向外扩张，
                    // 按压点附近的词不会因为矩形边线而闪失。
                    val hitRect: Rect? = run {
                        var acc: Rect? = null
                        for (box in hit) {
                            val r = box.bounds
                            val prev = acc
                            acc = if (prev == null) r else Rect(
                                left = minOf(prev.left, r.left),
                                top = minOf(prev.top, r.top),
                                right = maxOf(prev.right, r.right),
                                bottom = maxOf(prev.bottom, r.bottom)
                            )
                        }
                        acc
                    }

                    var finished = false
                    while (!finished) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id }
                        val pressedCount = event.changes.count { it.pressed }
                        if (pressedCount > 1) {
                            // 真的来了**第二根按下的手指**（用户要捏合缩放）→
                            // 放弃本次取词，交还外层。
                            selectedBoxes.clear()
                            dragStart = null
                            dragCurrent = null
                            toolbarPosition = null
                            finished = true
                        } else if (change == null) {
                            // ⚠️ 指针从本事件里消失了 —— 这**不是**"用户要取消"。
                            //
                            // 旧代码把 `change == null` 与"多了手指"合并成同一条
                            // "取消并清空"，而实际上它命中两种情况，鼠标尤其常见：
                            //   · 光标划出页卡边界（拖动选词时经常划出去）；
                            //   · 途中收到 Enter / Exit 这类**不含 change 的悬停事件**。
                            // 于是一松手（或光标一出界）刚选好的内容就被抹掉 ——
                            // 这就是"滑动选词不持久"的直接成因之一。
                            //
                            // 正确语义：把已经选中的内容**保留下来**并按"松手"处理。
                            if (selectedBoxes.isNotEmpty()) {
                                toolbarPosition = toolbarAnchor(selectedBoxes, barSize.height)
                                settledBox = boundsOf(selectedBoxes)
                            }
                            finished = true
                        } else if (!change.pressed) {
                            // 同上：抬手必须消费，否则会被外层再判成"点了一下页面"。
                            change.consume()
                            if (selectedBoxes.isNotEmpty()) {
                                toolbarPosition = toolbarAnchor(selectedBoxes, barSize.height)
                                // 记住这个框：抬手后仍可拖角微调（手柄态）。
                                // **长按选词与框选都要保留**：长按选中之后"差一个字、
                                // 拖一下边界"是最自然的下一步，只给框选保留就等于
                                // 长按路径永远没有手柄。
                                settledBox = boundsOf(selectedBoxes)
                            } else {
                                dragStart = null
                                dragCurrent = null
                            }
                            finished = true
                        } else if (isTalktree) {
                            // ---- 条畅选词：按阅读顺序从锚点扩选 ----
                            // 与对话界面"拖手柄扩选"是同一语义：起点固定、终点跟手，
                            // 中间所有词都进选区，扩出来的必然是连续正文。
                            change.consume()
                            val over = hitBoxesAt(change.position).firstOrNull()
                            val j = over?.let { b -> ordered.indexOfFirst { it.id == b.id } } ?: -1
                            if (j >= 0 && anchorIdx >= 0) {
                                val a = minOf(anchorIdx, j)
                                val b2 = maxOf(anchorIdx, j)
                                val slice = ordered.subList(a, b2 + 1).toMutableList()
                                // 两端换成"按压点细化后的子盒"，中间保持整盒：
                                // 于是"按住一个词往旁边拖"扩出来的边界正好落在词上，
                                // 而不是把起点那一行整行吞进去。
                                slice[anchorIdx - a] = refineToWord(ordered[anchorIdx], down.position)
                                slice[j - a] = refineToWord(ordered[j], change.position)
                                selectedBoxes.clear()
                                selectedBoxes.addAll(slice)
                            }
                        } else {
                            change.consume()
                            val current = change.position
                            dragCurrent = current
                            val s = dragStart ?: current
                            var selectionRect = Rect(
                                left = minOf(s.x, current.x),
                                top = minOf(s.y, current.y),
                                right = maxOf(s.x, current.x),
                                bottom = maxOf(s.y, current.y)
                            )
                            // 长按命中的词盒并进选区矩形：拖动只做"扩张"，
                            // 不会把按压点已选中的词丢掉。
                            val base = hitRect
                            if (base != null) {
                                selectionRect = Rect(
                                    left = minOf(selectionRect.left, base.left),
                                    top = minOf(selectionRect.top, base.top),
                                    right = maxOf(selectionRect.right, base.right),
                                    bottom = maxOf(selectionRect.bottom, base.bottom)
                                )
                            }
                            // 动态计算与判定框相交的 OCR 词盒
                            selectedBoxes.clear()
                            for (box in wordBoxes) {
                                if (selectionRect.overlaps(box.bounds)) {
                                    selectedBoxes.add(box)
                                }
                            }
                        }
                    }

                    // ---- 兜底：起手按在词上，就绝不允许以"空选区"收场 ----
                    //
                    // 拖动选词中途可能被各种原因打断（指针消失、走出边界、事件异常），
                    // 只要这一下**起手确实命中了词**，松手后就该有一个选区留着。
                    // 没有这层兜底，用户会遇到"明明拖过、松手什么都没了"。
                    if (selectedBoxes.isEmpty() && hit.isNotEmpty()) {
                        selectedBoxes.clear()
                        selectedBoxes.addAll(hit)
                        settledBox = boundsOf(hit)
                        toolbarPosition = toolbarAnchor(hit, barSize.height)
                    }
                }
            }
    ) {
        // Canvas 高性能绘制选中词条的高亮背景与指示器
        val marksByBox = remember(syntaxMarks) { syntaxMarks.associateBy { it.boxId } }
        Canvas(modifier = Modifier.fillMaxSize()) {
            val highlightColor = scheme.primary.copy(alpha = 0.28f)

            // ① 句法层级：命中词框铺一层层级淡底 + 下沿实线 + 左沿竖条。
            //    与文字路径（SyntaxUnitBlock）用**同一套**配色与同一套表达方式，
            //    两种阅读模式下"第几层"看起来是一致的。
            if (marksByBox.isNotEmpty()) {
                for (box in wordBoxes) {
                    val mark = marksByBox[box.id] ?: continue
                    val hue = syntaxLevelHue(scheme, mark.level)
                    drawRoundRect(
                        color = syntaxLevelFill(scheme, mark.level),
                        topLeft = box.bounds.topLeft,
                        size = box.bounds.size,
                        cornerRadius = CornerRadius(3.dp.toPx(), 3.dp.toPx()),
                        style = Fill
                    )
                    val bottom = box.bounds.bottom
                    val left = box.bounds.left
                    drawLine(
                        color = hue,
                        start = Offset(left, bottom),
                        end = Offset(box.bounds.right, bottom),
                        strokeWidth = 1.5.dp.toPx()
                    )
                    drawRect(
                        color = hue,
                        topLeft = Offset(left - 2.dp.toPx(), box.bounds.top),
                        size = androidx.compose.ui.geometry.Size(
                            3.dp.toPx(),
                            box.bounds.height
                        )
                    )
                }
            }

            // ② 条畅选词（文字层）的选区：**逐行一条**，能精确到半个词。
            //
            // 与对话界面同一种画法：先把同一行里连续的字合并成一条横条，
            // 再在两端各画一只水滴手柄。盒模型画不出这两样东西 ——
            // 它只有"整块高亮 + 四个外接角"。
            val sel = charSel
            if (sel != null && textLayer != null) {
                for (r in textLayer.rectsFor(sel)) {
                    drawRoundRect(
                        color = highlightColor,
                        topLeft = r.topLeft,
                        size = r.size,
                        cornerRadius = CornerRadius(3.dp.toPx(), 3.dp.toPx()),
                        style = Fill
                    )
                }
                textLayer.handleCenters(sel)?.let { (startHandle, endHandle) ->
                    val dropR = 7.dp.toPx()
                    listOf(startHandle, endHandle).forEach { c ->
                        // 水滴：一个实心圆 + 一段向上的细颈
                        drawLine(
                            color = scheme.primary,
                            start = c,
                            end = Offset(c.x, c.y - dropR * 1.9f),
                            strokeWidth = 2.dp.toPx(),
                            cap = androidx.compose.ui.graphics.StrokeCap.Round
                        )
                        drawCircle(scheme.primary, dropR, c)
                        drawCircle(Color.White, dropR * 0.42f, c)
                    }
                }
            }

            // ②' 选区高亮（盒模型；画在句法底色之上，保证交互反馈始终可见）
            for (box in selectedBoxes) {
                // 绘制柔和圆角矩形高亮条
                drawRoundRect(
                    color = highlightColor,
                    topLeft = box.bounds.topLeft,
                    size = box.bounds.size,
                    cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx()),
                    style = Fill
                )
            }

            // 同文本超链接：实下划线 + 极淡提亮。
            //
            // 画在**选区高亮之下、查找高亮之上**：选区是临时的（会盖住它），
            // 查找高亮也是临时的，但两者都不该长期把链接特征糊掉。
            //
            // 用下划线而不是描边/底色：扫描纸面底色不定（白纸、黄纸、旧书），
            // 描边与底色会和纸色糊在一起认不出，而"字下有一条横线"在任何底色上
            // 都是明确的形状信号 —— 这也是网页可点文本最通用的表达。
            linkBoxes.forEach { (box, _) ->
                val r = box.bounds
                // 极淡提亮：只提亮度不染色，保证黑字更实、白纸不刺眼
                drawRoundRect(
                    color = Color(0x33000000),
                    topLeft = r.topLeft,
                    size = r.size,
                    cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx())
                )
                // 下划线
                drawLine(
                    color = Color(0xFF1B6FE0),
                    start = Offset(r.left + 1f, r.bottom - 1.5.dp.toPx()),
                    end = Offset(r.right - 1f, r.bottom - 1.5.dp.toPx()),
                    strokeWidth = 1.8.dp.toPx(),
                    cap = androidx.compose.ui.graphics.StrokeCap.Round
                )
            }

            // 页内查找命中：琥珀色描边 + 淡底，画在选区高亮之上，保证可见。
            // 当前定位的那一处加粗并填更实，便于"上一处/下一处"时一眼找到。
            findMatches.forEachIndexed { index, box ->
                val active = index == findActiveIndex.coerceIn(
                    0,
                    (findMatches.size - 1).coerceAtLeast(0)
                )
                drawRoundRect(
                    color = Color(0xFFFFB300).copy(alpha = if (active) 0.40f else 0.18f),
                    topLeft = box.bounds.topLeft,
                    size = box.bounds.size,
                    cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx()),
                    style = Fill
                )
                drawRoundRect(
                    color = Color(0xFFFFB300),
                    topLeft = box.bounds.topLeft,
                    size = box.bounds.size,
                    cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx()),
                    style = Stroke(width = if (active) 2.5.dp.toPx() else 1.dp.toPx())
                )
            }

            // ---- 微型小窗标签：位置锚点，不依赖识别出的文字 ----
            //
            // 画在选区与链接**之外**、所有高亮**之上**：它是最"实"的一层，
            // 必须始终看得见（拖选时也不该被半透明高亮糊掉）。
            // 形状取"实心圆 + 白描边 + 序号"，和系统地图上的图钉是同一类表达：
            // 圆形在任意纸面上都不会和正文笔画混淆，序号又支持口头指认（"第 3 个"）。
            pageTags.forEach { tag ->
                val c = Offset(tag.x * size.width, tag.y * size.height)
                drawCircle(Color(0xFF1B6FE0).copy(alpha = 0.92f), TAG_RADIUS_DP * density, c)
                drawCircle(
                    Color.White,
                    TAG_RADIUS_DP * density,
                    c,
                    style = Stroke(width = 1.5f * density)
                )
            }

            // 选框与四角手柄：**长按选词与框选都画**。
            //
            // 之前这里挂着 `selectionMethod == BOX_DRAG`，于是默认的「条畅选词」
            // 一个手柄都看不到 —— 而手柄正是"选完还能拖边界"的唯一入口，
            // 缺了它就只能整段重选，和对话界面的手感完全不一致。
            val start = dragStart
            val cur = dragCurrent
            // 已抬起手指但选框还在 → 进入「手柄微调」态。
            //
            // 为什么要有这一态：框选是"按住拖 → 松手"的一次性手势，
            // 框完就没了，用户想微调边界只能再从头框一次 ——
            // 而对话界面那套"水滴手柄"正是为了解决这个，两边手感不一致。
            val settled = settledBox
            if (charSel == null &&
                ((start != null && cur != null && start != cur) || settled != null)
            ) {
                val r: Rect = settled ?: Rect(
                    left = minOf(start!!.x, cur!!.x),
                    top = minOf(start.y, cur.y),
                    right = maxOf(start.x, cur.x),
                    bottom = maxOf(start.y, cur.y)
                )
                drawRoundRect(
                    color = scheme.secondary.copy(alpha = 0.16f),
                    topLeft = r.topLeft,
                    size = r.size,
                    cornerRadius = CornerRadius(6.dp.toPx(), 6.dp.toPx())
                )
                // 描一圈边：只靠 12%~16% 的淡底，浅色纸面背景上几乎看不出来，
                // 用户会以为"框选没生效"。
                drawRoundRect(
                    color = scheme.secondary.copy(alpha = 0.65f),
                    topLeft = r.topLeft,
                    size = r.size,
                    cornerRadius = CornerRadius(6.dp.toPx(), 6.dp.toPx()),
                    style = Stroke(width = 1.dp.toPx())
                )
                // 四角手柄：与对话界面那套水滴手柄同一套交互 ——
                // 框完后不必重新框，拖角即可微调边界。
                val handleR = 9.dp.toPx()
                val corners = listOf(r.topLeft, Offset(r.right, r.top), r.bottomRight, Offset(r.left, r.bottom))
                corners.forEachIndexed { idx, c ->
                    drawCircle(scheme.secondary.copy(alpha = 0.95f), handleR, c)
                    drawCircle(Color.White, handleR * 0.42f, c)
                    if (activeHandle == idx) {
                        drawCircle(scheme.secondary.copy(alpha = 0.35f), handleR * 1.7f, c)
                    }
                }
            }
        }

        // 句法角色小标签：挂在每个单元命中的**第一个词框**上方。
        // 用绝对定位的 Text 而不是 Canvas 里画字：不需要引入 TextMeasurer，
        // 且这些标签没有手势修饰符，指针事件照旧落到下面的选词手势面。
        if (marksByBox.isNotEmpty()) {
            val boxById = remember(wordBoxes) { wordBoxes.associateBy { it.id } }
            syntaxMarks.forEach { mark ->
                if (!mark.showRoleLabel) return@forEach
                val box = boxById[mark.boxId] ?: return@forEach
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = scheme.surfaceContainerHighest,
                    border = BorderStroke(1.dp, syntaxLevelHue(scheme, mark.level)),
                    modifier = Modifier.offset {
                        IntOffset(
                            box.bounds.left.toInt(),
                            (box.bounds.top - 16.dp.toPx()).toInt().coerceAtLeast(0)
                        )
                    }
                ) {
                    Text(
                        text = "L${mark.level} ${mark.role}",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = syntaxLevelHue(scheme, mark.level),
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                    )
                }
            }
        }

        // ---- 考纲生词下划线：命中词盒底部一条 2dp 色条 ----
        //
        // 不进 Canvas（省掉 TextMeasurer 与逐字几何），与句法角色标签同一策略：
        // 绝对定位的 Box，位置取词盒的 bounds；不加手势修饰符，
        // 指针事件照旧落到下面的选词手势面。
        if (vocabBoxIds.isNotEmpty()) {
            val boxById = remember(wordBoxes) { wordBoxes.associateBy { it.id } }
            val density = LocalDensity.current
            vocabBoxIds.forEach { id ->
                val box = boxById[id] ?: return@forEach
                Box(
                    modifier = Modifier
                        .offset {
                            IntOffset(
                                box.bounds.left.toInt(),
                                (box.bounds.bottom - 3.dp.toPx()).toInt().coerceAtLeast(0)
                            )
                        }
                        .width(with(density) { box.bounds.width.toDp() })
                        .height(2.dp)
                        .background(OCR_VOCAB_COLOR, RoundedCornerShape(1.dp))
                )
            }
        }

        // ---- 手势轨迹：画在左上角，复现一次就能定死问题 ----
        //
        // 选词这块已经来回好几轮了，纯靠读代码猜根因代价太高 ——
        // 而真实原因取决于设备、输入设备、识别结果，只有现场日志说了算。
        // 这一行会直接告诉：按在哪、命中第几个字、选区扩到哪、或者为什么没生效。
        trace?.let { t ->
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = scheme.scrim.copy(alpha = 0.82f),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp)
            ) {
                Text(
                    text = t,
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onPrimary,
                    maxLines = 3,
                    modifier = Modifier
                        .widthIn(max = 420.dp)
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }

        // ---- 微型小窗标签的序号：与句法角色标签一样用绝对定位 Text ----
        //
        // 不画在 Canvas 里（那要引入 TextMeasurer 才认字），直接用 Text 叠上去：
        // 文字随字号/语言自动排版，且这里**不加手势修饰符** ——
        // 指针事件照旧落到下面的手势面，点标签由手势面统一判定，
        // 不会因为"标签自己也是可点组件"而产生两套命中逻辑。
        pageTags.forEach { tag ->
            Surface(
                shape = CircleShape,
                color = Color(0xFF1B6FE0),
                border = BorderStroke(1.5.dp, Color.White),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset {
                        IntOffset(
                            (tag.x * overlaySize.width).toInt() - TAG_RADIUS_DP.dp.roundToPx(),
                            (tag.y * overlaySize.height).toInt() - TAG_RADIUS_DP.dp.roundToPx()
                        )
                    }
            ) {
                Text(
                    text = tag.label.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    modifier = Modifier
                        .width(TAG_RADIUS_DP.dp * 2)
                        .padding(vertical = 1.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        }

        // 浮动操作栏（Material 3 原生微圆角浮层）
        val pos = toolbarPosition
        // 选区下沿换算到屏幕坐标：顶部放不下时把工具条翻到这下面（见 offset 里的说明）。
        val belowY = remember(selectedBoxes, charSel, overlaySize, containerScale, containerPan) {
            val z = if (containerScale > 0.01f) containerScale else 1f
            val cx0 = overlaySize.width / 2f
            val cy0 = overlaySize.height / 2f
            val byLocal = charSel?.let { textLayer?.boundsFor(it)?.bottom }
                ?: if (selectedBoxes.isEmpty()) null else selectionBottom(selectedBoxes)
            if (byLocal == null) 0f else (byLocal - cy0) * z + cy0 + containerPan.y
        }
        AnimatedVisibility(
            visible = pos != null && (charSel != null || selectedBoxes.isNotEmpty()),
            enter = fadeIn() + scaleIn(initialScale = 0.9f),
            exit = fadeOut() + scaleOut(targetScale = 0.9f),
            modifier = Modifier
                .offset {
                    val p = pos
                    val w = overlaySize.width.toFloat()
                    val h = overlaySize.height.toFloat()
                    when {
                        p == null -> IntOffset.Zero
                        // 尚未量到尺寸时退回旧口径（首帧兜底，尺寸一到立即换新算法）
                        w <= 0f || h <= 0f ->
                            IntOffset((p.x - 168f).toInt().coerceAtLeast(0), p.y.toInt().coerceAtLeast(0))
                        else -> {
                            val z = if (containerScale > 0.01f) containerScale else 1f
                            // ① 选区锚点在屏幕上的真实位置：容器 graphicsLayer
                            //    先绕中心缩放、再平移（Android 层级矩阵的既定顺序）。
                            val cx = w / 2f
                            val cy = h / 2f
                            val sx = (p.x - cx) * z + cx + containerPan.x
                            val sy = (p.y - cy) * z + cy + containerPan.y
                            // ② 期望的工具条左上角（屏幕像素）：沿用旧口径「锚点让开 168px」，
                            //    再按工具条实测尺寸钳进可视区。旧版既不换算也不钳 ——
                            //    锚点是内容本地像素，被 zoom 再乘一遍后直接飞出屏幕，
                            //    表现为"选中了却看不到气泡"。
                            var dx = sx - 168f
                            var dy = sy
                            dx = dx.coerceAtLeast(8f)
                            dy = dy.coerceAtLeast(8f)
                            if (barSize.width > 0) {
                                dx = dx.coerceAtMost(maxOf(8f, w - barSize.width - 8f))
                            }
                            if (barSize.height > 0) {
                                dy = dy.coerceAtMost(maxOf(8f, h - barSize.height - 8f))
                            }
                            // ⚠️ 顶部空间不够时（选中了最上面一行），工具条被 dy 的
                            // 下钳压回 8px，正好盖在选中的字上 —— 又变成"工具条挡字"。
                            //
                            // 这里**翻到选区下方**而不是与文字重叠：横向拖动已经交给
                            // 滑动翻页，所以下方遮挡不影响操作，用户却能看清自己选的字。
                            // 需要拿到选区下沿；拿不到（旧锚点没带）就不翻，保持原行为。
                            if (belowY > 0f && dy < 8f && sy < 8f + barSize.height) {
                                dy = belowY
                                dy = dy.coerceAtMost(maxOf(8f, h - barSize.height - 8f))
                            }
                            // ③ 反算回容器本地坐标：本节点自己的反向缩放以左上角为轴，
                            //    只改尺寸不改位置，所以这里算出的本地坐标就是最终落点。
                            val lx = (dx - cx - containerPan.x) / z + cx
                            val ly = (dy - cy - containerPan.y) / z + cy
                            IntOffset(lx.roundToInt(), ly.roundToInt())
                        }
                    }
                }
                // 反向缩放：容器放大 N 倍时工具条缩回 1/N，屏幕上看仍是正常大小。
                // transformOrigin 取左上角，保证锚点（选区上方）不因反向缩放而漂移。
                .graphicsLayer {
                    val inverse = if (containerScale > 0.01f) 1f / containerScale else 1f
                    scaleX = inverse
                    scaleY = inverse
                    transformOrigin = TransformOrigin(0f, 0f)
                }
        ) {
            val fullText = charSel
                ?.let { textLayer?.textOf(it)?.trim() }
                ?.takeIf { it.isNotEmpty() }
                ?: selectedBoxes.joinToString(" ") { it.text }
            LiveTextActionBar(
                // 实测工具条尺寸：②里的可视区钳制要靠它知道右/下边界在哪。
                modifier = Modifier.onSizeChanged { barSize = it },
                fullText = fullText,
                syntaxActive = syntaxActive,
                onAskQuestion = {
                    toolbarPosition = null
                    onAskQuestion(fullText)
                },

                onOpenEtymology = {
                    toolbarPosition = null
                    onOpenEtymology(fullText)
                },
                onTranslate = onTranslate?.let { callback ->
                    {
                        toolbarPosition = null
                        callback(fullText)
                    }
                },
                onAnalyzeSyntax = onAnalyzeSyntax?.let { callback ->
                    {
                        toolbarPosition = null
                        callback(fullText)
                    }
                },
                onClearSyntax = {
                    toolbarPosition = null
                    onClearSyntax()
                }
            )
        }
    }
}

/**
 * 实况选词工具条（提问 / 划线 / 笔记 / 词源·音标 / 翻译 / 句法 / 复制）。
 *
 * 抽成独立 composable 是因为现在有两个宿主都用它：
 * 位图路径的浮层（[LiveTextComposeOverlay]）与原生文字层
 * （[NativeSelectionTextLayer]）。两处各写一份必然会在调整图标时走偏。
 */
@Composable
internal fun LiveTextActionBar(
    fullText: String,
    onAskQuestion: () -> Unit,

    onOpenEtymology: () -> Unit,
    /** 为 null 时不显示「翻译」（内置浏览器没有模型通路，不该出现死按钮）。 */
    onTranslate: (() -> Unit)? = null,
    /** 为 null 时不显示「句法」。 */
    onAnalyzeSyntax: (() -> Unit)? = null,
    syntaxActive: Boolean = false,
    onClearSyntax: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    val clipboardManager = LocalClipboardManager.current
    // 工具条动作变多、窄屏上会超出屏幕：横向可滚动 + 最大宽度约束，
    // 保证每个动作都能被点到（而不是被挤出屏幕点不到）。
    val maxBarWidth = (LocalConfiguration.current.screenWidthDp.dp - 24.dp).coerceAtLeast(160.dp)
    val translateAction = onTranslate
    val analyzeSyntaxAction = onAnalyzeSyntax

    Surface(
        modifier = modifier.widthIn(max = maxBarWidth),
        shape = RoundedCornerShape(20.dp),
        color = scheme.surfaceContainerHighest,
        contentColor = scheme.onSurface,
        shadowElevation = 8.dp,
        tonalElevation = 6.dp,
        border = androidx.compose.foundation.BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.6f))
    ) {
        Row(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 1. 提问 (调起 TalkTree 小窗)
            IconButton(onClick = onAskQuestion) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.Chat,
                    contentDescription = "针对选文提问",
                    tint = scheme.primary,
                    modifier = Modifier.padding(2.dp)
                )
            }

            // 划线/笔记按钮已按用户要求整体移除（2026-10-17）：
            // 读书笔记采集只保留手写批注层；已有划线在右侧边栏「注释」页可见。

            // 3. 跨语言历史拟音与词源
            IconButton(onClick = onOpenEtymology) {
                Icon(
                    painter = painterResource(com.treechat.workbench.R.drawable.ic_ip_chinese),
                    contentDescription = "查看三语国际音标与词源",
                    tint = scheme.primary,
                    modifier = Modifier.padding(2.dp)
                )
            }

            // 5. 选句翻译（流式译文落在悬浮小窗里，不阻塞阅读）
            if (translateAction != null) {
                IconButton(onClick = translateAction) {
                    Icon(
                        imageVector = Icons.Outlined.Language,
                        contentDescription = "选句翻译",
                        tint = scheme.primary,
                        modifier = Modifier.padding(2.dp)
                    )
                }
            }

            // 6. 句法嵌套切片并标注在原文上
            if (analyzeSyntaxAction != null) {
                IconButton(onClick = analyzeSyntaxAction) {
                    Icon(
                        imageVector = Icons.Outlined.AccountTree,
                        contentDescription = "句法嵌套切片并标注",
                        tint = scheme.secondary,
                        modifier = Modifier.padding(2.dp)
                    )
                }
            }

            // 7. 清除句法标注（只在确实有标注时出现）
            if (syntaxActive) {
                IconButton(onClick = onClearSyntax) {
                    Icon(
                        imageVector = Icons.Outlined.Clear,
                        contentDescription = "清除句法标注",
                        tint = scheme.error,
                        modifier = Modifier.padding(2.dp)
                    )
                }
            }

            // 8. 复制
            IconButton(onClick = {
                clipboardManager.setText(AnnotatedString(fullText))
            }) {
                Icon(
                    imageVector = Icons.Outlined.ContentCopy,
                    contentDescription = "复制文本",
                    tint = scheme.onSurfaceVariant,
                    modifier = Modifier.padding(2.dp)
                )
            }
        }
    }
}

/**
 * 工具条锚点：选区最上方的水平中心，向上让开 50px。
 *
 * 抽成函数是因为现在有两个入口都要算它（拖拽结束、autoSelect 直接弹出），
 * 两处各写一份必然会在调整间距时走偏。
 */
/**
 * 工具条锚点：默认放在选区**正上方**。
 *
 * ## 为什么上移量必须由工具条**实测高度**决定，不能写死
 * 旧实现固定上移 50px，可工具条实际高度（按钮行 + 内边距 + 描边）常常超过 50px，
 * 于是工具条的上沿正好压在选中文字上面 —— 表现就是"一选词，工具条就把字挡住"。
 * 那个 50px 是拍脑袋的经验值，工具条改版一次就失效。
 *
 * @param barHeightPx 工具条实测高度（尚未量到时传 0，退回一个偏保守的默认值）。
 * @param gapPx 工具条与文字之间的额外间距，保证不"贴脸"。
 */
/** 由选区矩形求工具条锚点（与 [toolbarAnchor] 同一口径，只是输入换成矩形）。 */
private fun anchorForRect(r: Rect, barHeightPx: Int = 0): Offset {
    val barHeight = if (barHeightPx > 0) barHeightPx.toFloat() else 56f
    return Offset(r.center.x, (r.top - barHeight - 10f).coerceAtLeast(10f))
}

private fun toolbarAnchor(boxes: List<OcrWordBox>, barHeightPx: Int = 0): Offset {
    val minTop = boxes.minOf { it.bounds.top }
    val centerX = (boxes.minOf { it.bounds.left } + boxes.maxOf { it.bounds.right }) / 2f
    // 量到实测高度就用它；首帧还没量到时用 56px 兜底（宁可偏高也别压字）。
    val barHeight = if (barHeightPx > 0) barHeightPx.toFloat() else 56f
    return Offset(centerX, (minTop - barHeight - 10f).coerceAtLeast(10f))
}

/**
 * 桌面级（鼠标/触控板）观察期的"实际到不了"上限。
 *
 * 语义是"只由抬起或位移来结束观察期，不由时间结束"。取一小时而不是 Long.MAX_VALUE：
 * `withTimeoutOrNull` 内部要把毫秒换算成纳秒，极大值有溢出风险，而一小时足够
 * 覆盖任何真人按住不动的情形。
 */
private const val MOUSE_OBSERVE_NEVER_MS = 3_600_000L

/** 微型小窗标签的半径（dp）。取 11dp：拇指按得准，又不至于糊住正文。 */
private const val TAG_RADIUS_DP = 11f

/** 点标签的命中半径（dp），比画出来的半径大一圈，按压偏一点也能中。 */
private const val TAG_HIT_DP = 18f

/** 按住空白多久算「我要在这儿放个标签」（毫秒）。 */
private const val TAG_HOLD_MS = 450L

/**
 * 把一次按压细化到**一个词**，与词盒本身的粗细无关。
 *
 * 为什么必须做：ML Kit 对中日韩文字常常把**一整行**只给一个 element
 * （汉字之间没有空格，它没有可靠的切分依据）。于是"按一下选一个词"这件事
 * 在盒级别根本无从谈起 —— 无论怎么调容差，选中的都是一整行。
 *
 * 做法：按落点在盒内的**横向比例**换算成字符下标，再用
 * [MessageSelectionController.wordBounds]（西文取整串、汉字走 ICU 词典分词）
 * 求出该下标所在的词，最后按字符比例把盒子裁成子盒。
 * 于是"按哪个词就选哪个词"，与识别结果的切分粒度解耦。
 *
 * 保留原 id：链接命中与扩选回查都按 id 认原盒，裁窄的只是可视范围与文本。
 */
private fun refineToWord(box: OcrWordBox, at: Offset): OcrWordBox {
    val t = box.text
    if (t.length <= 1) return box
    val w = box.bounds.width
    if (w <= 1f) return box
    val frac = ((at.x - box.bounds.left) / w).coerceIn(0f, 0.9999f)
    val idx = (frac * t.length).toInt().coerceIn(0, t.length - 1)
    val range = runCatching {
        MessageSelectionController.wordBounds(t, idx)
    }.getOrNull() ?: return box
    val (start, end) = range
    if (end <= start || (start == 0 && end >= t.length)) return box
    return box.copy(
        text = t.substring(start, end),
        bounds = Rect(
            left = box.bounds.left + w * start / t.length,
            right = box.bounds.left + w * end / t.length,
            top = box.bounds.top,
            bottom = box.bounds.bottom
        )
    )
}

/**
 * 把词盒按**阅读顺序**排成一维序列：先按纵坐标聚成行（同行判据取行高 60%，
 * 与 `LiveTextPageView.pageText` 同一套），行内再按横坐标从左到右。
 *
 * 条畅选词的"扩选"是按这个顺序取连续区间，而不是按几何矩形 ——
 * 矩形扩选在双栏、表格、带注释行的版面上会把不该选的词一起框进来。
 */
private fun orderedReadingOrder(boxes: List<OcrWordBox>): List<OcrWordBox> =
    groupOcrLines(boxes).flatten()

/** 同行判据的唯一来源：纵坐标差不超过较高行高的 60%，行内按横坐标从左到右。 */
private fun groupOcrLines(boxes: List<OcrWordBox>): List<List<OcrWordBox>> {
    if (boxes.isEmpty()) return emptyList()
    val sorted = boxes.sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }))
    val lines = ArrayList<MutableList<OcrWordBox>>()
    for (box in sorted) {
        val line = lines.lastOrNull()
        val anchor = line?.firstOrNull()
        val sameLine = anchor != null &&
            kotlin.math.abs(box.bounds.top - anchor.bounds.top) <=
            maxOf(box.bounds.height, anchor.bounds.height) * 0.6f
        if (sameLine) line?.add(box) else lines.add(mutableListOf(box))
    }
    return lines.map { it.sortedBy { b -> b.bounds.left } }
}

/**
 * 找出应标为"同文本超链接"的 OCR 词盒，返回顺序与 [wordBoxes] 一致。
 *
 * 两条腿：
 * 1. 词盒自身文本（≥2 字）出现在某条引用原文中 —— 西文与成块识别出的中文走这条。
 * 2. **同一行内相邻词盒拼成的串**出现在引用原文中 —— ML Kit 对汉字常常逐字切盒，
 *    单字永远过不了第 1 条的 2 字下限，于是中文扫描件上的链接整条都标不出来、
 *    也点不了。拼接时**不补盒间空格**：汉字之间本无空格，补了反而拼不出引用原文。
 *    只有整段拼接落在引用原文内才命中，所以孤立的单字不会被误标成链接。
 */
/**
 * 在 OCR 页面上找出"被分支引用过"的词盒。
 *
 * ## 为什么必须在**文字层**上匹配
 *
 * 选区文本来自 [OcrTextLayer.text]（行间有 `\n`、西文词间有空格），
 * 而旧实现在这里**另拼了一套**——按行把词盒首尾相连、不带任何分隔符。
 * 两套拼法不同源，于是凡是**跨词或跨行**的引用都匹配不上；
 * 再叠加"逐词兜底"那条要求词长 ≥ 2 的判据，中文被逐字切盒时整条判据直接失效。
 * 结果就是用户看到的"提问后页面上一点标记都没有"。
 *
 * 现在改成：直接用 [layer] 的 [OcrTextLayer.text] 找，
 * 与选区**同一个字符串**，再经 [OcrTextLayer.boxIndexesIn] 映射回词盒。
 *
 * ## 为什么要忽略空白
 *
 * 文字层会在西文之间补空格，而引用文本可能来自别的拼接路径（框选、复制、
 * 模型回显），空白未必逐字一致。两侧都**剔除所有空白**再比较，
 * 下标映射回原文，保证命中的是真正的词。
 */
private fun findOcrLinkBoxes(
    wordBoxes: List<OcrWordBox>,
    layer: OcrTextLayer?,
    quoteLinkTargets: List<com.treechat.workbench.ui.selection.QuoteLinkTarget>
): List<Pair<OcrWordBox, com.treechat.workbench.data.model.ConversationNode>> {
    if (quoteLinkTargets.isEmpty() || wordBoxes.isEmpty()) return emptyList()
    if (layer == null || layer.isEmpty) return emptyList()

    // 剔除空白后的层文本 + 回原文下标映射
    val layerText = layer.text
    val strippedToOrig = IntArray(layerText.length + 1)
    val stripped = StringBuilder(layerText.length)
    var n = 0
    layerText.forEachIndexed { i, c ->
        if (!c.isWhitespace()) {
            stripped.append(c)
            strippedToOrig[n] = i
            n++
        }
    }
    strippedToOrig[n] = layerText.length
    val hay = stripped.toString()

    val matched = LinkedHashMap<String, com.treechat.workbench.data.model.ConversationNode>()
    quoteLinkTargets.forEach { target ->
        val needle = target.quote.filterNot { it.isWhitespace() }
        if (needle.length < MIN_OCR_LINK_LENGTH) return@forEach
        var from = 0
        while (from <= hay.length - needle.length) {
            val at = hay.indexOf(needle, from, ignoreCase = true)
            if (at < 0) break
            val startOrig = strippedToOrig[at]
            val endOrig = strippedToOrig[at + needle.length - 1]
            // 映射回词盒：只标真正承载文字的盒，插入的分隔符（-1）自动跳过
            layer.boxIndexesIn(startOrig..endOrig).forEach { boxIdx ->
                wordBoxes.getOrNull(boxIdx)?.let { box ->
                    matched.putIfAbsent(box.id, target.node)
                }
            }
            from = at + 1
        }
    }

    return wordBoxes.mapNotNull { b -> matched[b.id]?.let { b to it } }
}

/**
 * 参与 OCR 链接标记的最小字符数（剔除空白后）。
 *
 * 取 2 是为了不把单个字/单个数字标成链接：一个孤立的"1"在书页里遍地都是，
 * 标出来只会让用户以为是可点的。
 */
private const val MIN_OCR_LINK_LENGTH = 2

/** 考纲生词下划线的颜色（正文页与 OCR 页同色：玫红 300）。 */
internal val OCR_VOCAB_COLOR = androidx.compose.ui.graphics.Color(0xFFE57373)

/** 一组词盒的并集外框；空集合返回 null。 */
private fun boundsOf(boxes: List<OcrWordBox>): Rect? {
    var acc: Rect? = null
    for (b in boxes) {
        val r = b.bounds
        val prev = acc
        acc = if (prev == null) r else Rect(
            minOf(prev.left, r.left),
            minOf(prev.top, r.top),
            maxOf(prev.right, r.right),
            maxOf(prev.bottom, r.bottom)
        )
    }
    return acc
}

/** 选区下沿（屏幕/内容坐标）：工具条无处安放时改放它下面。 */
private fun selectionBottom(boxes: List<OcrWordBox>): Float =
    boxes.maxOf { it.bounds.bottom }

/**
 * 这次指针事件是不是**滚动/滚轮**类（触控板双指、鼠标滚轮、外接滚轮）。
 *
 * 与 `ui.selection.SelectableMarkdown` 里的同名判断是**同一套判据**，
 * 两处各自私有：它们分属不同的手势实现（这里是 OCR 图层的框选/划词，
 * 那里是正文的选词），没有共享的手势基类可挂。判据若将来要改，两处一起改。
 *
 * 为什么不能只判 `changes.size > 1`：触屏双指确实是两个 pointer，
 * 但触控板双指滚动被系统合成成**一个鼠标指针的滚轮事件**，changes.size 仍是 1。
 */
private fun PointerEvent.isScrollLike(): Boolean {
    if (type == PointerEventType.Scroll) return true
    return changes.any { it.scrollDelta != androidx.compose.ui.geometry.Offset.Zero }
}
