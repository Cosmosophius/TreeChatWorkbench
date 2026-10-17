package com.treechat.workbench.ui.reader

import android.content.Context
import android.database.Cursor
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.Log
import com.treechat.workbench.rag.DocumentParser
import com.treechat.workbench.rag.PdfTextExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * 真实文档导入与解析引擎 (DocumentFileReader)
 *
 * ## 导入只做"立刻能完成的事"，绝不自动 OCR
 * 用户明确要求："导入扫描档不要自动 OCR，直接导入就好，我自己选择是否 OCR。"
 * 因此本类的 PDF 分支**只抽文本层**（[PdfTextExtractor]，毫秒级）：
 *  - **有文本层**（文字版 PDF / 电子书 / 导出的讲义）→ 直接抽字符，登记为普通文档；
 *  - **没有文本层**（扫描件）→ **不抽取正文**，把书登记进来、正文留空，并标记
 *    `isScanned = true, isOcrProcessed = false`（即"未识别"）。
 *    是否识别、识别哪一页，全部交给用户在阅读器里显式触发
 *    （见 [ReaderScreen] 的「识别本页 / 识别整本」入口与 [com.treechat.workbench.ocr.PdfOcr]）。
 *
 * 为什么不能在导入时 OCR：几百页的扫描教材整本识别要跑几十秒到几分钟，
 * 用户只是想"先把书放进书架看一眼"，不该被强行拖住。
 *
 * ## 其余格式
 * `.txt/.md/.epub/.docx/.pptx/.mobi/.azw3` 照旧由 [DocumentParser] 抽文本
 * （这些格式本就不含 OCR 步骤），失败时给出可读错误。
 *
 * 旧的 `.doc` / `.ppt` 是 OLE 复合二进制格式，**明确报错**并提示另存为
 * `.docx` / `.pptx`，而不是硬读字节出一堆乱码。
 *
 * 真实分页：长文按字数切分，支持真翻页与进度记忆。默认口径是 [CHARS_PER_PAGE]，
 * 阅读器里会换成按实测视口算出来的 [adaptiveCharsPerPage]（平板不再留大片空白）。
 */
object DocumentFileReader {

    private const val TAG = "DocumentFileReader"

    /**
     * 文本阅读每页约多少字（真实分页口径，书架与阅读器共用）。
     *
     * 这是**导入期**的默认口径，也是自适应分页算不出来时（视口还没测到）的兜底值。
     * 阅读器真正用的每页字数由 [adaptiveCharsPerPage] 按实测视口现算 ——
     * 否则平板上会出现"一页字排不满、底下一大片空白"。
     */
    const val CHARS_PER_PAGE = 800

    /** 自适应每页字数的上下限：防极端排版把一页切成三个字、或把整本书塞进一页。 */
    private const val MIN_CHARS_PER_PAGE = 120
    private const val MAX_CHARS_PER_PAGE = 6000

    /**
     * 自适应分页所需的版面度量，全部是**像素**，由阅读器按实测视口 + 当前排版算好传进来。
     *
     * 为什么分页算法要这些：页容量 = 可用高度 ÷ 每字实际占高，而"每字实际占高"
     * 取决于字号、行距与段距 —— 这些都由 [com.treechat.workbench.ui.reader.ReaderTypography]
     * 决定、并且用户随时会调，分页算法自己不持有任何全局状态，只认传进来的数。
     */
    data class PageCapacitySpec(
        /** 正文段落区可用宽度（卡片宽 − 卡片左右内边距）。 */
        val viewportWidthPx: Float,
        /** 正文段落区可用高度（页眉、卡片内边距之外真正能排字的高度）。 */
        val viewportHeightPx: Float,
        /** 正文字号（px，sp→px 已含系统字号缩放，与正文 Text 同源）。 */
        val fontSizePx: Float,
        /** 行高（px，= 字号 × 行距倍率）。 */
        val lineHeightPx: Float,
        /** 正文段落统一加的字距（px，与正文的 `letterSpacing = 0.3.sp` 对齐）。 */
        val letterSpacingPx: Float,
        /** 每段上下内边距之和（px，与正文段落的 `padding(vertical = 4.dp)` 对齐）。 */
        val paragraphSpacingPx: Float
    )

    /** 最近一次导入失败的可读原因（成功则为 null）。界面用它做提示，不假装成功。 */
    @Volatile
    var lastError: String? = null
        private set

    /** 正文排版统计（只算一次，按正文字符串的**引用**缓存）。 */
    private class TextStats(val avgGlyphEm: Float, val avgParagraphChars: Float)

    private val statsLock = Any()
    private var statsSource: String? = null
    private var statsCache: TextStats? = null

    /**
     * 单个字符的占宽估计（em：1em = 一个字号）。
     *
     * 中日韩全角按整字宽，西文字母数字约半个字号，空格与半角标点更窄。
     * 这是估算不是排版引擎，只要全书平均下来够准即可 —— 真正的换行仍由 Compose 完成。
     */
    private fun glyphEm(ch: Char): Float = when {
        ch.code >= 0x2E80 -> 1f            // CJK、全角标点
        ch == ' ' || ch == '\t' -> 0.3f
        ch.isLetterOrDigit() -> 0.55f       // 西文字母 / 数字
        else -> 0.4f                        // 半角标点
    }

    /**
     * 全书正文的排版统计：每字符平均宽度 + 平均段落长度。
     *
     * 只算一次并按**字符串引用**缓存（一次阅读会话里正文就是同一个 String 实例，
     * 引用比较零成本、也不会因内容微调误命中）。换书/OCR 重写正文才会重算。
     */
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

    /** 真正扫一遍正文（只在缓存未命中时跑，见 [textStats]）。 */
    private fun computeTextStats(text: String): TextStats {
        var glyphSum = 0f
        var glyphCount = 0f
        var paragraphCount = 0
        var paragraphChars = 0L
        // 段落口径与渲染完全一致：按换行切、丢掉空行。
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
            }
        )
    }

    /**
     * 按**实测视口**估算每页该装多少字：可用高度 ÷ 每字平均占高。
     *
     * 每字平均占高 = 行高 ÷ 每行字数 +（每段的半行余量 + 段距）÷ 平均段落长度。
     * 后半截是关键：正文是**逐段**排版的，每段都要另起一行、还带上下段距，
     * 只按"高度 ÷ 行高 × 每行字数"算会明显高估，平板上照样留一大截空白。
     *
     * 结果取整到 10 字：±1px 的测量抖动不该让整本书的分页跟着重排。
     * PDF / 扫描件不走这里 —— 它们按物理页分页本来就对。
     */
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

    /**
     * 把用户选中的文件复制到应用私有目录并解析出正文。
     *
     * **是 suspend**：文件复制与（非 PDF 的）解析都要读盘，必须放在 IO 协程里。
     * 注意：**这里不再有任何 OCR** —— 扫描件的正文留空，识别由用户在阅读器中显式触发。
     */
    suspend fun importFromUri(
        context: Context,
        uri: Uri
    ): ReaderDocument? {
        lastError = null
        return try {
            val displayName = queryDisplayName(context, uri)
                ?: "未命名文档_${System.currentTimeMillis() % 1000}"
            val extension = displayName.substringAfterLast('.', "").lowercase()

            val docsDir = File(context.filesDir, "reader_docs")
            if (!docsDir.exists()) docsDir.mkdirs()

            val destFile = File(docsDir, "${UUID.randomUUID()}_$displayName")
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(destFile).use { output -> input.copyTo(output) }
            } ?: run {
                lastError = "无法读取所选文件（权限被拒或文件已被移动）"
                return null
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
                // PalmDB 容器族：.azw 是同族旧扩展名，按 MOBI 处理（避免落到 else 的 TXT）。
                "mobi", "azw" -> ReaderFormat.MOBI
                "azw3" -> ReaderFormat.AZW3
                else -> ReaderFormat.TXT
            }

            // ---------- PDF：只抽文本层，抽不到就是扫描件，登记后正文留空 ----------
            if (format == ReaderFormat.PDF) {
                val pageCount = pdfPageCount(destFile).coerceAtLeast(1)
                val bytes = withContext(Dispatchers.IO) {
                    runCatching { destFile.readBytes() }.getOrNull()
                }
                val textLayer = bytes?.let { PdfTextExtractor.extractTextLayer(it) }
                val id = UUID.randomUUID().toString()
                if (textLayer.isNullOrBlank()) {
                    // 扫描件（或文本层不可用的 PDF）：登记为文档，正文留空，标记"未识别"。
                    Log.i(TAG, "PDF 无可用文本层，登记为扫描件（未识别）：$displayName，$pageCount 页")
                    return ReaderDocument(
                        id = id,
                        title = displayName,
                        filePath = destFile.absolutePath,
                        format = ReaderFormat.PDF,
                        fileSize = fileSize,
                        pageCount = pageCount,
                        currentPage = 1,
                        isScanned = true,
                        isOcrProcessed = false,
                        ragChunkCount = 0,
                        sampleText = ""
                    )
                }
                Log.i(TAG, "PDF 文本层命中（${textLayer.length} 字），按普通文档登记：$displayName")
                return ReaderDocument(
                    id = id,
                    title = displayName,
                    filePath = destFile.absolutePath,
                    format = ReaderFormat.PDF,
                    fileSize = fileSize,
                    pageCount = pageCount,
                    currentPage = 1,
                    isScanned = false,
                    isOcrProcessed = false,
                    ragChunkCount = 0,
                    sampleText = textLayer
                )
            }

            // ---------- 其它格式：交给 DocumentParser（不含任何 OCR） ----------
            val parsed = try {
                DocumentParser.parse(context, uri, displayName)
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
                sampleText = text
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "导入真实文档失败: ${e.message}", e)
            lastError = "导入失败：${e.javaClass.simpleName} ${e.localizedMessage.orEmpty()}".trim()
            null
        }
    }

    /**
     * 提取指定页的纯文本（真分页实现）。
     *
     * @param charsPerPage 本页该装多少字：由 [adaptiveCharsPerPage] 按实测视口现算；
     *   不传则退回导入期的 [CHARS_PER_PAGE] 口径（PDF/扫描件与"还没测到视口"的兜底）。
     */
    fun getPageContent(doc: ReaderDocument, page: Int, charsPerPage: Int = CHARS_PER_PAGE): String {
        if (doc.sampleText.isBlank()) return "暂无页面内容"
        val perPage = charsPerPage.coerceAtLeast(1)
        val total = doc.sampleText.length
        val start = (page.coerceAtLeast(1) - 1) * perPage
        if (start >= total) return "已阅读至末页"
        val end = minOf(total, start + perPage)
        return doc.sampleText.substring(start, end).trim()
    }

    /** 用 PdfRenderer 读真实页数；失败按 1 页计。 */
    private fun pdfPageCount(file: File): Int = try {
        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        try {
            PdfRenderer(pfd).use { it.pageCount }
        } finally {
            pfd.close()
        }
    } catch (e: Exception) {
        Log.w(TAG, "读取 PDF 页数失败，按 1 页处理", e)
        1
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? {
        if (uri.scheme == "content") {
            var cursor: Cursor? = null
            try {
                cursor = context.contentResolver.query(uri, null, null, null, null)
                if (cursor != null && cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) return cursor.getString(index)
                }
            } finally {
                cursor?.close()
            }
        }
        return uri.path?.substringAfterLast('/')
    }
}
