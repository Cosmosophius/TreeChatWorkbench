// 桌面替身：export.InkNoteExporter —— 手写批注板导出 PNG / PDF（java2D + pdfbox）。
// API 面与安卓一致：outputFile / exportPdf / exportPng / computeContentBounds。
package com.treechat.workbench.export

import android.content.Context
import com.treechat.workbench.data.prefs.InkNoteBoard
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.geom.Line2D
import java.awt.geom.Rectangle2D
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.imageio.ImageIO

object InkNoteExporter {
    private const val PADDING = 40f
    private const val HEADER_HEIGHT = 56f

    fun outputFile(context: Context, board: InkNoteBoard, isPdf: Boolean): File {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val safeTitle = board.title
            .replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_")
            .take(36)
            .ifBlank { "ink_note" }
        val ext = if (isPdf) "pdf" else "png"
        return File(File(context.filesDir, "ink_exports"), "${safeTitle}_$stamp.$ext")
    }

    fun computeContentBounds(board: InkNoteBoard): Rectangle2D {
        val union = Rectangle2D.Double()
        board.cards.forEach { card ->
            union.add(Rectangle2D.Double(card.x.toDouble(), card.y.toDouble(), card.width.toDouble(), card.height.toDouble()))
        }
        board.strokes.forEach { stroke ->
            stroke.points.forEach { p -> union.add(Rectangle2D.Double(p.x - 2.0, p.y - 2.0, 4.0, 4.0)) }
        }
        if (union.isEmpty) {
            return Rectangle2D.Double(0.0, 0.0, 480.0, 220.0)
        }
        return union
    }

    /** 导出 PDF（pdfbox：页里嵌渲染好的位图）。 */
    fun exportPdf(board: InkNoteBoard, outFile: File): File {
        val pngBytes = renderPngBytes(board)
        org.apache.pdfbox.pdmodel.PDDocument().use { doc ->
            val img = ImageIO.read(ByteArrayInputStream(pngBytes))
            val page = org.apache.pdfbox.pdmodel.PDPage(
                org.apache.pdfbox.pdmodel.common.PDRectangle(img.width.toFloat(), img.height.toFloat())
            )
            doc.addPage(page)
            val pdImage = org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory.createFromImage(doc, img)
            val content = org.apache.pdfbox.pdmodel.PDPageContentStream(doc, page)
            content.drawImage(pdImage, 0f, 0f)
            content.close()
            doc.save(outFile)
        }
        return outFile
    }

    fun exportPng(board: InkNoteBoard, outFile: File): File {
        outFile.parentFile?.let { if (!it.exists()) it.mkdirs() }
        outFile.writeBytes(renderPngBytes(board))
        return outFile
    }

    private fun renderPngBytes(board: InkNoteBoard): ByteArray {
        val bounds = computeContentBounds(board)
        val rawW = (bounds.width + PADDING * 2).toFloat()
        val rawH = (bounds.height + PADDING * 2 + HEADER_HEIGHT).toFloat()
        val maxSide = maxOf(rawW, rawH)
        val scale = if (maxSide > 3840f) 3840f / maxSide else 1.25f
        val w = (rawW * scale).toInt().coerceAtLeast(1)
        val h = (rawH * scale).toInt().coerceAtLeast(1)

        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

        // 底色
        g.color = Color(0xFCFCF9)
        g.fillRect(0, 0, w, h)

        // 页眉
        g.color = Color(0x1C1B1A)
        g.font = Font("PingFang SC", Font.BOLD, (14 * scale).toInt().coerceAtLeast(10))
        g.drawString(
            board.title.ifBlank { "思维导图手写批注" },
            (PADDING * scale).toInt(),
            (HEADER_HEIGHT * 0.7f * scale).toInt().coerceAtLeast(12),
        )

        // 坐标变换：内容区 → 画布
        val originX = ((PADDING - bounds.minX) * scale).toDouble()
        val originY = ((PADDING + HEADER_HEIGHT - bounds.minY) * scale).toDouble()
        val oldTransform = g.transform
        g.translate(originX, originY)
        g.scale(scale.toDouble(), scale.toDouble())

        // 连线（先画线，避免压卡片）
        val byId = board.cards.associateBy { it.id }
        g.color = Color(0x9AA09A)
        g.stroke = BasicStroke(1.4f)
        board.cards.forEach { card ->
            val parent = card.parentId?.let { byId[it] }
            if (parent != null) {
                g.draw(
                    Line2D.Float(
                        parent.x + parent.width, parent.y + parent.height / 2f,
                        card.x, card.y + card.height / 2f,
                    )
                )
            }
        }

        // 卡片
        board.cards.forEach { card ->
            val ix = card.x.toInt(); val iy = card.y.toInt()
            val iw = card.width.toInt(); val ih = card.height.toInt()
            g.color = Color.WHITE
            g.fillRoundRect(ix, iy, iw, ih, 12, 12)
            g.color = Color(0xDDDDD8)
            g.stroke = BasicStroke(1f)
            g.drawRoundRect(ix, iy, iw, ih, 12, 12)
            g.color = Color(0x1C1B1A)
            g.font = Font("PingFang SC", Font.PLAIN, 12)
            g.drawString(card.title, ix + 10, iy + 8 + g.fontMetrics.ascent)
            if (!card.summary.isNullOrBlank()) {
                g.color = Color(0x606468)
                g.font = Font("PingFang SC", Font.PLAIN, 11)
                g.drawString(card.summary!!.take(60), ix + 10, iy + 12 + g.fontMetrics.height * 2)
            }
        }

        // 笔迹
        board.strokes.forEach { stroke ->
            if (stroke.points.isEmpty()) return@forEach
            g.color = Color(stroke.colorArgb.toInt())
            g.stroke = BasicStroke(stroke.baseWidth, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            val path = java.awt.geom.Path2D.Float()
            path.moveTo(stroke.points.first().x, stroke.points.first().y)
            stroke.points.drop(1).forEach { p -> path.lineTo(p.x, p.y) }
            g.draw(path)
        }

        g.transform = oldTransform
        g.dispose()

        val bos = ByteArrayOutputStream()
        ImageIO.write(img, "png", bos)
        return bos.toByteArray()
    }
}
