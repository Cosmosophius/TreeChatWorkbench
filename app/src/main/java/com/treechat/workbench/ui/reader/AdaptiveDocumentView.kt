package com.treechat.workbench.ui.reader

import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.automirrored.outlined.NavigateBefore
import androidx.compose.material.icons.automirrored.outlined.NavigateNext
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.treechat.workbench.etymology.data.VocabIndex
import com.treechat.workbench.data.model.ConversationNode
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import com.treechat.workbench.ocr.OcrEngine
import com.treechat.workbench.ocr.PdfOcr
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.treechat.workbench.ocr.PdfPageSource
import com.treechat.workbench.ui.selection.FloatingSelectionToolbar
import com.treechat.workbench.ui.selection.MessageSelectionController
import com.treechat.workbench.ui.selection.SelectableMarkdownText
import com.treechat.workbench.ui.selection.SelectionScheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * 双击判定窗口（毫秒）：300ms 内的第二次点按才算双击 —— 与系统双击阈值同一量级。
 * 也用作"刚清完选区/标注"之后的静默窗，避免一次手快被算成两次动作。
 */
private const val DOUBLE_TAP_WINDOW_MS = 300L

/**
 * 彻底重构的纯 Material 3 原生自适应文档阅读画卷 (AdaptiveDocumentView)
 *
 * 核心升级：
 * 1. 【普通文本坚决纯单页】：TXT / Word / PPT / Markdown / EPUB 保持绝对单页沉浸流；
 * 2. 【扫描 PDF 专属多列】：只有扫描件/PDF 才允许在外部通过 [单页/双页/三页] 切换列数；
 * 3. 【彻底干掉系统纯复制菜单】：自建原生悬浮选择栏（提问、复制、词源·音标、划线、笔记），严禁 emoji；
 * 4. 【沉浸全屏手势】：轻触正文中央自由显隐大标题与控制栏，享受极致沉浸；
 * 5. 【真实分页驱动】：接入 DocumentFileReader 真分页数据，上一页/下一页真实翻页；
 * 6. 【选句翻译与句法标注】：工具条上的「翻译」把选中段落交给当前模型（本地 NPU
 *    或云端，同一条通路）译成目标语言，流式结果落在悬浮小窗里；「句法」让模型返回
 *    层级化句法单元的 JSON，解析成功后**按层级标注在原文/词框上**，失败则如实
 *    报错并原样展示模型输出（见 [SentenceToolState] / [SyntaxAnnotatedText]）。
 * 6. 【翻页交互】正文区左右 1/3 点按翻页（沉浸态下也翻得动）、中间区**双击**才切沉浸、
 *    方向键/翻页键/Esc 走键盘、横向拖动播 [PageTurnMode] 三档动画里的跟手动画。
 *    这四条共用一个点按路由器（见 [dispatchBodyTap]），"取消选中 → 清标注"的优先级
 *    与改造前一字不差 —— 有选区/有标注时点按绝不会被翻页吞掉。
 */
@Composable
fun AdaptiveDocumentView(
    document: ReaderDocument,
    currentPage: Int,
    highlights: List<DocumentHighlight>,
    onPageChange: (Int) -> Unit,
    onAskQuestion: (selectedText: String) -> Unit,
    onHighlight: (selectedText: String, pageIndex: Int) -> Unit,
    onAddNote: (selectedText: String, pageIndex: Int) -> Unit,
    onOpenEtymology: (selectedText: String) -> Unit = {},
    /**
     * 同文本超链接目标：正文里凡是**被某条分支引用过**的原文都会被标成可点。
     *
     * ## 为什么文档页也需要它
     * 对话界面早就有这套（`quoteLinkTargets` + `QuoteLinkMatcher`），但只接在聊天正文；
     * 阅读器与 OCR 一直没接，于是"我曾在某个分支里引用过文献里的这段话"这个联系
     * 在读文献时完全看不见。这里把同一套机制接过来，**不加任何数据库字段** ——
     * 全靠各分支 `originQuote` 的文本匹配。
     */
    quoteLinkTargets: List<com.treechat.workbench.ui.selection.QuoteLinkTarget> = emptyList(),
    /** 点中同文本超链接 → 跳到对应分支。 */
    onQuoteLinkClick: (ConversationNode) -> Unit = {},
    /**
     * 文本选词方案：**与对话界面读同一份设置**。
     *
     * 此前阅读器把它写死成 TOUCH_LONG_PRESS，于是用户在设置里换方案对阅读器无效，
     * 同一个"选词"在两处有两套手感。
     */
    selectionScheme: com.treechat.workbench.ui.selection.SelectionScheme =
        com.treechat.workbench.ui.selection.SelectionScheme.TOUCH_LONG_PRESS,
    onToggleImmersive: () -> Unit = {},
    pdfColumns: Int = 1, // 针对扫描 PDF 的多列设置（1, 2, 3）
    /** 沉浸全屏：底栏、翻页条、选词方式切换器一并收起，整屏只留正文。 */
    isImmersive: Boolean = false,
    /** 实况选词的选词方式（默认条畅手柄选字，用户可随时在底栏一键切换为电脑框选）。 */
    selectionMethod: LiveSelectionMethod = LiveSelectionMethod.TALKTREE,
    onSelectionMethodChange: (LiveSelectionMethod) -> Unit = {},
    /** 全局输入设备模式：自动检测鼠标 / 强制键鼠 / 强制触控（扫描档与文本档共用）。 */
    inputMode: com.treechat.workbench.data.prefs.InputMode =
        com.treechat.workbench.data.prefs.InputMode.AUTO,
    /** 选句翻译 / 句法标注的现场状态（由书架持有，一次只服务一个选区）。 */
    sentenceTool: SentenceToolState = remember { SentenceToolState() },
    /** 工具条「翻译」：把选中段落交给当前模型译成目标语言。 */
    onTranslateSelection: (selectedText: String) -> Unit = {},
    /** 工具条「句法」：让模型把选中段落切成层级化句法单元（带 0 基页号）。 */
    onAnalyzeSyntax: (selectedText: String, pageIndex: Int) -> Unit = { _, _ -> },
    /** 清除句法标注。 */
    onClearSyntax: () -> Unit = {},
    /**
     * 生词匹配：启用的考纲 token（空集 = 用户已关闭考纲）。
     *
     * 命中词在正文上画考纲色下划线；右侧边栏据此统计"本页生词"。
     */
    activeExamTokens: Set<String> = emptySet(),
    /** 当前页文本上报（正文页给出该处拼接文本，供边栏统计本页生词）。 */
    onPageText: (String) -> Unit = {},
    /** 当前选中文本上报（空串 = 清除；供边栏「AI」页签与此「词条」页）。 */
    onSelectionText: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val clipboardManager = LocalClipboardManager.current
    val scheme = MaterialTheme.colorScheme

    // 选中文本状态与工具条锚点位置
    // ---- 页内查找（Ctrl+F）：实况扫描档与文本档共用同一个关键词 ----
    var findVisible by remember { mutableStateOf(false) }
    var findQuery by remember { mutableStateOf("") }
    // 当前定位到第几处命中 / 本页命中总数（"第 n / m 处"与上一处、下一处）
    var findIndex by remember { mutableStateOf(0) }
    var pageFindCount by remember { mutableStateOf(0) }


    var selectedText by remember { mutableStateOf("") }
    // 选中文本变化 → 上报（含清空）：右侧边栏的「AI」快捷动作与「词条」都跟着它走
    LaunchedEffect(selectedText) { onSelectionText(selectedText) }
    // 选中词所在的**整句/整段**：翻译与句法都只针对"这一段"，而不是一个孤零零的词。
    // 与 selectedText（长按命中的词）分开保存，避免改掉提问/划线/笔记既有的取词语义。
    var selectedSentence by remember { mutableStateOf("") }
    var selectedPageIndex by remember { mutableIntStateOf(currentPage) }
    var toolbarOffset by remember { mutableStateOf<Offset?>(null) }

    // 复制反馈：与对话界面一致 —— 复制后按钮短暂显示"已复制"。
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1500)
            copied = false
        }
    }

    /** 收起选区工具条（清空词 + 整句 + 工具条锚点）。 */
    fun clearTextSelection() {
        selectedText = ""
        selectedSentence = ""
        toolbarOffset = null
    }

    // 底栏溢出：窄屏时把次要控件（呈现切换 / 选词方式 / 排版）收进一个菜单，
    // 保证页码与翻页键始终完整可见，不被挤出屏幕。
    var bottomOverflowOpen by remember { mutableStateOf(false) }

    // 选词方式下拉菜单
    var methodMenuOpen by remember { mutableStateOf(false) }

    // 阅读排版（字号 / 行距 / 护眼底色）：状态来自 ReaderTypographyStore，
    // 面板里的改动会立刻反映到下面所有正文渲染处（同一个 StateFlow）。
    val readerTypography = rememberReaderTypography()
    var typographyDialogOpen by remember { mutableStateOf(false) }
    val eyeCare = readerTypography.eyeCare.resolve(isSystemInDarkTheme())

    // 判定实际列数：普通文本在开启"电脑模式"时采用双页对开（2列），否则单列；扫描件/PDF 听从 pdfColumns
    val actualColumns = if (document.format == ReaderFormat.PDF || document.isScanned) {
        pdfColumns.coerceIn(1, 3)
    } else if (readerTypography.desktopLayout) {
        2 // 电脑模式（桌面版阅读对开排版）：TXT/EPUB/DOCX/MOBI 左右双页并排
    } else {
        1
    }

    // ==================== 实况选词 (Live Text) 接线 ====================
    // 只有「能按图像渲染的 PDF / 扫描件」才允许上实况层；TXT/MD/EPUB/DOCX/PPTX
    // 一律保持纯文本沉浸阅读，不给它们叠加任何 OCR 浮层。
    val canRenderLivePdf = (
        document.format == ReaderFormat.PDF ||
            document.format == ReaderFormat.SCANNED_PDF ||
            document.isScanned
        ) && document.filePath.isNotBlank()

    // 扫描件/PDF 默认直接进实况；文本类文档该开关恒为 false、不显示。
    var liveTextEnabled by remember(document.id) { mutableStateOf(canRenderLivePdf) }

    // 一个文档一份 PdfPageSource（反复开关 PdfRenderer 会重读 xref，很慢）。
    // 打开放在 IO 协程里；用 awaitCancellation + finally 把关闭收拢到唯一一处：
    // 换书 / 退出阅读器时协程被取消 → finally 释放，不会泄漏 PdfRenderer 与 fd。
    var pdfSource by remember(document.id) { mutableStateOf<PdfPageSource?>(null) }
    var pdfSourceResolved by remember(document.id) { mutableStateOf(!canRenderLivePdf) }
    LaunchedEffect(document.id, document.filePath, canRenderLivePdf) {
        var src: PdfPageSource? = null
        try {
            src = if (canRenderLivePdf) {
                withContext(Dispatchers.IO) { PdfPageSource.open(document.filePath) }
            } else {
                null
            }
            pdfSource = src
            pdfSourceResolved = true
            awaitCancellation()
        } finally {
            pdfSource = null
            pdfSourceResolved = true
            src?.close()
        }
    }

    // 1~3 页 LRU：翻回上一页时不再重新渲染 + 重新识别。换书/退出时统一回收。
    val livePageCache = remember(document.id) { LiveTextPageCache(3) }


    DisposableEffect(document.id) {
        onDispose { livePageCache.clear() }
    }

    val useLiveText = canRenderLivePdf && liveTextEnabled


    // ==================== 自适应分页：按实测视口算每页字数 ====================
    val density = LocalDensity.current

    // 正文段落区（卡片里真正排字的那块）的**实测**宽高。
    // 只有拿到它才能按"实际可用高度 ÷ 实际行高"算每页字数，否则平板上
    // 固定 800 字一页会留一大片底部空白（见 DocumentFileReader.adaptiveCharsPerPage）。
    var viewportWidthPx by remember(document.id) { mutableStateOf(0f) }
    var viewportHeightPx by remember(document.id) { mutableStateOf(0f) }

    /**
     * 采纳一次正文视口的实测尺寸（由 [SingleDocumentPageCard] 的 onSizeChanged 回上来）。
     *
     * 高度**只在非沉浸态采样**：沉浸会收起底部翻页栏把正文区撑高几十 dp，
     * 若跟着采样，每次进出沉浸都会让同一页码对应的正文整体错位（页码没变、字全换了）。
     * 宽度变了（旋转 / 对开双页切换）则连高度一起重采 —— 那本来就是另一套版面。
     */
    fun onViewportMeasured(widthPx: Float, heightPx: Float) {
        if (widthPx <= 0f || heightPx <= 0f) return
        if (widthPx != viewportWidthPx) {
            viewportWidthPx = widthPx
            viewportHeightPx = heightPx
        } else if (!isImmersive || viewportHeightPx == 0f) {
            viewportHeightPx = heightPx
        }
    }

    // 只有"流式文本"（TXT/MD/EPUB/DOCX/PPTX/MOBI/AZW3 与旧知识库拼回的正文）才做自适应分页；
    // PDF / 扫描件（含只有文本层的 PDF）按物理页分页本来就对，一个字都不动。
    val isFlowTextDocument = document.format != ReaderFormat.PDF &&
        document.format != ReaderFormat.SCANNED_PDF && !document.isScanned

    // 排版口径换算成像素：sp→px 会带上系统字号缩放，与正文 Text 的换算完全同源。
    val pageCapacitySpec = remember(
        document.id, viewportWidthPx, viewportHeightPx,
        readerTypography.fontScale, readerTypography.lineHeightMultiplier
    ) {
        with(density) {
            DocumentFileReader.PageCapacitySpec(
                viewportWidthPx = viewportWidthPx,
                viewportHeightPx = viewportHeightPx,
                fontSizePx = readerTypography.fontSizeSp.sp.toPx(),
                lineHeightPx = readerTypography.lineHeightSp.sp.toPx(),
                letterSpacingPx = 0.3f.sp.toPx(),
                paragraphSpacingPx = 4f.dp.toPx() * 2f
            )
        }
    }

    // 每页字数：视口还没测到之前按老口径（800 字）走，测完立刻换成实测容量。
    val pageCapacity = if (isFlowTextDocument && viewportWidthPx > 0f && viewportHeightPx > 0f) {
        DocumentFileReader.adaptiveCharsPerPage(document.sampleText, pageCapacitySpec)
    } else {
        DocumentFileReader.CHARS_PER_PAGE
    }

    // 有效页数随每页字数变化（PDF/扫描件仍是物理页数）。底栏"共 N 页"、翻页按钮边界
    // 与所有"当前页"渲染都以它为准 —— 容量变过之后直接用 document.pageCount 会越界。
    val effectivePageCount = if (isFlowTextDocument) {
        ((document.sampleText.length + pageCapacity - 1) / pageCapacity).coerceAtLeast(1)
    } else {
        document.pageCount.coerceAtLeast(1)
    }

    /**
     * 底栏是否进入"紧凑模式"（次要控件收进溢出菜单）。
     *
     * 门槛 420dp 的来由：页码 + 呈现切换 + 选词方式 + Aa + 两侧翻页键在窄屏上
     * 会被挤到换行/截断，页都翻不动；而这个宽度以上的平板/折叠屏完全放得下。
     * 视口尚未实测到时按 false 处理，保持与旧版一致。
     */
    val readerDensity = androidx.compose.ui.platform.LocalDensity.current
    val bottomCompact = viewportWidthPx > 0f &&
        (viewportWidthPx / readerDensity.density) < 420f

    // 跨页查找：本页找不到就往后（或往前）翻，直到某一页有命中。
    var findBusy by remember { mutableStateOf(false) }
    var findStatus by remember { mutableStateOf<String?>(null) }
    val findScope = rememberCoroutineScope()
    val readerContext = LocalContext.current
    LaunchedEffect(findQuery) {
        findIndex = 0
        findStatus = null
    }

    /**
     * 取某一页的**可搜索文本**（1 基页号）。
     *
     * 文本档直接读分页后的正文；扫描档优先读 OCR 缓存，未缓存则当场渲染 + 识别
     * （识别结果会写回缓存，所以"查一遍"顺带把翻到的页也准备好了）。
     * 返回 null 表示这一页拿不到文本（页源未就绪/渲染失败），调用方跳过它。
     */
    suspend fun pageSearchText(page1Based: Int): String? {
        if (!useLiveText) {
            return DocumentFileReader.getPageContent(document, page1Based - 1, pageCapacity)
        }
        val key = "${document.id}#$page1Based"
        livePageCache.get(key)?.let { return it.boxes.joinToString(" ") { b -> b.text } }
        val src = pdfSource ?: return null
        val page = withContext(Dispatchers.IO) {
            val scale = PdfOcr.resolveRenderScale(readerContext)
            val bmp = src.renderPage(page1Based - 1, scale) ?: return@withContext null
            LivePage(bitmap = bmp, boxes = OcrEngine.recognizeOrFallback(bmp))
        } ?: return null
        livePageCache.put(key, page)
        return page.boxes.joinToString(" ") { b -> b.text }
    }

    /** 跨页查找：direction = +1 向后、-1 向前，环形遍历一圈。 */
    fun findAcrossPages(direction: Int) {
        val q = findQuery.trim()
        if (q.isBlank() || findBusy) return
        findScope.launch {
            findBusy = true
            findStatus = null
            try {
                var page = currentPage
                val total = effectivePageCount
                var scanned = 0
                while (scanned < total - 1) {
                    page += direction
                    if (page < 1) page = total
                    if (page > total) page = 1
                    scanned++
                    val text = pageSearchText(page) ?: continue
                    if (text.contains(q, ignoreCase = true)) {
                        onPageChange(page)
                        findStatus = "已跳到第 $page 页"
                        return@launch
                    }
                }
                findStatus = "全书没有更多「$q」"
            } finally {
                findBusy = false
            }
        }
    }

    // 渲染/翻页用的当前页：字号调大后持久化的页号可能落在新页数之外，先钳住。
    val safePage = currentPage.coerceIn(1, effectivePageCount)

    // 钳过的页号回写一次（只在真越界时写，不会循环触发）。
    LaunchedEffect(document.id, effectivePageCount) {
        if (currentPage !in 1..effectivePageCount) {
            onPageChange(currentPage.coerceIn(1, effectivePageCount))
        }
    }

    // ==================== 翻页：分区点按 / 双击切沉浸 / 方向键 / 拖动动画 ====================
    val turnMode = readerTypography.pageTurn
    // 翻页动画只接在**文本渲染**路径上：实况卡内部已有单指缩放平移手势（会吃掉横向拖动），
    // 而且动画期间要同时摆出两页位图 + 词盒识别，容易闪"加载中"。PDF/扫描件仍即时换页。
    val turnAnimationEnabled = turnMode != PageTurnMode.OFF && !useLiveText
    val turnState = rememberPageTurnState(document.id)

    LaunchedEffect(document.id, turnMode, useLiveText) {
        // 中途改动画档位、切到实况选词：把还没播完的翻页现场丢掉，别留下半张翻起的页。
        if (!turnAnimationEnabled) turnState.clear()
    }

    /** 翻到 [target]（页号先钳到有效范围内）。点按分区、方向键、底栏按钮共用这一个入口。 */
    fun turnTo(target: Int) {
        val from = safePage
        val to = target.coerceIn(1, effectivePageCount)
        if (to == from) return
        if (turnAnimationEnabled) {
            // 正在翻页时 start() 会返回 false —— 250ms 内的连点直接吞掉，不叠动画。
            turnState.start(from, to, forward = to > from, autoPlay = true)
        } else {
            onPageChange(to)
        }
    }

    /** 按方向翻 actualColumns 页（电脑模式对开就是一次翻两页）。 */
    fun turnPage(direction: Int) = turnTo(safePage + direction * actualColumns)

    /** 文本路径的点按优先级：① 取消选中 → ② 清句法标注（返回是否消费了这次点按）。 */
    fun consumeTapClear(): Boolean = when {
        selectedText.isNotBlank() -> {
            clearTextSelection()
            true
        }
        sentenceTool.hasAnnotation -> {
            onClearSyntax()
            true
        }
        else -> false
    }

    // 双击判定与"刚清过就别再翻页"的静默窗，两处 onTapBlank 共用（见 dispatchBodyTap）。
    var lastMiddleTapUptime by remember(document.id) { mutableStateOf(0L) }
    var tapQuietUntil by remember(document.id) { mutableStateOf(0L) }

    // 实况卡的 onTapBlank 是无参回调（LiveTextPageView 不在本次改造范围、签名不能动），
    // 但实况路径同样要做左右分区 —— 于是用一个**只读**的 Initial pass 记录器在按下
    // 瞬间记下 x。Initial pass 先于子节点消费，且它自己不消费任何事件，
    // 实况卡的框选/点按/缩放一律不受影响。
    var livePointerX by remember(document.id) { mutableStateOf(Float.NaN) }
    val recordLivePointer = Modifier.pointerInput(document.id) {
        awaitEachGesture {
            val down = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.pressed }
            if (down != null) livePointerX = down.position.x
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.changes.none { it.pressed }) break
            }
        }
    }

    val cardPaddingPx = with(density) { 14.dp.toPx() }
    val columnGapPx = with(density) { 8.dp.toPx() }

    /** 把"列内 x"换算成整块正文行里的比例位置（0 = 最左，1 = 最右），供左/中/右三分区判定。 */
    fun bodyTapFraction(columnIndex: Int, xInColumn: Float): Float {
        if (xInColumn.isNaN()) return 0.5f
        val columnOuter = viewportWidthPx + cardPaddingPx * 2f + columnGapPx
        val totalWidth = columnOuter * actualColumns - columnGapPx
        if (totalWidth <= 0f) return 0.5f
        return ((columnOuter * columnIndex + xInColumn) / totalWidth).coerceIn(0f, 1f)
    }

    /**
     * 正文区一次点按的统一分发，优先级与改造前完全一致：
     * ① 取消选中 → ② 清句法标注 → ③ 左 1/3 上一页、右 1/3 下一页 → ④ 中 1/3 **双击**切沉浸。
     *
     * - 有选区/有标注时点按只会去清它们，绝不会顺手翻页（要求里的硬约束）；
     * - 单击中间区**什么都不做**：沉浸开关改成双击触发，避免单击正文误触进出沉浸；
     * - 刚清完选区/标注的 300ms 内吞掉下一次点按，否则"清完顺手又翻了一页"。
     *
     * @param clearFirst 返回 true 表示这次点按已被"取消选中 / 清标注"消费。
     */
    /**
     * 正文区一次点按的统一分发。
     *
     * @param allowZoneTurn 左侧 1/3 / 右侧 1/3 是否翻页。
     *
     * ## 为什么实况（OCR）路径要传 false
     * 翻页在扫描页上已改为**左右滑动**，而滑动与选词是**同一根手指**上的两种意图。
     * 若同时保留"点两侧翻页"：
     *  · 取词时手一抖就跳页 —— 而在扫描页上点选词恰恰是最主要的下一步；
     *  · 一次横向拖动的起点若先被判成"轻点"，松手又会翻一次，
     *    用户看到的是"明明在滑，页却又弹回去"，即滑动与选词互相打架。
     *
     * 所以实况路径传 false：只保留清标注与**双击切沉浸**
     * （双击需要两次点按才能判定，仍走同一个路由）。
     */
    fun dispatchBodyTap(
        fraction: Float,
        clearFirst: () -> Boolean,
        // 默认值**跟随渲染路径**：实况（OCR/扫描）路径永远不做分区点击翻页。
        //
        // 之前默认恒为 true，靠每个调用点自己传 false —— 只要漏一处，扫描页上
        // "点一下取词"就会变成"点一下跳页"，而取词正是扫描页最主要的操作，
        // 用户会直接判定为"完全没法用"。翻页在实况路径已改为左右滑动
        // （见 LiveTextPageCard 的 onSwipePage），两套并存必然互相打架。
        allowZoneTurn: Boolean = !useLiveText
    ) {
        val now = SystemClock.uptimeMillis()
        if (now < tapQuietUntil) return
        if (clearFirst()) {
            tapQuietUntil = now + DOUBLE_TAP_WINDOW_MS
            return
        }
        when {
            allowZoneTurn && fraction < 1f / 3f -> turnPage(-1)
            allowZoneTurn && fraction > 2f / 3f -> turnPage(1)
            else -> {
                if (now - lastMiddleTapUptime <= DOUBLE_TAP_WINDOW_MS) {
                    lastMiddleTapUptime = 0L
                    onToggleImmersive()
                } else {
                    lastMiddleTapUptime = now
                }
            }
        }
    }

    // ==================== 键鼠：方向键 / 翻页键 / Esc ====================
    //
    // Compose 的按键事件只有**焦点节点**及其祖先才收得到，所以正文根节点要挂
    // focusRequester + focusable。焦点只在首次进入阅读时请求一次：每次重组都抢，
    // 会把 Aa 面板、对话输入框的焦点拽走（这正是"抢焦点"事故的典型来源）。
    val readerFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        // 最多等两帧再要一次：modifier 还没挂到布局树上时 requestFocus() 会抛
        // IllegalStateException，被吞掉就变成"键盘永远没反应"。3 次都失败就认了 ——
        // 那说明这块区域根本不可聚焦，静默退出比反复抢焦点更安全。
        repeat(3) {
            if (runCatching { readerFocusRequester.requestFocus() }.isSuccess) return@LaunchedEffect
            withFrameNanos { }
        }
    }

    fun onReaderKey(event: KeyEvent): Boolean {
        if (event.type != KeyEventType.KeyDown) return false
        // Ctrl/Cmd + F：呼出页内查找（扫描档实况与文本档都能用，像 macOS 预览一样）
        if ((event.isCtrlPressed || event.isMetaPressed) && event.key == Key.F) {
            findVisible = true
            return true
        }
        return when (event.key) {
            Key.DirectionLeft, Key.PageUp -> {
                turnPage(-1)
                true
            }
            Key.DirectionRight, Key.PageDown -> {
                turnPage(1)
                true
            }
            // Esc 优先收查找栏；没有查找栏时才退出沉浸
            Key.Escape -> when {
                findVisible -> {
                    findVisible = false
                    findQuery = ""
                    true
                }
                isImmersive -> {
                    onToggleImmersive()
                    true
                }
                else -> false
            }
            else -> false
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            // 护眼底色铺在整块画卷区域（含卡片四周的留白）：
            // 刺眼往往来自白底大面积留白，而不是卡片本身，所以连留白一起着色。
            .background(eyeCare?.paper ?: Color.Transparent)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                // 键鼠方向键翻页：先挂焦点请求器、再让这块区域可持有焦点、最后才是按键
                // 拦截。onPreviewKeyEvent 在子节点之前收到事件，方向键不会先被正文的
                // 纵向滚动吃掉；焦点请求本身只在首次进入阅读时做一次（见上面的
                // LaunchedEffect），避免每次重组都把面板/输入框的焦点抢走。
                .focusRequester(readerFocusRequester)
                .focusable()
                .onPreviewKeyEvent { onReaderKey(it) }
        ) {
            // 0. 页内查找栏（Ctrl+F / Cmd+F 唤出，Esc 收起）
            //    扫描档实况命中词框会被琥珀色标出；文本档显示本页命中数。
            if (findVisible) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(scheme.surfaceContainerHigh)
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Search,
                        contentDescription = null,
                        tint = scheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    BasicTextField(
                        value = findQuery,
                        onValueChange = { findQuery = it },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodySmall.copy(color = scheme.onSurface),
                        cursorBrush = SolidColor(scheme.primary),
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(readerFocusRequester),
                        decorationBox = { inner ->
                            if (findQuery.isEmpty()) {
                                Text(
                                    text = "在本页查找（Ctrl+F）",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = scheme.onSurfaceVariant
                                )
                            }
                            inner()
                        }
                    )
                    if (findQuery.isNotBlank()) {
                        Text(
                            text = if (pageFindCount > 0) {
                                "${findIndex + 1} / $pageFindCount"
                            } else {
                                "无匹配"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.onSurfaceVariant
                        )
                        IconButton(
                            onClick = {
                                // 本页还有上一处就在本页挪；到头了才跨页往前找。
                                if (findIndex > 0) findIndex-- else findAcrossPages(-1)
                            },
                            modifier = Modifier.size(30.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.KeyboardArrowUp,
                                contentDescription = "上一处",
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        IconButton(
                            onClick = {
                                if (findIndex < pageFindCount - 1) findIndex++ else findAcrossPages(1)
                            },
                            modifier = Modifier.size(30.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.KeyboardArrowDown,
                                contentDescription = "下一处",
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                    if (findBusy) {
                        CircularProgressIndicator(
                            strokeWidth = 2.dp,
                            modifier = Modifier
                                .padding(horizontal = 6.dp)
                                .size(14.dp)
                        )
                    }
                    findStatus?.let { status ->
                        Text(
                            text = status,
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.primary,
                            modifier = Modifier.padding(horizontal = 4.dp)
                        )
                    }
                    TextButton(onClick = { findVisible = false; findQuery = "" }) {
                        Text(
                            text = "关闭",
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.onSurfaceVariant
                        )
                    }
                }
            }

            // 1. 正文画卷区域（水平并排 actualColumns 列）
            //
            // 外面多包一层 Box：翻页动画要"旧页 + 新页"两层、横向拖动也要一个统一
            // 落点；关掉动画或走实况选词时只渲染普通一层，不加任何额外开销。
            val bodyAreaModifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 6.dp)

            /** 按**基础页号**渲染整行正文（翻页动画期间旧页与新页各要一份）。 */
            @Composable
            fun PageRow(pageBase: Int) {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    for (col in 0 until actualColumns) {
                        val pIdx = pageBase + col
                        // 标注只画在"产生它的那一页"上：句法结果带文档 id 与 0 基页号，
                        // 与当前列的页号不一致时视为"不属于本页"，不画。
                        val syntaxOnThisPage = sentenceTool.hasAnnotation &&
                            sentenceTool.documentId == document.id &&
                            sentenceTool.pageIndex == pIdx - 1
                        if (pIdx <= effectivePageCount) {
                            if (useLiveText) {
                                // 实况选词路径：按用户选定的方式渲染（自绘框选 / 原生选区）
                                LiveTextPageCard(
                                    source = pdfSource,
                                    sourceResolved = pdfSourceResolved,
                                    cache = livePageCache,
                                    document = document,
                                    pageNumber = pIdx,
                                    method = selectionMethod,
                                    inputMode = inputMode,
                                    findQuery = findQuery,
                                    findActiveIndex = findIndex,
                                    onFindCount = { pageFindCount = it },
                                    isImmersive = isImmersive,
                                    // 多列并排时只让第一列做"原生选区→剪贴板→工具条"的桥，
                                    // 否则一次复制会在三列上同时弹出三个工具条。
                                    isPrimaryPage = col == 0,
                                    columnIndex = col,
                                    columnCount = actualColumns,
                                    // 实况卡自己处理横向滑动：未放大时单指横滑即翻一段。
                                    // 这里复用与点按分区完全相同的 turnPage，
                                    // 保证"滑"和"点右三分之一"翻的是同一页。
                                    onSwipePage = { dir -> turnPage(dir) },
                                    // 多列时双击某一列 → 放大成一页沉浸。
                                    // 先把当前可见的第一页设成沉浸页，再切沉浸，
                                    // 避免用户双击第二列却看到第一列被放大。
                                    onDoubleTapZoom = {
                                        if (actualColumns > 1) {
                                            onPageChange(pIdx)
                                            onToggleImmersive()
                                        }
                                    },
                                    onAskQuestion = onAskQuestion,
                                    onHighlight = onHighlight,
                                    onAddNote = onAddNote,
                                    onOpenEtymology = onOpenEtymology,
                                    quoteLinkTargets = quoteLinkTargets,
                                    onQuoteLinkClick = onQuoteLinkClick,
                                    // 生词考纲匹配 + 本页文本上报 + 句法标注数据
                                    activeExamTokens = activeExamTokens,
                                    onPageText = onPageText,
                                    toolState = sentenceTool,
                                    onTranslateSentence = onTranslateSelection,
                                    // 点「微型小窗标签」→ 开一个绑在这一页这一处的小窗。
                                    //
                                    // 复用提问那条链路（在本书专属会话里建节点 + 开小窗），
                                    // 但**引用文本换成位置定位串** —— 关键正在这里：
                                    // OCR 一个字都没认出来的地方依然能开窗说事，
                                    // 不再要求"先认出正确的字才能选中它"。
                                    onPageTagClick = { tag ->
                                        onAskQuestion("第 ${tag.page} 页 · 标签 ${tag.label}")
                                    },
                                    onTapBlank = {
                                        // ⚠️ 实况（OCR/扫描）路径**不再做分区点击翻页**。
                                        //
                                        // 翻页已改为**左右滑动**（见 LiveTextPageCard 的
                                        // onSwipePage）。两套并存是错的：
                                        //  · 点一下就想翻页 → 取词时手一抖就跳页，
                                        //    而在扫描页上点选词恰恰是最主要的下一步；
                                        //  · 更糟的是它和滑动**互相打架**：一次横向拖动的
                                        //    起点若被判成"轻点"，松手时又会翻一次，
                                        //    用户会看到"明明在滑，页却又弹回去了"。
                                        //
                                        // 这里只保留两件事：清掉句法标注、以及双击切沉浸
                                        // （双击仍需要两次点按来判定，所以留同一个路由）。
                                        dispatchBodyTap(
                                            bodyTapFraction(col, livePointerX),
                                            clearFirst = {
                                                if (sentenceTool.hasAnnotation) {
                                                    onClearSyntax()
                                                    true
                                                } else {
                                                    false
                                                }
                                            },
                                            // 实况路径不分区点击翻页：翻页已改为左右滑动，
                                            // 两套并存会与选词手势互相打架（见上方说明）。
                                            allowZoneTurn = false
                                        )
                                    },
                                    onTranslate = onTranslateSelection,
                                    onAnalyzeSyntax = { text -> onAnalyzeSyntax(text, pIdx - 1) },
                                    syntaxUnitsForPage = if (syntaxOnThisPage) sentenceTool.units else emptyList(),
                                    syntaxActive = sentenceTool.hasAnnotation,
                                    onClearSyntax = onClearSyntax,
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight()
                                        // 只读的按下位置记录器：给无参的 onTapBlank 补上坐标
                                        .then(recordLivePointer)
                                )
                            } else {
                                SingleDocumentPageCard(
                                    document = document,
                                    pageIndex = pIdx,
                                    highlights = highlights.filter { it.pageIndex == pIdx - 1 },
                                    syntaxUnits = if (syntaxOnThisPage) sentenceTool.units else emptyList(),
                                    inputMode = inputMode,
                                    selectionScheme = selectionScheme,
                                    // ⚠️ 这两项以前**没传**，是"文本阅读器提问后原文里
                                    // 连下划线都没有、也点不开"的直接原因。
                                    //
                                    // 为什么能一路编译通过：SingleDocumentPageCard 的
                                    // quoteLinkTargets / onQuoteLinkClick 都带默认值
                                    // （emptyList / 空实现），漏传不会报错，只会静默
                                    // 拿到空值 —— 与"表格链接传不下去"是同一类错误。
                                    // OCR 那条路（LiveTextPageCard）传了，所以扫描件
                                    // 正常、纯文本异常：差异正出在这一行。
                                    quoteLinkTargets = quoteLinkTargets,
                                    onQuoteLinkClick = onQuoteLinkClick,
                                    // 自适应分页：这一页该装多少字由实测视口算出（见 pageCapacity）。
                                    pageSize = pageCapacity,
                                    // 把正文段落区的实测宽高回上来，驱动下一次页容量计算。
                                    onViewportMeasured = { w, h -> onViewportMeasured(w, h) },
                                    onSelectWord = { word, sentence, pos ->
                                        selectedText = word
                                        selectedSentence = sentence
                                        selectedPageIndex = pIdx - 1
                                        toolbarOffset = pos
                                    },
                                    onTapBlank = { pos ->
                                        // 点正文：**先取消选中**、再清标注，都没了才轮到
                                        // 左右分区翻页；中间区要连点两下才切沉浸。
                                        dispatchBodyTap(
                                            bodyTapFraction(col, pos.x + cardPaddingPx),
                                            clearFirst = { consumeTapClear() }
                                        )
                                    },
                                    currentSelectedWord = if (selectedPageIndex == pIdx - 1) selectedText else "",
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight()
                                )
                            }
                        } else {
                            Spacer(modifier = Modifier.weight(1f))
                        }

                        if (col < actualColumns - 1 && pIdx < effectivePageCount) {
                            VerticalDivider(
                                modifier = Modifier.padding(vertical = 12.dp),
                                color = scheme.outlineVariant.copy(alpha = 0.4f)
                            )
                        }
                    }
                }
            }

            if (turnAnimationEnabled) {
                PageTurnBox(
                    state = turnState,
                    mode = turnMode,
                    currentPage = safePage,
                    columns = actualColumns,
                    pageCount = effectivePageCount,
                    onPageChange = onPageChange,
                    modifier = bodyAreaModifier
                ) { pageBase ->
                    PageRow(pageBase)
                }
            } else {
                Box(modifier = bodyAreaModifier) { PageRow(safePage) }
            }

            // 2. 底部极简翻页进度栏 (向 M3 规范对齐)
            //
            // 沉浸态下整条收起：沉浸 = 整屏只有正文，顶栏、底栏、列切换、
            // 进度角标全部让位；**双击**正文中间区再把它们唤回来（单击不再触发，
            // 见 dispatchBodyTap）—— 沉浸态下正文的左右 1/3 点按仍可翻页，翻得动。
            AnimatedVisibility(
                visible = !isImmersive,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = scheme.surfaceContainerLow,
                    tonalElevation = 1.dp
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = { turnPage(-1) },
                            enabled = safePage > 1
                        ) {
                            Icon(Icons.AutoMirrored.Outlined.NavigateBefore, contentDescription = "上一页")
                        }

                        // 用 weight 把中间这段夹在两个翻页键之间：页码 + 切换器 + 选词方式
                        // 再长也不会把两侧的按钮挤出屏幕。
                        // 页码拖动条：可**直接拖到任意页**，不用一页页点。
                        //
                        // 为什么放在底栏而不是顶栏：底栏本来就有"上一页/下一页"两个键，
                        // 拖条紧挨着它们构成"精确 ↔ 快速"的完整翻页控件，
                        // 且底栏在沉浸态之外始终可见，不会像顶栏那样被藏掉。
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = "第 $safePage 页 / 共 $effectivePageCount 页",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Medium,
                                color = scheme.onSurfaceVariant
                            )
                            // 单页文档拖条没有意义，只在多页时出现。
                            if (effectivePageCount > 1) {
                                Spacer(modifier = Modifier.width(10.dp))
                                PageScrubber(
                                    current = safePage,
                                    total = effectivePageCount,
                                    onPage = { p -> turnTo(p) }
                                )
                            }
                            // 句法标注的状态与**清除入口**：有标注时给出一句话说明
                            // （几个单元、几个没能定位）+ 一个清除按钮；分析中给进度提示。
                            if (sentenceTool.analyzing) {
                                Text(
                                    text = "句法分析中…",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = scheme.primary
                                )
                            } else if (sentenceTool.hasAnnotation || sentenceTool.failureReason != null) {
                                Text(
                                    text = buildString {
                                        append("句法：")
                                        append(sentenceTool.units.size)
                                        append(" 个单元")
                                        if (sentenceTool.unlocated.isNotEmpty()) {
                                            append("（${sentenceTool.unlocated.size} 个未定位）")
                                        }
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = scheme.secondary
                                )
                                TextButton(onClick = onClearSyntax) {
                                    Icon(
                                        imageVector = Icons.Outlined.Clear,
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp),
                                        tint = scheme.error
                                    )
                                    Spacer(modifier = Modifier.width(3.dp))
                                    Text(
                                        text = "清除标注",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = scheme.error
                                    )
                                }
                            }
                            // 宽屏/平板：次要控件平铺（原行为）
                            if (!bottomCompact) {
                                // 扫描原图 / 文本排版 切换（仅能渲染图像的 PDF、扫描件显示）。
                                // 标签必须写"当前处于哪种呈现"，不能写成"要不要实况"——
                                // 旧文案「实况选词」会被误当成选词方式选项去点，一点就关掉扫描原图，
                                // 表现为"点条畅选词却跳到文本阅读"。
                                if (canRenderLivePdf) {
                                    TextButton(onClick = { liveTextEnabled = !liveTextEnabled }) {
                                        Text(
                                            text = if (liveTextEnabled) "当前：扫描原图" else "当前：文本排版",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.SemiBold,
                                            color = if (liveTextEnabled) scheme.primary else scheme.onSurfaceVariant
                                        )
                                    }
                                }
                                // 实况选词的"选词方式"选择器：自绘框选 / 系统原生选区。
                                // 选择落到 LiveSelectionPrefs（会被记住，下次进来仍是你选的那个）。
                                if (useLiveText) {
                                    Box {
                                        TextButton(onClick = { methodMenuOpen = true }) {
                                            Icon(
                                                imageVector = Icons.Outlined.Tune,
                                                contentDescription = null,
                                                modifier = Modifier.size(14.dp),
                                                tint = scheme.primary
                                            )
                                            Spacer(modifier = Modifier.width(3.dp))
                                            Text(
                                                text = selectionMethod.shortLabel,
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.SemiBold,
                                                color = scheme.primary
                                            )
                                        }
                                        DropdownMenu(
                                            expanded = methodMenuOpen,
                                            onDismissRequest = { methodMenuOpen = false }
                                        ) {
                                            LiveSelectionMethod.entries.forEach { candidate ->
                                                DropdownMenuItem(
                                                    text = {
                                                        Column {
                                                            Text(
                                                                text = candidate.displayName,
                                                                style = MaterialTheme.typography.bodyMedium
                                                            )
                                                            Text(
                                                                text = candidate.description,
                                                                style = MaterialTheme.typography.labelSmall,
                                                                color = scheme.onSurfaceVariant
                                                            )
                                                        }
                                                    },
                                                    leadingIcon = {
                                                        if (candidate == selectionMethod) {
                                                            Icon(
                                                                imageVector = Icons.Outlined.Check,
                                                                contentDescription = null,
                                                                tint = scheme.primary
                                                            )
                                                        }
                                                    },
                                                    onClick = {
                                                        methodMenuOpen = false
                                                        onSelectionMethodChange(candidate)
                                                    }
                                                )
                                            }
                                        }
                                    }
                                }
                                // 阅读排版：字号 / 行距 / 护眼底色。改动即时生效并被记住。
                                TextButton(onClick = { typographyDialogOpen = true }) {
                                    Text(
                                        text = "Aa",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontFamily = FontFamily.Serif,
                                        fontWeight = FontWeight.Bold,
                                        color = scheme.primary
                                    )
                                }
                            } else {
                                // 窄屏：收进溢出菜单，页码与翻页键不再被挤
                                Box {
                                    IconButton(
                                        onClick = { bottomOverflowOpen = true },
                                        modifier = Modifier.size(34.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.MoreVert,
                                            contentDescription = "更多阅读选项",
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                    DropdownMenu(
                                        expanded = bottomOverflowOpen,
                                        onDismissRequest = { bottomOverflowOpen = false }
                                    ) {
                                        if (canRenderLivePdf) {
                                            DropdownMenuItem(
                                                text = {
                                                    Text(
                                                        if (liveTextEnabled) "切到文本排版"
                                                        else "切到扫描原图"
                                                    )
                                                },
                                                onClick = {
                                                    liveTextEnabled = !liveTextEnabled
                                                    bottomOverflowOpen = false
                                                }
                                            )
                                        }
                                        if (useLiveText) {
                                            LiveSelectionMethod.entries.forEach { candidate ->
                                                DropdownMenuItem(
                                                    text = {
                                                        Text("选词方式：${candidate.shortLabel}")
                                                    },
                                                    onClick = {
                                                        onSelectionMethodChange(candidate)
                                                        bottomOverflowOpen = false
                                                    }
                                                )
                                            }
                                        }
                                        DropdownMenuItem(
                                            text = { Text("阅读排版（字号 / 行距 / 护眼）") },
                                            onClick = {
                                                typographyDialogOpen = true
                                                bottomOverflowOpen = false
                                            }
                                        )
                                    }
                                }
                            }
                        }

                        IconButton(
                            onClick = { turnPage(1) },
                            enabled = safePage < effectivePageCount
                        ) {
                            Icon(Icons.AutoMirrored.Outlined.NavigateNext, contentDescription = "下一页")
                        }
                    }
                }
            }
        }

        // 3. 文字文档的选区工具条：**复用对话界面的那一套** [FloatingSelectionToolbar]。
        //
        // 与对话界面用的是同一个组件、同一份"复制"实现（LocalClipboardManager +
        // 复制后显示"已复制"的反馈）。动作集合保留阅读器自己的选项卡
        // （提问 / 复制 / 词源·音标 / 划线 / 笔记 / 翻译 / 句法），通过组件新增的
        // **可选参数**（见 SelectionToolbar.kt）去掉"全选"、把标签换成本地叫法。
        //
        // 注意：扫描档的实况选词（LiveTextPageView / LiveTextComposeOverlay）一个字都没动。
        // 文本档（非实况）时命中数自己算：把本页可见文本切出来数一遍，
        // 与扫描档的浮层回报互不干扰（两者互斥，只有一条会生效）。
        LaunchedEffect(findQuery, currentPage, document.id) {
            if (!findQuery.isBlank() && !useLiveText) {
                val text = DocumentFileReader.getPageContent(
                    document,
                    currentPage - 1,
                    pageCapacity
                )
                pageFindCount = countOccurrences(text, findQuery)
                if (findIndex >= pageFindCount) findIndex = 0
            }
        }

        val toolbarAnchor = toolbarOffset?.let { Rect(it.x, it.y, it.x, it.y) } ?: Rect.Zero
        FloatingSelectionToolbar(
            visible = selectedText.isNotBlank() && toolbarOffset != null,
            anchorRect = toolbarAnchor,
            onAsk = {
                val t = selectedText
                clearTextSelection()
                onAskQuestion(t)
            },
            onCopy = {
                if (selectedText.isNotBlank()) {
                    clipboardManager.setText(AnnotatedString(selectedText))
                    copied = true
                }
                clearTextSelection()
            },
            // 划线/笔记入口已按用户要求整体移除（2026-10-17）：
            // 工具条不再出现这两个按钮；读书笔记采集只保留手写批注层，
            // 已有的划线数据仍在右侧边栏「注释」页可见。
            // onSelectAll 占位：阅读器不提供"全选"。
            onSelectAll = {},
            onDismiss = { clearTextSelection() },
            highlightActive = false,
            onEtymology = {
                val t = selectedText
                clearTextSelection()
                onOpenEtymology(t)
            },
            onTranslate = {
                val t = selectedSentence.ifBlank { selectedText }
                clearTextSelection()
                if (t.isNotBlank()) onTranslateSelection(t)
            },
            onSyntax = {
                val t = selectedSentence.ifBlank { selectedText }
                val p = selectedPageIndex
                clearTextSelection()
                if (t.isNotBlank()) onAnalyzeSyntax(t, p)
            },
            copyFeedback = copied,
            showSelectAll = false,
            askLabel = "提问"
        )

        // 阅读排版面板（字号 / 行距 / 护眼底色）
        if (typographyDialogOpen) {
            ReaderTypographyDialog(onDismiss = { typographyDialogOpen = false })
        }
    }
}

/** 大小写不敏感地统计 needle 在 text 中出现的次数（页内查找计数用）。 */
private fun countOccurrences(text: String, needle: String): Int {
    val n = needle.trim()
    if (n.isEmpty() || text.isEmpty()) return 0
    var count = 0
    var from = 0
    while (true) {
        val idx = text.indexOf(n, from, ignoreCase = true)
        if (idx < 0) break
        count++
        from = idx + n.length
    }
    return count
}

/**
 * 单页文档纸张模拟卡片（支持真实内容分页与长按/双击精准选词）
 *
 * 长按会把**命中词所在的整句/整段**也一并交出去（[onSelectWord] 的第二个参数）：
 * 提问/划线/笔记仍按原来的词走，而「翻译」「句法」要的是一整句。
 *
 * [syntaxUnits] 非空时，含命中片段的段落改用 [SyntaxAnnotatedText] 渲染 ——
 * 层级底色/左侧竖线/role 标签都叠在**原文本身**上，未命中的文字照常按正文显示，
 * 原文一个字符都不改、不丢。
 *
 * @param pageSize 本页装多少字（自适应分页算出的容量，见 [DocumentFileReader.adaptiveCharsPerPage]）。
 * @param onViewportMeasured 正文段落区的实测宽高，回给宿主做自适应分页；
 *   宽度永远采、高度只在非沉浸态采（由宿主决定，这里只负责报数）。
 * @param onTapBlank 正文区一次点按（带**列内坐标**）：宿主用它做左/中/右三分区
 *   与双击判定，因此坐标必须给 —— 段落与卡片空白两处手势都要带上。
 */
@Composable
private fun SingleDocumentPageCard(
    document: ReaderDocument,
    pageIndex: Int,
    highlights: List<DocumentHighlight>,
    syntaxUnits: List<SyntaxUnit>,
    inputMode: com.treechat.workbench.data.prefs.InputMode =
        com.treechat.workbench.data.prefs.InputMode.AUTO,
    selectionScheme: com.treechat.workbench.ui.selection.SelectionScheme =
        com.treechat.workbench.ui.selection.SelectionScheme.TOUCH_LONG_PRESS,
    /** 同文本超链接目标（正文里被分支引用过的段落会标出并可点）。 */
    quoteLinkTargets: List<com.treechat.workbench.ui.selection.QuoteLinkTarget> = emptyList(),
    onQuoteLinkClick: (ConversationNode) -> Unit = {},
    /** 启用的考纲 token（空 = 关闭考纲），见 AdaptiveDocumentView 同名参数。 */
    activeExamTokens: Set<String> = emptySet(),
    /** 本页文本上报（边栏"本页生词"的数据源）。 */
    onPageText: (String) -> Unit = {},
    /** 选句翻译 / 句法标注的现场状态（本页相关时画句法卡）。 */
    toolState: SentenceToolState? = null,
    /** 句法卡上的「翻译本段」。 */
    onTranslateSentence: (String) -> Unit = {},
    pageSize: Int = DocumentFileReader.CHARS_PER_PAGE,
    onViewportMeasured: (widthPx: Float, heightPx: Float) -> Unit = { _, _ -> },
    onSelectWord: (word: String, sentence: String, pos: Offset) -> Unit,
    onTapBlank: (pos: Offset) -> Unit,
    currentSelectedWord: String = "",
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    val scrollState = rememberScrollState()
    val realContent = DocumentFileReader.getPageContent(document, pageIndex, pageSize)

    // ---- 生词考纲匹配（本页整词，VocabIndex 的标签列是数据源）----
    // 命中词的"整词区间"会通过 SelectableMarkdownText 的新 vocab 通道画
    // 下划线与淡底；侧栏的数量统计由上层按 onPageText 上报的文本另行算
    //（同一套 findVocabHits，口径一致）。
    val vocabHits = remember(realContent, activeExamTokens) {
        findVocabHits(realContent, activeExamTokens) { VocabIndex.examTags(it) }
    }
    // 本页文本一变就上报：边栏据此算本页生词数与列表
    LaunchedEffect(realContent) { onPageText(realContent) }

    // 句法标注按用户要求**不画在原文页**上：结果统一进右侧边栏「句法」页签
    //（见 ReaderSidePanel.SyntaxPane）。本页基数只用于"本页文本上报"。

    // 阅读排版：字号 / 行距 / 护眼底色的**唯一**取值处。
    // 纸面底色与文字色都跟着护眼主题走，字号/行高由 ReaderTypography 统一算，
    // 于是"预览"与"正文"必然一致。
    val typography = rememberReaderTypography()
    val cardEyeCare = typography.eyeCare.resolve(isSystemInDarkTheme())

    var cardCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    // 文本阅读器 100% 完全复用条畅对话界面的选词器核心（MessageSelectionController + SelectableMarkdownText）：
    // 彻底解决"旧选词器只能选 1 个词"的痛点，支持水滴手柄自由拖拉、跨词/多词/整行自由选择；
    // 选项卡完全沿用阅读器原生的 FloatingSelectionToolbar，仅底层选词实现彻底对齐条畅！
    val cardSelectionController = remember(document.id, pageIndex, pageSize) {
        MessageSelectionController()
    }

    LaunchedEffect(currentSelectedWord) {
        if (currentSelectedWord.isBlank()) {
            cardSelectionController.clear()
        }
    }

    ElevatedCard(
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = cardEyeCare?.paper ?: scheme.surfaceBright
        ),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp),
        modifier = modifier.onGloballyPositioned { cardCoordinates = it }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(14.dp)
        ) {
            // 页眉小标
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = document.title,
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "P.$pageIndex",
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.primary,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 正文内容区域：100% 完整复用条畅 SelectableMarkdownText（手柄拖选、多词、多行、水滴手柄）
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(scrollState)
                    .onSizeChanged { size ->
                        onViewportMeasured(size.width.toFloat(), size.height.toFloat())
                    }
                    .pointerInput(document.id, pageIndex) {
                        detectTapGestures(onTap = { pos -> onTapBlank(pos) })
                    }
            ) {
                SelectableMarkdownText(
                    markdown = realContent,
                    controller = cardSelectionController,
                    // **与对话界面同一份设置**：方案与输入档都听用户的。
                    //
                    // 这里曾写死 FORCE_TOUCH / TOUCH_LONG_PRESS，两头都错：
                    //  · 写死 FORCE_TOUCH → 鼠标/触控板按下拖动**不再划选**
                    //    （触屏档只认长按，而鼠标的语义是"按下即拖"）；
                    //  · 写死 TOUCH_LONG_PRESS → 设置里换方案对阅读器无效。
                    // 现在如实透传：AUTO 按指针类型自动分流 —— 鼠标走桌面拖选、
                    // 手指走长按。这正是"和对话界面一样"的定义。
                    selectionScheme = selectionScheme,
                    // 手柄在阅读器里**始终显示**（含鼠标/触控板）：
                    // 默认策略是"桌面级指针不画手柄"（浏览器惯例），但读文献时
                    // 用户要的是"鼠标能按下即拖划选"**同时**"选完能抓手柄微调"，
                    // 两者并不冲突 —— 用开关打开即可，对话界面不受影响。
                    alwaysShowHandles = true,
                    quoteLinkTargets = quoteLinkTargets,
                    onQuoteClick = { node -> onQuoteLinkClick(node) },
                    inputMode = inputMode,
                    baseFontSize = typography.fontSizeSp.sp,
                    selectionColor = scheme.primary.copy(alpha = 0.28f),
                    contentPadding = PaddingValues(0.dp),
                    vocabRanges = vocabHits.map { it.start until it.endExclusive },
                    vocabColor = if (activeExamTokens.isEmpty()) {
                        Color.Unspecified
                    } else {
                        Color(0xFFE57373)
                    },
                    onSelectionChanged = { hasSel ->
                        if (hasSel) {
                            val selected = cardSelectionController.selectedText()
                            val bound = cardSelectionController.selectionBoundingRect()
                            if (selected.isNotBlank() && bound != null) {
                                val root = cardCoordinates
                                val globalPos = if (root != null && root.isAttached) {
                                    Offset(bound.center.x, bound.top)
                                } else {
                                    bound.center
                                }
                                onSelectWord(selected, sentenceContaining(realContent, selected), globalPos)
                            }
                        } else {
                            onSelectWord("", "", Offset.Zero)
                        }
                    },
                    // 不传 onAsk 与 onCopy：隐藏对话界面的菜单，100% 沿用阅读器原有的 FloatingSelectionToolbar
                    onAsk = null,
                    onCopy = null,
                    modifier = Modifier
                        .fillMaxWidth()
                        // 外层再压一层竖条光标（与对话界面 ChatMessageView 的做法一致）。
                        // overrideDescendants = true：正文区域一律竖条，
                        // 不被内部的表格横向滚动、代码块、链接等子层换掉光标。
                        .pointerHoverIcon(PointerIcon.Text, overrideDescendants = true)
                )

            }
        }
    }
}

/**
 * 取 [content] 中命中词 [word] 所在的**整句**（找不到词就退回该词本身）。
 *
 * 判据落在句末标点上：中英俄三种标点一起认（。！？!?.;；\n 与俄文的 …）。
 * 为什么需要它：「翻译」翻一个孤立的词没有意义、「句法切分」更不可能只切一个词，
 * 而文本路径的长按只给出一个词 —— 于是这里把它扩到所在句，作为这两个动作的输入。
 */
private fun sentenceContaining(content: String, word: String): String {
    if (word.isBlank()) return ""
    val at = content.indexOf(word)
    if (at < 0) return word
    val terminators = charArrayOf('。', '！', '？', '!', '?', '.', ';', '；', '\n', '…')
    var start = 0
    for (i in at - 1 downTo 0) {
        if (content[i] in terminators) {
            start = i + 1
            break
        }
    }
    var end = content.length
    for (i in at + word.length until content.length) {
        if (content[i] in terminators) {
            end = i + 1
            break
        }
    }
    val sentence = content.substring(start, end).trim()
    return sentence.ifBlank { word }
}

/**
 * 页码拖动条：按住滑块可快速定位到任意页。
 *
 * ## 交互设计：拖动时只**预览**页码，松手才真正翻页
 * 拖动过程中直接翻页会有两个问题：
 *  1. 长文档每翻一页都要重新分页/渲染，拖动会变成"拖着卡顿"；
 *  2. 用户还在滑，页面就已经在跳，手一松落点与看到的位置对不上。
 * 所以这里把"当前页"分成两个量：预览值随手指走，松手才提交。
 *
 * ## 为什么不用 Slider
 * Slider 的步进是离散的、且默认带一整套动画与语义；在"页数可能上百"的场景下，
 * 滑块本身太窄、拇指命中率低。这里自己画，滑块半径给到 11dp，
 * 并且拖动区域比可见轨道更宽（命中区比视觉大一圈），手指更容易按住。
 */
@Composable
private fun PageScrubber(
    current: Int,
    total: Int,
    onPage: (Int) -> Unit
) {
    var trackWidthPx by remember { mutableIntStateOf(1) }
    var dragging by remember { mutableStateOf(false) }
    // 拖动中显示的预览页码；松手后交回 current
    var preview by remember(current) { mutableIntStateOf(current) }

    val density = LocalDensity.current
    val knobRadius = with(density) { 11.dp.toPx() }
    val trackHeight = with(density) { 3.dp.toPx() }
    val hitSlop = with(density) { 14.dp.toPx() }
    val shown = if (dragging) preview else current
    val fraction = if (total <= 1) 0f else (shown - 1).toFloat() / (total - 1)

    Column(
        modifier = Modifier
            .width(150.dp)
            .pointerInput(total) {
                val width = size.width.toFloat().coerceAtLeast(1f)
                fun pageAt(x: Float): Int {
                    val usable = (width - knobRadius * 2f).coerceAtLeast(1f)
                    val f = ((x - knobRadius) / usable).coerceIn(0f, 1f)
                    return (1 + (f * (total - 1)).toInt()).coerceIn(1, total)
                }
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    dragging = true
                    preview = pageAt(down.position.x)
                    var moved = true
                    while (moved) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id }
                        if (change == null || !change.pressed) moved = false
                        else preview = pageAt(change.position.x)
                    }
                    // 松手才提交：见函数注释里的"预览 vs 提交"
                    onPage(preview)
                    dragging = false
                }
            }
    ) {
        // 轨道 + 滑块
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(22.dp)   // 视觉细，但命中区靠上面的 pointerInput 覆盖
        ) {
            val cy = size.height / 2f
            val usable = (size.width - knobRadius * 2f).coerceAtLeast(1f)
            val knobX = knobRadius + usable * fraction

            // 轨道
            drawLine(
                color = Color(0xFF3A414C),
                start = Offset(knobRadius, cy),
                end = Offset(size.width - knobRadius, cy),
                strokeWidth = trackHeight,
                cap = androidx.compose.ui.graphics.StrokeCap.Round
            )
            // 已读部分
            drawLine(
                color = Color(0xFF66E0A3),
                start = Offset(knobRadius, cy),
                end = Offset(knobX, cy),
                strokeWidth = trackHeight,
                cap = androidx.compose.ui.graphics.StrokeCap.Round
            )
            // 滑块
            drawCircle(Color(0xFF66E0A3), knobRadius, Offset(knobX, cy))
            drawCircle(Color(0xFF0E1116), knobRadius * 0.42f, Offset(knobX, cy))
        }
    }
}
