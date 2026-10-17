package com.treechat.workbench.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * 单条聊天消息实体。
 */
@Entity(
    tableName = "chat_messages",
    foreignKeys = [
        ForeignKey(
            entity = ConversationNode::class,
            parentColumns = ["id"],
            childColumns = ["node_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["node_id"]),
        Index(value = ["created_at"])
    ]
)
data class ChatMessage(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),

    @ColumnInfo(name = "node_id")
    val nodeId: String,

    /** 角色：user / assistant / system / tool */
    val role: String,

    val content: String,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "token_count")
    val tokenCount: Int = 0,

    /** 流式输出中标记。 */
    @ColumnInfo(name = "is_streaming")
    val isStreaming: Boolean = false,

    /**
     * 本轮联网检索到的来源，序列化为 JSON 存下来。
     *
     * 存成 JSON 而不是单独建表：来源只是展示用的附属信息，
     * 从不参与查询与关联，为它建表得不偿失。
     */
    @ColumnInfo(name = "search_sources")
    val searchSourcesJson: String? = null,

    /**
     * 随消息一起发出的图片，存为 JSON 数组的 **data URI** 字符串
     * （形如 `["data:image/jpeg;base64,..."]`）。
     *
     * ## 为什么存 data URI 而不是文件路径
     * 1. **不丢**：用户从相册选图时拿到的是一个临时 content:// 授权，
     *    进程重启后该授权即失效。只存路径的话，历史消息里的图会变成一片空白。
     *    存字节本身才真正做到"落库、重启还在"。
     * 2. 模型接口要的就是 base64（OpenAI 的 `image_url` / Anthropic 的
     *    `source.base64`），存成 data URI 可直接取用，不必再读盘转换。
     *
     * ## 代价（有意接受）
     * base64 会让体积膨胀约 33%。所以**入库前必须压缩**
     * （见 ImagePicker：限制长边 + JPEG 质量压缩），否则几张原图就能把库撑大。
     */
    @ColumnInfo(name = "images_json")
    val imagesJson: String? = null,

    /**
     * 思考模型返回的推理过程（`reasoning_content`）。
     *
     * ## 为什么单独存一列而不是拼进 content
     * 思考过程与最终答案是**两种性质的内容**：前者是草稿，后者是结论。
     * 混在一起会让回答难以阅读，也会让"复制回答"带上大段推理文字。
     * 分开存之后，界面可以折叠展示思考、正文保持干净。
     *
     * ## 为什么历史消息多为 NULL
     * 本列随 v6→v7 迁移加入。此前版本会把 `reasoning_content` 直接丢弃，
     * 所以那些消息的思考内容**已经不可恢复** —— 这不是 bug，
     * 而是当时没有保存；只有新版本产生的消息才会带上它。
     */
    @ColumnInfo(name = "reasoning_content")
    val reasoningContent: String? = null,

    /**
     * 本条回复所引用的本地知识库原文切片（序列化为 JSON），
     * 用于在界面上显式提示「正在回复知识库原文」并展示原文出处卡片。
     */
    @ColumnInfo(name = "knowledge_sources")
    val knowledgeSourcesJson: String? = null
)
