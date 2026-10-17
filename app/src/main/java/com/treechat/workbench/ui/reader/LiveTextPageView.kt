package com.treechat.workbench.ui.reader

import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.treechat.workbench.data.model.ConversationNode
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.treechat.workbench.ocr.OcrEngine
import com.treechat.workbench.ui.selection.FloatingSelectionToolbar
import com.treechat.workbench.ui.selection.MessageSelectionController
import com.treechat.workbench.ui.selection.SelectableMarkdownText
import com.treechat.workbench.ui.selection.SelectionScheme
import com.treechat.workbench.ocr.PdfOcr
import com.treechat.workbench.ocr.PdfPageSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * 阅读器「实况选词」渲染路径 (Live Text)。
 *
 * 与 [OcrEngine]（识别）和 [LiveTextComposeOverlay]（框选交互）配合，把
 * 「扫描页图像 → 位图上的词盒 → 屏幕上的高亮框 → 点按工具条提问」这条链路
 * 真正接通。
 *
 * ─────────────────── 位图坐标 → 屏幕坐标（最容易错的一处） ───────────────────
 *
 * **只保留一套坐标系**：位图、词盒、手势面全部放进**同一个**被
 * `graphicsLayer(scale/translation)` 缩放的容器里，缩放与平移只作用在这一层上。
 * 于是：
 *
 *  1. 词盒仍按 `ContentScale.Fit` 的等比缩放 + 居中偏移换算到"内容坐标"
 *     （[displayBoxes]，内容坐标 = 未缩放时的容器坐标）；
 *  2. Compose 会把指针位置**反向映射**回被缩放节点的本地坐标，
 *     所以 [LiveTextComposeOverlay] 里拿到的坐标与词盒坐标天然同源；
 *  3. 放大、平移、继续框选，三者永远一致 —— 不存在"两套坐标系要靠公式对齐"
 *     的环节，也就没有了"页面放大缩小选中位置发生偏移"。
 *
 * 旧版把缩放/居中算在词盒上、却不把缩放容器算进去，一旦页面能缩放就整体漂移。
 *
 * 纯 Compose + Material 3，无 emoji。
 */

/** 一页已渲染好的位图与它上面识别出的词盒（坐标口径：**位图像素**）。 */
internal data class LivePage(
    val bitmap: Bitmap,
    val boxes: List<OcrWordBox>
)

/** 实况框选可放大的倍数范围。下限 1 表示不允许缩到比整页更小。 */
/**
 * 判断落点附近有没有文字（带容差），供"挪画面 vs 取词"分流。
 *
 * 容差 14dp：词盒只框笔画，手���压得偏一点仍算指着这个字；
 * 容差过大会把行距误判成"有字"，那又会让挪画面变回与取词打架。
 */
private fun hitBoxesForPan(
    boxes: List<OcrWordBox>,
    at: Offset,
    padPx: Float = 14f
): Boolean {
    val l = at.x - padPx
    val t = at.y - padPx
    val r = at.x + padPx
    val b = at.y + padPx
    return boxes.any { box ->
        val rb = box.bounds
        rb.left < r && rb.right > l && rb.top < b && rb.bottom > t
    }
}

private const val MIN_PAGE_ZOOM = 1f
private const val MAX_PAGE_ZOOM = 5f

/** 比对剪贴板文字时忽略一切空白（跨行复制会带换行，本页文字里也可能有）。 */
private val WHITESPACE = Regex("\\s+")

/**
 * 同一次「系统复制」只允许一列弹出工具条的认领窗口（毫秒）。
 * 多列各自监听同一块剪贴板，而短词可能同时出现在几页里；不认领，
 * 复制一个"的/ the"就可能两三个气泡一起冒出来（这正是旧版只给首列监听的原因）。
 */
private const val CLIP_CLAIM_WINDOW_MS = 500L

/** 最近一次已被某一列认领的剪贴板文本（去空白口径），见 [claimClipboardForOnePopup]。 */
private var lastClaimedClip: String? = null
private var lastClaimedAtMs: Long = 0L

/**
 * 判定"本列是否可以为这段剪贴板文本弹出工具条"，并顺手完成认领。
 *
 * 规则：文本必须先通过本页子串校验（不含本页文字的一律不弹），随后
 * 与最近一次认领比对 —— 同一段文本在认领窗口内已被别的列弹过就让行，
 * 否则由本列认领并返回 true。监听按列序注册，首列先收到回调，
 * 因此打平时自然由首列出面，与旧版"只有首列监听"的观感一致。
 *
 * 一次性复制事件的回调在几百毫秒内全部送达，500ms 的窗口既罩得住整批列，
 * 又不会挡住用户几秒后重新复制同一段文本时再弹一次。
 */
private fun claimClipboardForOnePopup(normalizedClip: String): Boolean {
    val now = System.currentTimeMillis()
    if (normalizedClip == lastClaimedClip && now - lastClaimedAtMs <= CLIP_CLAIM_WINDOW_MS) {
        return false
    }
    lastClaimedClip = normalizedClip
    lastClaimedAtMs = now
    return true
}

/**
 * 极小的 LRU 页缓存（默认 3 页，正好覆盖「三列并排」的场景）。
 *
 * 目的：翻页切回上一页时不必重新渲染位图 + 重新跑 ML Kit（两者加起来常常
 * 几百毫秒到 1 秒）。键由调用方给定（文档 id + 页号）。
 *
 * ── 为什么淘汰时**不** recycle ────────────────────────────────────────
 * 被 LRU 淘汰的那一页往往刚刚还在屏幕上显示，Compose 可能还有一帧要读它；
 * 提前 `Bitmap.recycle()` 会画到已回收的位图而崩。位图一旦不再被引用，其
 * 原生像素内存会被 GC 回收（API 26+ 不再依赖显式 recycle），所以这里只丢弃
 * 引用。**只有 [clear]**（离开文档 / 退出阅读器）才统一 recycle —— 那时界面
 * 已经不再显示这些位图，是安全的。
 */
internal class LiveTextPageCache(private val capacity: Int = 3) {

    private class Entry(val page: LivePage, var stamp: Long)

    private val lock = Any()
    private val entries = LinkedHashMap<String, Entry>()
    private var clock = 0L

    fun get(key: String): LivePage? = synchronized(lock) {
        val e = entries[key] ?: return@synchronized null
        e.stamp = ++clock
        e.page
    }

    fun put(key: String, page: LivePage) = synchronized(lock) {
        entries[key] = Entry(page, ++clock)
        while (entries.size > capacity) {
            val oldest = entries.minByOrNull { it.value.stamp } ?: break
            entries.remove(oldest.key)
        }
    }

    /** 丢弃全部缓存并回收位图（仅在不显示它们的时机调用）。 */
    fun clear() = synchronized(lock) {
        for (e in entries.values) {
            try {
                e.page.bitmap.recycle()
            } catch (_: Throwable) {
            }
        }
        entries.clear()
    }
}

/**
 * 单页「实况选词」卡片：把 PDF 的一页渲染成图像，按用户在底栏选定的
 * [method] 挂上不同的选词层。
 *
 * @param method [LiveSelectionMethod.BOX_DRAG] 走位图 + 自绘框选；
 *   [LiveSelectionMethod.SYSTEM_NATIVE] 走 OCR 文字层 + 原生 SelectionContainer。
 * @param isImmersive 沉浸态下连页码角标也收起（整屏只有正文）。
 * @param isPrimaryPage 多列并排时是否是第一列。旧实现用它把"原生选区 → 剪贴板 →
 *   工具条"整条通路关死在首列上，代价是非首列**永远不会**弹工具条（从第二列选字
 *   复制，屏幕上毫无反应）。现在改为各列都监听、按剪贴板文本"单次认领"去重
 *   （见 [NativeSelectionTextLayer] 内的认领逻辑），此参数保留以稳定调用方 API。
 * @param syntaxUnitsForPage 属于**本页**的句法单元（由宿主按文档 + 页号筛过）。
 *   非空即按 [OcrWordBox] 包围盒给命中的词框上色并挂角色小标签。
 * @param onHighlight/onAddNote 会带上该卡的 **0 基页号**（与 [DocumentHighlight] 一致）
 */
@Composable
internal fun LiveTextPageCard(
    source: PdfPageSource?,
    sourceResolved: Boolean,
    cache: LiveTextPageCache,
    document: ReaderDocument,
    pageNumber: Int,
    method: LiveSelectionMethod,
    inputMode: com.treechat.workbench.data.prefs.InputMode =
        com.treechat.workbench.data.prefs.InputMode.AUTO,
    /** 页内查找关键词（Ctrl+F），命中词框会被高亮。 */
    findQuery: String = "",
    /** 当前定位到第几处命中（0 基）。 */
    findActiveIndex: Int = 0,
    /** 本页命中总数回报。 */
    onFindCount: (Int) -> Unit = {},
    isImmersive: Boolean,
    isPrimaryPage: Boolean,
    /** 本页在多列布局中的列序（0 起）与总列数。
     *  两者共同参与 pageKey：列数一变就必须复位缩放/平移，否则布局重排后
     *  残留的变换会让画面"莫名其妙翻转"。 */
    columnIndex: Int = 0,
    columnCount: Int = 1,
    onAskQuestion: (String) -> Unit,
    onHighlight: (String, Int) -> Unit,
    onAddNote: (String, Int) -> Unit,
    onOpenEtymology: (String) -> Unit,
    onTapBlank: () -> Unit,
    /** 同文本超链接目标：正文里被分支引用过的段落会被标出并可点。 */
    quoteLinkTargets: List<com.treechat.workbench.ui.selection.QuoteLinkTarget> = emptyList(),
    onQuoteLinkClick: (ConversationNode) -> Unit = {},
    /** 标签落点回调（宿主可用来做提示）；标签的持久化由本卡自己负责。 */
    onAddPageTag: (normX: Float, normY: Float) -> Unit = { _, _ -> },
    /** 点已有标签 → 开它的小窗。 */
    onPageTagClick: (PageTagStore.PageTag) -> Unit = {},
    /**
     * 在本页上**横向滑动翻页**：direction 为 +1 下一段、-1 上一段。
     *
     * 为什么放在卡片内部而不是复用外层的翻页动画：
     * 外层那条 `turnAnimationEnabled = ... && !useLiveText` 明确把实况卡排除了
     * （注释写的理由是"实况卡内部已有单指缩放平移手势，会吃掉横向拖动"）。
     * 那个理由在**双指**上成立，但单指横向滑动本来是可以还给翻页的，
     * 所以这里单独做一层：只在"单指、横向位移超过阈值、未放大"时触发，
     * 既不与双指缩放打架，也不会影响取词（取词由内层浮层优先消费）。
     */
    onSwipePage: (Int) -> Unit = {},
    /** 双击本页放大为一页沉浸（仅多列时可用）。 */
    onDoubleTapZoom: (() -> Unit)? = null,
    onTranslate: ((String) -> Unit)? = null,
    onAnalyzeSyntax: ((String) -> Unit)? = null,
    syntaxUnitsForPage: List<SyntaxUnit> = emptyList(),
    syntaxActive: Boolean = false,
    /** 启用的考纲 token（空 = 关闭考纲），点击命中词盒画考纲色下划线。 */
    activeExamTokens: Set<String> = emptySet(),
    /** 本页文本上报（OCR 文字层的 text，供边栏统计本页生词）。 */
    onPageText: (String) -> Unit = {},
    /** 选句翻译 / 句法标注状态（本页相关时在页底出卡）。 */
    toolState: SentenceToolState? = null,
    /** 句法卡「翻译本段」。 */
    onTranslateSentence: (String) -> Unit = {},
    onClearSyntax: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme

    // ⚠️ pageKey 必须含**列数**（isPrimaryPage 之外还要传列序）。
    //
    // 原先只由 文档 + 页号 组成，于是"单页 → 三页"这类**列数变化**不改变 key，
    // 而下面的复位 effect 是 `LaunchedEffect(pageKey, method)` —— 不触发。
    // 结果：切换页数布局重排时，上一帧残留的 zoom / panX 依然作用在新布局上，
    // 表现为"莫名其妙把屏幕翻过去、来回翻转"。
    // 把列数纳入 key 后，列一变 key 就变，复位必然发生。
    val columnKey = "${document.id}#$pageNumber#$columnIndex/$columnCount"
    val pageKey = columnKey

    // 命中缓存就直接用，避免「切回来再识别一遍」
    var rendered by remember(pageKey) { mutableStateOf(cache.get(pageKey)) }
    var loading by remember(pageKey) { mutableStateOf(rendered == null) }

    LaunchedEffect(pageKey, source, sourceResolved) {
        // ① 缓存命中：立即呈现，不重新渲染/识别
        val cached = cache.get(pageKey)
        if (cached != null) {
            rendered = cached
            loading = false
            return@LaunchedEffect
        }
        if (source == null) {
            // 页源还没打开完 → 保持加载态；确认打不开 → 停加载，落到失败提示
            loading = !sourceResolved
            return@LaunchedEffect
        }
        loading = true
        // ② 渲染位图 + 识别词盒，全部在 IO 线程（识别数百毫秒，且几何回退要逐像素扫描）
        val page = withContext(Dispatchers.IO) {
            val scale = PdfOcr.resolveRenderScale(context)
            val bmp = source.renderPage(pageNumber - 1, scale) ?: return@withContext null
            LivePage(bitmap = bmp, boxes = OcrEngine.recognizeOrFallback(bmp))
        }
        if (page != null) {
            cache.put(pageKey, page)
            rendered = page
        }
        loading = false
    }

    // 实际显示区域尺寸（像素），用来把位图坐标换算到"内容坐标"
    var boxSize by remember { mutableStateOf(IntSize.Zero) }

    // 微型小窗标签：本卡自己持有读写。它是纯界面层的"位置注释"，
    // 与识别出的文字无关，所以刻意不上升到宿主的状态管理里去。
    val tagStore = remember { PageTagStore(context) }
    var pageTags by remember(document.id, pageNumber) {
        mutableStateOf(tagStore.load(document.id, pageNumber))
    }
    fun refreshTags() {
        pageTags = tagStore.load(document.id, pageNumber)
    }

    // ★ 坐标换算：Image 用 ContentScale.Fit 铺满同一个 Box，所以显示尺寸就是 Box 的尺寸；
    //   scale = min(可用宽/位图宽, 可用高/位图高)（等比、取小，保证整页可见）；
    //   offsetX/Y = 剩余空间的一半（Fit 的居中留白）。
    //   词盒 bounds 是位图像素，乘 scale 再加 offset 即得**内容坐标**。
    //   内容坐标这一层就是那个被 graphicsLayer 缩放/平移的容器，
    //   所以放大平移之后再框选也不会漂（见文件头说明）。
    val displayBoxes = remember(rendered, boxSize) {
        val page = rendered
        if (page == null || boxSize.width <= 0 || boxSize.height <= 0) {
            emptyList<OcrWordBox>()
        } else {
            val bw = page.bitmap.width.toFloat()
            val bh = page.bitmap.height.toFloat()
            if (bw <= 0f || bh <= 0f) {
                emptyList<OcrWordBox>()
            } else {
                val scale = minOf(boxSize.width / bw, boxSize.height / bh)
                val dx = (boxSize.width - bw * scale) / 2f
                val dy = (boxSize.height - bh * scale) / 2f
                page.boxes.map { box ->
                    val b = box.bounds
                    box.copy(
                        bounds = Rect(
                            left = b.left * scale + dx,
                            top = b.top * scale + dy,
                            right = b.right * scale + dx,
                            bottom = b.bottom * scale + dy
                        )
                    )
                }
            }
        }
    }

    // 本页的句法标注：按显示坐标的词框算好"哪个框属于第几层"，浮层只负责画。
    // 只在句法结果确实属于本页时才非空（宿主已按文档 + 页号筛过）。
    val syntaxMarks = remember(displayBoxes, syntaxUnitsForPage) {
        buildSyntaxBoxMarks(displayBoxes, syntaxUnitsForPage)
    }

    // ---- 生词命中词盒（考纲色下划线的绘制依据）----
    val vocabBoxIds = remember(displayBoxes, activeExamTokens) {
        findVocabBoxes(displayBoxes, activeExamTokens).toSet()
    }
    // ---- 本页文本上报：文字层 text（与选区同源，边栏按它匹配）----
    val layerForReport = remember(displayBoxes) { buildOcrTextLayer(displayBoxes) }
    LaunchedEffect(layerForReport?.text) { onPageText(layerForReport?.text.orEmpty()) }

    // 句法标注同样不画在原文页：统一经右侧边栏「句法」页签展示。

    // ── 缩放 / 平移（只作用于位图框选路径）────────────────────────────────
    // 放在**词盒与外层之间的同一个容器**上：Image、词盒浮层、手势面同属这一层，
    // 于是缩放平移对三者是一次性的整体变换，不需要任何坐标补偿。
    var zoom by remember(pageKey) { mutableStateOf(MIN_PAGE_ZOOM) }
    var panX by remember(pageKey) { mutableStateOf(0f) }
    var panY by remember(pageKey) { mutableStateOf(0f) }
    // 换页 / 换选词方式就把缩放复位，否则会带着上一页的放大率看到一脸空白
    LaunchedEffect(pageKey, method) {
        zoom = MIN_PAGE_ZOOM
        panX = 0f
        panY = 0f
    }

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = scheme.surfaceBright,
        tonalElevation = 1.dp,
        shadowElevation = 2.dp,
        modifier = modifier.fillMaxSize()
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(10.dp))
                .onSizeChanged { boxSize = it }
                // 双指外扩/捏合缩放 + 平移。单指平移由 detectTransformGestures 一并处理：
                // 未放大时上下限都是 0，单指拖动等于不动（不会把页面拖出屏幕外）。
                //
                // ⚠️ 手势在**所有选词方式下都要挂**，不能只在 BOX_DRAG 时挂。
                //
                // 原实现外面套了 `if (method == BOX_DRAG)`，于是默认的「条畅选词」
                // 模式下这一整段 pointerInput 根本不存在 —— 表现就是"双指不能放大"。
                // 选词方式与"能不能缩放页面"是两件正交的事：前者决定单指怎么取词，
                // 后者决定双指怎么操作页面。把缩放绑到选词方式上是耦合错了。
                //
                // ---- 单指横向滑动翻页 / 双击放大 ----
                //
                // 与上面那个 `detectTransformGestures` **并列**，而不是合并：
                // 两个检测器会同时收到事件，所以这里必须自己把边界划清楚，否则
                // 会出现"想翻页结果页面跟着挪"或"想放大却翻了页"。
                //
                // 判定条件（缺一不可）：
                //   ① **只有一根手指** —— 两根及以上一律让给缩放检测器；
                //   ② 位移以**横向为主**（|dx| > |dy|）—— 纵向留给取词与滚动；
                //   ③ 位移超过阈值 —— 小抖动不算翻页；
                //   ④ 当前**未放大** —— 放大后单指是用来拖动页面的，
                //      这时翻页会和"把放大的内容拖回来"打架。
                // ---- 缩放 / 平移：与选词**互斥**，按落点分流 ----
                //
                // ⚠️ 这一层历史上和选词抢过同一个拖动，是"选词与挪页面打架"的来源。
                //
                // 曾经的写法是 `detectTransformGestures`：它对**单指或左键拖动**
                // 同样报 pan，于是用户"按住一个字往旁边拖"时页面**同时被平移**；
                // 页面一移，光标对应的内容位置就变了 —— 看着拖的是甲词，
                // 实际选到乙词。未放大时平移量被钳死为 0，所以这病只在放大后发作。
                //
                // 现在两条路按落点分开，互不重叠：
                //  · 落点在文字上（或容差内）→ 整条手势归选词，本层不碰；
                //  · 落点在空白处 → 本层负责挪画面；
                //  · 两根及以上手指 → 一律缩放/平移（捏合）。
                // 另外每次循环都检查"事件是否已被选词消费"，一旦被消费立刻放手，
                // 从机制上杜绝同一帧内两条手势并行。
                .pointerInput(pageKey, displayBoxes) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val isMouse = down.type == PointerType.Mouse
                        // 桌面指针与触屏单指的落点分流：
                        //   · 落点在文字上（或容差内）→ 整条手势归选词，本层不碰；
                        //   · 落点在空白处 → 本层负责挪画面。
                        //
                        // ⚠️ 触屏单指的"挪画面"只在**放大态**开启：
                        // 未放大时平移量被钳为 0，开启只会白白消费掉移动 ——
                        // 滑动翻页层（下一段）见 isConsumed 就退出，
                        // 于是"在空白处快扫想翻页"被平移层抢光，页面永远不翻。
                        // 放大后空白单指平移可用，正是读扫描件纯粹靠双手挪纸的刚需。
                        var panning = !hitBoxesForPan(displayBoxes, down.position) &&
                            (isMouse || zoom > 1.001f)
                        var handedOver = false
                        while (true) {
                            val event = awaitPointerEvent()
                            val pressed = event.changes.filter { it.pressed }
                            if (pressed.isEmpty()) break
                            // 选词层已消费这次移动 → 本层立刻放手，绝不并行再动页面。
                            // 缺这一句，选词与平移就会在同一帧里各走各的。
                            if (event.changes.any { it.isConsumed }) {
                                if (pressed.size > 1) {
                                    panning = true // 双指：交给缩放/平移
                                } else {
                                    handedOver = true // 单指已并入选词，本次手势不再接手
                                }
                            }
                            if (handedOver && pressed.size < 2) continue
                            if (pressed.size >= 2) panning = true
                            if (!panning) continue
                            val zoomChange = event.calculateZoom()
                            val panChange = event.calculatePan()
                            if (zoomChange == 1f && panChange == Offset.Zero) continue
                            val next = (zoom * zoomChange).coerceIn(MIN_PAGE_ZOOM, MAX_PAGE_ZOOM)
                            val maxX = size.width * (next - 1f) / 2f
                            val maxY = size.height * (next - 1f) / 2f
                            zoom = next
                            panX = (panX + panChange.x).coerceIn(-maxX, maxX)
                            panY = (panY + panChange.y).coerceIn(-maxY, maxY)
                            event.changes.forEach { if (it.pressed) it.consume() }
                        }
                    }
                }
                // ---- 鼠标滚轮 / 触控板双指：在**指针所在处**缩放 ----
                //
                // 以指针为锚点，而不是以屏幕中心为锚点：
                // 看扫描件时用户的意图几乎总是"把光标指着的那一行放大看清"，
                // 中心缩放会把目标顶出屏幕，还得再拖回来 —— 那是浏览器的老毛病。
                //
                // 锚点公式（与上面的 graphicsLayer 变换严格对应：先绕中心缩放，再平移）：
                //   变换前屏幕上 p 点下面的内容坐标 c = center + (p - center - pan) / zoom
                //   要让 c 在缩放后仍落在 p 上： pan' = p - center - zoom' * (c - center)
                // 化简即 pan' = p - center - (zoom'/zoom) * (p - center - pan)。
                .pointerInput(pageKey) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.type != PointerEventType.Scroll) continue
                            val change = event.changes.firstOrNull() ?: continue
                            val dy = change.scrollDelta.y
                            if (dy == 0f) continue
                            val factor = if (dy < 0f) 1.12f else 1f / 1.12f
                            val next = (zoom * factor).coerceIn(MIN_PAGE_ZOOM, MAX_PAGE_ZOOM)
                            if (next != zoom) {
                                val cx = size.width / 2f
                                val cy = size.height / 2f
                                val p = change.position
                                val dx = p.x - cx - panX
                                val dyv = p.y - cy - panY
                                val k = next / zoom
                                val maxX = size.width * (next - 1f) / 2f
                                val maxY = size.height * (next - 1f) / 2f
                                zoom = next
                                panX = (p.x - cx - k * dx).coerceIn(-maxX, maxX)
                                panY = (p.y - cy - k * dyv).coerceIn(-maxY, maxY)
                            }
                            change.consume()
                        }
                    }
                }
                .pointerInput(pageKey, onSwipePage) {
                    val swipeThreshold = size.width * 0.18f
                    val doubleTapWindow = viewConfiguration.doubleTapTimeoutMillis.toLong()
                    var lastTapUptime = 0L
                    // 「按住多久就认为是要选词」的判定窗口。
                    //
                    // 这是让**滑动翻页**与**长按选词**共存的关键：两者用的是同一根手指，
                    // 必须靠"用户按下后停顿了多久"来分辨意图，而不是靠模式开关 ——
                    // 后者会逼用户在"能翻页"与"能取词"之间二选一，那是错的取舍。
                    //
                    // 取 150ms：明显短于系统的长按阈值（约 300–500ms），
                    // 所以"想取词的人"几乎必然在窗口内保持不动 → 判定为选词，我方退出；
                    // 而"想翻页的人"手指是持续横向移动的，不会原地停 150ms。
                    val selectIntentWindow = 150L

                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        // ⚠️ 桌面指针（鼠标/触控板）**完全不参与滑动翻页**。
                        //
                        // 这个手势按"横向位移超过屏宽 18%"就翻页，而用鼠标选一个短语
                        // 很容易横向划过 18% —— 于是"选词"变成了"翻页"，选区连同页面一起没了。
                        // 桌面端翻页有滚轮、方向键与翻页按钮，不需要也不该用拖动。
                        if (down.type == PointerType.Mouse) return@awaitEachGesture
                        val downTime = android.os.SystemClock.uptimeMillis()
                        var totalDx = 0f
                        var totalDy = 0f
                        var multiTouch = false
                        var lastPos = down.position
                        var fired = false

                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Main)
                            val pressed = event.changes.filter { it.pressed }
                            if (pressed.isEmpty()) break
                            // 第二根手指落下 → 判定为双指操作，本手势整体作废
                            if (pressed.size > 1) {
                                multiTouch = true
                                break
                            }
                            val change = pressed.firstOrNull() ?: break
                            // 内层（选词浮层）已经吃掉这次拖动 → 它正在划词，本手势立刻放手。
                            // 缺这一条时"按住拖选"会同时被判成横向滑动，一次拖动既选词又翻页。
                            if (change.isConsumed) break
                            totalDx += change.position.x - lastPos.x
                            totalDy += change.position.y - lastPos.y
                            lastPos = change.position

                            val elapsed = android.os.SystemClock.uptimeMillis() - downTime

                            // ① 已按住超过窗口 = 用户在"瞄准一个词"，本手势彻底放手，
                            //    一个事件都不消费，让选词检测器完整接管（否则它连长按都起不来）。
                            if (elapsed >= selectIntentWindow) break

                            // ② 窗口内就横向挪了一大截 = 用户在翻页，这时才消费并翻页。
                            if (!multiTouch && zoom <= MIN_PAGE_ZOOM * 1.01f &&
                                kotlin.math.abs(totalDx) > swipeThreshold &&
                                kotlin.math.abs(totalDx) > kotlin.math.abs(totalDy) * 1.4f
                            ) {
                                change.consume()
                                onSwipePage(if (totalDx < 0) 1 else -1)
                                fired = true
                                break
                            }
                            // ③ 窗口内、没横向挪动：**不消费**，把这次按压留给选词。
                            //    之前这里无条件 consume()，选词手势一起手就被吃掉，
                            //    正是"滑动翻页和选词打架"的根因。
                        }

                        // 双击放大：两指都抬起后，看这次是否是一次"短且没怎么动"的点按
                        if (!fired && !multiTouch && zoom <= MIN_PAGE_ZOOM * 1.01f &&
                            kotlin.math.abs(totalDx) < swipeThreshold &&
                            kotlin.math.abs(totalDy) < swipeThreshold &&
                            downTime - lastTapUptime < doubleTapWindow
                        ) {
                            onDoubleTapZoom?.invoke()
                            lastTapUptime = 0L
                        } else if (!fired && !multiTouch) {
                            lastTapUptime = downTime
                        }
                    }
                }
        ) {
            val page = rendered
            if (page != null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = zoom
                            scaleY = zoom
                            translationX = panX
                            translationY = panY
                        }
                ) {
                    // 扫描原图（位图）永远在底层呈现，无论是条畅选词还是电脑框选，绝不遮挡原图！
                    Image(
                        bitmap = page.bitmap.asImageBitmap(),
                        contentDescription = "第 $pageNumber 页扫描图像",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                    )

                    // 叠加在原图之上的实况选词浮层（支持条畅手柄选词与电脑框选切换）
                    LiveTextComposeOverlay(
                        wordBoxes = displayBoxes,
                        selectionMethod = method,
                        inputMode = inputMode,
                        findQuery = findQuery,
                        findActiveIndex = findActiveIndex,
                        onFindCount = onFindCount,
                        onAskQuestion = onAskQuestion,
                        onOpenEtymology = onOpenEtymology,
                        onTapBlank = onTapBlank,
                        quoteLinkTargets = quoteLinkTargets,
                        onQuoteLinkClick = onQuoteLinkClick,
                        pageTags = pageTags,
                        // 只报"哪一页"，不掺坐标：浮层靠它判断是否换页，
                        // 沉浸态切换、缩放、旋转都不该被误判成换页。
                        pageIdentity = columnKey,
                        onAddTag = { pos ->
                            if (boxSize.width > 0 && boxSize.height > 0) {
                                val nx = pos.x / boxSize.width
                                val ny = pos.y / boxSize.height
                                tagStore.add(document.id, pageNumber, nx, ny)
                                refreshTags()
                                onAddPageTag(nx, ny)
                            }
                        },
                        onTagClick = onPageTagClick,
                        syntaxMarks = syntaxMarks,
                        vocabBoxIds = vocabBoxIds,
                        onTranslate = onTranslate,
                        onAnalyzeSyntax = onAnalyzeSyntax,
                        syntaxActive = syntaxActive,
                        onClearSyntax = onClearSyntax,
                        containerScale = zoom,
                        containerPan = Offset(panX, panY),
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            when {
                loading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(strokeWidth = 2.dp)
                    }
                }
                page == null && sourceResolved -> {
                    // 诚实失败：不假装成功，也不画空白页
                    Text(
                        text = if (source == null) {
                            "该文档无法按图像渲染（本地 PDF 文件不可用），已回落到文本阅读。"
                        } else {
                            "第 $pageNumber 页图像渲染失败，已回落到文本阅读。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .fillMaxWidth()
                            .padding(16.dp)
                    )
                }
            }

            // ---- 沉浸态左右边缘翻页钮 ----
            //
            // 为什么只在沉浸态显示：正常态有底部翻页条与滑动翻页，边缘再放按钮是重复；
            // 沉浸态把一切都收了（整屏只剩正文），横向滑动的触屏手势又容易被
            // "按住文字选词"抢走 —— 用户要的是**始终可达**的显式翻页入口。
            //
            // 按钮是真实组件、会拦下自己圆区内的按压：这正是想要的行为
            //（按它=翻页，绝不被选词/挪纸判走）；区域刻意收小并贴边，
            // 扫描页文字顶到边的概率低，挤占选词起手的面积可忽略。
            if (isImmersive && page != null) {
                listOf(
                    Alignment.CenterStart to -1,
                    Alignment.CenterEnd to 1
                ).forEach { (anchor, direction) ->
                    Surface(
                        onClick = { onSwipePage(direction) },
                        shape = CircleShape,
                        color = scheme.scrim.copy(alpha = 0.32f),
                        modifier = Modifier
                            .align(anchor)
                            .padding(horizontal = 8.dp)
                    ) {
                        Icon(
                            imageVector = if (direction < 0) {
                                Icons.AutoMirrored.Outlined.KeyboardArrowLeft
                            } else {
                                Icons.AutoMirrored.Outlined.KeyboardArrowRight
                            },
                            contentDescription = if (direction < 0) "上一页" else "下一页",
                            tint = scheme.onPrimary,
                            modifier = Modifier
                                .padding(6.dp)
                                .size(28.dp)
                        )
                    }
                }
            }

            // 页码小标（多列并排时用来区分是哪一页）。
            // 沉浸态下它是"悬浮角标"，一并收起 —— 整屏只留正文。
            if (page != null && !isImmersive) {
                Text(
                    text = "P.$pageNumber",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = scheme.primary,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                )
            }

        }
    }
}

/**
 * 「系统原生 SelectionContainer」文字层。
 *
 * ## 为什么用剪贴板做桥
 * Compose 1.6 的原生选区 API（`Selection` / `SelectionRegistrar` / `Selectable`
 * 以及带 `selection` 参数的 `SelectionContainer` 重载）**全部是 internal**，
 * 应用侧既读不到选区偏移，也拿不到选区回调。于是这里让原生选区照常工作
 * （原生手柄 + 放大镜 + 系统菜单），再用「系统菜单里点复制 → 剪贴板变化」这条
 * 通路把文字接进本工程的工具条（提问/划线/笔记/词源）。这一点在界面上如实写明，
 * 不让用户以为工具条会自己知道选区。
 *
 * @param bridgeClipboard 本层是否注册剪贴板监听。多列并排时**每一列都注册**
 *   （旧实现只给首列，非首列选字复制后永远等不到工具条），重复弹出的风险由
 *   [claimClipboardForOnePopup] 的"单次认领"兜住：监听按列序注册、先到先得，
 *   同一段文本 500ms 内只有一列会真正弹出。
 */
@Composable
private fun NativeSelectionTextLayer(
    text: String,
    bridgeClipboard: Boolean,
    onAskQuestion: (String) -> Unit,
    onHighlight: (String) -> Unit,
    onAddNote: (String) -> Unit,
    onOpenEtymology: (String) -> Unit,
    onTapBlank: () -> Unit,
    /** 同文本超链接目标：正文里被分支引用过的段落会被标出并可点。 */
    quoteLinkTargets: List<com.treechat.workbench.ui.selection.QuoteLinkTarget> = emptyList(),
    onQuoteLinkClick: (ConversationNode) -> Unit = {},
    onTranslate: ((String) -> Unit)? = null,
    onAnalyzeSyntax: ((String) -> Unit)? = null,
    syntaxActive: Boolean = false,
    onClearSyntax: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val scrollState = rememberScrollState()

    // 阅读排版：字号 / 行距 / 护眼底色（与文本阅读路径同一套设置）。
    val readerTypography = rememberReaderTypography()
    val pageEyeCare = readerTypography.eyeCare.resolve(isSystemInDarkTheme())

    // 由「系统菜单复制」接住的文字；非空即弹工具条
    var picked by remember(text) { mutableStateOf<String?>(null) }
    // 只认"确实是从本页文字层里复制出来的"：去掉所有空白后必须是本页文字的子串。
    // 否则用户在别的窗口（比如 TalkTree 悬浮小窗）复制任何东西，这里都会莫名弹出工具条。
    val normalizedPage = remember(text) { text.replace(WHITESPACE, "") }

    DisposableEffect(text, bridgeClipboard) {
        val manager = if (bridgeClipboard) {
            context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        } else {
            null
        }
        val listener = if (manager != null) {
            ClipboardManager.OnPrimaryClipChangedListener {
                val clip = runCatching {
                    manager.primaryClip
                        ?.takeIf { it.itemCount > 0 }
                        ?.getItemAt(0)
                        ?.coerceToText(context)
                        ?.toString()
                }.getOrNull()
                val normalizedClip = clip?.replace(WHITESPACE, "").orEmpty()
                if (normalizedClip.isNotEmpty() &&
                    normalizedPage.contains(normalizedClip) &&
                    claimClipboardForOnePopup(normalizedClip)
                ) {
                    picked = clip
                }
            }
        } else {
            null
        }
        if (manager != null && listener != null) {
            manager.addPrimaryClipChangedListener(listener)
        }
        onDispose {
            if (manager != null && listener != null) {
                manager.removePrimaryClipChangedListener(listener)
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            // 只观察、不消费的点按：
            // `detectTapGestures` 会 `consume()` 按下事件，压在原生选区上会把
            // SelectionContainer 的选区手势一起吃掉；这里自己写一个不消费的版本。
            .pointerInput(text) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var tapped = false
                    withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                        var pending = true
                        while (pending) {
                            val event = awaitPointerEvent()
                            if (event.changes.size > 1) {
                                pending = false
                            } else {
                                val change = event.changes.firstOrNull { it.id == down.id }
                                when {
                                    change == null -> pending = false
                                    !change.pressed -> {
                                        tapped = true
                                        pending = false
                                    }
                                    (change.position - down.position).getDistance() >
                                        viewConfiguration.touchSlop -> pending = false
                                }
                            }
                        }
                    }
                    if (tapped) {
                        if (picked != null) picked = null else onTapBlank()
                    }
                }
            }
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (text.isBlank()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "本页未识别出可选择的文字，请切回「实况框选」查看原图。",
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            } else {
                val controller = remember(text) { MessageSelectionController() }
                var toolbarAnchor by remember { mutableStateOf<Rect?>(null) }

                Box(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(scrollState)
                            .pointerHoverIcon(PointerIcon.Text)
                    ) {
                        SelectableMarkdownText(
                            markdown = text,
                            controller = controller,
                            selectionScheme = SelectionScheme.TOUCH_LONG_PRESS,
                            baseFontSize = readerTypography.fontSizeSp.sp,
                            selectionColor = scheme.primary.copy(alpha = 0.28f),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                            onSelectionChanged = { hasSel ->
                                if (hasSel) {
                                    val sel = controller.selectedText()
                                    val bound = controller.selectionBoundingRect()
                                    if (sel.isNotBlank() && bound != null) {
                                        picked = sel
                                        toolbarAnchor = bound
                                    }
                                } else {
                                    picked = null
                                    toolbarAnchor = null
                                }
                            },
                            onAsk = null,
                            onCopy = null,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    // 100% 沿用阅读器原生的 FloatingSelectionToolbar，7 个选项卡完全保持不变！
                    FloatingSelectionToolbar(
                        visible = picked != null && toolbarAnchor != null,
                        anchorRect = toolbarAnchor ?: Rect.Zero,
                        onAsk = {
                            val t = picked
                            controller.clear()
                            picked = null
                            if (!t.isNullOrBlank()) onAskQuestion(t)
                        },
                        onCopy = {
                            val t = picked
                            controller.clear()
                            picked = null
                            if (!t.isNullOrBlank()) {
                                val clip = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                clip?.setPrimaryClip(android.content.ClipData.newPlainText("text", t))
                            }
                        },
                        onSelectAll = {},
                        onDismiss = {
                            controller.clear()
                            picked = null
                        },
                        onEtymology = {
                            val t = picked
                            controller.clear()
                            picked = null
                            if (!t.isNullOrBlank()) onOpenEtymology(t)
                        },

                        onTranslate = onTranslate?.let { callback ->
                            {
                                val t = picked
                                controller.clear()
                                picked = null
                                if (!t.isNullOrBlank()) callback(t)
                            }
                        },
                        onSyntax = onAnalyzeSyntax?.let { callback ->
                            {
                                val t = picked
                                controller.clear()
                                picked = null
                                if (!t.isNullOrBlank()) callback(t)
                            }
                        },
                        showSelectAll = false,
                        askLabel = "提问",
                        highlightLabel = "划线"
                    )
                }
            }

            Text(
                text = "条畅选词模式：水滴手柄自由拖拉长按选词，悬浮弹出提问与划线工具条。要直接在扫描原图上拉框圈词，请在底栏切换为「电脑框选」。",
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }
    }
}

/**
 * 把一页的 OCR 词盒还原成可读文字（原生选区那一档用）。
 *
 * 分两步：先按纵坐标聚成行（同一行的判定用行高的 60% 做容差），
 * 再把行内词按横坐标排序拼接。
 */
private fun pageText(boxes: List<OcrWordBox>): String {
    if (boxes.isEmpty()) return ""
    val sorted = boxes.sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }))
    val lines = ArrayList<MutableList<OcrWordBox>>()
    for (box in sorted) {
        val current = lines.lastOrNull()
        val anchor = current?.firstOrNull()
        val sameLine = anchor != null &&
            abs(box.bounds.top - anchor.bounds.top) <=
            maxOf(anchor.bounds.height, box.bounds.height) * 0.6f
        if (sameLine) current.add(box) else lines.add(mutableListOf(box))
    }
    return lines.joinToString("\n") { line ->
        joinWords(line.sortedBy { it.bounds.left }.map { it.text })
    }
}

/** 行内拼接：见 [needsSpaceBetween] 的判据。 */
private fun joinWords(words: List<String>): String {
    val sb = StringBuilder()
    words.forEachIndexed { index, word ->
        if (index > 0 && needsSpaceBetween(words[index - 1].lastOrNull(), word.firstOrNull())) {
            sb.append(' ')
        }
        sb.append(word)
    }
    return sb.toString()
}

/**
 * 词间要不要补一个空格：CJK 相邻不补，只要有一侧是 ASCII（拉丁字母/数字/半角标点）
 * 就补。ML Kit 中文识别会把汉字逐字切盒、把西文切成整词，用这条判据拼回去
 * 最接近原文排版。
 */
private fun needsSpaceBetween(prev: Char?, next: Char?): Boolean {
    if (prev == null || next == null) return false
    return prev.code < 128 || next.code < 128
}
