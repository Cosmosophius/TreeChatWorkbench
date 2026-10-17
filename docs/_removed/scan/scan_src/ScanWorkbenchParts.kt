package com.treechat.workbench.scan

import android.graphics.BitmapFactory
import android.graphics.PointF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.treechat.workbench.ui.theme.AccentCyan
import com.treechat.workbench.ui.theme.AccentIceBlue
import com.treechat.workbench.ui.theme.AccentNeonGreen
import com.treechat.workbench.ui.theme.TextMuted
import com.treechat.workbench.ui.theme.TextPrimary
import com.treechat.workbench.ui.theme.TextSecondary
import java.io.File

/** 网格里的一张页缩略图，带删除 / 上下移。 */
@Composable
internal fun PageCard(
    store: ScanSessionStore,
    session: ScanSession,
    page: ScanPage,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
    onMoveUp: (() -> Unit)?,
    onMoveDown: (() -> Unit)?
) {
    val file = store.pageFileOf(session.id, page)
    val bitmap = remember(file.absolutePath, file.lastModified()) {
        if (file.exists()) BitmapFactory.decodeFile(file.absolutePath) else null
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF1B1F26))
            .clickable { onOpen() }
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.72f)
                    .background(Color(0xFF11141A)),
                contentAlignment = Alignment.Center
            ) {
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "第 ${page.index} 页",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Text("已丢失", color = TextMuted, fontSize = 11.sp)
                }
                // 页码角标
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(Color(0xCC161A20))
                        .padding(horizontal = 5.dp, vertical = 2.dp)
                ) {
                    Text("${page.index}", color = AccentCyan, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
                // 操作角标
                Row(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    MiniAction(Icons.Default.KeyboardArrowUp, "上移", onMoveUp)
                    MiniAction(Icons.Default.KeyboardArrowDown, "下移", onMoveDown)
                    MiniAction(Icons.Default.Delete, "删除", onDelete, tint = Color(0xFFFF8A80))
                }
            }
        }
    }
}

@Composable
private fun MiniAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    desc: String,
    onClick: (() -> Unit)?,
    tint: Color = TextSecondary
) {
    Box(
        modifier = Modifier
            .size(26.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xCC161A20))
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = desc, tint = if (onClick != null) tint else Color(0xFF4A505A), modifier = Modifier.size(14.dp))
    }
}

/**
 * 单页预览 + **手动框选裁切**。
 *
 * 自动找边框失败时（纸和背景同色 / 只露一角）靠这里兜底：
 * 用户拖四个角，自己框出要保留的区域。这与扫描器的"手动裁剪"完全等价。
 */
@Composable
internal fun PagePreviewDialog(
    store: ScanSessionStore,
    session: ScanSession,
    page: ScanPage,
    colorMode: ScanColorMode,
    autoCrop: Boolean,
    onDismiss: () -> Unit,
    onApplyManualCrop: (Array<PointF>) -> Unit
) {
    val file = store.pageFileOf(session.id, page)
    val bitmap = remember(file.absolutePath) {
        if (file.exists()) BitmapFactory.decodeFile(file.absolutePath) else null
    }

    // 四角（归一化 0..1，随预览区尺寸换算成实际像素）
    var quad by remember {
        mutableStateOf(arrayOf(
            PointF(0f, 0f), PointF(1f, 0f), PointF(1f, 1f), PointF(0f, 1f)
        ))
    }
    var dragging by remember { mutableStateOf(-1) }   // 正在拖的角索引，-1 = 无

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth()
                // 同 ScanWorkbench：不用 fillMaxSize(比例) —— 那按整屏算，
                // 会溢出父层约束导致底部被裁。
                .heightIn(max = 700.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF14171C))
                .border(1.dp, Color(0xFF2C323D), RoundedCornerShape(16.dp))
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("第 ${page.index} 页", color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, "关闭", tint = TextSecondary)
                    }
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .background(Color(0xFF0D1014))
                        .pointerInput(Unit) {
                            // 拖角：把按下点归一到预览区（0..1），得到要拖的角。
                            // size 来自 PointerInputScope，即本预览区的实际尺寸 ——
                            // 不能用 Canvas 的 size（那是绘制作用域，拖拽回调里拿不到）。
                            detectDragGestures(
                                onDragStart = { pos ->
                                    dragging = nearestCorner(
                                        quad, pos,
                                        size.width.toFloat(), size.height.toFloat()
                                    )
                                },
                                onDrag = { change, _ ->
                                    if (dragging >= 0) {
                                        val nx = (change.position.x / size.width.toFloat()).coerceIn(0f, 1f)
                                        val ny = (change.position.y / size.height.toFloat()).coerceIn(0f, 1f)
                                        quad = quad.copyOf().also { it[dragging] = PointF(nx, ny) }
                                    }
                                },
                                onDragEnd = { dragging = -1 },
                                onDragCancel = { dragging = -1 }
                            )
                        }
                ) {
                    // 在 @Composable 作用域取一次密度，把 px 值带进 DrawScope。
                    // （Dp.toPx() 需要 Density 接收者，而 Canvas 的 lambda 里拿不到
                    //  能解析的密度，所以提前算好是最不容易出错的做法。）
                    val borderPx = with(androidx.compose.ui.platform.LocalDensity.current) { 2.dp.toPx() }
                    val handlePx = with(androidx.compose.ui.platform.LocalDensity.current) { 7.dp.toPx() }
                    val dotPx = with(androidx.compose.ui.platform.LocalDensity.current) { 3.dp.toPx() }
                    // 主题色是 `val`（非常量）——在 DrawScope 的非 composable lambda 里
                    // 直接读会被编译期判成 composable 调用而报错。组合期先快照成本地值。
                    val accent = AccentNeonGreen
                    val white = Color.White
                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize()
                        )
                        // 裁切框：四角连线 + 半透明遮罩暗示"框外会被裁掉"
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val p0 = Offset(quad[0].x * size.width, quad[0].y * size.height)
                            val p1 = Offset(quad[1].x * size.width, quad[1].y * size.height)
                            val p2 = Offset(quad[2].x * size.width, quad[2].y * size.height)
                            val p3 = Offset(quad[3].x * size.width, quad[3].y * size.height)
                            // 四边形边框
                            drawPath(
                                androidx.compose.ui.graphics.Path().apply {
                                    moveTo(p0.x, p0.y); lineTo(p1.x, p1.y)
                                    lineTo(p2.x, p2.y); lineTo(p3.x, p3.y); close()
                                },
                                color = accent,
                                style = Stroke(width = borderPx)
                            )
                            // 四角把手（可拖）
                            listOf(p0, p1, p2, p3).forEach { p ->
                                drawCircle(accent, radius = handlePx, center = p)
                                drawCircle(white, radius = dotPx, center = p)
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButtonCyan(
                        label = "重置框",
                        onClick = {
                            quad = arrayOf(PointF(0f, 0f), PointF(1f, 0f), PointF(1f, 1f), PointF(0f, 1f))
                        }
                    )
                    Button(
                        onClick = {
                            val bmpW = bitmap?.width ?: return@Button
                            val bmpH = bitmap?.height ?: return@Button
                            onApplyManualCrop(
                                arrayOf(
                                    PointF(quad[0].x * bmpW, quad[0].y * bmpH),
                                    PointF(quad[1].x * bmpW, quad[1].y * bmpH),
                                    PointF(quad[2].x * bmpW, quad[2].y * bmpH),
                                    PointF(quad[3].x * bmpW, quad[3].y * bmpH)
                                )
                            )
                        },
                        enabled = bitmap != null,
                        modifier = Modifier.weight(1f).height(40.dp),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = AccentNeonGreen, contentColor = Color.Black)
                    ) {
                        Icon(Icons.Default.ContentCut, null, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(5.dp))
                        Text("按此框裁切", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

/** 找到离按下点最近的角（阈值内）。 */
private fun nearestCorner(quad: Array<PointF>, pos: Offset, w: Float, h: Float): Int {
    var best = -1
    var bestD = Float.MAX_VALUE
    // 触摸半径按预览区宽度取 ~11%，够手指点中角又不至于误触邻角
    val threshold = (w * 0.11f).coerceIn(24f, 96f)
    quad.forEachIndexed { i, c ->
        val dx = c.x * w - pos.x
        val dy = c.y * h - pos.y
        val d = dx * dx + dy * dy
        if (d < bestD) { bestD = d; best = i }
    }
    val threshSq = threshold * threshold
    return if (bestD <= threshSq) best else -1
}

@Composable
private fun OutlinedButtonCyan(label: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.height(40.dp),
        shape = RoundedCornerShape(10.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF232830), contentColor = AccentCyan)
    ) {
        Text(label, fontSize = 12.sp)
    }
}

/** 设置对话框：彩色/黑白 + 自动裁边开关。 */
@Composable
internal fun ScanSettingsDialog(
    session: ScanSession,
    onDismiss: () -> Unit,
    onColorMode: (ScanColorMode) -> Unit,
    onAutoCrop: (Boolean) -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF14171C))
                .border(1.dp, Color(0xFF2C323D), RoundedCornerShape(16.dp))
                .padding(18.dp)
        ) {
            Text("扫描设置", color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(14.dp))

            // 彩色 / 黑白
            Text("输出色彩", color = TextSecondary, fontSize = 12.sp)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ScanColorMode.entries.forEach { mode ->
                    val sel = mode == session.colorMode
                    Button(
                        onClick = { onColorMode(mode) },
                        modifier = Modifier.height(36.dp),
                        shape = RoundedCornerShape(9.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (sel) AccentNeonGreen else Color(0xFF232830),
                            contentColor = if (sel) Color.Black else TextSecondary
                        )
                    ) { Text(mode.displayName, fontSize = 12.sp) }
                }
            }

            Spacer(Modifier.height(16.dp))

            // 自动裁边开关
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("自动识别边缘并裁切", color = TextSecondary, fontSize = 12.sp)
                    Text("自动找纸张边框并拉正；失败时可在预览里手动框选", color = TextMuted, fontSize = 10.sp)
                }
                Switch(checked = session.autoCropEnabled, onCheckedChange = onAutoCrop)
            }

            Spacer(Modifier.height(18.dp))
            Button(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth().height(38.dp),
                shape = RoundedCornerShape(9.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF232830), contentColor = TextSecondary)
            ) { Text("完成", fontSize = 12.sp) }
        }
    }
}