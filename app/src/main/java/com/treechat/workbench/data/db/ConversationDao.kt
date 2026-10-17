package com.treechat.workbench.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.treechat.workbench.data.model.ChatMessage
import com.treechat.workbench.data.model.ConversationNode
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {

    // ------------------------------------------------------------ 节点管理

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNode(node: ConversationNode)

    @Update
    suspend fun updateNode(node: ConversationNode)

    @Query("SELECT * FROM conversation_nodes WHERE id = :id LIMIT 1")
    suspend fun getNodeById(id: String): ConversationNode?

    @Query("SELECT * FROM conversation_nodes WHERE id = :id LIMIT 1")
    fun observeNodeById(id: String): Flow<ConversationNode?>

    /**
     * 取某个会话的根节点。
     *
     * 注意这里必须带 conversation_id 条件：早期版本只按 parent_id IS NULL
     * 取全局第一条，多会话之后会取错到别的会话的根。
     */
    @Query(
        """
        SELECT * FROM conversation_nodes
        WHERE conversation_id = :conversationId AND parent_id IS NULL
        ORDER BY created_at ASC LIMIT 1
        """
    )
    suspend fun getRootNodeForConversation(conversationId: String): ConversationNode?

    /** 拓扑图只应画当前会话的分支树，不是全部会话混在一起。 */
    @Query(
        """
        SELECT * FROM conversation_nodes
        WHERE conversation_id = :conversationId
        ORDER BY created_at ASC
        """
    )
    fun observeNodesForConversation(conversationId: String): Flow<List<ConversationNode>>

    /**
     * 观察**所有**带引用原文的分支，不限会话。
     *
     * ## 为什么需要它
     * 文献页要显示"这一段曾被哪条分支引用过"的标识。而提问的落点很分散：
     * 在文献页点「提问」走的是 `createQuestionInFolder`，它在**按书名建的
     * 另一棵会话树**里落节点，**不会**把那条会话设为当前会话。
     *
     * 若跟着"当前会话"取（`ChatNodeViewModel.nodes` 那个流本来就只含当前会话），
     * 文献页永远看不到刚提问产生的那条引用 —— 表现为"提问之后既没有下划线、
     * 也点不开"，而且换个会话再回来还是点不开。
     *
     * 所以这里按"有没有引用原文"来取，与当前会话无关：
     * 只要用户从任何一本书里引用过一段话，那条引用就该在文献页可见、可点。
     *
     * 只读查询，不涉及任何表结构变更。
     */
    @Query(
        """
        SELECT * FROM conversation_nodes
        WHERE origin_quote IS NOT NULL
          AND TRIM(origin_quote) <> ''
        ORDER BY created_at DESC
        """
    )
    fun observeQuotedNodes(): Flow<List<ConversationNode>>

    /**
     * 一次性取会话的全部节点（非 Flow）。
     *
     * 用于"打开会话时复位遗留的生成中标记"这类一次性维护任务：
     * 用 Flow 去观察反而多余，而且首帧之后还要自己取消订阅。
     */
    @Query(
        """
        SELECT * FROM conversation_nodes
        WHERE conversation_id = :conversationId
        ORDER BY created_at ASC
        """
    )
    suspend fun getNodesForConversation(conversationId: String): List<ConversationNode>


    @Query("SELECT * FROM conversation_nodes WHERE parent_id = :parentId ORDER BY created_at ASC")
    fun observeChildrenNodes(parentId: String): Flow<List<ConversationNode>>

    @Query("DELETE FROM conversation_nodes WHERE id = :id")
    suspend fun deleteNodeById(id: String)

    /**
     * 删除整个会话的所有节点。
     *
     * 会话与节点之间刻意没有外键（迁移更简单），所以级联要在这里显式做。
     * 节点的删除会通过外键级联清掉它们的消息。
     */
    @Query("DELETE FROM conversation_nodes WHERE conversation_id = :conversationId")
    suspend fun deleteNodesByConversation(conversationId: String)

    @Query("SELECT COUNT(*) FROM conversation_nodes WHERE conversation_id = :conversationId")
    suspend fun countNodesForConversation(conversationId: String): Int

    // ------------------------------------------------------------ 消息管理

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: ChatMessage)

    @Update
    suspend fun updateMessage(message: ChatMessage)

    @Query("SELECT * FROM chat_messages WHERE node_id = :nodeId ORDER BY created_at ASC")
    fun observeMessagesForNode(nodeId: String): Flow<List<ChatMessage>>

    @Query("SELECT * FROM chat_messages WHERE node_id = :nodeId ORDER BY created_at ASC")
    suspend fun getMessagesForNode(nodeId: String): List<ChatMessage>

    @Query("DELETE FROM chat_messages WHERE id = :messageId")
    suspend fun deleteMessage(messageId: String)

    /**
     * 这条消息属于哪个节点。
     *
     * 引用卡片"跳回原文"时需要：先由来源消息 id 找到它所在的分支节点，
     * 再决定跳转目标。消息与节点的关系只有这张表知道。
     */
    @Query("SELECT node_id FROM chat_messages WHERE id = :messageId LIMIT 1")
    suspend fun getNodeIdForMessage(messageId: String): String?

    /**
     * 全文搜索：正文命中关键词的**会话 id** 列表（只读查询，不改任何表结构）。
     *
     * 用途：访达（文件夹页）的"搜对话"。标题匹配在内存里做，这里补上
     * "标题没命中、但对话内容里提到过这个词"的那一类，否则用户搜到的
     * 永远只是他自己起的标题，而不是他真正记得的那句话。
     */
    @Query(
        """
        SELECT DISTINCT n.conversation_id FROM chat_messages m
        JOIN conversation_nodes n ON n.id = m.node_id
        WHERE m.content LIKE '%' || :keyword || '%'
        LIMIT 300
        """
    )
    suspend fun conversationIdsContaining(keyword: String): List<String>

    // ---------------------------------------------- 跨会话检索（标题 + 消息正文）

    /**
     * 检索**分支标题**。`conversationId` 传 null 表示不限定会话（搜全部）。
     *
     * 只读查询，不新增/修改任何表、列或 migration —— 复用已有的
     * `conversation_nodes.title` 列，正则是 LIKE，与上面的粗筛同一条路子。
     *
     * 排序在内存里做（见 SearchRepository）：SQL 的 COLLATE NOCASE 对
     * 简体/繁体以外的字符集行为不可靠，而"标题前缀命中优先"这类规则
     * 用 Kotlin 表达更直接，也省得为了排序建索引。
     */
    @Query(
        """
        SELECT id, conversation_id AS conversationId, parent_id AS parentId,
               title, created_at AS createdAt
        FROM conversation_nodes
        WHERE title LIKE '%' || :keyword || '%'
          AND (:conversationId IS NULL OR conversation_id = :conversationId)
        LIMIT :limit
        """
    )
    suspend fun searchNodeTitles(
        keyword: String,
        conversationId: String?,
        limit: Int
    ): List<NodeTitleHit>

    /**
     * 检索**消息正文**。`conversationId` 传 null 表示不限定会话。
     *
     * 连表取节点信息，是为了在结果里直接带上"这条消息属于哪个分支、
     * 哪个会话"，从而能一键跳过去；否则用户看到一堆片段却不知道在哪。
     */
    @Query(
        """
        SELECT m.id AS messageId, m.node_id AS nodeId, m.role AS role,
               m.content AS content, m.created_at AS createdAt,
               n.conversation_id AS conversationId, n.title AS nodeTitle
        FROM chat_messages m
        JOIN conversation_nodes n ON n.id = m.node_id
        WHERE m.content LIKE '%' || :keyword || '%'
          AND (:conversationId IS NULL OR n.conversation_id = :conversationId)
        ORDER BY m.created_at DESC
        LIMIT :limit
        """
    )
    suspend fun searchMessageContent(
        keyword: String,
        conversationId: String?,
        limit: Int
    ): List<MessageContentHit>

    /** 列出全部会话（检索范围选择器用）。 */
    @Query("SELECT id, title, created_at AS createdAt FROM conversations ORDER BY created_at DESC")
    suspend fun getAllConversationSummaries(): List<ConversationSummary>

    /** 取某条消息之前最近的一条用户消息内容，用于会话列表的摘要预览。 */
    @Query(
        """
        SELECT content FROM chat_messages
        WHERE node_id IN (
            SELECT id FROM conversation_nodes WHERE conversation_id = :conversationId
        ) AND role = 'user'
        ORDER BY created_at DESC LIMIT 1
        """
    )
    suspend fun getLatestUserMessage(conversationId: String): String?

    // ---------------------------------------------- 树状递归 CTE：回溯祖先链

    /**
     * 递归 CTE：从指定节点沿 parent_id 上溯到所在会话的根。
     *
     * 因为每个会话的根节点 parent_id 为 null，这个查询天然在会话边界终止，
     * 不会跨会话串上下文。
     */
    @Query(
        """
        WITH RECURSIVE AncestorTree AS (
            SELECT id, parent_id, conversation_id, title, origin_quote,
                   source_message_id, created_at, summary, 0 AS depth
            FROM conversation_nodes
            WHERE id = :targetNodeId

            UNION ALL

            SELECT p.id, p.parent_id, p.conversation_id, p.title, p.origin_quote,
                   p.source_message_id, p.created_at, p.summary, a.depth + 1
            FROM conversation_nodes p
            INNER JOIN AncestorTree a ON p.id = a.parent_id
        )
        SELECT id, parent_id, conversation_id, title, origin_quote,
               source_message_id, created_at, summary
        FROM AncestorTree
        ORDER BY depth DESC
        """
    )
    suspend fun getAncestorChain(targetNodeId: String): List<ConversationNode>
}
