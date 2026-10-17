package com.treechat.workbench.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * 一个会话（一整个对话）。
 *
 * 层级关系：Folder -> Conversation -> ConversationNode（分支树）-> ChatMessage
 *
 * 每个 Conversation 拥有自己的一棵分支树：其根节点的 conversation_id 指向这里，
 * 且 parent_id 为 null。现有的递归 CTE 上溯查询天然以 parent_id = null 终止，
 * 所以每个会话的上下文链自动隔离，不需要改那段 SQL。
 */
@Entity(
    tableName = "conversations",
    foreignKeys = [
        ForeignKey(
            entity = Folder::class,
            parentColumns = ["id"],
            childColumns = ["folder_id"],
            // 文件夹被删时，里面的对话不跟着删，而是回到根目录 ——
            // 删文件夹是整理动作，不该连带毁掉聊天记录。
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [Index(value = ["folder_id"])]
)
data class Conversation(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),

    /** null 表示在根目录（未归档）。 */
    @ColumnInfo(name = "folder_id")
    val folderId: String? = null,

    val title: String,

    /**
     * 来源说明，用于在列表里显示"这个对话从哪来"。
     * 例如「手动创建」「来自 主干对话 · 分支导出」。
     */
    @ColumnInfo(name = "source_origin")
    val sourceOrigin: String? = null,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "is_pinned", defaultValue = "0")
    val isPinned: Boolean = false,

    @ColumnInfo(name = "is_favorite", defaultValue = "0")
    val isFavorite: Boolean = false
)
