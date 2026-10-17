package com.treechat.workbench.ui.reader

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.treechat.workbench.data.prefs.AppSettings
import com.treechat.workbench.ocr.PdfOcr
import com.treechat.workbench.ocr.PdfPageSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URLDecoder
import java.util.zip.ZipFile

/**
 * 真实封面抽取与缓存（对齐 Koodo Reader 的思路，但用我们自己的解析通路）。
 *
 * ## 缓存在哪
 * `context.filesDir/covers/<documentId>.<ext>`。路径**完全由 documentId 推导**，
 * 因此**不新增任何 Room 列/表/迁移**：书卡的封面 = "按 id 找缓存文件，找到就用、
 * 找不到就画生成封面"。删除书时连同封面缓存一起删。
 *
 * ## 每种格式从哪取（取不到一律返回 null，由界面回落到生成封面，绝不让导入失败）
 * - **EPUB**：解压 zip，`META-INF/container.xml` → OPF；EPUB3 取 manifest 里
 *   `properties="cover-image"` 的项，EPUB2 取 `<meta name="cover" content="id">`
 *   指向的 manifest 项；再回落"文件名含 cover 的第一张图"，最后回落"第一张图"。
 * - **PDF / 扫描档**：用工程既有的 [PdfPageSource]（PdfRenderer）**渲染第 1 页**当封面。
 *   大 PDF 渲染会慢，所以做成**可开关**（[ReaderCoverPrefs.pdfCoverEnabled]）。
 * - **MOBI / AZW3**：PalmDB 的 EXTH 里 type 201 = cover offset、202 = thumb offset，
 *   图片记录下标 = `firstImageIndex + offset`（firstImageIndex 来自 MOBI 头偏移 92），
 *   取出该记录后按魔数在记录内定位图片起点。这里是一份**只取封面**的精简版，
 *   不改动也复用了 [com.treechat.workbench.rag.MobiParser] 的同款字节读法。
 * - **DOCX / PPTX**：解压取 `word/media/` 与 `ppt/media/` 目录里的第一张图。
 * - **TXT / MD**：没有封面，直接回落生成封面。
 *
 * ## 诚实边界
 * 取不到（无封面、加密、HUFF/CDIC、不支持的图片类型、渲染失败）都返回 null，
 * **不伪造**：由界面画"生成封面"，而不是假装是真实封面。
 */
object CoverExtractor {

    private const val TAG = "CoverExtractor"

    /** PDF 首页渲染为封面时的目标倍率上限（越小越快、图越小）。 */
    private const val PDF_COVER_SCALE = 1.5f

    private fun coversDir(context: Context): File =
        File(context.filesDir, "covers").apply { if (!exists()) mkdirs() }

    /** 已缓存的封面文件（按 documentId 前缀匹配任意扩展名）；没有返回 null。 */
    fun findCached(context: Context, documentId: String): File? {
        val dir = coversDir(context)
        val prefix = "$documentId."
        return dir.listFiles()?.firstOrNull { it.isFile && it.name.startsWith(prefix) }
    }

    /** 删除某文档的封面缓存（删书时调用）。 */
    fun deleteCache(context: Context, documentId: String) {
        val dir = coversDir(context)
        val prefix = "$documentId."
        runCatching {
            dir.listFiles()?.forEach { if (it.isFile && it.name.startsWith(prefix)) it.delete() }
        }
    }

    /**
     * 抽取封面并写入缓存，返回缓存文件；取不到返回 null。
     *
     * 全程在 IO 协程里跑，绝不阻塞导入主流程；任何异常都被吞掉并记日志。
     *
     * @param allowPdfCover 是否允许渲染 PDF 首页当封面（可开关，见 [ReaderCoverPrefs]）。
     */
    suspend fun extract(
        context: Context,
        doc: ReaderDocument,
        allowPdfCover: Boolean
    ): File? = withContext(Dispatchers.IO) {
        val file = doc.filePath.takeIf { it.isNotBlank() }?.let(::File)
        if (file == null || !file.exists()) return@withContext null
        val existing = findCached(context, doc.id)
        if (existing != null) return@withContext existing

        val bytes: ByteArray? = try {
            when (doc.format) {
                ReaderFormat.EPUB -> epubCover(file)
                ReaderFormat.DOCX, ReaderFormat.DOC -> zipFirstImage(file, "word/media/")
                ReaderFormat.PPTX, ReaderFormat.PPT -> zipFirstImage(file, "ppt/media/")
                ReaderFormat.MOBI, ReaderFormat.AZW3 -> mobiCover(file)
                ReaderFormat.PDF, ReaderFormat.SCANNED_PDF ->
                    if (allowPdfCover) pdfFirstPage(context, file) else null
                ReaderFormat.TXT, ReaderFormat.MARKDOWN -> null
            }
        } catch (t: Throwable) {
            Log.w(TAG, "封面抽取失败（${doc.format}）：$t")
            null
        }

        val ext = bytes?.let { sniffImageExt(it) } ?: return@withContext null
        runCatching {
            val out = File(coversDir(context), "${doc.id}.$ext")
            out.writeBytes(bytes)
            Log.i(TAG, "封面已缓存：${doc.format} → ${out.name}（${bytes.size} 字节）")
            out
        }.getOrElse {
            Log.w(TAG, "封面写盘失败：$it")
            null
        }
    }

    // ------------------------------------------------------------------ PDF 首页

    private suspend fun pdfFirstPage(context: Context, file: File): ByteArray? {
        val src = PdfPageSource.open(file.absolutePath) ?: return null
        return try {
            val scale = minOf(PdfOcr.resolveRenderScale(context), PDF_COVER_SCALE)
            val bmp = src.renderPage(0, scale) ?: return null
            try {
                val out = ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
                out.toByteArray()
            } finally {
                bmp.recycle()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "PDF 首页封面渲染失败：$t")
            null
        } finally {
            src.close()
        }
    }

    // ------------------------------------------------------------ DOCX / PPTX 首图

    private fun zipFirstImage(file: File, prefix: String): ByteArray? {
        ZipFile(file).use { zip ->
            val entries = zip.entries().asSequence()
                .filter { !it.isDirectory && it.name.startsWith(prefix) && isImageName(it.name) }
                .sortedBy { it.name }
                .toList()
            val entry = entries.firstOrNull() ?: return null
            return zip.getInputStream(entry).use { it.readBytes() }
        }
    }

    // -------------------------------------------------------------------- EPUB

    private fun epubCover(file: File): ByteArray? {
        ZipFile(file).use { zip ->
            // container.xml / OPF 是 XML 文本，必须先解码成字符串再做正则；
            // 封面图本身保持 ByteArray 原样（要按魔数判断类型）。
            val containerXml = zip.entryBytes("META-INF/container.xml")?.toString(Charsets.UTF_8)
            val opfPath = containerXml?.let {
                Regex("full-path\\s*=\\s*\"([^\"]+)\"", RegexOption.IGNORE_CASE)
                    .find(it)?.groupValues?.getOrNull(1)
            }
            val opfDir = opfPath?.substringBeforeLast('/', "") ?: ""
            val opf = opfPath?.let { zip.entryBytes(it) }?.toString(Charsets.UTF_8)

            var coverHref: String? = null
            if (opf != null) {
                val idToHref = HashMap<String, String>()
                Regex("<item\\b[^>]*>", RegexOption.IGNORE_CASE).findAll(opf).forEach { m ->
                    val tag = m.value
                    val id = attr(tag, "id")
                    val href = attr(tag, "href")
                    if (id != null && href != null) idToHref[id] = href
                    if (coverHref == null && attr(tag, "properties")?.contains("cover-image") == true &&
                        href != null
                    ) {
                        coverHref = href
                    }
                }
                if (coverHref == null) {
                    val metaId = Regex(
                        "<meta\\b[^>]*name\\s*=\\s*\"cover\"[^>]*content\\s*=\\s*\"([^\"]+)\"",
                        RegexOption.IGNORE_CASE
                    ).find(opf)?.groupValues?.getOrNull(1)
                        ?: Regex(
                            "<meta\\b[^>]*content\\s*=\\s*\"([^\"]+)\"[^>]*name\\s*=\\s*\"cover\"",
                            RegexOption.IGNORE_CASE
                        ).find(opf)?.groupValues?.getOrNull(1)
                    if (metaId != null) coverHref = idToHref[metaId]
                }
            }

            // 1) 命中的封面 href
            coverHref?.let { href ->
                zip.entryBytes(resolveZipPath(opfDir, href))?.let { return it }
            }
            // 2) 文件名含 cover 的第一张图
            val images = zip.entries().asSequence()
                .filter { !it.isDirectory && isImageName(it.name) }
                .toList()
            images.firstOrNull { it.name.substringAfterLast('/').contains("cover", ignoreCase = true) }
                ?.let { return zip.getInputStream(it).use { s -> s.readBytes() } }
            // 3) 第一张图
            images.firstOrNull()?.let { return zip.getInputStream(it).use { s -> s.readBytes() } }
            return null
        }
    }

    private fun ZipFile.entryBytes(name: String): ByteArray? {
        val e = getEntry(name) ?: getEntry(name.replace(' ', '+')) ?: run {
            // 处理 %20 等百分号编码
            val decoded = runCatching { URLDecoder.decode(name, "UTF-8") }.getOrNull()
            decoded?.let { getEntry(it) }
        } ?: return null
        return getInputStream(e).use { it.readBytes() }
    }

    private fun attr(tag: String, name: String): String? =
        Regex("$name\\s*=\\s*\"([^\"]*)\"", RegexOption.IGNORE_CASE)
            .find(tag)?.groupValues?.getOrNull(1)

    /** 把相对 href 解析为 zip 内路径（处理 `../` 与百分号编码）。 */
    private fun resolveZipPath(baseDir: String, href: String): String {
        val clean = href.substringBefore('#')
        val decoded = runCatching { URLDecoder.decode(clean, "UTF-8") }.getOrDefault(clean)
        val joined = if (baseDir.isEmpty()) decoded else "$baseDir/$decoded"
        val out = ArrayList<String>()
        for (p in joined.split('/')) {
            when (p) {
                "", "." -> {}
                ".." -> if (out.isNotEmpty()) out.removeAt(out.size - 1)
                else -> out.add(p)
            }
        }
        return out.joinToString("/")
    }

    // -------------------------------------------------------------------- MOBI

    /**
     * PalmDB EXTH 取封面（精简版）。
     *
     * 只做一条路径：解析记录表 → 记录 0 的 EXTH → type 201/202 → 图片记录 →
     * 记录内按魔数定位图片起点。任何一步失败返回 null。
     */
    private fun mobiCover(file: File): ByteArray? {
        val bytes = file.readBytes()
        val records = splitRecords(bytes) ?: return null
        val rec0 = records.firstOrNull() ?: return null
        if (rec0.size < MOBI_HDR_START + 4 || !asciiAt(rec0, MOBI_HDR_START, "MOBI")) return null

        val firstImageIndex = u32(rec0, MOBI_HDR_START + 92).toInt()
        val coverOffset = exthCoverOffset(rec0) ?: return null
        val imageIndex = firstImageIndex + coverOffset
        if (imageIndex <= 0 || imageIndex >= records.size) return null
        val rec = records[imageIndex]
        if (rec.isEmpty()) return null

        // 记录里图片起点可能带一小段头；在前 1024 字节内按魔数定位。
        val start = findImageStart(rec)
        val candidate = if (start > 0) rec.copyOfRange(start, rec.size) else rec
        return if (sniffImageExt(candidate) != null) candidate else null
    }

    private fun exthCoverOffset(rec0: ByteArray): Int? {
        val headerLength = u32(rec0, MOBI_HDR_START + 4).toInt()
        val exthStart = MOBI_HDR_START + headerLength
        if (headerLength <= 0 || exthStart + 12 > rec0.size) return null
        if (!asciiAt(rec0, exthStart, "EXTH")) return null
        val exthLength = u32(rec0, exthStart + 4).toInt()
        val count = u32(rec0, exthStart + 8).toInt()
        val end = minOf(rec0.size, exthStart + exthLength)
        var p = exthStart + 12
        var i = 0
        var cover: Int? = null
        var thumb: Int? = null
        while (i < count && p + 8 <= end) {
            val type = u32(rec0, p).toInt()
            val len = u32(rec0, p + 4).toInt()
            if (len < 8 || p + len > end) break
            val dataOff = p + 8
            when (type) {
                201 -> cover = u32(rec0, dataOff).toInt()
                202 -> thumb = u32(rec0, dataOff).toInt()
            }
            p += len
            i++
        }
        return cover ?: thumb
    }

    /** 记录内按魔数找图片起点；找不到返回 0。 */
    private fun findImageStart(rec: ByteArray): Int {
        val limit = minOf(rec.size, 1024)
        var i = 0
        while (i < limit) {
            if (sniffAt(rec, i) != null) return i
            i++
        }
        return 0
    }

    /** PalmDB 分记录（与 [com.treechat.workbench.rag.MobiParser] 同口径的极简版）。 */
    private fun splitRecords(bytes: ByteArray): List<ByteArray>? {
        if (bytes.size < PDB_HEADER_SIZE + RECORD_INFO_SIZE) return null
        var count = u16(bytes, PDB_NUM_RECORDS_OFFSET)
        if (!offsetsLookValid(bytes, count)) {
            val derived = ((u32(bytes, PDB_HEADER_SIZE) - PDB_HEADER_SIZE) / RECORD_INFO_SIZE).toInt()
            if (!offsetsLookValid(bytes, derived)) return null
            count = derived
        }
        val offsets = LongArray(count + 1)
        for (i in 0 until count) offsets[i] = u32(bytes, PDB_HEADER_SIZE + i * RECORD_INFO_SIZE)
        offsets[count] = bytes.size.toLong()
        return (0 until count).map { i ->
            val from = offsets[i].toInt()
            val to = minOf(offsets[i + 1], bytes.size.toLong()).toInt()
            if (to <= from) ByteArray(0) else bytes.copyOfRange(from, to)
        }
    }

    private fun offsetsLookValid(bytes: ByteArray, count: Int): Boolean {
        if (count <= 0 || count > 0xFFFF) return false
        val tableEnd = PDB_HEADER_SIZE.toLong() + RECORD_INFO_SIZE.toLong() * count
        if (tableEnd > bytes.size) return false
        if (u32(bytes, PDB_HEADER_SIZE) != tableEnd) return false
        var prev = tableEnd
        for (i in 0 until count) {
            val off = u32(bytes, PDB_HEADER_SIZE + i * RECORD_INFO_SIZE)
            if (off < prev || off > bytes.size) return false
            prev = off
        }
        return true
    }

    // ------------------------------------------------------------- 魔数嗅探

    /** 返回图片扩展名（jpg/png/gif/bmp/webp）；不认识返回 null。 */
    private fun sniffImageExt(b: ByteArray): String? = sniffAt(b, 0)

    private fun sniffAt(b: ByteArray, off: Int): String? {
        if (off < 0 || off + 12 > b.size) {
            // 允许较短的 bmp（2 字节魔数）
            if (off + 2 <= b.size && b[off] == 0x42.toByte() && b[off + 1] == 0x4D.toByte()) return "bmp"
            return null
        }
        val b0 = b[off].toInt() and 0xFF
        val b1 = b[off + 1].toInt() and 0xFF
        val b2 = b[off + 2].toInt() and 0xFF
        val b3 = b[off + 3].toInt() and 0xFF
        return when {
            b0 == 0xFF && b1 == 0xD8 && b2 == 0xFF -> "jpg"
            b0 == 0x89 && b1 == 0x50 && b2 == 0x4E && b3 == 0x47 -> "png"
            b0 == 0x47 && b1 == 0x49 && b2 == 0x46 && b3 == 0x38 -> "gif"
            b0 == 0x42 && b1 == 0x4D -> "bmp"
            b0 == 0x52 && b1 == 0x49 && b2 == 0x46 && b3 == 0x46 &&
                b[off + 8] == 0x57.toByte() && b[off + 9] == 0x45.toByte() &&
                b[off + 10] == 0x42.toByte() && b[off + 11] == 0x50.toByte() -> "webp"
            else -> null
        }
    }

    private fun isImageName(name: String): Boolean {
        val n = name.lowercase()
        return n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png") ||
            n.endsWith(".gif") || n.endsWith(".bmp") || n.endsWith(".webp")
    }

    // ------------------------------------------------------------- 字节读取（大端）

    private const val PDB_HEADER_SIZE = 78
    private const val RECORD_INFO_SIZE = 8
    private const val PDB_NUM_RECORDS_OFFSET = 76
    private const val MOBI_HDR_START = 16

    private fun u16(b: ByteArray, offset: Int): Int {
        if (offset < 0 || offset + 2 > b.size) return 0
        return ((b[offset].toInt() and 0xFF) shl 8) or (b[offset + 1].toInt() and 0xFF)
    }

    private fun u32(b: ByteArray, offset: Int): Long {
        if (offset < 0 || offset + 4 > b.size) return 0L
        return ((b[offset].toLong() and 0xFF) shl 24) or
            ((b[offset + 1].toLong() and 0xFF) shl 16) or
            ((b[offset + 2].toLong() and 0xFF) shl 8) or
            (b[offset + 3].toLong() and 0xFF)
    }

    private fun asciiAt(b: ByteArray, offset: Int, expected: String): Boolean {
        if (offset < 0 || offset + expected.length > b.size) return false
        for (i in expected.indices) {
            if ((b[offset + i].toInt() and 0xFF) != expected[i].code) return false
        }
        return true
    }
}

/**
 * 封面相关的本机偏好（不落库、不加 Room 列）。
 *
 * 与全局设置共用同一个 SharedPreferences 文件、用**独立的键**（同
 * [LiveSelectionPrefs] 的做法），避免与 AppSettings 的内存缓存打架。
 */
class ReaderCoverPrefs(context: Context) {

    private val prefs = context.getSharedPreferences(AppSettings.PREFS, Context.MODE_PRIVATE)

    /** 不裁剪封面（对应 Koodo 的 isDisableCrop）：开启后用 Fit 显示而非裁切填满。 */
    var disableCrop by mutableStateOf(seedDisableCrop())
        private set

    /** 是否允许渲染 PDF 首页当封面（大 PDF 会慢，默认开启，可关）。 */
    var pdfCoverEnabled by mutableStateOf(seedPdfCover())
        private set

    fun updateDisableCrop(value: Boolean) {
        disableCrop = value
        prefs.edit().putBoolean(KEY_DISABLE_CROP, value).apply()
    }

    fun updatePdfCover(value: Boolean) {
        pdfCoverEnabled = value
        prefs.edit().putBoolean(KEY_PDF_COVER, value).apply()
    }

    private fun seedDisableCrop(): Boolean = prefs.getBoolean(KEY_DISABLE_CROP, false)

    private fun seedPdfCover(): Boolean = prefs.getBoolean(KEY_PDF_COVER, true)

    private companion object {
        const val KEY_DISABLE_CROP = "reader_disable_cover_crop"
        const val KEY_PDF_COVER = "reader_pdf_cover_enabled"
    }
}
