package com.treechat.workbench.ui.knowledge

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.treechat.workbench.data.model.KnowledgeChunk
import com.treechat.workbench.data.model.KnowledgeDocument
import com.treechat.workbench.rag.Chunker
import com.treechat.workbench.rag.KnowledgeRetriever
import com.treechat.workbench.rag.RagIndexingScheme
import com.treechat.workbench.ui.chat.ChatNodeViewModel
import com.treechat.workbench.ui.theme.AccentCyan
import com.treechat.workbench.ui.theme.AccentGold
import com.treechat.workbench.ui.theme.AccentNeonGreen
import com.treechat.workbench.ui.theme.AppBackground
import com.treechat.workbench.ui.theme.CardSurface
import com.treechat.workbench.ui.theme.DividerDark
import com.treechat.workbench.ui.theme.PanelSurface
import com.treechat.workbench.ui.theme.TextMuted
import com.treechat.workbench.ui.theme.TextPrimary
import com.treechat.workbench.ui.theme.TextSecondary
import com.treechat.workbench.task.BackgroundTaskRegistry
import java.util.Locale
import kotlinx.coroutines.launch

private const val PREFS_KNOWLEDGE_FOLDERS = "knowledge_folders"
private const val KEY_CUSTOM_FOLDER_PATHS = "custom_folder_paths"

/** 视图浏览模式：列表 vs 访达网格。 */
private enum class ExplorerViewMode {
    LIST,
    FINDER_GRID
}

/** 当前文件夹下的直接子文件夹摘要信息。 */
private data class FolderItemSummary(
    val name: String,
    val fullPath: String,
    val subFolderCount: Int,
    val documentCount: Int,
    val enabledDocumentCount: Int,
    val totalChunkCount: Int,
    val isEnabled: Boolean
)

/**
 * 本地知识库全屏文件夹与教材资源管理页（Material Design 3）。
 *
 * 设计特性：
 *  - 零 Emoji，纯 Material Design 3 矢量图标、Surface、Card、FilterChip 与层级排版；
 *  - 支持多级虚拟/持久化目录结构（SharedPreferences 持久化空目录 + 文档 folderPath 聚合）；
 *  - 支持面包屑导航、列表/访达网格双视图切换、文件夹级一键启用/停用、教材移动/重命名/删除；
 *  - 内置页内“切片原文查看器 (Chunk Inspector)”（支持关键词高亮检索）与“检索命中测试”实时预览。
 */
@Composable
fun KnowledgeManagerDialog(
    viewModel: ChatNodeViewModel,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val documents by viewModel.knowledgeDocuments.collectAsState()
    val busy by viewModel.knowledgeBusy.collectAsState()
    val message by viewModel.knowledgeMessage.collectAsState()
    val backgroundTasks by BackgroundTaskRegistry.tasks.collectAsState()

    // 全局知识库启用状态（读取 viewModel.settings.knowledgeEnabled 并支持动态切换）
    var globalKnowledgeEnabled by remember(viewModel.settings.knowledgeEnabled) {
        mutableStateOf(viewModel.settings.knowledgeEnabled)
    }

    // 用户创建的持久化文件夹集合（保证新建的空文件夹立即显示）
    var customFolders by remember {
        mutableStateOf(loadCustomFolders(context))
    }

    // 当前所在的文件夹路径（"" 表示根目录，例如 "语言学考研/乔姆斯基专题"）
    var currentFolderPath by remember { mutableStateOf("") }

    // 视图模式：列表 / 访达网格
    var viewMode by remember { mutableStateOf(ExplorerViewMode.FINDER_GRID) }

    // 当前打开查看切片的教材（非空时进入页内 Chunk Inspector 视图）
    var inspectingDocumentId by remember { mutableStateOf<String?>(null) }
    val inspectingDocument = remember(documents, inspectingDocumentId) {
        documents.find { it.id == inspectingDocumentId }
    }
    var inspectingChunks by remember { mutableStateOf<List<KnowledgeChunk>>(emptyList()) }
    var inspectingLoading by remember { mutableStateOf(false) }
    var chunkSearchQuery by remember { mutableStateOf("") }

    /** 每次删除切片后自增，用来重新拉取该教材的切片列表。 */
    var chunksRefreshKey by remember { mutableStateOf(0) }

    // 检索命中测试面板开关与状态
    var showRetrievalTestBar by remember { mutableStateOf(false) }
    var retrievalTestQuery by remember { mutableStateOf("") }
    var retrievalTestRunning by remember { mutableStateOf(false) }
    var retrievalTestResult by remember { mutableStateOf<KnowledgeRetriever.RetrievalResult?>(null) }
    var retrievalScopeCurrentFolderOnly by remember { mutableStateOf(false) }

    // 弹窗状态：新建文件夹、重命名教材、移动教材、移动文件夹、删除文件夹确认
    var showNewFolderDialog by remember { mutableStateOf(false) }
    var newFolderInput by remember { mutableStateOf("") }

    var renamingDocument by remember { mutableStateOf<KnowledgeDocument?>(null) }
    var renameDocInput by remember { mutableStateOf("") }

    var movingDocument by remember { mutableStateOf<KnowledgeDocument?>(null) }
    var moveDocTargetInput by remember { mutableStateOf("") }

    var movingFolder by remember { mutableStateOf<FolderItemSummary?>(null) }
    var moveFolderTargetInput by remember { mutableStateOf("") }

    var deletingFolder by remember { mutableStateOf<FolderItemSummary?>(null) }
    var deletingDocument by remember { mutableStateOf<KnowledgeDocument?>(null) }
    var showSchemeSettingsDialog by remember { mutableStateOf(false) }
    // 逐份文档「重建索引（选方案）」：非空表示正在为这份文档选择索引方案
    var rebuildSchemeDocument by remember { mutableStateOf<KnowledgeDocument?>(null) }
    // 后台任务面板（OCR / 建索引进度随时可查）
    var showBackgroundTasks by remember { mutableStateOf(false) }

    // 当进入某个教材的 Chunk Inspector 时，加载其全部切片
    LaunchedEffect(inspectingDocument?.id, chunksRefreshKey) {
        val doc = inspectingDocument
        if (doc == null) {
            inspectingChunks = emptyList()
            inspectingLoading = false
        } else {
            inspectingLoading = true
            inspectingChunks = runCatching {
                viewModel.getKnowledgeDocumentChunks(doc.id)
            }.getOrDefault(emptyList())
            inspectingLoading = false
        }
    }

    // 汇总所有已知文件夹路径（含父级路径前缀）
    val allKnownFolderPaths = remember(documents, customFolders) {
        buildAllFolderPaths(documents.map { it.folderPath }, customFolders)
    }

    // 当前目录下的直接子文件夹
    val childFolders = remember(currentFolderPath, allKnownFolderPaths, documents) {
        buildChildFolderSummaries(
            parentPath = currentFolderPath,
            allFolderPaths = allKnownFolderPaths,
            documents = documents
        )
    }

    // 当前目录下的直接教材文档
    val currentFolderDocuments = remember(currentFolderPath, documents) {
        val normCurrent = normalizeFolderPath(currentFolderPath)
        documents.filter { normalizeFolderPath(it.folderPath) == normCurrent }
    }

    // 多文件导入器（自动导入到 currentFolderPath）
    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        uris.forEach { uri ->
            val name = viewModel.resolveDisplayName(uri)
            viewModel.importKnowledgeDocument(uri, name, normalizeFolderPath(currentFolderPath))
        }
    }

    // 执行实时检索命中测试
    fun executeLiveRetrievalTest(queryText: String) {
        val q = queryText.trim()
        if (q.isEmpty()) {
            retrievalTestResult = null
            return
        }
        scope.launch {
            retrievalTestRunning = true
            val targetDocs = documents.filter { doc ->
                doc.enabled && (!retrievalScopeCurrentFolderOnly || isInsideOrSameFolder(doc.folderPath, currentFolderPath))
            }
            val docsWithChunks = targetDocs.mapNotNull { doc ->
                val chunks = runCatching { viewModel.getKnowledgeDocumentChunks(doc.id) }.getOrNull()
                if (chunks != null) doc to chunks else null
            }
            retrievalTestResult = KnowledgeRetriever.rankChunksInMemory(
                query = q,
                documentsWithChunks = docsWithChunks,
                topK = KnowledgeRetriever.DEFAULT_TOP_K
            )
            retrievalTestRunning = false
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = false
        )
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = AppBackground
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp, vertical = 16.dp)
            ) {
                // 1. 顶部应用栏
                ExplorerTopBar(
                    onOpenBackgroundTasks = { showBackgroundTasks = true },
                    runningTaskCount = backgroundTasks.count { it.isRunning },
                    totalDocs = documents.size,
                    enabledDocs = documents.count { it.enabled },
                    totalChunks = documents.sumOf { it.chunkCount },
                    globalEnabled = globalKnowledgeEnabled,
                    onGlobalEnabledChange = { enabled ->
                        globalKnowledgeEnabled = enabled
                        updateGlobalKnowledgeEnabled(viewModel, enabled)
                    },
                    showRetrievalTest = showRetrievalTestBar,
                    onToggleRetrievalTest = { showRetrievalTestBar = !showRetrievalTestBar },
                    onOpenSchemeSettings = { showSchemeSettingsDialog = true },
                    onDismiss = onDismiss
                )

                Spacer(modifier = Modifier.height(10.dp))

                // 2. 面包屑路径导航栏
                BreadcrumbNavigationBar(
                    currentFolderPath = currentFolderPath,
                    inspectingDocument = inspectingDocument,
                    onNavigateFolder = { targetPath ->
                        inspectingDocumentId = null
                        chunkSearchQuery = ""
                        currentFolderPath = normalizeFolderPath(targetPath)
                    },
                    onExitInspector = {
                        inspectingDocumentId = null
                        chunkSearchQuery = ""
                    }
                )

                // 3. 状态提示与导入进度条
                if (busy) {
                    Spacer(modifier = Modifier.height(8.dp))
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(3.dp),
                        color = AccentCyan,
                        trackColor = CardSurface
                    )
                }

                message?.let { msg ->
                    Spacer(modifier = Modifier.height(8.dp))
                    StatusMessageBanner(
                        message = msg,
                        onDismiss = { viewModel.clearKnowledgeMessage() }
                    )
                }

                // 4. 实时检索命中测试面板（可折叠）
                AnimatedVisibility(visible = showRetrievalTestBar) {
                    Column {
                        Spacer(modifier = Modifier.height(10.dp))
                        LiveRetrievalTestPanel(
                            query = retrievalTestQuery,
                            onQueryChange = { retrievalTestQuery = it },
                            onRunTest = { executeLiveRetrievalTest(retrievalTestQuery) },
                            isRunning = retrievalTestRunning,
                            result = retrievalTestResult,
                            currentFolderPath = currentFolderPath,
                            scopeCurrentFolderOnly = retrievalScopeCurrentFolderOnly,
                            onScopeChange = {
                                retrievalScopeCurrentFolderOnly = it
                                if (retrievalTestQuery.isNotBlank()) {
                                    executeLiveRetrievalTest(retrievalTestQuery)
                                }
                            },
                            onOpenEvidenceInInspector = { evidence ->
                                val targetDoc = documents.find { it.id == evidence.documentId }
                                if (targetDoc != null) {
                                    currentFolderPath = normalizeFolderPath(targetDoc.folderPath)
                                    inspectingDocumentId = targetDoc.id
                                    chunkSearchQuery = Chunker.extractKeyTerms(retrievalTestQuery)
                                        .firstOrNull()
                                        ?: retrievalTestQuery.trim()
                                }
                            }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // 5. 主体内容区：切片原文查看器 (Chunk Inspector) 或 文件夹与教材资源管理器
                if (inspectingDocument != null) {
                    ChunkInspectorView(
                        document = inspectingDocument,
                        chunks = inspectingChunks,
                        isLoading = inspectingLoading,
                        searchQuery = chunkSearchQuery,
                        onSearchQueryChange = { chunkSearchQuery = it },
                        onToggleDocumentEnabled = { enabled ->
                            viewModel.setKnowledgeDocumentEnabled(inspectingDocument.id, enabled)
                        },
                        onBackToFolder = {
                            inspectingDocumentId = null
                            chunkSearchQuery = ""
                        },
                        onDeleteChunk = { chunk ->
                            viewModel.deleteChunk(chunk.id, inspectingDocument.id)
                            chunksRefreshKey++
                        },
                        onClearAllChunks = {
                            viewModel.deleteDocumentIndex(inspectingDocument.id)
                            chunksRefreshKey++
                        },
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    )
                } else {
                    // 文件夹与文档操作栏
                    FolderDocumentActionBar(
                        currentFolderPath = currentFolderPath,
                        busy = busy,
                        viewMode = viewMode,
                        onViewModeChange = { viewMode = it },
                        onNewFolderClick = {
                            newFolderInput = ""
                            showNewFolderDialog = true
                        },
                        onImportClick = {
                            filePicker.launch(
                                arrayOf(
                                    "text/*",
                                    "application/pdf",
                                    "application/epub+zip",
                                    "application/json",
                                    "application/octet-stream",
                                    "*/*"
                                )
                            )
                        }
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    // 目录内容区
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(CardSurface)
                            .border(1.dp, DividerDark, RoundedCornerShape(12.dp))
                    ) {
                        if (childFolders.isEmpty() && currentFolderDocuments.isEmpty()) {
                            EmptyFolderPlaceholder(
                                currentFolderPath = currentFolderPath,
                                onNewFolder = {
                                    newFolderInput = ""
                                    showNewFolderDialog = true
                                },
                                onImportFiles = {
                                    filePicker.launch(arrayOf("*/*"))
                                }
                            )
                        } else if (viewMode == ExplorerViewMode.FINDER_GRID) {
                            FinderGridExplorerContent(
                                folders = childFolders,
                                documents = currentFolderDocuments,
                                onOpenFolder = { folder -> currentFolderPath = folder.fullPath },
                                onToggleFolderEnabled = { folder, enabled ->
                                    toggleFolderHierarchyEnabled(viewModel, documents, folder.fullPath, enabled)
                                },
                                onMoveFolder = { folder ->
                                    movingFolder = folder
                                    moveFolderTargetInput = parentFolderOf(folder.fullPath)
                                },
                                onDeleteFolder = { folder -> deletingFolder = folder },
                                onOpenDocumentInspector = { doc ->
                                    inspectingDocumentId = doc.id
                                    chunkSearchQuery = ""
                                },
                                onToggleDocumentEnabled = { doc, enabled ->
                                    viewModel.setKnowledgeDocumentEnabled(doc.id, enabled)
                                },
                                onRenameDocument = { doc ->
                                    renamingDocument = doc
                                    renameDocInput = doc.title
                                },
                                onMoveDocument = { doc ->
                                    movingDocument = doc
                                    moveDocTargetInput = doc.folderPath
                                },
                                onDeleteDocument = { doc ->
                                    deletingDocument = doc
                                },
                                onRebuildIndex = { doc -> rebuildSchemeDocument = doc }
                            )
                        } else {
                            ListExplorerContent(
                                folders = childFolders,
                                documents = currentFolderDocuments,
                                onOpenFolder = { folder -> currentFolderPath = folder.fullPath },
                                onToggleFolderEnabled = { folder, enabled ->
                                    toggleFolderHierarchyEnabled(viewModel, documents, folder.fullPath, enabled)
                                },
                                onMoveFolder = { folder ->
                                    movingFolder = folder
                                    moveFolderTargetInput = parentFolderOf(folder.fullPath)
                                },
                                onDeleteFolder = { folder -> deletingFolder = folder },
                                onOpenDocumentInspector = { doc ->
                                    inspectingDocumentId = doc.id
                                    chunkSearchQuery = ""
                                },
                                onToggleDocumentEnabled = { doc, enabled ->
                                    viewModel.setKnowledgeDocumentEnabled(doc.id, enabled)
                                },
                                onRenameDocument = { doc ->
                                    renamingDocument = doc
                                    renameDocInput = doc.title
                                },
                                onMoveDocument = { doc ->
                                    movingDocument = doc
                                    moveDocTargetInput = doc.folderPath
                                },
                                onDeleteDocument = { doc ->
                                    deletingDocument = doc
                                },
                                onRebuildIndex = { doc -> rebuildSchemeDocument = doc }
                            )
                        }
                    }
                }
            }
        }
    }

    // ---------------- 弹窗：新建文件夹 ----------------
    if (showNewFolderDialog) {
        NewFolderModalDialog(
            currentFolderPath = currentFolderPath,
            folderNameInput = newFolderInput,
            onFolderNameChange = { newFolderInput = it },
            onConfirm = {
                val cleanInput = normalizeFolderPath(newFolderInput)
                if (cleanInput.isNotEmpty()) {
                    val fullNewPath = if (currentFolderPath.isBlank()) {
                        cleanInput
                    } else {
                        "${normalizeFolderPath(currentFolderPath)}/$cleanInput"
                    }
                    customFolders = saveCustomFolder(context, customFolders, fullNewPath)
                }
                showNewFolderDialog = false
            },
            onDismiss = { showNewFolderDialog = false }
        )
    }

    // ---------------- 弹窗：重命名教材 ----------------
    renamingDocument?.let { doc ->
        RenameDocumentModalDialog(
            document = doc,
            titleInput = renameDocInput,
            onTitleChange = { renameDocInput = it },
            onConfirm = {
                val trimmed = renameDocInput.trim()
                if (trimmed.isNotEmpty()) {
                    viewModel.renameKnowledgeDocument(doc.id, trimmed)
                }
                renamingDocument = null
            },
            onDismiss = { renamingDocument = null }
        )
    }

    // ---------------- 弹窗：移动教材到文件夹 ----------------
    movingDocument?.let { doc ->
        MoveTargetFolderModalDialog(
            dialogTitle = "移动教材《${doc.title}》",
            currentTargetInput = moveDocTargetInput,
            availableFolders = allKnownFolderPaths,
            onTargetInputChange = { moveDocTargetInput = it },
            onConfirm = {
                val targetPath = normalizeFolderPath(moveDocTargetInput)
                if (targetPath.isNotEmpty()) {
                    customFolders = saveCustomFolder(context, customFolders, targetPath)
                }
                viewModel.moveKnowledgeDocument(doc.id, targetPath)
                movingDocument = null
            },
            onDismiss = { movingDocument = null }
        )
    }

    // ---------------- 弹窗：移动文件夹 ----------------
    movingFolder?.let { folder ->
        val selectableFolders = remember(allKnownFolderPaths, folder.fullPath) {
            allKnownFolderPaths.filterNot {
                it == folder.fullPath || it.startsWith("${folder.fullPath}/")
            }
        }
        MoveTargetFolderModalDialog(
            dialogTitle = "移动文件夹“${folder.name}”到目标父目录",
            currentTargetInput = moveFolderTargetInput,
            availableFolders = selectableFolders,
            onTargetInputChange = { moveFolderTargetInput = it },
            onConfirm = {
                val targetParent = normalizeFolderPath(moveFolderTargetInput)
                val newFolderFullPath = if (targetParent.isEmpty()) {
                    folder.name
                } else {
                    "$targetParent/${folder.name}"
                }
                if (newFolderFullPath != folder.fullPath &&
                    !newFolderFullPath.startsWith("${folder.fullPath}/")
                ) {
                    customFolders = moveCustomFolderPrefix(
                        context = context,
                        currentSet = customFolders,
                        oldPrefix = folder.fullPath,
                        newPrefix = newFolderFullPath
                    )
                    documents.forEach { doc ->
                        val docFolder = normalizeFolderPath(doc.folderPath)
                        if (docFolder == folder.fullPath) {
                            viewModel.moveKnowledgeDocument(doc.id, newFolderFullPath)
                        } else if (docFolder.startsWith("${folder.fullPath}/")) {
                            val suffix = docFolder.removePrefix("${folder.fullPath}/")
                            viewModel.moveKnowledgeDocument(doc.id, "$newFolderFullPath/$suffix")
                        }
                    }
                }
                movingFolder = null
            },
            onDismiss = { movingFolder = null }
        )
    }

    // ---------------- 弹窗：删除文件夹确认 ----------------
    deletingFolder?.let { folder ->
        DeleteFolderConfirmDialog(
            folder = folder,
            onDeleteFolderAndDocs = {
                customFolders = removeCustomFolderPrefix(context, customFolders, folder.fullPath)
                documents.filter { isInsideOrSameFolder(it.folderPath, folder.fullPath) }
                    .forEach { doc -> viewModel.deleteKnowledgeDocument(doc.id) }
                deletingFolder = null
            },
            onDeleteFolderMoveDocsToRoot = {
                customFolders = removeCustomFolderPrefix(context, customFolders, folder.fullPath)
                documents.filter { isInsideOrSameFolder(it.folderPath, folder.fullPath) }
                    .forEach { doc -> viewModel.moveKnowledgeDocument(doc.id, "") }
                deletingFolder = null
            },
            onDismiss = { deletingFolder = null }
        )
    }

    // ---------------- 弹窗：删除文档确认 ----------------
    deletingDocument?.let { doc ->
        AlertDialog(
            onDismissRequest = { deletingDocument = null },
            title = {
                Text(
                    text = "确认删除教材文档",
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp
                )
            },
            text = {
                Text(
                    text = "确定要删除《${doc.title}》吗？\n该操作将同时清理该书的全部 ${doc.chunkCount} 段切片和索引，删除后不可恢复。",
                    color = TextSecondary,
                    fontSize = 14.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val target = doc
                        deletingDocument = null
                        viewModel.deleteKnowledgeDocument(target.id, target.title)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE53935))
                ) {
                    Text("删除", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { deletingDocument = null }) {
                    Text("取消", color = TextSecondary)
                }
            },
            containerColor = PanelSurface
        )
    }

    // ---------------- 弹窗：RAG 双方案切换与云端 API 配置面板 ----------------
    if (showBackgroundTasks) {
        BackgroundTasksSheet(onDismiss = { showBackgroundTasks = false })
    }

    if (showSchemeSettingsDialog) {
        RagSchemeConfigDialog(
            viewModel = viewModel,
            onDismiss = { showSchemeSettingsDialog = false }
        )
    }

    // ---------------- 弹窗：逐份文档「重建索引（选方案）」 ----------------
    rebuildSchemeDocument?.let { doc ->
        RebuildIndexSchemeDialog(
            document = doc,
            busy = busy,
            cloudApiReady = viewModel.settings.cloudRagConfig().isValidForApi(),
            statusMessage = message,
            onConfirm = { scheme ->
                viewModel.rebuildDocumentIndex(doc.id, scheme, doc.title)
            },
            onDismiss = { rebuildSchemeDocument = null }
        )
    }
}

// ============================================================================
// 顶部栏、面包屑与操作栏组件
// ============================================================================

@Composable
private fun ExplorerTopBar(
    totalDocs: Int,
    enabledDocs: Int,
    totalChunks: Int,
    globalEnabled: Boolean,
    onGlobalEnabledChange: (Boolean) -> Unit,
    showRetrievalTest: Boolean,
    onToggleRetrievalTest: () -> Unit,
    onOpenSchemeSettings: () -> Unit,
    onOpenBackgroundTasks: () -> Unit,
    runningTaskCount: Int,
    onDismiss: () -> Unit
) {
    Surface(
        color = PanelSurface,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, DividerDark),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                color = AccentCyan.copy(alpha = 0.14f),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.size(40.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.FolderOpen,
                        contentDescription = null,
                        tint = AccentCyan,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "本地知识库文件夹管理",
                    color = TextPrimary,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "共 $totalDocs 册教材 · 已启用 $enabledDocs 册 · 累计 $totalChunks 段滑动窗口切片",
                    color = TextSecondary,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // 检索命中测试开关按钮
            OutlinedButton(
                onClick = onToggleRetrievalTest,
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, if (showRetrievalTest) AccentCyan else DividerDark),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = if (showRetrievalTest) AccentCyan.copy(alpha = 0.15f) else Color.Transparent,
                    contentColor = if (showRetrievalTest) AccentCyan else TextPrimary
                ),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = "检索测试",
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(text = "检索测试", fontSize = 12.sp, fontWeight = FontWeight.Medium)
            }

            Spacer(modifier = Modifier.width(10.dp))

            // RAG 方案与云端 API 索引设置入口
            OutlinedButton(
                onClick = onOpenSchemeSettings,
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, DividerDark),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = PanelSurface,
                    contentColor = TextPrimary
                ),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = "索引方案设置",
                    modifier = Modifier.size(16.dp),
                    tint = AccentGold
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(text = "方案/API", fontSize = 12.sp, fontWeight = FontWeight.Medium)
            }

            Spacer(modifier = Modifier.width(10.dp))

            // 后台任务入口：OCR / 切片建索引都可能跑很久，切走了也能回来查
            OutlinedButton(
                onClick = onOpenBackgroundTasks,
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, DividerDark),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = if (runningTaskCount > 0) AccentCyan.copy(alpha = 0.15f) else PanelSurface,
                    contentColor = TextPrimary
                ),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.HourglassEmpty,
                    contentDescription = "后台任务",
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

            Spacer(modifier = Modifier.width(12.dp))

            // 全局 RAG 开关
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

            Spacer(modifier = Modifier.width(8.dp))

            IconButton(onClick = onDismiss) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "关闭",
                    tint = TextSecondary
                )
            }
        }
    }
}

@Composable
private fun BreadcrumbNavigationBar(
    currentFolderPath: String,
    inspectingDocument: KnowledgeDocument?,
    onNavigateFolder: (String) -> Unit,
    onExitInspector: () -> Unit
) {
    val segments = remember(currentFolderPath) {
        normalizeFolderPath(currentFolderPath)
            .split('/')
            .filter { it.isNotBlank() }
    }

    Surface(
        color = CardSurface,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, DividerDark),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (currentFolderPath.isNotEmpty() || inspectingDocument != null) {
                Surface(
                    color = PanelSurface,
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable {
                            if (inspectingDocument != null) {
                                onExitInspector()
                            } else {
                                onNavigateFolder(parentFolderOf(currentFolderPath))
                            }
                        }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "返回上一级",
                            tint = AccentCyan,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(text = "上级", color = AccentCyan, fontSize = 12.sp)
                    }
                }
                Spacer(modifier = Modifier.width(8.dp))
            }

            // 根目录节点
            val isAtRoot = segments.isEmpty() && inspectingDocument == null
            BreadcrumbChip(
                label = "根目录",
                active = isAtRoot,
                onClick = { onNavigateFolder("") }
            )

            // 各级文件夹节点
            segments.forEachIndexed { idx, seg ->
                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = TextMuted,
                    modifier = Modifier
                        .padding(horizontal = 2.dp)
                        .size(16.dp)
                )
                val targetPath = segments.take(idx + 1).joinToString("/")
                val isCurrentFolder = idx == segments.lastIndex && inspectingDocument == null
                BreadcrumbChip(
                    label = seg,
                    active = isCurrentFolder,
                    onClick = { onNavigateFolder(targetPath) }
                )
            }

            // 当前正在查看切片的文档节点
            if (inspectingDocument != null) {
                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = TextMuted,
                    modifier = Modifier
                        .padding(horizontal = 2.dp)
                        .size(16.dp)
                )
                BreadcrumbChip(
                    label = "切片查看：${inspectingDocument.title}",
                    active = true,
                    onClick = {}
                )
            }
        }
    }
}

@Composable
private fun BreadcrumbChip(
    label: String,
    active: Boolean,
    onClick: () -> Unit
) {
    Surface(
        color = if (active) AccentCyan.copy(alpha = 0.16f) else Color.Transparent,
        shape = RoundedCornerShape(6.dp),
        border = if (active) BorderStroke(1.dp, AccentCyan.copy(alpha = 0.5f)) else null,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
    ) {
        Text(
            text = label,
            color = if (active) AccentCyan else TextSecondary,
            fontSize = 12.sp,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun FolderDocumentActionBar(
    currentFolderPath: String,
    busy: Boolean,
    viewMode: ExplorerViewMode,
    onViewModeChange: (ExplorerViewMode) -> Unit,
    onNewFolderClick: () -> Unit,
    onImportClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedButton(
                onClick = onNewFolderClick,
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, AccentGold.copy(alpha = 0.7f)),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentGold)
            ) {
                Icon(
                    imageVector = Icons.Default.CreateNewFolder,
                    contentDescription = "新建文件夹",
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(text = "新建文件夹", fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }

            Button(
                onClick = onImportClick,
                enabled = !busy,
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = AccentCyan,
                    contentColor = Color.Black
                )
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "导入教材到此目录",
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                val targetLabel = if (currentFolderPath.isBlank()) {
                    "导入教材到此目录（根目录）"
                } else {
                    "导入教材到此目录"
                }
                Text(
                    text = if (busy) "正在切块并建立索引…" else targetLabel,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        // 视图切换按钮组：列表 / 访达网格
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            FilterChip(
                selected = viewMode == ExplorerViewMode.LIST,
                onClick = { onViewModeChange(ExplorerViewMode.LIST) },
                label = { Text("列表", fontSize = 12.sp) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.ViewList,
                        contentDescription = "列表视图",
                        modifier = Modifier.size(16.dp)
                    )
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = AccentCyan.copy(alpha = 0.18f),
                    selectedLabelColor = AccentCyan,
                    selectedLeadingIconColor = AccentCyan
                )
            )
            FilterChip(
                selected = viewMode == ExplorerViewMode.FINDER_GRID,
                onClick = { onViewModeChange(ExplorerViewMode.FINDER_GRID) },
                label = { Text("访达网格", fontSize = 12.sp) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.GridView,
                        contentDescription = "访达网格视图",
                        modifier = Modifier.size(16.dp)
                    )
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = AccentCyan.copy(alpha = 0.18f),
                    selectedLabelColor = AccentCyan,
                    selectedLeadingIconColor = AccentCyan
                )
            )
        }
    }
}

// ============================================================================
// 文件夹与文档浏览视图（访达网格 & 列表）
// ============================================================================

@Composable
private fun FinderGridExplorerContent(
    folders: List<FolderItemSummary>,
    documents: List<KnowledgeDocument>,
    onOpenFolder: (FolderItemSummary) -> Unit,
    onToggleFolderEnabled: (FolderItemSummary, Boolean) -> Unit,
    onMoveFolder: (FolderItemSummary) -> Unit,
    onDeleteFolder: (FolderItemSummary) -> Unit,
    onOpenDocumentInspector: (KnowledgeDocument) -> Unit,
    onToggleDocumentEnabled: (KnowledgeDocument, Boolean) -> Unit,
    onRenameDocument: (KnowledgeDocument) -> Unit,
    onMoveDocument: (KnowledgeDocument) -> Unit,
    onDeleteDocument: (KnowledgeDocument) -> Unit,
    onRebuildIndex: (KnowledgeDocument) -> Unit
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 280.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (folders.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                SectionGroupHeader(
                    title = "子文件夹 (${folders.size})",
                    subtitle = "点击进入目录；切换文件夹开关可一键启用或停用该目录下的全部教材"
                )
            }
            gridItems(folders, key = { "folder_${it.fullPath}" }) { folder ->
                FinderFolderCard(
                    folder = folder,
                    onClick = { onOpenFolder(folder) },
                    onToggleEnabled = { enabled -> onToggleFolderEnabled(folder, enabled) },
                    onMove = { onMoveFolder(folder) },
                    onDelete = { onDeleteFolder(folder) }
                )
            }
        }

        if (documents.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                SectionGroupHeader(
                    title = "当前目录教材 (${documents.size})",
                    subtitle = "点击任一教材卡片可打开“切片原文查看器”检视全部段落与原文引述"
                )
            }
            gridItems(documents, key = { "doc_${it.id}" }) { doc ->
                FinderDocumentCard(
                    doc = doc,
                    onClick = { onOpenDocumentInspector(doc) },
                    onToggleEnabled = { enabled -> onToggleDocumentEnabled(doc, enabled) },
                    onInspectChunks = { onOpenDocumentInspector(doc) },
                    onRename = { onRenameDocument(doc) },
                    onMove = { onMoveDocument(doc) },
                    onDelete = { onDeleteDocument(doc) },
                    onRebuildIndex = { onRebuildIndex(doc) }
                )
            }
        }
    }
}

@Composable
private fun ListExplorerContent(
    folders: List<FolderItemSummary>,
    documents: List<KnowledgeDocument>,
    onOpenFolder: (FolderItemSummary) -> Unit,
    onToggleFolderEnabled: (FolderItemSummary, Boolean) -> Unit,
    onMoveFolder: (FolderItemSummary) -> Unit,
    onDeleteFolder: (FolderItemSummary) -> Unit,
    onOpenDocumentInspector: (KnowledgeDocument) -> Unit,
    onToggleDocumentEnabled: (KnowledgeDocument, Boolean) -> Unit,
    onRenameDocument: (KnowledgeDocument) -> Unit,
    onMoveDocument: (KnowledgeDocument) -> Unit,
    onDeleteDocument: (KnowledgeDocument) -> Unit,
    onRebuildIndex: (KnowledgeDocument) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (folders.isNotEmpty()) {
            item {
                SectionGroupHeader(
                    title = "子文件夹 (${folders.size})",
                    subtitle = "切换右侧开关可批量控制文件夹下全部教材的检索启用状态"
                )
            }
            items(folders, key = { "list_folder_${it.fullPath}" }) { folder ->
                FinderFolderCard(
                    folder = folder,
                    onClick = { onOpenFolder(folder) },
                    onToggleEnabled = { enabled -> onToggleFolderEnabled(folder, enabled) },
                    onMove = { onMoveFolder(folder) },
                    onDelete = { onDeleteFolder(folder) }
                )
            }
        }

        if (documents.isNotEmpty()) {
            item {
                Spacer(modifier = Modifier.height(4.dp))
                SectionGroupHeader(
                    title = "当前目录教材 (${documents.size})",
                    subtitle = "点击教材可打开切片原文查看器（含章节、字符区间与关键词高亮）"
                )
            }
            items(documents, key = { "list_doc_${it.id}" }) { doc ->
                FinderDocumentCard(
                    doc = doc,
                    onClick = { onOpenDocumentInspector(doc) },
                    onToggleEnabled = { enabled -> onToggleDocumentEnabled(doc, enabled) },
                    onInspectChunks = { onOpenDocumentInspector(doc) },
                    onRename = { onRenameDocument(doc) },
                    onMove = { onMoveDocument(doc) },
                    onDelete = { onDeleteDocument(doc) },
                    onRebuildIndex = { onRebuildIndex(doc) }
                )
            }
        }
    }
}

@Composable
private fun SectionGroupHeader(title: String, subtitle: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = title,
            color = TextPrimary,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = subtitle,
            color = TextMuted,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

// ============================================================================
// 文件夹项与教材文档项（M3 卡片 + 开关 + 更多菜单）
// ============================================================================

@Composable
private fun FinderFolderCard(
    folder: FolderItemSummary,
    onClick: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = PanelSurface),
        border = BorderStroke(
            width = 1.dp,
            color = if (folder.isEnabled && folder.documentCount > 0) {
                AccentGold.copy(alpha = 0.45f)
            } else {
                DividerDark
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                color = AccentGold.copy(alpha = 0.16f),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.size(42.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.Folder,
                        contentDescription = null,
                        tint = AccentGold,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = folder.name,
                    color = TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = buildString {
                        append("${folder.subFolderCount} 个子目录 · ${folder.documentCount} 册教材")
                        if (folder.documentCount > 0) {
                            append("（已启用 ${folder.enabledDocumentCount} 册 · ${folder.totalChunkCount} 段）")
                        }
                    },
                    color = TextSecondary,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // 文件夹级一键启用/停用 Switch
            Switch(
                checked = folder.isEnabled,
                onCheckedChange = onToggleEnabled,
                colors = SwitchDefaults.colors(checkedTrackColor = AccentNeonGreen)
            )

            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "文件夹操作",
                        tint = TextSecondary
                    )
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("打开文件夹") },
                        leadingIcon = {
                            Icon(Icons.Default.FolderOpen, contentDescription = null)
                        },
                        onClick = {
                            menuExpanded = false
                            onClick()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("移动到") },
                        leadingIcon = {
                            Icon(Icons.Default.DriveFileMove, contentDescription = null)
                        },
                        onClick = {
                            menuExpanded = false
                            onMove()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("删除文件夹", color = Color(0xFFE57373)) },
                        leadingIcon = {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = null,
                                tint = Color(0xFFE57373)
                            )
                        },
                        onClick = {
                            menuExpanded = false
                            onDelete()
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun FinderDocumentCard(
    doc: KnowledgeDocument,
    onClick: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onInspectChunks: () -> Unit,
    onRename: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit,
    onRebuildIndex: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val docIcon = if (doc.sourceType.equals("pdf", ignoreCase = true) ||
        doc.sourceType.equals("epub", ignoreCase = true)
    ) {
        Icons.Default.MenuBook
    } else {
        Icons.Default.Description
    }
    // 该文档当前使用的索引方案（无切片时如实标注"未建索引"，不冒充已建）
    val hasIndex = doc.chunkCount > 0
    val schemeBadge = if (hasIndex) schemeBadgeText(doc.indexingScheme) else "未建索引"
    val schemeBadgeColor = if (hasIndex) AccentCyan else TextMuted
    val schemeBadgeBg = if (hasIndex) AccentCyan.copy(alpha = 0.14f) else CardSurface

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = PanelSurface),
        border = BorderStroke(
            width = 1.dp,
            color = if (doc.enabled) AccentCyan.copy(alpha = 0.4f) else DividerDark
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                color = if (doc.enabled) AccentCyan.copy(alpha = 0.14f) else CardSurface,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.size(42.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = docIcon,
                        contentDescription = null,
                        tint = if (doc.enabled) AccentCyan else TextMuted,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = doc.title,
                    color = if (doc.enabled) TextPrimary else TextMuted,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // sourceType 徽标
                    Surface(
                        color = AccentGold.copy(alpha = 0.16f),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = doc.sourceType.uppercase(),
                            color = AccentGold,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                    Text(
                        text = "${doc.chunkCount} 段切片 · ${formatChars(doc.charCount)}",
                        color = TextSecondary,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    // 当前索引方案徽标（逐份文档选择方案的可见反馈）
                    Surface(
                        color = schemeBadgeBg,
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = schemeBadge,
                            color = schemeBadgeColor,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            Switch(
                checked = doc.enabled,
                onCheckedChange = onToggleEnabled,
                colors = SwitchDefaults.colors(checkedTrackColor = AccentNeonGreen)
            )

            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "教材操作菜单",
                        tint = TextSecondary
                    )
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("查看原文切片") },
                        leadingIcon = {
                            Icon(Icons.Default.Visibility, contentDescription = null)
                        },
                        onClick = {
                            menuExpanded = false
                            onInspectChunks()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("重建索引（选方案）") },
                        leadingIcon = {
                            Icon(Icons.Default.Refresh, contentDescription = null)
                        },
                        onClick = {
                            menuExpanded = false
                            onRebuildIndex()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("重命名") },
                        leadingIcon = {
                            Icon(Icons.Default.Edit, contentDescription = null)
                        },
                        onClick = {
                            menuExpanded = false
                            onRename()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("移动到文件夹…") },
                        leadingIcon = {
                            Icon(Icons.Default.DriveFileMove, contentDescription = null)
                        },
                        onClick = {
                            menuExpanded = false
                            onMove()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("删除", color = Color(0xFFE57373)) },
                        leadingIcon = {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = null,
                                tint = Color(0xFFE57373)
                            )
                        },
                        onClick = {
                            menuExpanded = false
                            onDelete()
                        }
                    )
                }
            }
        }
    }
}

// ============================================================================
// 页内切片原文查看器 (Chunk Inspector)
// ============================================================================

@Composable
internal fun ChunkInspectorView(
    document: KnowledgeDocument,
    chunks: List<KnowledgeChunk>,
    isLoading: Boolean,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onToggleDocumentEnabled: (Boolean) -> Unit,
    onBackToFolder: () -> Unit,
    onDeleteChunk: (KnowledgeChunk) -> Unit,
    onClearAllChunks: () -> Unit,
    modifier: Modifier = Modifier
) {
    val filteredChunks = remember(chunks, searchQuery) {
        val q = searchQuery.trim()
        if (q.isEmpty()) {
            chunks
        } else {
            chunks.filter { chunk ->
                chunk.text.contains(q, ignoreCase = true) ||
                    (chunk.section?.contains(q, ignoreCase = true) == true)
            }
        }
    }

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(CardSurface)
            .border(1.dp, DividerDark, RoundedCornerShape(12.dp))
            .padding(14.dp)
    ) {
        // 顶部教材信息栏
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(
                onClick = onBackToFolder,
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.ArrowBack,
                    contentDescription = "返回目录",
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("返回目录", fontSize = 12.sp)
            }

            Spacer(modifier = Modifier.width(12.dp))

            Icon(
                imageVector = Icons.Default.MenuBook,
                contentDescription = null,
                tint = AccentCyan,
                modifier = Modifier.size(22.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "切片原文查看器 · 《${document.title}》",
                    color = TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val folderDisplay = document.folderPath.ifBlank { "根目录" }
                Text(
                    text = "所属目录：$folderDisplay · 格式：${document.sourceType.uppercase()} · 共 ${chunks.size} 段（含 ~60 字滑动窗口重叠）· ${formatChars(document.charCount)}",
                    color = TextSecondary,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (document.enabled) "参与检索" else "已停用",
                    color = if (document.enabled) AccentNeonGreen else TextMuted,
                    fontSize = 12.sp
                )
                Spacer(modifier = Modifier.width(6.dp))
                Switch(
                    checked = document.enabled,
                    onCheckedChange = onToggleDocumentEnabled,
                    colors = SwitchDefaults.colors(checkedTrackColor = AccentNeonGreen)
                )
                Spacer(modifier = Modifier.width(10.dp))
                // 清空该书的全部切片与索引，文档本身（原文）保留。
                OutlinedButton(
                    onClick = onClearAllChunks,
                    enabled = chunks.isNotEmpty(),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.DeleteSweep,
                        contentDescription = null,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("清除全部切片", fontSize = 12.sp)
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // 文档内原文关键词搜索栏（支持实时过滤与原文高亮）
        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchQueryChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = {
                Text(
                    text = "在《${document.title}》的全部切片中搜索原文关键词（自动高亮匹配文字）…",
                    fontSize = 12.sp,
                    color = TextMuted
                )
            },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = "搜索切片原文",
                    tint = AccentCyan
                )
            },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "匹配 ${filteredChunks.size}/${chunks.size} 段",
                            color = AccentGold,
                            fontSize = 11.sp
                        )
                        IconButton(onClick = { onSearchQueryChange("") }) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "清空搜索",
                                tint = TextSecondary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            },
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = AccentCyan,
                unfocusedBorderColor = DividerDark,
                focusedTextColor = TextPrimary,
                unfocusedTextColor = TextPrimary
            ),
            shape = RoundedCornerShape(10.dp)
        )

        Spacer(modifier = Modifier.height(10.dp))

        if (isLoading) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(text = "正在读取教材原文切片…", color = TextSecondary, fontSize = 13.sp)
            }
        } else if (filteredChunks.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (searchQuery.isBlank()) "该教材暂无切片数据" else "未找到包含“$searchQuery”的原文切片",
                    color = TextMuted,
                    fontSize = 13.sp
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(filteredChunks, key = { it.id }) { chunk ->
                    ChunkInspectorCard(
                        documentTitle = document.title,
                        chunk = chunk,
                        highlightQuery = searchQuery,
                        onDelete = { onDeleteChunk(chunk) }
                    )
                }
            }
        }
    }
}

@Composable
private fun ChunkInspectorCard(
    documentTitle: String,
    chunk: KnowledgeChunk,
    highlightQuery: String,
    onDelete: () -> Unit
) {
    val charEnd = chunk.charStart + chunk.text.length
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = PanelSurface),
        border = BorderStroke(1.dp, DividerDark)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            // 切片元信息头部：第 X 段、章节标题、字符区间
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Surface(
                        color = AccentCyan.copy(alpha = 0.16f),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = "第 ${chunk.orderIndex + 1} 段",
                            color = AccentCyan,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }

                    if (!chunk.section.isNullOrBlank()) {
                        Surface(
                            color = AccentGold.copy(alpha = 0.16f),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                text = "章节：${chunk.section}",
                                color = AccentGold,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }
                }

                Text(
                    text = "字符位置 ${chunk.charStart}..$charEnd（${chunk.text.length} 字）",
                    color = TextMuted,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )

                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.DeleteOutline,
                        contentDescription = "删除这一段切片",
                        tint = Color(0xFFE57373),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // 标准引述题头预览
            Text(
                text = "> 【知识库原文引述 · 出处：《$documentTitle》· 第 ${chunk.orderIndex + 1} 段】",
                color = AccentNeonGreen.copy(alpha = 0.9f),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace
            )

            Spacer(modifier = Modifier.height(6.dp))

            // 原文内容（支持搜索关键词高亮）
            Text(
                text = buildHighlightedText(chunk.text, highlightQuery),
                color = TextPrimary,
                fontSize = 13.sp,
                lineHeight = 20.sp
            )
        }
    }
}

// ============================================================================
// 检索命中测试面板 (Live Retrieval Test View)
// ============================================================================

@Composable
internal fun LiveRetrievalTestPanel(
    query: String,
    onQueryChange: (String) -> Unit,
    onRunTest: () -> Unit,
    isRunning: Boolean,
    result: KnowledgeRetriever.RetrievalResult?,
    currentFolderPath: String,
    scopeCurrentFolderOnly: Boolean,
    onScopeChange: (Boolean) -> Unit,
    onOpenEvidenceInInspector: (KnowledgeRetriever.Evidence) -> Unit
) {
    Surface(
        color = PanelSurface,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, AccentCyan.copy(alpha = 0.45f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null,
                        tint = AccentCyan,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "检索命中测试（2-Gram 倒排 + 学术关键术语精确子串加权 + BM25 长度归一化）",
                        color = TextPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    FilterChip(
                        selected = !scopeCurrentFolderOnly,
                        onClick = { onScopeChange(false) },
                        label = { Text("全部已启用目录", fontSize = 11.sp) }
                    )
                    FilterChip(
                        selected = scopeCurrentFolderOnly,
                        onClick = { onScopeChange(true) },
                        label = {
                            val label = if (currentFolderPath.isBlank()) "仅根目录" else "仅当前目录"
                            Text(label, fontSize = 11.sp)
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    placeholder = {
                        Text(
                            text = "输入测试问题或术语（例如：乔姆斯基 深层结构 表层结构）…",
                            fontSize = 12.sp,
                            color = TextMuted
                        )
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AccentCyan,
                        unfocusedBorderColor = DividerDark,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    ),
                    shape = RoundedCornerShape(10.dp)
                )

                Button(
                    onClick = onRunTest,
                    enabled = !isRunning && query.isNotBlank(),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AccentCyan,
                        contentColor = Color.Black
                    )
                ) {
                    Text(
                        text = if (isRunning) "检索中…" else "立即测试检索",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // 快捷示例查询词 Chip
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(text = "快捷示例：", color = TextMuted, fontSize = 11.sp)
                listOf("乔姆斯基 深层结构", "转换生成语法 表层结构", "语言能力与语言运用").forEach { preset ->
                    AssistChip(
                        onClick = {
                            onQueryChange(preset)
                            onRunTest()
                        },
                        label = { Text(preset, fontSize = 11.sp) },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = CardSurface,
                            labelColor = TextSecondary
                        )
                    )
                }
            }

            // 检索命中结果预览列表
            result?.let { res ->
                Spacer(modifier = Modifier.height(10.dp))
                HorizontalDivider(color = DividerDark)
                Spacer(modifier = Modifier.height(8.dp))

                val keyTerms = remember(query) { Chunker.extractKeyTerms(query) }
                Text(
                    text = "${res.summary} · 提取关键术语：${if (keyTerms.isEmpty()) "无" else keyTerms.joinToString("、")}",
                    color = AccentNeonGreen,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )

                Spacer(modifier = Modifier.height(8.dp))

                if (res.evidences.isEmpty()) {
                    Text(
                        text = "未在当前已启用的教材中命中相关切片，请检查教材是否已开启或更换查询词。",
                        color = TextMuted,
                        fontSize = 12.sp
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 240.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(res.evidences, key = { "hit_${it.chunkId}" }) { ev ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onOpenEvidenceInInspector(ev) },
                                shape = RoundedCornerShape(8.dp),
                                colors = CardDefaults.cardColors(containerColor = CardSurface),
                                border = BorderStroke(1.dp, DividerDark)
                            ) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        val folderPrefix = ev.folderPath.takeIf { it.isNotBlank() }?.let { "[$it] " }.orEmpty()
                                        Text(
                                            text = "> 【知识库原文引述 · 出处：$folderPrefix《${ev.documentTitle}》· 第 ${ev.orderIndex + 1} 段】",
                                            color = AccentCyan,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Surface(
                                            color = AccentNeonGreen.copy(alpha = 0.16f),
                                            shape = RoundedCornerShape(4.dp)
                                        ) {
                                            Text(
                                                text = "得分 ${String.format(Locale.US, "%.2f", ev.score)}",
                                                color = AccentNeonGreen,
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = buildHighlightedText(ev.text, query),
                                        color = TextPrimary,
                                        fontSize = 12.sp,
                                        maxLines = 3,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ============================================================================
// 空目录占位与消息横幅
// ============================================================================

@Composable
private fun EmptyFolderPlaceholder(
    currentFolderPath: String,
    onNewFolder: () -> Unit,
    onImportFiles: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Surface(
            color = PanelSurface,
            shape = CircleShape,
            border = BorderStroke(1.dp, DividerDark),
            modifier = Modifier.size(64.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Default.FolderOpen,
                    contentDescription = null,
                    tint = TextMuted,
                    modifier = Modifier.size(32.dp)
                )
            }
        }
        Spacer(modifier = Modifier.height(14.dp))
        Text(
            text = if (currentFolderPath.isBlank()) "知识库根目录暂无文件夹或教材" else "当前文件夹“$currentFolderPath”为空",
            color = TextPrimary,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "您可以创建专题子文件夹分类管理考研或课程教材，或直接导入 PDF / EPUB / TXT / MD 教材到此目录。",
            color = TextMuted,
            fontSize = 12.sp
        )
        Spacer(modifier = Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onNewFolder, shape = RoundedCornerShape(10.dp)) {
                Icon(Icons.Default.CreateNewFolder, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("新建子文件夹", fontSize = 12.sp)
            }
            Button(
                onClick = onImportFiles,
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AccentCyan, contentColor = Color.Black)
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("导入教材到此目录", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun StatusMessageBanner(
    message: String,
    onDismiss: () -> Unit
) {
    Surface(
        color = CardSurface,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, AccentGold.copy(alpha = 0.6f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Info,
                contentDescription = null,
                tint = AccentGold,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = message,
                color = AccentGold,
                fontSize = 12.sp,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onDismiss) {
                Text("知道了", color = AccentCyan, fontSize = 12.sp)
            }
        }
    }
}

// ============================================================================
// 模态操作对话框（新建文件夹、重命名教材、移动到文件夹、删除文件夹确认）
// ============================================================================

@Composable
private fun NewFolderModalDialog(
    currentFolderPath: String,
    folderNameInput: String,
    onFolderNameChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建知识库文件夹", fontSize = 16.sp, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                val parentDesc = if (currentFolderPath.isBlank()) "根目录" else currentFolderPath
                Text(
                    text = "将在“$parentDesc”下创建新文件夹（支持输入多级路径，如“语言学考研/乔姆斯基专题”）：",
                    fontSize = 12.sp,
                    color = TextSecondary
                )
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedTextField(
                    value = folderNameInput,
                    onValueChange = onFolderNameChange,
                    singleLine = true,
                    placeholder = { Text("例如：乔姆斯基专题", fontSize = 12.sp) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = folderNameInput.trim().isNotEmpty()
            ) {
                Text("创建")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

@Composable
private fun RenameDocumentModalDialog(
    document: KnowledgeDocument,
    titleInput: String,
    onTitleChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("重命名教材", fontSize = 16.sp, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text(
                    text = "修改教材在知识库检索与原文引述中的显示标题：",
                    fontSize = 12.sp,
                    color = TextSecondary
                )
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedTextField(
                    value = titleInput,
                    onValueChange = onTitleChange,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = titleInput.trim().isNotEmpty()
            ) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

@Composable
private fun MoveTargetFolderModalDialog(
    dialogTitle: String,
    currentTargetInput: String,
    availableFolders: List<String>,
    onTargetInputChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(dialogTitle, fontSize = 16.sp, fontWeight = FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "可选择下方已有文件夹，或直接输入目标文件夹路径（留空表示移动到根目录）：",
                    fontSize = 12.sp,
                    color = TextSecondary
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = currentTargetInput,
                    onValueChange = onTargetInputChange,
                    singleLine = true,
                    placeholder = { Text("留空代表根目录，如：语言学考研/乔姆斯基专题", fontSize = 12.sp) },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(10.dp))
                Text(text = "快速选择目录：", fontSize = 11.sp, color = TextMuted)
                Spacer(modifier = Modifier.height(6.dp))
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 180.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    item {
                        FolderPickerOptionRow(
                            label = "根目录",
                            selected = normalizeFolderPath(currentTargetInput).isEmpty(),
                            onClick = { onTargetInputChange("") }
                        )
                    }
                    items(availableFolders) { folderPath ->
                        FolderPickerOptionRow(
                            label = folderPath,
                            selected = normalizeFolderPath(currentTargetInput) == folderPath,
                            onClick = { onTargetInputChange(folderPath) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text("确认移动")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

@Composable
private fun FolderPickerOptionRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        color = if (selected) AccentCyan.copy(alpha = 0.16f) else CardSurface,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, if (selected) AccentCyan else DividerDark),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Folder,
                contentDescription = null,
                tint = if (selected) AccentCyan else AccentGold,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = label,
                color = if (selected) AccentCyan else TextPrimary,
                fontSize = 12.sp,
                modifier = Modifier.weight(1f)
            )
            if (selected) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = AccentCyan,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@Composable
private fun DeleteFolderConfirmDialog(
    folder: FolderItemSummary,
    onDeleteFolderAndDocs: () -> Unit,
    onDeleteFolderMoveDocsToRoot: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("删除文件夹“${folder.name}”", fontSize = 16.sp, fontWeight = FontWeight.Bold) },
        text = {
            Text(
                text = if (folder.documentCount == 0) {
                    "确定要移除空文件夹“${folder.fullPath}”吗？"
                } else {
                    "该文件夹包含 ${folder.documentCount} 册教材。您可以选择仅移除文件夹并将教材移回根目录，或连同文件夹内的教材一并删除。"
                },
                fontSize = 13.sp,
                color = TextSecondary
            )
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (folder.documentCount > 0) {
                    OutlinedButton(onClick = onDeleteFolderMoveDocsToRoot) {
                        Text("仅删目录（教材移至根目录）", fontSize = 12.sp)
                    }
                }
                Button(
                    onClick = onDeleteFolderAndDocs,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE57373))
                ) {
                    Text(
                        text = if (folder.documentCount > 0) "删除目录及内部教材" else "确认删除",
                        color = Color.White,
                        fontSize = 12.sp
                    )
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

// ============================================================================
// 辅助纯函数与持久化逻辑
// ============================================================================

private fun normalizeFolderPath(raw: String): String =
    raw.trim()
        .replace('\\', '/')
        .split('/')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .joinToString("/")

private fun parentFolderOf(folderPath: String): String {
    val norm = normalizeFolderPath(folderPath)
    val lastSlash = norm.lastIndexOf('/')
    return if (lastSlash < 0) "" else norm.substring(0, lastSlash)
}

private fun isInsideOrSameFolder(docFolderPath: String, targetFolder: String): Boolean {
    val normDoc = normalizeFolderPath(docFolderPath)
    val normTarget = normalizeFolderPath(targetFolder)
    if (normTarget.isEmpty()) return true
    return normDoc == normTarget || normDoc.startsWith("$normTarget/")
}

private fun loadCustomFolders(context: Context): Set<String> {
    val prefs = context.getSharedPreferences(PREFS_KNOWLEDGE_FOLDERS, Context.MODE_PRIVATE)
    val rawSet = prefs.getStringSet(KEY_CUSTOM_FOLDER_PATHS, emptySet()).orEmpty()
    return rawSet.map { normalizeFolderPath(it) }.filter { it.isNotEmpty() }.toSet()
}

private fun persistCustomFolders(context: Context, folders: Set<String>): Set<String> {
    val cleaned = folders.map { normalizeFolderPath(it) }.filter { it.isNotEmpty() }.toSet()
    context.getSharedPreferences(PREFS_KNOWLEDGE_FOLDERS, Context.MODE_PRIVATE)
        .edit()
        .putStringSet(KEY_CUSTOM_FOLDER_PATHS, cleaned)
        .apply()
    return cleaned
}

private fun saveCustomFolder(context: Context, current: Set<String>, newPath: String): Set<String> {
    val norm = normalizeFolderPath(newPath)
    if (norm.isEmpty()) return current
    val expanded = LinkedHashSet<String>(current)
    val parts = norm.split('/')
    for (i in 1..parts.size) {
        expanded.add(parts.take(i).joinToString("/"))
    }
    return persistCustomFolders(context, expanded)
}

private fun removeCustomFolderPrefix(context: Context, current: Set<String>, prefixToRemove: String): Set<String> {
    val normPrefix = normalizeFolderPath(prefixToRemove)
    val remaining = current.filterNot {
        it == normPrefix || it.startsWith("$normPrefix/")
    }.toSet()
    return persistCustomFolders(context, remaining)
}

private fun moveCustomFolderPrefix(
    context: Context,
    currentSet: Set<String>,
    oldPrefix: String,
    newPrefix: String
): Set<String> {
    val normOld = normalizeFolderPath(oldPrefix)
    val normNew = normalizeFolderPath(newPrefix)
    val updated = LinkedHashSet<String>()
    currentSet.forEach { path ->
        when {
            path == normOld -> updated.add(normNew)
            path.startsWith("$normOld/") -> {
                val suffix = path.removePrefix("$normOld/")
                updated.add("$normNew/$suffix")
            }
            else -> updated.add(path)
        }
    }
    val parts = normNew.split('/').filter { it.isNotEmpty() }
    for (i in 1..parts.size) {
        updated.add(parts.take(i).joinToString("/"))
    }
    return persistCustomFolders(context, updated)
}

/**
 * 合并文档中的 `folderPath` 与用户新建的持久化文件夹，并自动展开所有中间父目录层级。
 */
private fun buildAllFolderPaths(
    docFolderPaths: List<String>,
    customFolders: Set<String>
): List<String> {
    val set = LinkedHashSet<String>()
    (docFolderPaths + customFolders).forEach { raw ->
        val norm = normalizeFolderPath(raw)
        if (norm.isNotEmpty()) {
            val parts = norm.split('/')
            for (i in 1..parts.size) {
                set.add(parts.take(i).joinToString("/"))
            }
        }
    }
    return set.sorted()
}

/**
 * 计算 [parentPath] 下的所有直接子目录及其统计摘要。
 */
private fun buildChildFolderSummaries(
    parentPath: String,
    allFolderPaths: List<String>,
    documents: List<KnowledgeDocument>
): List<FolderItemSummary> {
    val normParent = normalizeFolderPath(parentPath)
    val prefix = if (normParent.isEmpty()) "" else "$normParent/"

    val immediateChildren = allFolderPaths.filter { path ->
        if (normParent.isEmpty()) {
            !path.contains('/')
        } else {
            path.startsWith(prefix) && !path.removePrefix(prefix).contains('/')
        }
    }

    return immediateChildren.map { childFullPath ->
        val childName = if (normParent.isEmpty()) childFullPath else childFullPath.removePrefix(prefix)
        val childPrefix = "$childFullPath/"
        val subFolderCount = allFolderPaths.count {
            it.startsWith(childPrefix) && !it.removePrefix(childPrefix).contains('/')
        }
        val docsInFolder = documents.filter { isInsideOrSameFolder(it.folderPath, childFullPath) }
        val enabledCount = docsInFolder.count { it.enabled }
        val totalChunks = docsInFolder.sumOf { it.chunkCount }
        FolderItemSummary(
            name = childName,
            fullPath = childFullPath,
            subFolderCount = subFolderCount,
            documentCount = docsInFolder.size,
            enabledDocumentCount = enabledCount,
            totalChunkCount = totalChunks,
            isEnabled = if (docsInFolder.isEmpty()) true else enabledCount > 0
        )
    }
}

/**
 * 批量切换某个文件夹（含其子目录）下全部教材的启用状态。
 */
private fun toggleFolderHierarchyEnabled(
    viewModel: ChatNodeViewModel,
    documents: List<KnowledgeDocument>,
    folderPath: String,
    enabled: Boolean
) {
    val normTarget = normalizeFolderPath(folderPath)
    viewModel.setKnowledgeFolderEnabled(normTarget, enabled)
    documents
        .map { normalizeFolderPath(it.folderPath) }
        .distinct()
        .filter { it == normTarget || it.startsWith("$normTarget/") }
        .forEach { path ->
            viewModel.setKnowledgeFolderEnabled(path, enabled)
        }
}

/**
 * 兼容更新全局知识库启用开关（`viewModel.settings.knowledgeEnabled`）。
 */
private fun updateGlobalKnowledgeEnabled(viewModel: ChatNodeViewModel, enabled: Boolean) {
    runCatching {
        val settingsObj = viewModel.settings
        val setter = settingsObj.javaClass.methods.firstOrNull {
            it.name == "setKnowledgeEnabled" && it.parameterTypes.size == 1
        }
        if (setter != null) {
            setter.invoke(settingsObj, enabled)
            return
        }
        val vmSetter = viewModel.javaClass.methods.firstOrNull {
            (it.name == "setKnowledgeEnabled" || it.name == "updateKnowledgeEnabled") &&
                it.parameterTypes.size == 1
        }
        if (vmSetter != null) {
            vmSetter.invoke(viewModel, enabled)
            return
        }
        val field = settingsObj.javaClass.getDeclaredField("knowledgeEnabled").apply {
            isAccessible = true
        }
        field.setBoolean(settingsObj, enabled)
    }
}

/**
 * 在原文中高亮搜索关键词。
 */
@Composable
private fun buildHighlightedText(text: String, rawQuery: String): AnnotatedString {
    val goldColor = AccentGold
    val terms = Chunker.extractKeyTerms(rawQuery).ifEmpty {
        val trimmed = rawQuery.trim()
        if (trimmed.isNotEmpty()) listOf(trimmed) else emptyList()
    }
    if (terms.isEmpty()) return AnnotatedString(text)

    return buildAnnotatedString {
        val lowerText = text.lowercase()
        val highlightRanges = ArrayList<IntRange>()
        for (term in terms) {
            val lowerTerm = term.lowercase()
            if (lowerTerm.isEmpty()) continue
            var startIndex = 0
            while (startIndex < lowerText.length) {
                val found = lowerText.indexOf(lowerTerm, startIndex)
                if (found < 0) break
                highlightRanges.add(found until (found + lowerTerm.length))
                startIndex = found + lowerTerm.length
            }
        }
        if (highlightRanges.isEmpty()) {
            append(text)
            return@buildAnnotatedString
        }

        val sorted = highlightRanges.sortedBy { it.first }
        var cursor = 0
        for (range in sorted) {
            if (range.first < cursor) continue
            if (range.first > cursor) {
                append(text.substring(cursor, range.first))
            }
            withStyle(
                SpanStyle(
                    background = goldColor.copy(alpha = 0.32f),
                    color = goldColor,
                    fontWeight = FontWeight.Bold
                )
            ) {
                append(text.substring(range.first, range.last + 1))
            }
            cursor = range.last + 1
        }
        if (cursor < text.length) {
            append(text.substring(cursor))
        }
    }
}

private fun formatChars(n: Int): String =
    when {
        n >= 10_000 -> String.format(Locale.US, "%.1f 万字", n / 10_000.0)
        n >= 1_000 -> String.format(Locale.US, "%.1f 千字", n / 1_000.0)
        else -> "$n 字"
    }

// ============================================================================
// RAG 双方案切换与云端 API 厂商配置面板
// ============================================================================

@Composable
fun RagSchemeConfigDialog(
    viewModel: ChatNodeViewModel,
    onDismiss: () -> Unit
) {
    val settings = viewModel.settings
    var selectedScheme by remember { mutableStateOf(settings.ragIndexingScheme) }
    var cloudEnabled by remember { mutableStateOf(settings.cloudRagEnabled) }
    var baseUrl by remember { mutableStateOf(settings.cloudRagBaseUrl) }
    var apiKey by remember { mutableStateOf(settings.cloudRagApiKey) }
    var embeddingModel by remember { mutableStateOf(settings.cloudRagEmbeddingModel) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Cloud,
                    contentDescription = null,
                    tint = AccentCyan,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "RAG 索引与检索双方案配置",
                    color = TextPrimary,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
            ) {
                Text(
                    text = "您可以自由选择使用纯本地端侧离线 2-gram 倒排索引，或配置云端 API 厂商生成高维向量 Embedding 进行精确语义检索：",
                    color = TextSecondary,
                    fontSize = 13.sp
                )
                Spacer(modifier = Modifier.height(14.dp))

                // 三种方案单选卡片
                com.treechat.workbench.rag.RagIndexingScheme.values().forEach { scheme ->
                    val isSelected = selectedScheme == scheme
                    Surface(
                        color = if (isSelected) AccentCyan.copy(alpha = 0.12f) else CardSurface,
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, if (isSelected) AccentCyan else DividerDark),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clickable {
                                selectedScheme = scheme
                                if (scheme != com.treechat.workbench.rag.RagIndexingScheme.LOCAL_OFFLINE) {
                                    cloudEnabled = true
                                }
                            }
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = scheme.label,
                                    color = if (isSelected) AccentCyan else TextPrimary,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = scheme.description,
                                    color = TextMuted,
                                    fontSize = 12.sp
                                )
                            }
                            if (isSelected) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = "已选择",
                                    tint = AccentCyan,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }

                // 如果选中了云端或混合方案，展示 API 配置输入框
                if (selectedScheme != com.treechat.workbench.rag.RagIndexingScheme.LOCAL_OFFLINE) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "云端 API 厂商参数 (OpenAI / 阿里云百炼 / 智谱 / SiliconFlow 等)",
                        color = AccentGold,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = baseUrl,
                        onValueChange = { baseUrl = it },
                        label = { Text("Base URL") },
                        placeholder = { Text("https://api.openai.com/v1") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AccentCyan,
                            unfocusedBorderColor = DividerDark
                        )
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        label = { Text("API Key") },
                        placeholder = { Text("sk-...") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AccentCyan,
                            unfocusedBorderColor = DividerDark
                        )
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    OutlinedTextField(
                        value = embeddingModel,
                        onValueChange = { embeddingModel = it },
                        label = { Text("Embedding 向量模型名称") },
                        placeholder = { Text("text-embedding-3-small") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AccentCyan,
                            unfocusedBorderColor = DividerDark
                        )
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    settings.updateRagIndexingScheme(selectedScheme)
                    settings.updateCloudRagEnabled(
                        cloudEnabled && selectedScheme != com.treechat.workbench.rag.RagIndexingScheme.LOCAL_OFFLINE
                    )
                    settings.updateCloudRagBaseUrl(baseUrl)
                    settings.updateCloudRagApiKey(apiKey)
                    settings.updateCloudRagEmbeddingModel(embeddingModel)
                    onDismiss()
                },
                colors = ButtonDefaults.buttonColors(containerColor = AccentCyan)
            ) {
                Text("保存设置", color = Color.Black, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", color = TextSecondary)
            }
        },
        containerColor = PanelSurface
    )
}

// ============================================================================
// 逐份文档「重建索引（选方案）」对话框
// ============================================================================

/** 把入库的方案标识（字符串）转成卡片上的短标签。 */
private fun schemeBadgeText(raw: String?): String =
    when (runCatching { RagIndexingScheme.valueOf(raw.orEmpty()) }.getOrNull()) {
        RagIndexingScheme.CLOUD_EMBEDDING -> "云端向量"
        RagIndexingScheme.HYBRID -> "双路混合"
        else -> "本地 2-Gram"
    }

/**
 * 为**单份文档**选择索引方案并重建（不改动全局设置）。
 *
 * 重建期间对话框保持打开：确认按钮变为「正在重建…」并禁用，对话框内滚动显示
 * ViewModel 推来的最新进度；完成后仍沿用既有的 `_knowledgeMessage` 提示链路
 * （顶部横幅与对话框内文字都会显示结果）。
 */
@Composable
internal fun RebuildIndexSchemeDialog(
    document: KnowledgeDocument,
    busy: Boolean,
    cloudApiReady: Boolean,
    statusMessage: String?,
    onConfirm: (RagIndexingScheme) -> Unit,
    onDismiss: () -> Unit
) {
    val initialScheme = remember(document.id, document.indexingScheme) {
        runCatching { RagIndexingScheme.valueOf(document.indexingScheme) }
            .getOrDefault(RagIndexingScheme.LOCAL_OFFLINE)
    }
    var selectedScheme by remember(document.id) { mutableStateOf(initialScheme) }
    // 只在本次点击「开始重建」之后才展示进度文字，避免把打开对话框前的旧提示串场
    var rebuildStarted by remember(document.id) { mutableStateOf(false) }
    val selectedNeedsCloud = selectedScheme != RagIndexingScheme.LOCAL_OFFLINE
    val liveStatus = if (rebuildStarted) statusMessage?.takeIf { it.isNotBlank() } else null

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = {
            Column {
                Text(
                    text = "重建索引（选方案）",
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp
                )
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = "《${document.title}》",
                    color = TextMuted,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
            ) {
                Text(
                    text = "为该文档单独选择索引方案（只影响这一份，不改动全局设置）：",
                    color = TextSecondary,
                    fontSize = 13.sp
                )
                Spacer(modifier = Modifier.height(10.dp))

                RagIndexingScheme.values().forEach { scheme ->
                    val isSelected = selectedScheme == scheme
                    Surface(
                        color = if (isSelected) AccentCyan.copy(alpha = 0.12f) else CardSurface,
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, if (isSelected) AccentCyan else DividerDark),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clickable(enabled = !busy) { selectedScheme = scheme }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = isSelected,
                                onClick = { selectedScheme = scheme },
                                enabled = !busy
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = scheme.label,
                                    color = if (isSelected) AccentCyan else TextPrimary,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = scheme.description,
                                    color = TextMuted,
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }
                }

                if (document.chunkCount > 0) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "确认后将先清除该文档现有的 ${document.chunkCount} 段旧切片与倒排索引，" +
                            "再按所选方案重建；文档本身不会被删除。",
                        color = AccentGold,
                        fontSize = 12.sp
                    )
                }

                if (selectedNeedsCloud && !cloudApiReady) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "云端配置不完整：需先在「方案/API」中填写 Base URL、API Key 与 " +
                            "Embedding 模型，否则不会按云端方案建索引（不会静默退回本地）。",
                        color = Color(0xFFE57373),
                        fontSize = 12.sp
                    )
                }

                if (rebuildStarted && liveStatus != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = liveStatus,
                        color = AccentGold,
                        fontSize = 12.sp
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    rebuildStarted = true
                    onConfirm(selectedScheme)
                },
                enabled = !busy,
                colors = ButtonDefaults.buttonColors(containerColor = AccentCyan)
            ) {
                Text(
                    text = if (busy && rebuildStarted) "正在重建…" else "开始重建",
                    color = Color.Black,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) {
                Text("关闭", color = TextSecondary)
            }
        },
        containerColor = PanelSurface
    )
}

