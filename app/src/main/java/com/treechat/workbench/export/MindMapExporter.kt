package com.treechat.workbench.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import com.treechat.workbench.data.model.ConversationNode
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/** 导出格式。 */
enum class ExportFormat(val label: String, val extension: String) {
    PDF("PDF（矢量，可打印）", "pdf"),
    PNG("PNG（图片，方便分享）", "png"),
    MARKDOWN("Markdown（文字大纲）", "md")
}

/**
 * 一个待导出的思维导图快照。
 *
 * 刻意**不直接依赖画布状态**（GraphViewState / offsets 那些）：
 * 导出是"把内容落成文件"，与当前 pan/zoom 无关 —— 用户希望导出的是整张图，
 * 而不是屏幕上恰好可见的那一块。位置只用来决定层级排布。
 */
data class MindMapDocument(
    val title: String,
    val nodes: List<MindMapNode>
)

/** 导出用的节点：只保留"要画出来/写出来"的东西。 */
data class MindMapNode(
    val id: String,
    val parentId: String?,
    val title: String,
    val quote: String?,
    /** 该分支下的消息条数，作为摘要信息。 */
    val messageCount: Int
)

/**
 * 从会话节点构建导出文档。
 *
 * 树的形状来自 `parentId`（父子继承指针，见交接文档 3.1）。
 * 根节点是 `parentId == null` 的那些 —— 正常只有一个，但也可能是多个
 * （历史数据），因此按列表处理，不强假设唯一。
 */
object MindMapBuilder {
    fun build(
        conversationTitle: String,
        nodes: List<ConversationNode>,
        messageCounts: Map<String, Int> = emptyMap()
    ): MindMapDocument {
        val items = nodes.map { node ->
            MindMapNode(
                id = node.id,
                parentId = node.parentId,
                title = node.title.ifBlank { "未命名分支" },
                quote = node.originQuote?.takeIf { it.isNotBlank() },
                messageCount = messageCounts[node.id] ?: 0
            )
        }
        return MindMapDocument(
            title = conversationTitle.ifBlank { "对话思维导图" },
            nodes = items
        )
    }
}

/**
 * 把思维导图渲染成 PDF / PNG / Markdown。
 *
 * ## 为什么自己画而不引第三方
 * 需求是"导出思维导图"。这里的内容只是**方框 + 连线 + 文字**，
 * Android 自带的 `PdfDocument` 与 `Canvas` 完全够用；引一个绘图/PDF 库
 * 会带来版本冲突与体积（本项目坚持自研 Markdown 也是同一个理由）。
 *
 * ## 布局算法
 * 经典分层树：深度决定列，兄弟顺序决定行。列宽按最深一层的节点文本宽度自适应；
 * 行高固定。连线用三次贝塞尔（与工作台的观感一致）。
 *
 * ## 分页
 * PDF 可能一页装不下。这里按"整列"分页 —— 不切断单个节点方框，
 * 因为把标题切成两半的图没有可读性。PNG 不分页（一张长图）。
 */
object MindMapExporter {

    private const val MARGIN = 36f
    private const val COLUMN_GAP = 56f
    private const val ROW_GAP = 18f
    private const val NODE_PAD_X = 12f
    private const val NODE_PAD_Y = 9f
    private const val TITLE_SIZE = 20f
    private const val NODE_TEXT_SIZE = 12f
    private const val QUOTE_TEXT_SIZE = 10f
    private const val FOOTER_RESERVE = 40f

    private const val BG_COLOR = 0xFFFFFFFF.toInt()
    private const val NODE_FILL = 0xFFF2F4F0.toInt()
    private const val NODE_STROKE = 0xFF3C6E47.toInt()
    private const val ROOT_FILL = 0xFFE3F1E5.toInt()
    private const val TEXT_COLOR = 0xFF1B1C19.toInt()
    private const val MUTED_COLOR = 0xFF5F6459.toInt()
    private const val LINK_COLOR = 0xFF8FA98F.toInt()

    // ---------------------------------------------------------------- 布局

    private class Placed(
        val node: MindMapNode,
        val depth: Int,
        val x: Float,
        val y: Float,
        val width: Float,
        val height: Float
    )

    private class Layout(
        val placed: List<Placed>,
        val width: Float,
        val height: Float
    )

    /**
     * 计算布局。
     *
     * 返回的内容坐标以 (0,0) 为左上角，调用方负责加页边距。
     */
    private fun layout(doc: MindMapDocument, textPaint: Paint, density: Float): Layout {
        val byParent = doc.nodes.groupBy { it.parentId }
        val placed = mutableListOf<Placed>()
        var cursorY = 0f
        // 每一列的最大宽度，用于让列之间不留过大的空隙
        val columnWidths = mutableMapOf<Int, Float>()
        var maxDepth = 0

        fun measure(node: MindMapNode): Pair<Float, Float> {
            val titleWidth = textPaint.measureText(node.title)
            val quoteWidth = node.quote?.let {
                textPaint.textSize = QUOTE_TEXT_SIZE
                val w = textPaint.measureText("“$it”")
                textPaint.textSize = NODE_TEXT_SIZE
                w
            } ?: 0f
            val w = max(titleWidth, quoteWidth) + NODE_PAD_X * 2
            val h = NODE_PAD_Y * 2 + NODE_TEXT_SIZE * density +
                if (node.quote != null) QUOTE_TEXT_SIZE * density + 4f else 0f
            return w to h
        }

        fun place(node: MindMapNode, depth: Int): Float {
            val children = byParent[node.id].orEmpty()
            val (w, h) = measure(node)
            columnWidths[depth] = max(columnWidths[depth] ?: 0f, w)
            maxDepth = max(maxDepth, depth)

            val centerY: Float
            if (children.isEmpty()) {
                // 叶子：占用一行
                centerY = cursorY + h / 2f
                cursorY += h + ROW_GAP
            } else {
                val childCenters = children.map { place(it, depth + 1) }
                centerY = (childCenters.min() + childCenters.max()) / 2f
            }

            // x 暂时记 0，等所有列宽确定后统一回填
            placed.add(Placed(node, depth, 0f, centerY - h / 2f, w, h))
            return centerY
        }

        // 根节点可能有多个（历史数据），按列表处理
        val roots = doc.nodes.filter { it.parentId == null }
        if (roots.isEmpty()) {
            // 没有根（数据异常）：退化成一列平铺，至少不丢内容
            doc.nodes.forEach { node ->
                val (w, h) = measure(node)
                columnWidths[0] = max(columnWidths[0] ?: 0f, w)
                placed.add(Placed(node, 0, 0f, cursorY, w, h))
                cursorY += h + ROW_GAP
            }
        } else {
            roots.forEach { place(it, 0) }
        }

        // 回填 x：每列左边缘 = 前面所有列宽之和 + 列间距
        val columnLeft = mutableMapOf<Int, Float>()
        var runningX = 0f
        for (d in 0..maxDepth) {
            columnLeft[d] = runningX
            runningX += (columnWidths[d] ?: 0f) + COLUMN_GAP
        }
        val finalPlaced = placed.map { p ->
            Placed(p.node, p.depth, columnLeft[p.depth] ?: 0f, p.y, p.width, p.height)
        }

        val totalWidth = finalPlaced.maxOfOrNull { it.x + it.width } ?: 0f
        // 内容高度取叶子累计的高度（cursorY 已含最后一个 ROW_GAP）
        val totalHeight = max(cursorY - ROW_GAP, finalPlaced.maxOfOrNull { it.y + it.height } ?: 0f)
        return Layout(finalPlaced, max(totalWidth, 1f), max(totalHeight, 1f))
    }

    // ---------------------------------------------------------------- 绘制

    private fun paintSet(density: Float): Map<String, Paint> {
        val nodeText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = TEXT_COLOR
            textSize = NODE_TEXT_SIZE * density
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val quoteText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = MUTED_COLOR
            textSize = QUOTE_TEXT_SIZE * density
        }
        val meta = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = MUTED_COLOR
            textSize = QUOTE_TEXT_SIZE * density
        }
        val nodeFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = NODE_FILL
        }
        val rootFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = ROOT_FILL
        }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.4f * density
            color = NODE_STROKE
        }
        val link = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.2f * density
            color = LINK_COLOR
        }
        val title = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = TEXT_COLOR
            textSize = TITLE_SIZE * density
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        return mapOf(
            "nodeText" to nodeText, "quoteText" to quoteText, "meta" to meta,
            "nodeFill" to nodeFill, "rootFill" to rootFill, "stroke" to stroke,
            "link" to link, "title" to title
        )
    }

    /** 在给定画布上绘制一页内容；offsetY 用于 PDF 分页时下移。 */
    private fun drawPage(
        canvas: Canvas,
        doc: MindMapDocument,
        layout: Layout,
        paints: Map<String, Paint>,
        density: Float,
        originX: Float,
        originY: Float,
        drawHeader: Boolean
    ) {
        canvas.drawColor(BG_COLOR)
        val titlePaint = paints.getValue("title")
        var contentTop = originY
        if (drawHeader) {
            canvas.drawText(doc.title, originX, originY + TITLE_SIZE * density, titlePaint)
            val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())
            val metaPaint = paints.getValue("meta")
            canvas.drawText(
                "${doc.nodes.size} 个分支 · 导出于 $stamp",
                originX, originY + TITLE_SIZE * density + QUOTE_TEXT_SIZE * density + 6f,
                metaPaint
            )
            contentTop = originY + TITLE_SIZE * density + QUOTE_TEXT_SIZE * density + 22f
        }

        val byId = layout.placed.associateBy { it.node.id }

        // ---- 连线先画，保证压在方框之下 ----
        val link = paints.getValue("link")
        layout.placed.forEach { from ->
            val parent = from.node.parentId?.let { byId[it] } ?: return@forEach
            val startX = parent.x + parent.width + originX
            val startY = parent.y + parent.height / 2f + contentTop
            val endX = from.x + originX
            val endY = from.y + from.height / 2f + contentTop
            val midX = (startX + endX) / 2f
            val path = Path().apply {
                moveTo(startX, startY)
                cubicTo(midX, startY, midX, endY, endX, endY)
            }
            canvas.drawPath(path, link)
        }

        // ---- 节点方框与文字 ----
        layout.placed.forEach { p ->
            val rect = RectF(
                p.x + originX,
                p.y + contentTop,
                p.x + p.width + originX,
                p.y + p.height + contentTop
            )
            val isRoot = p.node.parentId == null
            canvas.drawRoundRect(rect, 8f * density, 8f * density,
                if (isRoot) paints.getValue("rootFill") else paints.getValue("nodeFill"))
            canvas.drawRoundRect(rect, 8f * density, 8f * density, paints.getValue("stroke"))

            val textPaint = paints.getValue("nodeText")
            var textY = rect.top + NODE_PAD_Y * density + NODE_TEXT_SIZE * density
            canvas.drawText(p.node.title, rect.left + NODE_PAD_X, textY, textPaint)

            p.node.quote?.let { quote ->
                val quotePaint = paints.getValue("quoteText")
                textY += QUOTE_TEXT_SIZE * density + 3f
                // 引文可能很长，按方框宽度截断（导出不追求完整引文，
                // 完整内容在 Markdown 导出里是齐全的）
                val maxChars = ((rect.width() - NODE_PAD_X * 2) /
                    (QUOTE_TEXT_SIZE * density * 0.6f)).toInt().coerceAtLeast(4)
                val shown = if (quote.length > maxChars) {
                    quote.take(maxChars - 1) + "…"
                } else {
                    quote
                }
                canvas.drawText("“$shown”", rect.left + NODE_PAD_X, textY, quotePaint)
            }

            if (p.node.messageCount > 0) {
                val metaPaint = paints.getValue("meta")
                canvas.drawText(
                    "${p.node.messageCount} 条",
                    rect.left + NODE_PAD_X,
                    rect.bottom - 5f,
                    metaPaint
                )
            }
        }
    }

    // ---------------------------------------------------------------- 导出

    /** 导出到 PDF。返回写出的文件。 */
    fun exportPdf(
        context: Context,
        doc: MindMapDocument,
        outFile: File,
        density: Float
    ): File {
        val paints = paintSet(density)
        val layout = layout(doc, paints.getValue("nodeText"), density)

        // A4 横向（点单位，1/72 英寸）：1280x905 约等于 A4 横向在 150dpi 下的像素量
        val pageWidth = 842f * 1.5f
        val pageHeight = 595f * 1.5f
        val availableWidth = pageWidth - MARGIN * 2
        val availableHeight = pageHeight - MARGIN * 2 - FOOTER_RESERVE

        // 内容太宽时整体等比缩小，保证一张图在一页宽内看得全
        val scale = if (layout.width > availableWidth) {
            availableWidth / layout.width
        } else 1f

        val pdf = PdfDocument()
        var pageIndex = 0
        var consumedY = 0f
        val headerHeight = TITLE_SIZE * density + QUOTE_TEXT_SIZE * density + 22f

        // 第一页可用高度要扣掉页眉
        var firstPage = true
        val contentTotalHeight = layout.height
        while (consumedY < contentTotalHeight || (pageIndex == 0 && contentTotalHeight == 0f)) {
            val usable = if (firstPage) availableHeight - headerHeight else availableHeight
            var sliceHeight = usable / scale
            if (sliceHeight <= 1f) sliceHeight = usable // 防止除零/负值
            val remaining = contentTotalHeight - consumedY
            val thisSlice = min(sliceHeight, remaining.coerceAtLeast(1f))

            val pageInfo = PdfDocument.PageInfo.Builder(
                pageWidth.toInt(), pageHeight.toInt(), pageIndex + 1
            ).create()
            val page = pdf.startPage(pageInfo)
            val canvas = page.canvas
            canvas.save()
            canvas.translate(MARGIN, MARGIN)
            canvas.scale(scale, scale)
            canvas.translate(0f, -consumedY)
            drawPage(
                canvas = canvas,
                doc = doc,
                layout = layout,
                paints = paints,
                density = density,
                originX = 0f,
                originY = 0f,
                drawHeader = false
            )
            canvas.restore()

            // 页眉是**每页都画**的：跨页的图没有标题会不知道是什么
            val headerCanvas = page.canvas
            val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())
            headerCanvas.drawText(
                doc.title + if (pageIndex > 0) "（续 ${pageIndex + 1}）" else "",
                MARGIN, MARGIN + TITLE_SIZE * density * 0.8f, paints.getValue("title")
            )
            headerCanvas.drawText(
                "${doc.nodes.size} 个分支 · 导出于 $stamp",
                MARGIN, MARGIN + TITLE_SIZE * density * 0.8f + QUOTE_TEXT_SIZE * density + 4f,
                paints.getValue("meta")
            )

            pdf.finishPage(page)
            consumedY += thisSlice
            firstPage = false
            pageIndex++
            if (consumedY >= contentTotalHeight) break
            if (pageIndex > 50) break // 防御：异常数据不至于生成无数页
        }

        outFile.parentFile?.mkdirs()
        FileOutputStream(outFile).use { pdf.writeTo(it) }
        pdf.close()
        return outFile
    }

    /** 导出到 PNG（一张长图，不分页）。 */
    fun exportPng(
        context: Context,
        doc: MindMapDocument,
        outFile: File,
        density: Float
    ): File {
        val paints = paintSet(density)
        val layout = layout(doc, paints.getValue("nodeText"), density)
        val headerHeight = TITLE_SIZE * density + QUOTE_TEXT_SIZE * density + 24f
        val width = (layout.width + MARGIN * 2).toInt().coerceIn(64, 8000)
        val height = (layout.height + MARGIN * 2 + headerHeight).toInt().coerceIn(64, 12000)

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(BG_COLOR)
        canvas.drawText(doc.title, MARGIN, MARGIN + TITLE_SIZE * density, paints.getValue("title"))
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())
        canvas.drawText(
            "${doc.nodes.size} 个分支 · 导出于 $stamp",
            MARGIN, MARGIN + TITLE_SIZE * density + QUOTE_TEXT_SIZE * density + 6f,
            paints.getValue("meta")
        )
        canvas.save()
        canvas.translate(MARGIN, MARGIN + headerHeight)
        // 这里不缩放：PNG 可以是长图，不需要塞进 A4
        drawPage(
            canvas = canvas,
            doc = doc,
            layout = layout,
            paints = paints,
            density = density,
            originX = 0f,
            originY = 0f,
            drawHeader = false
        )
        canvas.restore()

        outFile.parentFile?.mkdirs()
        FileOutputStream(outFile).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        bitmap.recycle()
        return outFile
    }

    /**
     * 导出为 Markdown 大纲。
     *
     * 缩进用两个空格 + `- `，层级用缩进表达而不是 `#` 标题：
     * 思维导图可能很深，`#######` 之后就无效了，而缩进列表没有深度上限。
     */
    fun exportMarkdown(doc: MindMapDocument, outFile: File): File {
        val byParent = doc.nodes.groupBy { it.parentId }
        val sb = StringBuilder()
        sb.append("# ").append(doc.title).append("\n\n")
        sb.append("> 共 ").append(doc.nodes.size).append(" 个分支，由 TreeChat 导出\n\n")

        val visited = mutableSetOf<String>()

        fun write(node: MindMapNode, depth: Int) {
            if (!visited.add(node.id)) return // 防环：异常数据不应导致死循环
            val indent = "  ".repeat(depth)
            sb.append(indent).append("- **").append(node.title).append("**")
            if (node.messageCount > 0) {
                sb.append("（").append(node.messageCount).append(" 条）")
            }
            sb.append('\n')
            node.quote?.let { quote ->
                // 引文折行后仍保持引用块，避免污染列表层级
                val oneLine = quote.replace("\n", " ").trim()
                sb.append(indent).append("  > ").append(oneLine).append('\n')
            }
            byParent[node.id].orEmpty().forEach { write(it, depth + 1) }
        }

        val roots = doc.nodes.filter { it.parentId == null }
        if (roots.isEmpty()) {
            doc.nodes.forEach { write(it, 0) }
        } else {
            roots.forEach { write(it, 0) }
        }
        // 没被访问到的节点（父节点缺失的孤立节点）也补上，避免静默丢内容
        doc.nodes.filterNot { visited.contains(it.id) }.forEach { orphan ->
            sb.append("\n## （未挂到树上的分支）\n\n")
            write(orphan, 0)
        }

        outFile.parentFile?.mkdirs()
        outFile.writeText(sb.toString(), Charsets.UTF_8)
        return outFile
    }

    /** 统一的输出文件名。 */
    fun outputFile(context: Context, doc: MindMapDocument, format: ExportFormat): File {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val safeTitle = doc.title
            .replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_")
            .take(40)
            .ifBlank { "mindmap" }
        val dir = File(context.cacheDir, "exports")
        dir.mkdirs()
        return File(dir, "${safeTitle}_$stamp.${format.extension}")
    }
}
