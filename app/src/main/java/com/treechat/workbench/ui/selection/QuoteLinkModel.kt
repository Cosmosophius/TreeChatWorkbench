package com.treechat.workbench.ui.selection

import com.treechat.workbench.data.model.ConversationNode

/**
 * 同文本超链接的目标定义。
 *
 * 当用户曾对某段文本进行过分叉对话（产生过带 [originQuote] 的分支节点），
 * 任何消息中再次出现相同文本时，都会被自动识别并高亮为此目标。
 */
data class QuoteLinkTarget(
    /** 引用的文本内容（去空白后）。 */
    val quote: String,
    /** 对应分支节点的完整信息。 */
    val node: ConversationNode
)

/**
 * 某个富文本块（[MdRichBlock]）内命中的一段同文本超链接区间。
 *
 * @param range 在块内 displayText 维度上的字符下标范围（闭区间，如 5..12）
 * @param target 对应的分支节点目标
 */
data class BlockQuoteLink(
    val range: IntRange,
    val target: QuoteLinkTarget
)

object QuoteLinkMatcher {

    /** 参与同文本自动匹配的最小字符数（防止单字或标点乱匹配）。 */
    const val MIN_QUOTE_LENGTH = 2

    /**
     * 从当前会话的所有节点中，提取出所有有效的引用目标列表。
     *
     * 规则：
     * 1. 过滤掉空白或长度小于 [MIN_QUOTE_LENGTH] 的引用；
     * 2. 排除当前节点自身（自己引用的文本在自己分支内无需跳向自己）；
     * 3. 按文本长度从长到短排序：优先匹配长句子，避免短词贪婪遮蔽长词；
     * 4. 相同引用的若有多条，保留创建时间最新的一条。
     */
    fun extractTargets(
        nodes: List<ConversationNode>,
        currentNodeId: String? = null
    ): List<QuoteLinkTarget> {
        val valid = nodes.filter { node ->
            val q = node.originQuote?.trim().orEmpty()
            q.length >= MIN_QUOTE_LENGTH && node.id != currentNodeId
        }

        // 按 quote 分组去重，相同 quote 取最新创建的节点
        val latestByQuote = valid.groupBy { it.originQuote!!.trim() }
            .mapValues { (_, list) -> list.maxByOrNull { it.createdAt }!! }

        return latestByQuote.values
            .map { QuoteLinkTarget(quote = it.originQuote!!.trim(), node = it) }
            .sortedByDescending { it.quote.length }
    }

    /**
     * 在一段显示文本（displayText）中查找所有命中的超链接区间。
     *
     * 规则：
     * - 区间绝不重叠：已被较长引用占用的字符区间，后续较短引用不得切入；
     * - **空白等价**：匹配前把连续空白压成单个空格，两侧用同一规则归一化；
     * - 返回按文本中出现顺序升序排列的列表。
     */
    fun findLinks(
        text: String,
        targets: List<QuoteLinkTarget>
    ): List<BlockQuoteLink> {
        if (text.isBlank() || targets.isEmpty()) return emptyList()

        // 归一化后的文本 + 回原文的下标映射。
        //
        // 为什么必须归一化：跨块选区在取词时会被拼成**含换行**的一串
        // （见 MessageSelectionController.selectedText：块与块之间补 '\n'），
        // 而匹配是**逐块**做的 —— 块内根本没有换行，于是 indexOf 必然落空，
        // 表现是"跨两段提问，原文里一点标记都没有"。
        // 压成单空格后两侧可比；下标再映射回**原文**，渲染高亮与命中判定
        // 仍然落在原文坐标系里，不会因为归一化而错位。
        val normToOrig = IntArray(text.length + 1)
        val norm = normalizeWhitespace(text, normToOrig)

        // 记录已被占用的字符下标（防止区间重叠）
        val occupied = BooleanArray(text.length)
        val result = mutableListOf<BlockQuoteLink>()

        for (target in targets) {
            val q = normalizeWhitespace(target.quote)
            if (q.isEmpty()) continue
            var startIndex = 0
            while (startIndex < norm.length) {
                val found = norm.indexOf(q, startIndex)
                if (found < 0) break

                // 归一化下标 -> 原文下标（首尾各取一个字符的起点）
                val startOrig = normToOrig[found]
                val endInclusive = normToOrig[found + q.length - 1]

                // 检查该区间是否已被更长引用占用
                var hasConflict = false
                for (i in startOrig..endInclusive) {
                    if (occupied[i]) {
                        hasConflict = true
                        break
                    }
                }

                if (!hasConflict) {
                    // 标记占用
                    for (i in startOrig..endInclusive) {
                        occupied[i] = true
                    }
                    result.add(
                        BlockQuoteLink(
                            range = startOrig..endInclusive,
                            target = target
                        )
                    )
                }

                startIndex = found + 1
            }
        }

        // 按在文本中的出现顺序排列
        return result.sortedBy { it.range.first }
    }

    /**
     * 空白归一化：去掉首尾空白，并把每段连续空白压成**单个空格**。
     *
     * @param toOriginal 非空时写入"归一化下标 -> 原文下标"的映射（长度需 ≥ 结果串长+1）。
     *   文案两侧必须走**同一个函数**，否则规则一旦不同步，
     *   匹配结果会整体错位 —— 而且这类错位在界面上完全看不出来。
     */
    private fun normalizeWhitespace(s: String, toOriginal: IntArray? = null): String {
        val sb = StringBuilder(s.length)
        var n = 0
        var i = 0
        val len = s.length
        // 首部空白直接丢弃
        while (i < len && s[i].isWhitespace()) i++
        while (i < len) {
            val c = s[i]
            if (c.isWhitespace()) {
                // 折叠成单个空格：先记下这段空白的**第一个**字符，
                // 命中区间若以空格开头，回映时必须落到它本人，
                // 落到空格之后那个字符会让整段区间右移一格。
                val runStart = i
                while (i < len && s[i].isWhitespace()) i++
                // 后面已经没有内容时，这段是**尾随**空白，必须丢弃 ——
                // 否则 "  引导文字   内容在这里  " 归一化成带尾随空格的串，
                // 与没有尾随空格的正文永远匹配不上。
                if (i < len) {
                    sb.append(' ')
                    if (toOriginal != null) toOriginal[n] = runStart
                    n++
                }
            } else {
                sb.append(c)
                if (toOriginal != null) toOriginal[n] = i
                n++
                i++
            }
        }
        if (toOriginal != null) toOriginal[n] = len
        return sb.toString()
    }

    /**
     * 检查给定的字符偏移（offset）是否落入某个超链接区间内。
     * 用于用户轻点（Tap）时的命中测试。
     *
     * ## 为什么要有 ±1 个字符的容错
     * `offset` 来自 `TextLayoutResult.getOffsetForPosition()`，它把点击坐标
     * **取整到最近的字符边界**。于是点在链接首字符的左外沿时会得到
     * `range.first - 1`，点在末字符的右外沿时会得到 `range.last + 1` ——
     * 精确判定 `offset in range` 就会漏。
     * 链接的可点区域只有那几个字符，这种"差一个字符"的落点在真机上非常常见，
     * 表现就是用户反复遇到的**"链接明明看得见，点了却没反应，多点两次才行"**。
     *
     * 因此先精确命中，精确不中再允许 ±1 字符的容差，并取**距离最近**的一条，
     * 避免两个相邻链接在边界上互相遮蔽。
     */
    fun findHit(
        offset: Int,
        links: List<BlockQuoteLink>
    ): BlockQuoteLink? {
        links.firstOrNull { offset in it.range }?.let { return it }
        return links
            .mapNotNull { link ->
                val distance = when {
                    offset < link.range.first -> link.range.first - offset
                    offset > link.range.last -> offset - link.range.last
                    else -> 0
                }
                if (distance <= 1) link to distance else null
            }
            .minByOrNull { it.second }
            ?.first
    }
}
