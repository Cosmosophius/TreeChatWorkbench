package com.treechat.workbench.network.protocol

/**
 * 传输给 API 的消息载荷。
 *
 * ## 为什么带 images
 * 本项目支持发送图片（数据层存的是 data URI，见 `ChatMessage.imagesJson`）。
 * 两种协议的图片格式完全不同，且**不能混用**：
 *  - OpenAI 兼容：`{"type":"image_url","image_url":{"url":"data:image/jpeg;base64,..."}}`
 *  - Anthropic ：`{"type":"image","source":{"type":"base64","media_type":"...","data":"..."}}`
 * 所以这里只保存"原始 data URI 列表"，具体形态由 `AiStreamClient` 按
 * 当前服务商的协议在构造请求时决定 —— 消息层不该关心协议差异。
 *
 * ## images 为空时行为完全不变
 * 空列表时请求体与加图片功能之前**逐字节一致**（content 仍是字符串），
 * 这是刻意的向后兼容：不少中转站对数组形态 content 支持不完整。
 */
data class PromptMessage(
    val role: String,
    val content: String,
    /**
     * 图片的 data URI 列表（形如 `data:image/jpeg;base64,...`）。
     *
     * ⚠️ 多轮对话里不要每轮都重发历史图片：base64 体积膨胀 33%，
     * 几步之后 token 消耗会爆炸。只带**最近一条用户消息**的图片。
     */
    val images: List<String> = emptyList()
)
