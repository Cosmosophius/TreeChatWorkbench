package com.treechat.workbench.ui.explorer

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddComment
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.FileUpload
import kotlinx.coroutines.launch
import androidx.compose.material.icons.outlined.FileDownload
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.treechat.workbench.export.TalkTreeBundle
import com.treechat.workbench.ui.common.FreePositionStore
import com.treechat.workbench.export.ZipExporter
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.mutableStateMapOf
import kotlin.math.max
import kotlin.math.min
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.runtime.saveable.mapSaver
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.treechat.workbench.data.model.Conversation
import com.treechat.workbench.data.model.Folder
import com.treechat.workbench.ui.chat.ChatNodeViewModel
import kotlinx.coroutines.delay
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

private const val TRASH_TARGET_ID = "__TRASH_BIN_DROP_TARGET__"

/** 收藏星标高亮色（琥珀色）。 */
private val FavoriteAmber = Color(0xFFF59E0B)

/** 视图模式：有序列表行 与 图标自由摆放画布（安卓桌面式）。 */
private enum class ExplorerLayout { LIST, GRID }

/**
 * 排序方式。
 *
 * 只给数据真实提供字段的两项：
 * - [NAME]：按名称（文件夹 name / 对话 title）；
 * - [MODIFIED]：按修改时间 —— Conversation 有 updatedAt；**Folder 没有
 *   修改时间字段**，只能退回 createdAt，界面上有明确注明，不虚构数据。
 */
private enum class ExplorerSort(val label: String) {
    NAME("按名称"),
    MODIFIED("按修改时间")
}

/** 浏览器里的一项：要么是文件夹，要么是对话。 */
private sealed interface ExplorerEntry {
    data class FolderEntry(
        val folder: Folder,
        val subFolderCount: Int,
        val conversationCount: Int
    ) : ExplorerEntry

    data class ConversationEntry(
        val conversation: Conversation,
        val folderPath: String
    ) : ExplorerEntry
}

/**
 * 长按拖起会话：从按下抬起那一刻到松手为止的所有跟手信息。
 *
 * 全部坐标均为 **root（窗口根节点）空间的像素**，与滚动无关 ——
 * 手指位置用累计位移推算，滚动只影响命中矩形，不影响跟手。
 */
private data class DragSession(
    /** 条目 key（entryKey），用于对齐占位与命中表。 */
    val key: String,
    /** 原始数据 id（Folder.id / Conversation.id），坐标 JSON 的键。 */
    val itemId: String,
    val entry: ExplorerEntry,
    /** 抓取点相对条目左上角的偏移（px）。 */
    val grab: Offset,
    /** 条目尺寸（px），用于浮层与边界夹紧。 */
    val widthPx: Int,
    val heightPx: Int
)

/**
 * 可空字符串的 rememberSaveable 保存器：null ↔ ""。
 *
 * 直接把 null 交给默认 autoSaver 能否落进 Bundle 没有保证，
 * 显式换算最稳（"" 表示根目录）。
 */
private val NullableFolderIdSaver = Saver<String?, String>(
    save = { value -> value ?: "" },
    restore = { saved -> if (saved.isEmpty()) null else saved }
)

/** 条目 key 中「文件夹」的前缀，与 [entryKey] 保持一致。 */
private const val FOLDER_KEY_PREFIX = "f_"

/**
 * 文件式对话管理器 —— Material 3 版 Finder。
 *
 * 结构复用访达的组织方式（左侧位置栏、路径栏、图标/列表双视图、排序），
 * 但图形全部换成 Material 3 矢量图标，配色走 [androidx.compose.material3.MaterialTheme.colorScheme]，
 * **不使用 macOS 拟物配色**。
 *
 * 交互对齐安卓桌面 launcher：
 * - 图标视图下条目可自由摆放，坐标存 SharedPreferences JSON
 *   （见 [com.treechat.workbench.ui.common.FreePositionStore]，不动 Room）；
 * - 长按拖起文件/文件夹，拖到另一个文件夹上松手即归组（列表与图标两种视图都支持）；
 * - 长按但未移动 → 沿用原有「更多」菜单（置顶/收藏/重命名/移动/删除）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderExplorer(
    viewModel: ChatNodeViewModel,
    onOpenConversation: (String) -> Unit,
    /** 新建对话后调用：此时活动会话已被切换，界面应跟着跳进对话视图。 */
    onConversationCreated: () -> Unit,
    modifier: Modifier = Modifier
) {
    // ---------------- 数据与持久化 ----------------
    val context = LocalContext.current
    val prefs = remember { FreePositionStore(context, FreePositionStore.KEY_EXPLORER) }
    val freePositions = remember { mutableStateOf(prefs.load()) }
    val density = LocalDensity.current

    val allFolders by viewModel.folders.collectAsState()
    val allConversations by viewModel.conversations.collectAsState()

    // ---------------- 可跨旋转保存的界面状态 ----------------
    var currentFolderId by rememberSaveable(stateSaver = NullableFolderIdSaver) {
        mutableStateOf<String?>(null)
    }
    var layout by rememberSaveable { mutableStateOf(ExplorerLayout.GRID) }
    var onlyFavorites by rememberSaveable { mutableStateOf(false) }
    var sortMode by rememberSaveable { mutableStateOf(ExplorerSort.NAME) }
    var sidebarExpanded by rememberSaveable { mutableStateOf(true) }

    // 当前目录被删掉时自动退回根目录，避免停在一个不存在的路径上
    //
    // ⚠️ 必须加 `allFolders.isNotEmpty()` 这一半条件，否则"记住上次所在目录"永远失效：
    // `viewModel.folders` 是 Room 的 StateFlow，**首帧是空列表**（查询还没回来）。
    // 没有这半条件时，首帧就把 `currentFolderId` 判为"目录不存在"清成 null，
    // 于是 rememberSaveable 恢复出来的目录 id 立刻被抹掉 ——
    // 用户看到的就是"我在哪个目录它根本不记，切个 Tab 回来就回根目录"。
    // 目录确实被删掉时列表非空、id 找不到，那时才该退回根。
    if (currentFolderId != null &&
        allFolders.isNotEmpty() &&
        allFolders.none { it.id == currentFolderId }
    ) {
        currentFolderId = null
    }

    val breadcrumb = remember(allFolders, currentFolderId) {
        buildBreadcrumb(allFolders, currentFolderId)
    }

    // ---------------- 搜对话（标题 + 正文全文） ----------------
    var explorerQuery by rememberSaveable { mutableStateOf("") }
    // 正文命中的会话 id：标题没命中、但对话内容里提到过关键词的那一类。
    var contentHitIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    LaunchedEffect(explorerQuery) {
        val q = explorerQuery.trim()
        if (q.length < 2) {
            contentHitIds = emptySet()
        } else {
            // 防抖：连续输入时不反复打库
            delay(220)
            contentHitIds = viewModel.conversationIdsMatchingContent(q)
        }
    }

    val entries = remember(allFolders, allConversations, currentFolderId, onlyFavorites, sortMode) {
        buildEntries(
            allFolders = allFolders,
            allConversations = allConversations,
            currentFolderId = currentFolderId,
            onlyFavorites = onlyFavorites,
            sortMode = sortMode
        )
    }

    /**
     * 应用搜索过滤后的条目。
     *
     * 命中规则（任一即可）：文件夹名 / 对话标题含关键词，或该对话正文命中关键词
     * （contentHitIds 由只读全文查询给出）。文件夹名命中时保留其子项不做递归过滤，
     * 否则"搜一个文件夹名"会看到空列表，与用户预期相反。
     */
    val visibleEntries = remember(entries, explorerQuery, contentHitIds) {
        val q = explorerQuery.trim()
        if (q.isEmpty()) {
            entries
        } else {
            entries.filter { entry ->
                when (entry) {
                    is ExplorerEntry.FolderEntry ->
                        entry.folder.name.contains(q, ignoreCase = true)
                    is ExplorerEntry.ConversationEntry ->
                        entry.conversation.title.contains(q, ignoreCase = true) ||
                            entry.conversation.id in contentHitIds
                }
            }
        }
    }

    val scope = rememberCoroutineScope()


    // ---------------- 对话框与反馈 ----------------
    var showNewFolderDialog by remember { mutableStateOf(false) }
    var showNewConversationDialog by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<Any?>(null) }
    var moveTarget by remember { mutableStateOf<Any?>(null) }
    var deleteTarget by remember { mutableStateOf<Any?>(null) }
    var fabMenuExpanded by remember { mutableStateOf(false) }
    var sortMenuExpanded by remember { mutableStateOf(false) }

    // 归组结果的 snackbar 式反馈（应用级 Scaffold 没挂 SnackbarHost，就地呈现）
    var snackText by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(snackText) {
        if (snackText != null) {
            delay(2400)
            snackText = null
        }
    }

    // ---------------- 多选状态（列表多选与图标框选共用） ----------------
    var selectionMode by remember { mutableStateOf(false) }
    val selectedEntryKeys = remember { mutableStateMapOf<String, Boolean>() }
    var batchDeleteConfirm by remember { mutableStateOf(false) }

    // ---------------- 图标视图空白处框选（Marquee Selection）状态 ----------------
    var marqueeStart by remember { mutableStateOf<Offset?>(null) }
    var marqueeCurrent by remember { mutableStateOf<Offset?>(null) }

    // ---------------- 废纸篓 Bounds 与悬停状态 ----------------
    var trashBinBounds by remember { mutableStateOf<Rect?>(null) }

    val talkTreeImportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = runCatching {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    TalkTreeBundle.importFromStream(stream)
                }
            }
            result.onSuccess { bundle ->
                if (bundle != null) {
                    viewModel.importTalkTreeBundle(bundle, currentFolderId) {
                        snackText = "已成功导入对话「${bundle.conversation.title}」"
                    }
                } else {
                    snackText = "未能解析 .talktree 档案"
                }
            }.onFailure { e ->
                snackText = "导入失败：${e.localizedMessage ?: "未知错误"}"
            }
        }
    }

    fun exportSingleEntryToZip(target: Any) {
        scope.launch {
            val (folders, convs) = when (target) {
                is Folder -> listOf(target) to emptyList()
                is Conversation -> emptyList<Folder>() to listOf(target)
                is ExplorerEntry.FolderEntry -> listOf(target.folder) to emptyList()
                is ExplorerEntry.ConversationEntry -> emptyList<Folder>() to listOf(target.conversation)
                else -> return@launch
            }
            snackText = "正在生成 ZIP 压缩包…"
            val file = ZipExporter.exportToZip(
                context = context,
                conversationDao = viewModel.conversationDaoInstance,
                targetFolders = folders,
                targetConversations = convs,
                allFolders = allFolders,
                allConversations = allConversations,
                zipNamePrefix = if (folders.isNotEmpty()) folders.first().name else convs.first().title
            )
            ZipExporter.shareFile(context, file, title = "分享压缩包")
            snackText = "已生成 ZIP 压缩包并调起分享"
        }
    }

    fun exportConversationTalkTree(conv: Conversation) {
        scope.launch {
            snackText = "正在生成 .talktree 跨平台档案…"
            val nodes = viewModel.conversationDaoInstance.getNodesForConversation(conv.id)
            val allMessages = mutableListOf<com.treechat.workbench.data.model.ChatMessage>()
            for (node in nodes) {
                allMessages.addAll(viewModel.conversationDaoInstance.getMessagesForNode(node.id))
            }
            val file = TalkTreeBundle.exportToFile(
                context = context,
                conversation = conv,
                nodes = nodes,
                messages = allMessages
            )
            ZipExporter.shareFile(
                context = context,
                file = file,
                mimeType = "application/octet-stream",
                title = "分享 .talktree 跨平台档案"
            )
            snackText = "已生成 .talktree 档案并调起分享"
        }
    }

    // ---------------- 拖拽状态 ----------------
    /** 条目 key -> root 空间矩形；拖拽命中与抓取点都靠它。 */
    val itemBounds = remember { mutableMapOf<String, Rect>() }

    // 视图结构一变就把命中表作废：否则上一个目录里残留在同一屏幕位置的
    // 文件夹会变成"假目标"，手指落上去会归错组。必须在组合阶段清，
    // 这样同一帧随后的布局会重新记录可见条目。
    remember(currentFolderId, onlyFavorites, layout, sortMode) {
        itemBounds.clear()
        Unit
    }

    var explorerBounds by remember { mutableStateOf<Rect?>(null) }
    var canvasBounds by remember { mutableStateOf<Rect?>(null) }
    /**
     * 画布实测尺寸变化（换屏、分屏、旋转、字号变化）时把已有摆放夹回可视区。
     *
     * 不夹的话：宽屏上摆到右侧的图标，换窄屏后会落到画布之外，用户既看不见
     * 也拖不回来。夹紧只在真的越界时写入，避免每次重组都落盘。
     */
    LaunchedEffect(canvasBounds, layout) {
        val canvas = canvasBounds ?: return@LaunchedEffect
        if (layout != ExplorerLayout.GRID) return@LaunchedEffect
        val px = density.density
        val columns = FreePlacement.columnCount(canvas.width / px)
        val positions = entries.mapIndexed { index, entry ->
            FreePlacement.positionOf(index, entryId(entry), columns, freePositions.value)
        }
        val clamped = FreePlacement.clampPositions(
            positions = freePositions.value,
            canvasWidthDp = canvas.width / px,
            maxContentHeightDp = maxOf(
                FreePlacement.contentHeightDp(positions),
                canvas.height / px
            )
        )
        if (clamped !== freePositions.value) {
            freePositions.value = clamped
            prefs.save(clamped)
        }
    }
    var dragSession by remember { mutableStateOf<DragSession?>(null) }
    var dragFinger by remember { mutableStateOf(Offset.Zero) }
    var dragMoved by remember { mutableStateOf(false) }
    var dropTargetId by remember { mutableStateOf<String?>(null) }
    // 长按未移动 → 弹出的「更多」菜单目标
    var overflowTarget by remember { mutableStateOf<ExplorerEntry?>(null) }

    val gridScroll = rememberScrollState()

    // ---- 滚动位置按目录记忆 ----
    //
    // 现象：进入某个目录 → 滑到中段某张卡 → 切到别的页签 → 返回，
    // 列表回到最顶部，用户得重新滑一遍去找刚才那张卡。
    //
    // 为什么按**目录 id** 分别记而不是只记一个全局位置：
    // 每个目录的条目数不同，共用一个位置在 A 目录是"中段"、在 B 目录就可能是"到底"，
    // 恢复出来必然错位。
    val listScrollMemory = rememberSaveable(saver = folderScrollSaver) {
        mutableStateMapOf<String, Int>()
    }
    val listState = rememberLazyListState()

    // 目录切换后恢复该目录上次的首条可见项；恢复不上去就保持 0。
    val scrollKey = currentFolderId ?: "__root__"
    LaunchedEffect(scrollKey) {
        listState.scrollToItem(listScrollMemory[scrollKey] ?: 0)
    }
    // 滚动时把首条可见项写回记忆。
    //
    // 为什么不用 LaunchedEffect(listState)：layoutInfo 不是可观察状态，
    // 首条可见项变化不会重启这个 effect，记忆永远写不进去。
    // 这里挂在 **snapshotFlow** 上：只在首条可见项**真的变了**时写一次，
    // 既能记录，又不会每帧触发重组。
    LaunchedEffect(listState, scrollKey) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .distinctUntilChanged()
            .collect { idx ->
                if (listScrollMemory[scrollKey] != idx) listScrollMemory[scrollKey] = idx
            }
    }

    // ---- 图标（网格）视图的滚动位置，同样按目录记忆 ----
    //
    // ⚠️ 网格视图与列表视图用的是**两套完全不同的滚动状态**：
    // 列表是 LazyListState（按"第几条"记忆），网格是 ScrollState（按**像素**记忆，
    // 因为网格是自由摆放画布，条目位置不构成线性序号）。
    // 上面只给列表加了记忆，而网格恰是默认视图 —— 于是用户"默认视图下切个 Tab
    // 回来就回到顶部"，看起来像"文件夹位置根本没记住"。
    val gridScrollMemory = rememberSaveable(saver = folderScrollSaver) {
        mutableStateMapOf<String, Int>()
    }
    LaunchedEffect(scrollKey, layout) {
        if (layout == ExplorerLayout.GRID) {
            gridScroll.scrollTo(gridScrollMemory[scrollKey] ?: 0)
        }
    }
    LaunchedEffect(gridScroll, scrollKey, layout) {
        if (layout != ExplorerLayout.GRID) return@LaunchedEffect
        snapshotFlow { gridScroll.value }
            .distinctUntilChanged()
            .collect { px ->
                if (gridScrollMemory[scrollKey] != px) gridScrollMemory[scrollKey] = px
            }
    }
    val dragThresholdPx = with(density) { FreePlacement.DRAG_CLASSIFY_DP.dp.toPx() }

    // ---------------- 拖拽辅助 ----------------
    fun clearDrag() {
        dragSession = null
        dropTargetId = null
        dragMoved = false
    }

    /** 长按抬起点：取条目 root 矩形推算手指绝对位置；测不到坐标就退回长按菜单。 */
    fun beginDrag(key: String, localOffset: Offset, entry: ExplorerEntry): Boolean {
        val bounds = itemBounds[key] ?: return false
        dragSession = DragSession(
            key = key,
            itemId = entryId(entry),
            entry = entry,
            grab = localOffset,
            widthPx = bounds.width.roundToInt(),
            heightPx = bounds.height.roundToInt()
        )
        dragFinger = bounds.topLeft + localOffset
        dragMoved = false
        dropTargetId = null
        return true
    }

    /**
     * 落点判定：只认**文件夹**（对话不能当容器），自身不作为目标。
     *
     * 拖到自己的子孙文件夹也返回其 id —— 松手时会给出明确的错误提示，
     * 而不是静默失败或退化成就地放置。
     */
    fun computeDropTarget(): String? {
        val session = dragSession ?: return null

        // 废纸篓判定：手指拖入废纸篓区域，判定为拖入废纸篓删除
        if (trashBinBounds?.contains(dragFinger) == true) {
            return TRASH_TARGET_ID
        }

        val canvas = canvasBounds ?: return null
        if (!canvas.contains(dragFinger)) return null
        val selfFolderId = (session.entry as? ExplorerEntry.FolderEntry)?.folder?.id

        if (layout == ExplorerLayout.GRID) {
            // 图标视图：判定手指位置（换算到画布内容坐标）是否压在某个文件夹格子上
            val px = density.density
            val columns = FreePlacement.columnCount(canvas.width / px)
            val rects = FreePlacement.contentRectsPx(
                ids = entries.map { entryId(it) },
                columns = columns,
                custom = freePositions.value,
                density = px
            )
            val point = Offset(
                x = dragFinger.x - canvas.left,
                y = dragFinger.y - canvas.top + gridScroll.value
            )
            for (index in entries.indices.reversed()) {
                val folder = (entries[index] as? ExplorerEntry.FolderEntry)?.folder ?: continue
                if (folder.id == selfFolderId) continue
                if (rects[folder.id]?.contains(point) == true) return folder.id
            }
            return null
        }

        // 列表视图：行矩形是布局实测的屏幕位置，手指压住即为目标
        for ((key, rect) in itemBounds) {
            if (!key.startsWith(FOLDER_KEY_PREFIX)) continue
            val id = key.substring(FOLDER_KEY_PREFIX.length)
            if (id == selfFolderId) continue
            if (rect.contains(dragFinger)) return id
        }
        return null
    }

    /**
     * 边缘自动滚动时必须保证**源条目仍在可视区内**：
     * 源条目的手势节点一旦被列表回收，拖拽手势会整个中断。
     */
    fun clampScrollForSource(delta: Float, key: String): Float {
        val canvas = canvasBounds ?: return 0f
        val bounds = itemBounds[key] ?: return 0f
        return if (delta > 0f) {
            // 内容上移：源条目 top - delta 仍需 >= 画布顶
            delta.coerceAtMost((bounds.top - canvas.top).coerceAtLeast(0f))
        } else {
            // 内容下移：源条目 bottom + |delta| 仍需 <= 画布底
            delta.coerceAtLeast(-((canvas.bottom - bounds.bottom).coerceAtLeast(0f)))
        }
    }

    /** 图标视图：未命中文件夹 → 就地放置，写入 SharedPreferences JSON。 */
    fun placeOnCanvas(session: DragSession) {
        val canvas = canvasBounds ?: return
        val px = density.density
        val canvasWidthDp = canvas.width / px
        val columns = FreePlacement.columnCount(canvasWidthDp)
        val positions = visibleEntries.mapIndexed { index, entry ->
            FreePlacement.positionOf(index, entryId(entry), columns, freePositions.value)
        }
        val contentHeight = FreePlacement.contentHeightDp(positions)
        val rawX = (dragFinger.x - canvas.left - session.grab.x) / px
        val rawY = (dragFinger.y - canvas.top + gridScroll.value - session.grab.y) / px
        val placed = FreePlacement.clampPlacement(
            x = rawX,
            y = rawY,
            canvasWidthDp = canvasWidthDp,
            // 内容高与视口高取大：既能放进视口里的空白区，也不会拖出内容边界
            maxContentHeightDp = maxOf(contentHeight, canvas.height / px)
        )
        // 顺手清掉已删除条目的残留坐标，避免 JSON 无限膨胀
        val aliveIds = buildSet {
            allFolders.forEach { add(it.id) }
            allConversations.forEach { add(it.id) }
        }
        freePositions.value = (freePositions.value + (session.itemId to placed))
            .filterKeys { it in aliveIds }
        prefs.save(freePositions.value)
    }

    /** 长按拖动统一收尾：未移动→长按选取或弹菜单；命中文件夹→归组；否则→就地放置。 */
    fun applyDrop(session: DragSession?, moved: Boolean) {
        if (session == null) {
            clearDrag()
            return
        }
        if (!moved) {
            clearDrag()
            if (layout == ExplorerLayout.LIST) {
                // 列表视图长按整行：与图标视图完全对齐，直接进入多选模式并勾选该项！
                selectionMode = true
                selectedEntryKeys[session.key] = true
            } else {
                overflowTarget = session.entry
            }
            return
        }
        val targetId = dropTargetId
        clearDrag()
        if (targetId == null) {
            if (layout == ExplorerLayout.GRID) placeOnCanvas(session)
            return
        }
        // 拖入废纸篓处理
        if (targetId == TRASH_TARGET_ID) {
            if (selectedEntryKeys.isNotEmpty()) {
                batchDeleteConfirm = true
            } else {
                deleteTarget = when (val entry = session.entry) {
                    is ExplorerEntry.FolderEntry -> entry.folder
                    is ExplorerEntry.ConversationEntry -> entry.conversation
                }
            }
            return
        }
        val target = allFolders.firstOrNull { it.id == targetId } ?: return
        when (val entry = session.entry) {
            is ExplorerEntry.FolderEntry -> {
                if (entry.folder.id == target.id) return
                if (collectSubtreeIds(allFolders, entry.folder.id).contains(target.id)) {
                    snackText = "不能把文件夹移入它自己的子文件夹"
                    return
                }
                viewModel.moveFolder(entry.folder.id, target.id)
                snackText = "已移入「${target.name}」"
            }
            is ExplorerEntry.ConversationEntry -> {
                viewModel.moveConversation(entry.conversation.id, target.id)
                snackText = "已移入「${target.name}」"
            }
        }
    }

    // 拖到画布上/下边缘自动滚动，支持跨可视区归组；
    // 滚动量会被 clampScrollForSource 夹住，避免源条目滑出手势节点被回收
    LaunchedEffect(dragSession != null) {
        if (dragSession == null) return@LaunchedEffect
        var lastFrame = 0L
        while (true) {
            val now = withFrameNanos { it }
            val dtMs = if (lastFrame == 0L) {
                16f
            } else {
                ((now - lastFrame) / 1_000_000f).coerceIn(0f, 48f)
            }
            lastFrame = now
            if (!dragMoved) continue
            val canvas = canvasBounds ?: continue
            val session = dragSession ?: break
            val edge = with(density) { FreePlacement.EDGE_ZONE_DP.dp.toPx() }
            val speed = with(density) { FreePlacement.EDGE_SPEED_DP.dp.toPx() }
            val localY = dragFinger.y - canvas.top
            val nearTop = localY < edge
            val nearBottom = localY > canvas.height - edge
            val ratio = when {
                nearTop -> (1f - localY / edge).coerceIn(0f, 1f)
                nearBottom -> ((localY - (canvas.height - edge)) / edge).coerceIn(0f, 1f)
                else -> 0f
            }
            if (ratio <= 0f) continue
            val raw = speed * ratio * (dtMs / 16f)
            val allowed = clampScrollForSource(if (nearTop) -raw else raw, session.key)
            if (allowed == 0f) continue
            val scrollable: ScrollableState =
                if (layout == ExplorerLayout.GRID) gridScroll else listState
            scrollable.scrollBy(allowed)
            dropTargetId = computeDropTarget()
        }
    }

    // ---------------- 界面 ----------------
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .onGloballyPositioned { explorerBounds = it.boundsInRoot() }
    ) {
        // 窄屏（手机竖屏）强制收成 56dp 图标栏，避免挤占主区
        val wide = maxWidth >= 560.dp
        val sidebarLabels = sidebarExpanded && wide

        Row(modifier = Modifier.fillMaxSize()) {
            // ---------------- 左侧位置栏（Finder 侧栏的 M3 版） ----------------
            ExplorerSidebar(
                allFolders = allFolders,
                currentFolderId = currentFolderId,
                onlyFavorites = onlyFavorites,
                expanded = sidebarLabels,
                onToggle = if (wide) ({ sidebarExpanded = !sidebarExpanded }) else null,
                onNavigate = { id ->
                    onlyFavorites = false
                    currentFolderId = id
                },
                onFavorites = { onlyFavorites = true }
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            ) {
                // ---------------- 路径栏 / 多选批量操作栏 ----------------
                if (selectionMode) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 6.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(onClick = {
                                selectionMode = false
                                selectedEntryKeys.clear()
                            }) {
                                Icon(Icons.Default.Close, contentDescription = "退出多选")
                            }
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "已选 ${selectedEntryKeys.size} 项",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(
                                onClick = {
                                    val all = entries.isNotEmpty() && entries.all { selectedEntryKeys.containsKey(entryKey(it)) }
                                    if (all) {
                                        selectedEntryKeys.clear()
                                    } else {
                                        entries.forEach { selectedEntryKeys[entryKey(it)] = true }
                                    }
                                }
                            ) {
                                val all = entries.isNotEmpty() && entries.all { selectedEntryKeys.containsKey(entryKey(it)) }
                                Text(if (all) "取消全选" else "全选")
                            }
                            if (selectedEntryKeys.isNotEmpty()) {
                                IconButton(onClick = {
                                    scope.launch {
                                        val selectedEntries = entries.filter { selectedEntryKeys.containsKey(entryKey(it)) }
                                        val targetFolders = selectedEntries.filterIsInstance<ExplorerEntry.FolderEntry>().map { it.folder }
                                        val targetConversations = selectedEntries.filterIsInstance<ExplorerEntry.ConversationEntry>().map { it.conversation }
                                        if (targetFolders.isEmpty() && targetConversations.isEmpty()) return@launch

                                        snackText = "正在批量打包 ${selectedEntries.size} 项为 ZIP…"
                                        val file = ZipExporter.exportToZip(
                                            context = context,
                                            conversationDao = viewModel.conversationDaoInstance,
                                            targetFolders = targetFolders,
                                            targetConversations = targetConversations,
                                            allFolders = allFolders,
                                            allConversations = allConversations,
                                            zipNamePrefix = "TalkTree_BatchExport"
                                        )
                                        ZipExporter.shareFile(context, file, title = "分享批量打包 ZIP")
                                        snackText = "已打包 ${selectedEntries.size} 项并调起分享"
                                    }
                                }) {
                                    Icon(
                                        Icons.Outlined.Archive,
                                        contentDescription = "批量打包导出 (ZIP)",
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                                IconButton(onClick = { batchDeleteConfirm = true }) {
                                    Icon(
                                        Icons.Filled.Delete,
                                        contentDescription = "批量删除",
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }
                    }
                } else {
                    BreadcrumbBar(
                        breadcrumb = breadcrumb,
                        onlyFavorites = onlyFavorites,
                        onNavigate = {
                            onlyFavorites = false
                            currentFolderId = it
                        }
                    )
                }

                // ---------------- 视图切换 / 收藏筛选 / 排序 / 恢复默认 ----------------
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SingleChoiceSegmentedButtonRow {
                        ExplorerLayout.entries.forEachIndexed { index, mode ->
                            SegmentedButton(
                                selected = layout == mode,
                                onClick = { layout = mode },
                                shape = SegmentedButtonDefaults.itemShape(
                                    index = index,
                                    count = ExplorerLayout.entries.size
                                ),
                                icon = {}
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(
                                        imageVector = if (mode == ExplorerLayout.LIST) {
                                            Icons.Default.ViewList
                                        } else {
                                            Icons.Default.Folder
                                        },
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Text(
                                        text = if (mode == ExplorerLayout.LIST) "列表" else "图标",
                                        style = MaterialTheme.typography.labelMedium,
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }

                    FilterChip(
                        selected = !onlyFavorites,
                        onClick = { onlyFavorites = false },
                        label = {
                            Text(
                                text = "全部",
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1
                            )
                        }
                    )

                    FilterChip(
                        selected = onlyFavorites,
                        onClick = { onlyFavorites = !onlyFavorites },
                        leadingIcon = {
                            Icon(
                                imageVector = if (onlyFavorites) Icons.Default.Star else Icons.Default.StarBorder,
                                contentDescription = null,
                                tint = if (onlyFavorites) FavoriteAmber else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(FilterChipDefaults.IconSize)
                            )
                        },
                        label = {
                            Text(
                                text = "收藏",
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1
                            )
                        }
                    )

                    // 排序：按名称 / 按修改时间
                    Box {
                        FilterChip(
                            selected = true,
                            onClick = { sortMenuExpanded = true },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Sort,
                                    contentDescription = null,
                                    modifier = Modifier.size(FilterChipDefaults.IconSize)
                                )
                            },
                            label = {
                                Text(
                                    text = sortMode.label,
                                    style = MaterialTheme.typography.labelMedium,
                                    maxLines = 1
                                )
                            }
                        )
                        DropdownMenu(
                            expanded = sortMenuExpanded,
                            onDismissRequest = { sortMenuExpanded = false }
                        ) {
                            ExplorerSort.entries.forEach { mode ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = mode.label,
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                    },
                                    trailingIcon = {
                                        if (sortMode == mode) {
                                            Icon(
                                                imageVector = Icons.Default.Check,
                                                contentDescription = "当前排序",
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    },
                                    onClick = {
                                        sortMode = mode
                                        sortMenuExpanded = false
                                    }
                                )
                            }
                            if (sortMode == ExplorerSort.MODIFIED) {
                                // 如实注明：Folder 表没有修改时间字段
                                Text(
                                    text = "文件夹没有修改时间字段，按创建时间排列",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.outline,
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                                )
                            }
                        }
                    }

                    // 恢复默认排列：清空自由摆放坐标 JSON
                    if (layout == ExplorerLayout.GRID) {
                        IconButton(
                            onClick = {
                                freePositions.value = emptyMap()
                                prefs.clear()
                            },
                            modifier = Modifier.size(34.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Restore,
                                contentDescription = "恢复默认排列",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(4.dp))

                    Text(
                        text = if (sortMode == ExplorerSort.MODIFIED) {
                            "${visibleEntries.size} 项 · 文件夹按创建时间"
                        } else {
                            "${visibleEntries.size} 项"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // ---------------- 搜对话（标题 + 正文全文） ----------------
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = explorerQuery,
                        onValueChange = { explorerQuery = it },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = MaterialTheme.typography.bodySmall,
                        shape = RoundedCornerShape(18.dp),
                        placeholder = {
                            Text("搜对话（标题与正文）", style = MaterialTheme.typography.bodySmall)
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                        },
                        trailingIcon = {
                            if (explorerQuery.isNotEmpty()) {
                                IconButton(onClick = { explorerQuery = "" }) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "清空搜索",
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    )
                }

                // ---------------- 内容 ----------------
                when {
                    visibleEntries.isEmpty() -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (explorerQuery.isBlank()) {
                                    "这里还没有内容"
                                } else {
                                    "没有匹配「${explorerQuery.trim()}」的对话"
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                    }

                    layout == ExplorerLayout.LIST -> {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(bottom = 88.dp)
                                .onGloballyPositioned { canvasBounds = it.boundsInRoot() },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            items(visibleEntries, key = { entryKey(it) }) { entry ->
                                val key = entryKey(entry)
                                val folderId = (entry as? ExplorerEntry.FolderEntry)?.folder?.id
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .onGloballyPositioned { itemBounds[key] = it.boundsInRoot() }
                                        .clickable {
                                            if (selectionMode) {
                                                if (selectedEntryKeys.containsKey(key)) {
                                                    selectedEntryKeys.remove(key)
                                                    if (selectedEntryKeys.isEmpty()) selectionMode = false
                                                } else {
                                                    selectedEntryKeys[key] = true
                                                }
                                            } else {
                                                when (entry) {
                                                    is ExplorerEntry.FolderEntry -> {
                                                        onlyFavorites = false
                                                        currentFolderId = entry.folder.id
                                                    }
                                                    is ExplorerEntry.ConversationEntry -> {
                                                        onOpenConversation(entry.conversation.id)
                                                    }
                                                }
                                            }
                                        }
                                        .explorerDragGestures(
                                            key = key,
                                            thresholdPx = dragThresholdPx,
                                            onStart = { offset ->
                                                if (!beginDrag(key, offset, entry)) {
                                                    overflowTarget = entry
                                                }
                                            },
                                            onMove = { amount, cumulative ->
                                                dragFinger += amount
                                                dragMoved =
                                                    cumulative.getDistance() >= dragThresholdPx
                                                dropTargetId = computeDropTarget()
                                            },
                                            onEnd = { moved -> applyDrop(dragSession, moved) },
                                            onCancel = { clearDrag() }
                                        )
                                ) {
                                    val isSelected = selectedEntryKeys.containsKey(key)
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        if (selectionMode) {
                                            Icon(
                                                imageVector = if (isSelected) Icons.Filled.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                                                contentDescription = null,
                                                tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                                modifier = Modifier
                                                    .padding(start = 4.dp, end = 8.dp)
                                                    .size(22.dp)
                                            )
                                        }

                                        Box(modifier = Modifier.weight(1f)) {
                                            when (entry) {
                                                is ExplorerEntry.FolderEntry -> FolderRow(
                                                    entry = entry,
                                                    dropTarget = folderId != null && folderId == dropTargetId,
                                                    isDimmed = dragSession?.key == key,
                                                    onClick = {
                                                        if (selectionMode) {
                                                            if (isSelected) {
                                                                selectedEntryKeys.remove(key)
                                                                if (selectedEntryKeys.isEmpty()) selectionMode = false
                                                            } else {
                                                                selectedEntryKeys[key] = true
                                                            }
                                                        } else {
                                                            onlyFavorites = false
                                                            currentFolderId = entry.folder.id
                                                        }
                                                    },
                                                    onTogglePin = {
                                                        viewModel.toggleFolderPinned(entry.folder.id)
                                                    },
                                                    onToggleFavorite = {
                                                        viewModel.toggleFolderFavorite(entry.folder.id)
                                                    },
                                                    onRename = { renameTarget = entry.folder },
                                                    onMove = { moveTarget = entry.folder },
                                                    onExportZip = { exportSingleEntryToZip(entry.folder) },
                                                    onDelete = { deleteTarget = entry.folder }
                                                )

                                                is ExplorerEntry.ConversationEntry -> ConversationRow(
                                                    entry = entry,
                                                    isDimmed = dragSession?.key == key,
                                                    contentHit = explorerQuery.isNotBlank() &&
                                                        entry.conversation.id in contentHitIds &&
                                                        !entry.conversation.title.contains(
                                                            explorerQuery.trim(),
                                                            ignoreCase = true
                                                        ),
                                                    onClick = {
                                                        if (selectionMode) {
                                                            if (isSelected) {
                                                                selectedEntryKeys.remove(key)
                                                                if (selectedEntryKeys.isEmpty()) selectionMode = false
                                                            } else {
                                                                selectedEntryKeys[key] = true
                                                            }
                                                        } else {
                                                            onOpenConversation(entry.conversation.id)
                                                        }
                                                    },
                                                    onTogglePin = {
                                                        viewModel.toggleConversationPinned(entry.conversation.id)
                                                    },
                                                    onToggleFavorite = {
                                                        viewModel.toggleConversationFavorite(entry.conversation.id)
                                                    },
                                                    onRename = { renameTarget = entry.conversation },
                                                    onMove = { moveTarget = entry.conversation },
                                                    onCopy = {
                                                        viewModel.duplicateConversation(entry.conversation.id)
                                                    },
                                                    onExportZip = { exportSingleEntryToZip(entry.conversation) },
                                                    onExportTalkTree = { exportConversationTalkTree(entry.conversation) },
                                                    onDelete = { deleteTarget = entry.conversation }
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    else -> {
                        // ============ 图标视图：安卓桌面式自由摆放画布 ============
                        val canvasWidthDp = canvasBounds?.let { it.width / density.density } ?: 0f
                        val columns = FreePlacement.columnCount(canvasWidthDp)
                        val positions = remember(visibleEntries, freePositions.value, columns) {
                            visibleEntries.mapIndexed { index, entry ->
                                FreePlacement.positionOf(
                                    index = index,
                                    itemId = entryId(entry),
                                    columns = columns,
                                    custom = freePositions.value
                                )
                            }
                        }
                        val contentHeightDp = remember(positions) {
                            FreePlacement.contentHeightDp(positions)
                        }

                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(bottom = 88.dp)
                                .onGloballyPositioned { canvasBounds = it.boundsInRoot() }
                                .verticalScroll(gridScroll)
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(contentHeightDp.dp)
                                    .pointerInput(visibleEntries, positions) {
                                        detectDragGesturesAfterLongPress(
                                            onDragStart = { offset ->
                                                marqueeStart = offset
                                                marqueeCurrent = offset
                                                selectionMode = true
                                            },
                                            onDrag = { change, dragAmount ->
                                                change.consume()
                                                val curr = (marqueeCurrent ?: marqueeStart ?: Offset.Zero) + dragAmount
                                                marqueeCurrent = curr
                                                val s = marqueeStart ?: return@detectDragGesturesAfterLongPress
                                                val box = Rect(
                                                    min(s.x, curr.x),
                                                    min(s.y, curr.y),
                                                    max(s.x, curr.x),
                                                    max(s.y, curr.y)
                                                )
                                                visibleEntries.forEachIndexed { i, e ->
                                                    val p = positions[i]
                                                    val itemRect = Rect(
                                                        p.x * density.density,
                                                        p.y * density.density,
                                                        (p.x + FreePlacement.CELL_W_DP) * density.density,
                                                        (p.y + FreePlacement.CELL_H_DP) * density.density
                                                    )
                                                    if (itemRect.overlaps(box)) {
                                                        selectedEntryKeys[entryKey(e)] = true
                                                    }
                                                }
                                            },
                                            onDragEnd = {
                                                marqueeStart = null
                                                marqueeCurrent = null
                                                if (selectedEntryKeys.isEmpty()) {
                                                    selectionMode = false
                                                }
                                            },
                                            onDragCancel = {
                                                marqueeStart = null
                                                marqueeCurrent = null
                                            }
                                        )
                                    }
                            ) {
                                // 可见窗口虚拟化：只组合落在可视区 ± 一屏内的图标。
                                // 位置仍按全量算（摆放坐标系不能变），只是不渲染窗口外的，
                                // 避免条目很多时一次性组合出上百个节点。
                                val viewportHeightPx = canvasBounds?.height ?: 0f
                                val scrollPx = gridScroll.value.toFloat()
                                val cellHeightPx = FreePlacement.CELL_H_DP * density.density
                                val visibleIndexes = positions.indices.filter { i ->
                                    val top = positions[i].y * density.density
                                    val bottom = top + cellHeightPx
                                    bottom >= scrollPx - viewportHeightPx &&
                                        top <= scrollPx + viewportHeightPx * 2f
                                }
                                visibleIndexes.forEach { index ->
                                    val entry = visibleEntries[index]
                                    val key = entryKey(entry)
                                    val position = positions[index]
                                    val folderId =
                                        (entry as? ExplorerEntry.FolderEntry)?.folder?.id
                                    val isTarget =
                                        folderId != null && folderId == dropTargetId
                                    val isSelected = selectedEntryKeys.containsKey(key)
                                    Box(
                                        modifier = Modifier
                                            .offset(x = position.x.dp, y = position.y.dp)
                                            .size(
                                                FreePlacement.CELL_W_DP.dp,
                                                FreePlacement.CELL_H_DP.dp
                                            )
                                            .onGloballyPositioned {
                                                itemBounds[key] = it.boundsInRoot()
                                            }
                                            .explorerDragGestures(
                                                key = key,
                                                thresholdPx = dragThresholdPx,
                                                onStart = { offset ->
                                                    if (!beginDrag(key, offset, entry)) {
                                                        overflowTarget = entry
                                                    }
                                                },
                                                onMove = { amount, cumulative ->
                                                    dragFinger += amount
                                                    dragMoved =
                                                        cumulative.getDistance() >= dragThresholdPx
                                                    dropTargetId = computeDropTarget()
                                                },
                                                onEnd = { moved -> applyDrop(dragSession, moved) },
                                                onCancel = { clearDrag() }
                                            )
                                            .clip(RoundedCornerShape(20.dp))
                                            .background(
                                                color = when {
                                                    isTarget -> MaterialTheme.colorScheme.primaryContainer
                                                    selectionMode && isSelected -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                                                    else -> Color.Transparent
                                                },
                                                shape = RoundedCornerShape(20.dp)
                                            )
                                            .then(
                                                when {
                                                    isTarget -> Modifier.border(
                                                        width = 2.dp,
                                                        color = MaterialTheme.colorScheme.primary,
                                                        shape = RoundedCornerShape(20.dp)
                                                    )
                                                    selectionMode && isSelected -> Modifier.border(
                                                        width = 2.dp,
                                                        color = MaterialTheme.colorScheme.primary,
                                                        shape = RoundedCornerShape(20.dp)
                                                    )
                                                    else -> Modifier
                                                }
                                            )
                                            .clickable {
                                                if (selectionMode) {
                                                    if (isSelected) {
                                                        selectedEntryKeys.remove(key)
                                                        if (selectedEntryKeys.isEmpty()) selectionMode = false
                                                    } else {
                                                        selectedEntryKeys[key] = true
                                                    }
                                                } else {
                                                    when (entry) {
                                                        is ExplorerEntry.FolderEntry -> {
                                                            onlyFavorites = false
                                                            currentFolderId = entry.folder.id
                                                        }

                                                        is ExplorerEntry.ConversationEntry ->
                                                            onOpenConversation(entry.conversation.id)
                                                    }
                                                }
                                            }
                                    ) {
                                        ExplorerIconTile(
                                            entry = entry,
                                            isDropTarget = isTarget,
                                            isSource = dragSession?.key == key,
                                            modifier = Modifier.fillMaxSize()
                                        )

                                        if (selectionMode) {
                                            Icon(
                                                imageVector = if (isSelected) Icons.Filled.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                                                contentDescription = null,
                                                tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                                modifier = Modifier
                                                    .align(Alignment.TopEnd)
                                                    .padding(6.dp)
                                                    .size(20.dp)
                                            )
                                        }
                                    }
                                }

                                // ---------------- 图标视图框选半透明虚线框 ----------------
                                if (marqueeStart != null && marqueeCurrent != null) {
                                    val s = marqueeStart!!
                                    val c = marqueeCurrent!!
                                    val primaryColor = MaterialTheme.colorScheme.primary
                                    Canvas(modifier = Modifier.fillMaxSize()) {
                                        val l = min(s.x, c.x)
                                        val t = min(s.y, c.y)
                                        val w = kotlin.math.abs(s.x - c.x)
                                        val h = kotlin.math.abs(s.y - c.y)
                                        drawRect(
                                            color = primaryColor.copy(alpha = 0.16f),
                                            topLeft = Offset(l, t),
                                            size = Size(w, h)
                                        )
                                        drawRect(
                                            color = primaryColor,
                                            topLeft = Offset(l, t),
                                            size = Size(w, h),
                                            style = Stroke(width = 1.5.dp.toPx())
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // ---------------- 废纸篓组件（支持单个或多选拖入删除） ----------------
        val isTrashHovered = dropTargetId == TRASH_TARGET_ID
        Surface(
            shape = CircleShape,
            color = if (isTrashHovered) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
            border = BorderStroke(
                if (isTrashHovered) 2.dp else 1.dp,
                if (isTrashHovered) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outlineVariant
            ),
            shadowElevation = if (isTrashHovered) 8.dp else 3.dp,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(bottom = 20.dp, end = 92.dp)
                .size(56.dp)
                .onGloballyPositioned { trashBinBounds = it.boundsInRoot() }
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                Icon(
                    imageVector = if (isTrashHovered) Icons.Filled.Delete else Icons.Outlined.Delete,
                    contentDescription = "废纸篓：拖入删除",
                    tint = if (isTrashHovered) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp)
                )
            }
        }

        // ---------------- 新建 ----------------
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(20.dp)
        ) {
            FloatingActionButton(
                onClick = { fabMenuExpanded = true },
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
            ) {
                Icon(Icons.Default.Add, contentDescription = "新建")
            }
            DropdownMenu(
                expanded = fabMenuExpanded,
                onDismissRequest = { fabMenuExpanded = false }
            ) {
                DropdownMenuItem(
                    text = { Text("新建文件夹", style = MaterialTheme.typography.bodyMedium) },
                    leadingIcon = {
                        Icon(
                            Icons.Default.CreateNewFolder,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    },
                    onClick = {
                        fabMenuExpanded = false
                        showNewFolderDialog = true
                    }
                )
                DropdownMenuItem(
                    text = { Text("新建对话", style = MaterialTheme.typography.bodyMedium) },
                    leadingIcon = {
                        Icon(
                            Icons.Default.AddComment,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    },
                    onClick = {
                        fabMenuExpanded = false
                        showNewConversationDialog = true
                    }
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                DropdownMenuItem(
                    text = { Text("导入 .talktree 对话", style = MaterialTheme.typography.bodyMedium) },
                    leadingIcon = {
                        Icon(
                            Icons.Outlined.FileUpload,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    },
                    onClick = {
                        fabMenuExpanded = false
                        talkTreeImportLauncher.launch(arrayOf("*/*"))
                    }
                )
            }
        }

        // ---------------- 拖起浮层：跟手 + 轻微放大 + 阴影 ----------------
        dragSession?.let { session ->
            val base = explorerBounds
            val canvas = canvasBounds
            if (base != null && canvas != null) {
                val width = session.widthPx.toFloat()
                val height = session.heightPx.toFloat()
                // 边界保护：浮层始终夹在画布可视区内，不盖路径栏/工具栏，也不越出屏幕
                val minLeft = canvas.left - base.left
                val minTop = canvas.top - base.top
                val maxLeft = minLeft + (canvas.width - width)
                val maxTop = minTop + (canvas.height - height)
                val left = (dragFinger.x - session.grab.x - base.left)
                    .coerceIn(minLeft, maxOf(maxLeft, minLeft))
                val top = (dragFinger.y - session.grab.y - base.top)
                    .coerceIn(minTop, maxOf(maxTop, minTop))
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .offset { IntOffset(left.roundToInt(), top.roundToInt()) }
                ) {
                    LiftedEntryContent(
                        entry = session.entry,
                        widthPx = session.widthPx,
                        isGrid = layout == ExplorerLayout.GRID
                    )
                }
            }
        }

        // ---------------- 长按未移动 → 「更多」菜单 ----------------
        overflowTarget?.let { target ->
            val base = explorerBounds
            val rect = itemBounds[entryKey(target)]
            val anchorX = ((rect?.center?.x ?: dragFinger.x) - (base?.left ?: 0f)).roundToInt()
            val anchorY = ((rect?.center?.y ?: dragFinger.y) - (base?.top ?: 0f)).roundToInt()
            Box(
                modifier = Modifier
                    .offset { IntOffset(anchorX, anchorY) }
            ) {
                DropdownMenu(
                    expanded = true,
                    onDismissRequest = { overflowTarget = null }
                ) {
                    val folder = (target as? ExplorerEntry.FolderEntry)?.folder
                    val conversation =
                        (target as? ExplorerEntry.ConversationEntry)?.conversation
                    EntryOverflowMenuContent(
                        isPinned = folder?.isPinned ?: conversation?.isPinned ?: false,
                        isFavorite = folder?.isFavorite ?: conversation?.isFavorite ?: false,
                        onTogglePin = {
                            when {
                                folder != null -> viewModel.toggleFolderPinned(folder.id)
                                conversation != null ->
                                    viewModel.toggleConversationPinned(conversation.id)
                            }
                        },
                        onToggleFavorite = {
                            when {
                                folder != null -> viewModel.toggleFolderFavorite(folder.id)
                                conversation != null ->
                                    viewModel.toggleConversationFavorite(conversation.id)
                            }
                        },
                        onRename = {
                            renameTarget = when {
                                folder != null -> folder
                                conversation != null -> conversation
                                else -> null
                            }
                        },
                        onMove = {
                            moveTarget = when {
                                folder != null -> folder
                                conversation != null -> conversation
                                else -> null
                            }
                        },
                        onCopy = conversation?.let { conv ->
                            { viewModel.duplicateConversation(conv.id) }
                        },
                        onExportZip = {
                            overflowTarget?.let { exportSingleEntryToZip(it) }
                        },
                        onExportTalkTree = conversation?.let { conv ->
                            { exportConversationTalkTree(conv) }
                        },
                        onDelete = {
                            deleteTarget = when {
                                folder != null -> folder
                                conversation != null -> conversation
                                else -> null
                            }
                        },
                        onDismiss = { overflowTarget = null }
                    )
                }
            }
        }

        // ---------------- 归组结果反馈 ----------------
        snackText?.let { message ->
            Surface(
                color = MaterialTheme.colorScheme.inverseSurface,
                contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                shape = RoundedCornerShape(8.dp),
                shadowElevation = 4.dp,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 96.dp)
                    .padding(horizontal = 24.dp)
            ) {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                )
            }
        }
    }

    // ---------------- 对话框 ----------------

    if (batchDeleteConfirm) {
        val count = selectedEntryKeys.size
        AlertDialog(
            onDismissRequest = { batchDeleteConfirm = false },
            title = { Text("移入废纸篓", fontWeight = FontWeight.Bold) },
            text = { Text("确定要将选中的 $count 个项目永久删除吗？该操作不可撤销。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        val keys = selectedEntryKeys.keys.toList()
                        keys.forEach { key ->
                            entries.find { entryKey(it) == key }?.let { entry ->
                                when (entry) {
                                    is ExplorerEntry.FolderEntry -> viewModel.deleteFolder(entry.folder.id)
                                    is ExplorerEntry.ConversationEntry -> viewModel.deleteConversation(entry.conversation.id)
                                }
                            }
                        }
                        selectedEntryKeys.clear()
                        selectionMode = false
                        batchDeleteConfirm = false
                        snackText = "已将 $count 项移入废纸篓"
                    }
                ) {
                    Text("全部删除", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { batchDeleteConfirm = false }) { Text("取消") }
            }
        )
    }

    if (showNewFolderDialog) {
        TextInputDialog(
            title = "新建文件夹",
            initial = "",
            placeholder = "例如：考研政治",
            onConfirm = { name ->
                viewModel.createFolder(currentFolderId, name)
                showNewFolderDialog = false
            },
            onDismiss = { showNewFolderDialog = false }
        )
    }

    if (showNewConversationDialog) {
        TextInputDialog(
            title = "新建对话",
            initial = "",
            placeholder = "例如：马原辩证法讨论",
            onConfirm = { name ->
                viewModel.createConversation(currentFolderId, name)
                showNewConversationDialog = false
                // 不跳走的话，用户会停在文件列表，而活动会话已经被静默换掉了
                onConversationCreated()
            },
            onDismiss = { showNewConversationDialog = false }
        )
    }

    renameTarget?.let { target ->
        val initial = when (target) {
            is Folder -> target.name
            is Conversation -> target.title
            else -> ""
        }
        TextInputDialog(
            title = "重命名",
            initial = initial,
            placeholder = "新名称",
            onConfirm = { name ->
                when (target) {
                    is Folder -> viewModel.renameFolder(target.id, name)
                    is Conversation -> viewModel.renameConversation(target.id, name)
                }
                renameTarget = null
            },
            onDismiss = { renameTarget = null }
        )
    }

    moveTarget?.let { target ->
        MoveTargetDialog(
            allFolders = allFolders,
            movingFolderId = (target as? Folder)?.id,
            title = when (target) {
                is Folder -> "移动文件夹「${target.name}」"
                is Conversation -> "移动对话「${target.title}」"
                else -> "移动到"
            },
            onConfirm = { targetFolderId ->
                when (target) {
                    is Folder -> viewModel.moveFolder(target.id, targetFolderId)
                    is Conversation -> viewModel.moveConversation(target.id, targetFolderId)
                }
                moveTarget = null
            },
            onDismiss = { moveTarget = null }
        )
    }

    deleteTarget?.let { target ->
        val label = when (target) {
            is Folder -> "文件夹「${target.name}」"
            is Conversation -> "对话「${target.title}」"
            else -> "该项"
        }
        val detail = when (target) {
            is Folder -> "其中的子文件夹会一并删除；里面的对话不会被删，会回到根目录。"
            is Conversation -> "该对话及其全部分支、消息都会被永久删除，不可撤销。"
            else -> ""
        }
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除$label？", style = MaterialTheme.typography.titleMedium) },
            text = { Text(detail, style = MaterialTheme.typography.bodyMedium) },
            confirmButton = {
                TextButton(onClick = {
                    when (target) {
                        is Folder -> viewModel.deleteFolder(target.id)
                        is Conversation -> viewModel.deleteConversation(target.id)
                    }
                    deleteTarget = null
                }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text("取消", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        )
    }
}

// ============================================================================
// 长按拖动手势：位移超过阈值 → 拖动归组/摆放；未移动 → 交给调用方弹菜单
// ============================================================================

/**
 * 给条目套上「长按拖起」手势。
 *
 * 与既有手势的分工：
 * - [androidx.compose.foundation.clickable] 的点按不受影响（长按本身就会取消点按）；
 * - 长按到达即进入拖起态；累计位移超过 [thresholdPx] 才算拖动，
 *   否则松手时由 [onEnd] 以 `moved = false` 上报 → 调用方弹出「更多」菜单。
 */
private fun Modifier.explorerDragGestures(
    key: Any,
    thresholdPx: Float,
    onStart: (Offset) -> Unit,
    onMove: (amount: Offset, cumulative: Offset) -> Unit,
    onEnd: (moved: Boolean) -> Unit,
    onCancel: () -> Unit
): Modifier = this.pointerInput(key, thresholdPx) {
    var cumulative = Offset.Zero
    detectDragGesturesAfterLongPress(
        onDragStart = { offset ->
            cumulative = Offset.Zero
            onStart(offset)
        },
        onDrag = { _, amount ->
            cumulative += amount
            onMove(amount, cumulative)
        },
        onDragEnd = { onEnd(cumulative.getDistance() >= thresholdPx) },
        onDragCancel = { onCancel() }
    )
}

// ============================================================================
// 左侧位置栏（Finder 侧栏的 Material 3 版：只列真实存在的位置）
// ============================================================================

@Composable
private fun ExplorerSidebar(
    allFolders: List<Folder>,
    currentFolderId: String?,
    onlyFavorites: Boolean,
    expanded: Boolean,
    /** 窄屏传 null：不给折叠钮，避免展开后挤没主区。 */
    onToggle: (() -> Unit)?,
    onNavigate: (String?) -> Unit,
    onFavorites: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme

    // 顶层文件夹快捷入口（parentFolderId == null 即根目录直属，
    // 数据里没有"最近使用 / 标签"之类字段，就不虚构这些栏目）
    val topLevel = remember(allFolders) {
        allFolders
            .filter { it.parentFolderId == null }
            .sortedWith(
                compareByDescending<Folder> { it.isPinned }
                    .thenBy { it.sortOrder }
                    .thenBy { it.name }
            )
    }

    Surface(
        color = scheme.surfaceContainer,
        modifier = Modifier
            .width(if (expanded) 176.dp else 56.dp)
            .fillMaxHeight()
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (expanded) {
                    Text(
                        text = "位置",
                        style = MaterialTheme.typography.labelLarge,
                        color = scheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                } else {
                    Spacer(modifier = Modifier.weight(1f))
                }
                if (onToggle != null) {
                    IconButton(onClick = onToggle, modifier = Modifier.size(30.dp)) {
                        Icon(
                            imageVector = Icons.Default.ChevronRight,
                            contentDescription = if (expanded) "收起位置栏" else "展开位置栏",
                            tint = scheme.onSurfaceVariant,
                            modifier = Modifier
                                .size(18.dp)
                                .graphicsLayer {
                                    rotationZ = if (expanded) 180f else 0f
                                }
                        )
                    }
                }
            }

            SidebarRow(
                icon = Icons.AutoMirrored.Filled.Chat,
                label = "全部对话",
                selected = !onlyFavorites && currentFolderId == null,
                expanded = expanded,
                onClick = { onNavigate(null) }
            )
            SidebarRow(
                icon = if (onlyFavorites) Icons.Default.Star else Icons.Default.StarBorder,
                label = "收藏",
                selected = onlyFavorites,
                expanded = expanded,
                onClick = onFavorites
            )

            if (topLevel.isNotEmpty()) {
                HorizontalDivider(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
                topLevel.forEach { folder ->
                    SidebarRow(
                        icon = Icons.Default.Folder,
                        label = folder.name,
                        selected = !onlyFavorites && currentFolderId == folder.id,
                        expanded = expanded,
                        onClick = { onNavigate(folder.id) }
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun SidebarRow(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    expanded: Boolean,
    onClick: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 1.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (selected) scheme.primaryContainer else Color.Transparent
            )
            .clickable(onClick = onClick)
            .padding(horizontal = if (expanded) 10.dp else 18.dp, vertical = 9.dp)
            // 收起态没有文字，靠 contentDescription 保留可读性
            .semantics { contentDescription = label },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (selected) scheme.onPrimaryContainer else scheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )
        if (expanded) {
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = if (selected) scheme.onPrimaryContainer else scheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

// ============================================================================
// 列表行（ExplorerLayout.LIST）
// ============================================================================

@Composable
private fun FolderRow(
    entry: ExplorerEntry.FolderEntry,
    onClick: () -> Unit,
    onTogglePin: () -> Unit,
    onToggleFavorite: () -> Unit,
    onRename: () -> Unit,
    onMove: () -> Unit,
    onExportZip: () -> Unit = {},
    onDelete: () -> Unit,
    /** 正被拖着的条目悬在其上：描边 + 底色变化，表示可放入。 */
    dropTarget: Boolean = false,
    /** 源条目占位：半透明。 */
    isDimmed: Boolean = false
) {
    val scheme = MaterialTheme.colorScheme
    val container = when {
        dropTarget -> scheme.primaryContainer
        isDimmed -> scheme.surfaceContainerLow.copy(alpha = 0.4f)
        entry.folder.isPinned -> scheme.surfaceContainer
        else -> scheme.surfaceContainerLow
    }
    val border = when {
        dropTarget -> BorderStroke(1.5.dp, scheme.primary)
        entry.folder.isPinned -> BorderStroke(1.dp, scheme.primary.copy(alpha = 0.28f))
        else -> null
    }
    Surface(
        color = container,
        shape = MaterialTheme.shapes.medium,
        border = border,
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (isDimmed) 0.5f else 1f)
    ) {
        Row(
            modifier = Modifier
                .padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Folder,
                contentDescription = null,
                tint = scheme.primary,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (entry.folder.isPinned) {
                        Icon(
                            imageVector = Icons.Default.PushPin,
                            contentDescription = "已置顶",
                            tint = scheme.primary,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                    Text(
                        text = entry.folder.name,
                        style = MaterialTheme.typography.bodyLarge,
                        color = scheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    text = "${entry.subFolderCount} 个文件夹 · ${entry.conversationCount} 个对话",
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant
                )
            }
            IconButton(
                onClick = onToggleFavorite,
                modifier = Modifier.size(34.dp)
            ) {
                Icon(
                    imageVector = if (entry.folder.isFavorite) {
                        Icons.Default.Star
                    } else {
                        Icons.Default.StarBorder
                    },
                    contentDescription = if (entry.folder.isFavorite) "取消收藏" else "收藏",
                    tint = if (entry.folder.isFavorite) FavoriteAmber else scheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
            EntryOverflowMenu(
                isPinned = entry.folder.isPinned,
                isFavorite = entry.folder.isFavorite,
                onTogglePin = onTogglePin,
                onToggleFavorite = onToggleFavorite,
                onRename = onRename,
                onMove = onMove,
                onExportZip = onExportZip,
                onDelete = onDelete
            )
        }
    }
}

@Composable
private fun ConversationRow(
    entry: ExplorerEntry.ConversationEntry,
    onClick: () -> Unit,
    onTogglePin: () -> Unit,
    onToggleFavorite: () -> Unit,
    onRename: () -> Unit,
    onMove: () -> Unit,
    onCopy: () -> Unit,
    onExportZip: () -> Unit = {},
    onExportTalkTree: () -> Unit = {},
    onDelete: () -> Unit,
    isDimmed: Boolean = false,
    /** 搜索命中来自**正文**（标题没命中）：行内给一个小标记，避免用户困惑。 */
    contentHit: Boolean = false
) {
    val scheme = MaterialTheme.colorScheme
    val container = when {
        isDimmed -> scheme.surfaceContainerLow.copy(alpha = 0.4f)
        entry.conversation.isPinned -> scheme.surfaceContainer
        else -> scheme.surfaceContainerLow
    }
    Surface(
        color = container,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (isDimmed) 0.5f else 1f)
    ) {
        Row(
            modifier = Modifier
                .padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Chat,
                contentDescription = null,
                tint = scheme.tertiary,
                modifier = Modifier.size(22.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (entry.conversation.isPinned) {
                        Icon(
                            imageVector = Icons.Default.PushPin,
                            contentDescription = "已置顶",
                            tint = scheme.primary,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                    Text(
                        text = entry.conversation.title,
                        style = MaterialTheme.typography.bodyLarge,
                        color = scheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (contentHit) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = scheme.tertiaryContainer
                        ) {
                            Text(
                                text = "正文命中",
                                style = MaterialTheme.typography.labelSmall,
                                color = scheme.onTertiaryContainer,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                            )
                        }
                    }
                }
                Text(
                    text = buildSourceLine(entry.conversation, entry.folderPath),
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(
                onClick = onToggleFavorite,
                modifier = Modifier.size(34.dp)
            ) {
                Icon(
                    imageVector = if (entry.conversation.isFavorite) {
                        Icons.Default.Star
                    } else {
                        Icons.Default.StarBorder
                    },
                    contentDescription = if (entry.conversation.isFavorite) "取消收藏" else "收藏",
                    tint = if (entry.conversation.isFavorite) FavoriteAmber else scheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
            EntryOverflowMenu(
                isPinned = entry.conversation.isPinned,
                isFavorite = entry.conversation.isFavorite,
                onTogglePin = onTogglePin,
                onToggleFavorite = onToggleFavorite,
                onRename = onRename,
                onMove = onMove,
                onCopy = onCopy,
                onExportZip = onExportZip,
                onExportTalkTree = onExportTalkTree,
                onDelete = onDelete
            )
        }
    }
}

// ============================================================================
// 图标视图（ExplorerLayout.GRID）：M3 矢量图标 + 自由摆放
// ============================================================================

/** 图标格子里的视觉规格：全部来自 Material 矢量，不使用位图/仿 macOS 拟物图形。 */
private data class EntryIconSpec(
    val imageVector: ImageVector,
    val container: Color,
    val tint: Color,
    val favorite: Boolean
)

@Composable
private fun entryIconSpec(entry: ExplorerEntry): EntryIconSpec {
    val scheme = MaterialTheme.colorScheme
    return when (entry) {
        is ExplorerEntry.FolderEntry -> {
            val hasContent = (entry.subFolderCount + entry.conversationCount) > 0
            if (hasContent) {
                // 有内容：primaryContainer 容器 + 线性文件夹
                EntryIconSpec(
                    imageVector = Icons.Outlined.Folder,
                    container = scheme.primaryContainer,
                    tint = scheme.onPrimaryContainer,
                    favorite = entry.folder.isFavorite
                )
            } else {
                // 空文件夹：浅色容器 + CreateNewFolder（一眼看出"里面还没有东西"）
                EntryIconSpec(
                    imageVector = Icons.Default.CreateNewFolder,
                    container = scheme.surfaceContainerHighest,
                    tint = scheme.outline,
                    favorite = entry.folder.isFavorite
                )
            }
        }

        is ExplorerEntry.ConversationEntry -> EntryIconSpec(
            imageVector = Icons.AutoMirrored.Filled.Chat,
            container = scheme.tertiaryContainer,
            tint = scheme.onTertiaryContainer,
            favorite = entry.conversation.isFavorite
        )
    }
}

/** 统一的 M3 条目图标：圆角容器 + 矢量图标 + 收藏角标。 */
@Composable
private fun EntryIcon(
    entry: ExplorerEntry,
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    val spec = entryIconSpec(entry)
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Surface(
            color = spec.container,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = spec.imageVector,
                    contentDescription = null,
                    tint = spec.tint,
                    modifier = Modifier.size(32.dp)
                )
            }
        }
        if (spec.favorite) {
            Surface(
                color = scheme.surface,
                shape = CircleShape,
                border = BorderStroke(1.dp, scheme.outlineVariant),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(2.dp)
                    .size(17.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.Star,
                        contentDescription = "已收藏",
                        tint = FavoriteAmber,
                        modifier = Modifier.size(11.dp)
                    )
                }
            }
        }
    }
}

/**
 * 图标视图的一格：图标 + 标题 + 徽章（子文件夹数 · 对话数）。
 *
 * 与安卓桌面一致，格子本身没有卡片底：只有作为落点时才亮起
 * primaryContainer 底色 + primary 描边；被拖起的源条目退成半透明占位。
 */
@Composable
private fun ExplorerIconTile(
    entry: ExplorerEntry,
    isDropTarget: Boolean,
    isSource: Boolean,
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .alpha(if (isSource) 0.25f else 1f)
                .padding(horizontal = 6.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            EntryIcon(entry = entry, modifier = Modifier.size(56.dp))

            Spacer(modifier = Modifier.height(6.dp))

            val pinned = when (entry) {
                is ExplorerEntry.FolderEntry -> entry.folder.isPinned
                is ExplorerEntry.ConversationEntry -> entry.conversation.isPinned
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (pinned) {
                    Icon(
                        imageVector = Icons.Default.PushPin,
                        contentDescription = "已置顶",
                        tint = scheme.primary,
                        modifier = Modifier
                            .padding(end = 3.dp)
                            .size(12.dp)
                    )
                }
                Text(
                    text = entryTitle(entry),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = scheme.onSurface,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            when (entry) {
                is ExplorerEntry.FolderEntry -> {
                    Spacer(modifier = Modifier.height(4.dp))
                    // 紧凑 M3 胶囊徽章：子文件夹数 · 对话数
                    Surface(
                        color = scheme.secondaryContainer.copy(alpha = 0.65f),
                        shape = RoundedCornerShape(percent = 50)
                    ) {
                        Text(
                            text = "${entry.subFolderCount} 文件夹 · ${entry.conversationCount} 对话",
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.onSecondaryContainer,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                        )
                    }
                }

                is ExplorerEntry.ConversationEntry -> {
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        text = buildSourceLine(entry.conversation, entry.folderPath),
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

/** 被拖起的条目浮层内容：轻微放大 + 阴影，全程跟手。 */
@Composable
private fun LiftedEntryContent(
    entry: ExplorerEntry,
    widthPx: Int,
    isGrid: Boolean,
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    if (isGrid) {
        Surface(
            color = scheme.surfaceContainerHigh,
            shape = RoundedCornerShape(20.dp),
            tonalElevation = 3.dp,
            shadowElevation = 8.dp,
            modifier = modifier.graphicsLayer(scaleX = 1.06f, scaleY = 1.06f)
        ) {
            Column(
                modifier = Modifier
                    .width(FreePlacement.CELL_W_DP.dp)
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                EntryIcon(entry = entry, modifier = Modifier.size(48.dp))
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = entryTitle(entry),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = scheme.onSurface,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    } else {
        val width = with(LocalDensity.current) { widthPx.toDp() }
        Surface(
            color = scheme.surfaceContainerHigh,
            shape = MaterialTheme.shapes.medium,
            tonalElevation = 3.dp,
            shadowElevation = 8.dp,
            modifier = modifier
                .width(width)
                .graphicsLayer(scaleX = 1.03f, scaleY = 1.03f)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                EntryIcon(entry = entry, modifier = Modifier.size(26.dp))
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = entryTitle(entry),
                    style = MaterialTheme.typography.bodyLarge,
                    color = scheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

// ============================================================================
// 更多菜单（行内按钮与长按浮层共用同一份菜单内容）
// ============================================================================

@Composable
private fun EntryOverflowMenu(
    isPinned: Boolean,
    isFavorite: Boolean,
    onTogglePin: () -> Unit,
    onToggleFavorite: () -> Unit,
    onRename: () -> Unit,
    onMove: () -> Unit,
    onCopy: (() -> Unit)? = null,
    onExportZip: (() -> Unit)? = null,
    onExportTalkTree: (() -> Unit)? = null,
    onDelete: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }, modifier = Modifier.size(30.dp)) {
            Icon(
                imageVector = Icons.Default.MoreVert,
                contentDescription = "更多",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            EntryOverflowMenuContent(
                isPinned = isPinned,
                isFavorite = isFavorite,
                onTogglePin = onTogglePin,
                onToggleFavorite = onToggleFavorite,
                onRename = onRename,
                onMove = onMove,
                onCopy = onCopy,
                onExportZip = onExportZip,
                onExportTalkTree = onExportTalkTree,
                onDelete = onDelete,
                onDismiss = { expanded = false }
            )
        }
    }
}

@Composable
private fun EntryOverflowMenuContent(
    isPinned: Boolean,
    isFavorite: Boolean,
    onTogglePin: () -> Unit,
    onToggleFavorite: () -> Unit,
    onRename: () -> Unit,
    onMove: () -> Unit,
    onCopy: (() -> Unit)? = null,
    onExportZip: (() -> Unit)? = null,
    onExportTalkTree: (() -> Unit)? = null,
    onDelete: () -> Unit,
    /** 关闭菜单；每个动作执行前由本函数统一回调。 */
    onDismiss: () -> Unit
) {
    DropdownMenuItem(
        text = {
            Text(
                text = if (isPinned) "取消置顶" else "置顶",
                style = MaterialTheme.typography.bodyMedium
            )
        },
        leadingIcon = {
            Icon(
                imageVector = Icons.Default.PushPin,
                contentDescription = null,
                tint = if (isPinned) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(17.dp)
            )
        },
        onClick = {
            onDismiss()
            onTogglePin()
        }
    )
    DropdownMenuItem(
        text = {
            Text(
                text = if (isFavorite) "取消收藏" else "收藏",
                style = MaterialTheme.typography.bodyMedium
            )
        },
        leadingIcon = {
            Icon(
                imageVector = if (isFavorite) Icons.Default.Star else Icons.Default.StarBorder,
                contentDescription = null,
                tint = if (isFavorite) FavoriteAmber else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(17.dp)
            )
        },
        onClick = {
            onDismiss()
            onToggleFavorite()
        }
    )
    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
    DropdownMenuItem(
        text = { Text("重命名", style = MaterialTheme.typography.bodyMedium) },
        leadingIcon = {
            Icon(
                Icons.Default.Edit,
                contentDescription = null,
                modifier = Modifier.size(17.dp)
            )
        },
        onClick = {
            onDismiss()
            onRename()
        }
    )
    DropdownMenuItem(
        text = { Text("移动到…", style = MaterialTheme.typography.bodyMedium) },
        leadingIcon = {
            Icon(
                Icons.Default.FolderOpen,
                contentDescription = null,
                modifier = Modifier.size(17.dp)
            )
        },
        onClick = {
            onDismiss()
            onMove()
        }
    )
    if (onCopy != null) {
        DropdownMenuItem(
            text = { Text("复制副本", style = MaterialTheme.typography.bodyMedium) },
            leadingIcon = {
                Icon(
                    Icons.Default.AddComment,
                    contentDescription = null,
                    modifier = Modifier.size(17.dp)
                )
            },
            onClick = {
                onDismiss()
                onCopy()
            }
        )
    }
    if (onExportZip != null) {
        DropdownMenuItem(
            text = { Text("压缩打包导出 (ZIP)", style = MaterialTheme.typography.bodyMedium) },
            leadingIcon = {
                Icon(
                    Icons.Outlined.Archive,
                    contentDescription = null,
                    modifier = Modifier.size(17.dp)
                )
            },
            onClick = {
                onDismiss()
                onExportZip()
            }
        )
    }
    if (onExportTalkTree != null) {
        DropdownMenuItem(
            text = { Text("导出为 .talktree 跨平台档案", style = MaterialTheme.typography.bodyMedium) },
            leadingIcon = {
                Icon(
                    Icons.Outlined.FileDownload,
                    contentDescription = null,
                    modifier = Modifier.size(17.dp)
                )
            },
            onClick = {
                onDismiss()
                onExportTalkTree()
            }
        )
    }
    DropdownMenuItem(
        text = {
            Text(
                "删除",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error
            )
        },
        leadingIcon = {
            Icon(
                Icons.Default.Delete,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(17.dp)
            )
        },
        onClick = {
            onDismiss()
            onDelete()
        }
    )
}

// ============================================================================
// 路径栏与空状态
// ============================================================================

@Composable
private fun BreadcrumbBar(
    breadcrumb: List<Folder>,
    onlyFavorites: Boolean,
    onNavigate: (String?) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        BreadcrumbSegment(
            label = "根目录",
            isCurrent = !onlyFavorites && breadcrumb.isEmpty(),
            onClick = { onNavigate(null) }
        )
        if (onlyFavorites) {
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(16.dp)
            )
            BreadcrumbSegment(
                label = "收藏夹",
                isCurrent = true,
                onClick = {}
            )
        } else {
            breadcrumb.forEachIndexed { index, folder ->
                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(16.dp)
                )
                BreadcrumbSegment(
                    label = folder.name,
                    isCurrent = index == breadcrumb.lastIndex,
                    onClick = { onNavigate(folder.id) }
                )
            }
        }
    }
}

@Composable
private fun BreadcrumbSegment(
    label: String,
    isCurrent: Boolean,
    onClick: () -> Unit
) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
        color = if (isCurrent) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        maxLines = 1,
        modifier = Modifier
            .clip(MaterialTheme.shapes.small)
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 4.dp)
    )
}

@Composable
private fun EmptyState(onlyFavorites: Boolean) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(bottom = 88.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = if (onlyFavorites) Icons.Default.StarBorder else Icons.Default.FolderOpen,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outlineVariant,
                modifier = Modifier.size(56.dp)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = if (onlyFavorites) "暂无收藏内容" else "这里还是空的",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = if (onlyFavorites) {
                    "点击文件夹或对话右侧的星标即可加入收藏"
                } else {
                    "点右下角 + 新建文件夹或对话"
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}

// ============================================================================
// 对话框
// ============================================================================

@Composable
private fun TextInputDialog(
    title: String,
    initial: String,
    placeholder: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = MaterialTheme.typography.titleMedium) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                placeholder = {
                    Text(placeholder, style = MaterialTheme.typography.bodyMedium)
                },
                shape = MaterialTheme.shapes.small,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = MaterialTheme.colorScheme.onSurface,
                    unfocusedTextColor = MaterialTheme.colorScheme.onSurface
                )
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(text) },
                enabled = text.isNotBlank()
            ) {
                Text("确定", color = MaterialTheme.colorScheme.primary)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    )
}

/**
 * 移动目标选择器：列出全部文件夹树（缩进表达层级）+ 根目录。
 *
 * 移动文件夹时会排除它自己及其子孙 —— 否则会形成环，
 * 之后路径计算会无限递归。
 */
@Composable
private fun MoveTargetDialog(
    allFolders: List<Folder>,
    movingFolderId: String?,
    title: String,
    onConfirm: (String?) -> Unit,
    onDismiss: () -> Unit
) {
    val blocked = remember(allFolders, movingFolderId) {
        if (movingFolderId == null) emptySet()
        else collectSubtreeIds(allFolders, movingFolderId)
    }

    val options = remember(allFolders, blocked) {
        buildFolderOptions(allFolders).filter { it.first !in blocked }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(320.dp)
            ) {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    item {
                        MoveOptionRow(
                            label = "全部对话（根目录）",
                            depth = 0,
                            onClick = { onConfirm(null) }
                        )
                    }
                    items(options, key = { it.first }) { (id, name, depth) ->
                        MoveOptionRow(
                            label = name,
                            depth = depth,
                            onClick = { onConfirm(id) }
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    )
}

@Composable
private fun MoveOptionRow(label: String, depth: Int, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .clickable(onClick = onClick)
            .padding(start = (8 + depth * 16).dp, top = 11.dp, bottom = 11.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.Folder,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(9.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

// ============================================================================
// 纯函数辅助
// ============================================================================

private fun entryKey(entry: ExplorerEntry): String = when (entry) {
    is ExplorerEntry.FolderEntry -> FOLDER_KEY_PREFIX + entry.folder.id
    is ExplorerEntry.ConversationEntry -> "c_" + entry.conversation.id
}

/** 条目的原始数据 id（自由摆放坐标 JSON 的键）。 */
private fun entryId(entry: ExplorerEntry): String = when (entry) {
    is ExplorerEntry.FolderEntry -> entry.folder.id
    is ExplorerEntry.ConversationEntry -> entry.conversation.id
}

/** 条目显示名。 */
private fun entryTitle(entry: ExplorerEntry): String = when (entry) {
    is ExplorerEntry.FolderEntry -> entry.folder.name
    is ExplorerEntry.ConversationEntry -> entry.conversation.title
}

/** 从当前目录上溯到根的路径（根在前）。 */
private fun buildBreadcrumb(allFolders: List<Folder>, folderId: String?): List<Folder> {
    if (folderId == null) return emptyList()
    val byId = allFolders.associateBy { it.id }
    val path = ArrayDeque<Folder>()
    var cursor = byId[folderId]
    var guard = 0
    while (cursor != null && guard++ < 512) {
        path.addFirst(cursor)
        cursor = cursor.parentFolderId?.let { byId[it] }
    }
    return path.toList()
}

/**
 * 文件夹在前、对话在后（与桌面资源管理器一致）。
 * - 置顶优先在两种排序下都保持；
 * - [ExplorerSort.NAME]：文件夹按 name、对话按 title；
 * - [ExplorerSort.MODIFIED]：对话按 updatedAt 降序；Folder 表**没有
 *   修改时间字段**，退回 createdAt（界面已注明）；
 * - 开启「收藏」筛选时，展示工作区全部已收藏的文件夹与对话。
 */
private fun buildEntries(
    allFolders: List<Folder>,
    allConversations: List<Conversation>,
    currentFolderId: String?,
    onlyFavorites: Boolean = false,
    sortMode: ExplorerSort = ExplorerSort.NAME
): List<ExplorerEntry> {
    val folderById = allFolders.associateBy { it.id }

    val targetFolders = if (onlyFavorites) {
        allFolders.filter { it.isFavorite }
    } else {
        allFolders.filter { it.parentFolderId == currentFolderId }
    }

    val folderComparator: Comparator<Folder> = when (sortMode) {
        ExplorerSort.NAME -> compareByDescending<Folder> { it.isPinned }
            .thenBy { it.name }
            .thenBy { it.sortOrder }
            .thenBy { it.createdAt }

        ExplorerSort.MODIFIED -> compareByDescending<Folder> { it.isPinned }
            .thenByDescending { it.createdAt }
            .thenBy { it.name }
    }

    val subFolders = targetFolders
        .sortedWith(folderComparator)
        .map { folder ->
            ExplorerEntry.FolderEntry(
                folder = folder,
                subFolderCount = allFolders.count { it.parentFolderId == folder.id },
                conversationCount = allConversations.count { it.folderId == folder.id }
            )
        }

    val targetConversations = if (onlyFavorites) {
        allConversations.filter { it.isFavorite }
    } else {
        allConversations.filter { it.folderId == currentFolderId }
    }

    val conversationComparator: Comparator<Conversation> = when (sortMode) {
        ExplorerSort.NAME -> compareByDescending<Conversation> { it.isPinned }
            .thenBy { it.title }
            .thenByDescending { it.updatedAt }

        ExplorerSort.MODIFIED -> compareByDescending<Conversation> { it.isPinned }
            .thenByDescending { it.updatedAt }
    }

    val conversations = targetConversations
        .sortedWith(conversationComparator)
        .map { conversation ->
            val pathLabel = if (onlyFavorites && conversation.folderId != null) {
                folderById[conversation.folderId]?.name.orEmpty()
            } else {
                ""
            }
            ExplorerEntry.ConversationEntry(
                conversation = conversation,
                folderPath = pathLabel
            )
        }

    return subFolders + conversations
}

/** 收集某个文件夹自身及其全部子孙的 id。 */
private fun collectSubtreeIds(allFolders: List<Folder>, rootId: String): Set<String> {
    val result = mutableSetOf(rootId)
    var frontier = listOf(rootId)
    var guard = 0
    while (frontier.isNotEmpty() && guard++ < 512) {
        val next = allFolders.filter { it.parentFolderId in frontier }.map { it.id }
        result.addAll(next)
        frontier = next
    }
    return result
}

/** 扁平化成 (id, 名称, 缩进层级)，用于移动目标列表。 */
private fun buildFolderOptions(allFolders: List<Folder>): List<Triple<String, String, Int>> {
    val byParent = allFolders.groupBy { it.parentFolderId }
    val out = mutableListOf<Triple<String, String, Int>>()

    fun walk(parentId: String?, depth: Int) {
        if (depth > 64) return
        byParent[parentId].orEmpty()
            .sortedWith(compareBy({ it.sortOrder }, { it.name }))
            .forEach { folder ->
                out.add(Triple(folder.id, folder.name, depth))
                walk(folder.id, depth + 1)
            }
    }
    walk(null, 0)
    return out
}

/** 来源行：所属文件夹（若在收藏视图下）+ 来源说明 + 最近活动时间。 */
private fun buildSourceLine(conversation: Conversation, folderPath: String = ""): String {
    val origin = conversation.sourceOrigin?.takeIf { it.isNotBlank() } ?: "未标注来源"
    val prefix = if (folderPath.isNotBlank()) "$folderPath · " else ""
    return "$prefix$origin · ${relativeTime(conversation.updatedAt)}"
}

private fun relativeTime(timestamp: Long): String {
    val diff = System.currentTimeMillis() - timestamp
    if (diff < 0) return "刚刚"
    val minutes = TimeUnit.MILLISECONDS.toMinutes(diff)
    val hours = TimeUnit.MILLISECONDS.toHours(diff)
    val days = TimeUnit.MILLISECONDS.toDays(diff)
    return when {
        minutes < 1 -> "刚刚"
        minutes < 60 -> "${minutes} 分钟前"
        hours < 24 -> "${hours} 小时前"
        days < 30 -> "${days} 天前"
        else -> "${days / 30} 个月前"
    }
}


/**
 * 目录滚动位置的保存器：`Map<String, Int>`（目录 id → 首条可见项下标）。
 *
 * 自己写 saver 而不直接 rememberSaveable：SnapshotStateMap 默认的 Saver
 * 在部分版本上不可靠，且这里明确要存的是"目录 id 字符串 → 下标整数"，
 * 显式写出序列化格式后重建行为完全可预期（也能避开 Map<*,*> 的类型擦除问题）。
 */
private val folderScrollSaver = mapSaver(
    save = { m -> m.mapValues { it.value.toString() } },
    restore = { m ->
        mutableStateMapOf<String, Int>().apply {
            m.forEach { (k, v) -> k.toIntOrNull()?.let { idx -> put(k, idx) } }
        }
    }
)
