// 桌面书架 v2（M2）：FileDialog 导入 + ReaderDocumentPool 书架 +
// 共享 AdaptiveDocumentView 阅读 + ReaderSidePanel 生词侧栏。
// 对话/AI/检索链路从 M3 起接入；这里用剪贴板/提示条顶住提问入口。
package com.treechat.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.FormatSize
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import com.treechat.workbench.ui.reader.DocumentHighlight
import com.treechat.workbench.ui.reader.ReaderDocument
import com.treechat.workbench.data.prefs.AppSettings
import com.treechat.workbench.etymology.data.VocabIndex
import com.treechat.workbench.ui.reader.AdaptiveDocumentView
import com.treechat.workbench.ui.reader.CoverExtractor
import com.treechat.workbench.ui.reader.DocumentFileReader
import com.treechat.workbench.ui.reader.PanelAiActions
import com.treechat.workbench.ui.reader.ReaderDocumentPool
import com.treechat.workbench.ui.reader.ReaderPanelState
import com.treechat.workbench.ui.reader.ReaderSidePanel
import com.treechat.workbench.ui.reader.ReaderTypographyDialog
import com.treechat.workbench.ui.reader.VocabPageSummary
import com.treechat.workbench.ui.reader.findVocabHits
import com.treechat.workbench.ui.selection.SelectionScheme
import com.treechat.workbench.ui.theme.TreeChatTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import javax.swing.SwingUtilities

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DesktopReaderHost(
    settings: AppSettings,
    env: DesktopAppEnv,
    onCloseRequest: () -> Unit,
    initialOpen: java.io.File? = null,
) {
    val context = DesktopAppEnvHolder.appContext
    val scope = rememberCoroutineScope()
    val pool = remember { ReaderDocumentPool(context) }
    val panelState = remember { ReaderPanelState.obtain(context) }

    var activeDocumentId by remember { mutableStateOf<String?>(null) }
    var visiblePage by remember { mutableIntStateOf(1) }
    var showTypography by remember { mutableStateOf(false) }
    var showPanel by remember { mutableStateOf(true) }
    var pageCapacity by remember { mutableIntStateOf(DocumentFileReader.CHARS_PER_PAGE) }
    var highlights by remember { mutableStateOf<Map<String, List<DocumentHighlight>>>(emptyMap()) }
    var toast by remember { mutableStateOf<String?>(null) }
    var focusedWord by remember { mutableStateOf<String?>(null) }

    val activeDocument = pool.documents.firstOrNull { it.id == activeDocumentId }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(toast) {
        toast?.let {
            snackbarHostState.showSnackbar(it)
            toast = null
        }
    }

    // 词库预热（纯 JVM 解析，TSV 走充面 assets）
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            runCatching { VocabIndex.ensureLoaded(context) }
        }
    }

    // CLI 直开一本书（桌面启动器 / 无头验证用）
    LaunchedEffect(initialOpen) {
        val file = initialOpen ?: return@LaunchedEffect
        if (file.exists()) {
            val doc = withContext(Dispatchers.IO) {
                DocumentFileReader.importDocument(context, file)
            }
            if (doc != null) {
                pool.addDocument(doc)
                activeDocumentId = doc.id
                visiblePage = 1
            } else {
                toast = DocumentFileReader.lastError ?: "导入失败"
            }
        }
    }

    fun importFiles() {
        SwingUtilities.invokeLater {
            val dialog = FileDialog(null as java.awt.Frame?, "导入书籍", FileDialog.LOAD)
            dialog.isMultipleMode = true
            dialog.isVisible = true
            val files = dialog.files?.toList().orEmpty()
            if (files.isEmpty()) return@invokeLater
            scope.launch {
                var firstId: String? = null
                for (file in files) {
                    val doc = withContext(Dispatchers.IO) {
                        DocumentFileReader.importDocument(context, file)
                    }
                    if (doc != null) {
                        pool.addDocument(doc)
                        firstId = firstId ?: doc.id
                    } else {
                        toast = DocumentFileReader.lastError ?: "导入失败"
                    }
                }
                if (firstId != null) {
                    activeDocumentId = firstId
                    visiblePage = 1
                }
            }
        }
    }

    Window(
        onCloseRequest = onCloseRequest,
        title = "条畅（桌面 M2 预览）",
        state = rememberWindowState(width = 1280.dp, height = 830.dp),
    ) {
        TreeChatTheme(
            themeMode = settings.themeMode,
            dynamicColor = false,
            palette = settings.themePalette,
            fontScale = settings.fontScale,
        ) {
            Scaffold(
                snackbarHost = { SnackbarHost(snackbarHostState) },
                topBar = {
                    TopAppBar(
                        title = {
                            if (activeDocument == null) {
                                Text("条畅 — 书架", fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            } else {
                                Text(activeDocument.title, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        },
                        navigationIcon = {
                            if (activeDocument != null) {
                                IconButton(onClick = {
                                    pool.updateProgress(activeDocument.id, visiblePage)
                                    activeDocumentId = null
                                }) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "回到书架")
                                }
                            }
                        },
                        actions = {
                            IconButton(onClick = { showTypography = true }) {
                                Icon(Icons.Outlined.FormatSize, contentDescription = "排版")
                            }
                            IconButton(onClick = { showPanel = !showPanel }) {
                                Icon(Icons.Outlined.MenuBook, contentDescription = "侧栏")
                            }
                            IconButton(onClick = { importFiles() }) {
                                Icon(Icons.Filled.Add, contentDescription = "导入书籍")
                            }
                        },
                    )
                },
            ) { padding ->
                if (activeDocument == null) {
                    DesktopShelf(
                        pool = pool,
                        onOpen = { doc ->
                            activeDocumentId = doc.id
                            visiblePage = doc.currentPage
                        },
                        onDelete = { doc ->
                            pool.removeDocument(doc.id)
                            CoverExtractor.deleteCache(context, doc.id)
                        },
                        onImport = { importFiles() },
                        modifier = Modifier.padding(padding),
                    )
                } else {
                    Row(modifier = Modifier.padding(padding).fillMaxSize()) {
                        Box(Modifier.weight(1f).fillMaxSize()) {
                            AdaptiveDocumentView(
                                document = activeDocument,
                                currentPage = visiblePage,
                                highlights = highlights[activeDocument.id] ?: emptyList(),
                                onPageChange = { page ->
                                    visiblePage = page
                                    pool.updateProgress(activeDocument.id, page)
                                },
                                onAskQuestion = { text ->
                                    env.setClipboardText(text)
                                    toast = "选段已复制（对话功能 M3 接入）"
                                },
                                onHighlight = { selected, page ->
                                    val list = highlights[activeDocument.id] ?: emptyList()
                                    highlights = highlights + (activeDocument.id to (list + DocumentHighlight(
                                        id = env.newUuid(),
                                        documentId = activeDocument.id,
                                        pageIndex = page,
                                        startOffset = 0,
                                        endOffset = 0,
                                        selectedText = selected,
                                    )))
                                    toast = "已加高亮（持久化在 M3 接 Room）"
                                },
                                onAddNote = { selected, _ ->
                                    env.setClipboardText(selected)
                                    toast = "笔记暂复制到剪贴板（M3 接注释板）"
                                },
                                onOpenEtymology = { word -> if (word.isNotBlank()) focusedWord = word },
                                selectionScheme = if (settings.selectionScheme == SelectionScheme.TOUCH_LONG_PRESS) {
                                    SelectionScheme.MOUSE_DESKTOP
                                } else {
                                    settings.selectionScheme
                                },
                                onToggleImmersive = {},
                                onPageText = {},
                            )
                        }
                        if (showPanel) {
                            ReaderSidePanel(
                                state = panelState,
                                summary = desktopVocabSummary(
                                    text = DocumentFileReader.getPageContent(
                                        activeDocument, visiblePage, pageCapacity,
                                    ),
                                    examTokens = panelState.examTokens,
                                ),
                                focusedWord = focusedWord,
                                notes = emptyList(),
                                ai = PanelAiActions(
                                    onAsk = { toast = "对话提问 M3 接入" },
                                    onSyntax = { toast = "句法分析 M3 接入" },
                                    onTranslate = { toast = "翻译 M3 接入" },
                                    onFollowUp = { toast = "翻译追问 M3 接入" },
                                ),
                                onPickWord = { focusedWord = it },
                                onWordFocused = { focusedWord = it },
                                modifier = Modifier.width(260.dp).fillMaxHeight(),
                            )
                        }
                    }
                }
            }
        }

        if (showTypography) {
            ReaderTypographyDialog(onDismiss = { showTypography = false })
        }
    }
}

/** 桌面书架网格（M2 先保功能；M5 换成与安卓版同视觉的 Shelf 内容）。 */
@Composable
private fun DesktopShelf(
    pool: ReaderDocumentPool,
    onOpen: (ReaderDocument) -> Unit,
    onDelete: (ReaderDocument) -> Unit,
    onImport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (pool.documents.isEmpty()) {
        Column(
            modifier = modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("书架还是空的", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text("支持 EPUB / TXT / PDF / Markdown / DOCX / PPTX / MOBI", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(16.dp))
            FilledTonalButton(onClick = onImport) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("导入书籍")
            }
        }
    } else {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(180.dp),
            modifier = modifier.fillMaxSize().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(pool.documents, key = { it.id }) { doc ->
                ElevatedCard(onClick = { onOpen(doc) }) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            doc.title,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "${doc.format.name} · 约 ${doc.sampleText.length} 字",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            if (doc.isScanned) "扫描件，读到第 ${doc.currentPage} 页" else "读到第 ${doc.currentPage} / ${doc.pageCount} 页",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = { onDelete(doc) }) { Text("删除") }
                    }
                }
            }
        }
    }
}

/** 生词汇总（M2 现算；M3 由 ViewModel 走 Room 收藏与跨页缓存）。 */
@Composable
private fun desktopVocabSummary(
    text: String,
    examTokens: Set<String>,
): VocabPageSummary {
    val unique = remember(text, examTokens) {
        findVocabHits(text, examTokens) { VocabIndex.examTags(it) }
    }
    return VocabPageSummary(
        count = unique.size,
        unique = unique,
    )
}
