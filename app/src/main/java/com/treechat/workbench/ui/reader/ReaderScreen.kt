package com.treechat.workbench.ui.reader

import android.app.Activity
import android.content.pm.ActivityInfo
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.foundation.verticalScroll
import com.treechat.workbench.ui.explorer.FreePlacement
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ViewList
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DocumentScanner
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Sort
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import com.treechat.workbench.etymology.data.VocabIndex
import com.treechat.workbench.ui.selection.QuoteLinkMatcher
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.treechat.workbench.data.model.KnowledgeChunk
import com.treechat.workbench.data.model.KnowledgeDocument
import com.treechat.workbench.etymology.ui.EtymoScopeBottomSheet
import com.treechat.workbench.ocr.OcrEngine
import com.treechat.workbench.ocr.PdfOcr
import com.treechat.workbench.ocr.PdfPageSource
import com.treechat.workbench.rag.Chunker
import com.treechat.workbench.rag.KnowledgeRetriever
import com.treechat.workbench.rag.RagIndexingScheme
import com.treechat.workbench.ui.browser.WebBrowserScreen
import com.treechat.workbench.ui.chat.ChatNodeViewModel
import com.treechat.workbench.ui.knowledge.BackgroundTasksSheet
import com.treechat.workbench.ui.knowledge.ChunkInspectorView
import com.treechat.workbench.ui.knowledge.LiveRetrievalTestPanel
import com.treechat.workbench.ui.knowledge.RagSchemeConfigDialog
import com.treechat.workbench.ui.knowledge.RebuildIndexSchemeDialog
import com.treechat.workbench.task.BackgroundTaskRegistry
import com.treechat.workbench.ui.theme.AccentCyan
import com.treechat.workbench.ui.theme.AccentIceBlue
import com.treechat.workbench.ui.theme.AccentGold
import com.treechat.workbench.ui.theme.AccentNeonGreen
import com.treechat.workbench.ui.theme.CardSurface
import com.treechat.workbench.ui.theme.DividerDark
import com.treechat.workbench.ui.theme.PanelSurface
import com.treechat.workbench.ui.theme.TextMuted
import com.treechat.workbench.ui.theme.TextPrimary
import com.treechat.workbench.ui.theme.TextSecondary
import com.treechat.workbench.ui.window.FloatingWindowManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.material.icons.outlined.CloudUpload
import com.treechat.workbench.data.reader.GoogleDriveClient
import java.io.File
import java.util.UUID

/**
 * 统一「书架」（原「阅读器」Tab 改造而来）。
 *
 * ## 单一真相
 * 书架上的一本书 = Room `knowledge_documents` 里的一行（检索的唯一真相）
 * ＋ 该 id 在 [ReaderDocumentPool] 里的阅读状态（页码 / 正文 / OCR 状态，存 SharedPreferences）。
 * 两者**用同一个 id 关联**，所以「导入一次」即同时出现在书架与阅读状态里；
 * 切片索引（RAG 检索）**不在导入或 OCR 完成时自动建立**，由用户在书架长按菜单里
 * 自己选方案手动触发（「参与检索」/「重建索引」→ [RebuildIndexSchemeDialog]）。
 *
 * 旧版本里阅读器卡片走 `importKnowledgeText` 会另起一个随机 id 写进 Room，
 * 导致同一本书在阅读池与知识库里是两个 id、互相看不见 —— 这正是"两个池子不互通"的根因。
 * 现在导入、接入索引、删除都统一按同一个 id 同时操作两处。
 *
 * ## 与旧界面的关系
 * - 保留原有阅读路径（[AdaptiveDocumentView]）、内置网页浏览器、词源浮层、OCR 进度胶囊；
 * - 原知识库管理弹窗里的「检索命中测试」「方案/API 全局设置」「后台任务」「切片查看器」
 *   迁移到本页，不再从「设置 → 检索与知识库」进入。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookshelfScreen(
    viewModel: ChatNodeViewModel,
    windowManager: FloatingWindowManager,
    /**
     * 阅读器是否处于沉浸全屏。
     *
     * **单一真相**：这个状态由 [com.treechat.workbench.TreeChatMainScreen]（应用级）持有，
     * 阅读器只读它、并只通过 [onImmersiveChange] 修改它。这样"阅读器沉浸"与
     * "应用顶栏 / 药丸导航是否渲染"读的是同一个布尔，绝不会出现两处 remember 各存一份、
     * 顶栏收不干净的错位。
     */
    isImmersive: Boolean = false,
    onImmersiveChange: (Boolean) -> Unit = {},
    /**
     * 由外部（命令面板 Ctrl+K 搜到某本书）指定要直接打开的文档 id。
     * 消费后回调 [onOpenDocumentConsumed] 清空，避免"退出这本书又被自动打开"。
     */
    openDocumentId: String? = null,
    onOpenDocumentConsumed: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val scheme = MaterialTheme.colorScheme
    val snackbarHostState = remember { SnackbarHostState() }

    val pool = remember { ReaderDocumentPool(context) }
    val noteBridge = remember { ReaderNoteBridge(context) }

    // 实况选词的「选词方式」（自绘框选 / 系统原生选区），用户选过就记住。
    // 与全局设置存在同一个 prefs 文件、但用**独立的键**，避免和 AppSettings 的内存缓存打架。
    val liveSelectionPrefs = remember { LiveSelectionPrefs(context) }

    // 封面偏好：是否不裁剪封面 / 是否允许渲染 PDF 首页当封面（均为本机偏好，不落库）。
    val coverPrefs = remember { ReaderCoverPrefs(context) }

    // 封面缓存版本号：导入后抽取到封面时自增，驱动书卡重新读取缓存文件。
    var coversVersion by remember { mutableIntStateOf(0) }

    // 当前打开的图书；若为 null 则显示书架列表。
    //
    // ⚠️ 这里存的是**文档 id**，文档本身从 [pool] 现取。两个原因：
    //
    //  1. 切 Tab 时 AnimatedContent 会把整棵子树移出组合（transition 结束后销毁），
    //     `remember` 一律失效 —— 用户"读到第 40 页、点一下别的 Tab 再点回来"
    //     就被打回书架列表，页码与阅读位置全丢。`rememberSaveable` 才能跨 Tab 存活。
    //  2. 文档的**真相在 pool**（它自己落盘）。界面只持有一个 id，就不会出现
    //     "窗口里标题是旧的、书架里是新的"这种双份状态各存一半的不同步。
    var activeDocumentId by rememberSaveable { mutableStateOf<String?>(null) }
    val activeDocument: ReaderDocument? = activeDocumentId?.let { id ->
        pool.documents.firstOrNull { it.id == id }
    }

    // 命令面板直达：指定的文档 id 到达后立刻打开（内部先按 id 找，找不到就忽略）
    LaunchedEffect(openDocumentId) {
        val target = openDocumentId ?: return@LaunchedEffect
        val doc = pool.documents.firstOrNull { it.id == target }
        if (doc != null) activeDocumentId = doc.id
        onOpenDocumentConsumed()
    }

    // 划线与高亮记录列表
    val highlights = remember { mutableStateListOf<DocumentHighlight>() }

    // 跨语历时音韵与全同义词源 (EtymoScope) 弹窗状态
    var etymologyTargetWord by remember { mutableStateOf<String?>(null) }

    // 选句翻译 / 句法标注的现场状态（一次只服务一个选区，带文档 id 与页号）
    val sentenceTool = remember { SentenceToolState() }

    // 沉浸全屏状态由 MainActivity 持有（见 isImmersive 参数）；此处只读、只转发修改。
    // 内置网页浏览器开关（自带地址栏，盖在书架/正文之上，不销毁下方阅读状态）
    var showWebBrowser by remember { mutableStateOf(false) }

    // 扫描 PDF 专属列数（1, 2, 3）
    var pdfColumnsCount by remember { mutableIntStateOf(1) }

    // 离线 OCR 进度状态（右侧悬浮胶囊，由 OcrProgressFloatingBadge 渲染）
    var ocrBadgeVisible by remember { mutableStateOf(false) }
    var ocrBadgePage by remember { mutableIntStateOf(0) }
    var ocrBadgeTotal by remember { mutableIntStateOf(0) }
    var ocrBadgeTitle by remember { mutableStateOf("") }

    // ------- 统一数据源：Room 文档（真相） + 阅读状态（同 id 关联） -------
    // 链接目标：取当前会话全部分支的 originQuote（引用原文）。
    // 与对话界面 ChatMessageView 里 quoteTargets 的取法完全一致 —— 两处若不同源，
    // 同一段文字在聊天里能点、在文献里就点不了，非常难解释。
    //
    // ⚠️ 这里必须用 `quotedNodes`（**跨会话**的全部引用），不能用
    // `viewModel.nodes` 再按当前会话过滤：
    //
    //  · `nodes` 是 `flatMapLatest` 到当前会话的，只含当前会话的分支；
    //  · 而在文献页点「提问」走 `createQuestionInFolder`，它按**书名**建一棵
    //    新的会话树来放这条引用，**不会**把那条会话切成当前会话。
    //
    // 于是文献页读到的链接目标里从来没有刚提问的那条引用 ——
    // 症状就是"提问之后既没有下划线、也点不开"，而且换个会话再回来照样点不开。
    //
    // 取法改为：所有带引用原文的分支，与当前会话无关。
    val quotedNodes by viewModel.quotedNodes.collectAsState()
    val readerQuoteTargets = remember(quotedNodes) {
        QuoteLinkMatcher.extractTargets(
            nodes = quotedNodes,
            currentNodeId = null
        )
    }

    val roomDocs by viewModel.knowledgeDocuments.collectAsState()
    val busy by viewModel.knowledgeBusy.collectAsState()
    val message by viewModel.knowledgeMessage.collectAsState()
    val backgroundTasks by com.treechat.workbench.task.BackgroundTaskRegistry.tasks.collectAsState()

    // 阅读池是快照列表，读它就订阅了重组
    val poolList = pool.documents.toList()

    val entries: List<ShelfEntry> = buildShelfEntries(roomDocs, poolList)

    // 数据安全：老版本 reader_document_pool 里遗留的书，若 Room 里没有对应行则按同 id 补建元信息，
    // 保证这些旧书继续出现在书架上、不被丢弃。先查后插，绝不覆盖已有行（避免级联删索引）。
    LaunchedEffect(roomDocs.map { it.id }.toSet(), poolList.map { it.id }.toSet()) {
        val roomIds = roomDocs.map { it.id }.toSet()
        poolList.filter { it.id !in roomIds }.forEach { reading ->
            viewModel.ensureShelfRoomDocument(
                documentId = reading.id,
                title = reading.title,
                fileName = reading.title,
                sourceType = reading.format.roomSourceType(),
                charCount = reading.sampleText.length
            )
        }
    }

    // ------- 迁移而来的知识库工具状态 -------
    var showRetrievalTest by remember { mutableStateOf(false) }
    var retrievalQuery by remember { mutableStateOf("") }
    var retrievalRunning by remember { mutableStateOf(false) }
    var retrievalResult by remember { mutableStateOf<KnowledgeRetriever.RetrievalResult?>(null) }

    var showSchemeSettings by remember { mutableStateOf(false) }
    var showBackgroundTasks by remember { mutableStateOf(false) }
    // 非空表示正在为这份书选择索引方案（接入 / 重建）
    var rebuildTarget by remember { mutableStateOf<ShelfEntry?>(null) }

    // 切片查看器：非空时进入页内切片视图
    var inspectingDoc by remember { mutableStateOf<KnowledgeDocument?>(null) }
    var inspectingChunks by remember { mutableStateOf<List<KnowledgeChunk>>(emptyList()) }
    var inspectingLoading by remember { mutableStateOf(false) }
    var chunkSearchQuery by remember { mutableStateOf("") }
    var chunksRefreshKey by remember { mutableIntStateOf(0) }

    LaunchedEffect(inspectingDoc?.id, chunksRefreshKey) {
        val doc = inspectingDoc
        if (doc == null) {
            inspectingChunks = emptyList()
            inspectingLoading = false
        } else {
            inspectingLoading = true
            inspectingChunks = runCatching { viewModel.getKnowledgeDocumentChunks(doc.id) }
                .getOrDefault(emptyList())
            inspectingLoading = false
        }
    }

    val activity = context as? Activity
    val view = LocalView.current

    // 真·沉浸全屏：隐藏系统状态栏与导航栏
    //
    // key 里带上 showWebBrowser：内置浏览器也会按自己的沉浸态开关系统栏，
    // 它退出时会"归还"系统栏；这里在浏览器关上后**重新按阅读器自己的状态**应用一次，
    // 否则会出现"阅读器仍是沉浸态、系统栏却回来了"的错位。
    LaunchedEffect(isImmersive, showWebBrowser) {
        val window = activity?.window ?: return@LaunchedEffect
        val controller = WindowCompat.getInsetsController(window, view)
        if (isImmersive) {
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            val window = activity?.window ?: return@onDispose
            WindowCompat.getInsetsController(window, view).show(WindowInsetsCompat.Type.systemBars())
        }
    }

    // 沉浸态必须有明确可用的退出方式：系统返回键**先退沉浸**，而不是直接退出应用。
    // 与"点正文唤出浮层"一起构成两条互不依赖的退出路径，避免"进去出不来"。
    BackHandler(enabled = isImmersive) { onImmersiveChange(false) }

    // 扫描件/PDF 选 2~3 页时自动转横屏（多页并排只有在横向才排得开）
    LaunchedEffect(pdfColumnsCount, activeDocument?.id) {
        val doc = activeDocument
        val multiPage = doc != null &&
            (doc.format == ReaderFormat.PDF || doc.isScanned) &&
            pdfColumnsCount > 1
        activity?.requestedOrientation = if (multiPage) {
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }
    DisposableEffect(Unit) {
        onDispose { activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    }

    // 手写笔批注与笔记开关（完全复用笔记系统）
    var inkingNoteActive by remember { mutableStateOf(false) }

    // 换书就清掉上一次的句法标注：否则换到别的文档时，上一本书的标注状态
    // 还挂在内存里（虽然不会画到不匹配的页上，但底栏会残留"句法：N 个单元"）。
    LaunchedEffect(activeDocument?.id) {
        sentenceTool.clear()
    }

    /** 打开某本书阅读：优先用已有阅读状态；旧库导入（无阅读状态）则用其切片拼回正文补建。 */
    fun openForReading(entry: ShelfEntry) {
        val reading = entry.reading
        if (reading != null) {
            activeDocumentId = reading.id
            return
        }
        coroutineScope.launch {
            val chunks = viewModel.getKnowledgeDocumentChunks(entry.document.id)
            val text = chunks.joinToString("\n\n") { it.text }
            if (text.isBlank()) {
                snackbarHostState.showSnackbar(
                    "《${entry.document.title}》没有可读正文（旧知识库导入且无本地文件）"
                )
                return@launch
            }
            val pages = ((text.length + DocumentFileReader.CHARS_PER_PAGE - 1) /
                DocumentFileReader.CHARS_PER_PAGE).coerceAtLeast(1)
            val built = ReaderDocument(
                id = entry.document.id,
                title = entry.document.title,
                filePath = "",
                format = readerFormatForSource(entry.document.sourceType),
                fileSize = 0L,
                pageCount = pages,
                currentPage = 1,
                isScanned = false,
                isOcrProcessed = false,
                isRagIndexed = entry.document.chunkCount > 0,
                ragChunkCount = entry.document.chunkCount,
                sampleText = text
            )
            pool.addDocument(built)
            activeDocumentId = built.id
        }
    }

    /** 删除一本书：Room 文档（级联清 chunks/grams）与其阅读状态**同时**清除。 */
    fun deleteEntry(entry: ShelfEntry) {
        viewModel.deleteKnowledgeDocument(entry.document.id, entry.document.title)
        entry.reading?.filePath?.takeIf { it.isNotBlank() }?.let { path ->
            runCatching { File(path).delete() }
        }
        // 封面缓存按 documentId 推导，删书时一并清掉，避免留下孤儿文件
        CoverExtractor.deleteCache(context, entry.document.id)
        pool.removeDocument(entry.document.id)
        coroutineScope.launch { snackbarHostState.showSnackbar("已删除《${entry.document.title}》") }
    }

    /** 为某本书接入 / 重建索引（选方案）：有阅读正文优先用正文，否则退回切片拼回。 */
    fun rebuildIndex(entry: ShelfEntry, chosen: RagIndexingScheme) {
        viewModel.indexShelfDocument(
            documentId = entry.document.id,
            text = entry.reading?.sampleText,
            title = entry.document.title,
            scheme = chosen
        )
    }


    /**
     * 触发离线 OCR —— **用户显式动作**（导入时不再自动识别）。
     *
     * [pageIndex] 为 null 表示整本识别，否则只识别 1 基页号 [pageIndex] 这一页。
     * 进度登记在后台任务面板（[BackgroundTaskRegistry]）里，同时在右侧悬浮胶囊上
     * 给一份即时反馈；识别完成后把正文落到文档上（[ReaderDocumentPool.replaceDocumentContent]）
     * 并刷新当前阅读页。
     *
     * 「识别过就不再识别」的既有标记语义保留：整本识别会写回成带
     * [PdfOcr.HEADER_PREFIX] 抬头的正文，[ReaderDocument.isOcrProcessed] 置真；
     * 已识别文档的文本不会被清空（单页识别是"并入"而不是"覆盖全书"）。
     */
    fun runOfflineOcr(reading: ReaderDocument, pageIndex: Int?) {
        val filePath = reading.filePath.takeIf { it.isNotBlank() }
        val file = filePath?.let(::File)
        if (filePath == null || file == null || !file.exists()) {
            coroutineScope.launch {
                snackbarHostState.showSnackbar("该文档没有本地文件，无法识别")
            }
            return
        }
        val wholeBook = pageIndex == null
        val page1Based = pageIndex?.coerceAtLeast(1) ?: 1

        coroutineScope.launch {
            val taskId = BackgroundTaskRegistry.start(
                title = if (wholeBook) "整本识别：《${reading.title}》"
                else "本页识别：《${reading.title}》第 $page1Based 页",
                detail = "准备中…"
            )
            ocrBadgeTitle = reading.title
            ocrBadgePage = 0
            ocrBadgeTotal = 0
            ocrBadgeVisible = true
            pool.updateOcrStatus(
                reading.id,
                isRunning = true,
                progress = 0f,
                isProcessed = reading.isOcrProcessed
            )

            val resultText: String? = withContext(Dispatchers.IO) {
                if (wholeBook) {
                    val bytes = runCatching { file.readBytes() }.getOrNull()
                    if (bytes == null) {
                        null
                    } else {
                        PdfOcr.extractText(context, bytes) { cur, total, _ ->
                            val p = if (total > 0) cur.toFloat() / total else null
                            BackgroundTaskRegistry.update(taskId, "已识别 $cur / $total 页", p)
                            ocrBadgePage = cur
                            ocrBadgeTotal = total
                        }
                    }
                } else {
                    BackgroundTaskRegistry.update(taskId, "正在识别第 $page1Based 页…", null)
                    ocrSinglePageText(context, filePath, page1Based)
                }
            }
            ocrBadgeVisible = false

            if (resultText.isNullOrBlank()) {
                pool.updateOcrStatus(
                    reading.id,
                    isRunning = false,
                    progress = 0f,
                    isProcessed = reading.isOcrProcessed
                )
                BackgroundTaskRegistry.fail(taskId, "未能识别出文字（文件可能非扫描件或清晰度不足）")
                snackbarHostState.showSnackbar(
                    "OCR 未能识别出文字（文件可能不是扫描件或清晰度不足）"
                )
                return@launch
            }

            val merged = if (wholeBook) resultText
            else mergePageText(reading.sampleText, page1Based, resultText)
            pool.replaceDocumentContent(reading.id, merged, isOcrProcessed = true)
            BackgroundTaskRegistry.finish(
                taskId,
                BackgroundTaskRegistry.State.DONE,
                if (wholeBook) "整本识别完成" else "第 $page1Based 页识别完成"
            )

            if (wholeBook) {
                // 识别结果已经写进正文（上面 replaceDocumentContent），OCR 一步不少；
                // 但**不再自动建索引** —— 切片方案由用户自己在书架长按菜单里选。
                snackbarHostState.showSnackbar(
                    "《${reading.title}》整本识别完成，可在书架长按选择「参与检索」建立切片索引"
                )
            } else {
                snackbarHostState.showSnackbar("已识别第 $page1Based 页")
            }
        }
    }

    /** 重新 OCR（整本）：仅对有本地文件的书有效（旧知识库导入的书没有文件）。 */
    fun triggerOcr(entry: ShelfEntry) {
        val reading = entry.reading
        if (reading == null) {
            coroutineScope.launch {
                snackbarHostState.showSnackbar("该书籍没有本地文件，无法重新识别")
            }
            return
        }
        runOfflineOcr(reading, pageIndex = null)
    }

    // ==================== 选句翻译 / 句法嵌套切片标注 ====================

    /**
     * 模型不可用时的**唯一出口**：把原因当面说清（长时 Snackbar），绝不静默失败。
     * 原因由 [ChatNodeViewModel.oneShotUnavailableReason] 给出（未挂载本地模型 / 云端无密钥）。
     */
    fun notifyModelUnavailable(reason: String) {
        coroutineScope.launch {
            snackbarHostState.showSnackbar(
                message = reason,
                withDismissAction = true,
                duration = SnackbarDuration.Long
            )
        }
    }

    /**
     * 「翻译」：把选中的整句/整段交给当前模型（本地 NPU 或云端，同一条通路），
     * 流式译文落在**可拖拽/可缩放的悬浮小窗**里 —— 不用阻塞对话框，读原文时
     * 译文一直在旁边。同一个选区重复点只置顶已有窗，不堆出新窗。
     */
    fun openTranslationWindow(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        val reason = viewModel.oneShotUnavailableReason()
        if (reason != null) {
            notifyModelUnavailable(reason)
            return
        }
        windowManager.openToolWindow(
            nodeId = "reader-tool:translate:${trimmed.hashCode()}",
            title = "选句翻译",
            originQuote = trimmed,
            content = {
                SentenceTranslationWindowContent(
                    viewModel = viewModel,
                    sourceText = trimmed,
                    // 追问：把「翻译一句」升级为真对话。挂在这本书的根对话树下
                    // （与阅读器的"提问"同一条路），以对话小窗打开 ——
                    // 引用整句原文，后续可以一直接话。
                    onFollowUp = { question ->
                        val sourceTitle = activeDocument?.title ?: "未知来源"
                        viewModel.createQuestionInSourceTree(
                            sourceTitle = sourceTitle,
                            question = question,
                            originQuote = trimmed,
                            onCreated = { nodeId, _ ->
                                windowManager.openSubWindow(
                                    nodeId = nodeId,
                                    title = question.take(24).ifBlank { "追问" },
                                    originQuote = trimmed,
                                    sourceMessageId = null,
                                    depth = 1
                                )
                            }
                        )
                    }
                )
            }
        )
    }

    /**
     * 「句法」：让模型把选中段落切成层级化句法单元（要求严格 JSON），
     * 解析成功则把层级标注在原文/词框上；**解析失败或调用失败一律如实报错**
     * —— 打开一个小窗展示失败原因与模型的**原样输出**，并在原文上什么都没画
     * （绝不用正则硬造一棵语法树）。
     */
    fun runSyntaxAnalysis(text: String, documentId: String, pageIndex: Int) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        val reason = viewModel.oneShotUnavailableReason()
        if (reason != null) {
            notifyModelUnavailable(reason)
            return
        }
        sentenceTool.begin(documentId = documentId, pageIndex = pageIndex, sourceText = trimmed)
        coroutineScope.launch {
            val raw = StringBuilder()
            var streamFailure: String? = null
            try {
                viewModel.streamSyntaxAnalysis(trimmed).collect { delta -> raw.append(delta) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                streamFailure = e.localizedMessage ?: "调用模型失败"
            }
            val rawText = raw.toString()
            val failure = streamFailure
            if (failure != null) {
                sentenceTool.fail(failure)
                windowManager.openToolWindow(
                    nodeId = "reader-tool:syntax-fail:${trimmed.hashCode()}:${rawText.hashCode()}",
                    title = "句法分析失败",
                    originQuote = trimmed,
                    content = { SyntaxFailureWindowContent(reason = failure, rawModelOutput = rawText) }
                )
                return@launch
            }
            when (val parsed = parseSyntaxAnalysis(rawText)) {
                is SyntaxParseResult.Parsed -> {
                    val (located, unlocated) = locateUnits(trimmed, parsed.units)
                    sentenceTool.succeed(located.map { it.unit }, unlocated)
                    snackbarHostState.showSnackbar(
                        "已标注 ${located.size} 个句法单元" +
                            if (unlocated.isNotEmpty()) {
                                "（另有 ${unlocated.size} 个片段未能在原文中定位）"
                            } else {
                                ""
                            }
                    )
                }
                is SyntaxParseResult.Failed -> {
                    sentenceTool.fail(parsed.reason)
                    windowManager.openToolWindow(
                        nodeId = "reader-tool:syntax-fail:${trimmed.hashCode()}:${rawText.hashCode()}",
                        title = "句法分析失败",
                        originQuote = trimmed,
                        content = {
                            SyntaxFailureWindowContent(
                                reason = parsed.reason,
                                rawModelOutput = parsed.raw
                            )
                        }
                    )
                }
            }
        }
    }

    /** 实时检索命中测试：对全部已启用教材做与对话一致的 2-gram 打分。 */
    fun runRetrievalTest() {
        val q = retrievalQuery.trim()
        if (q.isEmpty()) {
            retrievalResult = null
            return
        }
        coroutineScope.launch {
            retrievalRunning = true
            val docsWithChunks = roomDocs.filter { it.enabled }.mapNotNull { doc ->
                val chunks = runCatching { viewModel.getKnowledgeDocumentChunks(doc.id) }.getOrNull()
                if (chunks != null) doc to chunks else null
            }
            retrievalResult = KnowledgeRetriever.rankChunksInMemory(
                query = q,
                documentsWithChunks = docsWithChunks,
                topK = KnowledgeRetriever.DEFAULT_TOP_K
            )
            retrievalRunning = false
        }
    }
    // 真实系统文件选择器（PDF / DOC/DOCX / PPT/PPTX / EPUB / TXT / MD）
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            coroutineScope.launch {
                snackbarHostState.showSnackbar("正在导入并登记文档...")
                // 导入只做"立刻能完成的事"：抽文本层 / 抽正文，**不跑 OCR**。
                val doc = DocumentFileReader.importFromUri(context, uri)
                if (doc != null) {
                    // ① 阅读状态（ReaderDocumentPool，id = doc.id）—— 导入后立刻可见
                    pool.addDocument(doc)
                    if (doc.sampleText.isBlank()) {
                        // ② 扫描件（或文本层不可用的 PDF）：只登记元信息，**不建索引**；
                        //    是否有文本取决于用户后来是否显式触发识别。
                        viewModel.ensureShelfRoomDocument(
                            documentId = doc.id,
                            title = doc.title,
                            fileName = doc.title,
                            sourceType = doc.format.roomSourceType(),
                            charCount = 0
                        )
                    } else {
                        // ② 有正文：只把元信息与正文写进 Room（进书架，同 id），
                        //    **不自动建索引** —— 切片方案由用户在书架长按菜单里自己选
                        //    （indexNow = false；正文在阅读状态里，手动建索引时直接取用）。
                        viewModel.importShelfDocument(
                            documentId = doc.id,
                            title = doc.title,
                            fileName = doc.title,
                            sourceType = doc.format.roomSourceType(),
                            text = doc.sampleText,
                            indexNow = false
                        )
                    }
                    activeDocumentId = doc.id
                    val note = if (doc.sampleText.isBlank()) {
                        "（扫描件 · 未识别，共 ${doc.pageCount} 页；可在阅读器里识别，识别后长按参与检索）"
                    } else {
                        "（共 ${doc.pageCount} 页 · 已加入书架，长按可参与检索）"
                    }
                    snackbarHostState.showSnackbar("已导入《${doc.title}》$note")
                    // 后台抽封面（IO，抽不到就静默回落生成封面，绝不让导入失败）
                    val covered = CoverExtractor.extract(context, doc, coverPrefs.pdfCoverEnabled)
                    if (covered != null) coversVersion++
                } else {
                    snackbarHostState.showSnackbar(
                        DocumentFileReader.lastError ?: "文档解析失败，请检查文件权限"
                    )
                }
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            AnimatedVisibility(
                visible = !isImmersive && !showWebBrowser,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                if (activeDocument == null) {
                    // 书架顶部栏
                    TopAppBar(
                        title = {
                            Text(
                                text = "书架",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                        },
                        actions = {
                            // 词源全局快速检索入口
                            IconButton(onClick = { etymologyTargetWord = "神圣" }) {
                                Icon(Icons.Outlined.Translate, contentDescription = "跨语音标与词源")
                            }
                            // 导入：同时写入 Room（供检索）与阅读状态（供阅读）
                            IconButton(onClick = {
                                filePickerLauncher.launch(
                                    arrayOf(
                                        "application/pdf",
                                        "application/msword",
                                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                                        "application/vnd.ms-powerpoint",
                                        "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                                        "application/epub+zip",
                                        "text/plain",
                                        "text/markdown",
                                        // MOBI / AZW3 的 SAF MIME；不加这两行，
                                        // 书架导入时系统文件管理器会把它们灰掉选不中。
                                        "application/x-mobipocket-ebook",
                                        "application/vnd.amazon.ebook"
                                    )
                                )
                            }) {
                                Icon(Icons.Outlined.Add, contentDescription = "导入电子书/文档")
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = scheme.surfaceContainerLow)
                    )
                } else {
                    // 阅读正文顶部栏 (超薄紧凑，支持全屏收缩)
                    val doc = activeDocument!!
                    TopAppBar(
                        title = {
                            Text(
                                text = doc.title,
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        navigationIcon = {
                            IconButton(onClick = { activeDocumentId = null }) {
                                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回书架")
                            }
                        },
                        actions = {
                            if (doc.format == ReaderFormat.PDF || doc.isScanned) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(end = 4.dp)
                                ) {
                                    listOf(1, 2, 3).forEach { cols ->
                                        FilterChip(
                                            selected = pdfColumnsCount == cols,
                                            onClick = { pdfColumnsCount = cols },
                                            label = { Text("${cols}页", fontSize = 11.sp) },
                                            modifier = Modifier.padding(horizontal = 2.dp)
                                        )
                                    }
                                }
                            }
                            // 离线 OCR：只对能按图渲染的 PDF / 扫描件提供。
                            // **必须由用户显式触发**（导入时不再自动识别）；
                            // 进度登记在后台任务面板里，识别完正文落到文档上并刷新。
                            val canOcr = (doc.format == ReaderFormat.PDF ||
                                doc.format == ReaderFormat.SCANNED_PDF ||
                                doc.isScanned) && doc.filePath.isNotBlank()
                            if (canOcr) {
                                var ocrMenuOpen by remember { mutableStateOf(false) }
                                Box {
                                    IconButton(onClick = { ocrMenuOpen = true }) {
                                        Icon(
                                            Icons.Outlined.DocumentScanner,
                                            contentDescription = "离线识别：本页 / 整本"
                                        )
                                    }
                                    DropdownMenu(
                                        expanded = ocrMenuOpen,
                                        onDismissRequest = { ocrMenuOpen = false }
                                    ) {
                                        DropdownMenuItem(
                                            text = { Text("识别本页（第 ${doc.currentPage} 页）") },
                                            leadingIcon = {
                                                Icon(Icons.Outlined.DocumentScanner, contentDescription = null)
                                            },
                                            onClick = {
                                                ocrMenuOpen = false
                                                runOfflineOcr(doc, doc.currentPage)
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    if (doc.isOcrProcessed) "重新识别整本（离线 OCR）"
                                                    else "识别整本（离线 OCR）"
                                                )
                                            },
                                            leadingIcon = {
                                                Icon(Icons.Outlined.AutoStories, contentDescription = null)
                                            },
                                            onClick = {
                                                ocrMenuOpen = false
                                                runOfflineOcr(doc, null)
                                            }
                                        )
                                    }
                                }
                            }
                            IconButton(onClick = { etymologyTargetWord = "神圣" }) {
                                Icon(Icons.Outlined.Translate, contentDescription = "跨语音标与词源")
                            }
                            // 开启/退出手写笔批注（完全复用笔记系统）
                            IconButton(onClick = { inkingNoteActive = !inkingNoteActive }) {
                                Icon(
                                    imageVector = Icons.Outlined.Edit,
                                    contentDescription = if (inkingNoteActive) "退出手写批注" else "开启手写批注",
                                    tint = if (inkingNoteActive) scheme.primary else scheme.onSurfaceVariant
                                )
                            }
                            IconButton(onClick = { onImmersiveChange(true) }) {
                                Icon(Icons.Outlined.Fullscreen, contentDescription = "全屏沉浸阅读")
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = scheme.surfaceContainerLow)
                    )
                }
            }
        },
        modifier = modifier.fillMaxSize()
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(if (isImmersive || showWebBrowser) PaddingValues(0.dp) else innerPadding)
        ) {
            val doc = activeDocument
            if (doc == null) {
                // ---------------- 书架视图 ----------------
                ShelfContent(
                    entries = entries,
                    onContentSearch = { q -> viewModel.documentIdsMatchingContent(q) },
                    inspectingDoc = inspectingDoc,
                    inspectingChunks = inspectingChunks,
                    inspectingLoading = inspectingLoading,
                    chunkSearchQuery = chunkSearchQuery,
                    onChunkSearchChange = { chunkSearchQuery = it },
                    onCloseInspector = {
                        inspectingDoc = null
                        chunkSearchQuery = ""
                    },
                    onDeleteChunk = { chunk ->
                        inspectingDoc?.let { viewModel.deleteChunk(chunk.id, it.id) }
                        chunksRefreshKey++
                    },
                    onClearAllChunks = {
                        inspectingDoc?.let { viewModel.deleteDocumentIndex(it.id) }
                        chunksRefreshKey++
                    },
                    onToggleDocumentEnabled = { enabled ->
                        inspectingDoc?.let { viewModel.setKnowledgeDocumentEnabled(it.id, enabled) }
                    },
                    onOpenDocument = { openForReading(it) },
                    onInspectChunks = { entry ->
                        inspectingDoc = entry.document
                        chunkSearchQuery = ""
                    },
                    onRebuildIndex = { entry -> rebuildTarget = entry },
                    onTriggerOcr = { triggerOcr(it) },
                    onDeleteDocument = { deleteEntry(it) },
                    onRenameDocument = { entry, newTitle ->
                        // Room（检索侧真相）与阅读池（阅读侧状态）同时改名，保持一致
                        viewModel.renameKnowledgeDocument(entry.document.id, newTitle)
                        pool.renameDocument(entry.document.id, newTitle)
                    },
                    onSetEnabled = { entry, enabled ->
                        viewModel.setKnowledgeDocumentEnabled(entry.document.id, enabled)
                    },
                    driveClient = remember { GoogleDriveClient(context) },
                    onImportFromCloud = { f ->
                        coroutineScope.launch {
                        // 与"导入书籍"完全同一条管线（文件在本地缓存里，file:// 直接读）
                        val doc = DocumentFileReader.importFromUri(context, android.net.Uri.fromFile(f))
                        if (doc != null) {
                            pool.addDocument(doc)
                            if (doc.sampleText.isBlank()) {
                                viewModel.ensureShelfRoomDocument(
                                    documentId = doc.id,
                                    title = doc.title,
                                    fileName = doc.title,
                                    sourceType = doc.format.roomSourceType(),
                                    charCount = 0
                                )
                            } else {
                                viewModel.ensureShelfRoomDocument(
                                    documentId = doc.id,
                                    title = doc.title,
                                    fileName = doc.title,
                                    sourceType = doc.format.roomSourceType(),
                                    charCount = doc.sampleText.length
                                )
                            }
                            snackbarHostState.showSnackbar("《${doc.title}》已进书架")
                        } else {
                            snackbarHostState.showSnackbar("导入失败：${DocumentFileReader.lastError ?: "无法解析该文件"}")
                        }
                        }
                    },
                    localFileOf = { entry ->
                        pool.documents.firstOrNull { it.id == entry.document.id }?.let { File(it.filePath) }
                    },
                    // 浏览器入口（与"导入书籍"并排，第一次进书架就能看见）
                    onOpenBrowser = { showWebBrowser = true },
                    // 工具栏
                    busy = busy,
                    message = message,
                    onClearMessage = { viewModel.clearKnowledgeMessage() },
                    onImport = {
                        filePickerLauncher.launch(
                            arrayOf(
                                "application/pdf",
                                "application/msword",
                                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                                "application/vnd.ms-powerpoint",
                                "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                                "application/epub+zip",
                                "text/plain",
                                    "text/markdown",
                                    "application/x-mobipocket-ebook",
                                    "application/vnd.amazon.ebook",
                                    "*/*"
                            )
                        )
                    },
                    globalEnabled = viewModel.settings.knowledgeEnabled,
                    onGlobalEnabledChange = { viewModel.settings.updateKnowledgeEnabled(it) },
                    showRetrievalTest = showRetrievalTest,
                    onToggleRetrievalTest = { showRetrievalTest = !showRetrievalTest },
                    retrievalQuery = retrievalQuery,
                    onRetrievalQueryChange = { retrievalQuery = it },
                    onRunRetrievalTest = { runRetrievalTest() },
                    retrievalRunning = retrievalRunning,
                    retrievalResult = retrievalResult,
                    onOpenEvidence = { evidence ->
                        val target = roomDocs.find { it.id == evidence.documentId }
                        if (target != null) {
                            inspectingDoc = target
                            chunkSearchQuery = Chunker.extractKeyTerms(retrievalQuery).firstOrNull()
                                ?: retrievalQuery.trim()
                        }
                    },
                    onOpenSchemeSettings = { showSchemeSettings = true },
                    runningTaskCount = backgroundTasks.count { it.isRunning },
                    onOpenBackgroundTasks = { showBackgroundTasks = true },
                    totalDocs = entries.size,
                    totalChunks = roomDocs.sumOf { it.chunkCount },
                    // 封面
                    coversVersion = coversVersion,
                    disableCoverCrop = coverPrefs.disableCrop,
                    onDisableCoverCropChange = { coverPrefs.updateDisableCrop(it) },
                    pdfCoverEnabled = coverPrefs.pdfCoverEnabled,
                    onPdfCoverEnabledChange = { coverPrefs.updatePdfCover(it) }
                )
            } else {
                // ---------------- 正文阅读页面 ----------------
                // ---- 右侧可调边栏（生词/词条/AI/注释）的状态与数据 ----
                val panelState = remember { ReaderPanelState.obtain(context) }
                val activeExamTokens = remember(panelState.examEnabled, panelState.examTokens) {
                    if (panelState.examEnabled) panelState.examTokens else emptySet()
                }
                var readerPageText by remember { mutableStateOf("") }
                var readerSelection by remember { mutableStateOf("") }
                var panelFocusedWord by remember { mutableStateOf<String?>(null) }
                // 注释数据在"划线/批注"后要刷新：用一个自增记号触发重读
                var noteTick by remember { mutableIntStateOf(0) }
                val pageVocab = remember(readerPageText, activeExamTokens) {
                    findVocabHits(readerPageText, activeExamTokens) { VocabIndex.examTags(it) }
                }
                val panelNotes = remember(doc.id, noteTick) {
                    noteBridge.getNotesForBook(doc.id)
                }
                // 句法分析完成后**自动**展开边栏并切到「句法」页签：
                // 标识不再画在原文上（用户明确要求），所以必须主动把结果送到眼前。
                LaunchedEffect(sentenceTool.hasAnnotation, sentenceTool.analyzing) {
                    if (sentenceTool.hasAnnotation && !sentenceTool.analyzing) {
                        panelState.applyExpanded(true)
                        panelState.applyTab(PanelTab.SYNTAX)
                    }
                }
                fun panelAsk(question: String) {
                    viewModel.createQuestionInSourceTree(
                        sourceTitle = doc.title,
                        question = question,
                        originQuote = readerSelection,
                        onCreated = { nodeId, _ ->
                            windowManager.openSubWindow(
                                nodeId = nodeId,
                                title = question.take(24).ifBlank { "本页追问" },
                                originQuote = readerSelection,
                                sourceMessageId = null,
                                depth = 1
                            )
                        }
                    )
                }
                Row(modifier = Modifier.fillMaxSize()) {
                    Box(modifier = Modifier.weight(1f)) {
                AdaptiveDocumentView(
                    modifier = Modifier.fillMaxSize(),
                    document = doc,
                    currentPage = doc.currentPage,
                    highlights = highlights.filter { it.documentId == doc.id },
                    pdfColumns = pdfColumnsCount,
                    // 沉浸态：底栏、翻页条、选词方式切换器与页码角标一并收起
                    isImmersive = isImmersive,
                    selectionMethod = liveSelectionPrefs.method,
                    onSelectionMethodChange = { liveSelectionPrefs.update(it) },
                    inputMode = viewModel.settings.inputMode,
                    // 选词方案与对话界面读同一份设置：同一个功能必须一套手感
                    selectionScheme = viewModel.settings.selectionScheme,
                    onPageChange = { newPage ->
                        pool.updateProgress(doc.id, newPage)
                    },
                    onToggleImmersive = { onImmersiveChange(!isImmersive) },
                    // 同文本超链接：把当前会话里"曾引用过某段原文"的分支作为链接目标。
                    // 与对话界面同一套机制（QuoteLinkMatcher），不新增任何数据库字段。
                    quoteLinkTargets = readerQuoteTargets,
                    onQuoteLinkClick = { node ->
                        coroutineScope.launch {
                            // conversationId 可为空（游离节点），此时只开窗不切会话
                            node.conversationId?.let { viewModel.openConversation(it) }
                            windowManager.openSubWindow(
                                nodeId = node.id,
                                title = node.title,
                                originQuote = node.originQuote,
                                sourceMessageId = node.sourceMessageId,
                                depth = 1
                            )
                        }
                    },
                    onAskQuestion = { selectedQuote ->
                        // 一本书 = 一棵根对话树（根节点以书名命名），
                        // 每次选词提问在它根下挂一个子节点。
                        // 旧做法是"建文件夹 + 每问一次新建一棵会话"，
                        // 结果同一本书散成一堆会话，而链接目标只取当前会话的分支，
                        // 于是刚提出来的引用在文献页既没有下划线、也点不开。
                        val sourceTitle = doc.title
                        viewModel.createQuestionInSourceTree(
                            sourceTitle = sourceTitle,
                            question = "",
                            originQuote = selectedQuote,
                            onCreated = { nodeId, _ ->
                                windowManager.openSubWindow(
                                    nodeId = nodeId,
                                    title = selectedQuote.take(28).trim()
                                        .ifBlank { sourceTitle },
                                    originQuote = selectedQuote,
                                    sourceMessageId = null,
                                    depth = 1
                                )
                                coroutineScope.launch {
                                    snackbarHostState.showSnackbar(
                                        "已归入《${sourceTitle}》的研读分支，可在小窗中继续"
                                    )
                                }
                            }
                        )
                    },
                    onHighlight = { quote, pageIdx ->
                        val highlight = DocumentHighlight(
                            id = UUID.randomUUID().toString(),
                            documentId = doc.id,
                            pageIndex = pageIdx,
                            startOffset = 0,
                            endOffset = quote.length,
                            selectedText = quote
                        )
                        highlights.add(highlight)
                        noteBridge.saveHighlightToNote(
                            documentId = doc.id,
                            bookTitle = doc.title,
                            pageIndex = pageIdx,
                            selectedQuote = quote,
                            userComment = "划线高亮"
                        )
                        noteTick++
                        coroutineScope.launch {
                            snackbarHostState.showSnackbar("已划线并同步至读书笔记白板")
                        }
                    },
                    onAddNote = { quote, pageIdx ->
                        noteBridge.saveHighlightToNote(
                            documentId = doc.id,
                            bookTitle = doc.title,
                            pageIndex = pageIdx,
                            selectedQuote = quote,
                            userComment = "批注笔记"
                        )
                        noteTick++
                        coroutineScope.launch {
                            snackbarHostState.showSnackbar("已保存笔记批注")
                        }
                    },
                    onOpenEtymology = { word -> etymologyTargetWord = word },
                    sentenceTool = sentenceTool,
                    onTranslateSelection = { selected -> openTranslationWindow(selected) },
                    onAnalyzeSyntax = { selected, pageIdx ->
                        runSyntaxAnalysis(selected, doc.id, pageIdx)
                    },
                    onClearSyntax = { sentenceTool.clear() },
                    // ---- 边栏数据链：考纲 token / 页文本 / 选区 ----
                    activeExamTokens = activeExamTokens,
                    onPageText = { readerPageText = it },
                    onSelectionText = { sel ->
                        readerSelection = sel
                        if (sel.isNotBlank()) panelFocusedWord = sel.trim()
                    }
                )

                // 覆盖在正文上方的手写笔记批注图层（完全复用笔记系统）
                ReaderInkOverlay(
                    documentId = doc.id,
                    pageIndex = doc.currentPage,
                    active = inkingNoteActive,
                    onClose = { inkingNoteActive = false }
                )
                    }

                    ReaderSidePanel(
                        state = panelState,
                        summary = VocabPageSummary(
                            count = pageVocab.size,
                            unique = dedupeVocabHits(pageVocab)
                        ),
                        focusedWord = panelFocusedWord,
                        notes = panelNotes,
                        ai = PanelAiActions(
                            onAsk = {
                                if (readerSelection.isNotBlank()) {
                                    panelAsk("请讲解这段原文")
                                } else {
                                    coroutineScope.launch {
                                        snackbarHostState.showSnackbar("先在正文里选中一段，再提问")
                                    }
                                }
                            },
                            onTranslate = {
                                if (readerSelection.isNotBlank()) openTranslationWindow(readerSelection)
                            },
                            onSyntax = {
                                if (readerSelection.isNotBlank()) {
                                    runSyntaxAnalysis(readerSelection, doc.id, doc.currentPage - 1)
                                }
                            },
                            onFollowUp = { q -> panelAsk(q) }
                        ),
                        syntaxSource = sentenceTool.sourceText,
                        syntaxUnits = sentenceTool.units,
                        syntaxUnlocated = sentenceTool.unlocated.size,
                        syntaxFailure = sentenceTool.failureReason,
                        syntaxAnalyzing = sentenceTool.analyzing,
                        onSyntaxClear = { sentenceTool.clear() },
                        onSyntaxTranslate = {
                            if (sentenceTool.sourceText.isNotBlank()) {
                                openTranslationWindow(sentenceTool.sourceText)
                            }
                        },
                        onPickWord = { word ->
                            panelFocusedWord = word
                            panelState.applyTab(PanelTab.ENTRY)
                        },
                        onWordFocused = { panelFocusedWord = it }
                    )
                }

                // 覆盖在正文上方的手写笔记批注图层（完全复用笔记系统）
                ReaderInkOverlay(
                    documentId = doc.id,
                    pageIndex = doc.currentPage,
                    active = inkingNoteActive,
                    onClose = { inkingNoteActive = false }
                )
            }

            // 跨语历时音韵与全同义词源全息视窗 (EtymoScope)
            etymologyTargetWord?.let { queryWord ->
                EtymoScopeBottomSheet(
                    initialQuery = queryWord,
                    onDismissRequest = { etymologyTargetWord = null }
                )
            }

            // 离线 OCR 进度悬浮胶囊（沉浸态下也是"悬浮角标"，收起）
            OcrProgressFloatingBadge(
                visible = ocrBadgeVisible && !isImmersive,
                progress = if (ocrBadgeTotal > 0) ocrBadgePage.toFloat() / ocrBadgeTotal else 0f,
                currentPage = ocrBadgePage,
                totalPages = ocrBadgeTotal,
                bookTitle = ocrBadgeTitle,
                onCancel = { ocrBadgeVisible = false },
                modifier = Modifier.align(Alignment.CenterEnd)
            )

            // 内置网页浏览器：盖在书架/正文之上（Box 最后一个子节点即最上层）
            if (showWebBrowser) {
                WebBrowserScreen(
                    viewModel = viewModel,
                    windowManager = windowManager,
                    onExit = { showWebBrowser = false },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }

    // ---------------- 弹窗：方案 / API（全局切片方案） ----------------
    if (showSchemeSettings) {
        RagSchemeConfigDialog(viewModel = viewModel, onDismiss = { showSchemeSettings = false })
    }

    // ---------------- 弹窗：后台任务 ----------------
    if (showBackgroundTasks) {
        BackgroundTasksSheet(onDismiss = { showBackgroundTasks = false })
    }

    // ---------------- 弹窗：接入 / 重建索引（选方案） ----------------
    rebuildTarget?.let { entry ->
        RebuildIndexSchemeDialog(
            document = entry.document,
            busy = busy,
            cloudApiReady = viewModel.settings.cloudRagConfig().isValidForApi(),
            statusMessage = message,
            onConfirm = { chosen ->
                rebuildIndex(entry, chosen)
            },
            onDismiss = { rebuildTarget = null }
        )
    }
}

// ============================================================================
// 书架主体（列表 + 工具栏 + 切片查看器）
// ============================================================================

/**
 * 书架图标的单元格尺寸（dp）。
 *
 * 与访达（[com.treechat.workbench.ui.explorer.FreePlacement] 的 104x140）不同的原因：
 * 书的卡片是「封面 + 两行标题」，封面按 0.70 宽高比裁切，格子必须更高，
 * 否则两本书的封面会叠在一起。
 */
private const val SHELF_CELL_W_DP = 132f
private const val SHELF_CELL_H_DP = 216f

@Composable
private fun ShelfContent(
    entries: List<ShelfEntry>,
    // 切片查看器
    inspectingDoc: KnowledgeDocument?,
    inspectingChunks: List<KnowledgeChunk>,
    inspectingLoading: Boolean,
    chunkSearchQuery: String,
    onChunkSearchChange: (String) -> Unit,
    onCloseInspector: () -> Unit,
    onDeleteChunk: (KnowledgeChunk) -> Unit,
    onClearAllChunks: () -> Unit,
    onToggleDocumentEnabled: (Boolean) -> Unit,
    // 卡片操作
    onOpenDocument: (ShelfEntry) -> Unit,
    /** 只读全文检索：切片正文命中关键词的文档 id（书架「搜书」用）。 */
    onContentSearch: suspend (String) -> Set<String> = { emptySet() },
    onInspectChunks: (ShelfEntry) -> Unit,
    onRebuildIndex: (ShelfEntry) -> Unit,
    onTriggerOcr: (ShelfEntry) -> Unit,
    onDeleteDocument: (ShelfEntry) -> Unit,
    onRenameDocument: (ShelfEntry, String) -> Unit,
    onSetEnabled: (ShelfEntry, Boolean) -> Unit,
    /** 打开内置网页浏览器（书架的显眼操作区与"导入书籍"并排）。 */
    onOpenBrowser: () -> Unit,
    /** 打开扫描工作台：拍照 / 相册导入 / 断点续拍 / 合成 PDF。 */
    /** 打开命令与检索面板。 */
    onOpenSearch: () -> Unit = {},
    // 工具栏
    busy: Boolean,
    message: String?,
    onClearMessage: () -> Unit,
    onImport: () -> Unit,
    globalEnabled: Boolean,
    onGlobalEnabledChange: (Boolean) -> Unit,
    showRetrievalTest: Boolean,
    onToggleRetrievalTest: () -> Unit,
    retrievalQuery: String,
    onRetrievalQueryChange: (String) -> Unit,
    onRunRetrievalTest: () -> Unit,
    retrievalRunning: Boolean,
    retrievalResult: KnowledgeRetriever.RetrievalResult?,
    onOpenEvidence: (KnowledgeRetriever.Evidence) -> Unit,
    onOpenSchemeSettings: () -> Unit,
    runningTaskCount: Int,
    onOpenBackgroundTasks: () -> Unit,
    totalDocs: Int,
    totalChunks: Int,
    // 封面
    coversVersion: Int,
    disableCoverCrop: Boolean,
    onDisableCoverCropChange: (Boolean) -> Unit,
    pdfCoverEnabled: Boolean,
    onPdfCoverEnabledChange: (Boolean) -> Unit,
    // ---- 谷歌云盘（书架 Finder 式落点 + 云端面板）----
    driveClient: GoogleDriveClient,
    /** 云端文件下载完成后由宿主注册进书架（池 + Room，与导入书籍同一条管线）。 */
    onImportFromCloud: (File) -> Unit,
    /** 书架条目 → 本地原书文件（路径在 ReaderDocumentPool，只有外层拿得到）。 */
    localFileOf: (ShelfEntry) -> File?
) {
    val scheme = MaterialTheme.colorScheme
    // 本组件作用域内需要的运行时（外层 bookshelfScreen 未必透传）：
    val context = androidx.compose.ui.platform.LocalContext.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    // ---- 书架浏览 UI 状态（搜索 / 排序 / 网格或列表）----
    var shelfQuery by remember { mutableStateOf("") }
    var shelfSort by remember { mutableStateOf(ShelfSort.RECENT) }
    var shelfGrid by remember { mutableStateOf(true) }

    // ---- 图标自由摆放（SharedPreferences JSON，不进 Room）----
    val shelfContext = androidx.compose.ui.platform.LocalContext.current
    val shelfPrefs = remember {
        com.treechat.workbench.ui.common.FreePositionStore(
            shelfContext,
            com.treechat.workbench.ui.common.FreePositionStore.KEY_SHELF
        )
    }
    var freePositions by remember { mutableStateOf(shelfPrefs.load()) }
    var canvasBounds by remember { mutableStateOf<Rect?>(null) }
    val shelfScroll = rememberScrollState()

    // 画布尺寸变化时把书架摆放夹回可视区（换屏/分屏/字号变化后图标不越界）。
    LaunchedEffect(canvasBounds, shelfGrid) {
        val canvas = canvasBounds ?: return@LaunchedEffect
        if (!shelfGrid || freePositions.isEmpty()) return@LaunchedEffect
        val px = shelfContext.resources.displayMetrics.density
        val columns = FreePlacement.columnCount(canvas.width / px, SHELF_CELL_W_DP)
        val positions = entries.mapIndexed { index, e ->
            FreePlacement.positionOf(
                index = index,
                itemId = e.document.id,
                columns = columns,
                custom = freePositions,
                cellWDp = SHELF_CELL_W_DP,
                cellHDp = SHELF_CELL_H_DP
            )
        }
        val clamped = FreePlacement.clampPositions(
            positions = freePositions,
            canvasWidthDp = canvas.width / px,
            maxContentHeightDp = maxOf(
                FreePlacement.contentHeightDp(positions, SHELF_CELL_H_DP),
                canvas.height / px
            ),
            cellWDp = SHELF_CELL_W_DP,
            cellHDp = SHELF_CELL_H_DP
        )
        if (clamped !== freePositions) {
            freePositions = clamped
            shelfPrefs.save(clamped)
        }
    }

    // ---- 搜书（书名 + 书里正文）----
    // 书名匹配在内存里做；正文命中靠只读的切片全文查询补上，否则只能搜到书名。
    var contentHitDocIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    LaunchedEffect(shelfQuery) {
        val q = shelfQuery.trim()
        if (q.length < 2) {
            contentHitDocIds = emptySet()
        } else {
            kotlinx.coroutines.delay(220)
            contentHitDocIds = onContentSearch(q)
        }
    }

    // ---- 长按多选模式状态 ----
    var selectionMode by remember { mutableStateOf(false) }
    val selectedDocIds = remember { mutableStateMapOf<String, Boolean>() }
    var batchDeleteConfirm by remember { mutableStateOf(false) }

    if (batchDeleteConfirm) {
        val count = selectedDocIds.size
        AlertDialog(
            onDismissRequest = { batchDeleteConfirm = false },
            title = { Text("批量删除书籍", fontWeight = FontWeight.Bold) },
            text = { Text("确定要永久删除已选中的 $count 本书籍及其阅读记录？该操作不可撤销。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        val idsToDelete = selectedDocIds.keys.toList()
                        idsToDelete.forEach { id ->
                            entries.find { it.document.id == id }?.let { onDeleteDocument(it) }
                        }
                        selectedDocIds.clear()
                        selectionMode = false
                        batchDeleteConfirm = false
                    }
                ) {
                    Text("全部删除", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { batchDeleteConfirm = false }) {
                    Text("取消")
                }
            }
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        if (inspectingDoc != null) {
            // 切片查看器（随书架进入，点某一本 → 查看其切片）
            ChunkInspectorView(
                document = inspectingDoc,
                chunks = inspectingChunks,
                isLoading = inspectingLoading,
                searchQuery = chunkSearchQuery,
                onSearchQueryChange = onChunkSearchChange,
                onToggleDocumentEnabled = onToggleDocumentEnabled,
                onBackToFolder = onCloseInspector,
                onDeleteChunk = onDeleteChunk,
                onClearAllChunks = onClearAllChunks,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp)
            )
            return@Column
        }

        ShelfToolbar(
            busy = busy,
            onImport = onImport,
            onOpenBrowser = onOpenBrowser,
            onOpenSearch = onOpenSearch,
            globalEnabled = globalEnabled,
            onGlobalEnabledChange = onGlobalEnabledChange,
            showRetrievalTest = showRetrievalTest,
            onToggleRetrievalTest = onToggleRetrievalTest,
            onOpenSchemeSettings = onOpenSchemeSettings,
            runningTaskCount = runningTaskCount,
            onOpenBackgroundTasks = onOpenBackgroundTasks,
            totalDocs = totalDocs,
            totalChunks = totalChunks
        )

        if (busy) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp),
                color = AccentCyan,
                trackColor = CardSurface
            )
        }

        message?.let { msg ->
            Surface(
                color = CardSurface,
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, AccentGold.copy(alpha = 0.6f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Info,
                        contentDescription = null,
                        tint = AccentGold,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = msg,
                        color = AccentGold,
                        fontSize = 12.sp,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onClearMessage) {
                        Text("知道了", color = AccentCyan, fontSize = 12.sp)
                    }
                }
            }
        }

        if (showRetrievalTest) {
            LiveRetrievalTestPanel(
                query = retrievalQuery,
                onQueryChange = onRetrievalQueryChange,
                onRunTest = onRunRetrievalTest,
                isRunning = retrievalRunning,
                result = retrievalResult,
                // 书架已统一为平铺列表，不再按文件夹分层；检索范围为全部已启用教材。
                currentFolderPath = "",
                scopeCurrentFolderOnly = false,
                onScopeChange = {},
                onOpenEvidenceInInspector = onOpenEvidence
            )
            Spacer(modifier = Modifier.height(6.dp))
        }

        // Finder 式废纸篓：多选态下出现在右下角，长按书架卡片拖进去即删除。
        var trashBounds by remember { mutableStateOf<Rect?>(null) }
        var trashDragPos by remember { mutableStateOf<Offset?>(null) }

        // ---- 谷歌云盘（Finder 式第二落点：拖入即上传；点击打开云端面板）----
        val driveClient = remember { GoogleDriveClient(context) }
        var driveBounds by remember { mutableStateOf<Rect?>(null) }
        // 拖拽幽灵：拖动中的书跟手（书架自由摆放画布只挪落点，视觉必须由外层补）
        var draggedEntry by remember { mutableStateOf<ShelfEntry?>(null) }
        // 云端面板与登录
        var drivePanelOpen by remember { mutableStateOf(false) }
        var driveLoginOpen by remember { mutableStateOf(false) }
        var pendingUploads by remember { mutableStateOf<List<ShelfEntry>?>(null) }

        fun selectedEntries(): List<ShelfEntry> =
            entries.filter { selectedDocIds.containsKey(it.document.id) }

        fun uploadToDrive(list: List<ShelfEntry>) {
            val targets: List<ShelfEntry> = if (list.isEmpty()) return else list
            if (!driveClient.isConfigured() || !driveClient.isAuthorized()) {
                // 没登录：先记下要传的书，登录成功自动续传
                pendingUploads = targets
                driveLoginOpen = true
                return
            }
            coroutineScope.launch {
                snackbarHostState.showSnackbar("正在上传 ${targets.size} 本到谷歌云盘…")
                try {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        targets.forEach { entry ->
                            // 原书文件在 ReaderDocumentPool（filesDir 副本）；
                            // KnowledgeDocument 上没有路径 —— 别搞混这两个模型。
                            val f = localFileOf(entry)
                            if (f != null && f.exists()) driveClient.uploadFile(f.name, f)
                        }
                    }
                    snackbarHostState.showSnackbar("已上传 ${targets.size} 本到云盘「条畅」文件夹")
                } catch (e: Exception) {
                    snackbarHostState.showSnackbar("云盘上传失败：${e.message}")
                }
            }
        }


        // ---- 登录对话框：粘贴 Client ID → 浏览器授权（回环）→ 自动续传挂起任务 ----
        if (driveLoginOpen) {
            var clientIdInput by remember { mutableStateOf(driveClient.let { "" }) }
            var busy by remember { mutableStateOf(false) }
            var errMsg by remember { mutableStateOf<String?>(null) }
            AlertDialog(
                onDismissRequest = { if (!busy) driveLoginOpen = false },
                title = { Text("连接谷歌云盘", fontWeight = FontWeight.Bold) },
                text = {
                    Column {
                        Text(
                            "一次性的准备：在 Google Cloud Console 建「Android 类型」OAuth 客户端" +
                                "（包名 com.treechat.workbench），把 Client ID 粘到这里。授权范围只有" +
                                "「本应用创建的文件」，你的其它网盘内容软件看不见。"
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = clientIdInput,
                            onValueChange = { clientIdInput = it },
                            singleLine = true,
                            placeholder = { Text("xxxxxxxx.apps.googleusercontent.com") },
                            textStyle = MaterialTheme.typography.bodySmall
                        )
                        errMsg?.let {
                            Text(it, color = scheme.error, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        enabled = !busy && clientIdInput.isNotBlank(),
                        onClick = {
                            busy = true
                            driveClient.saveClientId(clientIdInput)
                            coroutineScope.launch {
                                val err = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                    runCatching { driveClient.authorize() }
                                        .exceptionOrNull()?.message
                                }
                                busy = false
                                if (err == null && driveClient.isAuthorized()) {
                                    driveLoginOpen = false
                                    pendingUploads?.let { list ->
                                        pendingUploads = null
                                        uploadToDrive(list)
                                    }
                                } else {
                                    errMsg = err ?: "授权未完成"
                                }
                            }
                        }
                    ) { Text(if (busy) "跳转浏览器…" else "登录") }
                },
                dismissButton = {
                    TextButton(enabled = !busy, onClick = { driveLoginOpen = false }) { Text("取消") }
                }
            )
        }

        // ---- 云端面板：文件列表（"条畅"文件夹）+ 下载到本地 Download ----
        if (drivePanelOpen) {
            var listing by remember { mutableStateOf<List<GoogleDriveClient.CloudFile>?>(null) }
            var loadErr by remember { mutableStateOf<String?>(null) }
            LaunchedEffect(Unit) {
                if (!driveClient.isConfigured() || !driveClient.isAuthorized()) {
                    drivePanelOpen = false
                    driveLoginOpen = true
                    return@LaunchedEffect
                }
                try {
                    listing = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        driveClient.listFilesInShelf()
                    }
                } catch (e: Exception) {
                    loadErr = e.message
                }
            }
            AlertDialog(
                onDismissRequest = { drivePanelOpen = false },
                title = { Text("谷歌云盘 · 条畅", fontWeight = FontWeight.Bold) },
                text = {
                    Column {
                        when {
                            loadErr != null -> Text("读取失败：$loadErr", color = scheme.error)
                            listing == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
                            listing!!.isEmpty() -> Text("云端「条畅」文件夹还没有文件。拖一本书到右下角云盘图标，或多选后点上传。")
                            else -> {
                                listing!!.forEach { cf ->
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 4.dp)
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                cf.name,
                                                style = MaterialTheme.typography.bodyMedium,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                "${cf.size / 1024 / 1024} MB",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = scheme.outline
                                            )
                                        }
                                        Row {
                                            // 直接进书架：下载到缓存 → 走导入书籍同一条管线
                                            TextButton(onClick = {
                                                coroutineScope.launch {
                                                    try {
                                                        val tmp = File(context.cacheDir, "cloud_${cf.name}")
                                                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                                            driveClient.download(cf.id, tmp)
                                                        }
                                                        snackbarHostState.showSnackbar("已下载，正在导入书架…")
                                                        onImportFromCloud(tmp)
                                                        tmp.delete()
                                                    } catch (e: Exception) {
                                                        snackbarHostState.showSnackbar("导入失败：${e.message}")
                                                    }
                                                }
                                            }) { Text("导入书架") }
                                            // 存到系统 Download/条畅：任何 App 都能拿到
                                            TextButton(onClick = {
                                                coroutineScope.launch {
                                                    try {
                                                        val dl = java.io.File(
                                                            android.os.Environment.getExternalStoragePublicDirectory(
                                                                android.os.Environment.DIRECTORY_DOWNLOADS
                                                            ),
                                                            "条畅"
                                                        )
                                                        dl.mkdirs()
                                                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                                            driveClient.download(cf.id, File(dl, cf.name))
                                                        }
                                                        snackbarHostState.showSnackbar("已存到 Download/条畅")
                                                    } catch (e: Exception) {
                                                        snackbarHostState.showSnackbar("下载失败：${e.message}")
                                                    }
                                                }
                                            }) { Text("存文件") }
                                        }
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { drivePanelOpen = false }) { Text("关闭") }
                },
                dismissButton = {
                    TextButton(onClick = {
                        driveClient.signOut()
                        drivePanelOpen = false
                    }) { Text("退出登录") }
                }
            )
        }


        if (entries.isEmpty()) {
            EmptyShelfHint()
        } else {
            if (selectionMode) {
                val allSelected = filteredIds(entries, shelfQuery).let { ids ->
                    ids.isNotEmpty() && ids.all { selectedDocIds.containsKey(it) }
                }
                ShelfBatchBar(
                    selectedCount = selectedDocIds.size,
                    allSelected = allSelected,
                    onSelectAllToggle = {
                        val ids = filteredIds(entries, shelfQuery)
                        if (allSelected) {
                            selectedDocIds.clear()
                        } else {
                            ids.forEach { selectedDocIds[it] = true }
                        }
                    },
                    onBatchDelete = { batchDeleteConfirm = true },
                    onUploadCloud = { uploadToDrive(selectedEntries()) },
                    onExitSelectionMode = {
                        selectionMode = false
                        selectedDocIds.clear()
                    }
                )
            } else {
                ShelfBrowserBar(
                    query = shelfQuery,
                    onQueryChange = { shelfQuery = it },
                    sort = shelfSort,
                    onSortChange = { shelfSort = it },
                    onResetPlacement = {
                        freePositions = emptyMap()
                        shelfPrefs.clear()
                    },
                    gridMode = shelfGrid,
                    onGridModeChange = { shelfGrid = it },
                    disableCrop = disableCoverCrop,
                    onDisableCropChange = onDisableCoverCropChange,
                    pdfCoverEnabled = pdfCoverEnabled,
                    onPdfCoverEnabledChange = onPdfCoverEnabledChange
                )
            }

            val comparator: Comparator<ShelfEntry> = when (shelfSort) {
                ShelfSort.RECENT -> compareByDescending {
                    it.reading?.lastReadTimestamp ?: it.document.createdAt
                }
                ShelfSort.TITLE -> compareBy { it.document.title.lowercase() }
                ShelfSort.IMPORTED -> compareByDescending { it.document.createdAt }
            }
            val filtered = entries
                .filter {
                    shelfQuery.isBlank() ||
                        it.document.title.contains(shelfQuery.trim(), ignoreCase = true) ||
                        it.document.id in contentHitDocIds
                }
                .sortedWith(comparator)

            Box(modifier = Modifier.fillMaxSize()) {
            if (filtered.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "没有匹配「${shelfQuery.trim()}」的书",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            } else if (shelfGrid) {
                // ============ 图标视图：安卓桌面式自由摆放画布（与访达同构） ============
                // 为什么不用 LazyVerticalGrid：自由摆放要求"每个图标有绝对坐标"，
                // 而网格布局把位置交给布局系统，两者互斥。书本数量通常几十本，
                // 一次性布局的开销可接受，因此不用惰性列表。
                val density = androidx.compose.ui.platform.LocalDensity.current
                val px = density.density
                val canvasWidthDp = canvasBounds?.width?.div(px) ?: 0f
                val columns = FreePlacement.columnCount(canvasWidthDp, SHELF_CELL_W_DP)
                val positions = remember(filtered, freePositions, columns) {
                    filtered.mapIndexed { index, e ->
                        FreePlacement.positionOf(
                            index = index,
                            itemId = e.document.id,
                            columns = columns,
                            custom = freePositions,
                            cellWDp = SHELF_CELL_W_DP,
                            cellHDp = SHELF_CELL_H_DP
                        )
                    }
                }
                val contentHeightDp = remember(positions) {
                    FreePlacement.contentHeightDp(positions, SHELF_CELL_H_DP)
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .onGloballyPositioned { coords ->
                            val origin = coords.positionInRoot()
                            canvasBounds = Rect(
                                origin.x,
                                origin.y,
                                origin.x + coords.size.width,
                                origin.y + coords.size.height
                            )
                        }
                        .verticalScroll(shelfScroll)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(contentHeightDp.dp)
                    ) {
                        // 可见窗口虚拟化：只组合"落在可视区 ± 一屏"的书卡片。
                        //
                        // 为什么必须做：原来用 LazyVerticalGrid，只组合可见的十几张卡；
                        // 换成自由摆放画布后如果把 filtered 全量组合，几百本书会同时
                        // 解码封面位图（每张最大 720px，ARGB 约 2MB），内存直接爆掉。
                        // 位置仍然按全量算（摆放坐标系不能变），只是不渲染窗口外的。
                        val viewportHeightPx = canvasBounds?.height ?: 0f
                        val scrollPx = shelfScroll.value.toFloat()
                        val cellHeightPx = SHELF_CELL_H_DP * px
                        val visibleIndexes = positions.indices.filter { i ->
                            val top = positions[i].y * px
                            val bottom = top + cellHeightPx
                            bottom >= scrollPx - viewportHeightPx &&
                                top <= scrollPx + viewportHeightPx * 2f
                        }
                        visibleIndexes.forEach { index ->
                            val entry = filtered[index]
                            val pos = positions[index]
                            val isSelected = selectedDocIds.containsKey(entry.document.id)
                            Box(
                                modifier = Modifier
                                    .offset(x = pos.x.dp, y = pos.y.dp)
                                    .size(SHELF_CELL_W_DP.dp, SHELF_CELL_H_DP.dp)
                            ) {
                                ShelfBookCard(
                                    entry = entry,
                                    grid = true,
                                    coversVersion = coversVersion,
                                    disableCrop = disableCoverCrop,
                                    selectionMode = selectionMode,
                                    isSelected = isSelected,
                                    onToggleSelect = {
                                        if (isSelected) {
                                            selectedDocIds.remove(entry.document.id)
                                            if (selectedDocIds.isEmpty()) selectionMode = false
                                        } else {
                                            selectedDocIds[entry.document.id] = true
                                        }
                                    },
                                    onEnterSelectionMode = {
                                        selectionMode = true
                                        selectedDocIds[entry.document.id] = true
                                    },
                                    onOpen = { onOpenDocument(entry) },
                                    onInspectChunks = { onInspectChunks(entry) },
                                    onRebuildIndex = { onRebuildIndex(entry) },
                                    onTriggerOcr = { onTriggerOcr(entry) },
                                    onRename = { newTitle -> onRenameDocument(entry, newTitle) },
                                    onSetEnabled = { enabled -> onSetEnabled(entry, enabled) },
                                    onDelete = { onDeleteDocument(entry) },
                                    contentHit = shelfQuery.isNotBlank() &&
                                        entry.document.id in contentHitDocIds &&
                                        !entry.document.title.contains(
                                            shelfQuery.trim(),
                                            ignoreCase = true
                                        ),
                                    trashBounds = trashBounds,
                                    onDragFingerChange = { pos -> trashDragPos = pos; draggedEntry = if (pos == null) null else entry },
                                    onDropToTrash = { batchDeleteConfirm = true },
                                    driveBounds = driveBounds,
                                    onDropToDrive = {
                                        uploadToDrive(
                                            if (selectionMode) selectedEntries() else listOf(entry)
                                        )
                                    },
                                    onDragRelease = { rootPos ->
                                        // 松手落点：先判废纸篓（删除），否则就地摆放并落盘。
                                        val canvas = canvasBounds
                                        if (trashBounds?.contains(rootPos) != true && canvas != null) {
                                            val contentY = rootPos.y - canvas.top + shelfScroll.value
                                            val placed = FreePlacement.clampPlacement(
                                                x = (rootPos.x - canvas.left) / px - SHELF_CELL_W_DP / 2f,
                                                y = contentY / px - SHELF_CELL_H_DP / 2f,
                                                canvasWidthDp = canvas.width / px,
                                                maxContentHeightDp = maxOf(
                                                    contentHeightDp,
                                                    canvas.height / px
                                                ),
                                                cellWDp = SHELF_CELL_W_DP,
                                                cellHDp = SHELF_CELL_H_DP
                                            )
                                            val alive = entries.map { it.document.id }.toSet()
                                            freePositions = (freePositions + (entry.document.id to placed))
                                                .filterKeys { it in alive }
                                            shelfPrefs.save(freePositions)
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filtered, key = { it.document.id }) { entry ->
                        val isSelected = selectedDocIds.containsKey(entry.document.id)
                        ShelfBookCard(
                            entry = entry,
                            grid = false,
                            coversVersion = coversVersion,
                            disableCrop = disableCoverCrop,
                            selectionMode = selectionMode,
                            isSelected = isSelected,
                            onToggleSelect = {
                                if (isSelected) {
                                    selectedDocIds.remove(entry.document.id)
                                    if (selectedDocIds.isEmpty()) selectionMode = false
                                } else {
                                    selectedDocIds[entry.document.id] = true
                                }
                            },
                            onEnterSelectionMode = {
                                selectionMode = true
                                selectedDocIds[entry.document.id] = true
                            },
                            onOpen = { onOpenDocument(entry) },
                            onInspectChunks = { onInspectChunks(entry) },
                            onRebuildIndex = { onRebuildIndex(entry) },
                            onTriggerOcr = { onTriggerOcr(entry) },
                            onRename = { newTitle -> onRenameDocument(entry, newTitle) },
                            onSetEnabled = { enabled -> onSetEnabled(entry, enabled) },
                            onDelete = { onDeleteDocument(entry) },
                            contentHit = shelfQuery.isNotBlank() &&
                                entry.document.id in contentHitDocIds &&
                                !entry.document.title.contains(
                                    shelfQuery.trim(),
                                    ignoreCase = true
                                ),
                            trashBounds = trashBounds,
                            onDragFingerChange = { pos -> trashDragPos = pos; draggedEntry = if (pos == null) null else entry },
                            onDropToTrash = { batchDeleteConfirm = true }
                        )
                    }
                }
            }

                // ---- 拖拽幽灵：被拖的书跟手（拖起时绘一册半透明缩卡）----
                val ghostEntry = draggedEntry
                val ghostPos = trashDragPos
                if (ghostEntry != null && ghostPos != null) {
                    Surface(
                        color = scheme.surfaceContainerHigh.copy(alpha = 0.94f),
                        tonalElevation = 8.dp,
                        shadowElevation = 10.dp,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .offset {
                                val ghostW = with(density) { 50.dp.toPx() }
                                val ghostTop = with(density) { 90.dp.toPx() }
                                IntOffset((ghostPos.x - ghostW).toInt(), (ghostPos.y - ghostTop).toInt())
                            }
                            .size(100.dp, 140.dp)
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                            modifier = Modifier.fillMaxSize().padding(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.AutoStories,
                                contentDescription = null,
                                tint = scheme.primary,
                                modifier = Modifier.size(30.dp)
                            )
                            Text(
                                text = ghostEntry.document.title,
                                style = MaterialTheme.typography.labelSmall,
                                color = scheme.onSurface,
                                maxLines = 4,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }

                // ---- 谷歌云盘落点：拖入即上传；点开 = 云端面板 ----
                if (selectionMode) {
                    val driveHot = trashDragPos != null &&
                        driveBounds?.contains(trashDragPos!!) == true
                    Surface(
                        onClick = { drivePanelOpen = true },
                        shape = CircleShape,
                        color = if (driveHot) scheme.primaryContainer else scheme.surfaceContainerHighest,
                        shadowElevation = 6.dp,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(end = 92.dp, bottom = 18.dp)
                            .size(58.dp)
                            .onGloballyPositioned { coords ->
                                val p = coords.positionInRoot()
                                driveBounds = Rect(
                                    p.x, p.y,
                                    p.x + coords.size.width, p.y + coords.size.height
                                )
                            }
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Outlined.CloudUpload,
                                contentDescription = "拖到这里上传谷歌云盘，点按打开云端",
                                tint = if (driveHot) scheme.primary else scheme.onSurfaceVariant,
                                modifier = Modifier.size(26.dp)
                            )
                        }
                    }
                }

                // 废纸篓：多选态下出现在右下角；被拖到上方时高亮。
                if (selectionMode) {
                    val hot = trashDragPos != null &&
                        trashBounds?.contains(trashDragPos!!) == true
                    Surface(
                        shape = CircleShape,
                        color = if (hot) scheme.errorContainer else scheme.surfaceContainerHighest,
                        shadowElevation = 6.dp,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(18.dp)
                            .size(58.dp)
                            .onGloballyPositioned { coords ->
                                val p = coords.positionInRoot()
                                trashBounds = Rect(
                                    p.x,
                                    p.y,
                                    p.x + coords.size.width,
                                    p.y + coords.size.height
                                )
                            }
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Outlined.Delete,
                                contentDescription = "拖到这里删除",
                                tint = if (hot) scheme.error else scheme.onSurfaceVariant,
                                modifier = Modifier.size(26.dp)
                            )
                        }
                    }
                }

                // 本组件自己的提示条（云盘上传/下载反馈在这里出现）
                SnackbarHost(
                    hostState = snackbarHostState,
                    modifier = Modifier.align(Alignment.BottomCenter)
                )
            }
        }
    }
}

/** 书架空状态的统一提示（含 MOBI/AZW3）。 */
@Composable
private fun EmptyShelfHint() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(horizontal = 24.dp)
        ) {
            Icon(
                imageVector = Icons.Outlined.AutoStories,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.outline
            )
            Text(
                text = "书架暂无文献：点上方【导入书籍】加 PDF / Word / PPT / EPUB / TXT / MD / MOBI / AZW3，" +
                    "或点【网页阅读】直接打开内置浏览器",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.outline,
                textAlign = TextAlign.Center
            )
        }
    }
}

/**
 * 书架工具栏：导入 / 全局检索开关 / 检索命中测试 / 方案·API / 后台任务。
 *
 * 这几项原先散在「设置 → 检索与知识库 → 打开管理」里；按用户要求统一搬到书架页顶部。
 */
@Composable
private fun ShelfToolbar(
    busy: Boolean,
    onImport: () -> Unit,
    onOpenBrowser: () -> Unit,
    /** 打开命令与检索面板（搜会话、书籍、分支标题与消息内容）。 */
    onOpenSearch: () -> Unit = {},
    /** 打开扫描工作台：拍照 / 相册导入 / 断点续拍 / 合成 PDF。 */
    globalEnabled: Boolean,
    onGlobalEnabledChange: (Boolean) -> Unit,
    showRetrievalTest: Boolean,
    onToggleRetrievalTest: () -> Unit,
    onOpenSchemeSettings: () -> Unit,
    runningTaskCount: Int,
    onOpenBackgroundTasks: () -> Unit,
    totalDocs: Int,
    totalChunks: Int
) {
    Surface(
        color = PanelSurface,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, DividerDark),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Button(
                onClick = onImport,
                enabled = !busy,
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = AccentCyan,
                    contentColor = Color.Black
                )
            ) {
                Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("导入书籍", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }

            // 搜索对话：浏览器的网页与书籍好说，但对"我以前聊过的那句话"最难找。
            // 这里给一个常驻按钮，不依赖用户知道 Ctrl+F / Ctrl+K。
            OutlinedButton(
                onClick = onOpenSearch,
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, DividerDark),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = PanelSurface,
                    contentColor = AccentIceBlue
                )
            ) {
                Icon(
                    imageVector = Icons.Outlined.Search,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("搜索", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }

            // 网页阅读：把内置浏览器放到书架最显眼的操作区，与"导入书籍"并排，
            // 第一次进书架就能看见 —— 不再只是一个没有文字的顶栏图标。
            OutlinedButton(
                onClick = onOpenBrowser,
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, DividerDark),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = PanelSurface,
                    contentColor = TextPrimary
                )
            ) {
                Icon(
                    imageVector = Icons.Outlined.Public,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = AccentCyan
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("网页阅读", fontSize = 12.sp, fontWeight = FontWeight.Medium)
            }

            OutlinedButton(
                onClick = onToggleRetrievalTest,
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, if (showRetrievalTest) AccentCyan else DividerDark),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = if (showRetrievalTest) AccentCyan.copy(alpha = 0.15f) else Color.Transparent,
                    contentColor = if (showRetrievalTest) AccentCyan else TextPrimary
                )
            ) {
                Icon(Icons.Outlined.Search, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("检索测试", fontSize = 12.sp, fontWeight = FontWeight.Medium)
            }

            OutlinedButton(
                onClick = onOpenSchemeSettings,
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, DividerDark),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = PanelSurface,
                    contentColor = TextPrimary
                )
            ) {
                Icon(
                    imageVector = Icons.Outlined.Settings,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = AccentGold
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("方案/API", fontSize = 12.sp, fontWeight = FontWeight.Medium)
            }

            OutlinedButton(
                onClick = onOpenBackgroundTasks,
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, DividerDark),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = if (runningTaskCount > 0) AccentCyan.copy(alpha = 0.15f) else PanelSurface,
                    contentColor = TextPrimary
                )
            ) {
                Icon(
                    imageVector = Icons.Outlined.HourglassEmpty,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = if (runningTaskCount > 0) AccentCyan else TextSecondary
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (runningTaskCount > 0) "后台任务 $runningTaskCount" else "后台任务",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            Spacer(modifier = Modifier.width(4.dp))

            Text(
                text = "共 $totalDocs 册 · $totalChunks 段切片",
                color = TextMuted,
                fontSize = 11.sp,
                maxLines = 1
            )

            Spacer(modifier = Modifier.width(4.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(CardSurface)
                    .border(1.dp, DividerDark, RoundedCornerShape(10.dp))
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text(
                    text = if (globalEnabled) "全局检索：开启" else "全局检索：关闭",
                    color = if (globalEnabled) AccentNeonGreen else TextMuted,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.width(8.dp))
                Switch(
                    checked = globalEnabled,
                    onCheckedChange = onGlobalEnabledChange,
                    colors = SwitchDefaults.colors(checkedTrackColor = AccentNeonGreen)
                )
            }
        }
    }
}

/** 书架排序口径。 */
internal fun ReaderFormat.roomSourceType(): String = when (this) {
    ReaderFormat.SCANNED_PDF -> "PDF"
    ReaderFormat.MARKDOWN -> "MARKDOWN"
    ReaderFormat.TXT -> "TEXT"
    else -> name
}

/** 知识库 `sourceType` → 阅读器格式（用于旧库导入的书补建阅读状态）。 */
internal fun readerFormatForSource(sourceType: String): ReaderFormat = when (sourceType.uppercase()) {
    "PDF" -> ReaderFormat.PDF
    "EPUB" -> ReaderFormat.EPUB
    "DOCX" -> ReaderFormat.DOCX
    "DOC" -> ReaderFormat.DOC
    "PPTX" -> ReaderFormat.PPTX
    "PPT" -> ReaderFormat.PPT
    "MARKDOWN", "MD" -> ReaderFormat.MARKDOWN
    else -> ReaderFormat.TXT
}

/** 把入库的索引方案标识转成卡片上的短标签。 */
private fun mergePageText(body: String, pageIndex1Based: Int, pageText: String): String {
    val marker = "【第 $pageIndex1Based 页】"
    val block = "$marker\n${pageText.trim()}"
    val existing = body.indexOf(marker)
    if (existing < 0) {
        val sep = if (body.isBlank()) "" else "\n\n"
        return body + sep + block
    }
    val next = body.indexOf("【第 ", existing + marker.length)
    val end = if (next < 0) body.length else next
    return body.substring(0, existing) + block +
        (if (next < 0) "" else body.substring(next))
}
