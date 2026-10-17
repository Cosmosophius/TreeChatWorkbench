package com.treechat.workbench

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Title
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VerticalAlignBottom
import androidx.compose.material.icons.filled.VerticalAlignTop
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.painterResource
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp as lerpRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.google.gson.Gson
import com.treechat.workbench.data.model.ConversationNode
import com.treechat.workbench.data.prefs.AppSettings
import com.treechat.workbench.data.prefs.GraphLayoutStore
import com.treechat.workbench.data.prefs.InkCardSnapshot
import com.treechat.workbench.data.prefs.InkNoteBoard
import com.treechat.workbench.data.prefs.InkNoteStore
import com.treechat.workbench.data.prefs.ProviderStore
import com.treechat.workbench.export.ExportFormat
import com.treechat.workbench.export.InkNoteExporter
import com.treechat.workbench.export.MindMapBuilder
import com.treechat.workbench.export.MindMapExporter
import com.treechat.workbench.network.protocol.ProviderConfig
import com.treechat.workbench.ui.chat.ChatNodeViewModel
import com.treechat.workbench.ui.chat.NodeChatContent
import com.treechat.workbench.ui.dictionary.DictionaryScreen
import com.treechat.workbench.ui.explorer.FolderExplorer
import com.treechat.workbench.ui.notes.InkWorkbenchScreen
import com.treechat.workbench.ui.reader.BookshelfScreen
import com.treechat.workbench.ui.settings.SettingsDialog
import androidx.compose.material.icons.filled.Memory
import com.treechat.workbench.ui.theme.AccentNeonGreen
import com.treechat.workbench.ui.theme.DarkSurface
import com.treechat.workbench.ui.theme.TextMuted
import com.treechat.workbench.ui.theme.TextPrimary
import com.treechat.workbench.ui.theme.TreeChatTheme
import com.treechat.workbench.ui.tree.EdgeAutoScroller
import com.treechat.workbench.ui.tree.GraphViewState
import com.treechat.workbench.ui.tree.TreeTopologyCanvas
import com.treechat.workbench.ui.tree.computeGraphLayout
import com.treechat.workbench.etymology.ui.EtymoScopeBottomSheet
import com.treechat.workbench.ui.window.DockSide
import com.treechat.workbench.ui.window.FloatingWindowCard
import com.treechat.workbench.ui.window.FloatingWindowManager
import com.treechat.workbench.ui.window.WindowDockBar
import com.treechat.workbench.ui.window.WindowSpace
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Shield
import com.treechat.workbench.ui.browser.WebBrowserScreen
import com.treechat.workbench.ui.desktop.CommandPaletteDialog
import com.treechat.workbench.data.db.ConversationSearchRepository
import com.treechat.workbench.data.db.ConversationSummary
import com.treechat.workbench.data.db.SearchResults
import com.treechat.workbench.data.db.SearchScope
import com.treechat.workbench.data.db.SearchType
import com.treechat.workbench.ui.desktop.DesktopCommand
import com.treechat.workbench.util.AppEdition
import com.treechat.workbench.util.AppStrings
import com.treechat.workbench.safety.BranchSafetyAuditor
import com.treechat.workbench.export.ZipExporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {

    private val viewModel by viewModels<ChatNodeViewModel>()

    /**
     * 服务商配置仓库（多服务商并存）。
     *
     * 由 Activity 持有并负责读写磁盘，ViewModel 只持有内存中的列表 ——
     * 与 settings 的分工一致，避免出现两份不同步的配置。
     */
    private val providerStore by lazy { ProviderStore(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 恢复**全部**已保存的服务商，并选中上次用的那条。
        //
        // 旧版本只存一份配置（键 provider_config），ProviderStore 会自动把它
        // 迁进新列表，因此升级用户不需要重新填写密钥。
        run {
            val saved = providerStore.loadProviders()
            if (saved.isNotEmpty()) {
                val activeId = providerStore.loadActiveId()
                // 上次选中的那条若已被删掉，回落到列表第一条（而不是凭空造一个默认值）
                val active = saved.firstOrNull { it.id == activeId } ?: saved.first()
                viewModel.upsertProviders(saved, makeActive = active)
                // 迁移后的首次启动：把新格式落盘（幂等，重复运行无副作用）
                providerStore.saveProviders(saved, active.id)
            }
        }

        setContent {
            // 设置取自 ViewModel（单点持有），不要在这里再 new 一份
            val settings = viewModel.settings
            TreeChatTheme(
                themeMode = settings.themeMode,
                dynamicColor = settings.dynamicColor,
                palette = settings.themePalette,
                // 全局字号滑块：改这里会立即重组整个应用，无需重启
                fontScale = settings.fontScale
            ) {
                TreeChatMainScreen(
                    viewModel = viewModel,
                    settings = settings
                )
            }
        }
    }

    companion object {
        /** 与 ProviderStore 共用同一个 SharedPreferences 文件。 */
        const val PREFS_NAME = "treechat_prefs"
    }
}

enum class WorkbenchViewMode {
    /** 文件式管理器：文件夹 + 对话的组织视图。 */
    EXPLORER,
    /** 单个会话的线性对话。 */
    CHAT_WORKSPACE,
    /** 思维导图工作台。 */
    TOPOLOGY_TREE,
    /** 活体思维导图手写批注笔记。 */
    INK_NOTES,
    /** 统一书架：书籍/教材的导入、阅读与知识库（RAG）索引的统一入口。 */
    SHELF,
    /** 三语词典：查词 / 音韵词源 / 单词本收藏夹 / 例句。 */
    DICTIONARY,
    /** 内置轻量安全网页浏览器。 */
    BROWSER
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TreeChatMainScreen(
    viewModel: ChatNodeViewModel,
    settings: AppSettings
) {
    val windowManager = remember { FloatingWindowManager() }
    val graphState = remember { GraphViewState() }
    // 画布边缘自动平移：拖动卡片/小窗/在小窗里拖选时，指针贴到画布边缘
    // 就缓慢顺势挪动画布，越靠边越快（有上限，见 EdgeAutoScroller 的说明）。
    // 放在这里是为了让画布与画布内的小窗共用同一份状态。
    val edgeScroller = remember { EdgeAutoScroller() }
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    val density = LocalDensity.current
    val scheme = MaterialTheme.colorScheme
    // 画布布局持久化（卡片位置/尺寸，按会话分别保存）。
    // 没有它的话，进程被杀后用户摆好的思维导图位置全部丢失。
    val layoutStore = remember { GraphLayoutStore(context) }
    val inkNoteStore = remember { InkNoteStore(context) }
    val browserController = remember { com.treechat.workbench.ui.browser.BrowserController() }
    var inkBoards by remember { mutableStateOf(inkNoteStore.loadAllBoards()) }
    var activeInkBoardId by remember {
        mutableStateOf(inkNoteStore.getActiveBoardId() ?: inkBoards.firstOrNull()?.id)
    }

    val rootNodeId by viewModel.rootNodeId.collectAsState()
    // 命令面板（Ctrl+K）的混合结果来源：会话与书籍
    val paletteConversations by viewModel.conversations.collectAsState()
    // 书架数据的读取入口（真正的快照在命令面板打开时取，见下方 paletteBooks）。
    val paletteBookPool = remember {
        com.treechat.workbench.ui.reader.ReaderDocumentPool(context)
    }
    // 注意：这是**当前会话**的节点，不是全库的。命名上不要叫 allNodes，
    // 否则在引入多会话之后极易被误当成全局集合使用。
    val conversationNodes by viewModel.nodes.collectAsState()
    val activeConversation by viewModel.activeConversation.collectAsState()
    val provider = viewModel.activeProvider

    val initialMode = remember {
        val target = (context as? android.app.Activity)?.intent?.getStringExtra("TARGET_VIEW_MODE")
        // 兼容旧的外部跳转标识 "READER"（书架由原阅读器 Tab 改造而来）
        if (target == "SHELF" || target == "READER") {
            WorkbenchViewMode.SHELF
        } else {
            WorkbenchViewMode.CHAT_WORKSPACE
        }
    }
    var viewMode by remember { mutableStateOf(initialMode) }

    /**
     * 阅读器沉浸全屏的**唯一真相**。
     *
     * 由本层持有，向下传给 [BookshelfScreen]（只读 + 通过回调修改）。阅读器进入沉浸时
     * 整个应用级 TopAppBar 与药丸导航**完全不渲染**（不是压扁），系统状态栏/导航栏
     * 由阅读器侧一并隐藏；退出时全部恢复。
     *
     * 用 rememberSaveable：沉浸态下若因屏幕旋转（PDF 多列会自动转横屏）导致 Activity
     * 重建，沉浸状态不会莫名其妙地丢回顶栏。
     */
    var readerImmersive by rememberSaveable { mutableStateOf(false) }

    // 离开书架（阅读器所在的分区）时复位：避免在别的 Tab 上顶栏被"遗留在隐藏状态"。
    LaunchedEffect(viewMode) {
        if (viewMode != WorkbenchViewMode.SHELF) readerImmersive = false
    }

    fun refreshInkBoards(selectId: String? = null) {
        val loaded = inkNoteStore.loadAllBoards()
        inkBoards = loaded
        activeInkBoardId = selectId ?: inkNoteStore.getActiveBoardId() ?: loaded.firstOrNull()?.id
    }

    fun createSnapshotToInkNotes(
        posOverride: Map<String, Offset>? = null,
        sizeOverride: Map<String, Size>? = null
    ) {
        coroutineScope.launch {
            val nodesSnapshot = conversationNodes
            val summaries = viewModel.loadNodeSummaries(nodesSnapshot.map { it.id })
            val fallbackPositions = computeGraphLayout(nodesSnapshot)
            val defaultW = with(density) { 215.dp.toPx() }
            val defaultH = with(density) { 118.dp.toPx() }

            val cardSnapshots = nodesSnapshot.map { node ->
                val pos = posOverride?.get(node.id)
                    ?: graphState.positions[node.id]
                    ?: fallbackPositions[node.id]
                    ?: Offset.Zero
                val customSize = sizeOverride?.get(node.id) ?: graphState.nodeSizes[node.id]
                val summaryText = summaries[node.id]
                val autoH = if (!summaryText.isNullOrBlank()) defaultH * 1.45f else defaultH
                InkCardSnapshot(
                    id = node.id,
                    parentId = node.parentId,
                    title = node.title.ifBlank { "未命名分支" },
                    quote = node.originQuote,
                    summary = summaryText,
                    x = pos.x,
                    y = pos.y,
                    width = customSize?.width ?: defaultW,
                    height = customSize?.height ?: autoH
                )
            }

            val newBoard = InkNoteBoard(
                title = InkNoteStore.formatDefaultTitle(activeConversation?.title),
                sourceConversationId = activeConversation?.id,
                cards = cardSnapshots,
                strokes = emptyList()
            )
            inkNoteStore.saveBoard(newBoard)
            refreshInkBoards(selectId = newBoard.id)
            viewMode = WorkbenchViewMode.INK_NOTES
        }
    }

    fun exportInkNoteAndShare(board: InkNoteBoard, isPdf: Boolean) {
        coroutineScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val out = InkNoteExporter.outputFile(context, board, isPdf)
                    if (isPdf) InkNoteExporter.exportPdf(board, out)
                    else InkNoteExporter.exportPng(board, out)
                }
            }
            result.onSuccess { file ->
                val uri = runCatching {
                    FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        file
                    )
                }.getOrNull() ?: return@onSuccess
                val mime = if (isPdf) "application/pdf" else "image/png"
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = mime
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                runCatching {
                    context.startActivity(Intent.createChooser(intent, "导出/分享手写笔记"))
                }
            }
        }
    }
    var showSettings by remember { mutableStateOf(false) }
    // 三语词典（汉字 / 英语 / 俄语）搜词浮层
    var showEtymoScope by remember { mutableStateOf(false) }

    /**
     * 模型/服务商快切菜单是否展开。
     *
     * 注：原「本地知识库管理弹窗」入口已移除 —— 教材与书籍统一在顶部「书架」Tab 管理。
     */
    var modelMenuExpanded by remember { mutableStateOf(false) }

    /**
     * 工作台左侧文件夹栏是否展开。
     *
     * 默认**收起**（用户要求"完全隐藏，有个小箭头标签可以展开"）：
     * 收起时只留一条窄标签，工作台的可视面积不被吃掉。
     * 只在工作台视图里生效 —— 对话视图已有自己的会话上下文，再挂一条
     * 文件夹栏只会挤窄消息区。
     */
    var folderRailExpanded by remember { mutableStateOf(false) }

    /**
     * 工作台"画布全屏"：让思维导图画布占满**内容区**。
     *
     * 语义与对话界面小窗的全屏**完全一致**（用户明确要求"和对话一样的屏幕全屏"）：
     * 仍是内容区铺满，**不覆盖顶部药丸导航与顶栏** ——
     * 药丸是切换视图的常驻入口，盖掉它用户就回不去了。
     * 这里额外收起左侧文件夹栏，因为工作台里唯一挤占画布的就是它。
     *
     * 不持久化：这是一次性视图态，重开 App 应回到常态。
     */
    var canvasFullscreen by remember { mutableStateOf(false) }

    // 离开工作台视图就自动退出画布全屏：否则切到别的视图再切回来，
    // 会莫名其妙地"一进来就是全屏"（状态泄漏到不相关的视图上）。
    LaunchedEffect(viewMode) {
        if (viewMode != WorkbenchViewMode.TOPOLOGY_TREE) canvasFullscreen = false
    }

    /**
     * 点了引用卡片后要滚到的那条消息 id。
     *
     * 放在这一层而不是各窗口内部：跳转可能跨越分支甚至会话，
     * 只有顶层同时知道"目标消息"和"哪个窗口会显示它"。
     */
    var messageJumpTarget by remember { mutableStateOf<String?>(null) }

    /**
     * 沉浸阅读模式：把顶部区域收成一条细栏。
     *
     * 为什么要做：顶栏(TopAppBar) + 视图切换分段按钮两行加起来占掉相当一块
     * 竖向空间，在手机上读长回答时非常浪费。开启后顶部只剩一条 40dp 的自绘
     * 细栏（会话名 + 模型快切 + 展开按钮），分段按钮整行隐藏。
     *
     * 用自绘 Box 而不是 TopAppBar：M3 的 TopAppBar 有强制的最小高度与内边距，
     * 达不到"细栏"的效果。细栏里仍保留模型/服务商入口与设置，避免"收起来就
     * 什么都点不到"。
     */
    var immersiveMode by remember { mutableStateOf(false) }

    /** 是否显示导出对话框（由工作台的"导出"按钮触发）。 */
    var showExport by remember { mutableStateOf(false) }
    var showCommandPalette by remember { mutableStateOf(false) }

    // ---- 跨会话检索（Ctrl+F）----
    //
    // 范围与类型都由用户选，所以两者都是**状态**而不是固定值：
    // `searchScope` 决定"搜全部对话还是某一个根对话"，`searchType` 决定
    // "搜标题 / 搜消息 / 都搜"。两者任一变化都要立即重查。
    var searchQuery by remember { mutableStateOf("") }
    var searchScope by remember { mutableStateOf<SearchScope>(SearchScope.AllConversations) }
    var searchType by remember { mutableStateOf(SearchType.Both) }
    var searchResults by remember { mutableStateOf(SearchResults()) }
    var searchLoading by remember { mutableStateOf(false) }
    var searchConversations by remember { mutableStateOf<List<ConversationSummary>>(emptyList()) }

    // 检索跳转时携带的关键词：非空即表示"当前是从检索跳到原文的"，
    // 消息列表据此在正文里标出命中词、并显示「返回检索结果」条。
    var searchJumpKeyword by remember { mutableStateOf<String?>(null) }

    val searchRepository = remember { ConversationSearchRepository(viewModel.conversationDao) }

    // 打开面板时拉一次会话清单（范围选择器要用），而不是每次重组都查。
    LaunchedEffect(showCommandPalette) {
        if (showCommandPalette) {
            searchConversations = searchRepository.listConversations()
        }
    }

    // 查询词 / 范围 / 类型任一变化即重查。
    // 去抖 220ms：中文输入法逐字上屏时每敲一下都查会明显发涩，
    // 而 220ms 仍在"打完就出结果"的感知阈值内。
    //
    // ⚠️ 触发条件是 showCommandPalette 而不是独立的搜索面板：
    // 检索已并入命令面板，没有第二个入口。
    LaunchedEffect(showCommandPalette, searchQuery, searchScope, searchType) {
        if (!showCommandPalette) return@LaunchedEffect
        if (searchQuery.isBlank()) {
            searchResults = SearchResults()
            searchLoading = false
            return@LaunchedEffect
        }
        searchLoading = true
        kotlinx.coroutines.delay(220)
        searchResults = searchRepository.search(searchQuery, searchScope, searchType)
        searchLoading = false
    }

    /** 命令面板选中的书：交给书架直接打开，消费后清空。 */
    var pendingOpenDocumentId by remember { mutableStateOf<String?>(null) }

    // 浏览器是否已经至少打开过一次：首次进入 BROWSER 前不组合 WebView，
    // 之后该常驻覆盖层就一直持有 WebView，切 Tab 不再销毁。
    var browserEverOpened by remember { mutableStateOf(false) }
    LaunchedEffect(viewMode) {
        if (viewMode == WorkbenchViewMode.BROWSER) browserEverOpened = true
    }

    LaunchedEffect(density.density) {
        windowManager.densityScale = density.density
    }

    val activeModelName = run {
        val model = provider.availableModels.firstOrNull { it.id == provider.selectedModelId }
        val name = model?.name ?: provider.selectedModelId
        if (provider.id == "local" || provider.id.contains("local")) {
            "本地 · $name"
        } else {
            name
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown &&
                    (event.isCtrlPressed || event.isMetaPressed)
                ) {
                    when (event.key) {
                        // 桌面级命令面板
                        Key.K -> {
                            showCommandPalette = true
                            true
                        }
                        // 跨会话检索（标题 + 消息正文）。
                        // 选 F 而不是别的键：与阅读器里页内查找的肌肉记忆一致，
                        // 且 Ctrl+K 已被命令面板占用。
                        Key.F -> {
                            showCommandPalette = true
                            true
                        }
                        else -> false
                    }
                } else false
            }
            .onSizeChanged { windowManager.viewportSize = it }
    ) {
        Scaffold(
            containerColor = scheme.background,
            topBar = {
                // 阅读器进入沉浸时：整条应用级顶栏**完全不渲染**（而不是压扁成 40dp），
                // 整屏留给正文；退出沉浸后 readerImmersive 归位，自动恢复原样。
                if (!readerImmersive) {
                TopAppBar(
                // 会话视图沉浸（app 级）：把顶栏压到细条高度（Default 是 64dp，不够细）
                modifier = if (immersiveMode) Modifier.height(40.dp) else Modifier,
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "条畅",
                            style = MaterialTheme.typography.titleMedium,
                            fontFamily = FontFamily.Serif,
                            color = scheme.primary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        // 显示当前所在会话名，避免多会话时"不知道自己在哪"
                        Text(
                            text = activeConversation?.title ?: "${conversationNodes.size} 节点",
                            style = MaterialTheme.typography.labelMedium,
                            color = scheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.width(110.dp)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = scheme.surfaceContainer,
                    titleContentColor = scheme.onSurface
                ),
                actions = {
                    // 沉浸阅读开关：开启后顶栏压到 40dp、分段按钮行隐藏，
                    // 只留这条细栏上的会话名与模型入口。
                    IconButton(onClick = { immersiveMode = !immersiveMode }) {
                        Icon(
                            imageVector = if (immersiveMode) {
                                Icons.Default.VerticalAlignBottom
                            } else {
                                Icons.Default.VerticalAlignTop
                            },
                            contentDescription = if (immersiveMode) {
                                "退出沉浸阅读（恢复顶栏）"
                            } else {
                                "沉浸阅读（收起顶栏）"
                            },
                            tint = scheme.onSurfaceVariant
                        )
                    }
                    // 模型快切
                    Box {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(MaterialTheme.shapes.small)
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null
                                ) { modelMenuExpanded = true }
                                .padding(horizontal = 8.dp, vertical = 6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Memory,
                                contentDescription = null,
                                tint = scheme.primary,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(5.dp))
                            Text(
                                text = activeModelName,
                                style = MaterialTheme.typography.labelMedium,
                                color = scheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.width(80.dp)
                            )
                            Icon(
                                imageVector = Icons.Default.ArrowDropDown,
                                contentDescription = "切换模型",
                                tint = scheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        ProviderAndModelMenu(
                            expanded = modelMenuExpanded,
                            onDismiss = { modelMenuExpanded = false },
                            viewModel = viewModel,
                            provider = provider,
                            rootNodeId = rootNodeId,
                            onOpenSettings = {
                                modelMenuExpanded = false
                                showSettings = true
                            }
                        )
                    }

                    Spacer(modifier = Modifier.width(4.dp))

                    IconButton(onClick = { showCommandPalette = true }) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            // 命令与检索已合并为同一个面板，所以这里同时是两者入口。
                            // 旧文案只写"命令面板"，用户找不到"搜对话消息"就是因为它。
                            contentDescription = "命令与检索（搜命令 / 会话 / 书籍 / 分支标题 / 消息内容）",
                            tint = scheme.onSurfaceVariant
                        )
                    }

                    if (AppEdition.isDictionaryEnabled(context)) {
                        IconButton(onClick = { showEtymoScope = true }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_ip_chinese),
                                contentDescription = "三语词典：搜汉字 / 英语 / 俄语的音标与词源",
                                tint = scheme.onSurfaceVariant
                            )
                        }
                    }

                    IconButton(onClick = { showSettings = true }) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "设置",
                            tint = scheme.onSurfaceVariant
                        )
                    }
                }
            )
            }
        }
    ) { innerPadding ->
        // ⚠️ 把"顶栏下沿"告诉窗口层。
        //
        // 浮层小窗挂在根 Box 上（这样拖拽时能盖住顶栏），但**全屏/贴靠**时若也从
        // y=0 开始铺，就会把 Scaffold 顶栏连同标签行一起盖住 —— 用户切不了标签、
        // 回不到上一层（"放大之后返回不了"），窗口自己的标题栏也会被顶到状态栏下面。
        // innerPadding 的顶部就是顶栏下沿在根坐标系里的位置，交给管理器与卡片用作
        // 全屏起点与拖拽上界（见 FloatingWindowManager.topInsetPx）。
        SideEffect {
            windowManager.topInsetPx = with(density) { innerPadding.calculateTopPadding().toPx() }
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {

                // 极简紧凑型 Material 3 悬浮药丸导航胶囊 (高度直降至 34dp，彻底取消死板黑框，面积与质感质的飞跃)
                // 阅读器沉浸时整行药丸导航一并消失，把竖向空间全部让给正文。
                if (!immersiveMode && !readerImmersive) {
                    Surface(
                        color = Color.Transparent,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 3.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(34.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val visibleModes = remember(context) {
                                if (AppEdition.isDictionaryEnabled(context)) {
                                    WorkbenchViewMode.entries
                                } else {
                                    WorkbenchViewMode.entries.filter { it != WorkbenchViewMode.DICTIONARY }
                                }
                            }
                            visibleModes.forEach { mode ->
                                val selected = (viewMode == mode)
                                val pillBg = if (selected) {
                                    MaterialTheme.colorScheme.secondaryContainer
                                } else {
                                    Color.Transparent
                                }
                                val textColor = if (selected) {
                                    MaterialTheme.colorScheme.onSecondaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }

                                Row(
                                    modifier = Modifier
                                        .height(30.dp)
                                        .clip(RoundedCornerShape(15.dp))
                                        .background(pillBg)
                                        .clickable { viewMode = mode }
                                        .padding(horizontal = 9.dp, vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    Icon(
                                        imageVector = when (mode) {
                                            WorkbenchViewMode.EXPLORER -> Icons.Default.Folder
                                            WorkbenchViewMode.CHAT_WORKSPACE -> Icons.AutoMirrored.Filled.Chat
                                            WorkbenchViewMode.TOPOLOGY_TREE -> Icons.Default.AccountTree
                                            WorkbenchViewMode.INK_NOTES -> Icons.Default.Edit
                                            WorkbenchViewMode.SHELF -> Icons.AutoMirrored.Filled.MenuBook
                                            WorkbenchViewMode.DICTIONARY -> Icons.Outlined.Translate
                                            WorkbenchViewMode.BROWSER -> Icons.Outlined.Public
                                        },
                                        contentDescription = null,
                                        tint = textColor,
                                        modifier = Modifier.size(13.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = when (mode) {
                                            WorkbenchViewMode.EXPLORER -> AppStrings.get("tab_explorer", settings.appLanguage)
                                            WorkbenchViewMode.CHAT_WORKSPACE -> AppStrings.get("tab_chat", settings.appLanguage)
                                            WorkbenchViewMode.TOPOLOGY_TREE -> AppStrings.get("tab_tree", settings.appLanguage)
                                            WorkbenchViewMode.INK_NOTES -> AppStrings.get("tab_notes", settings.appLanguage)
                                            WorkbenchViewMode.SHELF -> AppStrings.get("tab_shelf", settings.appLanguage)
                                            WorkbenchViewMode.DICTIONARY -> AppStrings.get("tab_dictionary", settings.appLanguage)
                                            WorkbenchViewMode.BROWSER -> AppStrings.get("tab_browser", settings.appLanguage)
                                        },
                                        fontSize = 12.sp,
                                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                        color = textColor,
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }
                }

                // Shared Axis Z：把"进入工作台"表达为沿 Z 轴向内钻取
                // 内容区：AnimatedContent（各 Tab 的界面）与浏览器常驻层同处一层。
                //
                // 为什么把浏览器层放这里而不是根 Box：
                // 放根级会铺满整屏，把上方那条 Tab 药丸导航也盖住 —— 用户在浏览器里
                // 切不回别的 Tab，顶部的地址栏区域也跟应用顶栏重叠，看起来就像
                // "输入网址的地方不见了"。放在内容区后，地址栏正好在药丸下方。
                Box(modifier = Modifier.weight(1f)) {
                // ⚠️ 各 Tab 的界面状态必须靠它才能跨 Tab 存活。
                //
                // AnimatedContent 在切换动画结束后会把**离场的那棵子树移出组合**，
                // 于是里面所有 `remember` 一律失效；而 `rememberSaveable` 也**不会**
                // 自动生效 —— 它只在有 SaveableStateHolder 提供存档位时才能把值存下来。
                // 缺了这一层的后果正是用户报的那一串：
                //   · 文件夹页停在哪个目录 → 回根目录
                //   · 打开的书、读到第几页 → 退回书架列表
                //   · 列表滚动位置 → 回到顶部
                // 三个界面各写各的 rememberSaveable 都没用，缺的是这一个 holder。
                val tabStateHolder = rememberSaveableStateHolder()
                AnimatedContent(
                    targetState = viewMode,
                    transitionSpec = {
                        // 用 ordinal 判断"前进/后退"，而不是硬编码某一种模式，
                        // 这样以后增删视图模式不用再改这里
                        val forward = targetState.ordinal > initialState.ordinal
                        if (forward) {
                            (scaleIn(initialScale = 0.92f) + fadeIn(tween(220))) togetherWith
                                (scaleOut(targetScale = 1.08f) + fadeOut(tween(170)))
                        } else {
                            (scaleIn(initialScale = 1.08f) + fadeIn(tween(220))) togetherWith
                                (scaleOut(targetScale = 0.92f) + fadeOut(tween(170)))
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                    label = "viewTransition"
                ) { mode ->
                    tabStateHolder.SaveableStateProvider(mode.name) {
                    when (mode) {
                        WorkbenchViewMode.EXPLORER -> {
                            FolderExplorer(
                                viewModel = viewModel,
                                onOpenConversation = { conversationId ->
                                    viewModel.openConversation(conversationId)
                                    viewMode = WorkbenchViewMode.CHAT_WORKSPACE
                                },
                                onConversationCreated = {
                                    viewMode = WorkbenchViewMode.CHAT_WORKSPACE
                                }
                            )
                        }

                        WorkbenchViewMode.CHAT_WORKSPACE -> {
                            rootNodeId?.let { id ->
                                NodeChatContent(
                                    nodeId = id,
                                    viewModel = viewModel,
                                    jumpToMessageId = messageJumpTarget,
                                    jumpToKeyword = searchJumpKeyword,
                                    // 返回检索：把面板拉回前台即可，关键词/范围/类型都还在状态里，
                                    // 用户接着往下翻结果，不用重输一遍。
                                    onBackToSearch = { showCommandPalette = true },
                                    onSearchHighlightExpired = { searchJumpKeyword = null },
                                    onJumpConsumed = { messageJumpTarget = null },
                                    onForkBranch = { quote ->
                                        // 从主对话视图分叉：来源消息就是当前视图最后一条，
                                        // 记录它，引用卡片才能跳回来
                                        coroutineScope.launch {
                                            val originMessageId = viewModel
                                                .latestMessageIdOfNode(id)
                                            val child = viewModel.forkChildNode(
                                                parentNodeId = id,
                                                selectedQuote = quote,
                                                sourceMessageId = originMessageId
                                            )
                                            windowManager.openSubWindow(
                                                nodeId = child.id,
                                                title = child.title,
                                                originQuote = quote,
                                                sourceMessageId = originMessageId,
                                                depth = 1
                                            )
                                        }
                                    },
                                    onOpenQuoteWindow = { targetNode ->
                                        // 同文本超链接点击：如果该分支小窗已开过则置顶展开，否则新建小窗
                                        val existing = windowManager.windows.find { it.nodeId == targetNode.id }
                                        if (existing != null) {
                                            existing.isMinimized = false
                                            windowManager.bringToFront(existing.id)
                                        } else {
                                            windowManager.openSubWindow(
                                                nodeId = targetNode.id,
                                                title = targetNode.title,
                                                originQuote = targetNode.originQuote,
                                                sourceMessageId = targetNode.sourceMessageId,
                                                depth = 1
                                            )
                                        }
                                    }
                                )
                            } ?: run {
                                // 会话还在异步加载时给个占位，避免白屏
                                Box(modifier = Modifier.fillMaxSize())
                            }
                        }

                        WorkbenchViewMode.TOPOLOGY_TREE -> {
                            // 工作台 = 「左侧可收起文件夹栏」+「画布」。
                            //
                            // 栏位默认收起，只留一条窄标签（见 FolderRail）。
                            // 为什么放在 Row 里而不是叠在画布上：叠上去会遮住
                            // 画布左边缘的卡片与连线，而收起后又不该继续占位。
                            Row(modifier = Modifier.fillMaxSize()) {
                                // 画布全屏时收起文件夹栏：它是工作台里唯一挤占画布的部件。
                                // 注意是全屏才收起，常态下保持原有「可收起」行为不变。
                                if (!canvasFullscreen) {
                                    FolderRail(
                                        viewModel = viewModel,
                                        expanded = folderRailExpanded,
                                        onToggle = { folderRailExpanded = !folderRailExpanded },
                                        onOpenConversation = { conversationId ->
                                        // 从工作台点开一个会话：**只做后台切换，不离开工作台**。
                                        //
                                        // 用户要求："工作台点对话文件会直接跳转到对话，
                                        // 改为留在工作台，对话后台切换。"
                                        // 因此这里刻意**不**再设 viewMode —— 视图由顶部的
                                        // 分段按钮控制，点会话只切换"当前会话"这一个状态
                                        // （activeConversationId 由 openConversation 写入，
                                        // FolderRail 的行高亮读的就是它，列表会正确跟着变）。
                                        viewModel.openConversation(conversationId)
                                    }
                                    )
                                }

                                TreeTopologyCanvas(
                                    viewModel = viewModel,
                                    graphState = graphState,
                                    windowManager = windowManager,
                                    onNodeSelected = { node ->
                                        // 双击卡片：弹出为全局浮层（与工作台内的小窗是两回事）
                                        windowManager.openSubWindow(
                                            nodeId = node.id,
                                            title = node.title,
                                            originQuote = node.originQuote,
                                            sourceMessageId = node.sourceMessageId,
                                            depth = 1
                                        )
                                    },
                                    onQuoteTargetSelected = { targetNode ->
                        // 与主对话视图 / 浮层小窗**完全同一套逻辑**：
                        // 该分支的小窗已开就置顶展开，没开就新建。
                        //
                        // 三处必须一致 —— 只接其中两处，就会出现用户描述的
                        // "思维导图里开着的小窗点不开链接、关掉反而能点"这种
                        // 看起来毫无道理、实则只是漏接一条回调的现象。
                        val existing = windowManager.windows
                            .find { it.nodeId == targetNode.id }
                        if (existing != null) {
                            existing.isMinimized = false
                            windowManager.bringToFront(existing.id)
                        } else {
                            targetNode.conversationId?.let {
                                viewModel.openConversation(it)
                            }
                            windowManager.openSubWindow(
                                nodeId = targetNode.id,
                                title = targetNode.title,
                                originQuote = targetNode.originQuote,
                                sourceMessageId = targetNode.sourceMessageId,
                                depth = 1
                            )
                        }
                    },
                    onJumpToSourceMessage = { messageId ->
                                        coroutineScope.launch {
                                            // 1) 这段引用出自哪条消息 → 2) 它属于哪个分支 →
                                            // 3) 哪个会话；然后切会话、把那个分支的小窗开到前面，
                                            // 并让窗口内滚到那条消息
                                            val sourceNodeId = viewModel.nodeIdOfMessage(messageId)
                                            if (sourceNodeId != null) {
                                                val conversationId =
                                                    viewModel.conversationIdOfNode(sourceNodeId)
                                                if (conversationId != null &&
                                                    conversationId != viewModel.activeConversationId.value
                                                ) {
                                                    viewModel.openConversation(conversationId)
                                                }
                                                val existing = windowManager.windows
                                                    .firstOrNull { it.nodeId == sourceNodeId }
                                                if (existing != null) {
                                                    if (existing.isMinimized) {
                                                        windowManager.toggleMinimize(existing.id)
                                                    }
                                                    windowManager.bringToFront(existing.id)
                                                } else {
                                                    val target =
                                                        conversationNodes.firstOrNull { it.id == sourceNodeId }
                                                    windowManager.openSubWindow(
                                                        nodeId = sourceNodeId,
                                                        title = target?.title ?: "来源分支",
                                                        originQuote = target?.originQuote,
                                                        sourceMessageId = messageId,
                                                        depth = 1
                                                    )
                                                }
                                                messageJumpTarget = messageId
                                            }
                                        }
                                    },
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight(),
                                    layoutStore = layoutStore,
                                    edgeScroller = edgeScroller,
                                    canvasFullscreen = canvasFullscreen,
                                    onToggleCanvasFullscreen = {
                                        canvasFullscreen = !canvasFullscreen
                                        // 进全屏时顺带收起文件夹栏的"展开"意图记录，
                                        // 退出全屏后不会突然弹出一个展开的栏（保持退出前观感一致）
                                        if (canvasFullscreen) folderRailExpanded = false
                                    },
                                    onRequestExport = { showExport = true },
                                    onSnapshotToNotes = { posMap, sizeMap ->
                                        createSnapshotToInkNotes(posMap, sizeMap)
                                    }
                                )
                            }
                        }

                        WorkbenchViewMode.INK_NOTES -> {
                            InkWorkbenchScreen(
                                boards = inkBoards,
                                activeBoardId = activeInkBoardId,
                                onSelectBoard = { id ->
                                    inkNoteStore.setActiveBoardId(id)
                                    activeInkBoardId = id
                                },
                                onCreateSnapshotFromCurrentTree = {
                                    createSnapshotToInkNotes()
                                },
                                onSaveBoard = { updated ->
                                    inkNoteStore.saveBoard(updated)
                                    refreshInkBoards(selectId = updated.id)
                                },
                                onDuplicateBoard = { boardId ->
                                    val copy = inkNoteStore.duplicateBoard(boardId)
                                    if (copy != null) {
                                        refreshInkBoards(selectId = copy.id)
                                    }
                                },
                                onRenameBoard = { boardId, newTitle ->
                                    inkNoteStore.renameBoard(boardId, newTitle)
                                    refreshInkBoards(selectId = boardId)
                                },
                                onDeleteBoard = { boardId ->
                                    inkNoteStore.deleteBoard(boardId)
                                    refreshInkBoards()
                                },
                                onExportBoard = { board, isPdf ->
                                    exportInkNoteAndShare(board, isPdf)
                                },
                                modifier = Modifier.fillMaxSize()
                            )
                        }

                        WorkbenchViewMode.SHELF -> {
                            BookshelfScreen(
                                viewModel = viewModel,
                                windowManager = windowManager,
                                openDocumentId = pendingOpenDocumentId,
                                onOpenDocumentConsumed = { pendingOpenDocumentId = null },
                                // 沉浸全屏：单一真相在 MainActivity，阅读器只读 + 回调修改
                                isImmersive = readerImmersive,
                                onImmersiveChange = { readerImmersive = it },
                                modifier = Modifier.fillMaxSize()
                            )
                        }

                        WorkbenchViewMode.DICTIONARY -> {
                            DictionaryScreen(
                                viewModel = viewModel,
                                onAskWord = { title, question ->
                                    // 单词本同样按"一棵根对话树"组织：根以「单词本」命名，
                                    // 每个单词的提问是它下面的一个子节点。
                                    // 旧做法是每次新建一棵会话并挂到「单词」文件夹下，
                                    // 于是查词记录散成一批独立会话、彼此看不到。
                                    viewModel.createQuestionInSourceTree(
                                        sourceTitle = "单词本",
                                        question = question,
                                        originQuote = title,
                                        onCreated = { nodeId, _ ->
                                            windowManager.openSubWindow(
                                                nodeId = nodeId,
                                                title = "单词：$title",
                                                originQuote = title,
                                                depth = 1
                                            )
                                        }
                                    )
                                },
                                modifier = Modifier.fillMaxSize()
                            )
                        }

                        WorkbenchViewMode.BROWSER -> {
                            // 浏览器是内容区里的常驻覆盖层（见下方 Box）：
                            // 放进 AnimatedContent 会在切 Tab 时被移出组合树，
                            // WebView 宿主随之拆装，页面滚动位置与加载状态全部丢失。
                        }
                    }
                    }
                }

                    // ---------------- 浏览器：内容区内的常驻覆盖层 ----------------
                    // 只在 BROWSER 模式占位；切到其它模式时收成 **0 尺寸**，
                    // 组合树里的 WebView 宿主**永不卸载** —— 页面滚动位置、历史与加载状态全程保持。
                    //
                    // ⚠️ 这里**绝不能**用"铺满整屏 + 吞掉所有指针事件"来隐藏。
                    // 那种写法会让这一层在非浏览器模式下仍然覆盖整屏并把触摸全部吃掉，
                    // 结果是顶部 Tab 栏、药丸导航、所有界面统统点不动（真实发生过的回归）。
                    // 隐藏的正确姿势是"不占位"：尺寸归零后它既看不见也命中不到任何坐标。
                    //
                    // 位置很关键：它必须是**内容区**的覆盖层（药丸导航下方），不能放到根 Box。
                    // 放根级会盖住顶部那条 Tab 药丸，用户切不回别的 Tab，地址栏也会
                    // 与应用顶栏区域重叠，看起来就像"输入网址的地方不见了"。
                    Box(
                        modifier = if (viewMode == WorkbenchViewMode.BROWSER) {
                            Modifier.fillMaxSize()
                        } else {
                            Modifier.size(0.dp)
                        }
                    ) {
                        if (browserEverOpened) {
                            WebBrowserScreen(
                                viewModel = viewModel,
                                windowManager = windowManager,
                                controller = browserController,
                                isActive = viewMode == WorkbenchViewMode.BROWSER,
                                onExit = { viewMode = WorkbenchViewMode.EXPLORER },
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                }
            }
        }


        // ---------------- 浮层：吸附预览（Windows 式"要贴过去的影子"） ----------------
        //
        // 位置在窗口**之下**：它是提示，不该盖住正在被拖的窗口。
        // 只有拖动进入吸附感应区时才有值（见 FloatingWindowManager.updateSnapPreview）。
        //
        // ⚠️ 这里必须带**生长动画**（用户明确要求把这个动画加回来）：
        // 影子不是凭空闪现的一整块，而是从被拖窗口**当前**的位置与大小
        //（SnapPreview.from）扩大、平移到目标贴靠位（SnapPreview.rect）。
        // 这正是 Windows 拖到屏幕边缘时那块"影子长出来"的观感，
        // 让用户在看清楚"要贴到哪儿"之前不会误松手。
        windowManager.snapPreview?.let { preview ->
            // 以贴靠目标为 key：换了目标位（左半屏↔右半屏）就重播一次生长动画。
            key(preview.target) {
                var grown by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) { grown = true }
                val grow by animateFloatAsState(
                    targetValue = if (grown) 1f else 0f,
                    animationSpec = tween(durationMillis = 170),
                    label = "snapPreviewGrow"
                )
                // 在"窗口当前位置"与"目标贴靠位"之间插值 → 影子边长大边挪过去
                val rect = lerpRect(preview.from, preview.rect, grow)
                Box(
                    modifier = Modifier
                        .offset {
                            androidx.compose.ui.unit.IntOffset(
                                rect.left.roundToInt(),
                                rect.top.roundToInt()
                            )
                        }
                        .size(
                            width = with(density) { rect.width.toDp() },
                            height = with(density) { rect.height.toDp() }
                        )
                        .clip(MaterialTheme.shapes.large)
                        .background(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.16f * grow)
                        )
                        .border(
                            width = 1.5.dp,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.55f * grow),
                            shape = MaterialTheme.shapes.large
                        )
                )
            }
        }

        // ---------------- 浮层：小窗（移至最顶层，允许拖拽挪到顶栏上面并覆盖它） ----------------
        // ⚠️ 渲染 SCREEN 空间的窗，**外加处于真全屏的 GRAPH 空间窗**。
        //
        // GRAPH 空间的窗默认由 TreeTopologyCanvas 在画布内渲染（要与卡片共用
        // pan/scale）。这里若不过滤，同一个窗会被画两次 —— 用户看到的就是
        // "点一下卡片弹出两个小窗"。
        //
        // 例外正是真全屏：用户点绿点要的就是"和对话界面一样铺满整个屏幕"，
        // 而画布层做不到（它永远跟着画布缩放）。所以真全屏时把该窗**接管**到
        // 这一层渲染；画布层同一时刻也把它排除了（见 TreeTopologyView 的 graphWindows），
        // 因此不会出现重复绘制。画布全屏不在此列 —— 它的定义就是留在画布里。
        windowManager.windows.filter {
            it.space == WindowSpace.SCREEN ||
                (it.space == WindowSpace.GRAPH && it.isFullScreen)
        }.forEach { item ->
                key(item.id) {
                    FloatingWindowCard(
                        windowItem = item,
                        manager = windowManager,
                        onRename = { newTitle -> viewModel.renameNode(item.nodeId, newTitle) },
                        onDelete = {
                            viewModel.deleteNode(item.nodeId)
                            windowManager.closeWindow(item.id)
                        },
                        onQuoteClick = { sourceMessageId ->
                            if (sourceMessageId != null) {
                                coroutineScope.launch {
                                    val sourceNodeId = viewModel.nodeIdOfMessage(sourceMessageId)
                                    if (sourceNodeId != null && sourceNodeId != item.nodeId) {
                                        val conversationId =
                                            viewModel.conversationIdOfNode(sourceNodeId)
                                        if (conversationId != null &&
                                            conversationId != viewModel.activeConversationId.value
                                        ) {
                                            viewModel.openConversation(conversationId)
                                        }
                                        val target = conversationNodes
                                            .firstOrNull { it.id == sourceNodeId }
                                        val existing = windowManager.windows
                                            .firstOrNull { it.nodeId == sourceNodeId }
                                        if (existing != null) {
                                            if (existing.isMinimized) {
                                                windowManager.toggleMinimize(existing.id)
                                            }
                                            windowManager.bringToFront(existing.id)
                                        } else {
                                            windowManager.openSubWindow(
                                                nodeId = sourceNodeId,
                                                title = target?.title ?: "来源分支",
                                                originQuote = target?.originQuote,
                                                sourceMessageId = sourceMessageId,
                                                depth = item.depth
                                            )
                                        }
                                    }
                                    messageJumpTarget = sourceMessageId
                                }
                            }
                        }
                    ) {
                        NodeChatContent(
                            nodeId = item.nodeId,
                            viewModel = viewModel,
                            jumpToMessageId = messageJumpTarget,
                            // 与主视图同一份检索跳转状态：检索命中的分支既可能
                            // 恰好是主视图当前分支，也可能只有浮层小窗开着，
                            // 两条路径都要能定位到命中词并返回。
                            jumpToKeyword = searchJumpKeyword,
                            onBackToSearch = { showCommandPalette = true },
                            onSearchHighlightExpired = { searchJumpKeyword = null },
                            onJumpConsumed = { messageJumpTarget = null },
                            onForkBranch = { quote ->
                                coroutineScope.launch {
                                    val originMessageId = viewModel
                                        .latestMessageIdOfNode(item.nodeId)
                                    val child = viewModel.forkChildNode(
                                        parentNodeId = item.nodeId,
                                        selectedQuote = quote,
                                        sourceMessageId = originMessageId
                                    )
                                    windowManager.openSubWindow(
                                        nodeId = child.id,
                                        title = child.title,
                                        originQuote = quote,
                                        sourceMessageId = originMessageId,
                                        depth = item.depth + 1,
                                        spawnFromPx = item.offset
                                    )
                                }
                            },
                            onOpenQuoteWindow = { targetNode ->
                                val existing = windowManager.windows.find { it.nodeId == targetNode.id }
                                if (existing != null) {
                                    existing.isMinimized = false
                                    windowManager.bringToFront(existing.id)
                                } else {
                                    windowManager.openSubWindow(
                                        nodeId = targetNode.id,
                                        title = targetNode.title,
                                        originQuote = targetNode.originQuote,
                                        sourceMessageId = targetNode.sourceMessageId,
                                        depth = item.depth + 1,
                                        spawnFromPx = item.offset
                                    )
                                }
                            }
                        )
                    }
                }
            }

            // ---------------- 浮层：停靠坞 ----------------
            WindowDockBar(
                manager = windowManager,
                side = DockSide.LEFT,
                modifier = Modifier.align(Alignment.CenterStart)
            )
            WindowDockBar(
                manager = windowManager,
                side = DockSide.RIGHT,
                modifier = Modifier.align(Alignment.CenterEnd)
            )

            // ---------------- 浮层：导出思维导图 ----------------
            if (showExport) {
                ExportMindMapDialog(
                    onDismiss = { showExport = false },
                    title = activeConversation?.title ?: "对话思维导图",
                    nodes = conversationNodes
                )
            }

            // ---------------- 浮层：设置 ----------------
            // 本地大模型（NPU 硬件调度 / 挂载进度 / 推理详情）三块浮层已按用户要求整体移除。

            // ---------------- 浮层：三语词典搜词（汉字 / 英语 / 俄语） ----------------
            if (showEtymoScope) {
                EtymoScopeBottomSheet(
                    initialQuery = "",
                    onDismissRequest = { showEtymoScope = false }
                )
            }

            if (showSettings) {
                SettingsDialog(
                    initialConfig = viewModel.activeProvider,
                    settings = settings,
                    // 把**全部**已保存的服务商交给面板：面板内部可以在它们之间
                    // 切换、增删，保存时交回全集。只传一条就会重现"互相覆盖"。
                    savedProviders = viewModel.savedProviders.ifEmpty {
                        listOf(viewModel.activeProvider)
                    },
                    onDismiss = { showSettings = false },
                    onSave = { newConfig ->
                        // 合并进列表（按 id 覆盖那一条），并把它设为当前选中
                        viewModel.upsertProviders(listOf(newConfig), makeActive = newConfig)
                        showSettings = false
                        persistAllProviders(context, viewModel)
                    },
                    onSaveAll = { all, activeId ->
                        // 整体替换而不是合并：删除服务商必须走这条路，
                        // upsert 只做合并，被删掉的那条会在下次落盘时"复活"
                        // （落盘读的是 savedProviders 全集）。
                        viewModel.replaceProviders(all)
                        activeId?.let { viewModel.selectProvider(it) }
                        persistAllProviders(context, viewModel)
                    }
                )
            }

            /**
             * 跳到某个分支（可选：再滚到其中某条消息）。
             *
             * 供命令面板的检索结果调用；与"跳到来源消息"的既有链路走同一套
             * 切会话 / 开窗 / 定位逻辑，不会分叉出第二套实现。
             */
            suspend fun openNodeBySearch(nodeId: String, messageId: String?) {
                val conversationId = viewModel.conversationIdOfNode(nodeId)
                if (conversationId != null &&
                    conversationId != viewModel.activeConversationId.value
                ) {
                    viewModel.openConversation(conversationId)
                }
                val existing = windowManager.windows.firstOrNull { it.nodeId == nodeId }
                if (existing != null) {
                    if (existing.isMinimized) {
                        windowManager.toggleMinimize(existing.id)
                    }
                    windowManager.bringToFront(existing.id)
                } else {
                    val target = conversationNodes.firstOrNull { it.id == nodeId }
                    windowManager.openSubWindow(
                        nodeId = nodeId,
                        title = target?.title ?: "检索结果",
                        originQuote = target?.originQuote,
                        sourceMessageId = messageId,
                        depth = 1
                    )
                }
                if (messageId != null) messageJumpTarget = messageId
            }

            if (showCommandPalette) {
                // 每次打开面板时重新取一份书籍快照（导入新书后立刻可搜）。
                // 用数量当 remember 的 key：列表内容变了但数量没变时不必重建。
                val paletteBooks = remember(
                    showCommandPalette,
                    paletteBookPool.documents.size
                ) { paletteBookPool.documents.toList() }

                val commands = remember(
                    settings.appLanguage,
                    paletteConversations,
                    paletteBooks
                ) {
                    listOf(
                        DesktopCommand(
                            id = "cmd_new_conv",
                            title = "新建对话会话",
                            subtitle = "在当前目录建立全新的根节点分支",
                            category = "会话",
                            icon = Icons.Default.Add,
                            shortcutHint = "Ctrl+N"
                        ) {
                            viewModel.createConversation(null, "新会话")
                            viewMode = WorkbenchViewMode.CHAT_WORKSPACE
                        },
                        DesktopCommand(
                            id = "cmd_safety_audit",
                            title = "AI 敏感枝条全量安全审计",
                            subtitle = "扫描并防范国内主流模型（DeepSeek、MiniMax等）合规风控拦截",
                            category = "合规安全",
                            icon = Icons.Outlined.Shield,
                            shortcutHint = "Ctrl+Shift+S"
                        ) {
                            coroutineScope.launch {
                                val targetId = rootNodeId
                                if (targetId != null) {
                                    val msgs = viewModel.conversationDao.getMessagesForNode(targetId)
                                    val res = BranchSafetyAuditor.auditWithAi(viewModel.activeProvider, msgs)
                                    viewModel.setNodeSensitive(targetId, res.isSensitive)
                                }
                            }
                        },
                        DesktopCommand(
                            id = "cmd_decouple_sanitize",
                            title = "敏感枝条一键脱敏解耦分叉",
                            subtitle = "切断上游历史长违规上下文，提取技术骨干接力",
                            category = "合规安全",
                            icon = Icons.Default.Security
                        ) {
                            coroutineScope.launch {
                                val activeId = rootNodeId
                                if (activeId != null) {
                                    viewModel.forkSanitizedBranch(activeId)
                                }
                            }
                        },
                        DesktopCommand(
                            id = "cmd_export_zip",
                            title = "导出当前对话为 ZIP 归档",
                            subtitle = "打包 Markdown 与 .talktree 结构至压缩包并分享",
                            category = "导出与归档",
                            icon = Icons.Default.Archive,
                            shortcutHint = "Ctrl+E"
                        ) {
                            coroutineScope.launch {
                                val conv = viewModel.activeConversation.value
                                if (conv != null) {
                                    val zipFile = ZipExporter.exportToZip(
                                        context = context,
                                        conversationDao = viewModel.conversationDao,
                                        targetFolders = emptyList(),
                                        targetConversations = listOf(conv),
                                        zipNamePrefix = "TalkTree"
                                    )
                                    ZipExporter.shareFile(context, zipFile)
                                }
                            }
                        },
                        DesktopCommand(
                            id = "cmd_lang_zh_cn",
                            title = "界面语言：简体汉语",
                            subtitle = "切换界面为简体汉语",
                            category = "语言偏好",
                            icon = Icons.Outlined.Translate
                        ) {
                            settings.updateAppLanguage(AppStrings.LANG_ZH_CN)
                        },
                        DesktopCommand(
                            id = "cmd_lang_zh_tw",
                            title = "界面语言：繁体汉语",
                            subtitle = "切換介面為繁體漢語",
                            category = "语言偏好",
                            icon = Icons.Outlined.Translate
                        ) {
                            settings.updateAppLanguage(AppStrings.LANG_ZH_TW)
                        },
                        DesktopCommand(
                            id = "cmd_lang_en",
                            title = "Interface: English",
                            subtitle = "Switch app language to English",
                            category = "Language",
                            icon = Icons.Outlined.Translate
                        ) {
                            settings.updateAppLanguage(AppStrings.LANG_EN)
                        },
                        DesktopCommand(
                            id = "cmd_lang_ru",
                            title = "Язык: Русский",
                            subtitle = "Переключить интерфейс на русский",
                            category = "Language",
                            icon = Icons.Outlined.Translate
                        ) {
                            settings.updateAppLanguage(AppStrings.LANG_RU)
                        },
                        DesktopCommand(
                            id = "cmd_settings",
                            title = "打开应用设置",
                            subtitle = "配置大模型 API、外观、字号与偏好",
                            category = "系统",
                            icon = Icons.Default.Settings,
                            shortcutHint = "Ctrl+,"
                        ) {
                            showSettings = true
                        }
                    ) + paletteConversations.take(200).map { conv ->
                        // ---- 混合结果：会话（直接跳转） ----
                        DesktopCommand(
                            id = "conv_${conv.id}",
                            title = conv.title.ifBlank { "未命名对话" },
                            subtitle = "打开这个对话会话",
                            category = "会话",
                            icon = Icons.AutoMirrored.Filled.Chat
                        ) {
                            viewModel.openConversation(conv.id)
                            viewMode = WorkbenchViewMode.CHAT_WORKSPACE
                        }
                    } + paletteBooks.take(200).map { book ->
                        // ---- 混合结果：书籍（直接翻到这本书） ----
                        DesktopCommand(
                            id = "book_${book.id}",
                            title = book.title.ifBlank { "未命名书籍" },
                            category = "书架",
                            subtitle = "在书架中打开这本书",
                            icon = Icons.AutoMirrored.Filled.MenuBook
                        ) {
                            pendingOpenDocumentId = book.id
                            viewMode = WorkbenchViewMode.SHELF
                        }
                    } + searchResults.titles.map { hit ->
                        // ---- 混合结果：分支标题命中 ----
                        // 排在会话/书籍之后是因为标题命中数量通常远少于两者，
                        // 而用户要找分支时往往会先看到会话列表——真正的"找不到"
                        // 才会往下翻到这里。
                        DesktopCommand(
                            id = "srch_title_${hit.nodeId}",
                            title = hit.title.ifBlank { "（未命名分支）" },
                            subtitle = buildString {
                                append("分支标题")
                                when {
                                    hit.matchedWhole -> append(" · 完全匹配")
                                    hit.matchedPrefix -> append(" · 标题开头匹配")
                                }
                            },
                            category = "分支标题",
                            icon = Icons.Default.Title
                        ) {
                            searchJumpKeyword = null
                            coroutineScope.launch { openNodeBySearch(hit.nodeId, null) }
                        }
                    } + searchResults.messages.map { hit ->
                        // ---- 混合结果：消息正文命中 ----
                        DesktopCommand(
                            id = "srch_msg_${hit.messageId}",
                            title = hit.snippet,
                            subtitle = buildString {
                                append(if (hit.role == "user") "我" else "AI")
                                hit.nodeTitle?.take(16)?.takeIf { it.isNotBlank() }
                                    ?.let { append(" · "); append(it) }
                            },
                            category = "消息内容",
                            icon = Icons.AutoMirrored.Filled.Chat
                        ) {
                            // 记下检索词：消息列表据此在正文里标出命中处，
                            // 并显示「返回检索结果」条
                            searchJumpKeyword = searchQuery.trim().ifEmpty { null }
                            coroutineScope.launch { openNodeBySearch(hit.nodeId, hit.messageId) }
                        }
                    }
                }
                CommandPaletteDialog(
                    commands = commands,
                    onDismiss = { showCommandPalette = false },
                    // 检索与命令共用这一个面板，所以范围/类型筛选条一并挂上。
                    // 用户一旦开始打字，这两个筛选条就会出现（面板内按 query 非空判断）。
                    searchScope = searchScope,
                    onSearchScopeChange = { searchScope = it },
                    searchType = searchType,
                    onSearchTypeChange = { searchType = it },
                    searchConversations = searchConversations,
                    onQueryChange = { searchQuery = it }
                )
            }

        }
    }
}

/**
 * 工作台左侧的可收起文件夹栏。
 *
 * 形态按用户的要求："完全隐藏，有个小箭头标签可以展开"。
 *
 * - **收起**：只留一条 24dp 的窄标签，里面是一个箭头。它仍然可点，
 *   所以"完全隐藏"不等于"找不到入口"—— 这是刻意保留的，否则用户
 *   把栏收起来之后就再也没有可点的东西把它叫回来。
 * - **展开**：固定 210dp 的列表，列文件夹与其下的会话；点会话切过去。
 *
 * 为什么自己实现而不用现成的 NavigationDrawer：抽屉要 modal 遮罩与
 * 手势开启，而这里需要的是"常驻可切换的窄栏"，遮罩会挡住画布操作。
 */
@Composable
private fun FolderRail(
    viewModel: ChatNodeViewModel,
    expanded: Boolean,
    onToggle: () -> Unit,
    onOpenConversation: (String) -> Unit
) {
    val folders by viewModel.folders.collectAsState()
    val conversations by viewModel.conversations.collectAsState()
    val activeConversationId by viewModel.activeConversationId.collectAsState()

    if (!expanded) {
        // 收起态：一条窄标签。用 Box 铺满高度，让点按区域足够大（24dp 宽
        // 已经接近触控下限，再窄就点不准）。
        Box(
            modifier = Modifier
                .width(24.dp)
                .fillMaxHeight()
                .background(DarkSurface.copy(alpha = 0.92f))
                .clickable(onClick = onToggle),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = "展开文件夹栏",
                tint = AccentNeonGreen,
                modifier = Modifier.size(18.dp)
            )
        }
        return
    }

    Column(
        modifier = Modifier
            .width(210.dp)
            .fillMaxHeight()
            .background(DarkSurface.copy(alpha = 0.96f))
    ) {
        // 标题行：收起按钮
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "文件夹",
                color = TextPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                contentDescription = "收起文件夹栏",
                tint = AccentNeonGreen,
                modifier = Modifier
                    .size(18.dp)
                    .clickable(onClick = onToggle)
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 8.dp)
        ) {
            // 根目录下的会话（不属于任何文件夹）
            val rootConversations = conversations.filter { it.folderId == null }
            rootConversations.forEach { conversation ->
                FolderRailConversationRow(
                    title = conversation.title,
                    isActive = conversation.id == activeConversationId,
                    indentDp = 0,
                    onClick = { onOpenConversation(conversation.id) }
                )
            }

            // 每个文件夹一段，其后跟着它的会话
            folders.forEach { folder ->
                Text(
                    text = folder.name,
                    color = TextMuted,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 4.dp, top = 10.dp, bottom = 2.dp)
                )
                conversations
                    .filter { it.folderId == folder.id }
                    .forEach { conversation ->
                        FolderRailConversationRow(
                            title = conversation.title,
                            isActive = conversation.id == activeConversationId,
                            indentDp = 8,
                            onClick = { onOpenConversation(conversation.id) }
                        )
                    }
            }

            if (folders.isEmpty() && rootConversations.isEmpty()) {
                Text(
                    text = "还没有会话",
                    color = TextMuted,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(4.dp)
                )
            }
        }
    }
}

/** 文件夹栏里的一行会话。 */
@Composable
private fun FolderRailConversationRow(
    title: String,
    isActive: Boolean,
    indentDp: Int,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = indentDp.dp, top = 2.dp, bottom = 2.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(
                if (isActive) AccentNeonGreen.copy(alpha = 0.16f) else Color.Transparent
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title.ifBlank { "未命名会话" },
            color = if (isActive) AccentNeonGreen else TextPrimary,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * 顶栏的"服务商 + 模型"快切菜单。
 *
 * 为什么要有服务商这一段：用户明确要求"多个服务商可以列表切换"。
 * 之前只能进设置面板才能换服务商，多服务商并存在日常使用中等于没生效 ——
 * 切一次要开设置、翻列表、保存，代价太高。现在顶栏一处即可切换。
 *
 * 结构分三块，顺序刻意是"先选服务商、再选它的模型"：
 * 换服务商时，模型列表整体换掉，用户的心智顺序就是这个。
 */
@Composable
private fun ProviderAndModelMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    viewModel: ChatNodeViewModel,
    provider: ProviderConfig,
    rootNodeId: String? = null,
    onOpenSettings: () -> Unit
) {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val saved = viewModel.savedProviders
    var pendingSensitiveModel by remember { mutableStateOf<com.treechat.workbench.network.protocol.AiModelInfo?>(null) }

    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        // ---------- 服务商段 ----------
        Text(
            text = "服务商",
            style = MaterialTheme.typography.labelSmall,
            color = scheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        )
        if (saved.isEmpty()) {
            DropdownMenuItem(
                text = {
                    Text(
                        "尚无已保存的服务商",
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant
                    )
                },
                onClick = onOpenSettings
            )
        }
        saved.forEach { item ->
            val isCurrent = item.id == provider.id
            DropdownMenuItem(
                text = {
                    Text(
                        text = item.name.ifBlank { item.id },
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isCurrent) scheme.primary else scheme.onSurface
                    )
                },
                trailingIcon = {
                    if (isCurrent) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = null,
                            tint = scheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                },
                onClick = {
                    // 切换当前服务商：只改选中项，其余服务商原样保留
                    viewModel.selectProvider(item.id)
                    onDismiss()
                    persistAllProviders(context, viewModel)
                }
            )
        }

        HorizontalDivider()

        // ---------- 模型段（属于当前服务商） ----------
        Text(
            text = "模型 · ${provider.name.ifBlank { provider.id }}",
            style = MaterialTheme.typography.labelSmall,
            color = scheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        )
        if (provider.availableModels.isEmpty()) {
            DropdownMenuItem(
                text = {
                    Text(
                        "该服务商尚无模型，请先在设置中获取",
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant
                    )
                },
                onClick = onOpenSettings
            )
        }
        provider.availableModels.forEach { model ->
            val selected = model.id == provider.selectedModelId
            DropdownMenuItem(
                text = {
                    Text(
                        text = model.name,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (selected) scheme.primary else scheme.onSurface
                    )
                },
                trailingIcon = {
                    if (selected) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = null,
                            tint = scheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                },
                onClick = {
                    val isSensitive = rootNodeId != null && viewModel.isNodeSensitive(rootNodeId)
                    val isStrict = BranchSafetyAuditor.isStrictModerationModel(model.id, provider.id, provider.baseUrl)
                    if (isSensitive && isStrict) {
                        pendingSensitiveModel = model
                    } else {
                        val updated = provider.copy(selectedModelId = model.id)
                        viewModel.upsertProviders(listOf(updated), makeActive = updated)
                        onDismiss()
                        persistAllProviders(context, viewModel)
                    }
                }
            )
        }

        HorizontalDivider()

        DropdownMenuItem(
            text = {
                Text(
                    "管理服务商与模型…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.primary
                )
            },
            onClick = onOpenSettings
        )
    }

    pendingSensitiveModel?.let { targetModel ->
        AlertDialog(
            onDismissRequest = { pendingSensitiveModel = null },
            icon = {
                Icon(
                    imageVector = Icons.Outlined.Shield,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
            },
            title = {
                Text(
                    text = "敏感内容与国内模型合规提醒",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = "当前活跃枝条已被标记为包含合规敏感内容。\n\n您即将切换至【${BranchSafetyAuditor.getStrictModelProviderName(targetModel.id)}】(${targetModel.name})。\n直接发送该长上下文极易触发该服务商的内容安全风控拒答，甚至导致 API Key 被冻结。\n\n建议：使用「一键脱敏解耦分叉」创建干净分支，或切回海外通用模型。",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val updated = provider.copy(selectedModelId = targetModel.id)
                        viewModel.upsertProviders(listOf(updated), makeActive = updated)
                        pendingSensitiveModel = null
                        onDismiss()
                        persistAllProviders(context, viewModel)
                    }
                ) {
                    Text("了解风险，仍要切换", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingSensitiveModel = null }) {
                    Text("取消切换")
                }
            }
        )
    }
}

/**
 * 导出思维导图的对话框：选格式 → 生成 → 分享/打开。
 *
 * 为什么把导出放在**对话框**里而不是直接落盘：
 * 用户在真机上最需要的是"发给别人"或"存到能看见的地方"。应用私有目录里的文件
 * 用户是找不到的，所以这里导出后用 FileProvider 生成 content:// URI，
 * 交给系统的分享/查看动作 —— 用户自己选存到哪、发给谁。
 */
@Composable
private fun ExportMindMapDialog(
    onDismiss: () -> Unit,
    title: String,
    nodes: List<ConversationNode>
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    var format by remember { mutableStateOf(ExportFormat.PDF) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var exportedFile by remember { mutableStateOf<File?>(null) }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = {
            Text("导出思维导图", style = MaterialTheme.typography.titleMedium)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "把「$title」的 ${nodes.size} 个分支导出为文件。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                ExportFormat.entries.forEach { option ->
                    val selected = option == format
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (selected) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    Color.Transparent
                                }
                            )
                            .clickable(enabled = !busy) { format = option }
                            .padding(horizontal = 10.dp, vertical = 8.dp)
                    ) {
                        RadioButton(
                            selected = selected,
                            onClick = { if (!busy) format = option }
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(option.label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                message?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                exportedFile?.let { file ->
                    Text(
                        "已生成：${file.name}（${file.length() / 1024} KB）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        },
        confirmButton = {
            if (exportedFile == null) {
                TextButton(
                    enabled = !busy && nodes.isNotEmpty(),
                    onClick = {
                        busy = true
                        message = null
                        scope.launch {
                            // 导出是 IO + 绘制，放到后台线程，避免卡住 UI
                            val result = withContext(Dispatchers.IO) {
                                runCatching {
                                    val doc = MindMapBuilder.build(
                                        conversationTitle = title,
                                        nodes = nodes
                                    )
                                    val out = MindMapExporter.outputFile(context, doc, format)
                                    when (format) {
                                        ExportFormat.PDF -> MindMapExporter.exportPdf(
                                            context, doc, out, density.density
                                        )
                                        ExportFormat.PNG -> MindMapExporter.exportPng(
                                            context, doc, out, density.density
                                        )
                                        ExportFormat.MARKDOWN -> MindMapExporter.exportMarkdown(
                                            doc, out
                                        )
                                    }
                                }
                            }
                            busy = false
                            result.fold(
                                onSuccess = { file ->
                                    exportedFile = file
                                    message = "已导出，点「分享 / 打开」发给别人或保存。"
                                },
                                onFailure = { error ->
                                    message = "导出失败：${error.localizedMessage ?: "未知错误"}"
                                }
                            )
                        }
                    }
                ) {
                    Text(if (busy) "导出中…" else "导出")
                }
            } else {
                TextButton(onClick = {
                    val file = exportedFile ?: return@TextButton
                    // 用 FileProvider 生成 content:// URI：
                    // Android 7.0+ 直接传 file:// 会抛 FileUriExposedException
                    val uri = runCatching {
                        FileProvider.getUriForFile(
                            context,
                            "${context.packageName}.fileprovider",
                            file
                        )
                    }.getOrNull()
                    if (uri == null) {
                        message = "无法生成分享链接（FileProvider 未配置）"
                        return@TextButton
                    }
                    val mime = when (format) {
                        ExportFormat.PDF -> "application/pdf"
                        ExportFormat.PNG -> "image/png"
                        ExportFormat.MARKDOWN -> "text/markdown"
                    }
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = mime
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    runCatching {
                        context.startActivity(Intent.createChooser(intent, "分享思维导图"))
                    }.onFailure {
                        message = "没有可用于打开该格式的应用"
                    }
                }) {
                    Text("分享 / 打开")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text("关闭") }
        }
    )
}

/**
 * 把 ViewModel 里**全部**服务商与当前选中项落盘。
 *
 * 关键语义：**落盘的是全集，不是刚编辑的那一条**。
 * 旧实现直接 `putString(provider_config, json)` 覆盖单份配置，于是
 * "改服务商就把上一个遗忘了"。现在按 id 合并后整份写出，
 * 任何一次保存都不会丢掉其它服务商。
 *
 * 这是"保存"这件事的唯一出口：所有调用点都走它，避免多处各自写盘
 * 导致规则分叉（端点拼接曾经就是这么分叉的）。
 */
private fun persistAllProviders(context: Context, viewModel: ChatNodeViewModel) {
    ProviderStore(context).saveProviders(
        providers = viewModel.savedProviders,
        activeId = viewModel.activeProvider.id
    )
}
