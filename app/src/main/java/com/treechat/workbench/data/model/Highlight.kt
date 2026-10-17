package com.treechat.workbench.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * 一条笔记高亮。
 *
 * 设计取舍（**这是本节最需要说清的地方**）：
 *
 * 高亮范围按 (message_id, start_offset, end_offset) 存**字符索引**，
 * 而不是把高亮文本本身存下来。理由是索引在原文里是"定位"，文本只是"副本"——
 * 若 AI 消息在高亮之后仍被增量改写（流式补全、重新生成），存文本会出现
 * 「高亮备份的这段已经不在原文里了」的悬空引用，而索引能随原文一起移动或
 * 在越界时被安全丢弃（渲染时做 coerce，见 MarkdownRenderer）。
 *
 * 偏移量相对的是 **message.content 的原始 Markdown 字符串**，
 * 不是渲染后的可见文本。两者由 MarkdownRenderer 的 offsetMap 互相换算。
 *
 * 外键挂在 chat_messages 上并 CASCADE：消息没了，它的高亮自然失去意义，
 * 不该留下孤儿行。
 */
@Entity(
    tableName = "message_highlights",
    foreignKeys = [
        ForeignKey(
            entity = ChatMessage::class,
            parentColumns = ["id"],
            childColumns = ["message_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["message_id"]),
        Index(value = ["node_id"])
    ]
)
data class Highlight(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),

    @ColumnInfo(name = "message_id")
    val messageId: String,

    /** 冗余一份 node_id：按节点批量取当前视图的高亮时不必再 join 消息表。 */
    @ColumnInfo(name = "node_id")
    val nodeId: String,

    @ColumnInfo(name = "start_offset")
    val startOffset: Int,

    @ColumnInfo(name = "end_offset")
    val endOffset: Int,

    /** 高亮色，存 ARGB 整数；这样以后加多种颜色不用再改库。 */
    @ColumnInfo(name = "color_argb")
    val colorArgb: Int = DEFAULT_COLOR_ARGB,

    /** 用户可附一句笔记；本轮 UI 只做高亮，字段先留着，不写假数据。 */
    val note: String? = null,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis()
) {
    companion object {
        /**
         * 默认高亮色：偏黄的强调色，ARGB。
         * 刻意不用主题色 —— 高亮是"我标记的"，应当与主题色可区分。
         */
        const val DEFAULT_COLOR_ARGB: Int = 0x66FFD54F
    }
}
