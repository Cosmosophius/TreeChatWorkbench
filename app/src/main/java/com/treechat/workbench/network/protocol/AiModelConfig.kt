package com.treechat.workbench.network.protocol

/**
 * 推理强度（思考深度 / reasoning effort）。
 *
 * 这是一个**跨协议的抽象**，两个协议各有自己的表达方式，这里统一成四档，
 * 由 `AiStreamClient` 在构造请求时翻译：
 *
 * - Anthropic Messages：`thinking = { type: "enabled", budget_tokens: N }`，
 *   关闭时用 `{ type: "disabled" }`。
 * - OpenAI 兼容：`reasoning_effort = "low" | "medium" | "high"`，
 *   关闭时不发送该字段（而不是发 "none"，那样可能被服务端判为非法值）。
 *
 * ⚠️ 诚实说明：`thinking` / `reasoning_effort` 的具体取值与是否被接受
 * **取决于服务商与中转站**。本地反代实测：带与不带这两个参数都能返回 200，
 * 但未能证明它真的改变了思考深度（见变更记录）。若某服务商拒绝该参数，
 * 应把推理强度设为"关闭"——关闭时不发送任何相关字段，退化为最兼容的请求。
 */
enum class ReasoningEffort(val label: String) {
    /** 不发送任何推理相关字段（最兼容）。 */
    OFF("关闭"),
    LOW("低"),
    MEDIUM("中"),
    HIGH("高"),
    /** 最大思考预算 —— 只在确认模型支持大预算时使用。 */
    MAX("最大");

    /**
     * Anthropic `thinking.budget_tokens` 的取值。
     *
     * 有意选得保守：budget 必须小于 `max_tokens`，而 max_tokens 来自模型声明的
     * 最大输出（可能只有 8192）。选太大的预算会让请求被服务端拒绝，
     * 表现为"一开思考就报错"，比"思考得不够深"更糟。
     */
    val anthropicBudgetTokens: Int
        get() = when (this) {
            OFF -> 0
            LOW -> 2_048
            MEDIUM -> 8_192
            HIGH -> 24_576
            MAX -> 32_768
        }

    /** OpenAI 兼容协议的 `reasoning_effort` 取值；OFF 返回 null（不发送该字段）。 */
    val openAiValue: String?
        get() = when (this) {
            OFF -> null
            LOW -> "low"
            MEDIUM -> "medium"
            HIGH -> "high"
            MAX -> "high"
        }
}

/**
 * 协议类型
 */
enum class ProtocolType {
    OPENAI_COMPATIBLE,
    ANTHROPIC_MESSAGES
}

/**
 * 单个模型的能力与容量描述。
 *
 * contextWindow / maxOutputTokens 用 Long：虽然 Int 上限 21 亿足够，
 * 但 token 计数语义上就是大整数，用 Long 可以避免将来出现隐式转换问题。
 */
data class AiModelInfo(
    val id: String,
    val name: String,
    val contextWindow: Long = 64_000L,
    val maxOutputTokens: Long = 4_096L,
    val supportsVision: Boolean = false,
    val supportsReasoning: Boolean = false
)

/**
 * 提供商标识与连接配置
 */
data class ProviderConfig(
    val id: String,
    val name: String,
    val baseUrl: String,
    val apiKey: String,
    val protocolType: ProtocolType = ProtocolType.OPENAI_COMPATIBLE,
    val selectedModelId: String,
    val availableModels: List<AiModelInfo> = emptyList(),
    /**
     * 该服务商当前选用的推理强度。
     *
     * 放在 ProviderConfig 上（而不是模型或全局设置上）的原因：
     * 同一个模型在不同中转站上的思考支持不一样，用户往往要针对"这一条链路"
     * 单独调；放在全局会导致"改了 A 服务商把 B 也改了"。
     *
     * 用普通字段而不是枚举默认值：Gson 反序列化旧配置时该字段缺失，
     * 会得到 null —— 因此读取处一律走 [reasoningEffortOrOff] 兜底。
     */
    val reasoningEffort: ReasoningEffort? = ReasoningEffort.OFF
) {
    /** 推理强度，缺失时按"关闭"处理（兼容旧配置）。 */
    val reasoningEffortOrOff: ReasoningEffort
        get() = reasoningEffort ?: ReasoningEffort.OFF

    companion object {

        fun defaultDeepSeek(apiKey: String = "") = ProviderConfig(
            id = "deepseek",
            name = "DeepSeek",
            baseUrl = "https://api.deepseek.com/v1",
            apiKey = apiKey,
            protocolType = ProtocolType.OPENAI_COMPATIBLE,
            // ⚠️ 模型 ID 请以「获取可用模型」拉回来的真实列表为准。
            // 这里填的是用户给出的版本（v4.1 flash）；若服务商实际 ID 不同，
            // 点一次「获取可用模型」就会用官方 ID 覆盖，不必手改。
            selectedModelId = "deepseek-v4.1-flash",
            availableModels = listOf(
                AiModelInfo(
                    "deepseek-v4.1-flash", "DeepSeek V4.1 Flash",
                    1_000_000L, 64_000L, supportsReasoning = true
                ),
                AiModelInfo(
                    "deepseek-chat", "DeepSeek Chat（兼容旧 ID）",
                    64_000L, 8_192L
                ),
                AiModelInfo(
                    "deepseek-reasoner", "DeepSeek Reasoner（兼容旧 ID）",
                    64_000L, 8_192L, supportsReasoning = true
                )
            )
        )

        fun defaultMimo(apiKey: String = "") = ProviderConfig(
            id = "mimo",
            name = "MIMO (小米)",
            baseUrl = "https://api.xiaomimimo.com/v1",
            apiKey = apiKey,
            protocolType = ProtocolType.OPENAI_COMPATIBLE,
            // ⚠️ 同上：以「获取可用模型」返回的官方 ID 为准。
            selectedModelId = "mimo-v2.6-flash",
            availableModels = listOf(
                AiModelInfo(
                    "mimo-v2.6-flash", "MIMO V2.6 Flash",
                    1_000_000L, 64_000L, supportsReasoning = true
                ),
                AiModelInfo(
                    "mimo-v2.5-pro", "MIMO v2.5 Pro（兼容旧 ID）",
                    131_072L, 8_192L
                )
            )
        )

        fun defaultAnthropic(apiKey: String = "") = ProviderConfig(
            id = "anthropic",
            name = "Anthropic",
            baseUrl = "https://api.anthropic.com/v1",
            apiKey = apiKey,
            protocolType = ProtocolType.ANTHROPIC_MESSAGES,
            selectedModelId = "claude-3-5-sonnet-20241022",
            availableModels = listOf(
                AiModelInfo(
                    "claude-3-5-sonnet-20241022", "Claude 3.5 Sonnet",
                    200_000L, 8_192L, supportsVision = true
                ),
                AiModelInfo(
                    "claude-3-5-haiku-20241022", "Claude 3.5 Haiku",
                    200_000L, 8_192L
                )
            )
        )

        fun defaultOpenAi(apiKey: String = "") = ProviderConfig(
            id = "openai",
            name = "OpenAI",
            baseUrl = "https://api.openai.com/v1",
            apiKey = apiKey,
            protocolType = ProtocolType.OPENAI_COMPATIBLE,
            selectedModelId = "gpt-4o-mini",
            availableModels = listOf(
                AiModelInfo(
                    "gpt-4o-mini", "GPT-4o mini",
                    128_000L, 16_384L, supportsVision = true
                ),
                AiModelInfo(
                    "gpt-4o", "GPT-4o",
                    128_000L, 16_384L, supportsVision = true
                )
            )
        )

        /**
         * 预设清单，供设置面板的快速填充使用。
         *
         * 注：原先第一个预设是「本地 NPU/APU 硬件加速大模型」(id = `local-npu-qwen`)，
         * 该方案已按用户要求整体移除（会拖慢整机），故不再提供。
         */
        fun presets(): List<ProviderConfig> = listOf(
            defaultDeepSeek(),
            defaultMimo(),
            defaultAnthropic(),
            defaultOpenAi()
        )
    }
}
