package com.treechat.workbench.safety

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonElement
import com.treechat.workbench.data.model.ChatMessage
import com.treechat.workbench.network.protocol.ProviderConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

data class SafetyAuditResult(
    val isSensitive: Boolean,
    val reason: String,
    val detectedKeywords: List<String> = emptyList()
)

/**
 * 枝条内容安全与风控审查引擎。
 *
 * 专门解决多模型频繁切换时，因在开放或海外模型中讨论敏感话题后切换到
 * DeepSeek / MiniMax(Mimo) / Kimi / GLM / 通义千问等国内严格审查模型导致 API 报错拒答、风控拦截甚至封号的痛点。
 */
object BranchSafetyAuditor {

    private val gson = Gson()
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    // 常见容易触发国内云端模型拦截的典型词组与模式（用于离线毫秒级初筛）
    private val HEURISTIC_PATTERNS = listOf(
        "翻墙", "梯子", "科学上网", "VPN", "proxy", "shadowsocks", "v2ray",
        "政治敏感", "违规言论", "颠覆", "暴恐", "黄赌毒", "枪支弹药", "违禁品",
        "六四", "8964", "天安门事件", "法轮功", "反共", "独立公投", "台独", "藏独", "疆独"
    )

    /**
     * 判断指定模型与服务商是否属于受严格内容合规审查约束的国内主流模型。
     * 包括：DeepSeek, MiniMax (Mimo), 月之暗面 (Kimi), 智谱 (GLM), 阿里通义千问 (Qwen),
     * 百度文心 (ERNIE), 零一万物 (Yi), 腾讯混元 (Hunyuan), 阶跃星辰 (StepFun), 百川 (Baichuan), 讯飞星火 (Spark) 等。
     */
    fun isStrictModerationModel(
        modelId: String,
        providerId: String = "",
        baseUrl: String = ""
    ): Boolean {
        val m = modelId.lowercase()
        val p = providerId.lowercase()
        val u = baseUrl.lowercase()

        // 1. DeepSeek
        if (m.contains("deepseek") || p.contains("deepseek") || u.contains("deepseek")) return true
        // 2. MiniMax / Mimo / 海螺 / abab
        if (m.contains("minimax") || m.contains("mimo") || m.contains("abab") || p.contains("minimax") || u.contains("minimax")) return true
        // 3. Moonshot / Kimi
        if (m.contains("moonshot") || m.contains("kimi") || p.contains("moonshot") || u.contains("moonshot")) return true
        // 4. 智谱 GLM
        if (m.contains("glm") || m.contains("chatglm") || p.contains("zhipu") || u.contains("bigmodel")) return true
        // 5. 阿里通义千问 Qwen
        if (m.contains("qwen") || p.contains("aliyun") || p.contains("dashscope") || u.contains("dashscope")) return true
        // 6. 百度文心 ERNIE
        if (m.contains("ernie") || p.contains("baidu") || u.contains("qianfan")) return true
        // 7. 百川 Baichuan
        if (m.contains("baichuan") || p.contains("baichuan")) return true
        // 8. 讯飞星火 Spark
        if (m.contains("spark") || p.contains("xfyun") || u.contains("xfyun")) return true
        // 9. 零一万物 Yi
        if (m.startsWith("yi-") || p.contains("lingyi") || u.contains("lingyiwanwu")) return true
        // 10. 腾讯混元 Hunyuan
        if (m.contains("hunyuan") || p.contains("tencent") || u.contains("hunyuan")) return true
        // 11. 阶跃星辰 StepFun
        if (m.startsWith("step-") || p.contains("stepfun")) return true
        // 12. 任意 .cn 域名端点
        if (u.contains(".cn/") || u.endsWith(".cn")) return true

        return false
    }

    /**
     * 获取严格合规模型的代表名称提示。
     */
    fun getStrictModelProviderName(modelId: String): String {
        val m = modelId.lowercase()
        return when {
            m.contains("deepseek") -> "DeepSeek"
            m.contains("minimax") || m.contains("mimo") || m.contains("abab") -> "MiniMax (Mimo)"
            m.contains("moonshot") || m.contains("kimi") -> "月之暗面 (Kimi)"
            m.contains("glm") -> "智谱 (GLM)"
            m.contains("qwen") -> "通义千问 (Qwen)"
            m.contains("ernie") -> "百度文心"
            m.contains("spark") -> "讯飞星火"
            else -> "国内严格合规模型"
        }
    }

    /**
     * 离线快速启发式检测：毫秒级，不消耗 Token，无网络依赖
     */
    fun auditLocally(messages: List<ChatMessage>): SafetyAuditResult {
        val detected = mutableListOf<String>()
        val combined = messages.joinToString("\n") { it.content }

        for (pattern in HEURISTIC_PATTERNS) {
            if (combined.contains(pattern, ignoreCase = true)) {
                detected.add(pattern)
            }
        }

        return if (detected.isNotEmpty()) {
            SafetyAuditResult(
                isSensitive = true,
                reason = "检测到包含可能触发国内模型风控的敏感词：${detected.take(5).joinToString("、")}",
                detectedKeywords = detected
            )
        } else {
            SafetyAuditResult(
                isSensitive = false,
                reason = "本地规则初筛通过，未发现明显敏感词。"
            )
        }
    }

    /**
     * 调用大模型智能审查整条分支的对话内容。
     * 针对复杂的隐喻、争议性政治讨论、擦边内容由大模型输出结构化判定。
     */
    suspend fun auditWithAi(
        provider: ProviderConfig,
        messages: List<ChatMessage>
    ): SafetyAuditResult = withContext(Dispatchers.IO) {
        val localCheck = auditLocally(messages)
        if (localCheck.isSensitive) {
            return@withContext localCheck
        }

        if (messages.isEmpty() || provider.apiKey.isBlank()) {
            return@withContext localCheck
        }

        val textToAudit = messages.takeLast(10).joinToString("\n") { "${it.role}: ${it.content}" }
        val prompt = """
            你是一个严格的内容安全审查员。请审查以下对话内容。
            判定标准：此内容若发送给中国大陆主流大模型（如 DeepSeek、通义千问等有严格内容安全审查的国内模型API），
            是否极易触发内容风控拦截、拒绝回答或接口违规报错（如涉及敏感政治、色情、违法犯罪、严重争议话题等）？
            
            待审查对话内容：
            $textToAudit
            
            请严格只回复纯 JSON 格式，不要附带任何 Markdown 代码块标签（如 ```json 等）：
            {"isSensitive": true或false, "reason": "50字以内的判断原因", "detectedKeywords": ["敏感点1"]}
        """.trimIndent()

        runCatching {
            val url = provider.baseUrl.trimEnd('/') + "/chat/completions"
            val payload = mapOf(
                "model" to provider.selectedModelId,
                "messages" to listOf(
                    mapOf("role" to "user", "content" to prompt)
                ),
                "stream" to false,
                "temperature" to 0.1f
            )

            val body = gson.toJson(payload).toRequestBody("application/json; charset=utf-8".toMediaType())
            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer ${provider.apiKey}")
                .post(body)
                .build()

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                return@withContext localCheck
            }

            val respBody = response.body?.string().orEmpty()
            val root = gson.fromJson(respBody, JsonObject::class.java)
            val content = root.getAsJsonArray("choices")
                ?.get(0)?.asJsonObject
                ?.getAsJsonObject("message")
                ?.get("content")?.asString.orEmpty().trim()

            // 清洗可能的 ```json 标记
            val cleaned = content
                .removePrefix("```json")
                .removePrefix("```")
                .removeSuffix("```")
                .trim()

            val auditObj = gson.fromJson(cleaned, JsonObject::class.java)
            val isSensitive = auditObj.get("isSensitive")?.asBoolean ?: false
            val reason = auditObj.get("reason")?.asString ?: if (isSensitive) "包含风控敏感内容" else "未发现敏感内容"
            val keywords = mutableListOf<String>()
            auditObj.getAsJsonArray("detectedKeywords")?.forEach { elem: JsonElement ->
                keywords.add(elem.asString)
            }

            SafetyAuditResult(
                isSensitive = isSensitive,
                reason = reason,
                detectedKeywords = keywords
            )
        }.getOrElse {
            localCheck
        }
    }

    /**
     * 将包含敏感内容的文本进行本地合规脱敏处理：
     * 替换高危词汇为 [已脱敏]，提取并保留纯客观学术/技术内容，阻断敏感长上下文。
     */
    fun generateSanitizedText(rawText: String): String {
        if (rawText.isBlank()) return "（已完成脱敏切断，无前序长上下文）"
        var result = rawText
        for (pattern in HEURISTIC_PATTERNS) {
            result = result.replace(Regex(Regex.escape(pattern), RegexOption.IGNORE_CASE), "[已合规脱敏]")
        }
        return result.take(360).trim()
    }
}
