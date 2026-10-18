package com.treechat.workbench.network.search

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.treechat.workbench.network.client.messagesEndpoint
import com.treechat.workbench.network.protocol.ProviderConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/** 一条搜索结果。 */
data class SearchResult(
    val title: String,
    val url: String,
    val snippet: String
)

/**
 * 一次后端探测的结果。
 *
 * 这是为了回答"为什么联网失败"而存在的：只给用户看一句
 * "原生搜索失败（HTTP 404）"没有任何可操作性 —— 他不知道到底请求了哪个
 * URL、服务端返回了什么。把请求地址、状态码、响应体片段都摊开，
 * 用户（或下一位维护者）才能自己判断是"中转站没暴露该接口"还是"密钥不对"。
 */
data class SearchProbeResult(
    val backend: SearchBackend,
    val ok: Boolean,
    /** 实际请求的 URL。404 时一眼能看出路径拼错或该端点不存在。 */
    val requestUrl: String,
    val httpCode: Int?,
    val detail: String,
    val resultCount: Int = 0,
    val elapsedMs: Long = 0
)

/** 联网搜索后端。 */
enum class SearchBackend {
    /**
     * 使用模型服务商自己的**服务端原生搜索**。
     *
     * 这是首选：它走 /messages 协议并带上服务端工具 `web_search_20250305`，
     * 用的是你聊天的那把 API Key —— **不需要任何额外的搜索密钥，也不用额外付费**。
     * 前提是你用的服务商/中转站实现了这个服务端工具。
     */
    NATIVE,
    SOGOU,
    BING,
    DUCKDUCKGO,
    SEARXNG,
    TAVILY,
    /**
     * DeepSeek 官方搜索（**专用搜索 Key 预设**，2026-10-18 新增）。
     *
     * 与 [NATIVE] 的区别：NATIVE 的 DeepSeek 通路自动复用"已保存的 DeepSeek
     * 聊天 Key"；本后端用于 DeepSeek 新推出的**独立搜索 Key**——
     * 单独申请、单独计费、只用于搜索，不与聊天 Key 混用。
     * 通路本身相同（`/anthropic/v1/messages` + `web_search_20250305` 服务端工具），
     * 只是 Key 的来源不同：优先专用 Key，未填时回退已保存的聊天 Key。
     */
    DEEPSEEK_SEARCH;

    val displayName: String
        get() = when (this) {
            NATIVE -> "跟随模型服务商（智能判断：原生端点或自动极速直连）"
            SOGOU -> "搜狗搜索（免密钥，国内秒级直连，最稳定推荐）"
            BING -> "Bing（免密钥，网页抓取）"
            DUCKDUCKGO -> "DuckDuckGo（免密钥，网页抓取）"
            SEARXNG -> "SearXNG（内置公共池 / 自建实例，免密钥）"
            TAVILY -> "Tavily（需 API Key，第三方搜索服务）"
            DEEPSEEK_SEARCH -> "DeepSeek 官方搜索（新搜索 Key，专用直连）"
        }

    /** 是否必须填 API Key。 */
    val requiresApiKey: Boolean
        get() = this == TAVILY || this == DEEPSEEK_SEARCH

    /** 是否需要填自定义实例地址。 */
    val requiresBaseUrl: Boolean
        get() = this == SEARXNG
}

/** 搜索配置。 */
data class SearchConfig(
    val enabled: Boolean = false,
    val backend: SearchBackend = SearchBackend.NATIVE,
    val apiKey: String = "",
    val searxngBaseUrl: String = "",
    val maxResults: Int = 5,
    /**
     * 从已保存的服务商列表中自动提取的 DeepSeek 官方 API Key
     *（与 DSH Android 的 credentialRef("DEEPSEEK_API_KEY") 机制完全一致）。
     * 即使当前正在用小米 MiMo 或反代 Gemini 聊天，也能自动复用这把 Key
     * 调用 https://api.deepseek.com/anthropic/v1/messages 完成官方原生搜索。
     */
    val deepseekApiKey: String = "",
    /**
     * DeepSeek **专用搜索 Key**（新出的独立搜索 Key，与聊天 Key 分开申请/计费）。
     * 仅 [SearchBackend.DEEPSEEK_SEARCH] 使用；空 = 回退到自动提取的聊天 Key。
     */
    val deepseekSearchKey: String = ""
)

/**
 * 客户端联网搜索。
 *
 * ## 两条通路
 * 1. **服务商原生搜索**（[SearchBackend.NATIVE]）：走 `/messages` 协议 + 服务端工具
 *    `web_search_20250305`，用当前服务商（或已保存的 DeepSeek 官方）那把 Key，
 *    不需要额外的搜索密钥。前提是服务商/中转站确实实现了这个服务端工具。
 * 2. **免密钥公开端点**（其余后端 + 内置降级管道）：由客户端直接发 HTTP 请求，
 *    抓公开 HTML 结果页或调公开 JSON API。**完全不需要任何 Key**，
 *    代价是没有任何 SLA（详见仓库根目录 `WEB_SEARCH_NO_KEY.md`）。
 *
 * ## 关于可靠性（重要）
 * 公开端点**没有任何官方保证**：页面改版会失效、请求过频会被限流、
 * 部分网络环境下域名不通（例如 DuckDuckGo / Wikipedia 在国内直连不可达）。
 * 因此这里的铁律有两条：
 * - **逐个降级**：任一通道失败就换下一个，不会因为一个 403 就整体判死；
 * - **不假装成功**：所有通道都失败时**抛出异常**（由调用方上报为"检索失败"），
 *   **绝不**返回空列表冒充"搜索无结果" —— 那会让用户以为"网上真没这资料"，
 *   进而怀疑模型，而不是怀疑网络。
 * 如果你需要稳定与 SLA，用 TAVILY（官方 API，需 Key）或自建 SEARXNG。
 */
class WebSearchClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()
) {
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val browserUa =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/124.0.0.0 Safari/537.36"

    /**
     * 抓取公开 HTML 结果页用的"快速失败"客户端。
     *
     * 为什么要单独配一个：默认 client 是 15s 连接 / 25s 读取，那是给**同一条**
     * 流式对话用的，可以慢。而免密钥抓取管道是"逐个端点试，谁先回用谁"，
     * 一个端点在 DNS 或握手阶段被墙时，等满 15 秒再换下一个，用户要干等十几秒
     * 才看到第一条结果。这里的超时只为"尽快判定这条路不通"服务。
     */
    private val fastClient = client.newBuilder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(7, TimeUnit.SECONDS)
        .build()

    /**
     * SearXNG 节点池专用客户端：比抓 HTML 再短一档。
     * 节点池要连续轮询 4 个实例，每个实例必须很小气地超时，否则故障转移本身
     * 就成了新的耗时来源。
     */
    private val searxngClient = client.newBuilder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    /** 内置高可用免密钥 SearXNG 公共节点池（开箱即用，免配置免注册，返回纯净 JSON）。 */
    private val defaultSearxngNodes = listOf(
        "https://searx.be",
        "https://baresearch.org",
        "https://search.ononoki.org",
        "https://searx.tiekoetter.com"
    )

    suspend fun search(
        query: String,
        config: SearchConfig,
        provider: ProviderConfig
    ): Result<List<SearchResult>> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext Result.success(emptyList())
        try {
            val results = when (config.backend) {
                SearchBackend.NATIVE -> {
                    // 首选尝试服务商原生搜索。失败（如小米 MiMo、标准 OpenAI 协议、
                    // 中转站未暴露 /messages、404）时不抛错，而是转内置免密钥管道。
                    val nativeResults = runCatching { searchNative(query, config, provider) }
                        .getOrElse { error ->
                            android.util.Log.i(
                                "TreeChatSearch",
                                "原生搜索不可用（${error.message}），转入内置免密钥管道"
                            )
                            emptyList()
                        }
                    if (nativeResults.isNotEmpty()) {
                        nativeResults
                    } else {
                        android.util.Log.i("TreeChatSearch", "原生搜索无结果，启动内置免密钥降级管道")
                        fallbackBuiltinSearch(query, config.maxResults)
                    }
                }
                SearchBackend.SOGOU -> {
                    val res = runCatching { searchSogou(query, config.maxResults) }.getOrDefault(emptyList())
                    if (res.isNotEmpty()) res else fallbackBuiltinSearch(query, config.maxResults)
                }
                SearchBackend.BING -> {
                    val bingRes = runCatching { searchBing(query, config.maxResults) }.getOrDefault(emptyList())
                    if (bingRes.isNotEmpty()) bingRes else fallbackBuiltinSearch(query, config.maxResults)
                }
                SearchBackend.DUCKDUCKGO -> {
                    val ddgRes = runCatching { searchDuckDuckGo(query, config.maxResults) }.getOrDefault(emptyList())
                    if (ddgRes.isNotEmpty()) ddgRes else fallbackBuiltinSearch(query, config.maxResults)
                }
                SearchBackend.SEARXNG -> {
                    // 用户显式选了 SearXNG 就**只**走 SearXNG：
                    // 自填实例失败会抛错，内置节点池全部失败也会抛错（见 searchPublicSearxng），
                    // 绝不静默换成别的引擎再假装是 SearXNG 的结果。
                    if (config.searxngBaseUrl.isNotBlank()) {
                        searchSearxng(query, config)
                    } else {
                        searchPublicSearxng(query, config.maxResults)
                    }
                }
                SearchBackend.TAVILY -> searchTavily(query, config)
                SearchBackend.DEEPSEEK_SEARCH -> {
                    // 显式选了 DeepSeek 搜索就**只**走 DeepSeek 官方端点：
                    // 专用 Key 优先，未填则回退已保存的 DeepSeek 聊天 Key；
                    // 两者皆无 → 抛错（用户显式选择的后端不静默换引擎）。
                    val key = config.deepseekSearchKey.ifBlank { resolveDeepSeekSearchKey(config, provider) }
                    if (key.isBlank()) {
                        throw IllegalStateException(
                            "未填写 DeepSeek 搜索 Key（设置 → 联网搜索），也未在服务商里保存过 DeepSeek 聊天 Key"
                        )
                    }
                    executeAnthropicWebSearch(
                        endpoint = DEEPSEEK_DEFAULT_SEARCH_ENDPOINT,
                        apiKey = key,
                        model = DEEPSEEK_DEFAULT_SEARCH_MODEL,
                        maxTokens = DEEPSEEK_DEFAULT_MAX_TOKENS,
                        query = query,
                        maxUses = config.maxResults.coerceIn(1, 10)
                    )
                }
            }
            Result.success(results)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ------------------------------------------------------------- 诊断

    /**
     * 逐个探测各后端的连通情况，把真实 URL / 状态码 / 响应体片段报出来。
     *
     * 为什么要专门做一个诊断入口：
     * 联网失败时用户看到的只是一句"原生搜索失败（HTTP 404）"。而 404 可能是
     * 很多原因 —— 中转站没有 /messages 端点、Base URL 少了或多了 /v1、
     * 该端点存在但未实现 web_search 服务端工具。这三者的处置办法完全不同，
     * 只报一个状态码等于把排查工作全丢给用户。
     *
     * 探测请求刻意做得最小：NATIVE 探测用的是固定查询串，
     * 这样即使失败也不消耗真实的检索额度。
     */
    suspend fun probe(
        config: SearchConfig,
        provider: ProviderConfig
    ): List<SearchProbeResult> = withContext(Dispatchers.IO) {
        val backends = listOf(
            SearchBackend.NATIVE,
            SearchBackend.BING,
            SearchBackend.DUCKDUCKGO,
            SearchBackend.SEARXNG,
            SearchBackend.TAVILY
        )
        backends.map { backend ->
            val started = System.currentTimeMillis()
            val url = describeUrl(backend, provider, config)
            val probeConfig = config.copy(backend = backend, maxResults = 3)
            // 注意：search() 本身返回 Result，**不要**再套一层 runCatching，
            // 否则得到 Result<Result<List<…>>>，onSuccess 里拿到的是内层 Result
            // 而不是列表（表现为 "Unresolved reference 'size'"）。
            val outcome = search("TreeChat 连通性测试", probeConfig, provider)
            val elapsed = System.currentTimeMillis() - started
            outcome.fold(
                onSuccess = { results ->
                    SearchProbeResult(
                        backend = backend,
                        ok = true,
                        requestUrl = url,
                        httpCode = 200,
                        detail = "可用，返回 ${results.size} 条来源",
                        resultCount = results.size,
                        elapsedMs = elapsed
                    )
                },
                onFailure = { error ->
                    val message = error.localizedMessage ?: error::class.java.simpleName
                    SearchProbeResult(
                        backend = backend,
                        ok = false,
                        requestUrl = url,
                        httpCode = extractHttpCode(message),
                        detail = message,
                        elapsedMs = elapsed
                    )
                }
            )
        }
    }

    /** 该后端在这份配置下会请求哪个地址（用于展示，不发请求）。 */
    private fun describeUrl(
        backend: SearchBackend,
        provider: ProviderConfig,
        config: SearchConfig
    ): String {
        return when (backend) {
            SearchBackend.NATIVE -> {
                val dsKey = resolveDeepSeekSearchKey(config, provider)
                if (dsKey.isNotBlank()) {
                    DEEPSEEK_DEFAULT_SEARCH_ENDPOINT
                } else {
                    messagesEndpoint(provider.baseUrl)
                }
            }
            SearchBackend.SOGOU -> "https://m.sogou.com/web/searchList.jsp?keyword=…"
            SearchBackend.BING -> "https://cn.bing.com/search?q=…"
            SearchBackend.DUCKDUCKGO -> "https://html.duckduckgo.com/html/?q=…（失败自动退 lite.duckduckgo.com/lite/）"
            SearchBackend.SEARXNG -> {
                val searx = config.searxngBaseUrl.trim().trimEnd('/')
                if (searx.isBlank()) {
                    "内置公共节点池：" + defaultSearxngNodes.joinToString("、") { "$it/search?q=…&format=json" }
                } else {
                    "$searx/search?q=…&format=json"
                }
            }
            SearchBackend.TAVILY -> "https://api.tavily.com/search"
            SearchBackend.DEEPSEEK_SEARCH -> DEEPSEEK_DEFAULT_SEARCH_ENDPOINT
        }
    }

    /** 从错误文案里抠出 HTTP 状态码（错误信息由本类自己拼，格式可控）。 */
    private fun extractHttpCode(message: String): Int? =
        Regex("HTTP (\\d{3})").find(message)?.groupValues?.get(1)?.toIntOrNull()

    /**
     * 解析可用于 DeepSeek 官方原生搜索的 API Key。
     * 与 DSH Android (`@deepseek-ai/dsh-web-search-deepseek`) 完全一致：
     * 即使当前正在用小米 MiMo 或反代 Gemini 聊天，只要已保存的服务商里有 DeepSeek Key，
     * 就直接拿来调用 DeepSeek 官方原生搜索端点 `https://api.deepseek.com/anthropic/v1/messages`。
     */
    private fun resolveDeepSeekSearchKey(config: SearchConfig, provider: ProviderConfig): String {
        if (config.deepseekApiKey.isNotBlank()) return config.deepseekApiKey.trim()
        if (provider.id.contains("deepseek", ignoreCase = true) ||
            provider.baseUrl.contains("api.deepseek.com", ignoreCase = true)
        ) {
            if (provider.apiKey.isNotBlank()) return provider.apiKey.trim()
        }
        return ""
    }

    // ------------------------------------------------- 服务商原生搜索（1:1 复刻 DSH Android）

    /**
     * 1:1 复刻 `DeepSeek Harness Android.apk` (`@deepseek-ai/dsh-web-search-deepseek`)：
     *
     * 1. **首选 DeepSeek 官方独立搜索端点**：
     *    - 固定地址：`https://api.deepseek.com/anthropic/v1/messages`（独立于聊天的 `/v1/chat/completions`）
     *    - 固定模型：`deepseek-v4-flash`（独立于当前聊天选中的模型，如 `mimo-v2-pro` 或 `deepseek-chat`）
     *    - 凭据复用：自动读取已保存的 `DEEPSEEK_API_KEY`，因此哪怕当前用小米 MiMo 或反代 Gemini 聊天，
     *      也能像在 DSH Android 里一样驱动 DeepSeek 官方云端搜索！
     * 2. **次选当前 Anthropic 反代端点**：
     *    - 若未配置 DeepSeek Key 且当前服务商为 Anthropic 协议，则尝试向当前服务商的 `/v1/messages`
     *      发送 `web_search_20250305` 请求。
     * 3. **交给调用方兜底**：
     *    - 这里只返回"原生服务端搜索结果"，拿不到就返回空列表；
     *      由 `search()` 的 NATIVE 分支统一降级到内置免密钥管道（只跑一次）。
     */
    private fun searchNative(
        query: String,
        config: SearchConfig,
        provider: ProviderConfig
    ): List<SearchResult> {
        val dsKey = resolveDeepSeekSearchKey(config, provider)

        // 路径 1：存在 DeepSeek API Key -> 1:1 走 DSH Android 的 DeepSeekSearchProvider
        if (dsKey.isNotBlank()) {
            val dshAttempt = runCatching {
                executeAnthropicWebSearch(
                    endpoint = DEEPSEEK_DEFAULT_SEARCH_ENDPOINT,
                    apiKey = dsKey,
                    model = DEEPSEEK_DEFAULT_SEARCH_MODEL,
                    maxTokens = DEEPSEEK_DEFAULT_MAX_TOKENS,
                    query = query,
                    maxUses = config.maxResults.coerceIn(1, 10)
                )
            }
            val dshResults = dshAttempt.getOrNull()
            if (!dshResults.isNullOrEmpty()) {
                return dshResults.take(config.maxResults)
            }
        }

        // 路径 2：如果当前服务商本身是 Anthropic 协议（如反代 Gemini / Claude），尝试调其自身的 /messages
        if (provider.protocolType == com.treechat.workbench.network.protocol.ProtocolType.ANTHROPIC_MESSAGES &&
            provider.apiKey.isNotBlank()
        ) {
            val rawBase = provider.baseUrl.trim().trimEnd('/')
            val endpoint = if (rawBase.contains("api.deepseek.com", ignoreCase = true)) {
                DEEPSEEK_DEFAULT_SEARCH_ENDPOINT
            } else {
                messagesEndpoint(rawBase)
            }
            if (endpoint.isNotBlank()) {
                val model = if (endpoint == DEEPSEEK_DEFAULT_SEARCH_ENDPOINT) {
                    DEEPSEEK_DEFAULT_SEARCH_MODEL
                } else {
                    provider.selectedModelId
                }
                val providerAttempt = runCatching {
                    executeAnthropicWebSearch(
                        endpoint = endpoint,
                        apiKey = provider.apiKey.trim(),
                        model = model,
                        maxTokens = DEEPSEEK_DEFAULT_MAX_TOKENS,
                        query = query,
                        maxUses = config.maxResults.coerceIn(1, 10)
                    )
                }
                val providerResults = providerAttempt.getOrNull()
                if (!providerResults.isNullOrEmpty()) {
                    return providerResults.take(config.maxResults)
                }
            }
        }

        // 路径 3：返回空列表，由调用方（search 的 NATIVE 分支）统一降级到内置免密钥管道。
        //
        // 这里刻意**不再**直接调用 fallbackBuiltinSearch。旧写法是：searchNative 内部先跑
        // 一整遍降级管道，失败后 search() 的 NATIVE 分支又跑一遍 —— 同一次提问最多要发
        // 两轮全部检索请求。网络受限时这正好表现为"转半天圈才吐出第一条结果"。
        // 管道现在只由 search() 触发一次。
        return emptyList()
    }

    /**
     * 发送与 `@deepseek-ai/dsh-web-search-deepseek` 完全一致的 Anthropic Messages 检索报文并解析响应。
     */
    private fun executeAnthropicWebSearch(
        endpoint: String,
        apiKey: String,
        model: String,
        maxTokens: Int,
        query: String,
        maxUses: Int
    ): List<SearchResult> {
        val payload = JsonObject().apply {
            addProperty("model", model)
            addProperty("max_tokens", maxTokens)
            add(
                "messages",
                JsonArray().apply {
                    add(
                        JsonObject().apply {
                            addProperty("role", "user")
                            add(
                                "content",
                                JsonArray().apply {
                                    add(
                                        JsonObject().apply {
                                            addProperty("type", "text")
                                            addProperty(
                                                "text",
                                                "Perform a web search for the query: $query"
                                            )
                                        }
                                    )
                                }
                            )
                        }
                    )
                }
            )
            add(
                "tools",
                JsonArray().apply {
                    add(
                        JsonObject().apply {
                            addProperty("type", "web_search_20250305")
                            addProperty("name", "web_search")
                            addProperty("max_uses", maxUses)
                        }
                    )
                }
            )
        }

        val request = Request.Builder()
            .url(endpoint)
            .addHeader("x-api-key", apiKey)
            .addHeader("authorization", "Bearer $apiKey")
            .addHeader("anthropic-version", DEEPSEEK_DEFAULT_API_VERSION)
            .addHeader("content-type", "application/json")
            .addHeader("accept", "application/json")
            .addHeader("user-agent", DSH_USER_AGENT)
            .post(payload.toString().toRequestBody(jsonMediaType))
            .build()

        val body = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val detail = try {
                    response.body?.string()?.take(180)
                } catch (e: Exception) {
                    null
                }
                error("原生搜索失败（HTTP ${response.code}）：$endpoint ${detail ?: ""}")
            }
            response.body?.string().orEmpty()
        }

        val root = JsonParser.parseString(body).asJsonObject
        val content = root.getAsJsonArray("content")
            ?: error("服务商未返回 content 块")

        // 1:1 复刻 DSH 的 citationSnippets(blocks)
        val snippets = mutableMapOf<String, String>()
        for (element in content) {
            val block = element.asJsonObject
            if (block.str("type") != "text") continue
            val citations = block.getAsJsonArray("citations") ?: continue
            for (citeElement in citations) {
                val cite = citeElement.asJsonObject
                val url = cite.str("url")
                val citedText = cite.str("cited_text")
                if (url.isNotEmpty() && citedText.isNotEmpty() && !snippets.containsKey(url)) {
                    snippets[url] = citedText
                }
            }
        }

        // 1:1 复刻 DSH 的 mapAnthropicResponse(response)
        val results = mutableListOf<SearchResult>()
        val seen = mutableSetOf<String>()

        for (element in content) {
            val block = element.asJsonObject
            if (block.str("type") != "web_search_tool_result") continue
            val items = block.getAsJsonArray("content") ?: continue
            for (itemElement in items) {
                val item = itemElement.asJsonObject
                if (item.str("type") != "web_search_result") continue
                val url = item.str("url")
                if (url.isEmpty() || !seen.add(url)) continue
                results.add(
                    SearchResult(
                        title = item.str("title").ifBlank { url },
                        url = url,
                        snippet = snippets[url].orEmpty()
                    )
                )
            }
        }

        return results
    }

    // ------------------------------------------------ 内置免配置降级管道 (搜狗/SearXNG池/Bing)

    /**
     * 内置**完全免密钥**搜索管道（当未配置任何 Key、或服务商网关不支持服务端搜索时兜底）。
     *
     * 顺序（都是公开端点，都不需要 Key）：
     * 1. **DuckDuckGo HTML 端点**（`html.duckduckgo.com/html/`，全球通用，先试 POST 再试 GET）；
     * 2. **搜狗移动端**：国内直连、毫秒级返回，海外网络通常不通；
     * 3. **SearXNG 公共节点池**：4 个实例逐个降级（详见 [searchPublicSearxng]）；
     * 4. **Bing 结果页**：`cn.bing.com` 国内外一般可达；
     * 5. **Wikipedia 公开 API**：作为"解释型问题"的最后知识兜底（无需 Key，见 [searchWikipedia]）。
     *
     * ## 三个刻意的设计
     * - **总时间预算** [PIPELINE_BUDGET_MS]：每个端点起自己的超时，但整条管道共享一个
     *   时间预算。否则"6 个端点各自超时"最坏能拖到一分钟，用户早就以为卡死了。
     *   预算用尽就停止剩余端点，并把"未尝试"如实写进失败原因。
     * - **不假装成功**：只要有端点正常响应（哪怕 0 条），就返回空列表（真实的零结果）；
     *   若**一个都没成功**，抛出异常并附上每个端点的失败原因 —— 由上层报成"检索失败"。
     * - **顺序按"大概率最快可用"排**：国内网络下 DuckDuckGo 会先失败（约 5 秒），
     *   随后搜狗几乎必然命中，总耗时仍可接受；海外网络则第一步就命中。
     */
    private fun fallbackBuiltinSearch(query: String, limit: Int): List<SearchResult> {
        val deadline = System.currentTimeMillis() + PIPELINE_BUDGET_MS
        val failures = mutableListOf<String>()
        var attempted = 0
        var anyChannelSucceeded = false

        /** 依次尝试一个通道：命中即返回；失败/空则记录原因继续。 */
        fun tryChannel(name: String, block: () -> List<SearchResult>): List<SearchResult>? {
            if (System.currentTimeMillis() >= deadline) {
                failures += "$name → 未尝试（已超出本次检索时间预算）"
                return null
            }
            attempted++
            val outcome = runCatching { block() }
            val results = outcome.getOrNull()
            if (!results.isNullOrEmpty()) {
                android.util.Log.i("TreeChatSearch", "免密钥管道 [$name] 命中 ${results.size} 条")
                return results
            }
            if (outcome.isSuccess) {
                anyChannelSucceeded = true
                failures += "$name → 返回 0 条结果"
            } else {
                failures += "$name → ${outcome.exceptionOrNull()?.message ?: "未知错误"}"
            }
            return null
        }

        // 1) DuckDuckGo HTML/Lite
        tryChannel("DuckDuckGo") { searchDuckDuckGo(query, limit) }?.let { return it }

        // 2) 搜狗移动端（国内直连最快）
        tryChannel("搜狗") { searchSogou(query, limit) }?.let { return it }

        // 3) SearXNG 公共节点池（共享同一个总预算）
        tryChannel("SearXNG 节点池") { searchPublicSearxng(query, limit, deadline) }?.let { return it }

        // 4) Bing
        tryChannel("Bing") { searchBing(query, limit) }?.let { return it }

        // 5) Wikipedia 知识兜底
        tryChannel("Wikipedia") { searchWikipedia(query, limit) }?.let { return it }

        if (attempted > 0 && !anyChannelSucceeded) {
            error("联网检索全部失败（共尝试 $attempted 个免密钥通道）：${failures.joinToString("；")}")
        }
        return emptyList()
    }

    /** 搜狗移动端网页与结构化数据提取（按目标 URL 聚合标题与长摘要，过滤站内推荐词标签） */
    private fun searchSogou(query: String, limit: Int): List<SearchResult> {
        val url = "https://m.sogou.com/web/searchList.jsp?keyword=" +
            java.net.URLEncoder.encode(query, "UTF-8")
        val html = getHtml(url, mobileUa = true)
        val results = mutableListOf<SearchResult>()
        val seenUrls = mutableSetOf<String>()

        // 策略 1：按落地 URL 分组聚合标题与正文摘要，过滤搜狗站内推荐词链接
        val linkRegex = Regex("""<a[^>]*href=['"]([^'"]*tc\?[^'"]*url=([^'"&]+)[^'"]*)['"][^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
        val groupedByUrl = LinkedHashMap<String, MutableList<String>>()
        for (match in linkRegex.findAll(html)) {
            val encodedTargetUrl = match.groupValues[2]
            val anchorText = stripTags(match.groupValues[3])
            val targetUrl = try {
                java.net.URLDecoder.decode(encodedTargetUrl, "UTF-8")
            } catch (e: Exception) {
                ""
            }
            if (targetUrl.startsWith("http") &&
                !targetUrl.contains("sogou.com/web/searchList") &&
                anchorText.length >= 6
            ) {
                val list = groupedByUrl.getOrPut(targetUrl) { mutableListOf() }
                if (!list.contains(anchorText)) {
                    list.add(anchorText)
                }
            }
        }

        for ((targetUrl, texts) in groupedByUrl) {
            if (texts.isEmpty()) continue
            val title = texts.first().take(80)
            val snippet = if (texts.size > 1) {
                texts.drop(1).joinToString(" | ")
            } else {
                texts.first()
            }
            if (seenUrls.add(targetUrl)) {
                results.add(SearchResult(title, targetUrl, snippet))
            }
            if (results.size >= limit) break
        }

        // 策略 2：若仍不足，从页面内嵌的 application/json 块中补充
        if (results.size < limit) {
            val jsonScriptRegex = Regex("""<script[^>]*type=['"]application/json['"][^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
            for (match in jsonScriptRegex.findAll(html)) {
                val scriptContent = match.groupValues[1]
                try {
                    val parsed = JsonParser.parseString(scriptContent)
                    if (parsed.isJsonObject) {
                        val root = parsed.asJsonObject
                        val title = stripTags(root.str("title"))
                        val summary = stripTags(root.str("summary"))
                        val itemUrl = root.str("h5Url").ifBlank { root.str("url") }
                        if (title.isNotBlank() && summary.isNotBlank() && itemUrl.isNotBlank() && seenUrls.add(itemUrl)) {
                            results.add(SearchResult(title, itemUrl, summary))
                        }
                    }
                } catch (_: Exception) {
                }
                if (results.size >= limit) break
            }
        }

        return results.take(limit)
    }

    /**
     * 遍历内置 SearXNG 公共节点池，逐个降级（Failover）。
     *
     * 关于"失败"的语义（重要，与旧实现不同）：
     * 旧实现在全部节点失败后 `return emptyList()` —— 表面上是"搜索成功、只是没结果"，
     * 实际是"四个节点全都 403/超时/未开放 JSON"。这会让用户以为是"网上没有这资料"，
     * 排查方向完全被带偏。现在区分两种情况：
     * - **有节点正常响应**（HTTP 200）但结果为空 → 这是真实的"零结果"，返回空列表；
     * - **没有任何节点响应成功** → 抛出异常，把每个节点的失败原因拼进消息里
     *   （`https://searx.be → HTTP 403；…`），用户一眼能看出是节点被墙还是没开 format=json。
     *
     * @param deadline 本次检索的总时间预算（毫秒时间戳），到点就停止轮询剩余节点，
     *   避免节点池本身变成新的耗时来源。默认不限时（探针场景需要完整结论）。
     */
    private fun searchPublicSearxng(
        query: String,
        limit: Int,
        deadline: Long = Long.MAX_VALUE
    ): List<SearchResult> {
        val failures = mutableListOf<String>()
        var anyNodeResponded = false
        var cutByDeadline = false

        for (node in defaultSearxngNodes) {
            if (System.currentTimeMillis() >= deadline) {
                cutByDeadline = true
                failures += "$node → 未尝试（已超出本次检索时间预算）"
                break
            }
            try {
                val url = "$node/search?q=" + java.net.URLEncoder.encode(query, "UTF-8") +
                    "&format=json&safesearch=0"
                val request = Request.Builder()
                    .url(url)
                    .addHeader("User-Agent", browserUa)
                    .addHeader("Accept", "application/json")
                    .get()
                    .build()
                val body = searxngClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) error("HTTP ${response.code}")
                    response.body?.string().orEmpty()
                }
                // 有些实例对外只开 HTML 版，对 format=json 返回 HTML 页面；
                // 这时 JsonParser 会抛异常，被下面的 catch 记成"该实例未开放 JSON"。
                val root = JsonParser.parseString(body).asJsonObject
                val array = root.getAsJsonArray("results") ?: continue
                anyNodeResponded = true
                val items = array.take(limit).mapNotNull { element ->
                    val obj = element.asJsonObject
                    val title = obj.str("title")
                    val link = obj.str("url")
                    if (title.isBlank() || link.isBlank()) null
                    else SearchResult(stripTags(title), link, stripTags(obj.str("content")))
                }
                if (items.isNotEmpty()) {
                    android.util.Log.i("TreeChatSearch", "SearXNG 节点 [$node] 成功命中 ${items.size} 条结果")
                    return items
                }
            } catch (e: Exception) {
                failures += "$node → ${e.message ?: e::class.java.simpleName}"
                android.util.Log.w("TreeChatSearch", "SearXNG 节点 [$node] 请求失败，尝试下一个节点: ${e.message}")
            }
        }

        if (!anyNodeResponded) {
            if (cutByDeadline) {
                error("SearXNG 公共节点池在时间预算内无一可用：${failures.joinToString("；")}")
            }
            error("SearXNG 公共节点池全部失败：${failures.joinToString("；")}")
        }
        return emptyList()
    }

    // ------------------------------------------------------------------ Bing

    /**
     * Bing 结果页抓取（免密钥）。
     *
     * 解析全部交给 [HtmlSearchResultParser.parseBing]：它除了 `li.b_algo` 结果块，
     * 还会把 Bing 的跳转链接（`bing.com/ck/a?…&u=a1<base64url>`）还原成真实 URL ——
     * 旧实现把这种跳转地址原样喂给模型，模型点不开、也没法引用。
     */
    private fun searchBing(query: String, limit: Int): List<SearchResult> {
        val url = "https://cn.bing.com/search?q=" + java.net.URLEncoder.encode(query, "UTF-8")
        val html = getHtml(url)
        val results = HtmlSearchResultParser.parseBing(html)
        if (results.isNotEmpty()) return results.take(limit)

        // 空结果时换 www.bing.com 再试一次：cn 版偶发返回极简页（无 b_algo）。
        val fallbackUrl = "https://www.bing.com/search?q=" +
            java.net.URLEncoder.encode(query, "UTF-8") + "&setlang=zh-CN"
        return HtmlSearchResultParser.parseBing(getHtml(fallbackUrl)).take(limit)
    }

    // ------------------------------------------------------------ DuckDuckGo

    /**
     * DuckDuckGo **免密钥**检索（无需任何 Key、无需注册）。
     *
     * 依次尝试三条路，任一拿到结果就返回：
     * 1. `https://html.duckduckgo.com/html/` **POST**：结构最稳（`result__a` / `result__snippet`），
     *    且刻意用 POST —— DDG 对该端点的 GET 更容易被判为爬虫而返回验证码页；
     * 2. 同端点 **GET**：个别网络/代理下 POST 被中间设备拦掉，GET 反而通；
     * 3. `https://lite.duckduckgo.com/lite/`：纯表格布局，改版频率最低，作为最后一道。
     *
     * 三条都失败（或都返回验证码/空页）时**抛出异常**，由调用方决定是继续降级还是上报失败。
     * 解析交给 [HtmlSearchResultParser]，它负责把 `//duckduckgo.com/l/?uddg=…` 还原成真实 URL。
     */
    private fun searchDuckDuckGo(query: String, limit: Int): List<SearchResult> {
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val region = if (HtmlSearchResultParser.looksCjk(query)) "cn-zh" else "wt-wt"
        val notes = mutableListOf<String>()

        // 1) html 端点，POST
        val htmlEndpoint = "https://html.duckduckgo.com/html/"
        val postOutcome = runCatching {
            HtmlSearchResultParser.parseDuckDuckGoHtml(
                httpPostForm(
                    url = htmlEndpoint,
                    form = linkedMapOf("q" to query, "kl" to region, "b" to ""),
                    ua = browserUa,
                    referer = "https://html.duckduckgo.com/"
                )
            )
        }
        postOutcome.getOrNull()?.takeIf { it.isNotEmpty() }?.let {
            android.util.Log.i("TreeChatSearch", "DuckDuckGo html(POST) 命中 ${it.size} 条")
            return it.take(limit)
        }
        postOutcome.exceptionOrNull()?.let { notes += "html(POST)：${it.message}" }

        // 2) html 端点，GET
        val getOutcome = runCatching {
            HtmlSearchResultParser.parseDuckDuckGoHtml(
                getHtml(
                    "$htmlEndpoint?q=$encoded&kl=$region",
                    referer = "https://html.duckduckgo.com/"
                )
            )
        }
        getOutcome.getOrNull()?.takeIf { it.isNotEmpty() }?.let {
            android.util.Log.i("TreeChatSearch", "DuckDuckGo html(GET) 命中 ${it.size} 条")
            return it.take(limit)
        }
        getOutcome.exceptionOrNull()?.let { notes += "html(GET)：${it.message}" }

        // 3) lite 端点
        val liteOutcome = runCatching {
            HtmlSearchResultParser.parseDuckDuckGoLite(
                getHtml(
                    "https://lite.duckduckgo.com/lite/?q=$encoded",
                    referer = "https://lite.duckduckgo.com/"
                )
            )
        }
        liteOutcome.getOrNull()?.takeIf { it.isNotEmpty() }?.let {
            android.util.Log.i("TreeChatSearch", "DuckDuckGo lite 命中 ${it.size} 条")
            return it.take(limit)
        }
        liteOutcome.exceptionOrNull()?.let { notes += "lite：${it.message}" }

        if (notes.isEmpty()) {
            // 三个端点都拿到了 HTTP 200，但一条结果都没解析出来：
            // 最可能是被风控换成了验证码/无结果页，而不是我们选错了 class 名。
            error("DuckDuckGo 返回了页面但未解析出结果（可能被风控拦截或页面结构已改版）")
        }
        error("DuckDuckGo 全部端点失败：${notes.joinToString("；")}")
    }

    // -------------------------------------------------------------- SearXNG

    private fun searchSearxng(query: String, config: SearchConfig): List<SearchResult> {
        val base = config.searxngBaseUrl.trim().trimEnd('/')
        require(base.isNotBlank()) { "未填写 SearXNG 实例地址" }

        val url = "$base/search?q=" + java.net.URLEncoder.encode(query, "UTF-8") +
            "&format=json&safesearch=0"
        val body = getHtml(url)
        val root = JsonParser.parseString(body).asJsonObject
        val array = root.getAsJsonArray("results") ?: return emptyList()

        return array.take(config.maxResults).mapNotNull { element ->
            val obj = element.asJsonObject
            val title = obj.str("title")
            val link = obj.str("url")
            if (title.isBlank() || link.isBlank()) null
            else SearchResult(title, link, stripTags(obj.str("content")))
        }
    }

    // ------------------------------------------------------------ Wikipedia

    /**
     * Wikipedia（维基百科）**公开 Action API** 兜底检索 —— **无需任何 Key**。
     *
     * 端点：`https://<lang>.wikipedia.org/w/api.php?action=query&list=search&…&format=json`
     * 这是 MediaWiki 面向第三方应用公开的只读接口，匿名即可调用（只需带 User-Agent）。
     * 它返回的是**百科条目**，不是网页检索结果：没有站点排名、没有时效性
     * （新闻/行情类问题基本帮不上），所以它只作为管道最后一环，
     * 专门用于"什么是 X / X 是什么 / 解释一下 X"这类**解释型问题**。
     *
     * 先中文后英文：中文条目覆盖不足时（如技术名词），英文 Wikipedia 往往有更完整的条目。
     *
     * 注意：`zh.wikipedia.org` / `en.wikipedia.org` 在**中国大陆直连不可达**，
     * 该通道只对海外网络或已开代理的用户有效；连不上时这里的超时会被计入
     * 管道总预算，因此它被放在最后一步。
     */
    private fun searchWikipedia(query: String, limit: Int): List<SearchResult> {
        val hosts = listOf("zh.wikipedia.org", "en.wikipedia.org")
        val notes = mutableListOf<String>()

        for (host in hosts) {
            val outcome = runCatching {
                val url = "https://$host/w/api.php?action=query&list=search&srsearch=" +
                    java.net.URLEncoder.encode(query, "UTF-8") +
                    "&srlimit=$limit&srprop=snippet%7Ctitlesnippet&utf8=1&format=json&formatversion=2"
                val body = httpGet(
                    url = url,
                    ua = browserUa,
                    referer = "https://$host/",
                    accept = "application/json"
                )
                val root = JsonParser.parseString(body).asJsonObject
                val array = root.getAsJsonObject("query")?.getAsJsonArray("search")
                    ?: return@runCatching emptyList()
                array.take(limit).mapNotNull { element ->
                    val obj = element.asJsonObject
                    val title = stripTags(obj.str("title"))
                    if (title.isBlank()) return@mapNotNull null
                    // pageid 版永久链接最稳（标题重定向、空格/编码都不会出问题）；
                    // 拿不到 pageid 才退回 /wiki/<标题>。
                    val pageId = if (obj.has("pageid") && !obj.get("pageid").isJsonNull) {
                        obj.get("pageid").asInt
                    } else {
                        0
                    }
                    val link = if (pageId > 0) {
                        "https://$host/?curid=$pageId"
                    } else {
                        "https://$host/wiki/" + java.net.URLEncoder.encode(
                            title.replace(' ', '_'),
                            "UTF-8"
                        )
                    }
                    SearchResult(title, link, stripTags(obj.str("snippet")))
                }
            }
            val items = outcome.getOrNull()
            if (!items.isNullOrEmpty()) {
                android.util.Log.i("TreeChatSearch", "Wikipedia [$host] 命中 ${items.size} 条")
                return items
            }
            outcome.exceptionOrNull()?.let { notes += "$host → ${it.message}" }
        }

        if (notes.isNotEmpty()) error("Wikipedia 兜底检索失败：${notes.joinToString("；")}")
        return emptyList()
    }

    // --------------------------------------------------------------- Tavily

    private fun searchTavily(query: String, config: SearchConfig): List<SearchResult> {
        require(config.apiKey.isNotBlank()) { "未填写 Tavily API Key" }

        val payload = JsonObject().apply {
            addProperty("api_key", config.apiKey)
            addProperty("query", query)
            addProperty("max_results", config.maxResults)
            addProperty("search_depth", "basic")
        }

        val request = Request.Builder()
            .url("https://api.tavily.com/search")
            .addHeader("Content-Type", "application/json")
            .post(payload.toString().toRequestBody(jsonMediaType))
            .build()

        val body = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                error("Tavily 返回 HTTP ${response.code}")
            }
            response.body?.string().orEmpty()
        }

        val root = JsonParser.parseString(body).asJsonObject
        val array = root.getAsJsonArray("results") ?: return emptyList()
        return array.take(config.maxResults).mapNotNull { element ->
            val obj = element.asJsonObject
            val title = obj.str("title")
            val link = obj.str("url")
            if (title.isBlank() || link.isBlank()) null
            else SearchResult(title, link, obj.str("content"))
        }
    }

    // ------------------------------------------------------------- 公共部分

    private val mobileUa =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/124.0.0.0 Mobile Safari/537.36"

    /**
     * 抓取一个 HTML 页面（搜索结果的公开网页）。
     *
     * `referer` 留空时按域名猜一个：不少搜索引擎会检查 Referer，
     * 缺失或被判为"外站跳转"时会直接返回验证码页。
     */
    private fun getHtml(
        url: String,
        mobileUa: Boolean = false,
        referer: String? = null
    ): String {
        val ua = if (mobileUa) this.mobileUa else browserUa
        val effectiveReferer = referer ?: when {
            url.contains("sogou.com") -> "https://m.sogou.com/"
            url.contains("duckduckgo.com") -> "https://html.duckduckgo.com/"
            else -> "https://cn.bing.com/"
        }
        return httpGet(
            url = url,
            ua = ua,
            referer = effectiveReferer,
            accept = "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
            mobileUa = mobileUa
        )
    }

    /** 统一的 GET 取文本。走 [fastClient]（快速失败），失败时抛 `error(...)`。 */
    private fun httpGet(
        url: String,
        ua: String,
        referer: String?,
        accept: String,
        mobileUa: Boolean = false
    ): String {
        val builder = Request.Builder()
            .url(url)
            .addHeader("User-Agent", ua)
            .addHeader("Accept", accept)
            .addHeader("Accept-Language", "zh-CN,zh;q=0.9,en-US;q=0.8,en;q=0.7")
            .addHeader("sec-ch-ua-mobile", if (mobileUa) "?1" else "?0")
            .addHeader("sec-fetch-dest", "document")
            .addHeader("sec-fetch-mode", "navigate")
            .get()
        if (!referer.isNullOrBlank()) builder.addHeader("Referer", referer)

        return fastClient.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                error("搜索请求失败：HTTP ${response.code}")
            }
            response.body?.string().orEmpty()
        }
    }

    /**
     * 表单 POST（`application/x-www-form-urlencoded`）。
     *
     * DuckDuckGo 的 html 端点用 POST 比 GET 稳：GET 更容易被它判成爬虫而返回
     * 验证码页或空结果页。FormBody 会自动带上正确的 Content-Type 与 Content-Length。
     */
    private fun httpPostForm(
        url: String,
        form: Map<String, String>,
        ua: String,
        referer: String?
    ): String {
        val bodyBuilder = FormBody.Builder()
        form.forEach { (key, value) -> bodyBuilder.add(key, value) }

        val builder = Request.Builder()
            .url(url)
            .addHeader("User-Agent", ua)
            .addHeader("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .addHeader("Accept-Language", "zh-CN,zh;q=0.9,en-US;q=0.8,en;q=0.7")
            .addHeader("Origin", "https://html.duckduckgo.com")
            .addHeader("sec-fetch-dest", "document")
            .addHeader("sec-fetch-mode", "navigate")
            .post(bodyBuilder.build())
        if (!referer.isNullOrBlank()) builder.addHeader("Referer", referer)

        return fastClient.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                error("搜索请求失败：HTTP ${response.code}")
            }
            response.body?.string().orEmpty()
        }
    }

    companion object {
        /**
         * 与 `DeepSeek Harness Android.apk` (`@deepseek-ai/dsh-web-search-deepseek`)
         * 100% 一致的官方搜索端点、模型与版本常量。
         */
        const val DEEPSEEK_DEFAULT_SEARCH_ENDPOINT = "https://api.deepseek.com/anthropic/v1/messages"
        const val DEEPSEEK_DEFAULT_SEARCH_MODEL = "deepseek-v4-flash"
        const val DEEPSEEK_DEFAULT_API_VERSION = "2023-06-01"
        const val DEEPSEEK_DEFAULT_MAX_TOKENS = 4096
        const val DSH_USER_AGENT = "deepseek-harness/0.0.1"

        /**
         * 免密钥降级管道的**总时间预算**（毫秒）。
         *
         * 管道里有 6 个左右的公开端点，如果各自独立超时，最坏情况会拖到一分钟以上，
         * 而用户在界面上只看到"正在检索…"转圈。给整条管道一个共享预算后，
         * 到点就停止剩余端点，并把"未尝试（超出时间预算）"如实写进失败原因。
         * 20 秒是取舍：足够国内网络走完 DuckDuckGo(失败) → 搜狗(命中)，
         * 又不至于让用户觉得卡死。
         */
        const val PIPELINE_BUDGET_MS = 20_000L

        /** 带正文格式化时，抓取几条结果的正文（前 N 条）。 */
        const val PAGE_EXCERPT_COUNT = 3

        /** 单条正文摘录的字符上限（超出由 [fetchAndExtract] 截断并标注）。 */
        const val PAGE_EXCERPT_MAX_CHARS = 2500

        /** 单条网页抓取超时（毫秒）。 */
        const val PAGE_FETCH_TIMEOUT_MS = 6_000L

        /** 整批网页抓取的总时间预算（毫秒）：到点未回的条目如实标注超时。 */
        const val PAGE_FETCH_BUDGET_MS = 10_000L

        /** 整批预算到点后，对未返回条目使用的如实标注（含括号，直接拼在「正文：」之后）。 */
        const val FETCH_TIMEOUT_NOTE = "（抓取超时，未取到正文）"
    }

    private fun JsonObject.str(key: String): String =
        if (has(key) && !get(key).isJsonNull) get(key).asString else ""

    /**
     * 粗暴去标签：搜索结果标题里的标签很少嵌套，够用。
     * 统一委托给 [HtmlSearchResultParser.stripTags]，与解析器共用同一套实体解码规则
     * （避免这里漏解 `&#x27;` 之类的十六进制实体，标题里出现 `&#39;` 这种字面量）。
     */
    private fun stripTags(raw: String): String = HtmlSearchResultParser.stripTags(raw)

    /**
     * 把搜索结果压成一段文本喂回给模型。
     *
     * 同时明确要求模型在回答中标注来源编号，这样前端才能渲染出
     * 类似 DeepSeek 的"来源列表"。
     */
    fun formatForModel(results: List<SearchResult>): String {
        if (results.isEmpty()) return "（搜索无结果，请基于已有知识回答并说明未能检索到资料）"
        return buildString {
            appendLine("以下是实时联网检索到的资料，请优先依据它们回答：")
            appendLine()
            results.forEachIndexed { index, item ->
                appendLine("[${index + 1}] ${item.title}")
                appendLine("URL: ${item.url}")
                if (item.snippet.isNotBlank()) appendLine("摘要: ${item.snippet}")
                appendLine()
            }
            append("请在回答中用到某条资料时，用 [编号] 的形式标注来源。")
        }
    }

    // ------------------------------------------------- 带正文的检索结果格式化

    /**
     * 旧版 [formatForModel] 只把"标题 + URL + 一句摘要"喂给模型，答案因此写得空洞 ——
     * 摘要里没有事实、没有数字、没有上下文。这里在**编号与旧版完全一致**的前提下，
     * 给前 [PAGE_EXCERPT_COUNT] 条结果补上真实网页正文摘录，模型才答得出细节。
     *
     * ## 并发与预算
     * - 前 3 条**并发**抓取（`coroutineScope` + `async`），彼此不互相等；
     * - 单条超时 [PAGE_FETCH_TIMEOUT_MS]（6 秒），整批再套一层
     *   [PAGE_FETCH_BUDGET_MS]（10 秒）的 `withTimeoutOrNull` 硬预算；
     * - 到点仍没回来的条目，如实写「抓取超时，未取到正文」，**绝不编造正文**。
     *
     * ## 诚实铁律
     * 抓取失败写 `正文：未取到（原因）`，页面无正文写 `正文：未取到（页面无可读正文）`。
     * 只有真的取到正文才会出现「正文摘录」。没有正文的条目也不会被删掉 ——
     * 标题与 URL 仍留给模型引用。
     *
     * @param results 搜索结果（编号沿用传入顺序，模型引用 `[编号]` 的语义不变）
     * @param context 预留参数（当前正文抽取走纯 OkHttp，不需要 Context；保留以便将来
     *                接 WebView 兜底渲染 JS 页面时不必改调用方签名）
     */
    suspend fun formatForModelWithPages(
        results: List<SearchResult>,
        context: android.content.Context? = null
    ): String {
        // 零结果时与旧实现给出**同一段文案**，避免出现两种"没搜到"的措辞。
        if (results.isEmpty()) return formatForModel(results)

        val pages = fetchPageExcerpts(results)

        return buildString {
            appendLine("以下是实时联网检索到的资料，请优先依据它们回答：")
            appendLine()
            results.forEachIndexed { index, item ->
                // 这四行与旧 formatForModel 逐字一致，保证编号/字段语义不变。
                appendLine("[${index + 1}] ${item.title}")
                appendLine("URL: ${item.url}")
                if (item.snippet.isNotBlank()) appendLine("摘要: ${item.snippet}")

                // page 为 null 表示该条不在前 N 条、没有抓正文，保持原有简洁形态。
                val page = pages[index]
                if (page != null) {
                    val err = page.error
                    when {
                        err == null && page.text.isNotBlank() -> {
                            appendLine("正文摘录：")
                            appendLine(page.text)
                        }
                        // 整批预算到点：如实写"超时"，与单页抓取失败区分开。
                        err == FETCH_TIMEOUT_NOTE -> appendLine("正文：$FETCH_TIMEOUT_NOTE")
                        err != null -> appendLine("正文：未取到（$err）")
                        else -> appendLine("正文：未取到（页面无可读正文）")
                    }
                }
                appendLine()
            }
            append("请在回答中用到某条资料时，用 [编号] 的形式标注来源。")
        }
    }

    /**
     * 并发抓取前 [PAGE_EXCERPT_COUNT] 条结果并返回"下标 → 抓取结果"。
     *
     * 完成一条就立刻写进 [done]（并发 Map），这样即使整批预算到点触发取消，
     * **已经回来的条目也不会丢**，只有真正没回来的才会被判为超时。
     */
    private suspend fun fetchPageExcerpts(
        results: List<SearchResult>
    ): Map<Int, PageContent> {
        val targets = results.take(PAGE_EXCERPT_COUNT)
        if (targets.isEmpty()) return emptyMap()

        val done = java.util.concurrent.ConcurrentHashMap<Int, PageContent>()
        withTimeoutOrNull(PAGE_FETCH_BUDGET_MS) {
            coroutineScope {
                targets.mapIndexed { index, item ->
                    async(Dispatchers.IO) {
                        try {
                            done[index] = fetchAndExtract(
                                url = item.url,
                                maxChars = PAGE_EXCERPT_MAX_CHARS,
                                timeoutMs = PAGE_FETCH_TIMEOUT_MS
                            )
                        } catch (cancel: CancellationException) {
                            // 整批预算到点导致的取消：如实记成超时，再把取消上抛，
                            // 保证 coroutineScope 能正常结束、不拖住预算。
                            done[index] = PageContent(item.url, "", "", FETCH_TIMEOUT_NOTE)
                            throw cancel
                        } catch (e: Exception) {
                            done[index] = PageContent(
                                item.url,
                                "",
                                "",
                                e.message ?: "抓取失败"
                            )
                        }
                    }
                }.joinAll()
            }
        }
        return done.toMap()
    }
}
