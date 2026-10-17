package com.treechat.workbench.rag

import android.util.Log
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper

/**
 * PdfTextExtractor —— PDF「文本层直抽」快路径。
 *
 * ── 为什么需要它 ──────────────────────────────────────────────────────
 * 改造前 [DocumentParser] 的 `.pdf` 分支无条件走整本渲染 + 离线 OCR
 * （[com.treechat.workbench.ocr.PdfOcr]）：对扫描件这是唯一可行的路，
 * 但对**文字版 PDF**（电子书、导出的讲义）纯属白跑 ——
 * 那类 PDF 每页都带文本层（内容流 + ToUnicode CMap），
 * 本来可以直接读出字符，却被渲染成位图再由 ML Kit 逐字猜回来，
 * 既慢（数百 ms/页）又可能引入识别错字。
 *
 * 这里用 Apache PDFBox 的 Android 移植（`com.tom-roush:pdfbox-android`）
 * 直接抽取文本层，命中就返回，命中不了才由调用方回落到 OCR。
 *
 * ── 判定「抽到了」的口径（[MIN_CHARS] / [MIN_LINES]）─────────────────
 * 规范化（去掉所有空白字符）后 **≥ 200 字** 且 **≥ 3 个非空行** 才算抽到。
 * 两道阈值缺一不可：
 *   - 只卡字数：某些 PDF 会把整本书的页眉/页脚/水印抽成一行超长串，
 *     或抽出几百字的目录骨架，看着"够长"其实不是正文；
 *   - 只卡行数：少量残留的书签/注释行也能凑出 3 行。
 * 两个都满足，才认为是真正的可读正文层。
 *
 * ── 诚实的失败语义 ────────────────────────────────────────────────────
 * 任何一步失败（PDF 打不开、PDFBox 未初始化、字体缺 ToUnicode 映射、
 * 加密文档、抽出来的是一堆不可见字符）一律返回 **null**，
 * 绝不返回空串或半截乱码 —— 返回 null 才会让调用方回落到 OCR，
 * 返回空串会被上层误当成"这本书是空的"。
 * 抽取结果也**不做任何美化**：不补字、不改行列，原样返回。
 */
object PdfTextExtractor {

    /** 日志 TAG。DocumentParser 的 PDF 分支复用它来记录走的是哪条路。 */
    const val TAG = "PdfTextExtractor"

    /** 「抽到了」的字数下限：规范化（去空白）后的字符数。 */
    const val MIN_CHARS = 200

    /** 「抽到了」的行数下限：非空行的数量。 */
    const val MIN_LINES = 3

    /**
     * 尝试直接抽取 PDF 的文本层。
     *
     * @param pdf PDF 原始字节（调用方已整体读入内存）
     * @return 抽到的正文（已 trim 首尾空白，**保留原有换行/分页结构**）；
     *         达不到 [MIN_CHARS]/[MIN_LINES] 阈值、或任何异常，返回 null。
     */
    fun extractTextLayer(pdf: ByteArray?): String? {
        if (pdf == null || pdf.isEmpty()) {
            Log.w(TAG, "文本层抽取跳过：字节为空")
            return null
        }
        // 资源（glyphlist / AFM / 内置字体）由 PDFBoxResourceLoader.init 注入，
        // 必须在 TreeChatApp.onCreate() 里先初始化。没初始化时不硬闯 ——
        // 直接让 load() 抛错再回到 OCR，避免打出难懂的 NPE。
        if (!PDFBoxResourceLoader.isReady()) {
            Log.w(TAG, "PDFBox 资源未初始化（PDFBoxResourceLoader.init 未调用），回落 OCR")
            return null
        }

        var doc: PDDocument? = null
        return try {
            val opened = PDDocument.load(pdf)
            doc = opened
            // sortByPosition=true：按页面坐标排序，避免分栏/表格里的文字按流顺序错乱。
            val stripper = PDFTextStripper().apply { sortByPosition = true }
            val raw = stripper.getText(opened).orEmpty()

            // 判定用的规范化计数：只统计非空白字符与非空行，不改动返回的内容。
            val nonBlankChars = raw.count { !it.isWhitespace() }
            val nonBlankLines = raw.lineSequence().count { it.isNotBlank() }
            val pageCount = runCatching { opened.numberOfPages }.getOrDefault(0)

            if (nonBlankChars >= MIN_CHARS && nonBlankLines >= MIN_LINES) {
                val text = raw.trim()
                Log.i(
                    TAG,
                    "PDF 文本层命中：$pageCount 页，$nonBlankChars 字 / $nonBlankLines 非空行" +
                        "（阈值 $MIN_CHARS 字 / $MIN_LINES 行）→ 不跑 OCR"
                )
                text
            } else {
                Log.i(
                    TAG,
                    "PDF 文本层不足：$pageCount 页，仅 $nonBlankChars 字 / $nonBlankLines 非空行" +
                        "（低于阈值 $MIN_CHARS 字 / $MIN_LINES 行）→ 回落 OCR"
                )
                null
            }
        } catch (t: Throwable) {
            // 字体缺 ToUnicode CMap、加密文档、损坏文件等都会走到这里 —— 一律回落 OCR。
            Log.w(TAG, "PDF 文本层抽取失败，回落 OCR：$t")
            null
        } finally {
            try {
                doc?.close()
            } catch (t: Throwable) {
                Log.w(TAG, "关闭 PDDocument 失败（不影响结果）：$t")
            }
        }
    }
}
