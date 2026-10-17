package com.treechat.workbench.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * 文件夹（组织层，可无限嵌套）。
 *
 * 注意与 ConversationNode.parentId 的区别：
 * - 这里的 parentFolderId 表示「组织归属」——这个文件夹放在哪个文件夹下
 * - ConversationNode.parentId 表示「语义分叉」——这段讨论引用自哪段讨论
 *
 * 两者正交，绝不能用同一个字段表达。混用会让「把对话拖进文件夹」这个动作
 * 破坏引用溯源树，之后查上下文链会全错。
 */
@Entity(
    tableName = "folders",
    foreignKeys = [
        ForeignKey(
            entity = Folder::class,
            parentColumns = ["id"],
            childColumns = ["parent_folder_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["parent_folder_id"])]
)
data class Folder(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),

    /** null 表示位于根目录。 */
    @ColumnInfo(name = "parent_folder_id")
    val parentFolderId: String? = null,

    val name: String,

    @ColumnInfo(name = "sort_order")
    val sortOrder: Int = 0,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "is_pinned", defaultValue = "0")
    val isPinned: Boolean = false,

    @ColumnInfo(name = "is_favorite", defaultValue = "0")
    val isFavorite: Boolean = false
)
