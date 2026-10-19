// 桌面工作台壳（M3）：TreeChatMainScreen 的桌面宿主。
// 用户指令（2026-10-17）：浮层小窗不沿用安卓的常驻覆盖层，
// SCREEN 空间的每个小窗 == 一个真正的独立顶层 Window（Compose Desktop 多窗）。
// GRAPH 空间小窗仍画在思维树画布内（语义上是卡片的就地放大，不是独立窗）。
package com.treechat.desktop

import androidx.activity.compose.ActivityResultBus
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import com.treechat.workbench.data.model.ConversationNode
import com.treechat.workbench.data.prefs.AppSettings
import com.treechat.workbench.data.prefs.ProviderStore
import com.treechat.workbench.ui.chat.ChatNodeViewModel
import com.treechat.workbench.ui.chat.NodeChatContent
import com.treechat.workbench.ui.explorer.FolderExplorer
import com.treechat.workbench.ui.reader.BookshelfScreen
import com.treechat.workbench.ui.theme.TreeChatTheme
import com.treechat.workbench.ui.tree.GraphViewState
import com.treechat.workbench.ui.tree.TreeTopologyCanvas
import com.treechat.workbench.ui.window.FloatingWindowItem
import com.treechat.workbench.ui.window.FloatingWindowManager
import com.treechat.workbench.ui.window.WindowSpace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import javax.swing.SwingUtilities
import kotlinx.coroutines.launch

/** 工作台模式（与安卓 MainActivity.WorkbenchViewMode 同名同语义）。 */
enum class WorkbenchViewMode {
    EXPLORER, CHAT_WORKSPACE, TOPOLOGY_TREE, INK_NOTES, SHELF, DICTIONARY, BROWSER
}

/** Application 充面单例（AndroidViewModel 构造要 Application）。 */
class DesktopApplication : android.app.Application()

@Composable
fun DesktopWorkbenchApp(
    settings: AppSettings,
    env: DesktopAppEnv,
    onCloseRequest: () -> Unit,
) {
    val context = DesktopAppEnvHolder.appContext
    val appInstance = remember { DesktopApplication() }

    // Provider 冷启动（对齐 MainActivity 的迁移逻辑，幂等落盘）
    val providerStore = remember { ProviderStore(context) }
    val viewModel = remember {
        ChatNodeViewModel(appInstance).also { vm ->
            val saved = providerStore.loadProviders()
            val active = providerStore.loadActiveId()
            if (saved.isNotEmpty()) {
                vm.upsertProviders(saved, saved.firstOrNull { it.id == active })
            }
        }
    }

    val snackbarHostState = remember { SnackbarHostState() }
    val windowManager = remember { FloatingWindowManager() }
    val stateHolder = rememberSaveableStateHolder()

    // --seed-demo 时直接打开演示会话
    LaunchedEffect(Unit) {
        DesktopDemoState.latestConvId?.let { viewModel.openConversation(it) }
    }

    // 文件/目录选择总线 → AWT FileDialog（SAF 桌面等价物）
    DisposableEffect(Unit) {
        val hook: (Long, ActivityResultContract<*, *>, Any?) -> Unit = { id, contract, _ ->
            when (contract) {
                is ActivityResultContracts.OpenDocumentTree -> {
                    SwingUtilities.invokeLater {
                        val chooser = javax.swing.JFileChooser()
                        chooser.dialogTitle = "选择云盘文件夹（桌面版 = 普通文件夹）"
                        chooser.fileSelectionMode = javax.swing.JFileChooser.DIRECTORIES_ONLY
                        chooser.isMultiSelectionEnabled = false
                        val chosen = if (chooser.showOpenDialog(null) == javax.swing.JFileChooser.APPROVE_OPTION) {
                            chooser.selectedFile?.absolutePath?.let { path ->
                                android.net.Uri.fromFile(java.io.File(path))
                            }
                        } else {
                            null
                        }
                        ActivityResultBus.complete(id, chosen)
                    }
                }
                else -> {
                    SwingUtilities.invokeLater {
                        val dialog = FileDialog(null as java.awt.Frame?, "选择文件", FileDialog.LOAD)
                        dialog.isMultipleMode = true
                        dialog.isVisible = true
                        val uris = dialog.files.map { android.net.Uri.fromFile(it) }
                        ActivityResultBus.complete(id, uris)
                    }
                }
            }
        }
        ActivityResultBus.pendingHook = hook
        onDispose { ActivityResultBus.pendingHook = null }
    }

    // 主窗
    Window(
        onCloseRequest = onCloseRequest,
        title = "条畅（桌面预览）",
        state = rememberWindowState(width = 1280.dp, height = 830.dp),
    ) {
        TreeChatTheme(
            themeMode = settings.themeMode,
            dynamicColor = false,
            palette = settings.themePalette,
            fontScale = settings.fontScale,
        ) {
            DesktopMainScreen(
                viewModel = viewModel,
                settings = settings,
                providerStore = providerStore,
                windowManager = windowManager,
                snackbarHostState = snackbarHostState,
                stateHolder = stateHolder,
                onExitRequest = onCloseRequest,
            )
        }
    }

    // 独立顶层小窗（SCREEN 空间；GRAPH 空间窗留在画布里）
    val screenWindows = windowManager.windows.filter { it.space == WindowSpace.SCREEN }
    screenWindows.forEach { item ->
        androidx.compose.runtime.key(item.id) {
            DesktopFloatingWindow(
                item = item,
                manager = windowManager,
                viewModel = viewModel,
                settings = settings,
            )
        }
    }
}

/** 独立顶层小窗（SCREEN 空间）：内容 = NodeChatContent 或工具窗 customContent。 */
@Composable
private fun DesktopFloatingWindow(
    item: FloatingWindowItem,
    manager: FloatingWindowManager,
    viewModel: ChatNodeViewModel,
    settings: AppSettings,
) {
    Window(
        onCloseRequest = { manager.closeWindow(item.id) },
        title = item.title.ifBlank { "条畅小窗" },
        state = rememberWindowState(
            width = item.width,
            height = item.height,
            position = WindowPosition(item.offset.x.dp, item.offset.y.dp),
        ),
    ) {
        TreeChatTheme(
            themeMode = settings.themeMode,
            dynamicColor = false,
            palette = settings.themePalette,
            fontScale = settings.fontScale,
        ) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                val custom = item.customContent
                if (custom != null) {
                    custom()
                } else {
                    NodeChatContent(
                        nodeId = item.nodeId,
                        viewModel = viewModel,
                        onForkBranch = { quote ->
                            // 桌面 v1：分叉窗同样走 openSubWindow 独立顶层窗；
                            // 子节点由窗内用户显式输入驱动（M4 与安卓的 forkChildNode 语义完全对齐）。
                        },
                        onOpenQuoteWindow = { node ->
                            val existing = manager.windows.firstOrNull { it.nodeId == node.id }
                            if (existing != null) {
                                manager.bringToFront(existing.id)
                            } else {
                                manager.openSubWindow(
                                    nodeId = node.id,
                                    title = node.title,
                                    originQuote = node.originQuote,
                                    depth = item.depth + 1,
                                    spawnFromPx = item.offset,
                                )
                            }
                        },
                    )
                }
            }
        }
    }
}

/** 桌面主窗内容：药丸 Tab 导航 + 主体切换 + Provider 快击/设置。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DesktopMainScreen(
    viewModel: ChatNodeViewModel,
    settings: AppSettings,
    providerStore: ProviderStore,
    windowManager: FloatingWindowManager,
    snackbarHostState: SnackbarHostState,
    stateHolder: androidx.compose.runtime.saveable.SaveableStateHolder,
    onExitRequest: () -> Unit,
) {
    var viewMode by rememberSaveable { mutableStateOf(WorkbenchViewMode.CHAT_WORKSPACE) }
    var showPalette by remember { mutableStateOf(false) }
    var paletteQuery by remember { mutableStateOf<String?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var showProviderMenu by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf<String?>(null) }
    val graphState = remember { GraphViewState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(toast) {
        toast?.let { snackbarHostState.showSnackbar(it); toast = null }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("条畅") },
                    actions = {
                        // 当前模型快击菜单（对齐安卓 ProviderAndModelMenu 两段结构）
                        Box {
                            IconButton(onClick = { showProviderMenu = !showProviderMenu }) {
                                Icon(Icons.Filled.MoreVert, contentDescription = "模型")
                            }
                            DesktopProviderAndModelMenu(
                                expanded = showProviderMenu,
                                onDismiss = { showProviderMenu = false },
                                viewModel = viewModel,
                                provider = viewModel.activeProvider,
                                providerStore = providerStore,
                                onOpenSettings = { showSettings = true },
                            )
                        }
                        IconButton(onClick = { showSettings = true }) {
                            Icon(Icons.Filled.Settings, contentDescription = "设置")
                        }
                    },
                )
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    listOf(
                        WorkbenchViewMode.EXPLORER to "工作台",
                        WorkbenchViewMode.CHAT_WORKSPACE to "对话",
                        WorkbenchViewMode.TOPOLOGY_TREE to "导图",
                        WorkbenchViewMode.INK_NOTES to "墨迹",
                        WorkbenchViewMode.SHELF to "书架",
                        WorkbenchViewMode.DICTIONARY to "词典",
                        WorkbenchViewMode.BROWSER to "浏览",
                    ).forEach { (mode, label) ->
                        FilterChip(selected = viewMode == mode, onClick = { viewMode = mode }, label = { Text(label) })
                        Spacer(Modifier.width(8.dp))
                    }
                }
            }
        },
        floatingActionButton = {
            if (viewMode == WorkbenchViewMode.CHAT_WORKSPACE) {
                FloatingActionButton(onClick = {
                    viewModel.createConversation(folderId = null, title = "新对话")
                }) { Icon(Icons.Filled.Add, contentDescription = "新建对话") }
            }
        },
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .onPreviewKeyEvent { event ->
                    if (event.isCtrlPressed && event.type == KeyEventType.KeyUp) {
                        when (event.key) {
                            Key.K -> { showPalette = true; paletteQuery = null; true }
                            Key.F -> { showPalette = true; paletteQuery = ""; true }
                            else -> false
                        }
                    } else {
                        false
                    }
                },
        ) {
            when (viewMode) {
                WorkbenchViewMode.EXPLORER -> FolderExplorer(
                    viewModel = viewModel,
                    onOpenConversation = { viewMode = WorkbenchViewMode.CHAT_WORKSPACE },
                    onConversationCreated = { viewMode = WorkbenchViewMode.CHAT_WORKSPACE },
                )
                WorkbenchViewMode.CHAT_WORKSPACE -> {
                    val rootNodeId by viewModel.rootNodeId.collectAsState()
                    if (rootNodeId != null) {
                        stateHolder.SaveableStateProvider("chat-$rootNodeId") {
                            NodeChatContent(
                                nodeId = rootNodeId!!,
                                viewModel = viewModel,
                                onForkBranch = { quote ->
                                    windowManager.openSubWindow(
                                        nodeId = rootNodeId!!,
                                        title = quote.take(24),
                                        originQuote = quote,
                                        depth = 1,
                                    )
                                },
                                onOpenQuoteWindow = { node -> openQuoteWindow(windowManager, node, 1) },
                            )
                        }
                    } else {
                        Column(Modifier.fillMaxSize().padding(24.dp)) {
                            Text("还没有会话。点右下角「+」创建一个。", style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
                WorkbenchViewMode.TOPOLOGY_TREE -> TreeTopologyCanvas(
                    viewModel = viewModel,
                    graphState = graphState,
                    windowManager = windowManager,
                    onNodeSelected = { node ->
                        val existing = windowManager.windows.firstOrNull { it.nodeId == node.id }
                        if (existing != null) {
                            windowManager.bringToFront(existing.id)
                        } else {
                            windowManager.openSubWindow(
                                nodeId = node.id,
                                title = node.title,
                                originQuote = node.originQuote,
                                depth = 1,
                            )
                        }
                    },
                )
                WorkbenchViewMode.INK_NOTES -> DesktopInkWorkbench(
                    viewModel = viewModel,
                    graphState = graphState,
                    toast = { toast = it },
                )
                WorkbenchViewMode.SHELF -> BookshelfScreen(
                    viewModel = viewModel,
                    windowManager = windowManager,
                    openDocumentId = null,
                    onOpenDocumentConsumed = {},
                    isImmersive = false,
                    onImmersiveChange = {},
                )
                WorkbenchViewMode.DICTIONARY -> com.treechat.workbench.ui.dictionary.DictionaryScreen(
                    viewModel = viewModel,
                    onAskWord = { title, question ->
                        // 对齐安卓 MainActivity：单词本 = 一棵根对话树，每次查词是根下的子节点
                        viewModel.createQuestionInSourceTree(
                            sourceTitle = "单词本",
                            question = question,
                            originQuote = title,
                            onCreated = { nodeId, _ ->
                                windowManager.openSubWindow(
                                    nodeId = nodeId,
                                    title = "单词：$title",
                                    originQuote = title,
                                    depth = 1,
                                )
                            },
                        )
                    },
                )
                WorkbenchViewMode.BROWSER -> DesktopBrowserPane(
                    env = DesktopAppEnvHolder.env,
                    settings = settings,
                    toast = { toast = it },
                )
            }
        }
    }

    if (showSettings) {
        com.treechat.workbench.ui.settings.SettingsDialog(
            initialConfig = viewModel.activeProvider,
            settings = settings,
            savedProviders = viewModel.savedProviders,
            onDismiss = { showSettings = false },
            onSave = {},
            onSaveAll = { providers, activeId ->
                viewModel.replaceProviders(providers)
                providerStore.saveProviders(providers, activeId)
            },
        )
    }

    if (showPalette) {
        com.treechat.workbench.ui.desktop.CommandPaletteDialog(
            commands = desktopCommands(
                viewModel = viewModel,
                settings = settings,
                switchTo = { viewMode = it; showPalette = false },
                toast = { toast = it; showPalette = false },
            ),
            initialQuery = paletteQuery ?: "",
            onQueryChange = { paletteQuery = it },
            onDismiss = {
                showPalette = false
                paletteQuery = null
            },
        )
    }
}

/** 工作台全局命令清单（Ctrl/Cmd+K / Ctrl/Cmd+F）。 */
private fun desktopCommands(
    viewModel: ChatNodeViewModel,
    settings: AppSettings,
    switchTo: (WorkbenchViewMode) -> Unit,
    toast: (String) -> Unit,
): List<com.treechat.workbench.ui.desktop.DesktopCommand> = listOf(
    com.treechat.workbench.ui.desktop.DesktopCommand(
        id = "mode.chat",
        title = "去对话",
        subtitle = "单会话线性对话",
        category = "视图",
        icon = Icons.AutoMirrored.Filled.Chat,
        shortcutHint = null,
        onExecute = { switchTo(WorkbenchViewMode.CHAT_WORKSPACE) },
    ),
    com.treechat.workbench.ui.desktop.DesktopCommand(
        id = "mode.tree",
        title = "去导图",
        subtitle = "思维导图工作台",
        category = "视图",
        icon = Icons.Filled.AccountTree,
        onExecute = { switchTo(WorkbenchViewMode.TOPOLOGY_TREE) },
    ),
    com.treechat.workbench.ui.desktop.DesktopCommand(
        id = "mode.shelf",
        title = "去书架",
        subtitle = "导入、阅读与知识索引",
        category = "视图",
        icon = Icons.AutoMirrored.Filled.MenuBook,
        onExecute = { switchTo(WorkbenchViewMode.SHELF) },
    ),
    com.treechat.workbench.ui.desktop.DesktopCommand(
        id = "mode.explorer",
        title = "去工作台",
        subtitle = "文件夹 + 会话管理",
        category = "视图",
        icon = Icons.Filled.Folder,
        onExecute = { switchTo(WorkbenchViewMode.EXPLORER) },
    ),
    com.treechat.workbench.ui.desktop.DesktopCommand(
        id = "mode.dictionary",
        title = "去词典",
        subtitle = "三语查词与词源",
        category = "视图",
        icon = Icons.Outlined.Translate,
        onExecute = { switchTo(WorkbenchViewMode.DICTIONARY) },
    ),
    com.treechat.workbench.ui.desktop.DesktopCommand(
        id = "action.newConversation",
        title = "新建对话",
        subtitle = "在当前文件夹创建",
        category = "动作",
        icon = androidx.compose.material.icons.Icons.Filled.Add,
        onExecute = {
            viewModel.createConversation(folderId = null, title = "新对话")
            toast("已创建新对话")
        },
    ),
    com.treechat.workbench.ui.desktop.DesktopCommand(
        id = "action.settings",
        title = "设置",
        subtitle = "模型 / 主题 / 输入方式",
        category = "动作",
        icon = androidx.compose.material.icons.Icons.Filled.Settings,
        onExecute = { toast("请用右上角齿轮进入设置") },
    ),
)

private fun openQuoteWindow(manager: FloatingWindowManager, node: ConversationNode, depth: Int) {
    val existing = manager.windows.firstOrNull { it.nodeId == node.id }
    if (existing != null) {
        manager.bringToFront(existing.id)
    } else {
        manager.openSubWindow(
            nodeId = node.id,
            title = node.title,
            originQuote = node.originQuote,
            depth = depth,
        )
    }
}

@Composable
private fun PlaceholderPane(message: String) {
    Column(Modifier.fillMaxSize().padding(24.dp)) {
        Text(message, style = MaterialTheme.typography.titleMedium)
    }
}

/** 墨迹白板（桌面接线：boards 状态 + store 直连，快照/导出在 M5 收口）。 */
@Composable
private fun DesktopInkWorkbench(
    viewModel: ChatNodeViewModel,
    graphState: GraphViewState,
    toast: (String) -> Unit,
) {
    val context = DesktopAppEnvHolder.appContext
    val inkNoteStore = remember { com.treechat.workbench.data.prefs.InkNoteStore(context) }
    val scope = rememberCoroutineScope()
    var boards by remember { mutableStateOf(inkNoteStore.loadAllBoards()) }
    var activeInkBoardId by remember {
        mutableStateOf(inkNoteStore.getActiveBoardId() ?: inkNoteStore.loadAllBoards().firstOrNull()?.id)
    }

    fun refresh(selectId: String? = null) {
        val loaded = inkNoteStore.loadAllBoards()
        boards = loaded
        activeInkBoardId = selectId ?: inkNoteStore.getActiveBoardId() ?: loaded.firstOrNull()?.id
    }

    com.treechat.workbench.ui.notes.InkWorkbenchScreen(
        boards = boards,
        activeBoardId = activeInkBoardId,
        onSelectBoard = { id ->
            inkNoteStore.setActiveBoardId(id)
            refresh(selectId = id)
        },
        onCreateSnapshotFromCurrentTree = {
            // 照安卓 createSnapshotToInkNotes 语义：当前树拓扑 → 白板卡片快照
            scope.launch {
                runCatching {
                    val nodesSnapshot = viewModel.nodes.value
                    val summaries = viewModel.loadNodeSummaries(nodesSnapshot.map { it.id })
                    val fallbackPositions = com.treechat.workbench.ui.tree.computeGraphLayout(nodesSnapshot)
                    val defaultW = 430f
                    val defaultH = 236f
                    val cardSnapshots = nodesSnapshot.map { node ->
                        val pos = graphState.positions[node.id]
                            ?: fallbackPositions[node.id]
                            ?: androidx.compose.ui.geometry.Offset.Zero
                        val customSize = graphState.nodeSizes[node.id]
                        val summaryText = summaries[node.id]
                        val autoH = if (!summaryText.isNullOrBlank()) defaultH * 1.45f else defaultH
                        com.treechat.workbench.data.prefs.InkCardSnapshot(
                            id = node.id,
                            parentId = node.parentId,
                            title = node.title.ifBlank { "未命名分支" },
                            quote = node.originQuote,
                            summary = summaryText,
                            x = pos.x,
                            y = pos.y,
                            width = customSize?.width ?: defaultW,
                            height = customSize?.height ?: autoH,
                        )
                    }
                    val newBoard = com.treechat.workbench.data.prefs.InkNoteBoard(
                        title = com.treechat.workbench.data.prefs.InkNoteStore
                            .formatDefaultTitle(viewModel.activeConversation.value?.title),
                        sourceConversationId = viewModel.activeConversation.value?.id,
                        cards = cardSnapshots,
                        strokes = emptyList(),
                    )
                    inkNoteStore.saveBoard(newBoard)
                    boards = inkNoteStore.loadAllBoards()
                    activeInkBoardId = newBoard.id
                    toast("已把画布快照存进白板")
                }.onFailure { toast("快照失败：${it.message}") }
            }
        },
        onSaveBoard = { updated ->
            inkNoteStore.saveBoard(updated)
            refresh(selectId = updated.id)
        },
        onDuplicateBoard = { boardId ->
            val copy = inkNoteStore.duplicateBoard(boardId)
            if (copy != null) refresh(selectId = copy.id)
        },
        onRenameBoard = { boardId, newTitle ->
            inkNoteStore.renameBoard(boardId, newTitle)
            refresh(selectId = boardId)
        },
        onDeleteBoard = { boardId ->
            inkNoteStore.deleteBoard(boardId)
            refresh()
        },
        onExportBoard = { board, isPdf ->
            // 照安卓 exportInkNoteAndShare：导出文件 + 保存对话框（桌面链）
            scope.launch {
                runCatching {
                    withContext(Dispatchers.IO) {
                        val out = com.treechat.workbench.export.InkNoteExporter
                            .outputFile(DesktopAppEnvHolder.appContext, board, isPdf)
                        if (isPdf) {
                            com.treechat.workbench.export.InkNoteExporter.exportPdf(board, out)
                        } else {
                            com.treechat.workbench.export.InkNoteExporter.exportPng(board, out)
                        }
                    }
                }.onSuccess { file ->
                    com.treechat.workbench.export.ZipExporter.shareFile(
                        DesktopAppEnvHolder.appContext,
                        file,
                        mimeType = if (isPdf) "application/pdf" else "image/png",
                        title = "导出/分享手写笔记",
                    )
                }.onFailure { toast("导出失败：${it.message}") }
            }
        },
    )
}
