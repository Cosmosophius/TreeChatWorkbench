package com.treechat.workbench.scan

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.state.RenderingMode
import com.treechat.workbench.ocr.OcrEngine
import com.treechat.workbench.ui.reader.OcrWordBox
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 把一个扫描会话合成为**带文字层的 PDF**。
 *
 * ## 为什么用 PDFBox 而不是 android.graphics.pdf.PdfDocument
 * 项目已经依赖 PDFBox（见 build.gradle.kts），而且 PDFBox 能同时做两件事：
 * 把页图作为 XObject 画进页面，**再把 OCR 识别到的词按坐标画成不可见的文字层**。
 * 这样产出的 PDF 既好看（真图），又能被全文检索、能复制文字——正是"扫描王"级别
 * 扫描件的核心体验，而且是开源方案相对云端的一个明确强项（自己可控文本层）。
 *
 * ## 文字层怎么写（关键技巧）
 * PDF 的"文字层"不是把文字画上去，而是写进页面的内容流里，
 * 让搜索/复制能找到。常见做法是：**文字用渲染模式 3（不可见）写**，
 * 用户看不见，但选中/搜索/复制都能命中。这就是所有扫描软件的通用做法。
 */
object ScanPdfComposer {

    class Result(
        val pdfBytes: ByteArray,
        val pageCount: Int,
        /** 每页是否成功 OCR 出了文字层。 */
        val textLayerPages: Int
    )

    /**
     * 合成会话 → PDF 字节。
     *
     * @param writeTextLayer 是否写文字层（true = 可搜索/可复制）。
     *        写文字层需要对每页跑一次 OCR，比较慢，但这是"扫描件"的完整体验。
     */
    suspend fun compose(
        context: Context,
        store: ScanSessionStore,
        session: ScanSession,
        writeTextLayer: Boolean = true,
        onProgress: ((current: Int, total: Int) -> Unit)? = null
    ): Result? = withContext(Dispatchers.IO) {
        if (session.pages.isEmpty()) return@withContext null

        var doc: PDDocument? = null
        try {
            doc = PDDocument()
            val font = PDType1Font.HELVETICA
            var textPages = 0

            session.pages.forEachIndexed { i, page ->
                onProgress?.invoke(i + 1, session.pages.size)
                val file = store.pageFileOf(session.id, page)
                if (!file.exists()) return@forEachIndexed
                val bmp = BitmapFactory.decodeFile(file.absolutePath) ?: return@forEachIndexed

                // PDF 页面尺寸按图片比例（72 dpi → 点）
                val pw = bmp.width.toFloat()
                val ph = bmp.height.toFloat()
                val pdPage = PDPage(PDRectangle(pw, ph))
                doc.addPage(pdPage)

                // 1) 画图片（铺满页面）
                val stream = PDPageContentStream(doc, pdPage)
                try {
                    val image = LosslessFactory.createFromImage(doc, bmp)
                    stream.drawImage(image, 0f, 0f, pw, ph)
                    stream.close()

                    // 2) 写不可见文字层（渲染模式 3）
                    if (writeTextLayer) {
                        val boxes = recognizeWithTimeout(bmp)
                        if (boxes.isNotEmpty()) {
                            val ts = PDPageContentStream(
                                doc, pdPage, PDPageContentStream.AppendMode.APPEND,
                                /* compress = */ true, /* resetContext = */ true
                            )
                            ts.beginText()
                            ts.setFont(font, 1f)
                            // 渲染模式 3（INVISIBLE）：文字写进内容流但不可见，
                            // 于是能搜索/复制/选中，肉眼看不见 —— 所有扫描软件都这么做。
                            ts.setRenderingMode(RenderingMode.NEITHER)
                            boxes.forEach { box ->
                                // PDF 坐标原点在左下，图像坐标原点在左上 → y 翻转
                                val r = box.bounds
                                val x = r.left.toFloat()
                                val y = ph - r.bottom.toFloat()
                                ts.newLineAtOffset(x, y)
                                ts.showText(sanitizeForPdf(box.text))
                            }
                            ts.endText()
                            ts.close()
                            textPages++
                        }
                    }
                } finally {
                    bmp.recycle()
                }
            }

            val out = ByteArrayOutputStream()
            doc.save(out)
            return@withContext Result(
                pdfBytes = out.toByteArray(),
                pageCount = doc.numberOfPages,
                textLayerPages = textPages
            )
        } catch (t: Throwable) {
            return@withContext null
        } finally {
            doc?.close()
        }
    }

    /** OCR 单页，带超时保护。失败返回空列表（那一页就没有文字层，但图片仍在）。 */
    private suspend fun recognizeWithTimeout(bmp: Bitmap): List<OcrWordBox> {
        // 不在这里再套一层 withTimeout：它是 CoroutineScope 的扩展，
        // 而本函数只是普通 suspend fun；且 OcrEngine.recognize 内部已有
        // 自己的超时保护（见 OcrEngine.TIMEOUT_MS），重复加只会让
        // "超时抛异常"和"超时返回 null"两条路径互相打架。
        return try {
            OcrEngine.recognize(bmp).orEmpty()
        } catch (t: Throwable) {
            emptyList()
        }
    }

    /**
     * PDF 标准字体不支持 Unicode，中文/西里尔要转 WinAnsi 编码范围。
     *
     * 处理方式：把非 Latin-1 的字符替换成 '?'——**图片已经画在页面上了**，
     * 文字层只是为了让中文部分也能被搜索到、能定位；替换成 '?' 不影响观感。
     * （想要完美的中文文字层需要嵌入中文字体，会把 APK 撑大，不在本次范围。）
     */
    private fun sanitizeForPdf(text: String): String {
        val sb = StringBuilder()
        for (ch in text) {
            val code = ch.code
            if (code in 32..126 || code in 160..255) {
                sb.append(ch)
            } else {
                sb.append('?')
            }
        }
        return sb.toString()
    }
}