package com.treechat.workbench.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 知识库文档（一本教材 / 一个 Markdown 文件 / 一段粘贴的文本）。
 *
 * ## 为什么要"全盘落库"而不是放内存
 * 用户明确要求"不管有多大都要全盘落库"。这不只是为了省内存，更重要的是：
 * 应用被杀、切后台、重启后，知识库必须还在 —— 否则每次打开都要重新导入教材，
 * 这个功能就等于不可用。
 *
 * 正文切块后存在 [KnowledgeChunk]，检索索引存在 [KnowledgeGram]。
 * 都不放内存，检索直接查库。
 */
@Entity(
    tableName = "knowledge_documents",
    indices = [Index("title")]
)
data class KnowledgeDocument(
    @PrimaryKey val id: String,
    /** 展示名，默认取文件名（去掉扩展名）。 */
    val title: String,
    /** 原始文件名，保留扩展名，便于用户辨认来源。 */
    val fileName: String,
    /** 来源类型：DOCX / MARKDOWN / TEXT。用于展示与排查解析问题。 */
    val sourceType: String,
    /** 全文字符数（解析后的纯文本长度），用于界面展示。 */
    val charCount: Int,
    /** 切块数量。 */
    val chunkCount: Int,
    /** 是否参与检索。允许用户临时关掉某本书而不删除。 */
    val enabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    /** 所属知识库文件夹路径（例如 "语言学考研/乔姆斯基专题"，空串代表根目录）。 */
    val folderPath: String = "",
    /** 索引模式方案：LOCAL_OFFLINE（本地 2-gram）或 CLOUD_EMBEDDING（云端向量）等。 */
    val indexingScheme: String = "LOCAL_OFFLINE"
)

/**
 * 教材切块：检索与"回答原文"的最小单位。
 *
 * 为什么存 `charStart`：回答里要标出处，而且用户点"查看原文"时应能定位到
 * 段落级位置。只存文本不存偏移，就无法回溯到原文语境。
 */
@Entity(
    tableName = "knowledge_chunks",
    indices = [
        Index("documentId"),
        // 检索后要按 (documentId, orderIndex) 取上下文邻块，这个复合索引让它走索引而不是全表扫
        Index("documentId", "orderIndex")
    ],
    foreignKeys = [
        ForeignKey(
            entity = KnowledgeDocument::class,
            parentColumns = ["id"],
            childColumns = ["documentId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class KnowledgeChunk(
    @PrimaryKey val id: String,
    val documentId: String,
    /** 在这份文档里的顺序号，从 0 开始。用于取上下文邻块与展示"第 N 段"。 */
    val orderIndex: Int,
    /** 切块正文（已做空白归一）。 */
    val text: String,
    /** 该块在原文纯文本中的起始字符偏移。 */
    val charStart: Int,
    /** 所属章节/小节标题（如果能识别出来），用于出处显示。 */
    val section: String? = null,
    /** 向量维度字符串编码（可选：云端 API Embedding 方案时存储高维向量值）。 */
    val embedding: String? = null
)

/**
 * 检索索引：**2 字组（bigram）倒排表**，全盘落库。
 *
 * ## 为什么是 2 字组而不是 3 字组
 * 实测（`probe_zh_retrieval.py`，14 个中文提问，top3 命中率）：
 *   2 字组 **14/14 (100%)** / 3 字组 9/14 (64%) / 4 字组 4/14 (28%)
 * 中文词多为双字（细胞、磷脂、核孔），3 字组要求查询与原文连续三字一致，
 * "膜的支架"这类提问就召回不到。
 *
 * ## 为什么不直接用 SQLite 的 FTS5
 * 1. Android API 29 的系统 SQLite ≈ 3.22，**不支持 trigram 分词器**（要 3.34+）；
 * 2. 即便支持，实测中文 trigram 效果也不如 bigram；
 * 3. 自建倒排表不依赖任何版本特性，任何设备都能跑。
 *
 * ## 存储策略
 * 一行一个 (gram, chunkId, freq)。freq 是该 2 字组在这个块里出现的次数，
 * 用于打分时偏向"反复提到该术语"的段落（教材里重点概念往往重复出现）。
 * 落库而非内存：用户要求全盘落库，且几 MB 教材的 gram 数会在数十万级。
 */
@Entity(
    tableName = "knowledge_grams",
    // 主键天然是 (gram, chunkId)：它同时充当"按 gram 查倒排"的索引。
    // 另建 documentId 索引用于删除整本书时快速定位。
    primaryKeys = ["gram", "chunkId"],
    indices = [Index("documentId"), Index("chunkId")],
    foreignKeys = [
        // 必须声明外键并 CASCADE：否则删掉一本书后，它的 gram 行会留在表里，
        // 成为一个"指向已删除块"的孤儿索引 —— 检索命中它却取不到正文，
        // 表现为"搜到了却答不出来"，极难排查。
        ForeignKey(
            entity = KnowledgeChunk::class,
            parentColumns = ["id"],
            childColumns = ["chunkId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class KnowledgeGram(
    /** 2 个字符（已小写化、去空白）。 */
    val gram: String,
    val chunkId: String,
    /** 为删除整本书时能按文档批量清理。 */
    val documentId: String,
    /** 该 gram 在此块内出现次数。 */
    val freq: Int
)
