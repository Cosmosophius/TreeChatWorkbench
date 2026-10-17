package com.treechat.workbench.ui.selection

import com.treechat.workbench.data.model.Highlight
import com.treechat.workbench.ui.markdown.MdRichBlock
import com.treechat.workbench.ui.markdown.parseMarkdownWithOffsets

/**
 * 把一条消息解析成块，供选择层与高亮投影共用。
 *
 * 单独抽出来是为了避免"解析两次"：渲染层需要块来做布局与命中检测，
 * 高亮投影需要同样的块来换算偏移。两处各解析一次不仅浪费，
 * 更危险的是**一旦两边的解析参数不一致（例如 plain 标志给错），
 * 高亮的显示位置就会与文字对不上**。
 *
 * @param plain true 表示用户输入，整条按一个段落处理且不做行内解析
 */
fun blocksForMessage(content: String, plain: Boolean): List<MdRichBlock> =
    if (plain) {
        listOf(
            MdRichBlock(
                kind = MdRichBlock.Kind.PARAGRAPH,
                rawText = content,
                sourceStart = 0,
                sourceEndExclusive = content.length,
                inlineMode = false
            )
        )
    } else {
        parseMarkdownWithOffsets(content)
    }

/**
 * 把持久高亮（**源偏移**）投影成渲染用的**显示区间**。
 *
 * 为什么必须投影而不能直接用：库里存的是 message.content 的字符索引，
 * 而渲染与选择都发生在剥掉行内标记后的显示文本上。
 * 例如 `**粗体**` 在第 0 位开始，但显示串里它从第 0 位开始、长度为 2，
 * 直接拿源索引去 addStyle 就会把高亮画到后面的字上。
 *
 * 跨块的高亮会被拆成每块一段 —— 显示层本来就是块级的，
 * 一个跨块区间没有任何单个 Text 能承载它。
 */
fun projectHighlights(
    blocks: List<MdRichBlock>,
    highlights: List<Highlight>
): List<HighlightRange> {
    if (highlights.isEmpty() || blocks.isEmpty()) return emptyList()
    val out = mutableListOf<HighlightRange>()
    highlights.forEach { hl ->
        val from = minOf(hl.startOffset, hl.endOffset)
        val to = maxOf(hl.startOffset, hl.endOffset)
        if (from >= to) return@forEach
        blocks.forEachIndexed { index, block ->
            if (block.kind == MdRichBlock.Kind.DIVIDER || block.kind == MdRichBlock.Kind.TABLE) {
                return@forEachIndexed
            }
            val blockEnd = block.sourceStart + block.rawText.length
            // 与本块无交集（注意块尾是开区间）
            if (to <= block.sourceStart || from >= blockEnd) return@forEachIndexed
            val localStart = (from - block.sourceStart).coerceIn(0, block.rawText.length)
            val localEnd = (to - block.sourceStart).coerceIn(0, block.rawText.length)
            if (localStart >= localEnd) return@forEachIndexed
            val displayStart = block.toDisplay(block.sourceStart + localStart)
            val displayEnd = block.toDisplay(block.sourceStart + localEnd)
            if (displayStart < displayEnd) {
                out.add(HighlightRange(index, displayStart, displayEnd))
            }
        }
    }
    return out
}

/**
 * 把**源文本坐标**的生词区间投影成可渲染的块级显示区间。
 *
 * 与 [projectHighlights] 的规则完全同构（同样跳过表格/分隔线、同样走
 * `block.toDisplay`），只是入参不同：持久高亮给的是源偏移，
 * 生词匹配给的是"整页原文里第 i 个字符到第 j 个字符"的整词区间。
 *
 * 为什么必须投影而不是直接叠：生词下标是**页原文**坐标系里的，
 * 而每块画的是剥掉行内标记后的显示串 —— 不投影直接加样式，距离哪怕差一个
 * `**`，下划线都会画到隔壁字上。
 */
fun projectSourceRanges(
    blocks: List<MdRichBlock>,
    ranges: List<IntRange>
): List<HighlightRange> {
    if (ranges.isEmpty() || blocks.isEmpty()) return emptyList()
    val out = mutableListOf<HighlightRange>()
    ranges.forEach { r ->
        if (r.first > r.last) return@forEach
        blocks.forEachIndexed { index, block ->
            if (block.kind == MdRichBlock.Kind.DIVIDER || block.kind == MdRichBlock.Kind.TABLE) {
                return@forEachIndexed
            }
            val blockEnd = block.sourceStart + block.rawText.length
            if (r.last < block.sourceStart || r.first >= blockEnd) return@forEachIndexed
            val localStart = (r.first - block.sourceStart).coerceIn(0, block.rawText.length)
            val localEnd = (r.last + 1 - block.sourceStart).coerceIn(0, block.rawText.length)
            if (localStart >= localEnd) return@forEachIndexed
            val displayStart = block.toDisplay(block.sourceStart + localStart)
            val displayEnd = block.toDisplay(block.sourceStart + localEnd)
            if (displayStart < displayEnd) {
                out.add(HighlightRange(index, displayStart, displayEnd))
            }
        }
    }
    return out
}

/** 该消息上是否已有覆盖同一源范围的高亮（工具条据此显示"取消高亮"）。 */
fun List<Highlight>.coversRange(messageId: String, start: Int, end: Int): Boolean =
    any { it.messageId == messageId && it.startOffset == start && it.endOffset == end }

/**
 * 把**关键词**在正文里的每一次出现，算成可渲染的高亮区间。
 *
 * ## 为什么需要它
 * 检索命中的是一条消息里的某几个字，而滚动只能把整条消息带进视口。
 * 一条回答动辄上千字，命中词常在很靠后的位置 —— 只滚动等于没定位，
 * 用户还得自己用眼睛再找一遍。这里把它标出来，视线直接落到命中处。
 *
 * ## 为什么复用 projectHighlights 那套源→显示偏移投影
 * 库里存的是**源偏移**，渲染用的是**显示偏移**（Markdown 标记被剥掉、
 * 表格特殊处理，两者对不上）。自己按字符串下标直接切会算出错误区间。
 * 走与用户手动高亮完全相同的 `toDisplay` 投影，两条路径的坐标永远一致。
 *
 * ## 为什么不落库
 * 这是"从检索跳过来"的一次性定位标记，不是用户手动划的注。
 * 落库会与真实高亮混在一起，而且检索词随时会变。
 * 它由 MessageRow 的 `searchHitKeyword` 驱动、几秒后自动熄灭。
 */
fun projectKeywordHits(
    blocks: List<MdRichBlock>,
    keyword: String?,
    maxHits: Int = 40
): List<HighlightRange> {
    val q = keyword?.trim().orEmpty()
    if (q.isEmpty() || blocks.isEmpty()) return emptyList()

    val out = mutableListOf<HighlightRange>()
    blocks.forEachIndexed { index, block ->
        if (block.kind == MdRichBlock.Kind.DIVIDER || block.kind == MdRichBlock.Kind.TABLE) {
            return@forEachIndexed
        }
        // 在**源文本**里找：rawText 才是带 Markdown 标记的原文。
        // 用 displayText 找得到的是显示偏移，混进源坐标系会错位。
        val raw = block.rawText
        var from = 0
        while (out.size < maxHits) {
            val at = raw.indexOf(q, from, ignoreCase = true)
            if (at < 0) break
            val to = at + q.length
            val displayStart = block.toDisplay(block.sourceStart + at)
            val displayEnd = block.toDisplay(block.sourceStart + to)
            if (displayStart < displayEnd) {
                out.add(HighlightRange(index, displayStart, displayEnd))
            }
            from = at + q.length
        }
    }
    return out
}
