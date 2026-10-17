package com.treechat.workbench.rag

import android.content.Context
import android.net.Uri
import android.util.Log
import com.treechat.workbench.ocr.PdfOcr
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * 从导入的文件里抽出纯文本。
 *
 * ## 为什么自己解析 docx 而不引依赖
 * `.docx` 本质是一个 zip，正文在 `word/document.xml` 里。
 * 只需要"取出文本 + 保留段落边界"的话，用 JDK 自带的
 * `ZipInputStream` + `DocumentBuilderFactory` 就够了（约 100 行），
 * 引 Apache POI 会给这个 4MB 的 APK 增加数 MB 体积，还会带来版本冲突风险。
 * 本项目对此已有先例（自研 Markdown、自研 PDF 导出）。
 *
 * ## 已知的取舍（诚实记录）
 * - **不支持 `.doc`（旧二进制格式）**：那是完全不同的格式，需要 POI 那一级别的库。
 *   遇到 `.doc` 会明确报错并提示另存为 `.docx`，而不是给出一堆乱码。
 * - **不解析图片 / 表格内的复杂嵌套**：表格单元格文本会被取出，但会丢失列对齐；
 *   教材正文通常是段落，这个取舍可接受。
 * - **不解析 PDF 的复杂结构**：`.pdf` 先由 [PdfTextExtractor] 直抽文本层
 *   （PDFBox），抽不到（扫描件 / 字体缺 ToUnicode CMap）才回落到
 *   [PdfOcr] 整本离线 OCR。两页并排的分栏、图表内的文字会丢失版式，
 *   与"能读"相比这是可接受的取舍。
 * - **MOBI / AZW3 走自研 [MobiParser]**：解析 PalmDB 容器 + PalmDOC 解压，
 *   无 DRM 时可用；受 DRM 保护、或使用 HUFF/CDIC 压缩的文件会**明确报错**
 *   而不是给出乱码。详见该文件的说明与边界。
 */
object DocumentParser {

    /** 解析结果：纯文本 + 识别到的来源类型。 */
    data class Parsed(
        val text: String,
        val sourceType: String
    )

    class UnsupportedFormatException(message: String) : Exception(message)

    /**
     * 按 URI 解析。按扩展名分派，遇到不支持的类型抛出可读的错误。
     *
     * **是 suspend**：`.pdf` 先试文本层直抽（[PdfTextExtractor]，毫秒级）；
     * 只有抽不到时才回落到整本离线 OCR（[PdfOcr]，几百页教材要跑几十秒），
     * 两条路都不能在主线程做。
     */
    suspend fun parse(
        context: Context,
        uri: Uri,
        displayName: String,
        onOcrProgress: PdfOcr.ProgressListener? = null
    ): Parsed {
        val lower = displayName.lowercase()
        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                out.write(buf, 0, n)
            }
            out.toByteArray()
        } ?: throw UnsupportedFormatException("无法读取文件：$displayName")

        return when {
            lower.endsWith(".docx") -> Parsed(parseDocx(bytes), "DOCX")
            lower.endsWith(".doc") -> throw UnsupportedFormatException(
                "不支持旧的 .doc 二进制格式（OLE 复合文档）。" +
                    "请用 Word 打开后「另存为」.docx，再导入。"
            )
            lower.endsWith(".pptx") -> Parsed(parsePptx(bytes), "PPTX")
            lower.endsWith(".ppt") -> throw UnsupportedFormatException(
                "不支持旧的 .ppt 二进制格式。请用 PowerPoint「另存为」.pptx，再导入。"
            )
            lower.endsWith(".pdf") -> {
                // 快路径：文字版 PDF（电子书 / 导出的讲义）每页都带文本层，
                // 用 PDFBox 直接抽字符即可，不必渲染成图再让 ML Kit 逐字猜。
                // 判定阈值与诚实回落的语义见 PdfTextExtractor。
                val textLayer = PdfTextExtractor.extractTextLayer(bytes)
                if (textLayer != null) {
                    Log.i(PdfTextExtractor.TAG, "PDF 走文本层直抽（${textLayer.length} 字），不跑 OCR")
                    Parsed(textLayer, "PDF")
                } else {
                    // 回落：扫描件，或字体缺 ToUnicode CMap 的 PDF。
                    // 这条路径与本类改造前**完全一致**，onOcrProgress 照常透传。
                    Log.i(PdfTextExtractor.TAG, "PDF 回落整本 OCR（文本层不可用）")
                    val text = PdfOcr.extractText(context, bytes, onOcrProgress)
                    if (!text.isNullOrBlank()) {
                        Parsed(text, "PDF")
                    } else {
                        throw UnsupportedFormatException(
                            "PDF 未能识别出文字（离线 OCR 失败或该文件没有可识别内容）。" +
                                "请确认扫描件清晰度后重试。"
                        )
                    }
                }
            }
            lower.endsWith(".epub") -> Parsed(parseEpub(bytes), "EPUB")
            // PalmDB 容器族：`.mobi` 与 `.azw3`（KF8）本质同族，`.azw` 是同族的旧扩展名，
            // 一并交给自研 [MobiParser]，避免落到下面"未知扩展名按文本猜"的分支产出乱码。
            // DRM、HUFF/CDIC 压缩、结构损坏等一律抛可读异常，绝不用乱码冒充成功。
            lower.endsWith(".mobi") || lower.endsWith(".azw3") || lower.endsWith(".azw") -> {
                val text = try {
                    MobiParser.extractText(bytes)
                } catch (e: MobiParser.MobiException) {
                    throw UnsupportedFormatException(e.message ?: "MOBI/AZW3 解析失败。")
                }
                Parsed(text, if (lower.endsWith(".azw3")) "AZW3" else "MOBI")
            }
            lower.endsWith(".md") || lower.endsWith(".markdown") ->
                Parsed(decodeText(bytes), "MARKDOWN")
            lower.endsWith(".txt") || lower.endsWith(".text") ->
                Parsed(decodeText(bytes), "TEXT")
            else -> {
                // 未知扩展名：按 UTF-8 文本尝试，失败则给出可读提示
                val text = decodeText(bytes)
                if (text.isBlank()) {
                    throw UnsupportedFormatException(
                        "无法识别该文件格式（$displayName）。支持 .docx / .pptx / .pdf / .epub / .mobi / .azw3 / .md / .txt"
                    )
                }
                Parsed(text, "TEXT")
            }
        }
    }

    /** 解析纯文本；自动处理 UTF-8 与 BOM / GBK 的常见情况。 */
    fun parsePlainText(text: String, sourceType: String): Parsed =
        Parsed(text, sourceType)

    /**
     * 文本解码：先试 UTF-8（严格模式，避免把 GBK 中文解成乱码仍算"成功"），
     * 失败再试 GBK —— 中文教材的 txt 有不少是 GBK 保存的。
     */
    private fun decodeText(bytes: ByteArray): String {
        // 去掉 UTF-8 BOM
        val start = if (bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
        ) 3 else 0
        val body = if (start == 0) bytes else bytes.copyOfRange(start, bytes.size)

        return try {
            val decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
            decoder.decode(java.nio.ByteBuffer.wrap(body)).toString()
        } catch (e: Exception) {
            // UTF-8 严格解码失败 → 多半是 GBK
            runCatching { String(body, charset("GBK")) }
                .getOrElse { String(body, Charsets.ISO_8859_1) }
        }
    }

    /**
     * 解析 docx 正文。
     *
     * 只取 `word/document.xml`，把 `<w:p>`（段落）之间插入换行，
     * `<w:t>`（文本 run）内容拼接起来。其他元素（样式、批注、修订）
     * 一律忽略 —— 教材正文不需要它们。
     */
    private fun parseDocx(bytes: ByteArray): String {
        var documentXml: ByteArray? = null
        ZipInputStream(bytes.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name == "word/document.xml") {
                    val out = ByteArrayOutputStream()
                    val buf = ByteArray(32 * 1024)
                    while (true) {
                        val n = zip.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                    }
                    documentXml = out.toByteArray()
                    break
                }
            }
        }
        val xml = documentXml
            ?: throw UnsupportedFormatException(
                "这个 .docx 里找不到正文（word/document.xml）。文件可能已损坏，" +
                    "或它是加密文档。"
            )

        // 关闭外部实体解析：导入的文件来自外部，必须防 XXE
        val factory = safeDocumentBuilderFactory()
        val doc = factory.newDocumentBuilder().parse(documentXml.inputStream())
        val sb = StringBuilder()

        // 逐段遍历：w:p = 段落，w:t = 文本片段
        val paragraphs = doc.getElementsByTagName("w:p")
        for (i in 0 until paragraphs.length) {
            val node = paragraphs.item(i)
            val children = node.childNodes
            val paragraph = StringBuilder()
            for (j in 0 until children.length) {
                val child = children.item(j)
                collectText(child, paragraph)
            }
            val line = paragraph.toString().trim()
            if (line.isNotEmpty()) {
                sb.append(line).append('\n')
            }
        }
        return sb.toString()
    }

    /** 递归收集 w:t 的文本；遇到 w:tab / w:br 转成空白或换行。 */
    private fun collectText(node: org.w3c.dom.Node, out: StringBuilder) {
        val name = node.nodeName
        if (name == "w:t") {
            out.append(node.textContent ?: "")
            return
        }
        if (name == "w:tab") {
            out.append(' ')
            return
        }
        if (name == "w:br") {
            out.append('\n')
            return
        }
        val children = node.childNodes
        for (i in 0 until children.length) {
            collectText(children.item(i), out)
        }
    }

    /**
     * 解析 EPUB 电子书，解压读取各章节 XML/HTML 中的文本
     */
    private fun parseEpub(bytes: ByteArray): String {
        val sb = StringBuilder()
        try {
            ZipInputStream(bytes.inputStream()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val name = entry.name.lowercase()
                    if ((name.endsWith(".html") || name.endsWith(".xhtml") || name.endsWith(".htm")) && !name.contains("toc")) {
                        val out = ByteArrayOutputStream()
                        val buf = ByteArray(16 * 1024)
                        while (true) {
                            val n = zip.read(buf)
                            if (n <= 0) break
                            out.write(buf, 0, n)
                        }
                        val html = out.toString("UTF-8")
                        // 过滤 HTML 标签提取纯文字
                        val clean = html.replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim()
                        if (clean.length > 20) {
                            sb.append(clean).append("\n\n")
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        val res = sb.toString().trim()
        if (res.isNotBlank()) return res
        throw UnsupportedFormatException("未能从该 EPUB 提取到有效章节文本。")
    }

    /**
     * 解析 PPTX 正文。
     *
     * 与 docx 同理：`.pptx` 也是一个 zip，每页幻灯片在 `ppt/slides/slideN.xml`，
     * 文本在 `<a:t>` 元素里。按幻灯片顺序拼接，页与页之间空行分隔。
     */
    private fun parsePptx(bytes: ByteArray): String {
        val slides = sortedMapOf<Int, ByteArray>()
        ZipInputStream(bytes.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name
                if (!name.startsWith("ppt/slides/slide") || !name.endsWith(".xml")) continue
                val index = name.removePrefix("ppt/slides/slide").removeSuffix(".xml").toIntOrNull()
                    ?: continue
                val out = ByteArrayOutputStream()
                val buf = ByteArray(32 * 1024)
                while (true) {
                    val n = zip.read(buf)
                    if (n <= 0) break
                    out.write(buf, 0, n)
                }
                slides[index] = out.toByteArray()
            }
        }
        if (slides.isEmpty()) {
            throw UnsupportedFormatException(
                "这个 .pptx 里找不到幻灯片正文（ppt/slides/*.xml）。文件可能已损坏或为纯图片演示。"
            )
        }
        val sb = StringBuilder()
        for ((index, xml) in slides) {
            val text = extractTagsText(xml, "a:t")
            if (text.isNotBlank()) {
                sb.append("【第 ").append(index).append(" 页】\n").append(text).append("\n\n")
            }
        }
        val result = sb.toString().trim()
        if (result.isBlank()) {
            throw UnsupportedFormatException(
                "这个 .pptx 的幻灯片里没有文字（可能是纯图片演示，需要 OCR）。"
            )
        }
        return result
    }

    /** 用 DOM 解析一段 XML，把所有指定标签名的文本按出现顺序拼成多行。 */
    private fun extractTagsText(xml: ByteArray, tag: String): String {
        val factory = safeDocumentBuilderFactory()
        val doc = factory.newDocumentBuilder().parse(xml.inputStream())
        val nodes = doc.getElementsByTagName(tag)
        val sb = StringBuilder()
        for (i in 0 until nodes.length) {
            val t = nodes.item(i).textContent
            if (!t.isNullOrBlank()) sb.append(t).append('\n')
        }
        return sb.toString().trim()
    }

    /**
     * 关闭外部实体解析（防 XXE），但**每一项都尽力而为**。
     *
     * 为什么必须容错：上面那几个特性名是 **Xerces 专有**的（`disallow-doctype-decl`、
     * `external-*-entities`）。Android 自带的 XML 解析器不认它们，`setFeature` 会直接抛
     * `ParserConfigurationException` —— 结果就是**任何 .docx / .pptx 一导入就失败**
     * （用户实测反馈过"docx 依然失败"，根因就在这里）。
     * 现在逐项 try/catch：支持的照常设上，不认的跳过，解析本身不受影响。
     */
    private fun safeDocumentBuilderFactory(): DocumentBuilderFactory {
        val f = DocumentBuilderFactory.newInstance()
        f.isNamespaceAware = false
        val feature = { name: String, value: Boolean ->
            runCatching { f.setFeature(name, value) }
        }
        feature("http://apache.org/xml/features/disallow-doctype-decl", true)
        feature("http://xml.org/sax/features/external-general-entities", false)
        feature("http://xml.org/sax/features/external-parameter-entities", false)
        runCatching { f.isXIncludeAware = false }
        runCatching { f.isExpandEntityReferences = false }
        return f
    }
}
