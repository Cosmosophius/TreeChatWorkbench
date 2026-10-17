package com.treechat.workbench.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * 树形会话节点（语义分叉层）。
 *
 * parent_id 表达的是「这段讨论引用自哪段讨论」，与文件夹的组织层级无关。
 *
 * conversation_id 把它归属到某个会话；每个会话的根节点 parent_id 为 null，
 * 因此递归上溯查询会自然在该会话的根处终止，各会话上下文天然互不串味。
 *
 * 这里刻意**不**对 conversations 建外键：
 * 迁移时需要把历史数据回填到一个新建的会话上，带外键会让迁移 SQL 更脆弱；
 * 会话删除时的级联由 Dao 里显式处理（先删节点，再删会话）。
 */
@Entity(
    tableName = "conversation_nodes",
    foreignKeys = [
        ForeignKey(
            entity = ConversationNode::class,
            parentColumns = ["id"],
            childColumns = ["parent_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["parent_id"]),
        Index(value = ["created_at"]),
        Index(value = ["conversation_id"])
    ]
)
data class ConversationNode(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),

    @ColumnInfo(name = "parent_id")
    val parentId: String? = null,

    @ColumnInfo(name = "conversation_id")
    val conversationId: String? = null,

    val title: String = "主对话",

    @ColumnInfo(name = "origin_quote")
    val originQuote: String? = null,

    @ColumnInfo(name = "source_message_id")
    val sourceMessageId: String? = null,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),

    /**
     * 记忆摘要：链路 Token 超限时压缩成核心结论，替换冗长历史。
     * （字段与读取逻辑已就绪，触发机制尚未接上。）
     */
    val summary: String? = null
)
