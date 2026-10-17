@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.treechat.workbench.ui.notes

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.treechat.workbench.data.prefs.InkCardSnapshot
import com.treechat.workbench.data.prefs.InkInteractionMode
import com.treechat.workbench.data.prefs.InkNoteBoard
import com.treechat.workbench.data.prefs.InkPoint
import com.treechat.workbench.data.prefs.InkStroke
import com.treechat.workbench.data.prefs.InkToolType
import com.treechat.workbench.ui.theme.AccentIceBlue
import com.treechat.workbench.ui.theme.AccentNeonGreen
import com.treechat.workbench.ui.theme.DarkSurface
import com.treechat.workbench.ui.theme.DarkSurfaceVariant
import com.treechat.workbench.ui.theme.TextMuted
import com.treechat.workbench.ui.theme.TextPrimary
import com.treechat.workbench.ui.theme.TextSecondary
import com.treechat.workbench.ui.theme.WindowBorderColor
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

private enum class CardDragHandle {
    MOVE,
    RESIZE_RIGHT,
    RESIZE_BOTTOM,
    RESIZE_CORNER
}

private data class CardHitTarget(
    val cardId: String,
    val handle: CardDragHandle
)

private val INK_COLOR_PRESETS = listOf(
    0xFFEF4444L to "朱砂红",
    0xFF10B981L to "松石翠",
    0xFFF59E0BL to "藤黄",
    0xFF38BDF8L to "青金蓝",
    0xFFA855F7L to "紫棠",
    0xFFF8FAFCL to "宣纸白"
)

private val STROKE_WIDTH_PRESETS = listOf(
    2.4f to "细",
    4.0f to "中",
    6.8f to "粗"
)

/**
 * 「条畅 · 活体思维导图手写笔记」主工作台界面。
 *
 * 核心能力：
 * 1. **非死图活体快照**：所有卡片节点保持独立坐标 `(x, y)` 与尺寸 `(width, height)`，
 *    在笔记中依然可随时拖动位置、拉边角放大缩小（放大后自动展示节点核心问答摘要）；
 * 2. **双坐标系矢量笔迹锚定**：
 *    - 落笔在卡片内部 -> 存储为该卡片的归一化局部坐标 `(0..1)`，拖动或缩放卡片时，卡片上的荧光笔高亮、圈注、遮挡胶带严丝合缝跟随！
 *    - 落笔在画布空白处 -> 存储为画布世界坐标，随画布平移缩放；
 *    - 箭头工具支持首尾跨卡片锚定，移动任意卡片时箭头自动跟随；
 * 3. **米 / O / V 原生手写笔 + 第三方电容笔 + 手指触屏 三级自适应优化**：
 *    - 原生主动笔（`PointerType.Stylus`）：支持 4096 级真实压感笔锋、笔尾橡皮擦（`PointerType.Eraser`）自动识别、以及「笔写手移（主动笔写字 + 单指拖卡片/移画布）」防误触；
 *    - 第三方被动电容笔 & 手指触屏（`PointerType.Touch`）：采用中点二次贝塞尔插值消除折线锯齿，并基于运笔速度拟合自然笔锋；书写态下双指捏合依然可随时缩放平移画布；
 * 4. **多副本管理与导出**：支持一键复制副本（Duplicate）、重命名、删除、导出矢量 PDF 或高清 PNG。
 */
@Composable
fun InkWorkbenchScreen(
    boards: List<InkNoteBoard>,
    activeBoardId: String?,
    onSelectBoard: (String) -> Unit,
    onCreateSnapshotFromCurrentTree: () -> Unit,
    onSaveBoard: (InkNoteBoard) -> Unit,
    onDuplicateBoard: (String) -> Unit,
    onRenameBoard: (String, String) -> Unit,
    onDeleteBoard: (String) -> Unit,
    onExportBoard: (InkNoteBoard, Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    val density = LocalDensity.current

    val activeBoard = remember(boards, activeBoardId) {
        boards.firstOrNull { it.id == activeBoardId } ?: boards.firstOrNull()
    }

    // 弹窗控制状态
    var showBoardListDialog by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameInput by remember(activeBoard) { mutableStateOf(activeBoard?.title.orEmpty()) }
    var showExportMenu by remember { mutableStateOf(false) }

    // 手写工具状态
    var interactionMode by remember { mutableStateOf(InkInteractionMode.WRITE) }
    var activeTool by remember { mutableStateOf(InkToolType.PEN) }
    var activeColorArgb by remember { mutableLongStateOf(INK_COLOR_PRESETS.first().first) }
    var activeStrokeWidth by remember { mutableFloatStateOf(STROKE_WIDTH_PRESETS[1].first) }
    /**
     * 「笔写手移」智能模式（默认开启）：
     * - 当检测到小米/OPPO/vivo 等主动手写笔（PointerType.Stylus）时，主动笔永远书写，
     *   同时允许手指在不切模式的情况下长按/拖动卡片或双指缩放；
     * - 当使用第三方电容笔或手指书写时，只要处于 [InkInteractionMode.WRITE]，单指/电容笔同样可直接书写！
     */
    var stylusOnlyLock by remember { mutableStateOf(false) }

    if (activeBoard == null) {
        EmptyInkNotesPlaceholder(
            onCreateSnapshot = onCreateSnapshotFromCurrentTree,
            modifier = modifier
        )
        return
    }

    // 当前白板的可变工作状态（以 board.id 为 key 隔离不同副本）
    var pan by remember(activeBoard.id) { mutableStateOf(Offset(activeBoard.panX, activeBoard.panY)) }
    var scale by remember(activeBoard.id) { mutableFloatStateOf(activeBoard.scale.coerceIn(0.28f, 2.8f)) }
    val cardMap = remember(activeBoard.id) {
        mutableStateMapOf<String, InkCardSnapshot>().apply {
            activeBoard.cards.forEach { put(it.id, it) }
        }
    }
    val strokes = remember(activeBoard.id) {
        mutableStateListOf<InkStroke>().apply {
            addAll(activeBoard.strokes)
        }
    }
    val undoStack = remember(activeBoard.id) { mutableStateListOf<List<InkStroke>>() }
    val redoStack = remember(activeBoard.id) { mutableStateListOf<List<InkStroke>>() }

    // 正在绘制中的实时笔迹预览
    var inFlightStroke by remember(activeBoard.id) { mutableStateOf<InkStroke?>(null) }
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }

    // 当外部切换或更新了同 ID 的卡片集合时同步
    LaunchedEffect(activeBoard.id, activeBoard.updatedAt) {
        if (cardMap.isEmpty() && activeBoard.cards.isNotEmpty()) {
            activeBoard.cards.forEach { cardMap[it.id] = it }
        }
    }

    fun persistCurrentState() {
        val orderedCards = activeBoard.cards.map { orig -> cardMap[orig.id] ?: orig }
        val updated = activeBoard.copy(
            panX = pan.x,
            panY = pan.y,
            scale = scale,
            cards = orderedCards,
            strokes = strokes.toList()
        )
        onSaveBoard(updated)
    }

    fun pushUndoSnapshot() {
        undoStack.add(strokes.map { it.copy(points = it.points.toList()) })
        if (undoStack.size > 40) undoStack.removeAt(0)
        redoStack.clear()
    }

    fun performUndo() {
        if (undoStack.isEmpty()) return
        val prev = undoStack.removeLast()
        redoStack.add(strokes.map { it.copy(points = it.points.toList()) })
        strokes.clear()
        strokes.addAll(prev)
        persistCurrentState()
    }

    fun performRedo() {
        if (redoStack.isEmpty()) return
        val next = redoStack.removeLast()
        undoStack.add(strokes.map { it.copy(points = it.points.toList()) })
        strokes.clear()
        strokes.addAll(next)
        persistCurrentState()
    }

    fun fitAllCards() {
        if (cardMap.isEmpty() || viewportSize == IntSize.Zero) return
        val minX = cardMap.values.minOf { it.x }
        val minY = cardMap.values.minOf { it.y }
        val maxX = cardMap.values.maxOf { it.x + it.width }
        val maxY = cardMap.values.maxOf { it.y + it.height }
        val contentW = (maxX - minX).coerceAtLeast(320f)
        val contentH = (maxY - minY).coerceAtLeast(240f)
        val pad = 72f
        val sx = (viewportSize.width - pad * 2) / contentW
        val sy = (viewportSize.height - pad * 2) / contentH
        val targetScale = min(sx, sy).coerceIn(0.35f, 1.35f)
        scale = targetScale
        pan = Offset(
            x = (viewportSize.width - contentW * targetScale) * 0.5f - minX * targetScale,
            y = (viewportSize.height - contentH * targetScale) * 0.5f - minY * targetScale
        )
        persistCurrentState()
    }

    // 坐标换算与命中测试辅助函数
    val edgeGrabRadiusWorld = with(density) { 26.dp.toPx() } / scale.coerceAtLeast(0.4f)
    val minCardW = with(density) { 160.dp.toPx() }
    val minCardH = with(density) { 78.dp.toPx() }

    fun screenToWorld(screenPos: Offset): Offset = (screenPos - pan) / scale

    fun hitTestCard(worldPos: Offset, forResizeAndMove: Boolean): CardHitTarget? {
        val ordered = activeBoard.cards.mapNotNull { cardMap[it.id] }
        for (card in ordered.asReversed()) {
            val rect = Rect(card.x, card.y, card.x + card.width, card.y + card.height)
            if (!forResizeAndMove) {
                if (rect.contains(worldPos)) {
                    return CardHitTarget(card.id, CardDragHandle.MOVE)
                }
            } else {
                val expanded = rect.inflate(edgeGrabRadiusWorld * 0.45f)
                if (!expanded.contains(worldPos)) continue
                val nearRight = kotlin.math.abs(worldPos.x - rect.right) <= edgeGrabRadiusWorld
                val nearBottom = kotlin.math.abs(worldPos.y - rect.bottom) <= edgeGrabRadiusWorld
                val handle = when {
                    nearRight && nearBottom -> CardDragHandle.RESIZE_CORNER
                    nearRight -> CardDragHandle.RESIZE_RIGHT
                    nearBottom -> CardDragHandle.RESIZE_BOTTOM
                    else -> CardDragHandle.MOVE
                }
                return CardHitTarget(card.id, handle)
            }
        }
        return null
    }

    /**
     * 检查点击是否命中某条「记忆遮挡胶带（TAPE）」，若是则切换其显隐背诵状态。
     */
    fun tryToggleTapeAt(worldPos: Offset): Boolean {
        for (i in strokes.indices.reversed()) {
            val s = strokes[i]
            if (s.toolType != InkToolType.TAPE) continue
            val worldPts = resolveStrokeWorldPoints(s, cardMap)
            val hitRadius = max(20f, s.baseWidth * 3.5f)
            if (isPointNearPolyline(worldPos, worldPts, hitRadius)) {
                strokes[i] = s.copy(tapeRevealed = !s.tapeRevealed)
                persistCurrentState()
                return true
            }
        }
        return false
    }

    /**
     * 橡皮擦碰撞擦除：删除所有与给定世界坐标距离小于阈值的笔迹。
     */
    fun eraseStrokesAt(worldPos: Offset): Boolean {
        val eraseRadius = max(18f, activeStrokeWidth * 3.2f) / scale.coerceAtLeast(0.5f)
        val toRemove = strokes.filter { stroke ->
            val pts = resolveStrokeWorldPoints(stroke, cardMap)
            isPointNearPolyline(worldPos, pts, eraseRadius)
        }
        if (toRemove.isNotEmpty()) {
            strokes.removeAll(toRemove)
            return true
        }
        return false
    }

    Column(modifier = modifier.fillMaxSize().background(scheme.background)) {
        // ==================== 1. 顶部：快照副本管理与导出栏 ====================
        Surface(
            color = scheme.surfaceContainer,
            tonalElevation = 2.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 切换/管理笔记副本按钮
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = scheme.surfaceVariant.copy(alpha = 0.65f),
                    border = BorderStroke(1.dp, WindowBorderColor),
                    modifier = Modifier
                        .weight(1f)
                        .clickable { showBoardListDialog = true }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.FolderOpen,
                            contentDescription = "切换笔记快照",
                            tint = scheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = activeBoard.title,
                                color = scheme.onSurface,
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "共 ${boards.size} 份快照 · ${cardMap.size} 卡片 · ${strokes.size} 笔迹（点击切换）",
                                color = scheme.onSurfaceVariant,
                                fontSize = 10.sp,
                                maxLines = 1
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.width(6.dp))

                // 新快照（从当前工作台导图抓取最新快照）
                IconButton(
                    onClick = onCreateSnapshotFromCurrentTree,
                    modifier = Modifier.size(34.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.AddPhotoAlternate,
                        contentDescription = "从当前工作台新建快照",
                        tint = scheme.primary,
                        modifier = Modifier.size(19.dp)
                    )
                }

                // 一键复制副本（Duplicate）
                IconButton(
                    onClick = { onDuplicateBoard(activeBoard.id) },
                    modifier = Modifier.size(34.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = "复制当前笔记副本",
                        tint = scheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }

                // 重命名当前笔记
                IconButton(
                    onClick = {
                        renameInput = activeBoard.title
                        showRenameDialog = true
                    },
                    modifier = Modifier.size(34.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = "重命名笔记",
                        tint = scheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }

                // 导出 PDF / PNG
                Box {
                    IconButton(
                        onClick = { showExportMenu = true },
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = "导出手写笔记",
                            tint = scheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    DropdownMenu(
                        expanded = showExportMenu,
                        onDismissRequest = { showExportMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("导出矢量 PDF（高清打印/平板审阅）", fontSize = 13.sp) },
                            onClick = {
                                showExportMenu = false
                                persistCurrentState()
                                val latest = activeBoard.copy(
                                    cards = activeBoard.cards.map { cardMap[it.id] ?: it },
                                    strokes = strokes.toList()
                                )
                                onExportBoard(latest, true)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("导出高清长图 PNG（方便分享）", fontSize = 13.sp) },
                            onClick = {
                                showExportMenu = false
                                persistCurrentState()
                                val latest = activeBoard.copy(
                                    cards = activeBoard.cards.map { cardMap[it.id] ?: it },
                                    strokes = strokes.toList()
                                )
                                onExportBoard(latest, false)
                            }
                        )
                    }
                }
            }
        }

        // ==================== 2. 手写笔与卡片交互工具栏 ====================
        Surface(
            color = DarkSurface,
            border = BorderStroke(0.5.dp, WindowBorderColor),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // 模式切换胶囊：【书写批注】 vs 【移动/改卡片】
                InkInteractionMode.entries.forEach { mode ->
                    val selected = interactionMode == mode
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (selected) scheme.primary else DarkSurfaceVariant,
                        border = BorderStroke(
                            1.dp,
                            if (selected) scheme.primary else WindowBorderColor
                        ),
                        modifier = Modifier.clickable { interactionMode = mode }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (mode == InkInteractionMode.WRITE) {
                                    Icons.Default.Edit
                                } else {
                                    Icons.Default.PanTool
                                },
                                contentDescription = mode.label,
                                tint = if (selected) scheme.onPrimary else TextPrimary,
                                modifier = Modifier.size(13.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = mode.label,
                                color = if (selected) scheme.onPrimary else TextPrimary,
                                fontSize = 11.5.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }

                // 分隔竖线
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(20.dp)
                        .background(WindowBorderColor)
                )

                // 五种手写批注工具切换
                InkToolType.entries.forEach { tool ->
                    val isSelected = interactionMode == InkInteractionMode.WRITE && activeTool == tool
                    Surface(
                        shape = RoundedCornerShape(7.dp),
                        color = if (isSelected) AccentNeonGreen.copy(alpha = 0.2f) else Color.Transparent,
                        border = BorderStroke(
                            1.dp,
                            if (isSelected) AccentNeonGreen else WindowBorderColor
                        ),
                        modifier = Modifier.clickable {
                            interactionMode = InkInteractionMode.WRITE
                            activeTool = tool
                        }
                    ) {
                        Text(
                            text = tool.label,
                            color = if (isSelected) AccentNeonGreen else TextPrimary,
                            fontSize = 11.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }

                // 颜色圆点选择
                INK_COLOR_PRESETS.forEach { (argb, name) ->
                    val c = Color(argb)
                    val selected = activeColorArgb == argb
                    Box(
                        modifier = Modifier
                            .size(if (selected) 22.dp else 18.dp)
                            .clip(CircleShape)
                            .background(c)
                            .border(
                                width = if (selected) 2.dp else 1.dp,
                                color = if (selected) Color.White else WindowBorderColor,
                                shape = CircleShape
                            )
                            .clickable {
                                activeColorArgb = argb
                                if (activeTool == InkToolType.ERASER) {
                                    activeTool = InkToolType.PEN
                                }
                            }
                    )
                }

                // 笔宽档位
                STROKE_WIDTH_PRESETS.forEach { (wVal, wLabel) ->
                    val selected = kotlin.math.abs(activeStrokeWidth - wVal) < 0.2f
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = if (selected) AccentIceBlue.copy(alpha = 0.22f) else Color.Transparent,
                        border = BorderStroke(1.dp, if (selected) AccentIceBlue else WindowBorderColor),
                        modifier = Modifier.clickable { activeStrokeWidth = wVal }
                    ) {
                        Text(
                            text = wLabel,
                            color = if (selected) AccentIceBlue else TextSecondary,
                            fontSize = 10.5.sp,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                        )
                    }
                }

                // 「笔写手移」专属防误触开关（针对米/O/V原生主动手写笔）
                Surface(
                    shape = RoundedCornerShape(7.dp),
                    color = if (stylusOnlyLock) AccentIceBlue.copy(alpha = 0.2f) else Color.Transparent,
                    border = BorderStroke(1.dp, if (stylusOnlyLock) AccentIceBlue else WindowBorderColor),
                    modifier = Modifier.clickable { stylusOnlyLock = !stylusOnlyLock }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.TouchApp,
                            contentDescription = "笔写手移防误触",
                            tint = if (stylusOnlyLock) AccentIceBlue else TextSecondary,
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(modifier = Modifier.width(3.dp))
                        Text(
                            text = if (stylusOnlyLock) "仅主动笔写(手移卡)" else "笔/电容笔/手皆可写",
                            color = if (stylusOnlyLock) AccentIceBlue else TextSecondary,
                            fontSize = 10.5.sp
                        )
                    }
                }

                // 撤销 / 重做 / 归位
                IconButton(
                    onClick = { performUndo() },
                    enabled = undoStack.isNotEmpty(),
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Undo,
                        contentDescription = "撤销",
                        tint = if (undoStack.isNotEmpty()) TextPrimary else TextMuted,
                        modifier = Modifier.size(16.dp)
                    )
                }
                IconButton(
                    onClick = { performRedo() },
                    enabled = redoStack.isNotEmpty(),
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Redo,
                        contentDescription = "重做",
                        tint = if (redoStack.isNotEmpty()) TextPrimary else TextMuted,
                        modifier = Modifier.size(16.dp)
                    )
                }
                IconButton(
                    onClick = { fitAllCards() },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.CenterFocusStrong,
                        contentDescription = "视野居中",
                        tint = TextPrimary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }

        // ==================== 3. 核心：活体卡片 + 手写笔迹双层无限画布 ====================
        Box(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { sz ->
                    val firstInit = viewportSize == IntSize.Zero && sz != IntSize.Zero
                    viewportSize = sz
                    if (firstInit && activeBoard.panX == 80f && activeBoard.panY == 120f) {
                        fitAllCards()
                    }
                }
                .pointerInput(
                    activeBoard.id,
                    interactionMode,
                    activeTool,
                    activeColorArgb,
                    activeStrokeWidth,
                    stylusOnlyLock
                ) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val downWorld = screenToWorld(down.position)
                        val isStylus = down.type == PointerType.Stylus
                        val isHardwareEraser = down.type == PointerType.Eraser

                        // 1. 判断此次触摸是否应当触发「橡皮擦」
                        val shouldErase = isHardwareEraser || (
                            interactionMode == InkInteractionMode.WRITE &&
                                activeTool == InkToolType.ERASER &&
                                (!stylusOnlyLock || isStylus)
                            )

                        // 2. 判断此次触摸是否应当触发「书写/画笔/荧光笔/胶带/箭头」
                        //    - 米/O/V 主动手写笔 (PointerType.Stylus) 在书写态下始终书写；
                        //      即便切在排布态，如果开了笔写手移，主动笔落笔也优先书写！
                        //    - 第三方被动电容笔与手指 (PointerType.Touch) 在书写态且未锁死仅主动笔时，直接顺滑书写！
                        val shouldWrite = !shouldErase && (
                            (isStylus && (interactionMode == InkInteractionMode.WRITE || stylusOnlyLock)) ||
                                (!isStylus && interactionMode == InkInteractionMode.WRITE && !stylusOnlyLock)
                            )

                        if (shouldErase) {
                            var erasedAny = false
                            val snapshotBefore = strokes.map { it.copy(points = it.points.toList()) }
                            if (eraseStrokesAt(downWorld)) {
                                erasedAny = true
                            }
                            while (true) {
                                val event = awaitPointerEvent()
                                // 若变成双指捏合，切换为缩放平移画布
                                if (event.changes.count { it.pressed } >= 2) {
                                    val zoom = event.calculateZoom()
                                    val panDelta = event.calculatePan()
                                    val centroid = event.calculateCentroid(useCurrent = false)
                                    val oldScale = scale
                                    val newScale = (scale * zoom).coerceIn(0.28f, 2.8f)
                                    val ratio = newScale / oldScale
                                    pan = centroid - (centroid - pan) * ratio + panDelta
                                    scale = newScale
                                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                                } else {
                                    val pointer = event.changes.firstOrNull { it.id == down.id } ?: break
                                    if (!pointer.pressed) break
                                    val wPos = screenToWorld(pointer.position)
                                    if (eraseStrokesAt(wPos)) {
                                        erasedAny = true
                                    }
                                    pointer.consume()
                                }
                                if (event.changes.none { it.pressed }) break
                            }
                            if (erasedAny) {
                                undoStack.add(snapshotBefore)
                                redoStack.clear()
                                persistCurrentState()
                            }
                        } else if (shouldWrite) {
                            // 先检查是否是轻点已有的「记忆遮挡胶带（TAPE）」进行背诵显隐切换
                            val startHitCard = hitTestCard(downWorld, forResizeAndMove = false)
                            val anchorId = startHitCard?.cardId
                            val anchorCard = anchorId?.let { cardMap[it] }

                            fun toStrokePoint(worldPt: Offset, change: PointerInputChange, prevWorld: Offset?, dtMs: Long): InkPoint {
                                val p = computeAdaptivePressure(change, prevWorld, worldPt, dtMs)
                                return if (anchorCard != null && activeTool != InkToolType.ARROW) {
                                    InkPoint(
                                        x = ((worldPt.x - anchorCard.x) / anchorCard.width.coerceAtLeast(1f)),
                                        y = ((worldPt.y - anchorCard.y) / anchorCard.height.coerceAtLeast(1f)),
                                        pressure = p
                                    )
                                } else if (anchorCard != null && activeTool == InkToolType.ARROW) {
                                    InkPoint(
                                        x = ((worldPt.x - anchorCard.x) / anchorCard.width.coerceAtLeast(1f)),
                                        y = ((worldPt.y - anchorCard.y) / anchorCard.height.coerceAtLeast(1f)),
                                        pressure = p
                                    )
                                } else {
                                    InkPoint(x = worldPt.x, y = worldPt.y, pressure = p)
                                }
                            }

                            val firstPt = toStrokePoint(downWorld, down, null, 16L)
                            val livePoints = mutableListOf(firstPt)
                            val strokeId = UUID.randomUUID().toString()
                            inFlightStroke = InkStroke(
                                id = strokeId,
                                tool = activeTool.name,
                                colorArgb = activeColorArgb,
                                baseWidth = activeStrokeWidth,
                                anchorCardId = anchorId,
                                points = livePoints.toList()
                            )

                            var prevWorld = downWorld
                            var prevTime = down.uptimeMillis
                            var maxMoveDist = 0f
                            var cancelledByMultiTouch = false
                            var lastWorld = downWorld

                            while (true) {
                                val event = awaitPointerEvent()
                                val pressedCount = event.changes.count { it.pressed }
                                if (pressedCount >= 2) {
                                    // 双指按下！立即取消未落成的单指误触墨迹，无缝转为双指缩放平移画布
                                    cancelledByMultiTouch = true
                                    inFlightStroke = null
                                    val zoom = event.calculateZoom()
                                    val panDelta = event.calculatePan()
                                    val centroid = event.calculateCentroid(useCurrent = false)
                                    val oldScale = scale
                                    val newScale = (scale * zoom).coerceIn(0.28f, 2.8f)
                                    val ratio = newScale / oldScale
                                    pan = centroid - (centroid - pan) * ratio + panDelta
                                    scale = newScale
                                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                                } else if (!cancelledByMultiTouch) {
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                    if (!change.pressed) break
                                    val curWorld = screenToWorld(change.position)
                                    lastWorld = curWorld
                                    val stepDist = (curWorld - prevWorld).getDistance()
                                    maxMoveDist = max(maxMoveDist, (curWorld - downWorld).getDistance())

                                    if (stepDist >= 1.2f / scale.coerceAtLeast(0.4f)) {
                                        val dt = (change.uptimeMillis - prevTime).coerceAtLeast(1L)
                                        // 兼容历史轨迹点（Android 高刷屏与主动笔高频硬件采样 history）
                                        change.historical.forEach { hist ->
                                            val hWorld = screenToWorld(hist.position)
                                            if ((hWorld - prevWorld).getDistance() >= 1.0f) {
                                                livePoints.add(toStrokePoint(hWorld, change, prevWorld, 4L))
                                                prevWorld = hWorld
                                            }
                                        }
                                        val newPt = toStrokePoint(curWorld, change, prevWorld, dt)
                                        if (activeTool == InkToolType.ARROW && livePoints.size >= 2) {
                                            livePoints[livePoints.lastIndex] = newPt
                                        } else {
                                            livePoints.add(newPt)
                                        }
                                        inFlightStroke = inFlightStroke?.copy(points = livePoints.toList())
                                        prevWorld = curWorld
                                        prevTime = change.uptimeMillis
                                    }
                                    change.consume()
                                }
                                if (event.changes.none { it.pressed }) break
                            }

                            val finished = inFlightStroke
                            inFlightStroke = null

                            if (!cancelledByMultiTouch && finished != null) {
                                // 如果只是轻点一下（移动极小），优先检测是否点击了某条遮挡胶带以切换显隐背诵
                                if (maxMoveDist < 8f && tryToggleTapeAt(downWorld)) {
                                    // 已切换遮挡胶带显隐，不再画多余墨点
                                } else {
                                    pushUndoSnapshot()
                                    val finalStroke = if (activeTool == InkToolType.ARROW && livePoints.size >= 2) {
                                        val endHit = hitTestCard(lastWorld, forResizeAndMove = false)
                                        val endCard = endHit?.cardId?.let { cardMap[it] }
                                        if (endCard != null) {
                                            val endRel = InkPoint(
                                                x = (lastWorld.x - endCard.x) / endCard.width.coerceAtLeast(1f),
                                                y = (lastWorld.y - endCard.y) / endCard.height.coerceAtLeast(1f),
                                                pressure = 1f
                                            )
                                            val updatedPts = listOf(livePoints.first(), endRel)
                                            finished.copy(
                                                endAnchorCardId = endCard.id,
                                                points = updatedPts
                                            )
                                        } else if (anchorCard != null) {
                                            // 起点在卡片内，终点在空白世界坐标：转为同卡相对坐标以跟随起点卡片
                                            finished.copy(points = listOf(livePoints.first(), livePoints.last()))
                                        } else {
                                            finished
                                        }
                                    } else {
                                        finished
                                    }
                                    strokes.add(finalStroke)
                                    persistCurrentState()
                                }
                            }
                        } else {
                            // 3. 排布态（或「笔写手移」下的手指触摸）：拖动卡片位置 / 拉卡片右下角改大小 / 平移缩放画布
                            val hit = hitTestCard(downWorld, forResizeAndMove = true)
                            var maxMove = 0f
                            while (true) {
                                val event = awaitPointerEvent()
                                val pressedCount = event.changes.count { it.pressed }
                                if (pressedCount >= 2) {
                                    val zoom = event.calculateZoom()
                                    val panDelta = event.calculatePan()
                                    val centroid = event.calculateCentroid(useCurrent = false)
                                    val oldScale = scale
                                    val newScale = (scale * zoom).coerceIn(0.28f, 2.8f)
                                    val ratio = newScale / oldScale
                                    pan = centroid - (centroid - pan) * ratio + panDelta
                                    scale = newScale
                                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                                } else if (hit != null) {
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                    if (!change.pressed) break
                                    val deltaScreen = change.position - change.previousPosition
                                    if (deltaScreen != Offset.Zero) {
                                        maxMove += deltaScreen.getDistance()
                                        val deltaWorld = deltaScreen / scale
                                        val currentCard = cardMap[hit.cardId]
                                        if (currentCard != null) {
                                            cardMap[hit.cardId] = when (hit.handle) {
                                                CardDragHandle.MOVE -> currentCard.copy(
                                                    x = currentCard.x + deltaWorld.x,
                                                    y = currentCard.y + deltaWorld.y
                                                )
                                                CardDragHandle.RESIZE_RIGHT -> currentCard.copy(
                                                    width = (currentCard.width + deltaWorld.x).coerceAtLeast(minCardW)
                                                )
                                                CardDragHandle.RESIZE_BOTTOM -> currentCard.copy(
                                                    height = (currentCard.height + deltaWorld.y).coerceAtLeast(minCardH)
                                                )
                                                CardDragHandle.RESIZE_CORNER -> currentCard.copy(
                                                    width = (currentCard.width + deltaWorld.x).coerceAtLeast(minCardW),
                                                    height = (currentCard.height + deltaWorld.y).coerceAtLeast(minCardH)
                                                )
                                            }
                                        }
                                        change.consume()
                                    }
                                } else {
                                    // 空白处单指拖拽平移画布
                                    val panDelta = event.calculatePan()
                                    if (panDelta != Offset.Zero) {
                                        maxMove += panDelta.getDistance()
                                        pan += panDelta
                                        event.changes.forEach { if (it.positionChanged()) it.consume() }
                                    }
                                }
                                if (event.changes.none { it.pressed }) break
                            }
                            if (maxMove < 8f) {
                                // 轻点：检查是否点击了某条遮挡胶带以切换背诵显隐
                                tryToggleTapeAt(downWorld)
                            } else {
                                persistCurrentState()
                            }
                        }
                    }
                }
        ) {
            // ---------------- 3.1 底层 Canvas：点阵背景 + 卡片贝塞尔连线 + 世界坐标自由笔迹 ----------------
            val linkCurveColor = AccentNeonGreen.copy(alpha = 0.65f)
            Canvas(modifier = Modifier.fillMaxSize()) {
                withTransform({
                    translate(left = pan.x, top = pan.y)
                    scale(scaleX = scale, scaleY = scale, pivot = Offset.Zero)
                }) {
                    // 绘制卡片之间的三次贝塞尔连线
                    activeBoard.cards.forEach { orig ->
                        val child = cardMap[orig.id] ?: orig
                        val parent = child.parentId?.let { cardMap[it] } ?: return@forEach
                        val start = Offset(parent.x + parent.width, parent.y + parent.height * 0.5f)
                        val end = Offset(child.x, child.y + child.height * 0.5f)
                        val dx = max(48f, (end.x - start.x) * 0.5f)
                        val path = Path().apply {
                            moveTo(start.x, start.y)
                            cubicTo(
                                start.x + dx, start.y,
                                end.x - dx, end.y,
                                end.x, end.y
                            )
                        }
                        drawPath(
                            path = path,
                            color = linkCurveColor,
                            style = Stroke(width = 2.8f, cap = StrokeCap.Round)
                        )
                    }

                    // 绘制画布世界坐标自由手写笔迹（anchorCardId == null）
                    strokes.filter { it.anchorCardId == null }.forEach { stroke ->
                        drawVectorInkStroke(stroke, cardMap)
                    }
                    inFlightStroke?.takeIf { it.anchorCardId == null }?.let { live ->
                        drawVectorInkStroke(live, cardMap)
                    }
                }
            }

            // ---------------- 3.2 中层：可自由拖动、拉大缩小的活体卡片 ----------------
            activeBoard.cards.forEach { orig ->
                val card = cardMap[orig.id] ?: orig
                val screenX = pan.x + card.x * scale
                val screenY = pan.y + card.y * scale
                val widthDp = with(density) { card.width.toDp() }
                val heightDp = with(density) { card.height.toDp() }

                InkInteractiveCard(
                    card = card,
                    isArrangeMode = interactionMode == InkInteractionMode.ARRANGE,
                    modifier = Modifier
                        .offset { IntOffset(screenX.roundToInt(), screenY.roundToInt()) }
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            transformOrigin = TransformOrigin(0f, 0f)
                        }
                        .size(width = widthDp, height = heightDp)
                )
            }

            // ---------------- 3.3 顶层 Canvas：锚定在卡片上的手写笔迹、荧光笔、跨卡箭头与背诵遮挡带 ----------------
            Canvas(modifier = Modifier.fillMaxSize()) {
                withTransform({
                    translate(left = pan.x, top = pan.y)
                    scale(scaleX = scale, scaleY = scale, pivot = Offset.Zero)
                }) {
                    strokes.filter { it.anchorCardId != null }.forEach { stroke ->
                        drawVectorInkStroke(stroke, cardMap)
                    }
                    inFlightStroke?.takeIf { it.anchorCardId != null }?.let { live ->
                        drawVectorInkStroke(live, cardMap)
                    }
                }
            }

            // 左下角状态提示条
            Surface(
                color = DarkSurface.copy(alpha = 0.86f),
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(0.5.dp, WindowBorderColor),
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(10.dp)
            ) {
                Text(
                    text = if (interactionMode == InkInteractionMode.WRITE) {
                        "✒️ 批注态：落笔在卡片上自动锚定跟随卡片 · 双指随时缩放平移 · 轻点遮挡带切换显隐"
                    } else {
                        "✋ 排布态：按住卡片中心拖动位置 · 拖卡片右/下/右下角拉大尺寸展示更多正文"
                    },
                    color = TextSecondary,
                    fontSize = 10.5.sp,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                )
            }
        }
    }

    // ==================== 4. 笔记快照与副本列表管理弹窗 ====================
    if (showBoardListDialog) {
        AlertDialog(
            onDismissRequest = { showBoardListDialog = false },
            title = {
                Text("手写笔记与副本管理 (${boards.size})", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = {
                            showBoardListDialog = false
                            onCreateSnapshotFromCurrentTree()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.AddPhotoAlternate, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("从当前工作台新建一份活体快照", fontSize = 12.5.sp)
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    LazyColumn(
                        modifier = Modifier.height(280.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(boards, key = { it.id }) { item ->
                            val isCurrent = item.id == activeBoard.id
                            val timeStr = remember(item.updatedAt) {
                                SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(item.updatedAt))
                            }
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = if (isCurrent) scheme.primaryContainer.copy(alpha = 0.45f) else DarkSurfaceVariant,
                                border = BorderStroke(
                                    1.dp,
                                    if (isCurrent) scheme.primary else WindowBorderColor
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onSelectBoard(item.id)
                                        showBoardListDialog = false
                                    }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = item.title,
                                            color = TextPrimary,
                                            fontSize = 13.sp,
                                            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium
                                        )
                                        Text(
                                            text = "${item.cards.size} 张卡片 · ${item.strokes.size} 笔迹 · $timeStr",
                                            color = TextSecondary,
                                            fontSize = 10.5.sp
                                        )
                                    }
                                    IconButton(
                                        onClick = { onDuplicateBoard(item.id) },
                                        modifier = Modifier.size(30.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.ContentCopy,
                                            contentDescription = "复制副本",
                                            tint = AccentIceBlue,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                    IconButton(
                                        onClick = { onDeleteBoard(item.id) },
                                        modifier = Modifier.size(30.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Delete,
                                            contentDescription = "删除此笔记",
                                            tint = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showBoardListDialog = false }) {
                    Text("关闭")
                }
            }
        )
    }

    // ==================== 5. 重命名弹窗 ====================
    if (showRenameDialog) {
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("重命名手写笔记", fontSize = 16.sp, fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = renameInput,
                    onValueChange = { renameInput = it },
                    singleLine = true,
                    label = { Text("笔记标题") },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onRenameBoard(activeBoard.id, renameInput)
                        showRenameDialog = false
                    }
                ) {
                    Text("保存")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) {
                    Text("取消")
                }
            }
        )
    }
}

@Composable
private fun EmptyInkNotesPlaceholder(
    onCreateSnapshot: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = DarkSurface,
            border = BorderStroke(1.dp, WindowBorderColor),
            modifier = Modifier
                .padding(24.dp)
                .fillMaxWidth(0.88f)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "✒️ 活体思维导图 · 手写批注本",
                    color = TextPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Serif
                )
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "将工作台中的思维导图一键快照至此：\n" +
                        "• 卡片非死图，依然可自由拖动位置、拉边角放大尺寸；\n" +
                        "• 完美适配小米/OPPO/vivo主动手写笔压感、第三方电容笔与手指触屏；\n" +
                        "• 卡片上的荧光笔划重点、圈注与背诵遮挡胶带随卡片移动自动跟随；\n" +
                        "• 支持一键复制多份复习副本与导出高清矢量 PDF / PNG。",
                    color = TextSecondary,
                    fontSize = 12.sp,
                    lineHeight = 19.sp
                )
                Spacer(modifier = Modifier.height(18.dp))
                OutlinedButton(
                    onClick = onCreateSnapshot,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentNeonGreen),
                    border = BorderStroke(1.dp, AccentNeonGreen),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp)
                ) {
                    Icon(Icons.Default.AddPhotoAlternate, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("立即将当前工作台导图快照至笔记", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

/**
 * 活体可拖拽缩放卡片组件。
 *
 * 当用户把卡片拉大时，自动显示更多引用原文与该节点下的 AI 问答核心摘要，
 * 方便直接用荧光笔划重点或用记忆胶带遮挡背诵。
 */
@Composable
private fun InkInteractiveCard(
    card: InkCardSnapshot,
    isArrangeMode: Boolean,
    modifier: Modifier = Modifier
) {
    val borderColor = if (isArrangeMode) AccentIceBlue else AccentNeonGreen.copy(alpha = 0.72f)
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .border(1.5.dp, borderColor, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(AccentNeonGreen)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = card.title.ifBlank { "分支节点" },
                    color = TextPrimary,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            card.quote?.takeIf { it.isNotBlank() }?.let { quote ->
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "「${quote.replace("\n", " ")}」",
                    color = AccentIceBlue.copy(alpha = 0.9f),
                    fontSize = 10.5.sp,
                    lineHeight = 14.sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }

            card.summary?.takeIf { it.isNotBlank() }?.let { sum ->
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = sum,
                    color = TextSecondary,
                    fontSize = 11.sp,
                    lineHeight = 15.5.sp,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        // 右下角缩放手柄角标（提示用户卡片依然可拉边角改大小）
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .width(11.dp)
                .height(2.dp)
                .background(borderColor.copy(alpha = 0.75f))
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .width(2.dp)
                .height(11.dp)
                .background(borderColor.copy(alpha = 0.75f))
        )
    }
}

/**
 * 三级自适应压感与速度笔锋计算：
 * - 米 / O / V 原生主动手写笔（`PointerType.Stylus`）：直接读取 4096/8192 级硬件真实压感；
 * - 第三方被动电容笔 & 手指触屏（`PointerType.Touch`）：屏蔽虚假的电容面积噪声，基于运笔速度计算平滑笔锋。
 */
private fun computeAdaptivePressure(
    change: PointerInputChange,
    prevWorld: Offset?,
    curWorld: Offset,
    dtMs: Long
): Float {
    if (change.type == PointerType.Stylus || change.type == PointerType.Eraser) {
        val hw = change.pressure
        if (!hw.isNaN() && hw > 0.01f && hw != 1.0f) {
            return (0.35f + 0.85f * hw.coerceIn(0.1f, 1.2f)).coerceIn(0.3f, 1.35f)
        }
    }
    if (prevWorld == null) return 1.0f
    val dist = (curWorld - prevWorld).getDistance()
    val velocity = dist / dtMs.coerceAtLeast(1L).toFloat()
    // 运笔慢则稳重略粗，快甩出锋则渐细
    val factor = (1.18f - (velocity * 0.22f).coerceIn(0f, 0.55f))
    return factor.coerceIn(0.55f, 1.2f)
}

/**
 * 将单条笔迹（画布世界坐标或卡片归一化锚定坐标）解析为当前画布世界坐标点序列。
 */
private fun resolveStrokeWorldPoints(
    stroke: InkStroke,
    cardMap: Map<String, InkCardSnapshot>
): List<InkPoint> {
    val startCard = stroke.anchorCardId?.let { cardMap[it] }
    if (stroke.toolType == InkToolType.ARROW && stroke.points.size >= 2) {
        val p0 = stroke.points.first()
        val p1 = stroke.points.last()
        val w0 = if (startCard != null) {
            InkPoint(
                x = startCard.x + p0.x * startCard.width,
                y = startCard.y + p0.y * startCard.height,
                pressure = p0.pressure
            )
        } else p0

        val endCard = stroke.endAnchorCardId?.let { cardMap[it] } ?: startCard
        val w1 = if (stroke.endAnchorCardId != null && endCard != null) {
            InkPoint(
                x = endCard.x + p1.x * endCard.width,
                y = endCard.y + p1.y * endCard.height,
                pressure = p1.pressure
            )
        } else if (startCard != null && stroke.endAnchorCardId == null) {
            InkPoint(
                x = startCard.x + p1.x * startCard.width,
                y = startCard.y + p1.y * startCard.height,
                pressure = p1.pressure
            )
        } else p1
        return listOf(w0, w1)
    }

    if (startCard == null) return stroke.points
    return stroke.points.map { pt ->
        InkPoint(
            x = startCard.x + pt.x * startCard.width,
            y = startCard.y + pt.y * startCard.height,
            pressure = pt.pressure
        )
    }
}

/**
 * 在 Compose DrawScope 中绘制矢量手写笔迹（钢笔、荧光笔、跨卡箭头、WriteBuddy 记忆遮挡胶带）。
 */
private fun DrawScope.drawVectorInkStroke(
    stroke: InkStroke,
    cardMap: Map<String, InkCardSnapshot>
) {
    val pts = resolveStrokeWorldPoints(stroke, cardMap)
    if (pts.isEmpty()) return

    val baseColor = Color(stroke.colorArgb)
    when (stroke.toolType) {
        InkToolType.ARROW -> {
            if (pts.size < 2) return
            val s = pts.first()
            val e = pts.last()
            val strokeW = max(2.4f, stroke.baseWidth)
            drawLine(
                color = baseColor,
                start = Offset(s.x, s.y),
                end = Offset(e.x, e.y),
                strokeWidth = strokeW,
                cap = StrokeCap.Round
            )
            val angle = atan2(e.y - s.y, e.x - s.x)
            val headLen = max(14f, strokeW * 3.8f)
            val a1 = angle - 0.5f
            val a2 = angle + 0.5f
            val headPath = Path().apply {
                moveTo(e.x, e.y)
                lineTo(e.x - headLen * cos(a1), e.y - headLen * sin(a1))
                moveTo(e.x, e.y)
                lineTo(e.x - headLen * cos(a2), e.y - headLen * sin(a2))
            }
            drawPath(
                path = headPath,
                color = baseColor,
                style = Stroke(width = strokeW, cap = StrokeCap.Round, join = StrokeJoin.Round)
            )
        }

        InkToolType.TAPE -> {
            // WriteBuddy 风格记忆遮挡胶带：未揭开时高不透明度遮住关键词，轻点后变为 22% 半透明供核对答案
            val path = buildBezierSmoothPath(pts)
            val tapeAlpha = if (stroke.tapeRevealed) 0.22f else 0.94f
            drawPath(
                path = path,
                color = baseColor.copy(alpha = tapeAlpha),
                style = Stroke(
                    width = max(18f, stroke.baseWidth * 5.2f),
                    cap = StrokeCap.Butt,
                    join = StrokeJoin.Round
                )
            )
        }

        InkToolType.HIGHLIGHTER -> {
            val path = buildBezierSmoothPath(pts)
            drawPath(
                path = path,
                color = baseColor.copy(alpha = 0.36f),
                style = Stroke(
                    width = max(14f, stroke.baseWidth * 4.0f),
                    cap = StrokeCap.Square,
                    join = StrokeJoin.Round
                )
            )
        }

        else -> {
            if (pts.size == 1) {
                val p = pts.first()
                drawCircle(
                    color = baseColor,
                    radius = max(1.5f, stroke.baseWidth * 0.55f),
                    center = Offset(p.x, p.y)
                )
                return
            }
            // 兼具中点二次贝塞尔丝滑度与真实压感笔锋
            for (i in 0 until pts.size - 1) {
                val p0 = pts[i]
                val p1 = pts[i + 1]
                val prev = if (i > 0) pts[i - 1] else p0
                val mid0 = Offset((prev.x + p0.x) * 0.5f, (prev.y + p0.y) * 0.5f)
                val mid1 = Offset((p0.x + p1.x) * 0.5f, (p0.y + p1.y) * 0.5f)
                val segPath = Path().apply {
                    if (i == 0) moveTo(p0.x, p0.y) else moveTo(mid0.x, mid0.y)
                    quadraticBezierTo(p0.x, p0.y, mid1.x, mid1.y)
                    if (i == pts.size - 2) lineTo(p1.x, p1.y)
                }
                val avgP = ((p0.pressure + p1.pressure) * 0.5f).coerceIn(0.3f, 1.35f)
                drawPath(
                    path = segPath,
                    color = baseColor,
                    style = Stroke(
                        width = max(1.2f, stroke.baseWidth * avgP),
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round
                    )
                )
            }
        }
    }
}

private fun buildBezierSmoothPath(pts: List<InkPoint>): Path {
    val path = Path()
    if (pts.isEmpty()) return path
    path.moveTo(pts.first().x, pts.first().y)
    if (pts.size == 1) {
        path.lineTo(pts.first().x + 0.5f, pts.first().y + 0.5f)
        return path
    }
    for (i in 1 until pts.size) {
        val prev = pts[i - 1]
        val curr = pts[i]
        val midX = (prev.x + curr.x) * 0.5f
        val midY = (prev.y + curr.y) * 0.5f
        path.quadraticBezierTo(prev.x, prev.y, midX, midY)
    }
    path.lineTo(pts.last().x, pts.last().y)
    return path
}

private fun isPointNearPolyline(target: Offset, pts: List<InkPoint>, threshold: Float): Boolean {
    if (pts.isEmpty()) return false
    if (pts.size == 1) {
        return hypot(target.x - pts[0].x, target.y - pts[0].y) <= threshold
    }
    for (i in 0 until pts.size - 1) {
        val a = Offset(pts[i].x, pts[i].y)
        val b = Offset(pts[i + 1].x, pts[i + 1].y)
        if (distancePointToSegment(target, a, b) <= threshold) return true
    }
    return false
}

private fun distancePointToSegment(p: Offset, a: Offset, b: Offset): Float {
    val vx = b.x - a.x
    val vy = b.y - a.y
    val lenSq = vx * vx + vy * vy
    if (lenSq <= 1e-4f) return hypot(p.x - a.x, p.y - a.y)
    val t = (((p.x - a.x) * vx + (p.y - a.y) * vy) / lenSq).coerceIn(0f, 1f)
    val projX = a.x + t * vx
    val projY = a.y + t * vy
    return hypot(p.x - projX, p.y - projY)
}
