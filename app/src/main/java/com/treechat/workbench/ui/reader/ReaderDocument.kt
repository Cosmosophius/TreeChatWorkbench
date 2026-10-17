package com.treechat.workbench.ui.reader

import androidx.compose.ui.graphics.Color

/**
 * 电子书与文档格式类型。
 */
enum class ReaderFormat(val extension: String, val label: String) {
    PDF("pdf", "PDF"),
    EPUB("epub", "EPUB"),
    TXT("txt", "TXT"),
    MARKDOWN("md", "MD"),
    DOCX("docx", "Word"),
    DOC("doc", "Word"),
    PPTX("pptx", "PPT"),
    PPT("ppt", "PPT"),
    MOBI("mobi", "MOBI"),
    AZW3("azw3", "AZW3"),
    SCANNED_PDF("pdf", "扫描件")
}

/**
 * 统一电子书/文档数据模型。
 * 打通书架、条畅 RAG 知识库与本地 OCR 状态。
 */
data class ReaderDocument(
    val id: String,
    val title: String,
    val filePath: String,
    val format: ReaderFormat,
    val fileSize: Long = 0L,
    val pageCount: Int = 1,
    val currentPage: Int = 1,
    val isScanned: Boolean = false,
    val isOcrProcessed: Boolean = false,
    val ocrProgress: Float = 0f,
    val isOcrRunning: Boolean = false,
    val isRagIndexed: Boolean = false,
    val ragChunkCount: Int = 0,
    val lastReadTimestamp: Long = System.currentTimeMillis(),
    val sampleText: String = ""
)

/**
 * 文档划线与标记数据结构。
 * 直接对齐条畅笔记系统 (InkNoteStore)。
 */
data class DocumentHighlight(
    val id: String,
    val documentId: String,
    val pageIndex: Int,
    val startOffset: Int,
    val endOffset: Int,
    val selectedText: String,
    val noteComment: String = "",
    val colorArgb: Long = 0x4DFFA726, // 默认柔和琥珀高亮
    val timestamp: Long = System.currentTimeMillis()
)
