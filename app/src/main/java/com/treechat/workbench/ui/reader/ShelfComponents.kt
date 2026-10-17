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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ViewList
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DocumentScanner
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Restore
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
import com.treechat.workbench.task.BackgroundTaskRegistry
import com.treechat.workbench.ui.browser.WebBrowserScreen
import com.treechat.workbench.ui.chat.ChatNodeViewModel
import com.treechat.workbench.ui.explorer.FreePlacement
import com.treechat.workbench.ui.knowledge.BackgroundTasksSheet
import com.treechat.workbench.ui.knowledge.ChunkInspectorView
import com.treechat.workbench.ui.knowledge.LiveRetrievalTestPanel
import com.treechat.workbench.ui.knowledge.RagSchemeConfigDialog
import com.treechat.workbench.ui.knowledge.RebuildIndexSchemeDialog
import com.treechat.workbench.ui.theme.AccentCyan
import com.treechat.workbench.ui.theme.AccentGold
import com.treechat.workbench.ui.theme.AccentNeonGreen
import com.treechat.workbench.ui.theme.CardSurface
import com.treechat.workbench.ui.theme.DividerDark
import com.treechat.workbench.ui.theme.PanelSurface
import com.treechat.workbench.ui.theme.TextMuted
import com.treechat.workbench.ui.theme.TextPrimary
import com.treechat.workbench.ui.theme.TextSecondary
import com.treechat.workbench.ui.window.FloatingWindowManager
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 书架（书籍列表）的界面组件：工具条 / 批量条 / 书卡片 / 封面与进度角标。
 *
 * 从 `ReaderScreen.kt`（曾超过 2800 行）中拆出：这些组件的共同点是**只依赖
 * 自己的参数**，不碰 ViewModel、不碰阅读器状态，是纯粹展示 + 回调。
 * 拆开之后"改书卡片"不再需要在一个两千多行的文件里定位，也避免误改阅读器逻辑。
 */

internal enum class ShelfSort(val label: String) {
    RECENT("最近阅读"),
    TITLE("标题"),
    IMPORTED("导入时间")
}

internal fun filteredIds(entries: List<ShelfEntry>, query: String): List<String> =
    entries.filter { query.isBlank() || it.document.title.contains(query.trim(), ignoreCase = true) }
        .map { it.document.id }

@Composable
internal fun ShelfBatchBar(
    selectedCount: Int,
    allSelected: Boolean,
    onSelectAllToggle: () -> Unit,
    onBatchDelete: () -> Unit,
    /** 批量上传到谷歌云盘（长按多选后"添加"的入口）。 */
    onUploadCloud: () -> Unit = {},
    onExitSelectionMode: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(12.dp),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onExitSelectionMode) {
                Icon(Icons.Filled.Close, contentDescription = "退出多选")
            }
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "已选 $selectedCount 本",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onSelectAllToggle) {
                Text(if (allSelected) "取消全选" else "全选")
            }
            if (selectedCount > 0) {
                // 上传云盘：与拖到 Drive 图标同一条上传通路
                IconButton(onClick = onUploadCloud) {
                    Icon(
                        Icons.Outlined.CloudUpload,
                        contentDescription = "上传到谷歌云盘",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                IconButton(onClick = onBatchDelete) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = "批量删除",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

/**
 * 书架顶部工具区：搜索框 + 网格/列表切换 + 排序 + 封面显示设置。
 *
 * 与 [ShelfToolbar]（导入书籍 / 网页阅读 / 检索测试 / 方案 API / 后台任务）分开一行，
 * 因为前者是"入口"，这一行是"浏览"。两行都在，入口不会被挤掉。
 */
@Composable
internal fun ShelfBrowserBar(
    query: String,
    onQueryChange: (String) -> Unit,
    sort: ShelfSort,
    onSortChange: (ShelfSort) -> Unit,
    /** 恢复默认排列（清空图标自由摆放坐标）。 */
    onResetPlacement: () -> Unit = {},
    gridMode: Boolean,
    onGridModeChange: (Boolean) -> Unit,
    disableCrop: Boolean,
    onDisableCropChange: (Boolean) -> Unit,
    pdfCoverEnabled: Boolean,
    onPdfCoverEnabledChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            placeholder = { Text("搜索书名", fontSize = 13.sp) },
            leadingIcon = {
                Icon(Icons.Outlined.Search, contentDescription = null, modifier = Modifier.size(18.dp))
            },
            textStyle = MaterialTheme.typography.bodyMedium,
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.weight(1f)
        )

        // 网格 / 列表切换
        // 恢复默认排列：清空图标自由摆放坐标（仅在图标视图有意义）
        if (gridMode) {
            IconButton(onClick = onResetPlacement) {
                Icon(
                    imageVector = Icons.Outlined.Restore,
                    contentDescription = "恢复默认排列",
                    modifier = Modifier.size(18.dp)
                )
            }
        }
        IconButton(onClick = { onGridModeChange(!gridMode) }) {
            Icon(
                imageVector = if (gridMode) Icons.AutoMirrored.Outlined.ViewList else Icons.Outlined.GridView,
                contentDescription = if (gridMode) "切换为列表" else "切换为网格"
            )
        }

        // 排序
        var sortOpen by remember { mutableStateOf(false) }
        Box {
            IconButton(onClick = { sortOpen = true }) {
                Icon(Icons.Outlined.Sort, contentDescription = "排序")
            }
            DropdownMenu(expanded = sortOpen, onDismissRequest = { sortOpen = false }) {
                ShelfSort.entries.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option.label) },
                        leadingIcon = {
                            if (option == sort) {
                                Icon(Icons.Outlined.Check, contentDescription = null)
                            }
                        },
                        onClick = {
                            sortOpen = false
                            onSortChange(option)
                        }
                    )
                }
            }
        }

        // 封面显示设置（不裁剪 / 是否抽取 PDF 首页封面）
        var prefOpen by remember { mutableStateOf(false) }
        Box {
            IconButton(onClick = { prefOpen = true }) {
                Icon(Icons.Outlined.Tune, contentDescription = "封面显示设置")
            }
            DropdownMenu(expanded = prefOpen, onDismissRequest = { prefOpen = false }) {
                DropdownMenuItem(
                    text = { Text(if (disableCrop) "封面不裁剪：开" else "封面不裁剪：关") },
                    leadingIcon = {
                        if (disableCrop) Icon(Icons.Outlined.Check, contentDescription = null)
                    },
                    onClick = {
                        prefOpen = false
                        onDisableCropChange(!disableCrop)
                    }
                )
                DropdownMenuItem(
                    text = { Text("抽取 PDF 首页作封面：" + if (pdfCoverEnabled) "开" else "关") },
                    leadingIcon = {
                        if (pdfCoverEnabled) Icon(Icons.Outlined.Check, contentDescription = null)
                    },
                    onClick = {
                        prefOpen = false
                        onPdfCoverEnabledChange(!pdfCoverEnabled)
                    }
                )
            }
        }
    }
}

/**
 * 书架上的单本书卡片（网格 / 列表两种形态共用同一套操作）。
 *
 * 展示：封面（真实抽取的封面，取不到则画"生成封面"）/ 进度角标 / 标题 /
 * 一行次要信息（格式 · 页数 · 索引状态；**没有作者数据就不写作者，绝不编**）。
 *
 * 操作：轻点打开；长按或点封面右上角「更多」打开菜单（打开 / 重命名 / 参与检索开关 /
 * 离线识别 / 参与检索（建立切片索引）或重建索引（选方案） / 查看切片 / 删除）。
 * 索引项文案随状态切换：未索引 →「参与检索」，已索引 →「重建索引」。
 * 删除二次确认。
 */
@Composable
internal fun ShelfBookCard(
    entry: ShelfEntry,
    grid: Boolean,
    coversVersion: Int,
    disableCrop: Boolean,
    selectionMode: Boolean = false,
    isSelected: Boolean = false,
    /** 搜索命中来自书里的正文（书名没命中）。 */
    contentHit: Boolean = false,
    /** 废纸篓在 root 坐标系里的矩形（Finder 式拖入删除）。 */
    trashBounds: Rect? = null,
    /** 拖拽过程中的手指 root 坐标（null = 未在拖）。 */
    onDragFingerChange: (Offset?) -> Unit = {},
    /** 松手时手指落在废纸篓内。 */
    onDropToTrash: () -> Unit = {},
    /** 谷歌云盘图标的屏幕矩形：拖书到这里松手 = 上传（Finder 式）。 */
    driveBounds: Rect? = null,
    onDropToDrive: () -> Unit = {},
    /**
     * 松手时的**root 坐标**（自由摆放用）。
     *
     * 与 [onDropToTrash] 的分工：先由这里判定"是不是落在废纸篓上"，
     * 不是才就地摆放。之所以给坐标而不是布尔，是因为摆放需要精确落点。
     */
    onDragRelease: (Offset) -> Unit = {},
    onToggleSelect: () -> Unit = {},
    onEnterSelectionMode: () -> Unit = {},
    onOpen: () -> Unit,
    onInspectChunks: () -> Unit,
    onRebuildIndex: () -> Unit,
    onTriggerOcr: () -> Unit,
    onRename: (String) -> Unit,
    onSetEnabled: (Boolean) -> Unit,
    onDelete: () -> Unit
) {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val doc = entry.document
    val reading = entry.reading
    var cardBounds by remember(doc.id) { mutableStateOf<Rect?>(null) }
    var menuOpen by remember(doc.id) { mutableStateOf(false) }
    var renameOpen by remember(doc.id) { mutableStateOf(false) }
    var confirmDelete by remember(doc.id) { mutableStateOf(false) }

    // 封面：按 id 找缓存文件；coversVersion 变化时重读（导入后抽到封面的情形）。
    val coverFile = remember(doc.id, coversVersion) { CoverExtractor.findCached(context, doc.id) }
    val coverBitmap = rememberCoverBitmap(coverFile)

    val cardColors = CardDefaults.cardColors(containerColor = scheme.surfaceContainerLow)

    if (grid) {
        Card(
            shape = RoundedCornerShape(12.dp),
            colors = cardColors,
            border = if (selectionMode && isSelected) {
                BorderStroke(2.dp, scheme.primary)
            } else null,
            modifier = Modifier
                .fillMaxWidth()
                .onGloballyPositioned { coords ->
                    val p = coords.positionInRoot()
                    cardBounds = Rect(
                        p.x,
                        p.y,
                        p.x + coords.size.width,
                        p.y + coords.size.height
                    )
                }
                .pointerInput(doc.id, selectionMode) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val origin = cardBounds?.topLeft ?: Offset.Zero
                        var hasMoved = false
                        val startTime = System.currentTimeMillis()
                        val result = withTimeoutOrNull(380L) {
                            while (true) {
                                // Final pass：外层 verticalScroll 在 Main pass 消费
                                // 纵向拖动，这里若也用 Main pass，就只能看到滚动"剩下"
                                // 的位移，判定会失准。
                                val event = awaitPointerEvent(PointerEventPass.Final)
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) break
                                val diff = change.position - down.position
                                if (diff.getDistance() > viewConfiguration.touchSlop) {
                                    hasMoved = true
                                    break
                                }
                            }
                        }
                        val elapsed = System.currentTimeMillis() - startTime
                        if (result == null && !hasMoved) {
                            // 确定性长按：稳定进入多选，绝不误触发打开！
                            down.consume()
                            if (selectionMode) onToggleSelect() else onEnterSelectionMode()
                            // 长按成立后继续跟踪：拖到废纸篓松手即删除（Finder 行为）。
                            // 没有这一段时，长按之后一移动就被当成"取消"，用户
                            // 既删不掉也选不中，只能干瞪眼。
                            //
                            // ⚠️ 为什么必须在 **Final pass** 追踪、且抬手也判定：
                            // 卡片外面套着 `.verticalScroll(shelfScroll)`（自由摆放画布），
                            // 滚动容器在 Main pass 就会消费纵向拖动。跟踪循环若用
                            // Main pass 的 awaitPointerEvent()，拿到的位移是滚动"剩下"的
                            // 部分，手指往上拖时甚至可能一格都不动 ——
                            // 表现正是"书能选中、却拖不到废纸篓"。
                            // Final pass 在滚动之后，能看到完整位移。
                            var dragging = false
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Final)
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) {
                                    // 抬手：只要移动过就按落点结算，
                                    // 不要求"dragging 曾经为 true"——
                                    // 滚动容器可能在最后一刻才交还控制权。
                                    val moved = (change.position - down.position).getDistance() >
                                        viewConfiguration.touchSlop
                                    if (dragging || moved) {
                                        val rootPos = change.position + origin
                                        onDragFingerChange(null)
                                        if (trashBounds?.contains(rootPos) == true) {
                                            onDropToTrash()
                                        } else if (driveBounds?.contains(rootPos) == true) {
                                            onDropToDrive()
                                        } else {
                                            onDragRelease(rootPos)
                                        }
                                    }
                                    break
                                }
                                if ((change.position - down.position).getDistance() >
                                    viewConfiguration.touchSlop
                                ) {
                                    dragging = true
                                }
                                if (dragging) {
                                    change.consume()
                                    onDragFingerChange(change.position + origin)
                                }
                            }
                        } else if (!hasMoved && elapsed < 220L) {
                            // 极速轻触：才触发打开或勾选
                            if (selectionMode) onToggleSelect() else onOpen()
                        }
                    }
                }
        ) {
            Column {
                // 封面用 **weight 占据"剩余高度"**，而不是 aspectRatio 自适应。
                //
                // 为什么：格子是固定尺寸（SHELF_CELL_H_DP），而副标题长度会随
                // 文档状态变化 —— 未建索引时 4 个字，扫描建索引后变成
                // "已索引 128 段 · 本地 2-Gram" 十几个字。封面若按 aspectRatio
                // 占固定高度，这块多出来的文字就没有空间，
                // 结果是**书名被挤掉半行、末尾还多个省略号**（用户报的现象）。
                //
                // 改成 weight(1f) 后，文字区高度固定，封面自动吃掉剩余空间：
                // 副标题再长也只会自己截断，永远不会去挤书名。
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    BookCover(
                        title = doc.title,
                        format = entry.displayFormat(),
                        bitmap = coverBitmap,
                        disableCrop = disableCrop,
                        modifier = Modifier.fillMaxSize()
                    )
                    ProgressCornerBadge(
                        reading = reading,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(6.dp)
                    )

                    if (selectionMode) {
                        Surface(
                            shape = CircleShape,
                            color = if (isSelected) scheme.primary else scheme.surface.copy(alpha = 0.75f),
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(6.dp)
                                .size(24.dp)
                        ) {
                            Icon(
                                imageVector = if (isSelected) Icons.Filled.CheckCircle else Icons.Outlined.Circle,
                                contentDescription = if (isSelected) "已勾选" else "未勾选",
                                tint = if (isSelected) scheme.onPrimary else scheme.outline,
                                modifier = Modifier.padding(2.dp)
                            )
                        }
                    } else {
                        Box(modifier = Modifier.align(Alignment.TopEnd)) {
                            CardOverflowButton(onClick = { menuOpen = true })
                            ShelfBookMenu(
                                expanded = menuOpen,
                                onDismiss = { menuOpen = false },
                                hasIndex = doc.chunkCount > 0,
                                ocrProcessed = reading?.isOcrProcessed == true,
                                enabled = doc.enabled,
                                onOpen = { menuOpen = false; onOpen() },
                                onRename = { menuOpen = false; renameOpen = true },
                                onToggleEnabled = { menuOpen = false; onSetEnabled(!doc.enabled) },
                                onTriggerOcr = { menuOpen = false; onTriggerOcr() },
                                onRebuildIndex = { menuOpen = false; onRebuildIndex() },
                                onInspectChunks = { menuOpen = false; onInspectChunks() },
                                indexSummary = indexSummaryOf(entry),
                                onDelete = { menuOpen = false; confirmDelete = true }
                            )
                        }
                    }
                }
                // 文字区：书名**优先**，其余信息一律让位。
                //
                // 原来"正文命中"徽章单独占一行，与副标题上下叠加，
                // 搜索时标题被挤到只剩一行。现在徽章与副标题**同一行**，
                // 徽章在前、副标题吃掉剩余宽度，两者都不会侵占书名的高度。
                Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                    Text(
                        text = doc.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = scheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row(
                        modifier = Modifier.padding(top = 3.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (contentHit) {
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = scheme.tertiaryContainer
                            ) {
                                Text(
                                    text = "命中",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = scheme.onTertiaryContainer,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(4.dp))
                        }
                        Text(
                            text = coverSecondaryLine(entry),
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                    }
                }
            }
        }
    } else {
        Card(
            shape = RoundedCornerShape(12.dp),
            colors = cardColors,
            border = if (selectionMode && isSelected) {
                BorderStroke(2.dp, scheme.primary)
            } else null,
            modifier = Modifier
                .fillMaxWidth()
                .onGloballyPositioned { coords ->
                    val p = coords.positionInRoot()
                    cardBounds = Rect(
                        p.x,
                        p.y,
                        p.x + coords.size.width,
                        p.y + coords.size.height
                    )
                }
                .pointerInput(doc.id, selectionMode) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val origin = cardBounds?.topLeft ?: Offset.Zero
                        var hasMoved = false
                        val startTime = System.currentTimeMillis()
                        val result = withTimeoutOrNull(380L) {
                            while (true) {
                                // Final pass：外层 verticalScroll 在 Main pass 消费
                                // 纵向拖动，这里若也用 Main pass，就只能看到滚动"剩下"
                                // 的位移，判定会失准。
                                val event = awaitPointerEvent(PointerEventPass.Final)
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) break
                                val diff = change.position - down.position
                                if (diff.getDistance() > viewConfiguration.touchSlop) {
                                    hasMoved = true
                                    break
                                }
                            }
                        }
                        val elapsed = System.currentTimeMillis() - startTime
                        if (result == null && !hasMoved) {
                            // 确定性长按：稳定进入多选，绝不误触发打开！
                            down.consume()
                            if (selectionMode) onToggleSelect() else onEnterSelectionMode()
                            // 长按成立后继续跟踪：拖到废纸篓松手即删除（Finder 行为）。
                            // 与网格分支同一处修正：必须在 **Final pass** 追踪。
                            // 外层是 `.verticalScroll(shelfScroll)`，滚动容器在
                            // Main pass 消费纵向拖动；用 Main pass 追踪时卡片
                            // 拿到的是滚动剩下的位移，往上拖常常一格都不动，
                            // 于是"能选中、却拖不到废纸篓"。
                            var dragging = false
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Final)
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) {
                                    val moved = (change.position - down.position).getDistance() >
                                        viewConfiguration.touchSlop
                                    if (dragging || moved) {
                                        val rootPos = change.position + origin
                                        onDragFingerChange(null)
                                        // 未落在废纸篓：列表布局没有自由摆放，
                                        // 必须**明确取消拖拽态**，否则手指的浮标会
                                        // 一直留在屏幕上。
                                        if (trashBounds?.contains(rootPos) == true) {
                                            onDropToTrash()
                                        } else if (driveBounds?.contains(rootPos) == true) {
                                            onDropToDrive()
                                        } else {
                                            onDragRelease(rootPos)
                                        }
                                    }
                                    break
                                }
                                if ((change.position - down.position).getDistance() >
                                    viewConfiguration.touchSlop
                                ) {
                                    dragging = true
                                }
                                if (dragging) {
                                    change.consume()
                                    onDragFingerChange(change.position + origin)
                                }
                            }
                        } else if (!hasMoved && elapsed < 220L) {
                            // 极速轻触：才触发打开或勾选
                            if (selectionMode) onToggleSelect() else onOpen()
                        }
                    }
                }
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (selectionMode) {
                    Icon(
                        imageVector = if (isSelected) Icons.Filled.CheckCircle else Icons.Outlined.Circle,
                        contentDescription = if (isSelected) "已勾选" else "未勾选",
                        tint = if (isSelected) scheme.primary else scheme.outline,
                        modifier = Modifier
                            .padding(end = 10.dp)
                            .size(24.dp)
                    )
                }

                Box(
                    modifier = Modifier
                        .width(48.dp)
                        .height(68.dp)
                        .clip(RoundedCornerShape(6.dp))
                ) {
                    BookCover(
                        title = doc.title,
                        format = entry.displayFormat(),
                        bitmap = coverBitmap,
                        disableCrop = disableCrop,
                        modifier = Modifier.fillMaxSize()
                    )
                    ProgressCornerBadge(
                        reading = reading,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(3.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = doc.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = scheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        text = coverSecondaryLine(entry),
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Box {
                    CardOverflowButton(onClick = { menuOpen = true }, dark = false)
                    ShelfBookMenu(
                        expanded = menuOpen,
                        onDismiss = { menuOpen = false },
                        hasIndex = doc.chunkCount > 0,
                        ocrProcessed = reading?.isOcrProcessed == true,
                        enabled = doc.enabled,
                        onOpen = { menuOpen = false; onOpen() },
                        onRename = { menuOpen = false; renameOpen = true },
                        onToggleEnabled = { menuOpen = false; onSetEnabled(!doc.enabled) },
                        onTriggerOcr = { menuOpen = false; onTriggerOcr() },
                        onRebuildIndex = { menuOpen = false; onRebuildIndex() },
                        onInspectChunks = { menuOpen = false; onInspectChunks() },
                        indexSummary = indexSummaryOf(entry),
                        onDelete = { menuOpen = false; confirmDelete = true }
                    )
                }
            }
        }
    }

    if (renameOpen) {
        RenameDocumentDialog(
            initial = doc.title,
            onDismiss = { renameOpen = false },
            onConfirm = { newTitle ->
                renameOpen = false
                onRename(newTitle)
            }
        )
    }
    if (confirmDelete) {
        ConfirmDeleteDialog(
            title = doc.title,
            onDismiss = { confirmDelete = false },
            onConfirm = {
                confirmDelete = false
                onDelete()
            }
        )
    }
}

/** 封面右上角的「更多」按钮。贴在封面图上，网格形态用半透明深色底衬以保证可读。 */

@Composable
internal fun CardOverflowButton(onClick: () -> Unit, dark: Boolean = true) {
    val bg = if (dark) Color.Black.copy(alpha = 0.42f) else Color.Transparent
    Surface(
        color = bg,
        shape = RoundedCornerShape(50),
        modifier = Modifier
            .padding(4.dp)
            .size(26.dp)
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.clickable(onClick = onClick)) {
            Icon(
                imageVector = Icons.Outlined.MoreVert,
                contentDescription = "更多操作",
                tint = if (dark) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(17.dp)
            )
        }
    }
}

/** 长按 / 点「更多」弹出的操作菜单（网格与列表共用）。 */
@Composable
internal fun ShelfBookMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    hasIndex: Boolean,
    ocrProcessed: Boolean,
    enabled: Boolean,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onToggleEnabled: () -> Unit,
    onTriggerOcr: () -> Unit,
    onRebuildIndex: () -> Unit,
    onInspectChunks: () -> Unit,
    onDelete: () -> Unit,
    /**
     * 索引概览（只读，例如"已索引 128 段 · 本地 2-Gram"）。
     *
     * 为什么放进菜单而不是留在卡片副标题：卡片只有 132dp 宽，
     * 这串字会被截成"已索引 128 · 本…"，半截信息比没有更糟；
     * 副标题只留"已索引"，细节在这里查。
     */
    indexSummary: String? = null
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        if (indexSummary != null) {
            DropdownMenuItem(
                text = {
                    Text(
                        indexSummary,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                // 纯信息项：不可点，禁用态（它只是展示，不是操作）
                enabled = false,
                onClick = {}
            )
            HorizontalDivider()
        }
        DropdownMenuItem(
            text = { Text("打开") },
            leadingIcon = { Icon(Icons.Outlined.AutoStories, contentDescription = null) },
            onClick = onOpen
        )
        DropdownMenuItem(
            text = { Text("重命名") },
            leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
            onClick = onRename
        )
        DropdownMenuItem(
            text = { Text(if (enabled) "参与检索：已启用" else "参与检索：已停用") },
            leadingIcon = {
                Icon(
                    Icons.Outlined.Search,
                    contentDescription = null,
                    tint = if (enabled) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            onClick = onToggleEnabled
        )
        HorizontalDivider()
        DropdownMenuItem(
            text = {
                Text(if (ocrProcessed) "重新识别（离线 OCR）" else "离线识别（本页 / 整本）")
            },
            leadingIcon = { Icon(Icons.Outlined.DocumentScanner, contentDescription = null) },
            onClick = onTriggerOcr
        )
        DropdownMenuItem(
            text = {
                Text(if (hasIndex) "重建索引（选方案）" else "参与检索（建立切片索引）")
            },
            leadingIcon = {
                Icon(
                    if (hasIndex) Icons.Outlined.Refresh else Icons.Outlined.Add,
                    contentDescription = null
                )
            },
            onClick = onRebuildIndex
        )
        DropdownMenuItem(
            text = { Text("查看原文切片") },
            leadingIcon = { Icon(Icons.Outlined.Visibility, contentDescription = null) },
            onClick = onInspectChunks
        )
        HorizontalDivider()
        DropdownMenuItem(
            text = { Text("删除", color = MaterialTheme.colorScheme.error) },
            leadingIcon = {
                Icon(Icons.Outlined.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
            },
            onClick = onDelete
        )
    }
}

/** 重命名对话框。 */
@Composable
internal fun RenameDocumentDialog(
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("重命名") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                label = { Text("书名") },
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(
                onClick = { if (text.isNotBlank()) onConfirm(text.trim()) },
                enabled = text.isNotBlank()
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

/** 删除二次确认。 */
@Composable
internal fun ConfirmDeleteDialog(
    title: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("删除确认") },
        text = {
            Text("确定删除《$title》？将同时清除它的切片索引、阅读状态与本地文件，此操作不可撤销。")
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("删除", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

/**
 * 封面。有真实封面（[bitmap] 非空）就按裁切填满显示；否则画"生成封面"。
 *
 * [disableCrop] 对应 Koodo 的 isDisableCrop：开启改为 Fit（顶部对齐、不裁切）。
 */
@Composable
internal fun BookCover(
    title: String,
    format: ReaderFormat,
    bitmap: ImageBitmap?,
    disableCrop: Boolean,
    modifier: Modifier
) {
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = "《$title》封面",
            contentScale = if (disableCrop) ContentScale.Fit else ContentScale.Crop,
            alignment = Alignment.TopCenter,
            modifier = modifier
        )
    } else {
        GeneratedBookCover(title = title, format = format, modifier = modifier)
    }
}

/**
 * 生成封面（没有真实封面时画）。
 *
 * 按格式配色的整幅底 + 左上角格式名 + 居中书名（照 Koodo 的 emptyCover 思路，
 * 但用我们自己的配色与文案，**不使用 emoji，不联网，不假装是真实封面**）。
 */
@Composable
internal fun GeneratedBookCover(
    title: String,
    format: ReaderFormat,
    modifier: Modifier
) {
    val colors = coverColors(format)
    Box(modifier = modifier.background(colors.bg)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(10.dp)
        ) {
            Surface(
                color = colors.fg.copy(alpha = 0.18f),
                shape = RoundedCornerShape(4.dp)
            ) {
                Text(
                    text = format.label,
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.fg,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = colors.fg,
                maxLines = 6,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** 封面进度角标：未识别 / New / Done / NN%（取不到阅读状态就不显示）。 */

@Composable
internal fun ProgressCornerBadge(reading: ReaderDocument?, modifier: Modifier) {
    val label = when {
        reading == null -> null
        reading.isScanned && !reading.isOcrProcessed -> "未识别"
        reading.pageCount > 0 && reading.currentPage >= reading.pageCount -> "Done"
        reading.currentPage <= 1 -> "New"
        else -> "${reading.currentPage * 100 / reading.pageCount}%"
    } ?: return
    Surface(
        color = Color.Black.copy(alpha = 0.62f),
        shape = RoundedCornerShape(4.dp),
        modifier = modifier
    ) {
        Text(
            text = label,
            color = Color.White,
            style = MaterialTheme.typography.labelSmall,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
        )
    }
}

/** 生成封面的格式配色（沿用 Koodo 的格式→颜色映射；浅色底用深色字保证可读）。 */
internal data class CoverColors(val bg: Color, val fg: Color)

internal fun coverColors(format: ReaderFormat): CoverColors = when (format) {
    ReaderFormat.PDF -> CoverColors(Color(0xFF37AA51), Color.White)
    ReaderFormat.SCANNED_PDF -> CoverColors(Color(0xFF1B5E20), Color.White)
    ReaderFormat.EPUB -> CoverColors(Color(0xFF21A5F1), Color.White)
    ReaderFormat.TXT -> CoverColors(Color(0xFFFBBF10), Color(0xFF1A1A1A))
    ReaderFormat.MARKDOWN -> CoverColors(Color(0xFF5E7FFF), Color.White)
    ReaderFormat.DOCX -> CoverColors(Color(0xFF118CD9), Color.White)
    ReaderFormat.DOC -> CoverColors(Color(0xFF2B579A), Color.White)
    ReaderFormat.PPTX -> CoverColors(Color(0xFFD24726), Color.White)
    ReaderFormat.PPT -> CoverColors(Color(0xFFD24726), Color.White)
    ReaderFormat.MOBI -> CoverColors(Color(0xFFFF6C6E), Color.White)
    ReaderFormat.AZW3 -> CoverColors(Color(0xFFFF9900), Color(0xFF1A1A1A))
}

/** 书卡的一行次要信息：格式 · 页数 · 索引状态（无作者数据就不写作者）。 */
internal fun coverSecondaryLine(entry: ShelfEntry): String {
    val parts = ArrayList<String>(3)
    parts += entry.displayFormat().label
    val pages = entry.reading?.pageCount ?: 0
    if (pages > 0) parts += "$pages 页"
    when {
        entry.document.chunkCount > 0 ->
            // 卡片只有 132dp 宽，"已索引 128 段 · 本地 2-Gram" 会被截成
            // "已索引 128 · 本…" —— 半截信息比没有更糟。
            // 只留"已索引"这个**用户真正要看的状态**，段数与方案在长按菜单里查。
            parts += "已索引"
        entry.reading?.isScanned == true && entry.reading?.isOcrProcessed != true -> parts += "未识别"
        else -> parts += "未建索引"
    }
    return parts.joinToString(" · ")
}

/** 书卡显示用的格式：优先阅读状态里的真实格式（含 MOBI/AZW3/扫描件），否则按 Room sourceType 推断。 */
private fun ShelfEntry.displayFormat(): ReaderFormat {
    val r = reading
    return when {
        r == null -> readerFormatForSource(document.sourceType)
        r.isScanned -> ReaderFormat.SCANNED_PDF
        else -> r.format
    }
}

/**
 * 长按菜单里的索引概览（段数 + 方案），未建索引时为 null。
 *
 * 卡片副标题只显示"已索引"（卡片只有 132dp 宽，放不下完整串），
 * 完整信息在这里查 —— 不能因为窄就把数据藏没了。
 */
internal fun indexSummaryOf(entry: ShelfEntry): String? {
    val doc = entry.document
    if (doc.chunkCount <= 0) return null
    return "已索引 ${doc.chunkCount} 段 · ${shelfSchemeBadge(doc.indexingScheme)}"
}

/** 读取封面位图（带下采样，避免大图占内存）；文件不存在返回 null。 */
@Composable
internal fun rememberCoverBitmap(file: File?): ImageBitmap? {
    val key = file?.absolutePath.orEmpty() + ":" + (file?.lastModified() ?: 0L)
    return produceState<ImageBitmap?>(initialValue = null, key1 = key) {
        value = if (file != null && file.exists()) {
            withContext(Dispatchers.IO) {
                decodeCoverBitmap(file)?.asImageBitmap()
            }
        } else {
            null
        }
    }.value
}

internal fun decodeCoverBitmap(file: File, maxSide: Int = 720): android.graphics.Bitmap? = try {
    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    android.graphics.BitmapFactory.decodeFile(file.absolutePath, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
        null
    } else {
        var sample = 1
        while (bounds.outWidth / sample > maxSide || bounds.outHeight / sample > maxSide) sample *= 2
        val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
        android.graphics.BitmapFactory.decodeFile(file.absolutePath, opts)
    }
} catch (t: Throwable) {
    null
}

// ============================================================================
// 数据合并与纯函数
// ============================================================================

/** 书架的一项：Room 文档（真相） + 该 id 的阅读状态（可能为空）。 */
internal data class ShelfEntry(
    val document: KnowledgeDocument,
    val reading: ReaderDocument?
)

/**
 * 合并 Room 文档与阅读状态。
 *
 * - Room 里有、阅读池里也有 → 直接配对（正常导入的书）；
 * - Room 里有、阅读池里没有 → 旧知识库导入的书，阅读状态用默认（点开时按切片补建正文）；
 * - 阅读池里有、Room 里没有 → 旧阅读器导入的书，先用阅读状态合成一个占位文档显示，
 *   随后由 [ChatNodeViewModel.ensureShelfRoomDocument] 按同 id 补建 Room 行（不丢失）。
 *
 * 排序：最近阅读（lastReadTimestamp）优先，否则按 Room 创建时间。
 */
internal fun buildShelfEntries(
    roomDocs: List<KnowledgeDocument>,
    poolList: List<ReaderDocument>
): List<ShelfEntry> {
    val roomIds = roomDocs.map { it.id }.toSet()
    val fromRoom = roomDocs.map { doc -> ShelfEntry(doc, poolList.firstOrNull { it.id == doc.id }) }
    val poolOnly = poolList.filter { it.id !in roomIds }.map { reading ->
        ShelfEntry(
            document = KnowledgeDocument(
                id = reading.id,
                title = reading.title,
                fileName = reading.title,
                sourceType = reading.format.roomSourceType(),
                charCount = reading.sampleText.length,
                chunkCount = reading.ragChunkCount,
                createdAt = reading.lastReadTimestamp
            ),
            reading = reading
        )
    }
    return (fromRoom + poolOnly).sortedByDescending {
        it.reading?.lastReadTimestamp ?: it.document.createdAt
    }
}

/** 阅读器的格式 → 知识库 `sourceType` 字符串（保持与既有取值一致）。 */

internal fun shelfSchemeBadge(raw: String?): String =
    when (runCatching { RagIndexingScheme.valueOf(raw.orEmpty()) }.getOrNull()) {
        RagIndexingScheme.CLOUD_EMBEDDING -> "云端向量"
        RagIndexingScheme.HYBRID -> "双路混合"
        else -> "本地 2-Gram"
    }

/**
 * 只识别 PDF 的**一页**（1 基页号），失败返回 null。
 *
 * 渲染用 [PdfPageSource]（一个文档一份 PdfRenderer 的既有封装）：
 * 打开 → 渲染该页位图 → [OcrEngine.recognizePlainText] → 关源、回收位图。
 * 只做识别，不做任何回退 —— 识别不出就返回 null，由调用方如实报"未识别出文字"。
 */
internal suspend fun ocrSinglePageText(
    context: android.content.Context,
    filePath: String,
    pageIndex1Based: Int
): String? {
    val src = withContext(Dispatchers.IO) { PdfPageSource.open(filePath) } ?: return null
    return try {
        val scale = PdfOcr.resolveRenderScale(context)
        val bmp = src.renderPage(pageIndex1Based - 1, scale) ?: return null
        try {
            OcrEngine.recognizePlainText(bmp)
        } finally {
            bmp.recycle()
        }
    } finally {
        src.close()
    }
}

/**
 * 把某一页的识别结果并入文档正文。
 *
 * 用「【第 N 页】」标记定位：该页已有识别块就**整段替换**，没有就追加到末尾。
 * 整本识别（[PdfOcr]）产出的正文用同一个标记口径，因此"先识别单页、再补整本"
 * 或反过来都不会互相污染；已识别的其它页也不会被丢掉。
 */