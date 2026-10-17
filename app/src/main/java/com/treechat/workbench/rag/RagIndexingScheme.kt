package com.treechat.workbench.rag

/**
 * RAG 索引与检索模式：用户可在本地与云端 API 之间自由切换。
 */
enum class RagIndexingScheme(val label: String, val description: String) {
    /** 纯本地离线：滑动窗口启发式切片 + 2-gram 倒排索引 + BM25 加权（零网络开销，隐私保密） */
    LOCAL_OFFLINE("纯本地离线 (2-Gram)", "无需联网，端侧 SQLite 倒排索引与启发式规则切片，完全离线与保密"),

    /** 云端 API 厂商：语义增强切片 / 云端 Embedding 向量索引 + 向量相似度检索（高语义理解精度） */
    CLOUD_EMBEDDING("云端 API 厂商 (向量 Embedding)", "调用兼容 OpenAI / 百炼 / 智谱等平台的向量接口建立高维索引，语义理解更强"),

    /** 混合模式：本地 2-gram 关键词与云端 Embedding 向量打分加权融合 */
    HYBRID("双路混合检索 (本地 + 云端)", "结合 2-gram 精确术语匹配与云端语义向量，召回与精准度最佳")
}

/**
 * 云端 RAG / Embedding 服务商配置参数。
 */
data class CloudRagConfig(
    val enabled: Boolean = false,
    val scheme: RagIndexingScheme = RagIndexingScheme.LOCAL_OFFLINE,
    /** 接口地址，例如 https://api.openai.com/v1 或兼容端点 */
    val baseUrl: String = "https://api.openai.com/v1",
    /** API 密钥 */
    val apiKey: String = "",
    /** 向量模型名称，如 text-embedding-3-small, text-embedding-v3, bge-m3 等 */
    val embeddingModel: String = "text-embedding-3-small",
    /** 向量维度（可选，部分接口如 OpenAI 支持指定维度缩减，默认使用模型原始维度） */
    val dimensions: Int? = null,
    /** 每批次请求的最大文本块数量，防止大教材单次超载 */
    val batchSize: Int = 16,
    /** 是否在切片时请求云端进行语义分段润色（可选增强） */
    val semanticChunkingEnabled: Boolean = false
) {
    fun isValidForApi(): Boolean {
        return baseUrl.isNotBlank() && apiKey.isNotBlank() && embeddingModel.isNotBlank()
    }
}
