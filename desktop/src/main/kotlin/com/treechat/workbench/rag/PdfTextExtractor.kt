// 桌面替身 com.treechat.workbench.rag.PdfTextExtractor：PDFBox 文本层直抽。
@file:Suppress("unused")
package com.treechat.workbench.rag

import java.io.ByteArrayInputStream
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.text.PDFTextStripper

object PdfTextExtractor {
    /** 安卓版签名：extractTextLayer(pdf: ByteArray?): String?。返回规范化正文，无文本层时 null。 */
    fun extractTextLayer(pdf: ByteArray?): String? {
        if (pdf == null) return null
        val raw = try {
            PDDocument.load(ByteArrayInputStream(pdf)).use { doc ->
                val stripper = PDFTextStripper()
                stripper.startPage = 1
                stripper.endPage = doc.numberOfPages
                stripper.getText(doc)
            }
        } catch (_: Exception) { return null }
        val normalized = raw.replace("\r\n", "\n").replace("\r", "\n").trim()
        if (normalized.length < 200) return null   // 布线对齐安卓版：短文本视为无文本层
        return normalized
    }
}
