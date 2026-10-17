package com.treechat.workbench.ui.reader

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.treechat.workbench.data.prefs.AppSettings
import com.treechat.workbench.data.prefs.InkPoint
import com.treechat.workbench.data.prefs.InkStroke
import com.treechat.workbench.data.prefs.InkToolType
import java.util.UUID
import kotlin.math.max

/**
 * 阅读器专用的手写笔迹透明图层（完全复用笔记系统的数据结构 InkStroke 与压感体系）。
 *
 * - 当 [active] = true：拦截手势，允许手写笔、电容笔或手指进行钢笔/荧光笔/橡皮擦批注；
 * - 当 [active] = false：手势完全透传给阅读器（正常翻页与长按选词），但当前页的笔迹依然常驻渲染在正文之上！
 * - 存储：按 documentId 与 pageIndex 保存在 SharedPreferences 中，绝不侵入 Room 数据库。
 */
@Composable
fun ReaderInkOverlay(
    documentId: String,
    pageIndex: Int,
    active: Boolean,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme

    val strokes = remember(documentId, pageIndex) { mutableStateListOf<InkStroke>() }
    val undoStack = remember(documentId, pageIndex) { mutableListOf<InkStroke>() }

    // 工具状态：PEN / HIGHLIGHTER / ERASER
    var currentTool by remember { mutableStateOf(InkToolType.PEN) }
    var currentColor by remember { mutableStateOf(Color(0xFFE53935)) } // 批注红
    var currentStrokeWidth by remember { mutableFloatStateOf(4f) }

    var currentDrawingPoints = remember { mutableStateListOf<InkPoint>() }

    // 从存储中加载当前页笔迹
    LaunchedEffect(documentId, pageIndex) {
        val loaded = loadPageStrokes(context, documentId, pageIndex)
        strokes.clear()
        strokes.addAll(loaded)
        undoStack.clear()
    }

    // 持久化当前页笔迹
    fun persistStrokes() {
        savePageStrokes(context, documentId, pageIndex, strokes.toList())
    }

    Box(modifier = modifier.fillMaxSize()) {

        // ==================== 1. 笔迹绘制层（无论是激活还是非激活，笔迹始终常驻呈现） ====================
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (active) {
                        // 激活手写模式时，接管单指与手写笔绘制
                        Modifier.pointerInput(documentId, pageIndex, currentTool, currentColor, currentStrokeWidth) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = true)
                                down.consume()

                                val initialPressure = down.pressure.coerceIn(0.2f, 1.3f)
                                currentDrawingPoints.clear()
                                currentDrawingPoints.add(InkPoint(down.position.x, down.position.y, initialPressure))

                                if (currentTool == InkToolType.ERASER) {
                                    eraseAt(down.position, strokes)
                                    persistStrokes()
                                }

                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                    if (!change.pressed) {
                                        // 抬手：生成最终的 InkStroke
                                        if (currentTool != InkToolType.ERASER && currentDrawingPoints.size > 1) {
                                            val colorArgb = if (currentTool == InkToolType.HIGHLIGHTER) {
                                                Color(0xFFFFEB3B).copy(alpha = 0.42f).toArgb()
                                            } else {
                                                currentColor.toArgb()
                                            }
                                            val baseW = if (currentTool == InkToolType.HIGHLIGHTER) 24f else currentStrokeWidth

                                            val newStroke = InkStroke(
                                                id = UUID.randomUUID().toString(),
                                                tool = currentTool.name,
                                                colorArgb = colorArgb.toLong(),
                                                baseWidth = baseW,
                                                points = currentDrawingPoints.toList()
                                            )
                                            strokes.add(newStroke)
                                            persistStrokes()
                                        }
                                        currentDrawingPoints.clear()
                                        break
                                    }

                                    change.consume()
                                    val p = change.pressure.coerceIn(0.2f, 1.3f)
                                    val pos = change.position
                                    currentDrawingPoints.add(InkPoint(pos.x, pos.y, p))

                                    if (currentTool == InkToolType.ERASER) {
                                        if (eraseAt(pos, strokes)) {
                                            persistStrokes()
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        Modifier
                    }
                )
        ) {
            // 绘制已保存的每条笔迹
            strokes.forEach { stroke ->
                drawSingleStroke(stroke)
            }

            // 绘制正在书写中的当前轨迹
            if (active && currentDrawingPoints.size > 1 && currentTool != InkToolType.ERASER) {
                val color = if (currentTool == InkToolType.HIGHLIGHTER) {
                    Color(0xFFFFEB3B).copy(alpha = 0.42f)
                } else {
                    currentColor
                }
                val width = if (currentTool == InkToolType.HIGHLIGHTER) 24f else currentStrokeWidth
                val path = Path()
                path.moveTo(currentDrawingPoints.first().x, currentDrawingPoints.first().y)
                for (i in 1 until currentDrawingPoints.size) {
                    val pt = currentDrawingPoints[i]
                    path.lineTo(pt.x, pt.y)
                }
                drawPath(
                    path = path,
                    color = color,
                    style = Stroke(
                        width = width,
                        cap = if (currentTool == InkToolType.HIGHLIGHTER) StrokeCap.Square else StrokeCap.Round,
                        join = StrokeJoin.Round
                    )
                )
            }
        }

        // ==================== 2. 悬浮手写工具栏（只在 active 时展示在顶部） ====================
        AnimatedVisibility(
            visible = active,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 10.dp)
                .zIndex(999f)
        ) {
            Surface(
                color = scheme.surfaceContainerHigh.copy(alpha = 0.95f),
                shape = RoundedCornerShape(20.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, scheme.outlineVariant),
                shadowElevation = 6.dp
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // 工具切换：钢笔
                    InkToolChip(
                        selected = currentTool == InkToolType.PEN,
                        label = "钢笔",
                        onClick = {
                            currentTool = InkToolType.PEN
                            currentStrokeWidth = 4f
                        }
                    )

                    // 工具切换：荧光笔
                    InkToolChip(
                        selected = currentTool == InkToolType.HIGHLIGHTER,
                        label = "荧光笔",
                        onClick = {
                            currentTool = InkToolType.HIGHLIGHTER
                            currentStrokeWidth = 24f
                        }
                    )

                    // 工具切换：橡皮擦
                    InkToolChip(
                        selected = currentTool == InkToolType.ERASER,
                        label = "橡皮",
                        onClick = {
                            currentTool = InkToolType.ERASER
                        }
                    )

                    // 颜色选择小圆点（钢笔用）
                    if (currentTool == InkToolType.PEN) {
                        listOf(
                            Color(0xFFE53935) to "红",
                            Color(0xFF1E88E5) to "蓝",
                            Color(0xFF43A047) to "绿",
                            Color(0xFF212121) to "黑"
                        ).forEach { (c, _) ->
                            Box(
                                modifier = Modifier
                                    .size(20.dp)
                                    .background(c, CircleShape)
                                    .then(
                                        if (currentColor == c) {
                                            Modifier.border(2.dp, scheme.onSurface, CircleShape)
                                        } else {
                                            Modifier
                                        }
                                    )
                                    .clickable { currentColor = c }
                            )
                        }
                    }

                    // 撤销按钮
                    IconButton(
                        onClick = {
                            if (strokes.isNotEmpty()) {
                                val popped = strokes.removeAt(strokes.lastIndex)
                                undoStack.add(popped)
                                persistStrokes()
                            }
                        },
                        enabled = strokes.isNotEmpty(),
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Undo,
                            contentDescription = "撤销",
                            tint = if (strokes.isNotEmpty()) scheme.primary else scheme.outline,
                            modifier = Modifier.size(16.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(4.dp))

                    // 退出/完成按钮
                    IconButton(
                        onClick = onClose,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "退出手写批注",
                            tint = scheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun InkToolChip(
    selected: Boolean,
    label: String,
    onClick: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (selected) scheme.primary else scheme.surfaceVariant,
        modifier = Modifier.clickable { onClick() }
    ) {
        Text(
            text = label,
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) scheme.onPrimary else scheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp)
        )
    }
}

/** 绘制单条矢量笔迹。 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawSingleStroke(stroke: InkStroke) {
    if (stroke.points.size < 2) return
    val path = Path()
    path.moveTo(stroke.points.first().x, stroke.points.first().y)
    for (i in 1 until stroke.points.size) {
        val pt = stroke.points[i]
        path.lineTo(pt.x, pt.y)
    }
    val isHighlighter = stroke.tool == InkToolType.HIGHLIGHTER.name
    drawPath(
        path = path,
        color = Color(stroke.colorArgb),
        style = Stroke(
            width = stroke.baseWidth,
            cap = if (isHighlighter) StrokeCap.Square else StrokeCap.Round,
            join = StrokeJoin.Round
        )
    )
}

/** 擦除触碰到的笔迹。 */
private fun eraseAt(pos: Offset, strokes: MutableList<InkStroke>): Boolean {
    val hitRadius = 24f
    val toRemove = strokes.filter { stroke ->
        stroke.points.any { pt ->
            val dx = pt.x - pos.x
            val dy = pt.y - pos.y
            dx * dx + dy * dy < hitRadius * hitRadius
        }
    }
    if (toRemove.isNotEmpty()) {
        strokes.removeAll(toRemove)
        return true
    }
    return false
}

// ---------------- 偏好数据存取（无 Room 依赖） ----------------

private fun prefs(context: Context) =
    context.applicationContext.getSharedPreferences(AppSettings.PREFS, Context.MODE_PRIVATE)

private fun pageKey(docId: String, page: Int) = "reader_ink_${docId}_p$page"

private fun loadPageStrokes(context: Context, docId: String, page: Int): List<InkStroke> {
    val raw = prefs(context).getString(pageKey(docId, page), null) ?: return emptyList()
    return try {
        val type = object : TypeToken<List<InkStroke>>() {}.type
        Gson().fromJson<List<InkStroke>>(raw, type) ?: emptyList()
    } catch (_: Exception) {
        emptyList()
    }
}

private fun savePageStrokes(context: Context, docId: String, page: Int, strokes: List<InkStroke>) {
    prefs(context).edit().putString(pageKey(docId, page), Gson().toJson(strokes)).apply()
}
