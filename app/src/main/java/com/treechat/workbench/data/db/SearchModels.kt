package com.treechat.workbench.data.db

/**
 * 跨会话检索的**范围**与**类型**选项，以及结果模型。
 *
 * 纯数据类，不含逻辑 —— 界面负责把它们做成选择器，
 * [ConversationSearchRepository] 负责按它们取数与排序。
 */

/** 检索范围：全部会话，或指定单个会话（"某一个根对话"）。 */
sealed interface SearchScope {
    /** null 表示不限会话。 */
    fun conversationIdOrNull(): String?

    data object AllConversations : SearchScope {
        override fun conversationIdOrNull(): String? = null
    }

    data class OneConversation(val conversationId: String) : SearchScope {
        override fun conversationIdOrNull(): String = conversationId
    }
}

/**
 * 检索类型：只搜标题、只搜消息、或都搜。
 *
 * 属性 [wantsTitles] / [wantsMessages] 让调用方不必写 `when` 分两次，
 * 也不会出现"加了新枚举值忘了补某个分支"的漏网。
 */
enum class SearchType(val displayName: String, val wantsTitles: Boolean, val wantsMessages: Boolean) {
    Both("标题与消息", wantsTitles = true, wantsMessages = true),
    Titles("仅标题", wantsTitles = true, wantsMessages = false),
    Messages("仅消息", wantsTitles = false, wantsMessages = true)
}

/**
 * 一次检索的完整结果。
 *
 * **标题与消息分开两个列表**而不是混在一个列表里：
 * 用户明确要求"分开标注"，而且两者性质不同 —— 标题是用户自己起的名字，
 * 消息是正文片段。混排会让人分不清命中的是哪一个。
 * [titles] 恒排在 [messages] 之前（标题优先）。
 */
data class SearchResults(
    val titles: List<TitleSearchResult> = emptyList(),
    val messages: List<MessageContentHitResult> = emptyList()
) {
    val total: Int get() = titles.size + messages.size
}

/**
 * 标题命中结果。
 *
 * [matchedWhole] / [matchedPrefix] 由检索层算好并据此排过序，
 * 界面可以直接把它们显示成"完全匹配"/"标题开头匹配"的小徽标。
 */
data class TitleSearchResult(
    val nodeId: String,
    val conversationId: String?,
    val parentId: String?,
    val title: String,
    val createdAt: Long,
    val matchedWhole: Boolean,
    val matchedPrefix: Boolean
)

/** 消息正文命中结果。 */
data class MessageContentHitResult(
    val messageId: String,
    val nodeId: String,
    /** user / assistant / system / tool */
    val role: String,
    /** 以命中位置为中心截取的片段（首尾可能有省略号）。 */
    val snippet: String,
    /** 该消息所属分支的标题，让用户知道"这是在哪条分支里找到的"。 */
    val nodeTitle: String?,
    val conversationId: String?,
    val createdAt: Long
)