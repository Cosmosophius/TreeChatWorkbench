package com.treechat.workbench.rag

import com.treechat.workbench.data.model.KnowledgeChunk
import com.treechat.workbench.data.model.KnowledgeGram
import java.util.UUID

/**
 * 教材切块 + 2 字组索引构建（含滑动窗口重叠）。
 *
 * ## 设计定位
 * 检索召回率由切块粒度、跨段滑动窗口重叠与索引方式共同决定。
 * 本对象保持纯函数实现，便于离线复算与单元回归验证，不依赖设备状态。
 *
 * ## 切块规则（受 OnDevice-RAG-Android WhiteSpaceSplitter 启发）
 * 1. 先按行归一化并切分原始文本；
 * 2. 识别到"章节标题"时，它本身成为独立的一块，并作为后续块的 `section`，
 *    同时重置跨段滑动重叠窗口；
 * 3. 同一章节内相邻块采用约 [CHUNK_OVERLAP_CHARS]（60 字符）的滑动窗口重叠：
 *    - 超出 [MAX_CHUNK_CHARS] 切分长段落时，在句末或子句边界断开，并向前回退约 60 字符作为下一块的前缀重叠；
 *    - 同一章节内跨自然段切分时，携带上一段末尾约 60 字符的语义窗口进入下一段，
 *      确保跨段落定义（如乔姆斯基"深层结构"与"表层结构"对照阐述）不会被生硬截断；
 * 4. 空白归一：连续空白压成一个空格，避免索引里产生冗余空白二元组。
 */
object Chunker {

    /** 单块最大字符数。 */
    const val MAX_CHUNK_CHARS = 320

    /** 相邻切片滑动窗口重叠字符数（约 60 字符）。 */
    const val CHUNK_OVERLAP_CHARS = 60

    /** 单块最小字符数：太短的块（如孤立短句）合并进相邻块。 */
    const val MIN_CHUNK_CHARS = 24

    /**
     * 章节标题识别。
     *
     * 中文教材的标题形态：
     *   第X节 / 第X章 / 第X单元 / 第X课
     *   一、二、三、……、  1. 1.1 1.1.1
     *   ## Markdown 标题
     */
    private val HEADING_PATTERNS = listOf(
        Regex("^第[一二三四五六七八九十百零\\d]{1,4}[章节单元课讲部分]\\s*.*$"),
        Regex("^[一二三四五六七八九十]{1,3}\\s*[、.．]\\s*\\S.*$"),
        Regex("^\\d{1,2}(\\.\\d{1,2}){0,3}\\s*[、.．]?\\s+\\S.*$"),
        Regex("^#{1,6}\\s+\\S.*$")
    )

    /** 标题行的长度上限：超过就多半是正文而不是标题。 */
    private const val HEADING_MAX_CHARS = 40

    fun isHeading(line: String): Boolean {
        val s = line.trim()
        if (s.isEmpty() || s.length > HEADING_MAX_CHARS) return false
        return HEADING_PATTERNS.any { it.matches(s) }
    }

    /**
     * 把纯文本切成带滑动窗口重叠的块。
     *
     * @param documentId 归属文档，用于建立与索引的关联。
     */
    fun chunk(documentId: String, rawText: String): List<KnowledgeChunk> {
        val text = normalizeWhitespace(rawText)
        val lines = text.split('\n')

        val chunks = mutableListOf<KnowledgeChunk>()
        var section: String? = null
        var buffer = StringBuilder()
        var bufferStart = 0
        var order = 0
        var charCursor = 0
        var overlapPrefix = ""

        fun flush(keepOverlapForNext: Boolean) {
            val body = buffer.toString().trim()
            if (body.isNotEmpty() && body != overlapPrefix.trim()) {
                chunks.add(
                    KnowledgeChunk(
                        id = UUID.randomUUID().toString(),
                        documentId = documentId,
                        orderIndex = order++,
                        text = body,
                        charStart = bufferStart,
                        section = section
                    )
                )
                overlapPrefix = if (keepOverlapForNext) {
                    extractOverlapTail(body, CHUNK_OVERLAP_CHARS)
                } else {
                    ""
                }
            } else if (!keepOverlapForNext) {
                overlapPrefix = ""
            }
            buffer = StringBuilder()
        }

        lines.forEach { line ->
            val lineStart = charCursor
            charCursor += line.length + 1

            val trimmed = line.trim()
            if (trimmed.isEmpty()) {
                // 空行作为自然段边界：若当前已积累到一定长度则落块并保留 60 字滑动窗口；
                // 若当前段很短则继续与下一自然段拼接，避免跨段概念定义被拆碎。
                if (buffer.length >= MAX_CHUNK_CHARS / 2) {
                    flush(keepOverlapForNext = true)
                }
                return@forEach
            }

            if (isHeading(trimmed)) {
                flush(keepOverlapForNext = false)
                section = trimmed.removePrefix("#").trim()
                chunks.add(
                    KnowledgeChunk(
                        id = UUID.randomUUID().toString(),
                        documentId = documentId,
                        orderIndex = order++,
                        text = section.orEmpty(),
                        charStart = lineStart,
                        section = section
                    )
                )
                return@forEach
            }

            // 普通正文：若 buffer 为空，先注入上一段末尾的 ~60 字符滑动重叠窗口
            if (buffer.isEmpty()) {
                if (overlapPrefix.isNotEmpty()) {
                    buffer.append(overlapPrefix)
                    bufferStart = (lineStart - overlapPrefix.length).coerceAtLeast(0)
                } else {
                    bufferStart = lineStart
                }
            }

            if (buffer.length + trimmed.length + 1 > MAX_CHUNK_CHARS && buffer.isNotEmpty()) {
                val combined = buffer.toString() + " " + trimmed
                val pieces = splitAtSentence(combined, MAX_CHUNK_CHARS, CHUNK_OVERLAP_CHARS)
                if (pieces.size > 1) {
                    pieces.dropLast(1).forEach { piece ->
                        val body = piece.trim()
                        if (body.isNotEmpty()) {
                            chunks.add(
                                KnowledgeChunk(
                                    id = UUID.randomUUID().toString(),
                                    documentId = documentId,
                                    orderIndex = order++,
                                    text = body,
                                    charStart = bufferStart,
                                    section = section
                                )
                            )
                        }
                    }
                    val tailPiece = pieces.last().trim()
                    buffer = StringBuilder(tailPiece)
                    overlapPrefix = extractOverlapTail(pieces[pieces.size - 2], CHUNK_OVERLAP_CHARS)
                    bufferStart = (lineStart + trimmed.length - tailPiece.length).coerceAtLeast(0)
                    return@forEach
                }
            }

            if (buffer.isNotEmpty()) buffer.append(' ')
            buffer.append(trimmed)
        }
        flush(keepOverlapForNext = false)

        return mergeTooShort(chunks)
    }

    /** 把连续空白归一成单个空格，并把全角空格也当空白。 */
    fun normalizeWhitespace(text: String): String =
        text.replace('\u3000', ' ')
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .replace(Regex("[ \\t\\x0B\\f]+"), " ")
            .replace(Regex("\n{3,}"), "\n\n")

    /**
     * 在不超过 [limit] 的前提下，尽量在句末标点或空白处断开，
     * 并在相邻分片之间保留约 [overlap] 字符的滑动窗口重叠（参考 WhiteSpaceSplitter）。
     */
    internal fun splitAtSentence(
        text: String,
        limit: Int,
        overlap: Int = CHUNK_OVERLAP_CHARS
    ): List<String> {
        val cleaned = text.trim()
        if (cleaned.length <= limit) return listOf(cleaned)
        val out = mutableListOf<String>()
        val safeOverlap = overlap.coerceIn(0, limit / 3)
        var start = 0

        while (start < cleaned.length) {
            val remaining = cleaned.length - start
            if (remaining <= limit) {
                val tail = cleaned.substring(start).trim()
                if (tail.isNotEmpty()) out.add(tail)
                break
            }

            val endLimit = start + limit
            val window = cleaned.substring(start, endLimit)

            // 优先找句末标点，其次找分号/逗号/空格边界
            val sentenceCut = window.indexOfLast {
                it == '。' || it == '！' || it == '？' || it == '；' || it == '.' || it == '\n'
            }
            val clauseCut = if (sentenceCut <= limit / 3) {
                window.indexOfLast { it == '，' || it == '、' || it == ',' || it == ' ' }
            } else {
                -1
            }

            val relativeEnd = when {
                sentenceCut > limit / 3 -> sentenceCut + 1
                clauseCut > limit / 2 -> clauseCut + 1
                else -> limit
            }

            val chunkEnd = start + relativeEnd
            val piece = cleaned.substring(start, chunkEnd).trim()
            if (piece.isNotEmpty()) {
                out.add(piece)
            }

            if (chunkEnd >= cleaned.length) break

            // 计算下一个窗口的起点：向前回退约 overlap（60）字符，并尽量对齐到标点或空格之后
            val rawNextStart = (chunkEnd - safeOverlap).coerceAtLeast(start + 1)
            val alignedNextStart = alignOverlapStart(cleaned, rawNextStart, chunkEnd)
            start = alignedNextStart.coerceAtLeast(start + 1)
        }

        return out
    }

    /**
     * 从块末尾提取约 [overlapChars] 字符作为下一块的滑动窗口前缀。
     */
    internal fun extractOverlapTail(text: String, overlapChars: Int = CHUNK_OVERLAP_CHARS): String {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || overlapChars <= 0) return ""
        if (trimmed.length <= overlapChars) return trimmed
        val rawStart = trimmed.length - overlapChars
        val aligned = alignOverlapStart(trimmed, rawStart, trimmed.length)
        return trimmed.substring(aligned).trim()
    }

    /**
     * 在 [rawStart] 附近寻找最自然的子句或词边界起点，避免把一个完整术语从中间劈开。
     */
    private fun alignOverlapStart(text: String, rawStart: Int, maxEnd: Int): Int {
        val searchLimit = (rawStart + 18).coerceAtMost(maxEnd - 1)
        for (i in rawStart until searchLimit) {
            val ch = text[i]
            if (ch == '。' || ch == '！' || ch == '？' || ch == '；' || ch == '，' ||
                ch == '、' || ch == '.' || ch == ',' || ch == ' '
            ) {
                val candidate = i + 1
                if (candidate < maxEnd) return candidate
            }
        }
        return rawStart
    }

    /** 合并过短的块（标题块不参与合并）。 */
    private fun mergeTooShort(chunks: List<KnowledgeChunk>): List<KnowledgeChunk> {
        if (chunks.size <= 1) return chunks
        val out = mutableListOf<KnowledgeChunk>()
        for (chunk in chunks) {
            val prev = out.lastOrNull()
            val isHeadingChunk = chunk.text == chunk.section
            if (prev != null &&
                !isHeadingChunk &&
                chunk.text.length < MIN_CHUNK_CHARS &&
                prev.text != prev.section &&
                prev.text.length + chunk.text.length <= MAX_CHUNK_CHARS + CHUNK_OVERLAP_CHARS
            ) {
                out[out.size - 1] = prev.copy(text = prev.text + " " + chunk.text)
            } else {
                out.add(chunk)
            }
        }
        // 重新编号，保证 orderIndex 连续（取上下文邻块依赖它连续）
        return out.mapIndexed { index, c -> c.copy(orderIndex = index) }
    }

    // ------------------------------------------------------------------ 索引

    /** 一个 2 字组及其在某块中的出现次数。 */
    data class GramFreq(val gram: String, val freq: Int)

    /**
     * 抽取 2 字组（bigram）。
     *
     * 归一化：小写化 + 去掉所有空白与标点。
     */
    fun bigrams(text: String): List<GramFreq> {
        val cleaned = text.lowercase().filter { !it.isWhitespace() && !isPunctuation(it) }
        if (cleaned.length < 2) return emptyList()
        val counts = HashMap<String, Int>(cleaned.length)
        for (i in 0 until cleaned.length - 1) {
            val gram = cleaned.substring(i, i + 2)
            counts[gram] = (counts[gram] ?: 0) + 1
        }
        return counts.map { GramFreq(it.key, it.value) }
    }

    /** 查询侧只关心"有哪些 2 字组"，不关心次数。 */
    fun queryGrams(query: String): List<String> =
        bigrams(query).map { it.gram }.distinct()

    /**
     * 提取查询中的关键学术术语/词块（按空白与标点切分，长度 >= 2），
     * 用于检索重排阶段执行精确子串匹配加权。
     */
    fun extractKeyTerms(query: String): List<String> {
        val terms = LinkedHashSet<String>()
        val sb = StringBuilder()
        fun flushTerm() {
            val t = sb.toString().trim().lowercase()
            if (t.length >= 2) {
                terms.add(t)
            }
            sb.setLength(0)
        }
        for (ch in query) {
            if (ch.isWhitespace() || isPunctuation(ch)) {
                flushTerm()
            } else {
                sb.append(ch)
            }
        }
        flushTerm()
        return terms.toList()
    }

    /**
     * 常见标点的判定。
     */
    private val PUNCTUATION = (
        "，。！？；：、（）【】《》〈〉「」『』“”‘’—…·～!?,.;:()[]{}<>\"'`~@#$%^&*+=|\\/_-"
        ).toSet()

    internal fun isPunctuation(ch: Char): Boolean = ch in PUNCTUATION

    /**
     * 为一份文档的全部块建立索引行。
     */
    fun buildIndex(documentId: String, chunks: List<KnowledgeChunk>): List<KnowledgeGram> {
        val out = ArrayList<KnowledgeGram>()
        chunks.forEach { chunk ->
            bigrams(chunk.text).forEach { gf ->
                out.add(
                    KnowledgeGram(
                        gram = gf.gram,
                        chunkId = chunk.id,
                        documentId = documentId,
                        freq = gf.freq
                    )
                )
            }
        }
        return out
    }
}
