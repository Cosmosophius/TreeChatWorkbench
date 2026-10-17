// 桌面替身：rag.DocumentParser（JVM 逻辑同款，直接吃字节）。
// 原版的 ZipInputStream/DocumentBuilderFactory/MobiParser 全是纯 JVM 可复用；
// URI/ContentResolver 특有路径按 File 语义收口。
@file:Suppress("unused", "MemberVisibilityCanBePrivate")
package com.treechat.workbench.rag

import com.treechat.workbench.ocr.PdfOcr
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

object DocumentParser {

    data class Parsed(
        val text: String,
        val sourceType: String,
    )

    class UnsupportedFormatException(message: String) : Exception(message)

    /** 桌面版入口：直接给字节（书架导入已把文件复制到 filesDir）。 */
    suspend fun parse(
        bytes: ByteArray,
        displayName: String,
        onOcrProgress: PdfOcr.ProgressListener? = null,
    ): Parsed {
        val lower = displayName.lowercase()
        return when {
            lower.endsWith(".docx") -> Parsed(parseDocx(bytes), "DOCX")
            lower.endsWith(".doc") -> throw UnsupportedFormatException(
                "不支持旧的 .doc 二进制格式（OLE 复合文档）。请用 Word 打开后「另存为」.docx，再导入。"
            )
            lower.endsWith(".pptx") -> Parsed(parsePptx(bytes), "PPTX")
            lower.endsWith(".ppt") -> throw UnsupportedFormatException(
                "不支持旧的 .ppt 二进制格式。请用 PowerPoint「另存为」.pptx，再导入。"
            )
            lower.endsWith(".pdf") -> {
                val textLayer = PdfTextExtractor.extractTextLayer(bytes)
                if (textLayer != null) {
                    Parsed(textLayer, "PDF")
                } else {
                    // 桌面 v1 扫描件不做真 OCR：「回落」用文字层抽取（成功即非扫描件）。
                    val text = com.treechat.workbench.ocr.PdfOcr.extractText(
                        com.treechat.desktop.DesktopAppEnvHolder.appContext, bytes,
                    )
                    if (text.isNotBlank()) Parsed(text, "PDF")
                    else throw UnsupportedFormatException(
                        "PDF 未能抽取到文字（桌面端暂无整本 OCR，文字层不可用的扫描件预留）。"
                    )
                }
            }
            lower.endsWith(".epub") -> Parsed(parseEpub(bytes), "EPUB")
            lower.endsWith(".mobi") || lower.endsWith(".azw3") || lower.endsWith(".azw") -> {
                val text = try {
                    MobiParser.extractText(bytes)
                } catch (e: MobiParser.MobiException) {
                    throw UnsupportedFormatException(e.message ?: "MOBI/AZW3 解析失败。")
                }
                Parsed(text, if (lower.endsWith(".azw3")) "AZW3" else "MOBI")
            }
            lower.endsWith(".md") || lower.endsWith(".markdown") -> Parsed(decodeText(bytes), "MARKDOWN")
            lower.endsWith(".txt") || lower.endsWith(".text") -> Parsed(decodeText(bytes), "TEXT")
            else -> {
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

    fun parsePlainText(text: String, sourceType: String): Parsed = Parsed(text, sourceType)

    private fun decodeText(bytes: ByteArray): String {
        val start = if (bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
        ) 3 else 0
        val body = if (start == 0) bytes else bytes.copyOfRange(start, bytes.size)
        return try {
            val decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
            decoder.decode(java.nio.ByteBuffer.wrap(body)).toString()
        } catch (_: Exception) {
            runCatching { String(body, charset("GBK")) }
                .getOrElse { String(body, Charsets.ISO_8859_1) }
        }
    }

    private fun parseDocx(bytes: ByteArray): String {
        var documentXml: ByteArray? = null
        ZipInputStream(bytes.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name == "word/document.xml") {
                    documentXml = readEntry(zip)
                    break
                }
            }
        }
        val xml = documentXml
            ?: throw UnsupportedFormatException(
                "这个 .docx 里找不到正文（word/document.xml）。文件可能已损坏，或它是加密文档。"
            )
        val factory = safeDocumentBuilderFactory()
        val doc = factory.newDocumentBuilder().parse(xml.inputStream())
        val sb = StringBuilder()
        val paragraphs = doc.getElementsByTagName("w:p")
        for (i in 0 until paragraphs.length) {
            val node = paragraphs.item(i)
            val children = node.childNodes
            val paragraph = StringBuilder()
            for (j in 0 until children.length) {
                collectText(childNode(children, j), paragraph)
            }
            val line = paragraph.toString().trim()
            if (line.isNotEmpty()) sb.append(line).append('\n')
        }
        return sb.toString()
    }

    private fun childNode(children: org.w3c.dom.NodeList, index: Int): org.w3c.dom.Node = children.item(index)

    private fun collectText(node: org.w3c.dom.Node, out: StringBuilder) {
        val name = node.nodeName
        if (name == "w:t") { out.append(node.textContent ?: ""); return }
        if (name == "w:tab") { out.append(' '); return }
        if (name == "w:br") { out.append('\n'); return }
        val children = node.childNodes
        for (i in 0 until children.length) collectText(children.item(i), out)
    }

    private fun parseEpub(bytes: ByteArray): String {
        val sb = StringBuilder()
        try {
            ZipInputStream(bytes.inputStream()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val name = entry.name.lowercase()
                    if ((name.endsWith(".html") || name.endsWith(".xhtml") || name.endsWith(".htm")) && !name.contains("toc")) {
                        val html = String(readEntry(zip), Charsets.UTF_8)
                        val clean = html.replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim()
                        if (clean.length > 20) sb.append(clean).append("\n\n")
                    }
                }
            }
        } catch (_: Exception) {}
        val res = sb.toString().trim()
        if (res.isNotBlank()) return res
        throw UnsupportedFormatException("未能从该 EPUB 提取到有效章节文本。")
    }

    private fun parsePptx(bytes: ByteArray): String {
        val slides = sortedMapOf<Int, ByteArray>()
        ZipInputStream(bytes.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name
                if (!name.startsWith("ppt/slides/slide") || !name.endsWith(".xml")) continue
                val index = name.removePrefix("ppt/slides/slide").removeSuffix(".xml").toIntOrNull() ?: continue
                slides[index] = readEntry(zip)
            }
        }
        if (slides.isEmpty()) {
            throw UnsupportedFormatException("这个 .pptx 里找不到幻灯片正文（ppt/slides/*.xml）。文件可能已损坏或为纯图片演示。")
        }
        val sb = StringBuilder()
        for ((index, xml) in slides) {
            val text = extractTagsText(xml, "a:t")
            if (text.isNotBlank()) {
                sb.append("【第 ").append(index).append(" 页】\n").append(text).append("\n\n")
            }
        }
        val result = sb.toString().trim()
        if (result.isBlank()) throw UnsupportedFormatException("这个 .pptx 的幻灯片里没有文字（可能是纯图片演示，需要 OCR）。")
        return result
    }

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

    private fun safeDocumentBuilderFactory(): DocumentBuilderFactory {
        val f = DocumentBuilderFactory.newInstance()
        f.isNamespaceAware = false
        val feature = { name: String, value: Boolean -> runCatching { f.setFeature(name, value) } }
        feature("http://apache.org/xml/features/disallow-doctype-decl", true)
        feature("http://xml.org/sax/features/external-general-entities", false)
        feature("http://xml.org/sax/features/external-parameter-entities", false)
        runCatching { f.isXIncludeAware = false }
        runCatching { f.isExpandEntityReferences = false }
        return f
    }

    private fun readEntry(zip: ZipInputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(32 * 1024)
        while (true) {
            val n = zip.read(buf)
            if (n <= 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }
}
