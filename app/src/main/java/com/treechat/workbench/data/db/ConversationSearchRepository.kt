package com.treechat.workbench.data.db

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 跨会话检索（标题 + 消息正文）。
 *
 * ## 范围与类型都可切换
 * - **范围**：全部会话 / 指定单个会话（用户口中的"某一个根对话"）。
 *   传入 [SearchScope.AllConversations] 即为全部。
 * - **类型**：只搜标题 / 只搜消息 / 都搜。
 *   "都搜"时**标题结果排在前面** —— 标题是用户自己写的、表达的是"这条分支是关于什么"，
 *   命中标题几乎总是比命中正文更接近用户意图。
 *
 * 纯只读：只调用 Dao 的 SELECT，不写库、不建表、不加索引。
 */
class ConversationSearchRepository(private val dao: ConversationDao) {

    /** 单类结果最多取多少条，避免长对话里一次灌进几千行。 */
    private val limit = 300

    suspend fun search(
        keyword: String,
        scope: SearchScope,
        type: SearchType
    ): SearchResults = withContext(Dispatchers.IO) {
        val q = keyword.trim()
        if (q.isEmpty()) return@withContext SearchResults(emptyList(), emptyList())

        val convId = scope.conversationIdOrNull()
        val titles = if (type.wantsTitles) {
            dao.searchNodeTitles(q, convId, limit)
                .map { it.toTitleResult(q) }
                .sortedWith(titleRank)
        } else {
            emptyList()
        }
        val messages = if (type.wantsMessages) {
            dao.searchMessageContent(q, convId, limit)
                .map { it.toMessageResult(q) }
        } else {
            emptyList()
        }
        SearchResults(titles = titles, messages = messages)
    }

    /** 供范围选择器列出全部会话。 */
    suspend fun listConversations(): List<ConversationSummary> =
        withContext(Dispatchers.IO) { dao.getAllConversationSummaries() }

    /**
     * 标题结果的排序：前缀命中 > 完整匹配 > 其它包含，同级按创建时间倒序。
     *
     * 为什么前缀优先：用户搜"语法"，标题叫「语法新解」的比叫「我的语法笔记整理」
     * 更可能是他要找的。这个规则要区分"开头命中/中间命中"，SQL 里排不划算，
     * 放内存里更直接，代价只是排序几十到几百条。
     */
    private val titleRank = Comparator<TitleSearchResult> { a, b ->
        val byRank = rankOf(a) - rankOf(b)
        if (byRank != 0) byRank else b.createdAt.compareTo(a.createdAt)
    }

    private fun rankOf(r: TitleSearchResult): Int = when {
        r.matchedWhole -> 0
        r.matchedPrefix -> 1
        else -> 2
    }
}

// ------------------------------------------------------------ 映射与片段截取

private fun NodeTitleHit.toTitleResult(keyword: String): TitleSearchResult {
    val t = title.trim()
    val lower = t.lowercase()
    val q = keyword.lowercase()
    return TitleSearchResult(
        nodeId = id,
        conversationId = conversationId,
        parentId = parentId,
        title = t.ifEmpty { "（未命名分支）" },
        createdAt = createdAt,
        matchedWhole = lower == q,
        matchedPrefix = lower.startsWith(q)
    )
}

private fun MessageContentHit.toMessageResult(keyword: String): MessageContentHitResult =
    MessageContentHitResult(
        messageId = messageId,
        nodeId = nodeId,
        role = role,
        snippet = buildSnippet(content, keyword),
        nodeTitle = nodeTitle,
        conversationId = conversationId,
        createdAt = createdAt
    )

/**
 * 截取命中位置前后的一小段作为展示片段。
 *
 * ## 为什么不直接展示整条正文
 * 一条 AI 回答动辄上千字，检索结果列表里塞全文既撑爆版面又看不清重点。
 * 只给命中点附近的一小段，用户扫一眼就知道"是不是我要的那句"。
 *
 * ## 为什么以**命中位置**为中心而不是以开头为准
 * 用户搜的那个词几乎总是出现在段落中部（结论、术语、专有名词）。
 * 若固定截开头，用户会看到一堆"完全不含关键词"的片段，得逐条翻找，
 * 检索就白做了。
 *
 * 片段里保留原始文本、不插入任何标记：标记一旦算错位置，
 * 界面的高亮就会跟着错位（这是逐字高亮最常见的失效方式）。
 * 命中下标另行返回给界面，让它按**同一套规则**去找高亮范围。
 */
private const val SNIPPET_LEAD = 28
private const val SNIPPET_TRAIL = 72

private fun buildSnippet(content: String, keyword: String): String {
    val oneLine = content.replace(Regex("\\s+"), " ").trim()
    val at = oneLine.indexOf(keyword, ignoreCase = true)
    if (at < 0) {
        return if (oneLine.length > SNIPPET_LEAD + SNIPPET_TRAIL) {
            oneLine.take(SNIPPET_LEAD + SNIPPET_TRAIL) + "…"
        } else {
            oneLine
        }
    }
    val start = (at - SNIPPET_LEAD).coerceAtLeast(0)
    val end = (at + keyword.length + SNIPPET_TRAIL).coerceAtMost(oneLine.length)
    val core = oneLine.substring(start, end)
    return buildString {
        if (start > 0) append('…')
        append(core)
        if (end < oneLine.length) append('…')
    }
}