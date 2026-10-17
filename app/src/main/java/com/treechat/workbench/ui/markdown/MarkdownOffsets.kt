package com.treechat.workbench.ui.markdown

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import android.graphics.Typeface
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/**
 * 带偏移映射的 Markdown 块模型。
 *
 * ## 为什么需要这个文件
 *
 * 用户要求"精确到字地自选文本，并针对所选内容提问 / 高亮"。
 * 自研 Markdown 渲染器把一段 Markdown 拆成若干块、每块一个 Text 组件，
 * 于是有两个偏移系并存：
 *
 * - **显示偏移**：用户眼睛看到、手指点到的那串字符里的索引
 *   （`**粗体**` 显示为 `粗体`，共 2 个字符）。
 * - **源偏移**：`message.content` 这个原始 Markdown 字符串里的索引
 *   （同一段占 8 个字符，因为还包含 4 个 `*`）。
 *
 * 高亮必须按**源偏移**存库（见 Highlight 实体的注释：避免悬空引用），
 * 但选择和渲染都发生在**显示偏移**上。因此每个块都要能双向换算，
 * 否则高亮会随着行内标记的多少整体错位。
 *
 * 这里不用"显示文本 + 单独一份 span 列表"的写法，而是**解析时同步记录**
 * 显示索引到源索引的映射表（`displayToSource`），原因：
 * 两者若分开计算，任何一处行内语法规则的改动都可能只改了一边，
 * 产生"高亮范围看起来对、存进去偏几位"这种最难查的错位。
 */

// ============================================================================
// 块模型
// ============================================================================

/**
 * 这些类型必须是 public 而不是 internal：
 * ui.selection.MessageSelectionController 是 public 类，它的公开构造参数
 * （BlockLayoutSnapshot）引用了 MdRichBlock。Kotlin 不允许公开 API 暴露
 * internal 类型（"public function exposes its internal parameter type"），
 * 所以整条被引用的类型链都要放开可见性。
 */
/** 列表块的单个条目：原文 + 它在源串里的起始偏移。 */
data class MdListItem(
    val rawText: String,
    val sourceStart: Int,
    /** 有序列表的序号起始下标（无序列表为 0）。 */
    val orderedIndex: Int = 0
)

/** 表格单元格。 */
data class MdCell(
    val rawText: String,
    val sourceStart: Int
)

/** 表格行。 */
data class MdTableRow(val cells: List<MdCell>)

/**
 * 一个可渲染的块。
 *
 * @param kind 块类型
 * @param spans 行内样式片段（按显示顺序、不重叠）
 * @param displayToSource 显示索引 -> 源索引，长度 = 显示文本长度 + 1
 * @param sourceStart 本块在源串里的起始偏移
 * @param sourceEndExclusive 本块在源串里的结束偏移（不含）
 */
data class MdRichBlock(
    val kind: Kind,
    /**
     * 本块的**原文**（尚未剥掉行内标记）。
     *
     * 这是整套偏移机制的唯一数据源：显示文本与"显示→源"映射表都由
     * 同一份行内解析结果产出，因此二者不可能不一致。
     *
     * 早期实现是在解析阶段就剥掉行内标记、把结果存进 spans，渲染时再单独
     * 做一遍行内解析 —— 两条路径只要有一处规则不同（哪怕只是将来加一种
     * 行内语法忘了同步），存进库的高亮就会整体错位若干字符，
     * 而界面上几乎看不出来。改成单一数据源后，这类错误在结构上被排除。
     */
    val rawText: String,
    /** 本块原文在整条消息源串里的起始偏移。 */
    val sourceStart: Int,
    val sourceEndExclusive: Int,
    val headingLevel: Int = 0,
    val codeLang: String = "",
    val listItems: List<MdListItem> = emptyList(),
    val ordered: Boolean = false,
    val tableHeader: MdTableRow? = null,
    val tableRows: List<MdTableRow> = emptyList(),
    /**
     * 是否按 Markdown 行内语法渲染。
     *
     * false 用于两张情况：代码块（内容必须逐字呈现，`*` 就是乘号）
     * 与用户自己输入的消息（用户随手打的 `*` 不该被当成强调语法）。
     * 关闭时显示文本等于原文，映射退化为恒等映射。
     */
    val inlineMode: Boolean = true
) {
    enum class Kind { HEADING, PARAGRAPH, CODE, QUOTE, LIST, TABLE, DIVIDER }

    /**
     * 行内解析结果（片段 + 显示串）。
     *
     * 缓存：选择命中检测与渲染每帧都会读 displayText，
     * 逐帧重新解析长段落（可达数千字符）是纯浪费。
     */
    private val inline: InlineBuild by lazy(LazyThreadSafetyMode.NONE) {
        if (inlineMode) buildInline(rawText) else buildPlain(rawText)
    }

    /** 整块的显示文本（已剥掉行内标记）。 */
    val displayText: String get() = inline.text

    /** 行内样式片段，供渲染层使用。 */
    val spans: List<MdTextSpan> get() = inline.spans

    /** 显示偏移 -> 源偏移。越界时夹到两端，绝不返回越界值。 */
    fun toSource(displayIndex: Int): Int {
        val map = inline.displayToLocal
        val local = if (map.isEmpty()) 0 else map[displayIndex.coerceIn(0, map.size - 1)]
        return sourceStart + local
    }

    /** 源偏移 -> 显示偏移（取最近的可见位置）。 */
    fun toDisplay(sourceIndex: Int): Int {
        val map = inline.displayToLocal
        if (map.isEmpty()) return 0
        val local = (sourceIndex - sourceStart).coerceAtLeast(0)
        // 映射表在源维度上单调不减，二分即可
        var lo = 0
        var hi = map.size - 1
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (map[mid] < local) lo = mid + 1 else hi = mid
        }
        return lo
    }
}


/** 行内样式片段。 */
data class MdTextSpan(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val strike: Boolean = false,
    val code: Boolean = false,
    val link: Boolean = false,
    /**
     * 概念链接片段（来自 `[[概念名]]` 标记）。
     *
     * 标记本身（双方括号）在解析阶段就被吃掉，displayText 里只剩概念名本身；
     * 命中与否由渲染层用 [knownConcepts] 判定。见 toAnnotated。
     */
    val concept: Boolean = false,
    /** 标记"这段不做行内解释"，仅用于代码等逐字呈现的内容。 */
    val plain: Boolean = false
)

// ============================================================================
// 解析
// ============================================================================

private val RICH_HEADING_RE = Regex("^(#{1,6})\\s+(.*)$")
private val RICH_BULLET_RE = Regex("^[-*+]\\s+(.*)$")
private val RICH_ORDERED_RE = Regex("^(\\d+)[.)]\\s+(.*)$")

/**
 * 把 Markdown 解析成带偏移映射的块列表。
 *
 * 与旧的 parseMarkdown 相比，多出的只有两件事：
 * 1. 记录每块在源串里的精确起止偏移；
 * 2. 为每块生成 displayToSource 映射表。
 * 切块规则本身沿用原逻辑（含流式容错），避免同一份 Markdown
 * 出现"两套解析结果不一致"。
 */
internal fun parseMarkdownWithOffsets(source: String): List<MdRichBlock> {
    val normalized = source.replace("\r\n", "\n").replace("\r", "\n")
    val lines = normalized.split("\n")

    // 每行在源串中的起始偏移。用累加而不是 indexOf，避免重复行取错位置。
    val lineStarts = IntArray(lines.size)
    var cursor = 0
    for (i in lines.indices) {
        lineStarts[i] = cursor
        cursor += lines[i].length + 1 // +1 是换行符
    }

    val blocks = mutableListOf<MdRichBlock>()
    val paraLines = mutableListOf<Pair<String, Int>>() // 行文本（已 trimStart）与源偏移

    fun flushParagraph() {
        if (paraLines.isEmpty()) return
        // 段落内的换行在旧实现里被换成空格，这里保持一致
        val joinedSource = paraLines.joinToString(" ") { it.first }
        val joinedStart = paraLines.first().second
        blocks.add(
            MdRichBlock(
                kind = MdRichBlock.Kind.PARAGRAPH,
                rawText = joinedSource,
                sourceStart = joinedStart,
                sourceEndExclusive = joinedStart + joinedSource.length
            )
        )
        paraLines.clear()
    }

    var i = 0
    while (i < lines.size) {
        val raw = lines[i]
        val trimmed = raw.trimStart()
        val lineStart = lineStarts[i]
        val contentStart = lineStart + (raw.length - trimmed.length)

        // ---- 代码围栏 ----
        if (trimmed.startsWith("```")) {
            flushParagraph()
            val lang = trimmed.removePrefix("```").trim()
            val codeStart = lineStart + raw.length + 1 // 跳过本行与换行符
            val sb = StringBuilder()
            var j = i + 1
            while (j < lines.size && !lines[j].trimStart().startsWith("```")) {
                sb.append(lines[j]).append('\n')
                j++
            }
            val codeText = sb.toString().trimEnd('\n')
            blocks.add(
                MdRichBlock(
                    kind = MdRichBlock.Kind.CODE,
                    rawText = codeText,
                    sourceStart = codeStart,
                    sourceEndExclusive = codeStart + codeText.length,
                    codeLang = lang,
                    // 代码逐字呈现：绝不过行内解析，否则 `*`、`_`、反引号会被吃掉
                    inlineMode = false
                )
            )
            i = if (j < lines.size) j + 1 else j
            continue
        }

        // ---- 分隔线 ----
        if (trimmed == "---" || trimmed == "***" || trimmed == "___") {
            flushParagraph()
            blocks.add(
                MdRichBlock(
                    kind = MdRichBlock.Kind.DIVIDER,
                    rawText = "",
                    sourceStart = contentStart,
                    sourceEndExclusive = contentStart + trimmed.length
                )
            )
            i++
            continue
        }

        // ---- 标题 ----
        val heading = RICH_HEADING_RE.find(trimmed)
        if (heading != null) {
            flushParagraph()
            val hashes = heading.groupValues[1]
            val body = heading.groupValues[2]
            // 标题正文的源起点 = 行内容起点 + 井号数 + 至少一个空格
            val bodyOffsetInTrimmed = trimmed.indexOf(body).coerceAtLeast(hashes.length)
            val bodyStart = contentStart + bodyOffsetInTrimmed
            blocks.add(
                MdRichBlock(
                    kind = MdRichBlock.Kind.HEADING,
                    rawText = body,
                    sourceStart = bodyStart,
                    sourceEndExclusive = lineStart + raw.length,
                    headingLevel = hashes.length
                )
            )
            i++
            continue
        }

        // ---- 引用 ----
        if (trimmed.startsWith(">")) {
            flushParagraph()
            val parts = mutableListOf<Pair<String, Int>>()
            var j = i
            while (j < lines.size && lines[j].trimStart().startsWith(">")) {
                val t = lines[j].trimStart()
                val offsetInTrimmed = t.indexOf('>') + 1
                val afterMarker = t.substring(offsetInTrimmed)
                val text = afterMarker.trimStart()
                val start = lineStarts[j] + (lines[j].length - t.length) + offsetInTrimmed +
                    (afterMarker.length - text.length)
                parts.add(text to start)
                j++
            }
            blocks.add(
                MdRichBlock(
                    kind = MdRichBlock.Kind.QUOTE,
                    // 引用块的原文按换行拼接：显示时换行要保留（旧实现如此），
                    // 逐行的行内解析交给渲染层
                    rawText = parts.joinToString("\n") { it.first },
                    sourceStart = parts.first().second,
                    sourceEndExclusive = parts.last().second + parts.last().first.length
                )
            )
            i = j
            continue
        }

        // ---- 表格 ----
        if (trimmed.startsWith("|") && i + 1 < lines.size && isTableSeparatorLine(lines[i + 1].trim())) {
            flushParagraph()
            val header = buildTableRow(raw, i, lineStarts)
            i += 2
            val rows = mutableListOf<MdTableRow>()
            while (i < lines.size && lines[i].trimStart().startsWith("|")) {
                rows.add(buildTableRow(lines[i], i, lineStarts))
                i++
            }
            val allCells = (listOf(header) + rows).flatMap { it.cells }
            blocks.add(
                MdRichBlock(
                    kind = MdRichBlock.Kind.TABLE,
                    // 单元格文本**按行内规则剥掉标记**后再拼（见 tableCellDisplayText
                    // 的说明）：这份扁平文本是"显示偏移"的统一参照，各格在其中的起点
                    // 由 tableCellTextStarts 按同一规则（单空格分隔）算回，
                    // 两边必须逐字一致，否则表格里的选区会整体错位到别的格。
                    //
                    // ⚠️ inlineMode 仍是 false：这是一份**已剥好的**显示串，
                    // 再过一次行内解析会二次剥离（`-`、`_` 之类会被再吃一次）。
                    rawText = allCells.joinToString(" ") { tableCellDisplayText(it.rawText) },
                    sourceStart = header.cells.firstOrNull()?.sourceStart ?: contentStart,
                    sourceEndExclusive = allCells.lastOrNull()
                        ?.let { it.sourceStart + it.rawText.length } ?: contentStart,
                    tableHeader = header,
                    tableRows = rows,
                    inlineMode = false
                )
            )
            continue
        }

        // ---- 列表 ----
        val bullet = RICH_BULLET_RE.find(trimmed)
        val ordered = RICH_ORDERED_RE.find(trimmed)
        if (bullet != null || ordered != null) {
            flushParagraph()
            val isOrdered = ordered != null && bullet == null
            val items = mutableListOf<MdListItem>()
            var j = i
            var orderIndex = 0
            while (j < lines.size) {
                val rawLine = lines[j]
                val t = rawLine.trimStart()
                val b = RICH_BULLET_RE.find(t)
                val o = RICH_ORDERED_RE.find(t)
                when {
                    isOrdered && o != null -> {
                        val text = o.groupValues[2]
                        val off = (rawLine.length - t.length) + t.indexOf(text).coerceAtLeast(0)
                        items.add(MdListItem(text, lineStarts[j] + off, orderIndex++))
                        j++
                    }
                    !isOrdered && b != null -> {
                        val text = b.groupValues[1]
                        val off = (rawLine.length - t.length) + t.indexOf(text).coerceAtLeast(0)
                        items.add(MdListItem(text, lineStarts[j] + off, 0))
                        j++
                    }
                    // 续行：旧实现把续行并进上一条
                    lines[j].startsWith("  ") && t.isNotEmpty() && items.isNotEmpty() -> {
                        val last = items.removeAt(items.size - 1)
                        items.add(last.copy(rawText = last.rawText + " " + t))
                        j++
                    }
                    else -> break
                }
            }
            blocks.add(
                MdRichBlock(
                    kind = MdRichBlock.Kind.LIST,
                    // 列表原文按换行拼接，条目间的换行由渲染层负责
                    rawText = items.joinToString("\n") { it.rawText },
                    sourceStart = items.firstOrNull()?.sourceStart ?: contentStart,
                    sourceEndExclusive = items.lastOrNull()
                        ?.let { it.sourceStart + it.rawText.length } ?: contentStart,
                    listItems = items,
                    ordered = isOrdered
                )
            )
            i = j
            continue
        }

        // ---- 空行 / 普通段落行 ----
        if (trimmed.isBlank()) {
            flushParagraph()
            i++
            continue
        }

        paraLines.add(trimmed to contentStart)
        i++
    }
    flushParagraph()
    return blocks
}

private fun buildTableRow(
    rawLine: String,
    lineIndex: Int,
    lineStarts: IntArray
): MdTableRow {
    val body = rawLine.trim().removePrefix("|").removeSuffix("|")
    val cells = mutableListOf<MdCell>()
    var searchFrom = rawLine.indexOf(body).coerceAtLeast(0)
    body.split("|").forEach { cell ->
        val display = cell.trim()
        val idx = rawLine.indexOf(display, searchFrom).let { if (it < 0) searchFrom else it }
        cells.add(MdCell(display, lineStarts[lineIndex] + idx))
        searchFrom = idx + display.length
    }
    return MdTableRow(cells)
}

private fun isTableSeparatorLine(line: String): Boolean {
    if (!line.contains('-')) return false
    val body = line.trim().removePrefix("|").removeSuffix("|")
    val cells = body.split("|").map { it.trim() }
    if (cells.isEmpty()) return false
    return cells.all { it.matches(Regex("^:?-{2,}:?$")) }
}

// ============================================================================
// 行内解析（显示文本 + 样式 + 映射，一次遍历产出）
// ============================================================================

/**
 * 一段行内解析结果。
 *
 * 不变式（整条选择/高亮链路都建立在这条上）：
 * `displayToLocal.size == text.length + 1`。
 * 末位那个多余元素表示"显示串末尾"对应的本地源偏移，
 * 供选区右端点落在文末时取值。任何新增片段的路径都必须走 appendSpan，
 * 否则映射表会短于显示文本，选区换算整体偏移。
 *
 * 映射记的是**相对本段起点的本地偏移**，由调用方加上绝对起点换算成源偏移；
 * 这样同一段文本可以被放在不同位置复用（引用块逐行解析就是这种场景）。
 */
private class InlineBuild(
    val spans: List<MdTextSpan>,
    val displayToLocal: IntArray
) {
    val text: String by lazy(LazyThreadSafetyMode.NONE) {
        buildString { spans.forEach { append(it.text) } }
    }
}

/**
 * 不做行内解析的构建：显示文本等于原文，映射为恒等映射。
 *
 * 用于代码块与用户消息：代码里的 `*`、`_`、反引号就是代码本身，
 * 过一遍行内解析会把它们当标记吃掉，**内容会被改坏**。
 */
private fun buildPlain(text: String): InlineBuild {
    if (text.isEmpty()) return InlineBuild(emptyList(), intArrayOf(0))
    return InlineBuild(
        spans = listOf(MdTextSpan(text, plain = true)),
        displayToLocal = IntArray(text.length + 1) { it }
    )
}


/**
 * 解析一段行内 Markdown。
 *
 * 关键约束：**样式片段与映射表必须在同一次遍历里产出**。
 * 若分成"先剥标记得到显示文本"与"再算映射"两步，
 * 两边的规则一旦不同步（例如将来加一种行内语法只改了一边），
 * 就会出现选择范围看起来正确、存进库却整体偏移若干字符的问题，
 * 而这类错位在界面上几乎看不出来。
 *
 * 递归深度受输入控制，但行内嵌套实际只有一层，不会退化。
 */
private fun buildInline(text: String): InlineBuild {
    if (text.isEmpty()) return InlineBuild(emptyList(), intArrayOf(0))

    val spans = mutableListOf<MdTextSpan>()
    val mapping = mutableListOf<Int>()

    /** 追加片段并**同步**写入本地映射。任何新增片段的路径都必须走这里。 */
    fun appendSpan(spanText: String, spanLocalStart: Int, style: MdTextSpan) {
        if (spanText.isEmpty()) return
        spans.add(style.copy(text = spanText))
        for (k in spanText.indices) mapping.add(spanLocalStart + k)
    }

    val plain = StringBuilder()
    var plainLocalStart = 0

    fun flushPlain() {
        if (plain.isNotEmpty()) {
            appendSpan(plain.toString(), plainLocalStart, MdTextSpan(""))
            plain.clear()
        }
    }

    var i = 0
    while (i < text.length) {
        val c = text[i]

        // 反斜杠转义：只输出被转义的那个字符，映射指向它本身
        if (c == '\\' && i + 1 < text.length) {
            if (plain.isEmpty()) plainLocalStart = i + 1
            plain.append(text[i + 1])
            i += 2
            continue
        }

        // 行内代码
        if (c == '`') {
            val end = text.indexOf('`', i + 1)
            if (end > i + 1) {
                flushPlain()
                appendSpan(text.substring(i + 1, end), i + 1, MdTextSpan("", code = true))
                i = end + 1
                continue
            }
        }

        // 粗体
        if (text.startsWith("**", i) || text.startsWith("__", i)) {
            val marker = text.substring(i, i + 2)
            val end = text.indexOf(marker, i + 2)
            if (end > i + 2) {
                flushPlain()
                val inner = subInline(text.substring(i + 2, end), i + 2, bold = true)
                spans.addAll(inner.spans)
                inner.displayToLocal.dropLast(1).forEach { mapping.add(it) }
                i = end + 2
                continue
            }
        }

        // 删除线
        if (text.startsWith("~~", i)) {
            val end = text.indexOf("~~", i + 2)
            if (end > i + 2) {
                flushPlain()
                val inner = subInline(text.substring(i + 2, end), i + 2, strike = true)
                spans.addAll(inner.spans)
                inner.displayToLocal.dropLast(1).forEach { mapping.add(it) }
                i = end + 2
                continue
            }
        }

        // 斜体
        if (c == '*' || c == '_') {
            val end = text.indexOf(c, i + 1)
            if (end > i + 1) {
                flushPlain()
                val inner = subInline(text.substring(i + 1, end), i + 1, italic = true)
                spans.addAll(inner.spans)
                inner.displayToLocal.dropLast(1).forEach { mapping.add(it) }
                i = end + 1
                continue
            }
        }

        // 概念链接：[[概念名]] —— 只把内层的概念名放进显示串，双方括号**吃掉**。
        // 必须排在下面的 [文字](地址) 之前：否则 `[[x]]` 会被那个分支按
        // "从第一个 [ 到某个 ]" 误判。这里同样走 appendSpan，
        // 因此 displayToSource 映射天然把"显示里的概念名"对回"源里的概念名"
        // （而不是对到方括号上），选择与高亮的偏移不会错位。
        if (text.startsWith("[[", i)) {
            val close = text.indexOf("]]", i + 2)
            if (close > i + 2) {
                flushPlain()
                appendSpan(text.substring(i + 2, close), i + 2, MdTextSpan("", concept = true))
                i = close + 2
                continue
            }
        }

        // 链接：[文字](地址) —— 只显示文字
        if (c == '[') {
            val close = text.indexOf(']', i + 1)
            if (close > i && close + 1 < text.length && text[close + 1] == '(') {
                val parenClose = text.indexOf(')', close + 2)
                if (parenClose > close) {
                    flushPlain()
                    appendSpan(text.substring(i + 1, close), i + 1, MdTextSpan("", link = true))
                    i = parenClose + 1
                    continue
                }
            }
        }

        if (plain.isEmpty()) plainLocalStart = i
        plain.append(c)
        i++
    }
    flushPlain()

    // 末位哨兵：显示串末尾对应的本地源偏移
    mapping.add(text.length)
    return InlineBuild(spans, mapping.toIntArray())
}
/**
 * 表格单元格的显示文本：把**行内标记按正文同一套规则**剥掉。
 *
 * ## 为什么必须与正文共用 [buildInline]
 *
 * 表格参与字符级选词（逐格登记几何，见 CellLayoutSnapshot）。几何里的
 * `textStart`/`textLength` 是按"各格显示文本在块显示串里的位置"算出来的，
 * 渲染画的是同一份文本。两者若各用一套剥离规则，只要有一处不同步
 * （例如多认一种标记），选中范围就会整体错位 ——
 * 表现是"从第二列起选出来的不是你点到的字"，界面上几乎看不出来。
 *
 * 所以这里**不另写扫描**，直接复用正文解析：成对才剥、单侧原样保留，
 * `2 * 3 = 6` 的裸星号不受影响，`**粗体**` / `` `代码` `` /
 * `~~删除~~` / `*斜体*` / `[文字](地址)` 与正文完全一致。
 * 用户看到的"表格里一堆星号"，就是因为此前表格路径压根没走这套规则。
 *
 * 渲染侧（TableGridCell）拿的是同一份解析产出的 spans 与文本，
 * 因此"显示什么"与"选中算到哪"永远同源。
 */
internal fun tableCellDisplayText(raw: String): String =
    if (raw.isEmpty()) raw else buildInline(raw).text

/**
 * 表格单元格按**正文同一套规则**解析出的行内片段。
 *
 * 与 [tableCellDisplayText] 必须来自同一次 [buildInline] 的两个返回值，
 * 否则"拼出来的显示文本"与"逐段套的样式"会对不上（少字/重字），
 * 表现为粗体底色或选区底衬错位半格。这是"同一份解析、两种用途"，
 * 不要拆成两次调用。
 *
 * 注意：入参应当是**已经剥过标记**的显示文本还是原文，取决于调用方 ——
 * 渲染层直接传单元格原文即可，返回片段拼起来正好等于
 * [tableCellDisplayText] 的结果。
 */
internal fun parseTableCellSpans(raw: String): List<MdTextSpan> =
    if (raw.isEmpty()) emptyList() else buildInline(raw).spans


/** 带外层样式的行内片段（粗体/斜体内部允许再嵌代码等）。 */
private fun subInline(
    text: String,
    localStart: Int,
    bold: Boolean = false,
    italic: Boolean = false,
    strike: Boolean = false
): InlineBuild {
    val inner = buildInline(text)
    val styled = inner.spans.map {
        it.copy(
            bold = it.bold || bold,
            italic = it.italic || italic,
            strike = it.strike || strike
        )
    }
    // 内层映射是相对子串的，整体平移 localStart 后即为相对本段的本地偏移
    return InlineBuild(styled, IntArray(inner.displayToLocal.size) { inner.displayToLocal[it] + localStart })
}

// ============================================================================
// 渲染成 AnnotatedString
// ============================================================================

/**
 * 把块的行内片段渲染成 AnnotatedString。
 *
 * 可见性是 public 而非 internal：ui.selection 包（选择渲染层）要调用它。
 * Kotlin 的 internal 是**模块内**可见，不是"包内"可见 ——
 * 跨包调用 internal 函数会直接编译失败。
 *
 * @param highlightRanges 持久高亮区间（显示维度，左闭右开转成 IntRange）
 * @param selectionRange 当前正在选择的范围
 */
fun MdRichBlock.toAnnotated(
    colors: MdColors,
    baseFontSize: TextUnit,
    color: Color = colors.text,
    highlightRanges: List<IntRange> = emptyList(),
    highlightColor: Color = Color.Unspecified,
    selectionRange: IntRange? = null,
    selectionColor: Color = Color.Unspecified,
    /** 本块内命中的同文本超链接区间列表。 */
    quoteLinkRanges: List<IntRange> = emptyList(),
    quoteLinkColor: Color = Color.Unspecified,
    /**
     * 命中本地词库的概念名集合（大小写敏感，与 `[[概念名]]` 里写的完全一致）。
     *
     * 概念链接据此分成两档：
     * - **命中**：实线超链接（下划线 + 超链接色），点击走词典/词源；
     * - **未命中**：弱化的"AI 链接"（斜体 + 淡底衬 + 同色，不加实线下划线），
     *   点击触发一次轻量追问。
     *
     * 为什么未命中不用"虚线下划线"：Compose 的 `TextDecoration` 是**掩码类**
     * （仅 None/Underline/LineThrough 三种位），无法表达虚线；真正的虚线需要
     * 覆盖文本渲染层自绘 Path，代价与回归风险都远超本改进的收益。
     * 这里改用"实线 vs 斜体淡底"这一对在视觉上明确可辨的写法。
     */
    @Suppress("UNUSED_PARAMETER")
    knownConcepts: Set<String> = emptySet(),
    /**
     * 粗体的颜色呈现（见 [com.treechat.workbench.data.prefs.BoldColorStyle]）。
     *
     * 默认 [BoldColorStyle.DEFAULT] = 只提字重、不改颜色，与改动前逐字一致。
     */
    boldColorStyle: com.treechat.workbench.data.prefs.BoldColorStyle =
        com.treechat.workbench.data.prefs.BoldColorStyle.DEFAULT,
    /** 粗体的高亮底色（[NONE] 表示不铺）。 */
    boldHighlightColor: com.treechat.workbench.data.prefs.BoldHighlightColor =
        com.treechat.workbench.data.prefs.BoldHighlightColor.NONE,
    /** 粗体的字形（楷体/仿宋/黑体等）。 */
    boldFontStyle: com.treechat.workbench.data.prefs.BoldFontStyle =
        com.treechat.workbench.data.prefs.BoldFontStyle.DEFAULT,
    /** 主题强调色，[boldColorStyle] 为 ACCENT/HIGHLIGHT 时需要它来取值。 */
    accentColor: Color = Color.Unspecified,
    /**
     * 生词匹配区间（**显示维度**，块内局部下标）与颜色。
     *
     * 由阅读器把"考纲命中词"投影到块级后传进来（见
     * HighlightProjection.projectSourceRanges）。渲染成"该色文字 + 下划线 +
     * 淡底"，与持久高亮（整块底色）区分开。
     */
    vocabRanges: List<IntRange> = emptyList(),
    vocabColor: Color = Color.Unspecified
): AnnotatedString {
    return buildAnnotatedString {
        var cursor = 0
        spans.forEach { span ->
            val start = cursor
            val end = cursor + span.text.length
            // ⚠️ 概念标记（曾经的 `[[概念名]]` 超链接）**已整体停用**：
            // 用户明确不需要"下划线 + 点击弹词源"这套。这里把概念片段按**正文**
            // 渲染：不加颜色、不加斜体、不加下划线、不加淡底。
            // 模型若仍输出双方括号，解析阶段已经把括号剥掉（见 parseInline 里
            // appendSpan(text.substring(i + 2, close), …)），因此用户只会看到
            // 干净的名词，看不到 `[[ ]]` 也不会误以为可点。
            // 词典/词源功能本身没有被删：它在「词」标签页与阅读器工具栏里照旧可用。
            val style = SpanStyle(
                // plain 片段（代码、纯文本模式）一律不加行内样式：
                // 它们的内容是逐字呈现的，加粗/变色会改变语义。
                // ---- 粗体：字重 + 颜色 + 底色 + 字形，四个维度独立可控 ----
                //
                // 为什么必须让颜色/底色/字形可配：中文里 `FontWeight.Bold` 的
                // 实际差异很小（多数中文字体没有真 Bold 字重，合成加粗很弱），
                // 模型标粗的重点在长段落里经常等于没标。用户需要能自己把它做强。
                //
                // 优先级：**链接色 > 粗体色**。链接与粗体可能落在同一段文字上，
                // 此时若让粗体色盖过链接色，"这里能点"的提示就没了 ——
                // 而可点性比强调更重要（选了就能跳到对应分支）。
                fontWeight = if (!span.plain && span.bold) FontWeight.Bold else null,
                color = when {
                    span.plain -> color
                    span.link -> colors.link
                    span.bold -> when (boldColorStyle) {
                        com.treechat.workbench.data.prefs.BoldColorStyle.ACCENT ->
                            if (accentColor != Color.Unspecified) accentColor else colors.accent
                        com.treechat.workbench.data.prefs.BoldColorStyle.WARM -> Color(0xFFD84315)
                        // HIGHLIGHT 档：颜色留给底色，文字保持本色（否则底色与文字都变重，反而看不清）
                        com.treechat.workbench.data.prefs.BoldColorStyle.HIGHLIGHT -> color
                        com.treechat.workbench.data.prefs.BoldColorStyle.DEFAULT -> color
                    }
                    else -> color
                },
                fontFamily = when {
                    span.code -> FontFamily.Monospace
                    // 只给**系统保证存在**的字族：SansSerif（黑体系）与 Serif（宋体系）。
                    // 楷体/仿宋/圆体在原生 Android 上并不预装（只有 Noto CJK），
                    // 而 `FontFamily("KaiTi")` 这种按名字加载的构造器在 Compose 里
                    // 是 internal，应用侧根本调不到 —— 实测编译直接失败。
                    // 所以这里不做"看着有、实际加载不出来"的选项。
                    // 黑体/宋体直接用系统保证存在的字族；楷体/仿宋按名字向系统要 ——
                    // Android 不预装这两类中文字族（只有 Noto CJK）。
                    //
                    // 为什么不用 Compose 的按名构造：它只接受资源 id / 文件路径 / Typeface，
                    // 直接传字体名会编译失败（实测）。Typeface 是平台 API，
                    // 找不到字族时静默回退默认字体，不会崩。
                    span.bold -> when (boldFontStyle) {
                        com.treechat.workbench.data.prefs.BoldFontStyle.HEI -> FontFamily.SansSerif
                        com.treechat.workbench.data.prefs.BoldFontStyle.SERIF -> FontFamily.Serif
                        com.treechat.workbench.data.prefs.BoldFontStyle.KAI ->
                            systemFontFamily("KaiTi", "楷体", "STKaiti")
                        com.treechat.workbench.data.prefs.BoldFontStyle.FANGSONG ->
                            systemFontFamily("FangSong", "仿宋", "STFangsong")
                        com.treechat.workbench.data.prefs.BoldFontStyle.DEFAULT -> null
                    }
                    else -> null
                },
                background = when {
                    span.code -> colors.inlineCodeBg
                    span.bold && boldHighlightColor.argb != 0L -> Color(boldHighlightColor.argb)
                    else -> Color.Unspecified
                },
                fontStyle = if (!span.plain && span.italic) FontStyle.Italic else null,
                textDecoration = when {
                    span.plain -> null
                    span.strike -> TextDecoration.LineThrough
                    span.link -> TextDecoration.Underline
                    else -> null
                },
                fontSize = if (span.code) baseFontSize * 0.94f else TextUnit.Unspecified
            )
            withStyle(style) { append(span.text) }

            // ---- 同文本超链接（点开 → 该分支的小窗）----
            //
            // 这是聊天正文里**唯一**的"可点开内容"标记，所以样式必须自成一格、
            // 且与 Markdown 的 URL 链接（通常是蓝色）明确区分：
            //   · 色相不同：用 tertiary，不是 URL 的 link 蓝；
            //   · 实线下划线 + 略强底衬：一眼看出"这段文字有对应的分支"。
            // 文档里不再存在第二种行内标记（概念链接已停用），因此不会再有
            // "看起来一样、点下去却是别的东西"的混淆。
            if (quoteLinkRanges.isNotEmpty()) {
                val linkClr = if (quoteLinkColor != Color.Unspecified) quoteLinkColor else colors.quoteLink
                quoteLinkRanges.forEach { range ->
                    val s = maxOf(start, range.first)
                    val e = minOf(end, range.last + 1)
                    if (s < e) {
                        addStyle(
                            SpanStyle(
                                color = linkClr,
                                textDecoration = TextDecoration.Underline,
                                background = linkClr.copy(alpha = 0.12f)
                            ),
                            s,
                            e
                        )
                    }
                }
            }

            // 选择态背景
            if (selectionRange != null && selectionColor != Color.Unspecified) {
                val s = maxOf(start, selectionRange.first)
                val e = minOf(end, selectionRange.last + 1)
                if (s < e) addStyle(
                    SpanStyle(background = selectionColor),
                    s,
                    e
                )
            }
            // 持久高亮背景（后加，覆盖选择态）
            if (highlightColor != Color.Unspecified) {
                highlightRanges.forEach { range ->
                    val s = maxOf(start, range.first)
                    val e = minOf(end, range.last + 1)
                    if (s < e) addStyle(SpanStyle(background = highlightColor), s, e)
                }
            }
            // 生词匹配：该色文字 + 下划线 + 淡底（压最上，读词优先级最高）
            if (vocabColor != Color.Unspecified) {
                vocabRanges.forEach { range ->
                    val s = maxOf(start, range.first)
                    val e = minOf(end, range.last + 1)
                    if (s < e) addStyle(
                        SpanStyle(
                            color = vocabColor,
                            textDecoration = TextDecoration.Underline,
                            background = vocabColor.copy(alpha = 0.10f)
                        ),
                        s,
                        e
                    )
                }
            }
            cursor = end
        }
    }
}

/** 行内片段拼接成纯文本时的字号换算辅助（供列表序号等使用）。 */
internal fun TextUnit.scaled(factor: Float): TextUnit = (this.value * factor).sp

/**
 * 按名字向系统要一个中文字族（楷体 / 仿宋这类 Android 不预装的字）。
 *
 * 依次尝试若干别名（各厂商 ROM 命名不同），拿不到就回退默认字体。
 * [Typeface.create] 对不存在的字族**不抛异常**，只返回默认字体，
 * 所以这里不需要 try/catch —— 这也是本方案不会让用户崩溃的原因。
 *
 * 按名字缓存：[SpanStyle] 每次重组都会重建，不缓存等于每帧做一次字体查找。
 */
private val systemFontCache = mutableMapOf<String, FontFamily>()

private fun systemFontFamily(vararg names: String): FontFamily {
    for (name in names) {
        systemFontCache[name]?.let { return it }
        // ⚠️ 不去校验"这个字族是不是真的存在"：`Typeface.create` 找不到时会静默
        // 返回默认字体，而不是抛异常，所以**直接用**即可 —— 判断逻辑本身
        // 在各 ROM 上行为不一致（familyName 在部分 SDK 上不可用），
        // 与其写一个可能误判的校验，不如把"有没有装楷体"的判断交给系统：
        // 装了就是楷体，没装就静默回退，用户看到的仍是正常粗体，不会崩。
        val tf = Typeface.create(name, Typeface.NORMAL)
        val fam = FontFamily(tf)
        systemFontCache[name] = fam
        return fam
    }
    return FontFamily.Default
}
