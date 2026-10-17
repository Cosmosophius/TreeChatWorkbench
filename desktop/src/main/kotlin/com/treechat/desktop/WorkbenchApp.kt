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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
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

    // 文件选择总线 → AWT FileDialog（SAF 桌面等价物）
    DisposableEffect(Unit) {
        val hook: (Long, ActivityResultContract<*, *>, Any?) -> Unit = { id, _, _ ->
            SwingUtilities.invokeLater {
                val dialog = FileDialog(null as java.awt.Frame?, "选择文件", FileDialog.LOAD)
                dialog.isMultipleMode = true
                dialog.isVisible = true
                val uris = dialog.files.map { android.net.Uri.fromFile(it) }
                ActivityResultBus.complete(id, uris)
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
                        // 当前模型快击菜单（M4 对齐完整 ProviderAndModelMenu）
                        Box {
                            IconButton(onClick = { showProviderMenu = !showProviderMenu }) {
                                Icon(Icons.Filled.MoreVert, contentDescription = "模型")
                            }
                            DropdownMenu(expanded = showProviderMenu, onDismissRequest = { showProviderMenu = false }) {
                                viewModel.savedProviders.forEach { provider ->
                                    DropdownMenuItem(
                                        text = { Text("${provider.name} · ${provider.selectedModelId ?: "未选模型"}") },
                                        onClick = {
                                            viewModel.selectProvider(provider.id)
                                            showProviderMenu = false
                                        },
                                    )
                                }
                            }
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
                    if (event.isCtrlPressed && event.key == Key.K && event.type == KeyEventType.KeyUp) {
                        true // 命令面板在 M4 接 CommandPaletteDialog，这里先占住快捷键
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
                WorkbenchViewMode.INK_NOTES -> PlaceholderPane("墨迹白板在 M4 接入")
                WorkbenchViewMode.SHELF -> BookshelfScreen(
                    viewModel = viewModel,
                    windowManager = windowManager,
                    openDocumentId = null,
                    onOpenDocumentConsumed = {},
                    isImmersive = false,
                    onImmersiveChange = {},
                )
                WorkbenchViewMode.DICTIONARY -> PlaceholderPane("词典版面在 M4 接入")
                WorkbenchViewMode.BROWSER -> PlaceholderPane("桌面浏览器在 M4 换独立窗口方案")
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
}

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
