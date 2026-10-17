package com.treechat.workbench.scan

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PointF
import android.graphics.RectF
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.treechat.workbench.ui.theme.AccentCyan
import com.treechat.workbench.ui.theme.AccentIceBlue
import com.treechat.workbench.ui.theme.AccentNeonGreen
import com.treechat.workbench.ui.theme.PanelSurface
import com.treechat.workbench.ui.theme.TextMuted
import com.treechat.workbench.ui.theme.TextPrimary
import com.treechat.workbench.ui.theme.TextSecondary
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 扫描工作台。
 *
 * ## 核心是"拍一半、能续"
 * 每一页拍完**立刻**编码成 JPEG 落盘（见 [ScanSessionStore.appendPage]），
 * 中途退出、杀进程、隔天再进来，点"继续扫描"就从第 N+1 页接着拍。
 * 这是开源方案相对扫描王最实在的优势——云端 App 做不到"处理到一半关机"。
 *
 * ## 拍照用系统相机而不是自己接 CameraX
 * 零依赖（省约 3MB）、拍照质量由系统保证、不用自己处理相机权限与生命周期。
 * 代价是拍完要经相册中转，且界面里没有实时滤镜预览——对"把纸拍成扫描件"
 * 这个目标来说完全够用。
 */
@Composable
fun ScanWorkbench(
    store: ScanSessionStore,
    /** 调起系统相机拍一张，返回 JPEG 字节；用户取消返回 null。 */
    onTakePhoto: suspend () -> ByteArray?,
    /** 从相册选一张图片，返回 JPEG 字节。 */
    onPickImage: suspend () -> ByteArray?,
    /** 合成完成后回调：交给书架把 PDF 登记进去。 */
    onPdfReady: (sessionId: String, title: String, bytes: ByteArray, pages: Int, textPages: Int) -> Unit,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    // LocalContext 只能在组合期取：合成 PDF 的协程里再用它就编译不过
    // （"Composable invocations can only happen from…"）。
    val appContext = androidx.compose.ui.platform.LocalContext.current
    var session by remember { mutableStateOf(store.current ?: store.newSession()) }
    var previewPage by remember { mutableStateOf<ScanPage?>(null) }
    var composing by remember { mutableStateOf(false) }
    var composeProgress by remember { mutableStateOf(0 to 0) }
    var message by remember { mutableStateOf<String?>(null) }
    var showSettings by remember { mutableStateOf(false) }

    // 打开时若没有会话，恢复最近一个（续拍）
    LaunchedEffect(Unit) {
        if (store.current == null) {
            val last = store.sessions.firstOrNull { it.pages.isNotEmpty() }
            if (last != null) store.openSession(last.id)
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .fillMaxWidth()
                // ⚠️ 高度用 **heightIn(max=)**，不能用 fillMaxSize(0.92f)。
                //
                // Dialog 开了 usePlatformDefaultWidth=false 后，它的父层给的是
                // **整屏**尺寸；再取 0.92 就是"屏高的 92%"，叠加书架页自身已有的
                // 上下内边距（状态栏 + 导航栏）之后必然超出屏幕底部 ——
                // 表现就是书架页下半屏被这个面板压住、且面板里的字只显示一半。
                //
                // heightIn(max=) 交由 Compose 按**父层实际给的约束**收敛，
                // 父层给多少就最多占多少，不会溢出。
                .heightIn(max = 720.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(Color(0xFF14171C))
                .border(1.dp, Color(0xFF2C323D), RoundedCornerShape(18.dp))
        ) {
            Column(modifier = Modifier.fillMaxSize()) {

                // ---- 顶栏 ----
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.ArrowBack, "返回", tint = TextSecondary, modifier = Modifier.size(20.dp))
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = session.title,
                            color = TextPrimary,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = if (session.pages.isEmpty()) "还没有页面，拍照或从相册导入开始"
                            else "已拍 ${session.pages.size} 页 · 可随时退出，下次接着拍",
                            color = TextMuted,
                            fontSize = 11.sp
                        )
                    }
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Default.Settings, "扫描设置", tint = TextSecondary, modifier = Modifier.size(19.dp))
                    }
                }

                // ---- 页面网格 ----
                Box(modifier = Modifier.weight(1f)) {
                    if (session.pages.isEmpty()) {
                        EmptyHint()
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(session.pages, key = { it.id }) { page ->
                                PageCard(
                                    store = store,
                                    session = session,
                                    page = page,
                                    onOpen = { previewPage = page },
                                    onDelete = {
                                        store.deletePage(session, page.index)
                                        session = session.copy(pages = session.pages.filterNot { it.id == page.id })
                                    },
                                    onMoveUp = if (page.index <= 1) null else {
                                        {
                                            store.movePage(session, page.index, -1)
                                            session = store.current ?: session
                                        }
                                    },
                                    onMoveDown = if (page.index >= session.pages.size) null else {
                                        {
                                            store.movePage(session, page.index, +1)
                                            session = store.current ?: session
                                        }
                                    }
                                )
                            }
                        }
                    }
                }

                message?.let {
                    Text(
                        text = it,
                        color = AccentCyan,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
                    )
                }

                // ---- 底部操作 ----
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ActionButton(
                        icon = Icons.Default.CameraAlt,
                        label = "拍照",
                        accent = AccentNeonGreen,
                        enabled = !composing,
                        modifier = Modifier.weight(1f)
                    ) {
                        scope.launch {
                            val jpeg = onTakePhoto()
                            if (jpeg != null) {
                                addPage(session, store, jpeg, message = { m -> message = m })
                                session = store.current ?: session
                            }
                        }
                    }
                    ActionButton(
                        icon = Icons.Default.Image,
                        label = "相册导入",
                        accent = AccentIceBlue,
                        enabled = !composing,
                        modifier = Modifier.weight(1f)
                    ) {
                        scope.launch {
                            val jpeg = onPickImage()
                            if (jpeg != null) {
                                addPage(session, store, jpeg, message = { m -> message = m })
                                session = store.current ?: session
                            }
                        }
                    }
                    ActionButton(
                        icon = Icons.Default.PictureAsPdf,
                        label = if (composing) "生成中 ${composeProgress.first}/${composeProgress.second}" else "生成 PDF",
                        accent = AccentCyan,
                        enabled = !composing && session.pages.isNotEmpty(),
                        modifier = Modifier.weight(1.3f)
                    ) {
                        composing = true
                        message = "正在合成带文字层的 PDF（每页需 OCR，稍候）…"
                        scope.launch {
                            val res = withContext(Dispatchers.IO) {
                                ScanPdfComposer.compose(
                                    context = appContext,
                                    store = store,
                                    session = session,
                                    writeTextLayer = true
                                ) { cur, total -> composeProgress = cur to total }
                            }
                            composing = false
                            if (res == null) {
                                message = "合成失败，请重试"
                            } else {
                                message = "已生成 ${res.pageCount} 页 PDF（${res.textLayerPages} 页含文字层，可搜索）"
                                onPdfReady(session.id, session.title, res.pdfBytes, res.pageCount, res.textLayerPages)
                            }
                        }
                    }
                }
            }
        }
    }

    // ---- 单页预览 + 手动裁切 ----
    previewPage?.let { page ->
        PagePreviewDialog(
            store = store,
            session = session,
            page = page,
            colorMode = session.colorMode,
            autoCrop = session.autoCropEnabled,
            onDismiss = { previewPage = null },
            onApplyManualCrop = { quad ->
                // 手动裁切：把该页按四边形重编码覆盖回去
                scope.launch {
                    withContext(Dispatchers.IO) {
                        val f = store.pageFileOf(session.id, page)
                        val bmp = BitmapFactory.decodeFile(f.absolutePath)
                        if (bmp != null) {
                            val corrected = ScanImageProcessor.perspectiveCorrect(bmp, quad)
                            val processed = ScanImageProcessor.process(
                                corrected, session.colorMode, autoCrop = false
                            )
                            writeJpeg(f, processed.bitmap)
                            bmp.recycle()
                        }
                    }
                    previewPage = null
                    message = "已按手动框选裁切第 ${page.index} 页"
                }
            }
        )
    }

    // ---- 设置（彩色/黑白、自动裁边）----
    if (showSettings) {
        ScanSettingsDialog(
            session = session,
            onDismiss = { showSettings = false },
            onColorMode = { mode ->
                store.persistSession(session.copy(colorMode = mode))
                session = session.copy(colorMode = mode)
            },
            onAutoCrop = { enabled ->
                store.persistSession(session.copy(autoCropEnabled = enabled))
                session = session.copy(autoCropEnabled = enabled)
            }
        )
    }
}

/** 编码 + 落盘 + 更新会话（每页独立，立刻生效）。 */
private suspend fun addPage(
    session: ScanSession,
    store: ScanSessionStore,
    jpegBytes: ByteArray,
    message: (String) -> Unit
) = withContext(Dispatchers.IO) {
    val bmp = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
    if (bmp == null) {
        message("图片解析失败")
        return@withContext
    }
    val processed = ScanImageProcessor.process(
        bmp, session.colorMode, autoCrop = session.autoCropEnabled
    )
    val out = encodeJpeg(processed.bitmap, quality = 88)
    bmp.recycle()
    processed.bitmap.recycle()
    val n = store.appendPage(session, out)
    message("已添加第 $n 页")
}

internal fun encodeJpeg(bmp: Bitmap, quality: Int): ByteArray {
    val out = ByteArrayOutputStream()
    bmp.compress(Bitmap.CompressFormat.JPEG, quality, out)
    return out.toByteArray()
}

internal fun writeJpeg(file: java.io.File, bmp: Bitmap) {
    file.writeBytes(encodeJpeg(bmp, 90))
}

// ------------------------------------------------------------ 小组件

@Composable
private fun EmptyHint() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Default.CameraAlt,
                contentDescription = null,
                tint = TextMuted,
                modifier = Modifier.size(44.dp)
            )
            Spacer(Modifier.height(10.dp))
            Text("拍照或从相册导入第一页", color = TextMuted, fontSize = 13.sp)
        }
    }
}

@Composable
private fun ActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    accent: Color,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(42.dp),
        shape = RoundedCornerShape(10.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = accent.copy(alpha = 0.16f),
            contentColor = accent,
            disabledContainerColor = Color(0xFF232830),
            disabledContentColor = TextMuted
        )
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(5.dp))
        Text(label, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}