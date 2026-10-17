package com.treechat.workbench.ui.browser

import android.content.Context
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.webkit.WebView
import com.treechat.workbench.ui.chat.ChatNodeViewModel
import com.treechat.workbench.ui.reader.DocumentFileReader
import com.treechat.workbench.ui.reader.ReaderDocumentPool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * 网页一键高保真转为标准多页 PDF 并自动归档入书架与 RAG 知识库检索池。
 *
 * 核心特性：
 * 1. 利用 Android 原生 [PdfDocument] 将 WebView 渲染树直接离屏绘制为多页 A4 格式标准 PDF；
 * 2. 完整保留正文文本排版、字体样式与正文插图；
 * 3. 若当前处于「阅读模式」，打印内容天然即为去除了广告/导航/评论的干净智能正文；
 * 4. 自动送入 [DocumentFileReader] 与 [ChatNodeViewModel]，书架立刻上架、RAG 立即建切片索引。
 */
object WebToPdfExporter {

    sealed class ExportResult {
        data class Success(val title: String, val filePath: String, val charCount: Int) : ExportResult()
        data class Failure(val reason: String) : ExportResult()
    }

    /**
     * 将给定的 [webView] 当前页面内容导出为标准多页 PDF 文件并登记到书架。
     */
    suspend fun exportAndImportToShelf(
        context: Context,
        webView: WebView,
        documentTitle: String,
        viewModel: ChatNodeViewModel,
        pool: ReaderDocumentPool
    ): ExportResult = withContext(Dispatchers.Main) {
        val safeTitle = sanitizeFilename(documentTitle.ifBlank { "网页归档文献" })
        val booksDir = File(context.filesDir, "books").apply { if (!exists()) mkdirs() }
        val targetPdf = File(booksDir, "${safeTitle}_${System.currentTimeMillis()}.pdf")

        val renderSuccess = try {
            renderWebViewToPdf(webView, targetPdf)
        } catch (e: Throwable) {
            return@withContext ExportResult.Failure("生成 PDF 失败: ${e.localizedMessage ?: "未知错误"}")
        }

        if (!renderSuccess || !targetPdf.exists() || targetPdf.length() <= 0L) {
            return@withContext ExportResult.Failure("PDF 文件生成为空或失败")
        }

        // 调用书架与阅读器统一导入管线
        val doc = withContext(Dispatchers.IO) {
            DocumentFileReader.importFromUri(context, Uri.fromFile(targetPdf))
        }

        if (doc == null) {
            return@withContext ExportResult.Failure("书架入库解析失败")
        }

        pool.addDocument(doc)

        // 写入 Room 与 RAG 检索
        if (doc.sampleText.isNotBlank()) {
            viewModel.importShelfDocument(
                documentId = doc.id,
                title = doc.title,
                fileName = doc.title,
                sourceType = "PDF",
                text = doc.sampleText
            )
        } else {
            viewModel.ensureShelfRoomDocument(
                documentId = doc.id,
                title = doc.title,
                fileName = doc.title,
                sourceType = "PDF",
                charCount = 0
            )
        }

        ExportResult.Success(
            title = doc.title,
            filePath = targetPdf.absolutePath,
            charCount = doc.sampleText.length
        )
    }

    /**
     * 将 WebView 内容按 A4 纸张比例与物理高度优雅分页绘制至 [PdfDocument] 中。
     */
    private fun renderWebViewToPdf(
        webView: WebView,
        outputFile: File
    ): Boolean {
        // A4 尺寸标准：595 x 842 pt (72 dpi)
        val a4Width = 595
        val a4Height = 842

        val viewWidth = webView.width.coerceAtLeast(360)
        val contentHeight = (webView.contentHeight * webView.scale).toInt().coerceAtLeast(webView.height)

        val scaleFactor = a4Width.toFloat() / viewWidth.toFloat()
        val scaledPageContentHeight = (a4Height / scaleFactor).toInt().coerceAtLeast(100)

        val totalPages = ((contentHeight + scaledPageContentHeight - 1) / scaledPageContentHeight).coerceIn(1, 200)

        val pdfDocument = PdfDocument()

        try {
            for (pageIndex in 0 until totalPages) {
                val pageInfo = PdfDocument.PageInfo.Builder(a4Width, a4Height, pageIndex + 1).create()
                val page = pdfDocument.startPage(pageInfo)
                val canvas = page.canvas

                // 缩放并平移绘制该页内容
                canvas.save()
                canvas.scale(scaleFactor, scaleFactor)
                canvas.translate(0f, -pageIndex * scaledPageContentHeight.toFloat())

                webView.draw(canvas)
                canvas.restore()

                pdfDocument.finishPage(page)
            }

            FileOutputStream(outputFile).use { out ->
                pdfDocument.writeTo(out)
            }
            return true
        } finally {
            pdfDocument.close()
        }
    }

    private fun sanitizeFilename(raw: String): String {
        return raw.replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_").trim('_').take(60)
    }
}
