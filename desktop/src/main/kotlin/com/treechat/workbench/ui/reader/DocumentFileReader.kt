// 桌面替身：ui.reader.DocumentFileReader。共享字符（CHARS_PER_PAGE/PageCapacitySpec/
// adaptiveCharsPerPage/getPageContent）绕开 Android 在本文件全部按 File 语义收口。
@file:Suppress("unused", "MemberVisibilityCanBePrivate")
package com.treechat.workbench.ui.reader

import android.content.Context
import android.util.Log
import com.treechat.workbench.rag.DocumentParser
import com.treechat.workbench.rag.PdfTextExtractor
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object DocumentFileReader {

    private const val TAG = "DocumentFileReader"

    const val CHARS_PER_PAGE = 800

    private const val MIN_CHARS_PER_PAGE = 120
    private const val MAX_CHARS_PER_PAGE = 6000

    data class PageCapacitySpec(
        val viewportWidthPx: Float,
        val viewportHeightPx: Float,
        val fontSizePx: Float,
        val lineHeightPx: Float,
        val letterSpacingPx: Float,
        val paragraphSpacingPx: Float,
    )

    /// 最近一次导入失败的可读原因
    @Volatile
    var lastError: String? = null
        private set

    private class TextStats(val avgGlyphEm: Float, val avgParagraphChars: Float)

    private val statsLock = Any()
    private var statsSource: String? = null
    private var statsCache: TextStats? = null

    private fun glyphEm(ch: Char): Float = when {
        ch.code >= 0x2E80 -> 1f
        ch == ' ' || ch == '\t' -> 0.3f
        ch.isLetterOrDigit() -> 0.55f
        else -> 0.4f
    }

    private fun textStats(text: String): TextStats = synchronized(statsLock) {
        val cached = statsCache
        if (cached != null && statsSource === text) {
            cached
        } else {
            computeTextStats(text).also {
                statsSource = text
                statsCache = it
            }
        }
    }

    private fun computeTextStats(text: String): TextStats {
        var glyphSum = 0f
        var glyphCount = 0f
        var paragraphCount = 0
        var paragraphChars = 0L
        for (line in text.split('\n')) {
            val trimmed = line.trimEnd('\r')
            if (trimmed.isBlank()) continue
            paragraphCount++
            paragraphChars += trimmed.length
            for (ch in trimmed) {
                glyphSum += glyphEm(ch)
                glyphCount++
            }
        }
        return TextStats(
            avgGlyphEm = if (glyphCount > 0f) glyphSum / glyphCount else 1f,
            avgParagraphChars = if (paragraphCount > 0) {
                paragraphChars.toFloat() / paragraphCount
            } else {
                40f
            },
        )
    }

    fun adaptiveCharsPerPage(text: String, spec: PageCapacitySpec): Int {
        if (text.isBlank()) return CHARS_PER_PAGE
        if (spec.viewportWidthPx <= 0f || spec.viewportHeightPx <= 0f ||
            spec.fontSizePx <= 0f || spec.lineHeightPx <= 0f
        ) {
            return CHARS_PER_PAGE
        }
        val stats = textStats(text)
        val glyphWidthPx = (stats.avgGlyphEm * spec.fontSizePx + spec.letterSpacingPx)
            .coerceAtLeast(1f)
        val charsPerLine = (spec.viewportWidthPx / glyphWidthPx).toInt().coerceAtLeast(1)
        val avgParagraphChars = stats.avgParagraphChars.coerceAtLeast(8f)
        val heightPerChar = spec.lineHeightPx / charsPerLine +
            (spec.lineHeightPx / 2f + spec.paragraphSpacingPx) / avgParagraphChars
        if (heightPerChar <= 0f) return CHARS_PER_PAGE
        val estimated = spec.viewportHeightPx / heightPerChar
        val rounded = (estimated / 10f).toInt() * 10
        return rounded.coerceIn(MIN_CHARS_PER_PAGE, MAX_CHARS_PER_PAGE)
    }

    fun getPageContent(doc: ReaderDocument, page: Int, charsPerPage: Int = CHARS_PER_PAGE): String {
        if (doc.sampleText.isBlank()) return "暂无页面内容"
        val perPage = charsPerPage.coerceAtLeast(1)
        val total = doc.sampleText.length
        val start = (page.coerceAtLeast(1) - 1) * perPage
        if (start >= total) return "已阅读至末页"
        val end = minOf(total, start + perPage)
        return doc.sampleText.substring(start, end).trim()
    }

    /** 桌面导入入口：文件已存在磁盘上，复制到私有目录后走与安卓一致的解析链。 */
    suspend fun importDocument(context: Context, source: File): ReaderDocument? {
        lastError = null
        return try {
            val displayName = source.name
            val extension = displayName.substringAfterLast('.', "").lowercase()

            val docsDir = File(context.filesDir, "reader_docs")
            if (!docsDir.exists()) docsDir.mkdirs()
            val destFile = File(docsDir, "${UUID.randomUUID()}_$displayName")
            withContext(Dispatchers.IO) {
                source.copyTo(destFile, overwrite = false)
            }

            val fileSize = destFile.length()
            val format = when (extension) {
                "pdf" -> ReaderFormat.PDF
                "epub" -> ReaderFormat.EPUB
                "txt" -> ReaderFormat.TXT
                "md", "markdown" -> ReaderFormat.MARKDOWN
                "docx" -> ReaderFormat.DOCX
                "doc" -> ReaderFormat.DOC
                "pptx" -> ReaderFormat.PPTX
                "ppt" -> ReaderFormat.PPT
                "mobi", "azw" -> ReaderFormat.MOBI
                "azw3" -> ReaderFormat.AZW3
                else -> ReaderFormat.TXT
            }

            if (format == ReaderFormat.PDF) {
                val pageCount = pdfPageCount(destFile).coerceAtLeast(1)
                val bytes = withContext(Dispatchers.IO) {
                    runCatching { destFile.readBytes() }.getOrNull()
                }
                val textLayer = bytes?.let { PdfTextExtractor.extractTextLayer(it) }
                if (textLayer.isNullOrBlank()) {
                    Log.i(TAG, "PDF 无可用文本层，登记为扫描件（未识别）: $displayName, $pageCount 页")
                    return ReaderDocument(
                        id = UUID.randomUUID().toString(),
                        title = displayName,
                        filePath = destFile.absolutePath,
                        format = ReaderFormat.PDF,
                        fileSize = fileSize,
                        pageCount = pageCount,
                        currentPage = 1,
                        isScanned = true,
                        isOcrProcessed = false,
                        ragChunkCount = 0,
                        sampleText = "",
                    )
                }
                Log.i(TAG, "PDF 文本层命中（${textLayer.length} 字）: $displayName")
                return ReaderDocument(
                    id = UUID.randomUUID().toString(),
                    title = displayName,
                    filePath = destFile.absolutePath,
                    format = ReaderFormat.PDF,
                    fileSize = fileSize,
                    pageCount = pageCount,
                    currentPage = 1,
                    isScanned = false,
                    isOcrProcessed = false,
                    ragChunkCount = 0,
                    sampleText = textLayer,
                )
            }

            val parsed = try {
                withContext(Dispatchers.IO) {
                    DocumentParser.parse(destFile.readBytes(), displayName)
                }
            } catch (e: DocumentParser.UnsupportedFormatException) {
                lastError = e.message ?: "不支持的文件格式"
                destFile.delete()
                return null
            }
            val text = parsed.text
            if (text.isBlank()) {
                lastError = "「$displayName」解析后没有文字内容（可能是空文档）"
                destFile.delete()
                return null
            }
            val pageCount = ((text.length + CHARS_PER_PAGE - 1) / CHARS_PER_PAGE).coerceAtLeast(1)
            ReaderDocument(
                id = UUID.randomUUID().toString(),
                title = displayName,
                filePath = destFile.absolutePath,
                format = format,
                fileSize = fileSize,
                pageCount = pageCount,
                currentPage = 1,
                isScanned = false,
                isOcrProcessed = false,
                ragChunkCount = 0,
                sampleText = text,
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "导入真实文档失败: ${e.message}", e)
            lastError = "导入失败：${e.javaClass.simpleName} ${e.localizedMessage.orEmpty()}".trim()
            null
        }
    }

    private fun pdfPageCount(file: File): Int = try {
        org.apache.pdfbox.pdmodel.PDDocument.load(file).use { it.numberOfPages }
    } catch (e: Exception) {
        Log.w(TAG, "读取 PDF 页数失败，按 1 页处理", e)
        1
    }
}
