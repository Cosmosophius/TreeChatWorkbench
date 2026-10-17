// 桌面替身 com.treechat.workbench.ocr.PdfOcr：整本/单页 OCR v1 用 PDFBox 文字层顶替。
@file:Suppress("unused")
package com.treechat.workbench.ocr

import android.content.Context
import java.io.File

object PdfOcr {
    fun interface ProgressListener {
        fun onProgress(current: Int, total: Int, text: String)
    }

    /** 安卓侧按屏密度给渲染倍率；桌面固定 2f。 */
    fun resolveRenderScale(context: Context): Float = 2f

    /** 整本 OCR 充面：走 PDFBox 文字层（签名带进度回调，对齐安卓）。 */
    suspend fun extractText(
        context: Context,
        bytes: ByteArray,
        onProgress: PdfOcr.ProgressListener? = null,
    ): String = com.treechat.workbench.rag.PdfTextExtractor.extractTextLayer(bytes) ?: ""

    /** 单页文本充面：PDFBox 按页抽取。 */
    suspend fun extractSinglePageText(filePath: String, pageIndex1Based: Int): String? = try {
        val file = File(filePath)
        org.apache.pdfbox.pdmodel.PDDocument.load(file).use { doc ->
            val stripper = org.apache.pdfbox.text.PDFTextStripper()
            stripper.startPage = pageIndex1Based
            stripper.endPage = pageIndex1Based
            stripper.getText(doc).takeIf { it.isNotBlank() }
        }
    } catch (_: Exception) { null }
}
