package com.treechat.workbench.ui.browser

import android.app.Activity
import android.net.Uri
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.AlertDialog
import androidx.compose.ui.graphics.luminance
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.treechat.workbench.etymology.ui.EtymoScopeBottomSheet
import com.treechat.workbench.ui.chat.ChatNodeViewModel
import com.treechat.workbench.ui.reader.OcrWordBox
import com.treechat.workbench.ui.reader.ReaderDocumentPool
import com.treechat.workbench.ui.reader.ReaderNoteBridge
import com.treechat.workbench.ui.selection.FloatingSelectionToolbar
import com.treechat.workbench.ui.window.FloatingWindowManager
import kotlinx.coroutines.launch

/** 工具条与选区之间的竖直间距：既是工具条落位的高度，也是选区块带向上多留的余量。 */
private val TOOLBAR_GAP = 56.dp

/** 域名取不到时的兜底文件夹名（不写死任何具体站点）。 */
private const val FALLBACK_FOLDER = "网页"

/** 输入不是网址时的必应 (Bing) 搜索入口。 */
private const val SEARCH_PREFIX = "https://cn.bing.com/search?q="

/**
 * 内置轻量浏览器。
 *
 * 能力：
 * 1. 普通网页浏览（地址栏 / 后退 / 前进 / 刷新 / 停止 / 进度）；
 * 2. 网页选词提问：长按选中文字 → 复用阅读器那套 Material 3 工具条
 *    （提问 / 划线 / 笔记 / 词源 / 复制）；
 * 3. 阅读模式（可一键还原，见 BrowserJs.READER_CSS）；
 * 4. 按网址或页面标题生成 root 对话，会话归入**以域名为名的文件夹**。
 *
 * 坐标换算（整个功能最容易错的一环）见 [selectionGeometry] 的注释。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebBrowserScreen(
    viewModel: ChatNodeViewModel,
    windowManager: FloatingWindowManager,
    onExit: () -> Unit,
    controller: BrowserController = remember { BrowserController() },
    /**
     * 浏览器当前是否**处于前台**（BROWSER Tab 被选中）。
     *
     * 为什么需要这个参数：浏览器为了保住网页状态被做成了常驻覆盖层，
     * 切走时只是收成 0 尺寸、**不离开组合树**。副作用是它的内部状态（沉浸态、
     * 选中的文本）也跟着常驻 —— 用户点过一次沉浸（地址栏整行收起）之后，
     * 无论切多少次 Tab 都回不来，看起来就像"输入网址的地方不见了"。
     * 旧实现切 Tab 会重建整个界面、状态自然复位，这里要把那份复位补回来。
     */
    isActive: Boolean = true,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val scheme = MaterialTheme.colorScheme

    val snackbarHostState = remember { SnackbarHostState() }
    val noteBridge = remember { ReaderNoteBridge(context) }

    var addressText by remember { mutableStateOf("") }
    var addressFocused by remember { mutableStateOf(false) }
    var readerModeOn by remember { mutableStateOf(false) }
    val isAppDark = isSystemInDarkTheme() || (MaterialTheme.colorScheme.background.luminance() < 0.5f)
    var readerDark by remember(isAppDark) { mutableStateOf(isAppDark) }

    /**
     * 浏览器的沉浸态。
     *
     * 沉浸 = 地址栏 + 导航/功能行 + 进度条全部收起，并且连系统状态栏/导航栏也一起
     * 让位（与阅读器的沉浸口径一致），整屏只留网页；**点一下网页**再把它们唤回来。
     *
     * 为什么"点一下"只在沉浸态生效（而不是两种状态都切换）：
     * 网页里的点击是正常操作（点链接、点按钮），如果在非沉浸态也拿它来收起地址栏，
     * 用户每点一个链接就会把地址栏弄没。所以收起只走功能行上的显式按钮。
     */
    var immersive by remember { mutableStateOf(false) }

    val activity = context as? Activity
    val view = LocalView.current
    LaunchedEffect(immersive) {
        val window = activity?.window ?: return@LaunchedEffect
        val insetController = WindowCompat.getInsetsController(window, view)
        if (immersive) {
            insetController.hide(WindowInsetsCompat.Type.systemBars())
            insetController.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            insetController.show(WindowInsetsCompat.Type.systemBars())
        }
    }
    // 退出浏览器时把系统栏还给用户，否则会一路带到别的页面
    DisposableEffect(Unit) {
        onDispose {
            val window = activity?.window ?: return@onDispose
            WindowCompat.getInsetsController(window, view).show(WindowInsetsCompat.Type.systemBars())
        }
    }

    /** 当前选中的网页文本；null 表示没有选区（工具条不显示）。 */
    var selection by remember { mutableStateOf<BrowserSelection?>(null) }
    // 重新进入浏览器时复位沉浸态与选区：等价于旧实现"切 Tab 重建界面"的效果。
    LaunchedEffect(isActive) {
        if (isActive) {
            immersive = false
            selection = null
        }
    }

    // "点按清空选区"的挂起任务：延迟清空给触屏选词收尾留出窗口，可被非空选区帧取消
    var tapClearJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val coroutineScope = rememberCoroutineScope()
    var etymologyWord by remember { mutableStateOf<String?>(null) }
    /** 非 null 表示"写网页批注"对话框打开，值是待批注的原文。 */
    var noteDraft by remember { mutableStateOf<String?>(null) }

    /** 复制后的即时反馈（与对话界面、文字文档阅读器是同一套：按钮短暂显示"已复制"）。 */
    var copied by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current

    // 每次选区变化都清掉"已复制"反馈，否则上一次的勾会留在新工具条上。
    LaunchedEffect(selection) { copied = false }

    /** 内容区（WebView 与工具条共同的坐标系原点）在窗口中的位置与尺寸，单位 px。 */
    var contentOriginInWindow by remember { mutableStateOf(Offset.Zero) }
    var contentSize by remember { mutableStateOf(IntSize.Zero) }

    // 地址栏跟随当前页；用户正在输入时绝不覆盖（否则跳转会把输入吃掉）
    LaunchedEffect(controller.currentUrl, addressFocused) {
        if (!addressFocused) addressText = displayUrl(controller.currentUrl)
    }

    fun toast(message: String) {
        scope.launch { snackbarHostState.showSnackbar(message) }
    }

    fun submitAddress(raw: String) {
        val target = normalizeAddress(raw)
        if (target == null) {
            toast("无法识别的网址")
            return
        }
        selection = null
        controller.loadUrl(target)
    }

    /**
     * 应用/关闭阅读模式。
     *
     * 关闭时执行"还原脚本"（摘属性 + 删 style 节点），**不需要**保存页面的任何状态 ——
     * 因为注入只加了一个 style 节点与两个 html 属性，没有改过 DOM 结构与内联样式。
     * 打开时若页面正文文字不足（纯 Web 应用页），脚本返回 '0'，这里如实报"无法进入"，
     * 并把开关拨回去 —— 不伪装成功。
     */
    fun applyReaderMode(on: Boolean) {
        if (!on) {
            controller.evaluate(BrowserJs.readerModeDisableScript(), null)
            return
        }
        controller.evaluate(BrowserJs.readerModeEnableScript()) { result ->
            if (result.trim('"') == "1") {
                controller.evaluate(BrowserJs.readerDarkScript(readerDark), null)
            } else {
                readerModeOn = false
                toast("本页无法进入阅读模式")
            }
        }
    }

    /** root 对话名：优先网页 <title>，为空退到域名；两者都空才用兜底串。 */
    fun pageNaming(): String {
        val domain = domainOf(controller.currentUrl)
        val title = controller.pageTitle.trim()
            .ifBlank { selection?.pageTitle?.trim().orEmpty() }
            .ifBlank { domain }
            .ifBlank { FALLBACK_FOLDER }
        // 网页标题常常是一整段营销文案，截断到可读长度；内容仍**全部来自网页**
        return title.take(80)
    }

    /**
     * 选区提问：**以页面名命名的根对话树**，这次提问挂在它根节点下。
     *
     * 不再"按站点域名建文件夹、每问一次新建一棵会话" ——
     * 那样同一个网站会散成一批互不相干的会话，而且这些节点上的引用
     * 在别处取不到（链接目标曾按当前会话取），于是引用关系对不上。
     */
    fun askAboutSelection(text: String) {
        if (text.isBlank()) return
        val title = pageNaming()
        viewModel.createQuestionInSourceTree(
            sourceTitle = title,
            question = "",
            originQuote = text,
            onCreated = { nodeId, _ ->
                windowManager.openSubWindow(
                    nodeId = nodeId,
                    title = text.take(28).trim().ifBlank { title },
                    originQuote = text,
                    depth = 1
                )
            }
        )
    }

    /** 按网址生成对话：不选词也要能为"当前这一页"开一个 root 对话。 */
    fun createConversationForPage() {
        val url = controller.currentUrl
        if (url.isBlank() || url == BrowserJs.START_URL) {
            toast("请先打开一个网页")
            return
        }
        val domain = domainOf(url).ifBlank { FALLBACK_FOLDER }
        val title = pageNaming()
        selection = null
        // 同上：以页面名命名的根对话树，不建文件夹。
        viewModel.createQuestionInSourceTree(
            sourceTitle = title,
            question = "",
            // 用页面地址当引用：既让节点记住"这页从哪来"，
            // 也满足入口对"提问与引用不能同时为空"的校验。
            originQuote = url,
            onCreated = { nodeId, _ ->
                windowManager.openSubWindow(
                    nodeId = nodeId,
                    title = title,
                    originQuote = url,
                    depth = 1
                )
            }
        )
        toast("已在「$title」下建好对话")
    }

    val shelfPool = remember { ReaderDocumentPool(context) }
    var exportingPdf by remember { mutableStateOf(false) }

    /**
     * 将当前网页完整内容（若处于阅读模式，则天然是去除了垃圾、保留正文插图的高保真排版）
     * 渲染导出为标准 PDF 并立刻注册到书架。
     */
    fun exportPageAsPdf() {
        val webView = controller.webView
        if (webView == null) {
            toast("网页尚未就绪，无法导出")
            return
        }
        val url = controller.currentUrl
        if (url.isBlank() || url == BrowserJs.START_URL) {
            toast("请先打开一个有效网页再导出")
            return
        }
        if (exportingPdf) return

        exportingPdf = true
        val title = pageNaming()
        toast("正在排版并生成 PDF 归档文件…")

        scope.launch {
            val result = WebToPdfExporter.exportAndImportToShelf(
                context = context,
                webView = webView,
                documentTitle = title,
                viewModel = viewModel,
                pool = shelfPool
            )
            exportingPdf = false
            when (result) {
                is WebToPdfExporter.ExportResult.Success -> {
                    val modeNote = if (readerModeOn) "（纯净正文模式）" else ""
                    toast("已生成 PDF 归档并录入书架$modeNote：《${result.title}》")
                }
                is WebToPdfExporter.ExportResult.Failure -> {
                    toast(result.reason)
                }
            }
        }
    }

    fun highlightSelection(text: String) {
        if (text.isBlank()) return
        val url = controller.currentUrl
        noteBridge.saveHighlightToNote(
            documentId = url.ifBlank { BrowserJs.START_URL },
            bookTitle = controller.pageTitle.trim().ifBlank { domainOf(url).ifBlank { FALLBACK_FOLDER } },
            pageIndex = 0,
            selectedQuote = text,
            userComment = "",
            cardTitle = "网页划线摘录"
        )
        toast("已划线并同步至笔记白板")
    }

    fun saveNote(comment: String) {
        val quote = noteDraft ?: return
        noteDraft = null
        val url = controller.currentUrl
        noteBridge.saveHighlightToNote(
            documentId = url.ifBlank { BrowserJs.START_URL },
            bookTitle = controller.pageTitle.trim().ifBlank { domainOf(url).ifBlank { FALLBACK_FOLDER } },
            pageIndex = 0,
            selectedQuote = quote,
            userComment = comment.ifBlank { "网页批注" },
            cardTitle = "网页批注"
        )
        toast("已保存网页批注")
    }

    // 系统返回：沉浸态先退出沉浸（保住"出得去"这条底线），再走网页历史，退无可退交回书架
    BackHandler {
        when {
            immersive -> immersive = false
            controller.canGoBack -> controller.goBack()
            else -> onExit()
        }
    }

    Scaffold(
        containerColor = scheme.surface,
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        Column(modifier = modifier.fillMaxSize().padding(innerPadding)) {

            // ---------------- 地址栏行 ----------------
            // 沉浸态整行收起；点一下网页再出来。
            AnimatedVisibility(
                visible = !immersive,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { controller.goBack() }, enabled = controller.canGoBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "后退")
                    }
                    IconButton(onClick = { controller.goForward() }, enabled = controller.canGoForward) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowForward, contentDescription = "前进")
                    }
                    IconButton(onClick = {
                        if (controller.isLoading) controller.stopLoading() else controller.reload()
                    }) {
                        if (controller.isLoading) {
                            Icon(Icons.Outlined.Close, contentDescription = "停止加载")
                        } else {
                            Icon(Icons.Outlined.Refresh, contentDescription = "刷新")
                        }
                    }
                    OutlinedTextField(
                        value = addressText,
                        onValueChange = { addressText = it },
                        modifier = Modifier
                            .weight(1f)
                            .onFocusChanged { addressFocused = it.isFocused },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodySmall,
                        shape = RoundedCornerShape(18.dp),
                        placeholder = {
                            Text("输入网址或必应搜索词", style = MaterialTheme.typography.bodySmall)
                        },
                        trailingIcon = {
                            if (addressText.isNotBlank()) {
                                IconButton(onClick = { submitAddress(addressText) }) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Outlined.ArrowForward,
                                        contentDescription = "载入 / 搜索",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { submitAddress(addressText) })
                    )

                    // 独立载入按钮：无需依赖键盘回车，输入后点按即可直接载入网址或发起必应搜索
                    FilledIconButton(
                        onClick = { submitAddress(addressText) },
                        enabled = addressText.isNotBlank(),
                        modifier = Modifier
                            .padding(start = 4.dp)
                            .size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ArrowForward,
                            contentDescription = "载入",
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            // ---------------- 功能行 ----------------
            AnimatedVisibility(
                visible = !immersive,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilterChip(
                        selected = readerModeOn,
                        onClick = {
                            val next = !readerModeOn
                            readerModeOn = next
                            applyReaderMode(next)
                        },
                        label = { Text("阅读模式", fontSize = 11.sp) }
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    FilterChip(
                        selected = readerDark,
                        enabled = true,
                        onClick = {
                            val next = !readerDark
                            readerDark = next
                            if (readerModeOn) {
                                controller.evaluate(BrowserJs.readerDarkScript(next), null)
                            } else {
                                controller.evaluate(BrowserJs.globalPageDarkScript(next), null)
                            }
                        },
                        label = { Text("深色", fontSize = 11.sp) }
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    FilterChip(
                        selected = controller.desktopMode,
                        onClick = {
                            val next = !controller.desktopMode
                            controller.desktopMode = next
                            toast(if (next) "已切换为电脑模式（Via 风格桌面排版）" else "已切换为移动版版面")
                        },
                        label = { Text("电脑模式", fontSize = 11.sp) }
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    // 沉浸阅读：收起地址栏/导航条与系统栏，整屏只留网页
                    IconButton(onClick = { immersive = true }) {
                        Icon(
                            imageVector = Icons.Outlined.Fullscreen,
                            contentDescription = "沉浸阅读（收起地址栏与导航条，点一下网页再唤出）"
                        )
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    TextButton(
                        onClick = { exportPageAsPdf() },
                        enabled = !exportingPdf
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.PictureAsPdf,
                            contentDescription = null,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(modifier = Modifier.width(3.dp))
                        Text(if (exportingPdf) "排版中…" else "存为PDF", fontSize = 11.sp)
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    TextButton(onClick = { createConversationForPage() }) {
                        Text("本页生成对话", fontSize = 12.sp)
                    }
                    TextButton(onClick = onExit) {
                        Text("返回书架", fontSize = 12.sp)
                    }
                }
            }

            if (controller.isLoading && !immersive) {
                LinearProgressIndicator(
                    progress = { controller.progress.coerceIn(0, 100) / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                )
            }

            // ---------------- 内容区（WebView + 选词工具条） ----------------
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .onGloballyPositioned { coordinates ->
                        contentOriginInWindow = coordinates.localToWindow(Offset.Zero)
                        contentSize = coordinates.size
                    }
            ) {
                BrowserWebView(
                    controller = controller,
                    readerModeOn = readerModeOn,
                    isDark = readerDark,
                    onSelection = { incoming ->
                        // 关键修复：只有收到非空选区才更新；空文本帧绝不清空已固定的选区！
                        // 彻底解决"选词器刚长按出来就一瞬间消失"的致命问题。
                        val text = incoming.text.trim()
                        if (text.isNotBlank()) {
                            // 触屏句柄微动/补发帧到来时，取消挂起的"点按清空"，
                            // 避免刚选上的词被一次快速点按误清
                            tapClearJob?.cancel()
                            tapClearJob = null
                            selection = incoming
                        }
                    },
                    onReaderModeRefused = {
                        // 换页后补注入失败：把开关纠正回来并如实告知，不让开关说谎
                        if (readerModeOn) {
                            readerModeOn = false
                            toast("本页无法进入阅读模式")
                        }
                    },
                    // 点一下网页：若当前正展示选区工具条，延迟 220ms 再清空 ——
                    // 若这其实是选词手势的收尾（马上会有非空选区帧到达），取消清空；
                    // 真正的点空白才清除选区。沉浸态则唤回地址栏。
                    onTapPage = {
                        if (selection != null) {
                            tapClearJob?.cancel()
                            tapClearJob = coroutineScope.launch {
                                kotlinx.coroutines.delay(220)
                                selection = null
                            }
                        } else if (immersive) {
                            immersive = false
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )

                val current = selection
                val webView = controller.webView
                if (current != null && webView != null) {
                    val geometry = remember(
                        current,
                        webView,
                        contentOriginInWindow,
                        contentSize,
                        density
                    ) {
                        selectionGeometry(
                            selection = current,
                            webView = webView,
                            contentOriginInWindow = contentOriginInWindow,
                            contentSize = contentSize,
                            gapPx = with(density) { TOOLBAR_GAP.toPx() }
                        )
                    }

                    // 选区工具条：**与对话界面/文字文档阅读器同一个组件**
                    // （ui/selection/FloatingSelectionToolbar），不是阅读器那套"实况框选"
                    // 浮层。这样浏览器里选词的观感与条畅一致，复制也走同一份实现。
                    //
                    // 锚点：选址区包围盒的顶边中点。geometry 里的 box 是"块带本地坐标"，
                    // 块带被放在 bandTopPx 处，所以换算回内容区坐标要把它加回去。
                    val anchor = remember(geometry) {
                        Rect(
                            left = geometry.box.bounds.left,
                            top = geometry.box.bounds.top + geometry.bandTopPx,
                            right = geometry.box.bounds.right,
                            bottom = geometry.box.bounds.bottom + geometry.bandTopPx
                        )
                    }
                    FloatingSelectionToolbar(
                        visible = true,
                        anchorRect = anchor,
                        onAsk = {
                            val t = current.text
                            selection = null
                            askAboutSelection(t)
                        },
                        onCopy = {
                            if (current.text.isNotBlank()) {
                                clipboard.setText(AnnotatedString(current.text))
                                copied = true
                            }
                            selection = null
                        },
                        onHighlight = {
                            val t = current.text
                            selection = null
                            highlightSelection(t)
                        },
                        // 浏览器不给"全选"（网页的全选由系统本身负责）。
                        onSelectAll = {},
                        onDismiss = { selection = null },
                        onEtymology = {
                            val w = current.text
                            selection = null
                            etymologyWord = w
                        },
                        onNote = {
                            val t = current.text
                            selection = null
                            noteDraft = t
                        },
                        copyFeedback = true,
                        showSelectAll = false,
                        askLabel = "提问",
                        highlightLabel = "划线"
                    )
                }

                val error = controller.lastError
                val blank = controller.currentUrl.isBlank() ||
                    controller.currentUrl == BrowserJs.START_URL
                if (error != null) {
                    Surface(
                        color = scheme.errorContainer,
                        contentColor = scheme.onErrorContainer,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(20.dp)
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Text(error, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                } else if (blank) {
                    Surface(
                        color = scheme.surfaceContainerHigh,
                        contentColor = scheme.onSurface,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(20.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = "内置网页阅读",
                                style = MaterialTheme.typography.titleSmall
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "输入网址回车加载。长按选中文字即可提问、划线、写批注或查词源；" +
                                    "右上角可对本页生成对话，会话会收进以域名命名的文件夹。",
                                style = MaterialTheme.typography.bodySmall,
                                color = scheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        etymologyWord?.let { word ->
            EtymoScopeBottomSheet(
                initialQuery = word,
                onDismissRequest = { etymologyWord = null }
            )
        }

        noteDraft?.let { quote ->
            NoteCommentDialog(
                quote = quote,
                onDismiss = { noteDraft = null },
                onConfirm = { comment -> saveNote(comment) }
            )
        }
    }
}

/**
 * 把网页选区换算成宿主 Compose 坐标，并给出工具条应该落在哪条块带上。
 *
 * 换算分两步，**每一步都必须做对**：
 *
 * 1. CSS 像素 → WebView 视图像素：`视图像素 = CSS 像素 × devicePixelRatio`。
 *
 *    - 倍率来自**页面自己**回传的 `window.devicePixelRatio`（[BrowserSelection.factor]），
 *      不是猜的，也不是 `WebView.getScale()` —— 后者自 Android 4.4 起基本恒为 1，
 *      乘上去会整体算错（这是本功能最大的坑，网上一半的示例代码都错在这里）。
 *      WebView 默认把页面按 ~160dpi 基准缩放，所以 devicePixelRatio 恰好等于
 *      "physical px / CSS px"，与旧的 scale 语义等价。
 *    - **不要加滚动量**：getClientRects/getBoundingClientRect 是视口相对的，
 *      滚动已经被浏览器算进去了（改用 pageX/pageY 那套绝对坐标才需要加 scrollY）。
 *    - 为此 WebView 侧关掉了双指缩放（见 BrowserWebView 的配置）：缩放会让
 *      "视口相对"的矩形与视觉视口错位，而缩放事件又不会触发选区重新上报，
 *      工具条就会飘。放大阅读请走阅读模式。
 *
 * 2. WebView 视图像素 → 本组件坐标系：
 *    WebView 与工具条同处内容区 Box，理论上原点重合；但为了不把"恰好重合"当成
 *    前提，这里用 `WebView.getLocationInWindow` 与内容区的
 *    `LayoutCoordinates.localToWindow` 求差，得到偏移 (dx, dy) 再平移。
 *    两者都用**窗口坐标**（而不是 getLocationOnScreen 的屏幕坐标）：Compose 的
 *    localToWindow 对应的是窗口坐标，混用屏幕坐标会在带状态栏/多窗口时差一条状态栏高度。
 *
 * 返回的 [SelectionGeometry.box] 是**块带本地**坐标（块带自身已经向下偏移了
 * bandTopPx），所以包围盒要减掉 bandTopPx —— 否则工具条会画到选区上方几十像素之外。
 */
private fun selectionGeometry(
    selection: BrowserSelection,
    webView: WebView,
    contentOriginInWindow: Offset,
    contentSize: IntSize,
    gapPx: Float
): SelectionGeometry {
    // 页面没给倍率（老页面/异常）时回落到宿主 density —— WebView 的 devicePixelRatio
    // 通常就等于它，比硬写 1f 更接近真相。
    val fallback = webView.resources.displayMetrics.density
    val scale = if (selection.factor > 0.01f) selection.factor else fallback

    val location = IntArray(2)
    webView.getLocationInWindow(location)
    val deltaX = location[0] - contentOriginInWindow.x
    val deltaY = location[1] - contentOriginInWindow.y

    val widthPx = if (contentSize.width > 0) contentSize.width.toFloat() else Float.MAX_VALUE
    val left = (selection.left * scale + deltaX).coerceIn(0f, widthPx)
    val right = (selection.right * scale + deltaX).coerceIn(0f, widthPx)
    val top = selection.top * scale + deltaY
    val bottom = selection.bottom * scale + deltaY

    // 块带顶边要留出工具条的位置：工具条被放在选区上方 gapPx 处
    val bandTop = (top - gapPx).coerceAtLeast(0f)
    val boxLeft = minOf(left, right)
    val boxRight = maxOf(left, right).coerceAtLeast(boxLeft + 2f)

    val box = OcrWordBox(
        id = "web-selection",
        bounds = Rect(
            left = boxLeft,
            top = (top - bandTop).coerceAtLeast(0f),
            right = boxRight,
            bottom = (bottom - bandTop).coerceAtLeast(0f)
        ),
        text = selection.text
    )

    val bandHeight = ((bottom - bandTop) + gapPx).coerceAtLeast(gapPx)
    return SelectionGeometry(
        box = box,
        bandTopPx = bandTop.toInt(),
        bandHeightPx = bandHeight.toInt()
    )
}

/** 一次选区的几何结果：包围盒（块带本地坐标）+ 块带的竖直落位与高度。 */
private data class SelectionGeometry(
    val box: OcrWordBox,
    val bandTopPx: Int,
    val bandHeightPx: Int
)

/** 地址栏显示用：空白页显示空串，而不是把 about:blank 甩给用户。 */
private fun displayUrl(url: String): String =
    if (url.isBlank() || url == BrowserJs.START_URL) "" else url

/** 站点域名：去 www. 前缀；取不到（about:/data:/空白页）返回空串。 */
private fun domainOf(url: String): String {
    if (url.isBlank()) return ""
    val host = runCatching { Uri.parse(url).host }.getOrNull() ?: return ""
    return host.removePrefix("www.")
}

/**
 * 地址栏输入的规整化：
 * 带 scheme 的原样用；含空格、或主机段没有点的（例如直接敲了关键词）
 * 走搜索；其余补 https://。
 */
private fun normalizeAddress(raw: String): String? {
    val text = raw.trim()
    if (text.isEmpty()) return null
    val lower = text.lowercase()
    if (lower.startsWith("http://") || lower.startsWith("https://") ||
        lower.startsWith("about:") || lower.startsWith("data:")
    ) {
        return text
    }
    // 包含空白字符必定是搜索关键词，直接调用必应搜索
    if (text.contains(' ')) return SEARCH_PREFIX + Uri.encode(text)

    val hostPart = text.substringBefore('/')
    // 必须符合标准域名特征（包含已知字母后缀如 .com/.cn）或 IP/localhost 才尝试访问网址，其余一律作为必应搜索
    val isDomain = hostPart.matches(Regex("^[a-zA-Z0-9-]+(\\.[a-zA-Z]{2,})+(?::\\d+)?$")) ||
        hostPart.matches(Regex("^\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}(?::\\d+)?$")) ||
        hostPart == "localhost"

    return if (isDomain) "https://$text" else SEARCH_PREFIX + Uri.encode(text)
}

/** 网页批注输入框：只有这里需要用户输入文字，其余交互全在网页本身。 */
@Composable
private fun NoteCommentDialog(
    quote: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var comment by remember(quote) { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("网页批注", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column {
                Text(
                    text = quote,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedTextField(
                    value = comment,
                    onValueChange = { comment = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("写点批注文字") },
                    minLines = 2
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(comment) }) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
