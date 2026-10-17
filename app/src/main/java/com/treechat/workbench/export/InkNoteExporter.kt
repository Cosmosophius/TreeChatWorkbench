package com.treechat.workbench.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.treechat.workbench.data.prefs.InkCardSnapshot
import com.treechat.workbench.data.prefs.InkNoteBoard
import com.treechat.workbench.data.prefs.InkPoint
import com.treechat.workbench.data.prefs.InkStroke
import com.treechat.workbench.data.prefs.InkToolType
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 将「活体手写笔记白板」（[InkNoteBoard]）连同当前卡片排布、尺寸及全部手写批注笔迹
 * 一并导出为矢量 PDF 或高清 PNG。
 */
object InkNoteExporter {

    private const val PADDING = 64f
    private const val HEADER_HEIGHT = 72f

    fun outputFile(context: Context, board: InkNoteBoard, isPdf: Boolean): File {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val safeTitle = board.title
            .replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_")
            .take(36)
            .ifBlank { "ink_note" }
        val ext = if (isPdf) "pdf" else "png"
        val dir = File(context.cacheDir, "exports")
        dir.mkdirs()
        return File(dir, "${safeTitle}_$stamp.$ext")
    }

    fun exportPdf(board: InkNoteBoard, outFile: File): File {
        val bounds = computeContentBounds(board)
        val pageWidth = max(842, (bounds.width() + PADDING * 2).roundToInt())
        val pageHeight = max(595, (bounds.height() + PADDING * 2 + HEADER_HEIGHT).roundToInt())

        val doc = PdfDocument()
        try {
            val info = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, 1).create()
            val page = doc.startPage(info)
            renderBoardToCanvas(
                canvas = page.canvas,
                board = board,
                bounds = bounds,
                pageWidth = pageWidth.toFloat(),
                pageHeight = pageHeight.toFloat()
            )
            doc.finishPage(page)
            outFile.parentFile?.mkdirs()
            FileOutputStream(outFile).use { doc.writeTo(it) }
        } finally {
            doc.close()
        }
        return outFile
    }

    fun exportPng(board: InkNoteBoard, outFile: File): File {
        val bounds = computeContentBounds(board)
        val rawW = bounds.width() + PADDING * 2
        val rawH = bounds.height() + PADDING * 2 + HEADER_HEIGHT
        // 控制最大纹理尺寸在 4096 以内，防止超大画布 OOM
        val maxSide = max(rawW, rawH)
        val scale = if (maxSide > 3840f) 3840f / maxSide else 1.25f
        val bmpW = max(960, (rawW * scale).roundToInt())
        val bmpH = max(640, (rawH * scale).roundToInt())

        val bitmap = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.save()
        canvas.scale(scale, scale)
        renderBoardToCanvas(
            canvas = canvas,
            board = board,
            bounds = bounds,
            pageWidth = rawW,
            pageHeight = rawH
        )
        canvas.restore()

        outFile.parentFile?.mkdirs()
        FileOutputStream(outFile).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        bitmap.recycle()
        return outFile
    }

    private fun computeContentBounds(board: InkNoteBoard): RectF {
        var minX = Float.POSITIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY

        val cardMap = board.cards.associateBy { it.id }

        board.cards.forEach { card ->
            minX = min(minX, card.x)
            minY = min(minY, card.y)
            maxX = max(maxX, card.x + card.width)
            maxY = max(maxY, card.y + card.height)
        }

        board.strokes.forEach { stroke ->
            val worldPts = resolveWorldPoints(stroke, cardMap)
            worldPts.forEach { pt ->
                minX = min(minX, pt.x - stroke.baseWidth)
                minY = min(minY, pt.y - stroke.baseWidth)
                maxX = max(maxX, pt.x + stroke.baseWidth)
                maxY = max(maxY, pt.y + stroke.baseWidth)
            }
        }

        if (!minX.isFinite() || !minY.isFinite()) {
            return RectF(0f, 0f, 960f, 600f)
        }
        return RectF(minX, minY, max(minX + 640f, maxX), max(minY + 420f, maxY))
    }

    private fun resolveWorldPoints(
        stroke: InkStroke,
        cardMap: Map<String, InkCardSnapshot>
    ): List<InkPoint> {
        val anchorId = stroke.anchorCardId
        val startCard = if (anchorId != null) cardMap[anchorId] else null

        if (stroke.toolType == InkToolType.ARROW && stroke.points.size >= 2) {
            val p0 = stroke.points.first()
            val p1 = stroke.points.last()
            val worldStart = if (startCard != null) {
                InkPoint(
                    x = startCard.x + p0.x * startCard.width,
                    y = startCard.y + p0.y * startCard.height,
                    pressure = p0.pressure
                )
            } else p0

            val endCard = stroke.endAnchorCardId?.let { cardMap[it] } ?: startCard
            val worldEnd = if (stroke.endAnchorCardId != null && endCard != null) {
                InkPoint(
                    x = endCard.x + p1.x * endCard.width,
                    y = endCard.y + p1.y * endCard.height,
                    pressure = p1.pressure
                )
            } else if (startCard != null && stroke.endAnchorCardId == null) {
                // 起点在卡片内但终点为世界坐标或同卡相对坐标
                InkPoint(
                    x = startCard.x + p1.x * startCard.width,
                    y = startCard.y + p1.y * startCard.height,
                    pressure = p1.pressure
                )
            } else p1
            return listOf(worldStart, worldEnd)
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

    private fun renderBoardToCanvas(
        canvas: Canvas,
        board: InkNoteBoard,
        bounds: RectF,
        pageWidth: Float,
        pageHeight: Float
    ) {
        // 1. 绘制温润深色宣墨背景
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(15, 20, 19)
            style = Paint.Style.FILL
        }
        canvas.drawRect(0f, 0f, pageWidth, pageHeight, bgPaint)

        // 2. 顶部题签栏
        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(232, 236, 234)
            textSize = 22f
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
        }
        val subPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(138, 150, 146)
            textSize = 13f
        }
        canvas.drawText("【条畅 · 手写笔记】${board.title}", PADDING, PADDING - 12f, titlePaint)
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(board.updatedAt))
        canvas.drawText(
            "共 ${board.cards.size} 张活体卡片 · ${board.strokes.size} 处手写批注 · 更新于 $stamp",
            PADDING,
            PADDING + 12f,
            subPaint
        )

        // 3. 平移至内容原点
        val offsetX = PADDING - bounds.left
        val offsetY = PADDING + HEADER_HEIGHT - bounds.top
        canvas.save()
        canvas.translate(offsetX, offsetY)

        val cardMap = board.cards.associateBy { it.id }

        // 4. 先画卡片之间的三次贝塞尔连线
        val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(175, 56, 189, 148)
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }
        val edgePath = Path()
        board.cards.forEach { child ->
            val parent = child.parentId?.let { cardMap[it] } ?: return@forEach
            val sx = parent.x + parent.width
            val sy = parent.y + parent.height * 0.5f
            val ex = child.x
            val ey = child.y + child.height * 0.5f
            val dx = max(48f, (ex - sx) * 0.5f)
            edgePath.reset()
            edgePath.moveTo(sx, sy)
            edgePath.cubicTo(sx + dx, sy, ex - dx, ey, ex, ey)
            canvas.drawPath(edgePath, edgePaint)
        }

        // 5. 绘制全局画布底层的自由手写笔迹（anchorCardId == null）
        board.strokes.filter { it.anchorCardId == null }.forEach { stroke ->
            drawInkStroke(canvas, stroke, cardMap)
        }

        // 6. 绘制每张活体卡片及其绑定的手写批注
        val cardFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(24, 31, 30)
            style = Paint.Style.FILL
        }
        val cardBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(56, 163, 117)
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }
        val cardTitlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(236, 240, 238)
            textSize = 18f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val cardQuotePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(165, 180, 175)
            textSize = 14f
        }
        val cardBodyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(208, 216, 212)
            textSize = 14.5f
        }

        board.cards.forEach { card ->
            val rect = RectF(card.x, card.y, card.x + card.width, card.y + card.height)
            canvas.drawRoundRect(rect, 18f, 18f, cardFillPaint)
            canvas.drawRoundRect(rect, 18f, 18f, cardBorderPaint)

            // 裁剪卡片内容区绘制文字
            canvas.save()
            canvas.clipRect(rect.left + 16f, rect.top + 14f, rect.right - 16f, rect.bottom - 14f)
            var curY = rect.top + 16f
            val availW = max(60, (card.width - 32f).roundToInt())

            val titleLayout = StaticLayout.Builder
                .obtain(card.title.ifBlank { "分支节点" }, 0, card.title.ifBlank { "分支节点" }.length, cardTitlePaint, availW)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setMaxLines(2)
                .build()
            canvas.save()
            canvas.translate(rect.left + 16f, curY)
            titleLayout.draw(canvas)
            canvas.restore()
            curY += titleLayout.height + 8f

            card.quote?.takeIf { it.isNotBlank() }?.let { q ->
                val qText = "「${q.replace("\n", " ").take(90)}」"
                val qLayout = StaticLayout.Builder
                    .obtain(qText, 0, qText.length, cardQuotePaint, availW)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setMaxLines(2)
                    .build()
                canvas.save()
                canvas.translate(rect.left + 16f, curY)
                qLayout.draw(canvas)
                canvas.restore()
                curY += qLayout.height + 8f
            }

            card.summary?.takeIf { it.isNotBlank() && curY < rect.bottom - 24f }?.let { sum ->
                val bodyLayout = StaticLayout.Builder
                    .obtain(sum, 0, sum.length, cardBodyPaint, availW)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .build()
                canvas.save()
                canvas.translate(rect.left + 16f, curY)
                bodyLayout.draw(canvas)
                canvas.restore()
            }
            canvas.restore()

            // 绘制锚定在该卡片上的手写笔迹、荧光笔与遮挡胶带
            board.strokes.filter { it.anchorCardId == card.id }.forEach { stroke ->
                drawInkStroke(canvas, stroke, cardMap)
            }
        }

        canvas.restore()
    }

    private fun drawInkStroke(
        canvas: Canvas,
        stroke: InkStroke,
        cardMap: Map<String, InkCardSnapshot>
    ) {
        val pts = resolveWorldPoints(stroke, cardMap)
        if (pts.isEmpty()) return

        val rawColor = stroke.colorArgb.toInt()
        val tool = stroke.toolType

        when (tool) {
            InkToolType.ARROW -> {
                if (pts.size < 2) return
                val start = pts.first()
                val end = pts.last()
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = rawColor
                    style = Paint.Style.STROKE
                    strokeWidth = max(2.5f, stroke.baseWidth)
                    strokeCap = Paint.Cap.ROUND
                    strokeJoin = Paint.Join.ROUND
                }
                canvas.drawLine(start.x, start.y, end.x, end.y, paint)
                val angle = atan2(end.y - start.y, end.x - start.x)
                val headLen = max(14f, stroke.baseWidth * 3.8f)
                val a1 = angle - 0.5f
                val a2 = angle + 0.5f
                val path = Path().apply {
                    moveTo(end.x, end.y)
                    lineTo(end.x - headLen * cos(a1), end.y - headLen * sin(a1))
                    moveTo(end.x, end.y)
                    lineTo(end.x - headLen * cos(a2), end.y - headLen * sin(a2))
                }
                canvas.drawPath(path, paint)
            }

            InkToolType.TAPE -> {
                val path = buildSmoothPath(pts)
                val alpha = if (stroke.tapeRevealed) 68 else 235
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.argb(
                        alpha,
                        Color.red(rawColor),
                        Color.green(rawColor),
                        Color.blue(rawColor)
                    )
                    style = Paint.Style.STROKE
                    strokeWidth = max(18f, stroke.baseWidth * 5.5f)
                    strokeCap = Paint.Cap.BUTT
                    strokeJoin = Paint.Join.ROUND
                }
                canvas.drawPath(path, paint)
            }

            InkToolType.HIGHLIGHTER -> {
                val path = buildSmoothPath(pts)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.argb(
                        96,
                        Color.red(rawColor),
                        Color.green(rawColor),
                        Color.blue(rawColor)
                    )
                    style = Paint.Style.STROKE
                    strokeWidth = max(14f, stroke.baseWidth * 4.2f)
                    strokeCap = Paint.Cap.SQUARE
                    strokeJoin = Paint.Join.ROUND
                }
                canvas.drawPath(path, paint)
            }

            else -> {
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = rawColor
                    style = Paint.Style.STROKE
                    strokeCap = Paint.Cap.ROUND
                    strokeJoin = Paint.Join.ROUND
                }
                if (pts.size == 1) {
                    val p = pts.first()
                    paint.style = Paint.Style.FILL
                    canvas.drawCircle(p.x, p.y, max(1.5f, stroke.baseWidth * 0.6f), paint)
                    return
                }
                // 分段压感绘制，呈现真实主动笔压感与速度笔锋
                for (i in 0 until pts.size - 1) {
                    val p0 = pts[i]
                    val p1 = pts[i + 1]
                    val avgPressure = ((p0.pressure + p1.pressure) * 0.5f).coerceIn(0.25f, 1.35f)
                    paint.strokeWidth = max(1.2f, stroke.baseWidth * avgPressure)
                    canvas.drawLine(p0.x, p0.y, p1.x, p1.y, paint)
                }
            }
        }
    }

    private fun buildSmoothPath(pts: List<InkPoint>): Path {
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
            path.quadTo(prev.x, prev.y, midX, midY)
        }
        path.lineTo(pts.last().x, pts.last().y)
        return path
    }
}
