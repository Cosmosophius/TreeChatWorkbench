package com.treechat.workbench.data.db

/**
 * 跨会话检索的结果投影。
 *
 * ## 为什么用「投影类」而不是直接返回实体
 * 检索只需要标题/正文片段，不需要 `origin_quote`、`summary`、`images_json`
 * 这些大字段。把它们一起查出来在长对话上是实打实的浪费（一次最多 300 行），
 * Room 的 `@Query` 也支持只 SELECT 用得到的列。
 *
 * 这些类是**只读查询的载体**，不参与 Room 的表结构，
 * 因此不涉及任何 migration。
 */

/** 标题命中。 */
data class NodeTitleHit(
    val id: String,
    val conversationId: String?,
    val parentId: String?,
    val title: String,
    val createdAt: Long
)

/** 消息正文命中。 */
data class MessageContentHit(
    val messageId: String,
    val nodeId: String,
    val role: String,
    val content: String,
    val createdAt: Long,
    val conversationId: String?,
    /** 该消息所属分支的标题 —— 结果里直接展示，省一次回查。 */
    val nodeTitle: String?
)

/** 会话摘要（检索范围选择器用）。 */
data class ConversationSummary(
    val id: String,
    val title: String,
    val createdAt: Long
)