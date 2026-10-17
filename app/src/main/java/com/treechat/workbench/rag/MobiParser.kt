package com.treechat.workbench.rag

import android.util.Log
import com.treechat.workbench.network.search.HtmlSearchResultParser
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * MOBI / AZW3 正文抽取（**纯 Kotlin 自研，零第三方依赖**）。
 *
 * ## 为什么自己解析而不引库
 * `.mobi` 与 `.azw3` 是同一族格式：外层是 Palm 的 **PalmDB** 记录容器，内层是
 * Mobipocket 定义的 **MOBI 头 + PalmDOC 压缩正文**，无 DRM 时全部有公开规范。
 * 现成的 Java 实现（多为 calibre `mobi` 模块的移植）体积大、久未维护，
 * 而本项目只需要"把正文文字抠出来"：自研约 300 行可控、可查、不增加 APK 体积，
 * 与自研 docx / pptx / epub / Markdown 解析的先例一致。
 *
 * ## 覆盖的路径
 * 1. **PalmDB 分记录**：78 字节库头 + 记录偏移表（每项 8 字节：偏移 4 + 属性 1 + UID 3）；
 * 2. **记录 0** = PalmDOC 头（压缩类型 / 文本长度 / 文本记录数 / 加密标志）
 *    + **MOBI 头**（`MOBI` 魔数、文本编码、first-non-book index）+ **EXTH 头**；
 * 3. **正文记录**：`1 .. first-non-book-1` 拼接后按压缩类型还原
 *    （1 = 未压缩直取，2 = PalmDOC LZ77 解压；17480 = HUFF/CDIC 不支持）；
 * 4. **编码**：MOBI 头的文本编码字段（1252 = windows-1252、65001 = UTF-8，
 *    并兼容 936/950/932/949 等 CJK 代码页）→ 严格解码失败再回落 UTF-8 / GBK / ISO-8859-1；
 * 5. **AZW3 / KF8**：合并文件里 MOBI6 正文记录之后是 `BOUNDARY` 记录，其后是第二个
 *    MOBI 头（KF8）与 KF8 正文段。MOBI6 正文取不到（为空或极短）时改从 KF8 段取，
 *    段末以 FLIS / FCIS / RESC / FDST / CRES 等非正文标记记录为界；
 *    只有 KF8 的纯 AZW3（记录 0 本身就是 KF8 头）由路径 3 自动覆盖。
 * 6. **HTML 剥标签**：MOBI / KF8 正文是轻量 HTML，去掉 `<script>/<style>/<head>`，
 *    把 `<p>/<div>/<br>` 等块级标签转成换行，解 HTML 实体，压空白。
 *
 * ## 诚实边界（拿不到就报错，绝不伪造正文）
 * - **DRM**：PalmDOC 加密标志非 0 → 直接抛 [MobiException]，提示先移除 DRM；
 * - **HUFF/CDIC 压缩**（类型 17480，较少见的 MOBI 变体）：不实现解压 → 明确报错；
 * - 非 PalmDB、缺 `MOBI` 魔数、记录表越界：明确报错，不返回乱码；
 * - **不做 KF8 skeleton/fragment 的精确重组**：KF8 段按记录顺序提取，
 *   个别书章节顺序可能与骨架定义略有出入（正文字符本身完整）；
 * - **不抽图片与版式**：插图、表格排版丢失，只取可读文字。
 */
internal object MobiParser {

    private const val TAG = "MobiParser"

    // ---------------------------------------------------------------- PalmDB 常量

    /** PalmDB 库头长度（记录偏移表紧接其后）。 */
    private const val PDB_HEADER_SIZE = 78

    /** 记录偏移表每项长度。结构：偏移 4 字节 + 属性 1 字节 + 唯一 ID 3 字节。 */
    private const val RECORD_INFO_SIZE = 8

    /** 库头里"记录数"字段的偏移（大端 2 字节）。 */
    private const val PDB_NUM_RECORDS_OFFSET = 76

    // ------------------------------------------------------- PalmDOC / MOBI 字段偏移

    /** PalmDOC 头（记录 0 起始 0..15 字节）字段偏移。 */
    private const val PALMDOC_COMPRESSION = 0
    private const val PALMDOC_RECORD_COUNT = 8
    private const val PALMDOC_ENCRYPTION = 12

    /** MOBI 头起始于记录 0 的偏移 16。相对该起点的字段偏移： */
    private const val MOBI_HDR_START = 16

    /** 文本编码（1252 / 65001 …）。 */
    private const val MOBI_HDR_TEXT_ENCODING = MOBI_HDR_START + 12

    /** first-non-book index：正文记录到此为止（其后是非正文记录）。 */
    private const val MOBI_HDR_FIRST_NON_BOOK = MOBI_HDR_START + 64

    // -------------------------------------------------------------------- 压缩类型

    private const val COMPRESSION_NONE = 1
    private const val COMPRESSION_PALMDOC = 2
    private const val COMPRESSION_HUFF_CDIC = 17480

    /** 正文短于此长度即认为"取不到"，转而尝试 KF8 段。 */
    private const val MIN_TEXT_CHARS = 50

    /** 解压输出上限（防损坏文件把内存撑爆）。 */
    private const val MAX_TEXT_BYTES = 64 * 1024 * 1024

    private const val DRM_MESSAGE = "该文件受 DRM 保护，无法解析；请先移除 DRM 再导入。"
    private const val NOT_MOBI_MESSAGE =
        "这不是有效的 MOBI/AZW3 文件（缺少 PalmDB 记录表或 MOBI 头）。文件可能已损坏或扩展名不符。"

    /** 解析过程中可预期、可向用户解释的失败（DRM / 压缩不支持 / 结构异常 / 无正文）。 */
    class MobiException(message: String) : Exception(message)

    /**
     * 从 `.mobi` / `.azw3` / `.azw` 的原始字节里抽出正文纯文本。
     *
     * @throws MobiException DRM、压缩类型不支持、结构损坏、或确实取不到正文时抛出，
     *   `message` 为可直接展示给用户的中文说明。**任何失败都不会返回乱码或空串冒充成功。**
     */
    fun extractText(bytes: ByteArray): String {
        val records = splitRecords(bytes)
        val rec0 = records[0]
        if (rec0.size < MOBI_HDR_START + 4) throw MobiException(NOT_MOBI_MESSAGE)
        if (!asciiAt(rec0, MOBI_HDR_START, "MOBI")) throw MobiException(NOT_MOBI_MESSAGE)

        // --- DRM：PalmDOC 头的加密标志非 0 即受保护（1=旧式 Mobipocket，2=Mobipocket） ---
        val encryption = u16(rec0, PALMDOC_ENCRYPTION)
        if (encryption != 0) {
            Log.w(TAG, "检测到 DRM：加密标志=$encryption")
            throw MobiException(DRM_MESSAGE)
        }

        val compression = u16(rec0, PALMDOC_COMPRESSION)
        val encoding = u32(rec0, MOBI_HDR_TEXT_ENCODING)
        // MOBI 头之后若跟着 EXTH 头，解析用于日志与 DRM 侧写线索（不决定正文边界）。
        parseExth(rec0)

        // --- 路径 A：MOBI6（含纯 KF8 文件，其记录 0 本身就是 KF8 头）正文 ---
        val mobiEnd = resolveTextEnd(rec0, records.size)
        var savedError: MobiException? = null
        val mobi6Text = try {
            // encoding 是 u32（Long），readTextSection 收 Int：编码号恒在 Int 范围内（1252 / 65001 等）。
            readTextSection(records, compression, 1, mobiEnd, encoding.toInt())
        } catch (e: MobiException) {
            // MOBI6 段不可用（如 HUFF/CDIC）：记录下来，改从 KF8 段取。
            savedError = e
            ""
        }

        // --- 路径 B：AZW3/KF8 段（BOUNDARY 记录之后的第二个 MOBI 头 + 正文） ---
        var kf8Text = ""
        if (mobi6Text.length < MIN_TEXT_CHARS) {
            kf8Text = runCatching { readKf8Section(records) }.getOrElse { e ->
                if (e is MobiException) { savedError = savedError ?: e; "" } else throw e
            }
        }

        val best = when {
            mobi6Text.length >= MIN_TEXT_CHARS -> mobi6Text
            kf8Text.length > mobi6Text.length -> kf8Text
            mobi6Text.isNotBlank() -> mobi6Text
            kf8Text.isNotBlank() -> kf8Text
            else -> throw savedError
                ?: MobiException("未能从该 MOBI/AZW3 提取到正文文本（可能为纯图片版式或结构异常）。")
        }
        Log.i(TAG, "MOBI/AZW3 抽取完成：MOBI6=${mobi6Text.length} 字，KF8=${kf8Text.length} 字")
        return best
    }

    // ---------------------------------------------------------------------- 分记录

    /**
     * 按 PalmDB 记录偏移表把文件切成若干记录。
     *
     * 记录数优先取库头偏移 76 的字段；若该值自相矛盾（偏移表对不上），
     * 则依据"第一条记录偏移 = 78 + 8×记录数"这一恒等关系反推记录数，
     * 以兼容个别工具写坏的头部。
     */
    private fun splitRecords(bytes: ByteArray): List<ByteArray> {
        if (bytes.size < PDB_HEADER_SIZE + RECORD_INFO_SIZE) throw MobiException(NOT_MOBI_MESSAGE)

        var count = u16(bytes, PDB_NUM_RECORDS_OFFSET)
        if (!offsetsLookValid(bytes, count)) {
            // 反推：第一条记录偏移紧跟在偏移表之后。
            val derived = ((u32(bytes, PDB_HEADER_SIZE) - PDB_HEADER_SIZE) / RECORD_INFO_SIZE).toInt()
            if (!offsetsLookValid(bytes, derived)) throw MobiException(NOT_MOBI_MESSAGE)
            count = derived
        }

        val offsets = LongArray(count + 1)
        for (i in 0 until count) {
            offsets[i] = u32(bytes, PDB_HEADER_SIZE + i * RECORD_INFO_SIZE)
        }
        offsets[count] = bytes.size.toLong()

        return (0 until count).map { i ->
            val from = offsets[i].toInt()
            val to = minOf(offsets[i + 1], bytes.size.toLong()).toInt()
            if (to <= from) ByteArray(0) else bytes.copyOfRange(from, to)
        }
    }

    /** 校验记录偏移表是否自洽：首条记录紧接表尾，偏移单调不减且不越界。 */
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

    // ------------------------------------------------------------ 正文边界与抽取

    /** MOBI6 正文的结束记录（不含）。优先 MOBI 头的 first-non-book，其次 PalmDOC 的文本记录数。 */
    private fun resolveTextEnd(rec0: ByteArray, recordCount: Int): Int {
        val firstNonBook = u32(rec0, MOBI_HDR_FIRST_NON_BOOK)
        val byHeader = if (firstNonBook in 1L..recordCount.toLong()) firstNonBook.toInt() else -1
        val byPalmDoc = 1 + u16(rec0, PALMDOC_RECORD_COUNT)
        val chosen = if (byHeader > 0) byHeader else byPalmDoc
        return chosen.coerceIn(1, recordCount)
    }

    /**
     * 读取并还原一段正文记录：拼接原始字节 → 解压 → 解码 → 剥 HTML。
     * 空区间返回空串（不报错），交给调用方决定回落。
     */
    private fun readTextSection(
        records: List<ByteArray>,
        compression: Int,
        startIndex: Int,
        endIndexExclusive: Int,
        encodingCode: Int
    ): String {
        if (startIndex < 1) return ""
        val end = minOf(endIndexExclusive, records.size)
        if (end <= startIndex) return ""
        val raw = ByteArrayOutputStream()
        for (i in startIndex until end) raw.write(records[i])
        val decompressed = decompress(raw.toByteArray(), compression)
        if (decompressed.isEmpty()) return ""
        return htmlToText(decode(decompressed, encodingCode))
    }

    // ------------------------------------------------------------------- AZW3 / KF8

    /**
     * 抽取 KF8 段正文。
     *
     * 结构：`BOUNDARY` 记录 → KF8 的 MOBI 头记录 → KF8 正文记录 …
     * 结束位置优先取 KF8 头的 first-non-book（若为文件内绝对下标且合理），
     * 否则扫描 FLIS/FCIS/RESC/FDST 等非正文标记记录，取二者中较小的一个，
     * 保证绝不把非正文记录当正文解压。
     */
    private fun readKf8Section(records: List<ByteArray>): String {
        val boundary = findBoundary(records)
            ?: throw MobiException("未找到 KF8 分界记录（BOUNDARY）。")
        val headerIndex = boundary + 1
        val start = boundary + 2
        if (headerIndex >= records.size || start >= records.size) {
            throw MobiException("AZW3 的 KF8 段不完整（缺少 KF8 头或正文记录）。")
        }
        val header = records[headerIndex]
        if (header.size < MOBI_HDR_START + 4 || !asciiAt(header, MOBI_HDR_START, "MOBI")) {
            throw MobiException("AZW3 的 KF8 段缺少 MOBI 头，无法解析。")
        }

        val kf8Compression = u16(header, PALMDOC_COMPRESSION)
        val kf8Encoding = u32(header, MOBI_HDR_TEXT_ENCODING)
        val kf8FirstNonBook = u32(header, MOBI_HDR_FIRST_NON_BOOK)
        val byHeader = if (kf8FirstNonBook in (start + 1).toLong()..records.size.toLong()) {
            kf8FirstNonBook.toInt()
        } else {
            Int.MAX_VALUE
        }
        val byScan = scanNonTextMarker(records, start)
        val end = minOf(byHeader, byScan).coerceIn(start, records.size)

        return readTextSection(records, kf8Compression, start, end, kf8Encoding.toInt())
    }

    /** 找到内容恰为 `BOUNDARY` 的记录下标。 */
    private fun findBoundary(records: List<ByteArray>): Int? {
        for (i in 1 until records.size) {
            val r = records[i]
            if (r.size in 8..16 && asciiAt(r, 0, "BOUNDARY")) return i
        }
        return null
    }

    /** 从 [from] 起找到第一条非正文标记记录，返回其下标；都没有则返回记录总数。 */
    private fun scanNonTextMarker(records: List<ByteArray>, from: Int): Int {
        for (i in from until records.size) {
            val r = records[i]
            if (r.size >= 4) {
                val magic = String(r, 0, 4, Charsets.US_ASCII)
                if (magic in NON_TEXT_MAGICS) return i
            }
        }
        return records.size
    }

    /** KF8 段正文记录之后常见的非正文记录魔数。 */
    private val NON_TEXT_MAGICS = setOf(
        "FLIS", "FCIS", "FDST", "DATP", "SRCS", "CMET", "RESC", "CRES",
        "FONT", "CONT", "AUDI", "VIDE", "INDX", "TAGX", "IDXT"
    )

    // ----------------------------------------------------------------------- EXTH

    /**
     * 解析记录 0 里的 EXTH 头（若有）并记日志。
     *
     * 仅用于确认头部结构完整性与侧写 DRM 线索：**不**用 EXTH 决定正文边界，
     * 以免不同工具对 0xC0/0xC1 语义（first/last content record）的实现差异影响抽取。
     */
    private fun parseExth(rec0: ByteArray) {
        val headerLength = u32(rec0, MOBI_HDR_START + 4).toInt()
        val exthStart = MOBI_HDR_START + headerLength
        if (headerLength <= 0 || exthStart + 12 > rec0.size) return
        if (!asciiAt(rec0, exthStart, "EXTH")) return
        val exthLength = u32(rec0, exthStart + 4).toInt()
        val recordCount = u32(rec0, exthStart + 8).toInt()
        val end = minOf(rec0.size, exthStart + exthLength)
        var p = exthStart + 12
        var i = 0
        var drmHint = false
        while (i < recordCount && p + 8 <= end) {
            val type = u32(rec0, p).toInt()
            val len = u32(rec0, p + 4).toInt()
            if (len < 8 || p + len > end) break
            if (type == 1 || type == 2 || type == 3) drmHint = true // DRM server / commerce id
            p += len
            i++
        }
        if (drmHint) Log.w(TAG, "EXTH 含 DRM 标识记录，但加密标志为 0，按未加密处理")
    }

    // ------------------------------------------------------------------ PalmDOC 解压

    private fun decompress(raw: ByteArray, compression: Int): ByteArray = when (compression) {
        COMPRESSION_NONE -> raw
        COMPRESSION_PALMDOC -> palmDocDecompress(raw)
        COMPRESSION_HUFF_CDIC -> throw MobiException(
            "该文件的正文使用 HUFF/CDIC 压缩（较少见的 MOBI 变体），暂不支持解压。" +
                "请先用 Calibre 转换为 EPUB 后导入。"
        )
        else -> throw MobiException("不支持的 MOBI 压缩类型（$compression）。请先用 Calibre 转换为 EPUB 后导入。")
    }

    /**
     * PalmDOC LZ77 解压（MOBI 的压缩类型 2）。
     *
     * 逐字节编码：0x00 → 字面 0；0x01..0x08 → 其后 N 个字节原样输出；
     * 0x09..0x7F → 单字节字面；0x80..0xBF → 双字节回引用
     * （距离 = 高 11 位，长度 = 低 3 位 + 3）；0xC0..0xFF → 空格 + (字节 ^ 0x80)。
     * 整个正文按一个连续字节流解压（跨记录的回引用属正常，与 Calibre 行为一致）。
     */
    private fun palmDocDecompress(data: ByteArray): ByteArray {
        var buf = ByteArray(maxOf(64, data.size * 3))
        var len = 0

        fun ensure(extra: Int) {
            if (len + extra <= buf.size) return
            var cap = buf.size * 2
            while (cap < len + extra) cap *= 2
            buf = buf.copyOf(cap)
        }

        fun push(b: Int) {
            ensure(1)
            buf[len++] = b.toByte()
        }

        fun checkCap() {
            if (len > MAX_TEXT_BYTES) {
                throw MobiException("正文解压后异常膨胀（超过 64MB），文件可能已损坏。")
            }
        }

        var i = 0
        while (i < data.size) {
            val c = data[i].toInt() and 0xFF
            i++
            when {
                c == 0 -> push(0)
                c in 1..8 -> {
                    var n = c
                    if (i + n > data.size) n = data.size - i
                    if (n <= 0) break
                    ensure(n)
                    System.arraycopy(data, i, buf, len, n)
                    len += n
                    i += n
                }
                c < 0x80 -> push(c)
                c < 0xC0 -> {
                    if (i >= data.size) break
                    val c2 = data[i].toInt() and 0xFF
                    i++
                    val pair = (c shl 8) or c2
                    val distance = (pair shr 3) and 0x07FF
                    val length = (pair and 0x07) + 3
                    if (distance == 0 || distance > len) continue // 损坏的回引用，跳过
                    ensure(length)
                    var src = len - distance
                    var k = 0
                    while (k < length) {
                        buf[len++] = buf[src++]
                        k++
                    }
                }
                else -> { // 0xC0..0xFF：空格 + (c ^ 0x80)
                    push(0x20)
                    push(c xor 0x80)
                }
            }
            checkCap()
        }
        return buf.copyOf(len)
    }

    // ------------------------------------------------------------------------ 解码

    private fun decode(raw: ByteArray, encodingCode: Int): String {
        charsetFor(encodingCode)?.let { strictDecode(raw, it)?.let { s -> return s } }
        strictDecode(raw, Charsets.UTF_8)?.let { return it }
        runCatching { String(raw, charset("GBK")) }.getOrNull()?.let { return it }
        return String(raw, Charsets.ISO_8859_1)
    }

    /** MOBI 文本编码字段 → 字符集。认不出返回 null，交给通用回落。 */
    private fun charsetFor(code: Int): Charset? = when (code) {
        65001 -> Charsets.UTF_8
        1252 -> charsetOrNull("windows-1252")
        1250, 1251, 1253, 1254, 1255, 1256, 1257, 1258 -> charsetOrNull("windows-$code")
        932 -> charsetOrNull("Shift_JIS")
        936 -> charsetOrNull("GBK")
        949 -> charsetOrNull("EUC-KR")
        950 -> charsetOrNull("Big5")
        else -> null
    }

    private fun charsetOrNull(name: String): Charset? =
        runCatching { Charset.forName(name) }.getOrNull()

    private fun strictDecode(bytes: ByteArray, charset: Charset): String? = try {
        charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: Exception) {
        null
    }

    // -------------------------------------------------------------------- HTML → 文本

    private val REMOVE_BLOCK_RE = Regex(
        "<(script|style|head|title|svg|template|noscript)\\b[^>]*>.*?</\\1\\s*>",
        // 两个 flag 只能走 Set 重载：Kotlin 的 Regex 没有「两个 RegexOption」的
        // 位置参数重载（1 个走单参重载，2 个及以上必须传 Set）。
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
    private val COMMENT_RE = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)
    private val CDATA_RE = Regex("<!\\[CDATA\\[.*?\\]\\]>", RegexOption.DOT_MATCHES_ALL)
    private val PI_RE = Regex("<\\?.*?\\?>", RegexOption.DOT_MATCHES_ALL)
    private val DOCTYPE_RE = Regex("<!DOCTYPE[^>]*>", RegexOption.IGNORE_CASE)
    private val BR_RE = Regex("<br\\s*/?>", RegexOption.IGNORE_CASE)
    private val PAGEBREAK_RE = Regex("<mbp:pagebreak[^>]*/?>", RegexOption.IGNORE_CASE)
    private val BLOCK_RE = Regex(
        "</?(p|div|li|tr|td|th|hr|h[1-6]|blockquote|section|article|table|ul|ol)\\b[^>]*>",
        RegexOption.IGNORE_CASE
    )
    private val TAG_RE = Regex("<[^>]*>")
    private val INLINE_WS_RE = Regex("[ \\t\\u000B\\f\\r]+")

    /**
     * 把 MOBI/KF8 的轻量 HTML 正文转成纯文本：去脚本/样式/头，块级标签转换行，
     * 剥剩余标签，解实体（复用 [HtmlSearchResultParser.decodeEntities]），压空白。
     */
    private fun htmlToText(raw: String): String {
        if (raw.isEmpty()) return ""
        var s = raw
        s = REMOVE_BLOCK_RE.replace(s, " ")
        s = COMMENT_RE.replace(s, " ")
        s = CDATA_RE.replace(s, " ")
        s = PI_RE.replace(s, " ")
        s = DOCTYPE_RE.replace(s, " ")
        s = BR_RE.replace(s, "\n")
        s = PAGEBREAK_RE.replace(s, "\n")
        s = BLOCK_RE.replace(s, "\n")
        s = TAG_RE.replace(s, " ")
        s = HtmlSearchResultParser.decodeEntities(s)

        val sb = StringBuilder(s.length)
        for (line in s.split('\n')) {
            val t = INLINE_WS_RE.replace(line, " ").trim()
            if (t.isNotEmpty()) sb.append(t).append('\n')
        }
        return sb.toString().trim()
    }

    // -------------------------------------------------------------------- 字节读取

    private fun u16(b: ByteArray, offset: Int): Int {
        if (offset < 0 || offset + 2 > b.size) return 0
        return ((b[offset].toInt() and 0xFF) shl 8) or (b[offset + 1].toInt() and 0xFF)
    }

    /** 大端无符号 32 位读成 Long（避免符号位问题）。越界返回 0。 */
    private fun u32(b: ByteArray, offset: Int): Long {
        if (offset < 0 || offset + 4 > b.size) return 0L
        return ((b[offset].toLong() and 0xFF) shl 24) or
            ((b[offset + 1].toLong() and 0xFF) shl 16) or
            ((b[offset + 2].toLong() and 0xFF) shl 8) or
            (b[offset + 3].toLong() and 0xFF)
    }

    /** 判断 [offset] 起是否为给定的 ASCII 串（越界返回 false）。 */
    private fun asciiAt(b: ByteArray, offset: Int, expected: String): Boolean {
        if (offset < 0 || offset + expected.length > b.size) return false
        for (i in expected.indices) {
            if ((b[offset + i].toInt() and 0xFF) != expected[i].code) return false
        }
        return true
    }
}
