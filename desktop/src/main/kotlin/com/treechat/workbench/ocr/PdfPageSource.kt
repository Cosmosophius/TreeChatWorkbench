// 桌面替身：ocr.PdfPageSource（PDFBox 2.0.27；词框走 writeString 钩子。
// PDFBox 2.0.27 无 PDFTextStripperByWord——已实测 jar 内不存在）。
@file:Suppress("unused", "MemberVisibilityCanBePrivate")
package com.treechat.workbench.ocr

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Rect
import com.treechat.workbench.ui.reader.OcrWordBox
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.rendering.ImageType
import org.apache.pdfbox.rendering.PDFRenderer
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.pdfbox.text.TextPosition

/** 渲染登记表：渲染时按 (file,page) 存词框；OcrEngine 取「最近渲染页」。 */
object OcrWordBoxRegistry {
    private val cache = HashMap<Pair<String, Int>, List<OcrWordBox>>()
    @Volatile private var current: Pair<String, Int>? = null

    @Synchronized
    fun record(filePath: String, pageIndex0: Int, boxes: List<OcrWordBox>) {
        cache[filePath to pageIndex0] = boxes
        current = filePath to pageIndex0
    }

    fun takeCurrent(): List<OcrWordBox> {
        val key = current ?: return emptyList()
        return synchronized(this) { cache[key].orEmpty() }
    }

    @Synchronized
    fun clearFile(filePath: String) {
        val stale = cache.keys.filter { it.first == filePath }
        stale.forEach { cache.remove(it) }
        if (current?.first == filePath) current = null
    }
}

/**
 * 桌面版 PdfPageSource。对共享代码保持安卓版 API 面：
 * open(filePath) / renderPage(pageIndex0, scale) / pageCount / close / filePath。
 */
class PdfPageSource private constructor(val filePath: String, private val document: PDDocument) {
    private val renderer = PDFRenderer(document)

    fun pageCount(): Int = document.numberOfPages

    /** pageIndex 0 基；返回充面 Bitmap，并登记该页词框（像素坐标 = 页坐标 × scale）。 */
    fun renderPage(pageIndex: Int, scale: Float): Bitmap? = try {
        val img = renderer.renderImage(
            pageIndex.coerceIn(0, document.numberOfPages - 1), scale, ImageType.RGB
        )
        OcrWordBoxRegistry.record(filePath, pageIndex, wordBoxesOf(document, pageIndex, scale))
        toFacadeBitmap(img)
    } catch (_: Exception) { null }

    fun close() {
        runCatching { document.close() }
        OcrWordBoxRegistry.clearFile(filePath)
    }

    companion object {
        fun open(filePath: String?): PdfPageSource? {
            if (filePath.isNullOrBlank()) return null
            return try {
                val f = File(filePath)
                if (!f.exists()) null else PdfPageSource(filePath, PDDocument.load(f))
            } catch (_: Exception) { null }
        }

        fun open(bytes: ByteArray): PdfPageSource? = try {
            PdfPageSource("memory-pdf", PDDocument.load(bytes))
        } catch (_: Exception) { null }

        internal fun toFacadeBitmap(img: BufferedImage): Bitmap {
            val bos = ByteArrayOutputStream()
            ImageIO.write(img, "png", bos)
            return Bitmap.decodePng(bos.toByteArray()) ?: Bitmap.createBitmap(img.width, img.height)
        }

        /** 重放文本坐标：PDFTextStripper.writeString(String, List<TextPosition>) 钩子。 */
        internal fun wordBoxesOf(document: PDDocument, pageIndex0: Int, scale: Float): List<OcrWordBox> =
            try {
                val stripper = BoxStripper(scale)
                stripper.startPage = pageIndex0 + 1
                stripper.endPage = pageIndex0 + 1
                stripper.sortByPosition = true
                stripper.getText(document)
                stripper.result
            } catch (_: Exception) { emptyList() }
    }
}

/**
 * writeString 每次收到一个「词」（PDFBox 把空格前后切成独立调用），
 * 用首末 TextPosition 拼出词框：x 从左，y 自顶（direction-adjusted 值）。
 */
private class BoxStripper(private val scale: Float) : PDFTextStripper() {
    val result = ArrayList<OcrWordBox>()
    var id = 0

    override fun writeString(text: String, textPositions: List<TextPosition>) {
        if (text.isBlank() || textPositions.isEmpty()) return
        val first = textPositions.first()
        val last = textPositions.last()
        val x1 = first.xDirAdj * scale
        val x2 = (last.xDirAdj + last.widthDirAdj) * scale
        val baseline = first.yDirAdj * scale
        val height = first.heightDir.coerceAtLeast(first.fontSize) * scale
        val y1 = baseline - height
        val y2 = baseline + height * 0.25f   // 余量吃掉下伸部，避免贴基线切字
        if (x2 <= x1 || y2 <= y1) return
        result += OcrWordBox(
            id = "pdf_${id++}",
            bounds = Rect(x1, y1, x2, y2),
            text = text,
            confidence = 1.0f,
        )
    }
}
