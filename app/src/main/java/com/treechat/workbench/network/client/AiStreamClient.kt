package com.treechat.workbench.network.client

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.treechat.workbench.network.protocol.AiModelInfo
import com.treechat.workbench.network.protocol.PromptMessage
import com.treechat.workbench.network.protocol.ProtocolType
import com.treechat.workbench.network.protocol.ProviderConfig
import com.treechat.workbench.network.protocol.ReasoningEffort
import com.treechat.workbench.network.search.SearchConfig
import com.treechat.workbench.network.search.SearchResult
import com.treechat.workbench.network.search.WebSearchClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.ConnectionPool
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** 一次对话过程中向前端抛出的事件。 */
sealed interface ChatEvent {
    /** 正文增量。 */
    data class Text(val delta: String) : ChatEvent

    /**
     * 思考过程（推理链）增量。
     *
     * ## 为什么要单独做成一种事件
     * 不少"思考模型"（DeepSeek-R1、MIMO、Qwen 思考版等）在流式响应里
     * 用**独立字段**返回思考内容，最常见的是 OpenAI 兼容扩展
     * `delta.reasoning_content`。此前解析器只读 `delta.content`，
     * 于是这些思考文本被**静默丢弃** —— 用户看到的现象正是：
     * "开了思考，但界面上什么都看不到"，很容易误判成"模型没在思考"。
     *
     * ## 为什么不能混进正文
     * 思考过程与最终答案要分开显示：混在一起会让回答本身
     * 变得难以阅读，而且思考内容不该被当作答案引用。
     */
    data class Reasoning(val delta: String) : ChatEvent

    /** 模型要求联网检索。 */
    data class SearchStarted(val query: String) : ChatEvent

    /** 检索完成（error 非空表示失败）。 */
    data class SearchFinished(
        val query: String,
        val sources: List<SearchResult>,
        val error: String?
    ) : ChatEvent

    /** 整体失败。 */
    data class Failed(val message: String) : ChatEvent
}

data class ConnTestResult(val ok: Boolean, val message: String)

/**
 * 服务端**明确**的失败：HTTP 非 2xx 状态码，或流内 error 字段。
 *
 * 与普通 IOException（连接被掐、读超时、对端 reset —— 即"断流"）分开，
 * 是为了让自动续传只救"断流"这类**瞬时**故障：
 * 服务端都说了"你这个请求不行"（密钥错、参数非法、超限），
 * 重发一次只会得到同样的答复，白烧配额 —— 所以 [retryable] 为 false 时
 * 续传逻辑直接放行给上层报错。
 *
 * 例外：408/429/5xx 是网关自己的瞬时故障，标 [retryable] = true，
 * 值得再给一次机会。
 */
private class ServerErrorException(
    val retryable: Boolean,
    message: String
) : IOException(message)

class AiStreamClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectionPool(ConnectionPool(8, 5, TimeUnit.MINUTES))
        .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
        .connectTimeout(15, TimeUnit.SECONDS)
        // 读超时负责流空闲兜底（服务端完全不发字节且不关连接）。
        // 收到结束信号时在 executeRound 里显式 close() 立即收工。
        //
        // 为什么是 120 秒而不是早前的 15 秒：SSE 流里"服务端超过 15 秒没吐字节"
        // 是**正常现象** —— 思考模型在写正文前会长时间只发心跳、供应商排队、
        // 移动网络抖动一下都会造成几十秒的静默。15 秒的读超时会把这些正常静默
        // 判成断流，直接 SocketTimeoutException 掐掉整条连接，用户看到的正是
        // "吐到一半断掉"。120 秒只兜"服务端彻底死掉且不关连接"的极端情况；
        // 正常流式期间几乎触达不到（每次收到心跳/字节都会重置计时）。
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build(),
    private val searchClient: WebSearchClient = WebSearchClient()
) {
    private val gson = Gson()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    /** 工具调用最多往返几轮。超过就强行让模型收尾，避免死循环。 */
    private companion object {
        const val MAX_TOOL_ROUNDS = 3
        const val SEARCH_TOOL_NAME = "web_search"

        /**
         * 断流续传时带上的"已产出末尾片段"长度上限（字符）。
         *
         * 只带末尾而不是全文：衔接上文足够用，又不会让请求体随回答变长而翻倍。
         */
        const val RESUME_TAIL_CHARS = 600
    }

    /**
     * 构造"断流续传"提示词。
     *
     * ## 为什么必须显式要求"不要重复"
     * 模型看到自己的半截输出，最常见的反应是从头重述。若不加约束，
     * 界面里就会出现两遍前半段 —— 比断流本身更糟。
     * 所以指令同时钉死三件事：**从中断处接着写、不要重复、不要重启开头**。
     *
     * @param tail 已产出文本的末尾片段（调用方已截断到 [RESUME_TAIL_CHARS]）。
     */
    private fun buildResumePrompt(tail: String): String = """
        （系统提示：上一条回复因网络中断未能传输完毕。请**严格从中断处接着写**，
        直接续写后续内容；不要重复下面已输出的部分，不要重新开头，不要加任何过渡说明。
        以下是你已输出内容的末尾片段，仅供衔接上下文：）

        $tail
    """.trimIndent()

    // ========================================================================
    // 主入口：带工具调用循环的流式对话
    // ========================================================================

    fun runChat(
        provider: ProviderConfig,
        messages: List<PromptMessage>,
        systemPrompt: String?,
        searchConfig: SearchConfig
    ): Flow<ChatEvent> {
        // 本地大模型（NPU / llama.cpp / Vulkan）方案已按用户要求整体移除。
        // 若用户的历史配置里还留着这条本地服务商，**如实报错**，不要让它去请求
        // 那个假的 127.0.0.1:0 地址后抛一个看不懂的网络错误。
        if (provider.id == "local-npu-qwen" || provider.id == "local_npu_qwen") {
            return kotlinx.coroutines.flow.flowOf(
                ChatEvent.Failed(
                    "本地模型方案已移除（它会拖慢整机）。请在「设置」里改选一个云端服务商" +
                        "（DeepSeek / 通义 / Anthropic / OpenAI 等）并填写 API 密钥。"
                )
            )
        }

        return channelFlow {
            val out = this
            val activeCall = AtomicReference<Call?>(null)

        val job = launch(Dispatchers.IO) {
            // 整次对话是否已经用掉"自动续传"机会（只续一次，见 issueRoundResumable）。
            // 声明在 try 外面：下面把失败转成 ChatEvent.Failed 时要标注是否重试过。
            var resumeUsed = false
            try {
                val conversation = mutableListOf<JsonObject>()
                if (!systemPrompt.isNullOrBlank()) {
                    conversation.add(messageJson("system", systemPrompt))
                }
                messages.forEach {
                    conversation.add(messageJson(it.role, it.content, it.images))
                }

                var round = 0
                var finished = false

                // 工具开关是可退化的：不少模型/中转站并不支持 function calling，
                // 直接带着 tools 发过去会 400。遇到这种情况退化为普通对话重试，
                // 而不是把整个请求判死 —— 用户要的是"能聊"，不是"必须能搜"。
                var toolsEnabled = searchConfig.enabled

                // 本次请求是否已经流出过任何正文（用于判断"是不是一个字都没出来"）
                var emittedText = false

                // 已交付给界面的正文累计。续传时要从中取**末尾片段**做衔接上下文，
                // 让模型"接着写"而不是从头再来（否则界面会出现两遍前半截）。
                val producedText = StringBuilder()
                // 本轮 issueRound 开始时 producedText 的长度：
                // 断流续传只需要"这一轮吐出来的部分"，之前的轮次早已进了 conversation。
                var attemptStartLen = 0

                suspend fun issueRound(useTools: Boolean): RoundResult {
                    val call = client.newCall(
                        buildRequest(provider, conversation, useTools)
                    )
                    activeCall.set(call)
                    return try {
                        executeRound(
                            call,
                            provider,
                            onDelta = { delta ->
                                emittedText = true
                                producedText.append(delta)
                                out.trySend(ChatEvent.Text(delta))
                            },
                            onReasoning = { delta -> out.trySend(ChatEvent.Reasoning(delta)) }
                        )
                    } finally {
                        activeCall.set(null)
                    }
                }

                /**
                 * 带**断线自动续传**的一轮请求（整次对话只续一次，见 [resumeUsed]）。
                 *
                 * ## 为什么要续传
                 * 云端 SSE 流随时可能被网络抖动、供应商排队超时掐断。旧实现直接把它抛成
                 * Failed，用户看到的就是"吐到一半断掉"且后半截永远没了。
                 *
                 * ## 续传策略
                 * - 本轮**已有部分内容**：把已产出文本的末尾片段连同"请从中断处接着写、
                 *   不要重复"的指令追加进对话再发一次 —— 模型从中断处续写，
                 *   界面侧的 builder 只会追加新 delta，**不会出现重复段落**。
                 * - 一个字都没吐：原样重发一次（纯网络抖动的裸重试）。
                 *
                 * ## 什么情况**不**重试
                 * - 用户点了停止（协程已取消，[isActive] 为 false）：绝不上抛重试；
                 * - 服务端明确拒绝（HTTP 非 2xx 且非 5xx、流内 error 字段）：
                 *   重发同样的请求只会得到同样的失败，白烧一次配额；
                 * - 已经续过一次：兜底为一次，避免网关故障时无限重发。
                 */
                suspend fun issueRoundResumable(useTools: Boolean): RoundResult {
                    attemptStartLen = producedText.length
                    try {
                        return issueRound(useTools)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        if (!isActive || resumeUsed) throw e
                        if (e is ServerErrorException && !e.retryable) throw e
                        if (e !is IOException) throw e
                        resumeUsed = true

                        val partial = producedText.substring(attemptStartLen)
                        if (partial.isNotEmpty()) {
                            // 只带末尾片段而不是全文：衔接够用，又不把上下文翻倍。
                            conversation.add(
                                messageJson(
                                    "user",
                                    buildResumePrompt(partial.takeLast(RESUME_TAIL_CHARS))
                                )
                            )
                        }
                        return issueRound(useTools)
                    }
                }

                while (round < MAX_TOOL_ROUNDS && isActive && !finished) {
                    round++

                    val roundResult = try {
                        issueRoundResumable(toolsEnabled)
                    } catch (e: IOException) {
                        // 退化成普通对话只在"服务端因 tools 报错且一个字都没吐"时才有意义：
                        // 此时从头重发不会与已输出内容重复。已有正文的失败交给上面的
                        // 续传逻辑处理，续传也失败了就如实报错（正文保留在界面里）。
                        if (toolsEnabled && !emittedText) {
                            toolsEnabled = false
                            out.send(
                                ChatEvent.SearchFinished(
                                    query = "",
                                    sources = emptyList(),
                                    error = "当前模型或网关不支持工具调用，已退化为普通对话"
                                )
                            )
                            issueRoundResumable(false)
                        } else {
                            throw e
                        }
                    }

                    if (roundResult.toolCalls.isEmpty()) {
                        finished = true
                    } else {
                        // 把模型这一轮的"我要调用工具"如实回填进对话
                        conversation.add(assistantToolCallJson(roundResult))

                        var anySearchFailed = false
                        for (toolCall in roundResult.toolCalls) {
                            val query = toolCall.query
                            out.send(ChatEvent.SearchStarted(query))

                            val outcome = searchClient.search(query, searchConfig, provider)
                            val sources = outcome.getOrNull().orEmpty()
                            val error = outcome.exceptionOrNull()?.localizedMessage
                            if (error != null) anySearchFailed = true

                            out.send(ChatEvent.SearchFinished(query, sources, error))

                            val toolContent = if (error != null) {
                                "搜索失败：$error"
                            } else {
                                // 带正文：并发抓取前几条结果页并抽取正文，让模型答得出细节。
                                // 抓不到/超时都会如实标注，不会编造正文（见 WebPageExtractor）。
                                searchClient.formatForModelWithPages(sources)
                            }
                            conversation.add(toolResultJson(toolCall.id, toolContent))
                        }

                        // 检索一失败，就不再让模型继续走工具路径。
                        // 旧实现在这种情况下还会再发一轮带 tools 的请求，模型往往
                        // 又要求搜一次，于是用户看到"同一句搜了好几遍、转好几圈"。
                        // 关掉 tools 后，下一轮就是普通对话，模型会基于已有信息收尾。
                        if (anySearchFailed) toolsEnabled = false
                    }
                }

                // 退出循环后如果**一个字都没流出来**，说明模型这几轮只在反复要求调用搜索
                // （或搜索返回了空结果但没报错）。此时不能让界面显示空白 ——
                // 关掉工具再要最后一轮普通回答，模型必须基于已有信息作答；
                // 若这一轮仍是空的，executeRound 会抛带原始响应片段的可读错误。
                if (!emittedText && isActive) {
                    issueRound(false)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                out.trySend(ChatEvent.Failed(describeFailure(e, null)))
            } finally {
                // ⚠️ 清理必须放在这里的 finally，**不能**用 channelFlow 的 awaitClose。
                //
                // awaitClose 的语义是"挂起直到 channel 被关闭"。
                // 而在"自己 launch 生产、自己 awaitClose"的写法里，
                // channel 只有在这个块返回后才会关闭 —— 于是 awaitClose
                // 永远等不到，块永远不返回，channelFlow 永远不完成。
                //
                // 后果（用户实际遇到的）：回答早就流式写完了，可 ViewModel 的
                // .collect{} 一直挂着，那个"写 isStreaming=false、把节点移出
                // 生成中集合"的 finally **永远执行不到**。
                // 表现就是：**回答已经输出完了，界面还显示"生成中"，
                // 发送按钮也一直停在"停止"图标上不变回箭头**。
                //
                // 放在协程的 finally 里则天然正确：
                //   - 协程正常跑完 → finally 执行 → block 返回 → Flow 正常完成；
                //   - 用户点"停止" → 协程被取消 → finally 执行 → 取消 OkHttp Call，
                //     否则会阻塞在 readUtf8Line 上，"停止"要等很久才响应。
                activeCall.get()?.cancel()
            }
        }

        // ⚠️ 等协程结束，block 才能真正返回，channelFlow 才会完成。不要删。
        //
        // 这是"答完自己结束"能成立的关键。此前的失败链有两环：
        //   1. 只 launch 不 join → 块立刻往下走（v1.12.0 已修）；
        //   2. 块末尾用 awaitClose → **挂起直到 channel 关闭**，而 channel 只在
        //      块返回后才关，于是块永不返回，Flow 永不完成（本次修复）。
        // 两环的后果一样：ViewModel 的 .collect{} 一直挂着，
        // "写 isStreaming=false、移出生成中集合"的 finally 永远执行不到，
        // 用户看到"回答输出完了还显示生成中，按钮不变回箭头"，
        // 必须手点"停止"才能收尾（取消收集时 finally 才跑到）。
        //
            // 现在清理逻辑已经移到上面协程的 finally 里，这里只需 join 后返回。
            job.join()
        }
    }

    // ========================================================================
    // 单轮 SSE 读取
    // ========================================================================

    private class ToolCallAccumulator {
        val id = StringBuilder()
        val name = StringBuilder()
        val arguments = StringBuilder()
    }

    private class ToolCallResult(val id: String, val name: String, val arguments: String) {
        /** 从 arguments JSON 里取 query 字段。 */
        val query: String
            get() = try {
                val obj = com.google.gson.JsonParser.parseString(arguments).asJsonObject
                obj.get("query")?.takeIf { !it.isJsonNull }?.asString.orEmpty()
            } catch (e: Exception) {
                ""
            }
    }

    private class RoundResult(
        val text: String,
        val toolCalls: List<ToolCallResult>
    )

    /**
     * 读取一轮 SSE。
     *
     * 这里用同步逐行读而不是 EventSource：因为工具调用需要"等这一轮彻底结束"
     * 才能决定要不要再发一轮请求，同步读的时序比回调更直观。
     */
    private fun executeRound(
        call: Call,
        provider: ProviderConfig,
        onDelta: (String) -> Unit,
        /**
         * 思考过程增量回调。
         *
         * 默认空实现，保证既有调用点不必改动；
         * 是否真的有内容取决于服务商是否返回 `reasoning_content`。
         */
        onReasoning: (String) -> Unit = {}
    ): RoundResult {
        val textBuilder = StringBuilder()
        val toolCalls = linkedMapOf<Int, ToolCallAccumulator>()

        /**
         * 诊断用：这一轮是否收到过"结构上认不出来"的帧。
         *
         * 为什么需要它：不同服务商/中转站的响应结构差异很大（有的 choices 是对象、
         * 有的把正文塞在别处、有的在流里回错误）。改进解析后能容忍这些差异，
         * 但**容忍不等于成功** —— 若整轮一个字都没解析出来，用户只会看到
         * "（无输出）"，完全无从判断问题出在哪。
         * 那时把原始响应片段展示出来，用户（和我）才能定位。
         */
        var sawUnparsedContent = false
        var unparsedSample: String? = null

        call.execute().use { response: Response ->
            if (!response.isSuccessful) {
                val body = try {
                    response.body?.string()?.take(200)
                } catch (e: Exception) {
                    null
                }
                // 408/429/5xx 是网关自己的瞬时故障，值得续传救一次；
                // 其余（密钥错、参数非法）重发也一样，抛不可重试让上层直接报错。
                throw ServerErrorException(
                    retryable = response.code == 408 || response.code == 429 || response.code >= 500,
                    message = httpErrorMessage(response.code, body)
                )
            }

            val source = response.body?.source() ?: throw IOException("响应体为空")

            // ⚠️ 必须用 try/finally 显式关闭 source，不能只靠 break。
            //
            // 这是"答完了还一直显示生成中、要手点停止"的**真正根因**：
            // 上面读到结束信号（finish_reason / message_stop / stop_reason）时只是
            // `break` 出 while 循环，然后 `call.execute().use {}` 在关闭响应时会把
            // **整个响应体读干净**（OkHttp 关闭 ResponseBody 时默认丢弃剩余数据）。
            // 而这类网关发完最后一帧后既不继续发、也不关连接，于是那次"排空"
            // 一直阻塞到读超时才返回 —— 界面就在这段时间里一直显示"生成中"。
            // 表现正是用户说的："话说完了还是显示在生成，要自己手动点图标停止"。
            //
            // 显式 close() 之后，OkHttp 的排空动作立刻拿到一个已关闭的流并立即返回，
            // 于是"模型说完"到"界面收工"之间不再有任何等待。
            try {
                while (!source.exhausted()) {
                    val line = source.readUtf8Line() ?: break
                    if (!line.startsWith("data:")) continue

                    val payload = line.removePrefix("data:").trim()
                    if (payload.isEmpty()) continue
                    if (payload == "[DONE]") break

                    val json = try {
                        gson.fromJson(payload, JsonObject::class.java)
                    } catch (e: Exception) {
                        continue
                    } ?: continue

                    when (provider.protocolType) {
                        ProtocolType.OPENAI_COMPATIBLE -> {
                            // 有些服务端会把错误塞在流里
                            if (json.has("error") && !json.get("error").isJsonNull) {
                                val errText = runCatching {
                                    json.get("error").toString().take(200)
                                }.getOrNull() ?: "服务端返回错误"
                                throw IOException(errText)
                            }

                            // ⚠️ 以下全部改成**防御式取值**，不再用 getAsJsonArray/asJsonObject。
                            //
                            // 为什么要这样：`getAsJsonArray("choices")` 在字段不是数组时会抛
                            // ClassCastException（R8 之后显示为
                            // "class o2.q cannot be cast to class o2.n"），
                            // 整个请求直接失败。而不同服务商/中转站的字段类型并不统一：
                            // 有的把 choices 给成对象、有的在流里混入非流式响应
                            // （此时没有 delta，正文在 message.content 里）。
                            // 对这类"格式不完全标准"的服务商，宁可用宽松解析也要能聊，
                            // 而不是抛一个用户完全看不懂的类型转换错误。
                            // choices 可能是数组（标准流式），也可能直接是对象
                            // （少数实现）。两种情况都取第一个有效对象。
                            val choicesArray = json.arrayOrNull("choices")
                            val choice: JsonObject? = if (choicesArray != null) {
                                choicesArray.firstOrNull()?.asJsonObjectOrNull()
                            } else {
                                json.objectOrNull("choices")
                            }
                            if (choice == null) continue

                            // 流式增量
                            val delta = choice.objectOrNull("delta")
                            // 非流式响应：正文在 message.content（Anthropic 风格）
                            // 或 choice.message.content（OpenAI 非流式）里
                            val fallbackText = choice.objectOrNull("message")
                                ?.stringOrNull("content")
                                ?: choice.stringOrNull("text")

                            if (delta != null) {
                                delta.stringOrNull("content")?.let { piece ->
                                    if (piece.isNotEmpty()) {
                                        textBuilder.append(piece)
                                        onDelta(piece)
                                    }
                                }
                                // ---- 思考内容 ----
                                //
                                // 各家"思考模型"用的字段名并不统一，这里按常见程度
                                // 依次尝试，**取到第一个非空就够了**（同一条 delta 里
                                // 不会同时出现两种命名）。
                                // 注意：这些内容之前是被**静默丢弃**的，
                                // 用户因此以为"开了思考什么都没发生"。
                                val reasoning = reasoningTextOf(delta)
                                if (!reasoning.isNullOrEmpty()) {
                                    onReasoning(reasoning)
                                }
                                accumulateToolCalls(delta, toolCalls)
                            } else if (!fallbackText.isNullOrEmpty()) {
                                // 服务端没有按流式返回：把整段当作一次增量交付，
                                // 至少让用户看到回答，而不是空白 + 报错
                                textBuilder.append(fallbackText)
                                onDelta(fallbackText)
                                sawUnparsedContent = true
                            } else {
                                // 走到这里说明这个 choice 里既没有 delta 也没有正文。
                                // 记下来：如果整轮什么都没解析出来，
                                // 就要把原始响应回显给用户（见下方诊断）。
                                sawUnparsedContent = true
                                if (unparsedSample == null) {
                                    unparsedSample = payload.take(400)
                                }
                            }

                            val finish = choice.get("finish_reason")
                            if (finish != null && !finish.isJsonNull) break
                        }

                        ProtocolType.ANTHROPIC_MESSAGES -> {
                            val type = json.stringOrNull("type")

                            // Anthropic 协议的上游也会把错误塞在流里（形如
                            // {"type":"error","error":{"type":"...","message":"..."}）。
                            // OpenAI 分支早有这段检查，Anthropic 分支此前**没有** ——
                            // 于是上游一报错就被静默吞掉，最后只以"没有返回任何内容"或
                            // 空结果收场，用户看到的就是"开了联网就没输出"。
                            if (type == "error") {
                                val msg = json.objectOrNull("error")
                                    ?.stringOrNull("message")
                                    ?: json.get("error")?.toString()?.take(300)
                                    ?: "服务商返回了未说明的错误"
                                throw IOException("服务商返回错误：$msg")
                            }

                            if (type == "content_block_start") {
                                val block = json.objectOrNull("content_block")
                                if (block?.stringOrNull("type") == "tool_use") {
                                    val index = json.intOrNull("index") ?: 0
                                    val acc = toolCalls.getOrPut(index) { ToolCallAccumulator() }
                                    block.stringOrNull("id")?.let { acc.id.append(it) }
                                    block.stringOrNull("name")?.let { acc.name.append(it) }
                                }
                            }
                            if (type == "content_block_delta") {
                                val delta = json.objectOrNull("delta")
                                val deltaType = delta?.stringOrNull("type")
                                if (deltaType == "text_delta" || deltaType == null) {
                                    delta?.stringOrNull("text")?.let { piece ->
                                        if (piece.isNotEmpty()) {
                                            textBuilder.append(piece)
                                            onDelta(piece)
                                        }
                                    }
                                } else if (deltaType == "input_json_delta") {
                                    val index = json.intOrNull("index") ?: 0
                                    val acc = toolCalls.getOrPut(index) { ToolCallAccumulator() }
                                    delta.stringOrNull("partial_json")?.let { acc.arguments.append(it) }
                                }
                            }
                            // Anthropic 的结束信号：message_delta 里的 stop_reason，
                            // 或随后的 message_stop。同样不能只等连接关闭。
                            if (type == "message_stop") break
                            if (type == "message_delta") {
                                val stop = json.objectOrNull("delta")?.get("stop_reason")
                                if (stop != null && !stop.isJsonNull) break
                            }
                        }
                    }
                }
            } finally {
                // 关键：让 OkHttp 不用去排空剩余字节（那会阻塞到读超时）
                runCatching { source.close() }
            }
        }

        // ---- 整轮无内容时的诊断 ----
        //
        // 一个字都没解析出来、也没有工具调用，说明响应结构与我们的解析假设不符。
        // 这时抛出带上**原始响应片段**的可读错误，而不是返回一个空结果让界面
        // 显示"（无输出）"—— 后者对用户毫无信息量，也让我无法远程定位问题。
        if (textBuilder.isEmpty() && toolCalls.isEmpty()) {
            val sample = unparsedSample
            if (sample != null) {
                throw IOException(
                    "服务商的响应格式无法识别（未取到任何文本或工具调用）。\n" +
                        "原始响应片段：\n$sample"
                )
            }
            // 连一帧都没收到
            throw IOException("服务商没有返回任何内容（连接正常但没有数据）。")
        }
        if (textBuilder.isEmpty() && toolCalls.isNotEmpty() && sawUnparsedContent) {
            // 只解析出工具调用、没有正文，且见过认不出的帧：多半是服务商
            // 把正文放在了我们没覆盖的位置。给出提示而不是静默空白。
            unparsedSample?.let { sample ->
                throw IOException(
                    "只解析到工具调用、没有正文。原始响应片段：\n$sample"
                )
            }
        }

        val results = toolCalls.entries
            .sortedBy { it.key }
            .map { (_, acc) ->
                ToolCallResult(
                    id = acc.id.toString().ifBlank { "call_${System.nanoTime()}" },
                    name = acc.name.toString(),
                    arguments = acc.arguments.toString().ifBlank { "{}" }
                )
            }
            .filter { it.name == SEARCH_TOOL_NAME }

        return RoundResult(textBuilder.toString(), results)
    }

    private fun accumulateToolCalls(
        delta: JsonObject,
        into: MutableMap<Int, ToolCallAccumulator>
    ) {
        // ⚠️ 这里必须用 arrayOrNull，绝不能用 Gson 的 getAsJsonArray。
        //
        // Gson 的 `JsonObject.getAsJsonArray(name)` 实现是**裸强转**
        // `(JsonArray) members.get(name)`，于是两种情形结局完全不同：
        //   - 字段**不存在**        → 返回 null，`?: return` 能兜住；
        //   - 字段**存在且值为 null** → 返回 JsonNull.INSTANCE，强转立刻抛
        //     ClassCastException:
        //     "com.google.gson.JsonNull cannot be cast to com.google.gson.JsonArray"
        //
        // 小米 MiMo 正是后者：它对**每一帧**都显式回传 `"tool_calls": null`。
        // 实测首帧原文（2026-10-11，直连 api.xiaomimimo.com/v1）：
        //   {"choices":[{"delta":{"content":"","role":"assistant",
        //    "tool_calls":null,"reasoning_content":null},"finish_reason":null,
        //    "index":0}],...}
        // 后果：**第一条增量就把整轮请求打死**，异常冒到界面变成
        // "请求失败：com.google.gson.JsonNull cannot be cast to ..."。
        // 这解释了用户观察到的全部现象——"能拉到模型列表但发消息永远无输出、
        // 换任何模型/任何推理强度都没用"：崩点在解析入口，
        // 与模型能力、推理参数、Base URL 全都无关。
        //
        // 这不是"小米不兼容"，是我们把 JSON null 当数组读了。
        val array = delta.arrayOrNull("tool_calls") ?: return
        for (element in array) {
            // 同类隐患：元素不是对象时 asJsonObject 同样抛，整轮一起失败。
            val obj = element.asJsonObjectOrNull() ?: continue
            val index = obj.intOrNull("index") ?: 0
            val acc = into.getOrPut(index) { ToolCallAccumulator() }

            obj.stringOrNull("id")?.let { acc.id.append(it) }
            // 同理：`"function": null` 在裸强转下也会抛。
            val function = obj.objectOrNull("function") ?: continue

            function.stringOrNull("name")?.let {
                if (acc.name.isEmpty()) acc.name.append(it)
            }
            function.stringOrNull("arguments")?.let {
                acc.arguments.append(it)
            }
        }
    }

    // ========================================================================
    // 请求构造
    // ========================================================================

    /**
     * 构造一条消息（OpenAI 兼容形态）。
     *
     * ## 为什么默认仍发纯字符串 content
     * 只有在**带图片**时才把 `content` 换成数组。纯文本继续用字符串，
     * 是为了百分之百向后兼容：不少第三方中转站对数组形态的 content
     * 支持不完整，无图时发数组等于平白引入失败风险。
     *
     * ## 两种协议的图片格式**不能混用**
     * - OpenAI 兼容：`{"type":"image_url","image_url":{"url":"data:image/jpeg;base64,..."}}`
     * - Anthropic ：`{"type":"image","source":{"type":"base64","media_type":"image/jpeg","data":"<纯base64>"}}`
     * 混用会被服务端直接判为参数非法。
     * Anthropic 形态由 [toAnthropicBlock] 在 buildRequest 里转换
     * （协议在构造消息时还不知道，不能在这里判断）。
     */
    private fun messageJson(
        role: String,
        content: String,
        images: List<String> = emptyList()
    ): JsonObject = JsonObject().apply {
        addProperty("role", role)
        if (images.isEmpty()) {
            addProperty("content", content)
        } else {
            add("content", contentArray(content, images))
        }
    }

    /**
     * 组装带图片的 content 数组（OpenAI 兼容形态）。
     *
     * 图片一律放在文字**前面**：把图放在问题前更符合"先看图再读问题"的直觉，
     * 也避免某些实现在长文本后截断图片。
     */
    private fun contentArray(content: String, images: List<String>): JsonArray =
        JsonArray().apply {
            images.forEach { dataUri ->
                // 拆不出来的数据直接跳过，而不是发一段坏 base64 让整个请求失败
                val parsed = com.treechat.workbench.rag.ImagePicker.splitDataUri(dataUri)
                    ?: return@forEach
                if (parsed.second.isBlank()) return@forEach
                add(
                    JsonObject().apply {
                        addProperty("type", "image_url")
                        add(
                            "image_url",
                            JsonObject().apply { addProperty("url", dataUri) }
                        )
                    }
                )
            }
            // 文字块：空字符串也发（有些服务端要求 content 数组非空）
            add(JsonObject().apply { addProperty("type", "text"); addProperty("text", content) })
        }

    /**
     * 把一条消息从 OpenAI 形态转成 Anthropic 形态。
     *
     * 涵盖：
     * 1. 角色为 "tool" 的搜索结果 -> 转为 Anthropic 的 "user" 消息 + "tool_result" 块；
     * 2. 带有 "tool_calls" 的 assistant 消息 -> 转为 Anthropic 的 "assistant" 消息 + "tool_use" 块；
     * 3. 带图片的消息 -> 转为 Anthropic 的 "image" base64 块；
     * 4. 纯文本消息保持原样。
     */
    private fun toAnthropicBlock(message: JsonObject): JsonObject {
        val role = message.get("role")?.asString ?: return message

        // 1. OpenAI 工具结果消息 {"role":"tool", "tool_call_id":"...", "content":"..."}
        //    -> Anthropic 要求: role: "user", content: [{"type":"tool_result", "tool_use_id":"...", "content":"..."}]
        if (role == "tool") {
            val toolCallId = message.get("tool_call_id")?.asString.orEmpty()
            val toolContent = message.get("content")?.asString.orEmpty()
            return JsonObject().apply {
                addProperty("role", "user")
                add("content", JsonArray().apply {
                    add(JsonObject().apply {
                        addProperty("type", "tool_result")
                        addProperty("tool_use_id", toolCallId)
                        addProperty("content", toolContent)
                    })
                })
            }
        }

        // 2. assistant 发起的工具调用 {"role":"assistant", "tool_calls":[...]}
        //    -> Anthropic 要求: role: "assistant", content: [text块, {"type":"tool_use", "id":"...", "name":"...", "input":{...}}]
        val toolCalls = message.getAsJsonArray("tool_calls")
        if (role == "assistant" && toolCalls != null && toolCalls.size() > 0) {
            val blocks = JsonArray()
            val textContent = message.get("content")?.takeIf { !it.isJsonNull }?.asString.orEmpty()
            if (textContent.isNotBlank()) {
                blocks.add(JsonObject().apply {
                    addProperty("type", "text")
                    addProperty("text", textContent)
                })
            }
            toolCalls.forEach { callElem ->
                val callObj = callElem.asJsonObject
                val id = callObj.get("id")?.asString.orEmpty()
                val funcObj = callObj.getAsJsonObject("function")
                val name = funcObj?.get("name")?.asString.orEmpty()
                val argsStr = funcObj?.get("arguments")?.asString.orEmpty()
                val inputJson = try {
                    com.google.gson.JsonParser.parseString(argsStr).asJsonObject
                } catch (e: Exception) {
                    JsonObject().apply { addProperty("query", argsStr) }
                }
                blocks.add(JsonObject().apply {
                    addProperty("type", "tool_use")
                    addProperty("id", id)
                    addProperty("name", name)
                    add("input", inputJson)
                })
            }
            return JsonObject().apply {
                addProperty("role", "assistant")
                add("content", blocks)
            }
        }

        val content = message.get("content") ?: return message
        // 只有数组形态（即带图片的）才需要改写
        if (!content.isJsonArray) return message

        val blocks = JsonArray()
        content.asJsonArray.forEach { block ->
            val obj = block.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
            when (obj.get("type")?.asString) {
                "text" -> blocks.add(obj)   // 文字块两协议一致，原样保留
                "image_url" -> {
                    val url = obj.getAsJsonObject("image_url")?.get("url")?.asString
                    val parsed = url?.let { com.treechat.workbench.rag.ImagePicker.splitDataUri(it) }
                    if (parsed != null) {
                        blocks.add(
                            JsonObject().apply {
                                addProperty("type", "image")
                                add(
                                    "source",
                                    JsonObject().apply {
                                        addProperty("type", "base64")
                                        addProperty("media_type", parsed.first)
                                        addProperty("data", parsed.second)
                                    }
                                )
                            }
                        )
                    }
                    // 拆不出来就丢弃这个图块，而不是发一条坏数据
                }
                else -> blocks.add(obj)
            }
        }
        return JsonObject().apply {
            addProperty("role", role)
            add("content", blocks)
        }
    }

    private fun assistantToolCallJson(round: RoundResult): JsonObject =
        JsonObject().apply {
            addProperty("role", "assistant")
            if (round.text.isNotBlank()) addProperty("content", round.text)
            else add("content", com.google.gson.JsonNull.INSTANCE)

            val calls = JsonArray()
            round.toolCalls.forEach { call ->
                calls.add(
                    JsonObject().apply {
                        addProperty("id", call.id)
                        addProperty("type", "function")
                        add(
                            "function",
                            JsonObject().apply {
                                addProperty("name", call.name)
                                addProperty("arguments", call.arguments)
                            }
                        )
                    }
                )
            }
            add("tool_calls", calls)
        }

    private fun toolResultJson(toolCallId: String, content: String): JsonObject =
        JsonObject().apply {
            addProperty("role", "tool")
            addProperty("tool_call_id", toolCallId)
            addProperty("content", content)
        }

    private fun searchToolJson(): JsonObject =
        JsonObject().apply {
            addProperty("type", "function")
            add(
                "function",
                JsonObject().apply {
                    addProperty("name", SEARCH_TOOL_NAME)
                    addProperty(
                        "description",
                        "联网搜索最新信息。当问题涉及实时新闻、当前事件、" +
                            "你不确定的事实、或需要引用具体来源时调用。"
                    )
                    add(
                        "parameters",
                        JsonObject().apply {
                            addProperty("type", "object")
                            add(
                                "properties",
                                JsonObject().apply {
                                    add(
                                        "query",
                                        JsonObject().apply {
                                            addProperty("type", "string")
                                            addProperty(
                                                "description",
                                                "搜索关键词，尽量精炼，使用与目标资料相同的语言"
                                            )
                                        }
                                    )
                                }
                            )
                            add(
                                "required",
                                JsonArray().apply { add("query") }
                            )
                        }
                    )
                }
            )
        }

    /** Anthropic Messages 协议的原生工具定义（使用 input_schema）。 */
    private fun anthropicSearchToolJson(): JsonObject =
        JsonObject().apply {
            addProperty("name", SEARCH_TOOL_NAME)
            addProperty(
                "description",
                "联网搜索最新信息。当问题涉及实时新闻、当前事件、你不确定的事实、或需要引用具体来源时调用。"
            )
            add(
                "input_schema",
                JsonObject().apply {
                    addProperty("type", "object")
                    add(
                        "properties",
                        JsonObject().apply {
                            add(
                                "query",
                                JsonObject().apply {
                                    addProperty("type", "string")
                                    addProperty(
                                        "description",
                                        "搜索关键词，尽量精炼，使用与目标资料相同的语言"
                                    )
                                }
                            )
                        }
                    )
                    add(
                        "required",
                        JsonArray().apply { add("query") }
                    )
                }
            )
        }

    private fun buildRequest(
        provider: ProviderConfig,
        conversation: List<JsonObject>,
        withTools: Boolean
    ): Request {
        val base = provider.baseUrl.trimEnd('/')
        val isAnthropic = provider.protocolType == ProtocolType.ANTHROPIC_MESSAGES

        // 无论 OpenAI 还是 Anthropic 协议，只要开启了工具都全力支持
        val effectiveTools = withTools

        val messagesArray = JsonArray().apply { conversation.forEach { add(it) } }

        return if (isAnthropic) {
            val systemText = conversation
                .firstOrNull { it.get("role")?.asString == "system" }
                ?.get("content")?.asString

            val anthropicMessages = JsonArray().apply {
                conversation
                    .filter { it.get("role")?.asString != "system" }
                    .forEach { add(toAnthropicBlock(it)) }
            }

            val payload = JsonObject().apply {
                addProperty("model", provider.selectedModelId)
                add("messages", anthropicMessages)
                addProperty("max_tokens", activeMaxTokens(provider))
                addProperty("stream", true)
                if (!systemText.isNullOrBlank()) addProperty("system", systemText)

                if (effectiveTools) {
                    // 为 Anthropic 协议注入标准的 web_search 工具定义
                    add(
                        "tools",
                        JsonArray().apply {
                            add(
                                JsonObject().apply {
                                    addProperty("name", SEARCH_TOOL_NAME)
                                    addProperty("description", "Search the web for up-to-date information")
                                    add(
                                        "input_schema",
                                        JsonObject().apply {
                                            addProperty("type", "object")
                                            add(
                                                "properties",
                                                JsonObject().apply {
                                                    add(
                                                        "query",
                                                        JsonObject().apply {
                                                            addProperty("type", "string")
                                                            addProperty("description", "Search query")
                                                        }
                                                    )
                                                }
                                            )
                                            add(
                                                "required",
                                                JsonArray().apply { add("query") }
                                            )
                                        }
                                    )
                                }
                            )
                        }
                    )
                }

                // 推理强度 → Anthropic 的 thinking 块。
                // 关闭时不发送 thinking 字段（而不是发 {type:"disabled"}）：
                // 部分中转站不认 disabled，会直接报参数非法。不发送最兼容。
                val effort = provider.reasoningEffortOrOff
                if (effort != ReasoningEffort.OFF) {
                    // budget_tokens 必须小于 max_tokens，否则服务端拒绝。
                    // 这里再夹一层保护：预算最多取 max_tokens 的 3/4。
                    val maxOut = activeMaxTokens(provider)
                    val budget = effort.anthropicBudgetTokens
                        .coerceAtLeast(1_024)
                        .coerceAtMost((maxOut * 3 / 4).coerceAtLeast(1_024).toInt())
                    add(
                        "thinking",
                        JsonObject().apply {
                            addProperty("type", "enabled")
                            addProperty("budget_tokens", budget)
                        }
                    )
                }

                // 开启联网搜索时，为 Anthropic 协议注入其原生格式的工具声明
                if (effectiveTools) {
                    add("tools", JsonArray().apply { add(anthropicSearchToolJson()) })
                }
            }

            Request.Builder()
                .url(messagesEndpoint(provider.baseUrl))
                .addHeader("x-api-key", provider.apiKey)
                .addHeader("Authorization", "Bearer ${provider.apiKey}")
                .addHeader("anthropic-version", "2023-06-01")
                .addHeader("Content-Type", "application/json")
                .post(gson.toJson(payload).toRequestBody(jsonMediaType))
                .build()
        } else {
            val payload = JsonObject().apply {
                addProperty("model", provider.selectedModelId)
                add("messages", messagesArray)
                addProperty("stream", true)
                addProperty("max_tokens", activeMaxTokens(provider))

                // 推理强度 → OpenAI 兼容的 reasoning_effort。
                // 关闭时**不发送**该字段：发 "none" 在部分服务商上会被判为非法枚举值。
                provider.reasoningEffortOrOff.openAiValue?.let { value ->
                    addProperty("reasoning_effort", value)
                }

                if (effectiveTools) {
                    add("tools", JsonArray().apply { add(searchToolJson()) })
                    addProperty("tool_choice", "auto")
                }
            }

            Request.Builder()
                .url(chatCompletionsEndpoint(provider.baseUrl))
                .addHeader("Authorization", "Bearer ${provider.apiKey}")
                .addHeader("Content-Type", "application/json")
                .post(gson.toJson(payload).toRequestBody(jsonMediaType))
                .build()
        }
    }

    private fun activeMaxTokens(provider: ProviderConfig): Long =
        provider.availableModels
            .firstOrNull { it.id == provider.selectedModelId }
            ?.maxOutputTokens
            ?.takeIf { it > 0 }
            ?: 4096L

    private fun httpErrorMessage(code: Int, body: String?): String = when (code) {
        401, 403 -> "鉴权失败（HTTP $code）：请检查 API 密钥"
        // 旧文案写的是"请检查 Base URL 是否包含 /v1"，方向恰好相反：
        // 拼接规则现在会**自动补 /v1 且不会重复**，所以多写 /v1 不会再导致 404。
        // 真正的 404 只剩"该服务商/中转站没有这个端点"。
        404 -> "接口不存在（HTTP 404）：该地址下没有此端点。请核对 Base URL 是否只填到" +
            "域名（或端口），是否确实提供该协议接口（Anthropic 走 /v1/messages，" +
            "OpenAI 兼容走 /v1/chat/completions）"
        429 -> "请求过频或额度不足（HTTP 429）"
        else -> "服务端错误 HTTP $code：${body ?: ""}"
    }

    private fun describeFailure(t: Throwable, response: Response?): String {
        val code = response?.code
        return when {
            code != null -> httpErrorMessage(code, null)
            // 续传/重试路径抛出来的服务端错误，message 里已经带了 body 片段，
            // 直接用它 —— 不然用户看到"服务端错误 HTTP 500："后面是空的。
            t is ServerErrorException -> t.message ?: "服务端错误"
            t is IOException -> "网络异常：${t.localizedMessage}"
            else -> t.localizedMessage ?: "未知错误"
        }
    }

    // ========================================================================
    // 连通性测试 / 模型列表（设置面板用）
    // ========================================================================

    suspend fun testConnection(provider: ProviderConfig): ConnTestResult = withContext(Dispatchers.IO) {
        if (provider.id == "local-npu-qwen" || provider.id == "local_npu_qwen") {
            return@withContext ConnTestResult(false, "该本地模型服务商已随方案移除，请在设置中改选云端服务商")
        }
        if (provider.apiKey.isBlank()) {
            return@withContext ConnTestResult(false, "尚未填写 API 密钥")
        }
        val base = provider.baseUrl.trimEnd('/')
        if (base.isBlank()) {
            return@withContext ConnTestResult(false, "尚未填写 API 地址")
        }

        try {
            val request = Request.Builder()
                .url(modelsEndpoint(provider.baseUrl))
                .apply {
                    if (provider.protocolType == ProtocolType.ANTHROPIC_MESSAGES) {
                        addHeader("x-api-key", provider.apiKey)
                        addHeader("anthropic-version", "2023-06-01")
                    } else {
                        addHeader("Authorization", "Bearer ${provider.apiKey}")
                    }
                }
                .get()
                .build()

            client.newCall(request).execute().use { resp ->
                when {
                    resp.isSuccessful -> {
                        val count = extractModels(
                            resp.body?.string().orEmpty(),
                            fallbackContext = 128_000L,
                            fallbackMaxTokens = 8_192L
                        ).size
                        if (count > 0) {
                            ConnTestResult(true, "连接正常，可列出 $count 个模型")
                        } else {
                            ConnTestResult(true, "连接正常（HTTP ${resp.code}）")
                        }
                    }
                    resp.code == 401 || resp.code == 403 ->
                        ConnTestResult(false, "鉴权失败（HTTP ${resp.code}）：API 密钥不正确或无权限")
                    resp.code == 404 ->
                        ConnTestResult(
                            false,
                            "该地址未提供模型列表接口（HTTP 404）。实际请求：${modelsEndpoint(provider.baseUrl)}" +
                                " —— 请核对 Base URL（只填到域名或端口即可，/v1 会自动补上）"
                        )
                    else -> {
                        val snippet = resp.body?.string().orEmpty().take(160)
                        ConnTestResult(false, "服务端返回 HTTP ${resp.code}：$snippet")
                    }
                }
            }
        } catch (e: IOException) {
            ConnTestResult(false, "网络不通：${e.localizedMessage}")
        } catch (e: Exception) {
            ConnTestResult(false, "测试失败：${e.localizedMessage}")
        }
    }

    /**
     * 拉取模型清单（含服务商上报的容量）。
     *
     * 调用方（SettingsDialog）会用返回的 contextWindow / maxOutputTokens 覆盖
     * 硬编码默认值，所以这里必须把真实值传出去，不能只回 id 列表。
     */
    suspend fun fetchModels(provider: ProviderConfig): List<AiModelInfo> = withContext(Dispatchers.IO) {
        if (modelsEndpoint(provider.baseUrl).isBlank()) return@withContext emptyList()
        try {
            val request = Request.Builder()
                .url(modelsEndpoint(provider.baseUrl))
                .apply {
                    if (provider.protocolType == ProtocolType.ANTHROPIC_MESSAGES) {
                        addHeader("x-api-key", provider.apiKey)
                        addHeader("anthropic-version", "2023-06-01")
                    } else {
                        addHeader("Authorization", "Bearer ${provider.apiKey}")
                    }
                }
                .get()
                .build()

            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext emptyList()
                extractModels(
                    body = resp.body?.string().orEmpty(),
                    // 服务商没报容量时的回落值。取协议常见的保守值，
                    // 宁可少记也不要虚报（虚报会让超长回答被服务端截断）。
                    fallbackContext = 128_000L,
                    fallbackMaxTokens = 8_192L
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * 解析 `/models` 响应里的模型清单。
     *
     * 与旧实现的关键差别：**不再只取 id**，而是把服务商上报的容量字段一起读出来。
     *
     * 为什么必须这么做：旧代码在"获取可用模型"时把 contextWindow / maxOutputTokens
     * 硬编码成 128000 / 8192，把服务商的真实值整个丢掉。实测某个 Anthropic 兼容
     * 反代返回的是 `context_window: 1000000, max_tokens: 64000`，而客户端却按
     * 8192 记账 —— 于是一边浪费了 8 倍的可用输出，一边用户看到"配置里自动写成
     * 一个奇怪的数"。字段名各家不同，所以两种拼写都认。
     *
     * 解析不到就回落到调用方给的默认值（不猜具体数字）。
     */
    private fun extractModels(
        body: String,
        fallbackContext: Long,
        fallbackMaxTokens: Long
    ): List<AiModelInfo> = try {
        val root = gson.fromJson(body, JsonObject::class.java) ?: return emptyList()
        val data = root.getAsJsonArray("data") ?: return emptyList()
        data.mapNotNull { element ->
            val obj = element.asJsonObject ?: return@mapNotNull null
            val id = obj.get("id")?.takeIf { !it.isJsonNull }?.asString ?: return@mapNotNull null
            if (id.isBlank()) return@mapNotNull null

            // 两种常见拼写：下划线（OpenAI/多数中转站）与驼峰（部分实现）
            val context = firstPositiveLong(obj, "context_window", "contextWindow", "context_length")
                ?: fallbackContext
            val maxTokens = firstPositiveLong(obj, "max_tokens", "maxTokens", "max_output_tokens")
                ?: fallbackMaxTokens

            AiModelInfo(
                id = id,
                name = obj.get("name")?.takeIf { !it.isJsonNull }?.asString?.ifBlank { null } ?: id,
                contextWindow = context,
                maxOutputTokens = maxTokens
            )
        }
    } catch (e: Exception) {
        emptyList()
    }

    /** 按多个候选键名取第一个 > 0 的长整数值；全都没有就返回 null。 */
    private fun firstPositiveLong(obj: JsonObject, vararg keys: String): Long? {
        for (key in keys) {
            val element = obj.get(key) ?: continue
            if (element.isJsonNull) continue
            val value = runCatching { element.asLong }.getOrNull() ?: continue
            if (value > 0) return value
        }
        return null
    }
}

// ============================================================================
// 防御式 JSON 取值（服务商字段类型不统一时的安全通道）
// ============================================================================

/**
 * 为什么需要这一组函数，而不是直接用 Gson 的 `getAsJsonObject` / `getAsJsonArray`：
 *
 * `getAsJsonObject("k")` 在 k 不是对象时会抛 `ClassCastException`；
 * 同理 `getAsJsonArray` 在不是数组时也抛。R8 混淆后这个异常的文案会变成
 * 用户完全看不懂的 `class o2.q cannot be cast to class o2.n`。
 *
 * 而真实世界的服务商/中转站字段类型并不统一：`choices` 可能给成对象，
 * 流式接口里可能混入非流式响应（没有 `delta`，正文在 `message.content`）。
 * 对这些情况，宽松解析能"照样能聊"，强转只会让整个请求失败。
 *
 * 这一层只负责**安全取值**，不改变任何语义：类型不对就返回 null，
 * 由调用方决定退路（跳过、或走备用字段）。
 */
private fun JsonObject.objectOrNull(key: String): JsonObject? =
    runCatching { get(key)?.takeIf { it.isJsonObject }?.asJsonObject }.getOrNull()

/**
 * 从流式 delta（或 message）里取"思考内容"。
 *
 * ## 为什么要有这一层
 * 此前解析器只读 `content`，导致思考文本被**静默丢弃** ——
 * 用户看到的现象是"开了思考但界面没反应"，很容易误判为
 * "模型没在思考"或"推理配置没生效"。
 *
 * ## 字段名为什么不统一
 * 这不是某个厂商的标准字段，而是各家在 OpenAI 兼容协议上的扩展：
 *   - `reasoning_content`：DeepSeek-R1 / 小米 MIMO / 多数中转站
 *   - `reasoning`        ：部分实现
 *   - `thinking`         ：少数实现
 * 且**不同时出现**，所以取第一个非空即可。
 *
 * 用容错取值：字段若被给成对象/数组（不良实现）时返回 null 而不是抛异常，
 * 否则一条畸形帧会让整个请求失败。
 */
private fun reasoningTextOf(delta: JsonObject): String? {
    for (key in REASONING_KEYS) {
        val value = delta.stringOrNull(key)
        if (!value.isNullOrEmpty()) return value
    }
    return null
}

/** 思考内容可能出现的字段名，按常见程度排序。 */
private val REASONING_KEYS = listOf("reasoning_content", "reasoning", "thinking")

private fun JsonObject.arrayOrNull(key: String): JsonArray? =
    runCatching { get(key)?.takeIf { it.isJsonArray }?.asJsonArray }.getOrNull()

private fun JsonObject.stringOrNull(key: String): String? =
    runCatching {
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
    }.getOrNull()

/**
 * 安全取整数。
 *
 * 为什么不能直接 `get(key)?.asInt`：该字段可能被给成对象/数组（不良实现），
 * 或给成非数字字符串，两种情况 `asInt` 都会抛（UnsupportedOperationException /
 * NumberFormatException）。工具调用的 `index` 一旦因此抛异常，
 * 整轮对话就会因为一个无关紧要的序号而失败。
 */
private fun JsonObject.intOrNull(key: String): Int? =
    runCatching { get(key)?.takeIf { it.isJsonPrimitive }?.asInt }.getOrNull()

private fun com.google.gson.JsonElement.asJsonObjectOrNull(): JsonObject? =
    runCatching { if (isJsonObject) asJsonObject else null }.getOrNull()

// ============================================================================
// 端点拼接规则（顶层函数：聊天与联网搜索必须共用同一套）
// ============================================================================

/**
 * Anthropic Messages 端点。
 *
 * 这条规则照搬 DeepSeek Harness 的 `messagesApiRoot`（在 app.asar 中实测确认）：
 *
 * ```js
 * function messagesApiRoot(baseURL) {
 *     const base = baseURL.replace(/\/+$/u, "");
 *     return new URL(base).pathname.endsWith("/v1") ? base : `${base}/v1`;
 * }
 * ```
 *
 * 三点必须同时成立：
 * 1. **去掉全部尾斜杠**，否则会拼出 `//messages`；
 * 2. **已以 `/v1` 结尾就不再追加** —— 这是幂等，不是"探测 404 后回退"。
 *    用户填 `http://host:8787` 与 `http://host:8787/v1` 都应得到同一个
 *    `/v1/messages`，绝不能出现 `/v1/v1/messages`；
 * 3. 用 `endsWith` 判断而不是正则替换 `/v1` 子串 —— `/v1` 可能合法地出现在
 *    部署路径中间（如 `/gateway/v1`），替换会破坏它。
 *
 * 历史 bug：过去聊天路径做 `trimEnd('/')` 后直接拼 `/chat/completions`，
 * 而联网搜索单独拼 `$base/messages`。两者规则不同源，于是同一个 Base URL
 * 下"聊天能通、联网搜索 404"。**新增任何端点都必须走这三个函数。**
 */
internal fun messagesEndpoint(baseUrl: String): String {
    val base = baseUrl.trim().trimEnd('/')
    if (base.isEmpty()) return ""
    return if (base.endsWith("/v1")) "$base/messages" else "$base/v1/messages"
}

/** OpenAI 兼容协议的对话端点。 */
internal fun chatCompletionsEndpoint(baseUrl: String): String {
    val base = baseUrl.trim().trimEnd('/')
    if (base.isEmpty()) return ""
    if (base.endsWith("/chat/completions")) return base
    return if (base.endsWith("/v1")) "$base/chat/completions" else "$base/v1/chat/completions"
}

/** 模型列表端点（连通性测试与"获取可用模型"共用）。 */
internal fun modelsEndpoint(baseUrl: String): String {
    val base = baseUrl.trim().trimEnd('/')
    if (base.isEmpty()) return ""
    if (base.endsWith("/models")) return base
    return if (base.endsWith("/v1")) "$base/models" else "$base/v1/models"
}
