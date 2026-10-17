package com.treechat.workbench.rag

import com.treechat.workbench.data.db.KnowledgeDao
import com.treechat.workbench.data.model.KnowledgeChunk
import com.treechat.workbench.data.model.KnowledgeDocument
import java.util.Locale
import kotlin.math.ln

/**
 * 本地知识库检索（教材 RAG）。
 *
 * ## 定位
 * 把用户提问转成 2 字组与关键术语，查倒排表并结合精确子串加权与 BM25 长度归一化重排，
 * 取 top-k 后补充邻近上下文，拼成供本地 NPU 模型或云端模型引用的教材原文证据块。
 *
 * ## 评分机制
 *  1. 查询侧二元组：`Chunker.queryGrams(query)`（去重后的 2 字组）；
 *  2. 2-gram 倒排基础分：结合词频饱和（BM25 风格 TF 饱和）与候选集逆文档频率（IDF）加权；
 *  3. 关键术语精确子串加权（Exact Key-Term Substring Boost）：对候选块检测查询中的完整术语、
 *     多词共现及章节标题匹配，并执行 BM25 风格段落长度归一化，确保学术概念定义块精准置顶；
 *  4. 同分时保持稳定顺序。
 */
class KnowledgeRetriever(private val dao: KnowledgeDao) {

    /** 一条命中的教材原文（含出处与文件夹路径信息）。 */
    data class Evidence(
        val chunkId: String,
        val documentId: String,
        val documentTitle: String,
        /** 章节/小节标题，可能为空。 */
        val section: String?,
        /** 在这份文档里的顺序号，从 0 开始，用于标注"第 N 段"。 */
        val orderIndex: Int,
        val text: String,
        /** 归一化后的相关度分数，用于展示与排序。 */
        val score: Double,
        /** 教材所属知识库文件夹路径（空字符串表示根目录）。 */
        val folderPath: String = ""
    )

    /** 检索结果。 */
    data class RetrievalResult(
        val evidences: List<Evidence>,
        /** 生成的教材原文块（可直接拼进 systemPrompt）；无命中时为空串。 */
        val contextText: String,
        /** 查询侧的 gram 数量，用于诊断查询粒度。 */
        val queryGramCount: Int,
        /** 当前使用的检索方案。 */
        val scheme: RagIndexingScheme = RagIndexingScheme.LOCAL_OFFLINE
    ) {
        val isEmpty: Boolean get() = evidences.isEmpty()

        /** 供界面显示的一句话摘要。 */
        val summary: String
            get() = if (isEmpty) {
                when (scheme) {
                    RagIndexingScheme.LOCAL_OFFLINE -> "未命中教材（本地 2-Gram，查询 ${queryGramCount} 组）"
                    RagIndexingScheme.CLOUD_EMBEDDING -> "未命中教材（云端向量检索）"
                    RagIndexingScheme.HYBRID -> "未命中教材（双路混合检索）"
                }
            } else {
                when (scheme) {
                    RagIndexingScheme.LOCAL_OFFLINE -> "命中 ${evidences.size} 段（本地 2-Gram 倒排）"
                    RagIndexingScheme.CLOUD_EMBEDDING -> "命中 ${evidences.size} 段（云端高维向量）"
                    RagIndexingScheme.HYBRID -> "命中 ${evidences.size} 段（本地+云端混合重排）"
                }
            }
    }

    /**
     * 检索并组装上下文。
     *
     * @param query 用户提问原文。
     * @param topK 取打分最高的前几块。
     */
    suspend fun retrieve(
        query: String,
        topK: Int = DEFAULT_TOP_K
    ): RetrievalResult {
        val grams = Chunker.queryGrams(query)
        if (grams.isEmpty()) {
            return RetrievalResult(emptyList(), "", 0)
        }

        val rows = dao.findEnabledGrams(grams, limit = GRAM_ROW_LIMIT)
        if (rows.isEmpty()) {
            return RetrievalResult(emptyList(), "", grams.size)
        }

        // 统计每个 2-gram 在当前候选集合中的出现块数，计算温和的 IDF 区分度权重
        val gramChunkCounts = HashMap<String, Int>(grams.size * 2)
        rows.forEach { row ->
            gramChunkCounts[row.gram] = (gramChunkCounts[row.gram] ?: 0) + 1
        }
        val totalCandidateChunks = rows.map { it.chunkId }.distinct().size.coerceAtLeast(1)

        // 第一阶段：2-gram 倒排聚合打分（含 BM25 TF 饱和与稀有度加权）
        val rawScoreByChunk = HashMap<String, Double>(totalCandidateChunks)
        val matchedGramCountByChunk = HashMap<String, Int>(totalCandidateChunks)
        rows.forEach { row ->
            val df = (gramChunkCounts[row.gram] ?: 1).toDouble()
            val idfBoost = 1.0 + 0.35 * ln(1.0 + totalCandidateChunks.toDouble() / df)
            val tfSaturated = 1.0 + 0.25 * minOf(row.freq, 5)
            val add = tfSaturated * idfBoost
            rawScoreByChunk[row.chunkId] = (rawScoreByChunk[row.chunkId] ?: 0.0) + add
            matchedGramCountByChunk[row.chunkId] = (matchedGramCountByChunk[row.chunkId] ?: 0) + 1
        }

        // 取候选池（放大候选倍数以进行精确子串 + 长度归一化重排）
        val candidatePoolSize = maxOf(topK * 6, 30)
        val candidateIds = rawScoreByChunk.entries
            .sortedByDescending { it.value }
            .take(candidatePoolSize)
            .map { it.key }

        val chunkById = dao.getChunks(candidateIds).associateBy { it.id }

        // 一次取回启用中的文档元数据（含 title 与 folderPath）
        val enabledDocsById = dao.getDocuments()
            .filter { it.enabled }
            .associateBy { it.id }

        // 提取查询中的关键学术术语与四字/三字连续短语，用于精确子串命中增强
        val keyTerms = Chunker.extractKeyTerms(query)
        val contiguousSubphrases = buildContiguousSubphrases(keyTerms)

        // 第二阶段：结合精确术语子串匹配、章节标题加成与 BM25 长度归一化重排
        val finalScoreByChunk = HashMap<String, Double>(candidateIds.size)
        for (chunkId in candidateIds) {
            val chunk = chunkById[chunkId] ?: continue
            val baseGramScore = rawScoreByChunk[chunkId] ?: 0.0
            val gramCoverage = (matchedGramCountByChunk[chunkId] ?: 0).toDouble() / grams.size.toDouble()

            val normalizedChunkText = chunk.text.lowercase()
            val cleanedChunkText = normalizedChunkText.filter { !it.isWhitespace() && !Chunker.isPunctuation(it) }
            val sectionText = chunk.section?.lowercase().orEmpty()

            // 1. 关键术语精确子串加权
            var exactTermBoost = 0.0
            var matchedKeyTermCount = 0
            for (term in keyTerms) {
                val cleanedTerm = term.filter { !it.isWhitespace() && !Chunker.isPunctuation(it) }
                if (cleanedTerm.length < 2) continue
                if (normalizedChunkText.contains(term) || cleanedChunkText.contains(cleanedTerm)) {
                    matchedKeyTermCount++
                    // 越长的学术专有名词（如"深层结构"、"转换生成语法"）精确命中权重越高
                    val lengthWeight = (cleanedTerm.length.coerceAtMost(8)).toDouble() * 0.45
                    exactTermBoost += 1.2 + lengthWeight
                }
                if (sectionText.isNotEmpty() && sectionText.contains(term)) {
                    exactTermBoost += 0.85
                }
            }

            // 多术语共现协同加成（例如同时命中"乔姆斯基"与"深层结构"）
            if (matchedKeyTermCount >= 2) {
                exactTermBoost += (matchedKeyTermCount - 1) * 1.35
            }

            // 连续学术短语（3~4 字窗口）精确子串微调加成
            for (sub in contiguousSubphrases) {
                if (cleanedChunkText.contains(sub)) {
                    exactTermBoost += 0.45
                }
            }

            // 2. BM25 风格长度归一化：抑制过长噪声块，同时防止极短孤立标题块虚高
            val charLen = chunk.text.length.coerceAtLeast(1).toDouble()
            val isHeadingOnly = chunk.section != null && chunk.text == chunk.section
            val lengthNorm = if (isHeadingOnly) {
                0.78
            } else {
                val b = 0.32
                val avgLen = 180.0
                val normDenominator = 1.0 - b + b * (charLen / avgLen).coerceIn(0.45, 2.2)
                1.0 / normDenominator
            }

            val combined = (baseGramScore * lengthNorm) + exactTermBoost + (gramCoverage * 1.5)
            finalScoreByChunk[chunkId] = combined / grams.size.toDouble()
        }

        val topIds = candidateIds
            .filter { finalScoreByChunk.containsKey(it) }
            .sortedByDescending { finalScoreByChunk[it] ?: 0.0 }
            .take(topK)

        val evidences = topIds.mapNotNull { chunkId ->
            val chunk = chunkById[chunkId] ?: return@mapNotNull null
            val doc = enabledDocsById[chunk.documentId]
            Evidence(
                chunkId = chunk.id,
                documentId = chunk.documentId,
                documentTitle = doc?.title ?: "未知文档",
                section = chunk.section,
                orderIndex = chunk.orderIndex,
                text = chunk.text,
                score = finalScoreByChunk[chunkId] ?: 0.0,
                folderPath = doc?.folderPath.orEmpty()
            )
        }

        val context = buildContext(evidences)
        return RetrievalResult(evidences, context, grams.size)
    }

    /**
     * 从长查询词串中生成 3~4 字连续子短语，辅助中文长句无空格提问时的精确短语命中。
     */
    private fun buildContiguousSubphrases(terms: List<String>): List<String> {
        val out = LinkedHashSet<String>()
        for (term in terms) {
            val cleaned = term.filter { !it.isWhitespace() && !Chunker.isPunctuation(it) }
            if (cleaned.length >= 4) {
                for (i in 0..cleaned.length - 4) {
                    out.add(cleaned.substring(i, i + 4))
                }
            } else if (cleaned.length == 3) {
                out.add(cleaned)
            }
        }
        return out.take(16)
    }

    /**
     * 统一检索入口：根据用户配置的检索模式（本地 2-gram / 云端向量 / 混合检索）分派执行。
     */
    suspend fun retrieveWithConfig(
        query: String,
        topK: Int = DEFAULT_TOP_K,
        cloudConfig: CloudRagConfig? = null,
        embeddingClient: CloudEmbeddingClient? = null
    ): RetrievalResult {
        val scheme = cloudConfig?.scheme ?: RagIndexingScheme.LOCAL_OFFLINE
        if (!cloudConfig?.enabled!! || scheme == RagIndexingScheme.LOCAL_OFFLINE) {
            return retrieve(query, topK)
        }

        if (embeddingClient == null || !cloudConfig.isValidForApi()) {
            // 云端配置不完整时无缝降级回本地离线 2-gram，确保不阻塞对话
            return retrieve(query, topK)
        }

        return when (scheme) {
            RagIndexingScheme.CLOUD_EMBEDDING -> {
                retrieveByCloudEmbedding(query, topK, cloudConfig, embeddingClient)
            }
            RagIndexingScheme.HYBRID -> {
                retrieveHybrid(query, topK, cloudConfig, embeddingClient)
            }
            RagIndexingScheme.LOCAL_OFFLINE -> retrieve(query, topK)
        }
    }

    /**
     * 云端向量检索：计算 Query 向量，与本地带有向量的切片进行余弦相似度匹配。
     */
    suspend fun retrieveByCloudEmbedding(
        query: String,
        topK: Int = DEFAULT_TOP_K,
        cloudConfig: CloudRagConfig,
        embeddingClient: CloudEmbeddingClient
    ): RetrievalResult {
        val queryVector = try {
            embeddingClient.getQueryEmbedding(query, cloudConfig)
        } catch (_: Exception) {
            null
        }

        // 若云端向量接口请求失败或返回空，平滑降级为本地 2-gram 检索
        if (queryVector == null || queryVector.isEmpty()) {
            return retrieve(query, topK)
        }

        val chunksWithVec = dao.getEnabledChunksWithEmbeddings()
        if (chunksWithVec.isEmpty()) {
            // 没有建立过向量索引的切片，降级为本地 2-gram
            return retrieve(query, topK)
        }

        val enabledDocsById = dao.getDocuments()
            .filter { it.enabled }
            .associateBy { it.id }

        val scoredChunks = chunksWithVec.mapNotNull { chunk ->
            val vec = CloudEmbeddingClient.decodeVector(chunk.embedding)
            if (vec.isEmpty()) return@mapNotNull null
            val sim = CloudEmbeddingClient.cosineSimilarity(queryVector, vec).toDouble()
            chunk to sim
        }.sortedByDescending { it.second }.take(topK)

        val evidences = scoredChunks.map { (chunk, sim) ->
            val doc = enabledDocsById[chunk.documentId]
            Evidence(
                chunkId = chunk.id,
                documentId = chunk.documentId,
                documentTitle = doc?.title ?: "未知文档",
                section = chunk.section,
                orderIndex = chunk.orderIndex,
                text = chunk.text,
                score = sim,
                folderPath = doc?.folderPath.orEmpty()
            )
        }

        val context = buildContext(evidences)
        return RetrievalResult(
            evidences = evidences,
            contextText = context,
            queryGramCount = 0,
            scheme = RagIndexingScheme.CLOUD_EMBEDDING
        )
    }

    /**
     * 双路混合检索 (Hybrid)：融合本地 2-gram 倒排打分与云端高维语义向量。
     */
    suspend fun retrieveHybrid(
        query: String,
        topK: Int = DEFAULT_TOP_K,
        cloudConfig: CloudRagConfig,
        embeddingClient: CloudEmbeddingClient
    ): RetrievalResult {
        val localResult = retrieve(query, topK = topK * 2)
        val queryVector = try {
            embeddingClient.getQueryEmbedding(query, cloudConfig)
        } catch (_: Exception) {
            null
        }

        if (queryVector == null || queryVector.isEmpty()) {
            return localResult.copy(scheme = RagIndexingScheme.LOCAL_OFFLINE)
        }

        val candidateChunkIds = localResult.evidences.map { it.chunkId }.toSet()
        val allEnabledChunksWithVec = dao.getEnabledChunksWithEmbeddings().associateBy { it.id }

        val mergedScores = HashMap<String, Double>()
        val localMaxScore = localResult.evidences.maxOfOrNull { it.score }?.coerceAtLeast(0.001) ?: 1.0

        // 归一化本地分数并加权 (权重 0.4)
        localResult.evidences.forEach { ev ->
            val normLocal = (ev.score / localMaxScore).coerceIn(0.0, 1.0)
            mergedScores[ev.chunkId] = normLocal * 0.4
        }

        // 计算向量相似度并加权 (权重 0.6)
        allEnabledChunksWithVec.forEach { (chunkId, chunk) ->
            val vec = CloudEmbeddingClient.decodeVector(chunk.embedding)
            if (vec.isNotEmpty()) {
                val sim = CloudEmbeddingClient.cosineSimilarity(queryVector, vec).toDouble()
                val current = mergedScores[chunkId] ?: 0.0
                mergedScores[chunkId] = current + (sim * 0.6)
            }
        }

        val topIds = mergedScores.entries
            .sortedByDescending { it.value }
            .take(topK)
            .map { it.key }

        val chunkById = dao.getChunks(topIds).associateBy { it.id }
        val enabledDocsById = dao.getDocuments()
            .filter { it.enabled }
            .associateBy { it.id }

        val evidences = topIds.mapNotNull { chunkId ->
            val chunk = chunkById[chunkId] ?: return@mapNotNull null
            val doc = enabledDocsById[chunk.documentId]
            Evidence(
                chunkId = chunk.id,
                documentId = chunk.documentId,
                documentTitle = doc?.title ?: "未知文档",
                section = chunk.section,
                orderIndex = chunk.orderIndex,
                text = chunk.text,
                score = mergedScores[chunkId] ?: 0.0,
                folderPath = doc?.folderPath.orEmpty()
            )
        }

        val context = buildContext(evidences)
        return RetrievalResult(
            evidences = evidences,
            contextText = context,
            queryGramCount = localResult.queryGramCount,
            scheme = RagIndexingScheme.HYBRID
        )
    }

    /**
     * 把证据拼成"教材原文"块。
     *
     * 对每一条命中额外取前后各 1 块作为上下文补充，并以 [MAX_CONTEXT_CHARS] 为硬上限。
     */
    private suspend fun buildContext(evidences: List<Evidence>): String {
        if (evidences.isEmpty()) return ""
        val sb = StringBuilder()

        for ((index, ev) in evidences.withIndex()) {
            val neighbours = dao.getNeighbourChunks(
                documentId = ev.documentId,
                fromIndex = ev.orderIndex - NEIGHBOUR_RADIUS,
                toIndex = ev.orderIndex + NEIGHBOUR_RADIUS
            )

            val body = StringBuilder()
            if (neighbours.isEmpty()) {
                body.append(ev.text)
            } else {
                neighbours.forEach { c ->
                    if (c.id == ev.chunkId) {
                        body.append("【核心命中段】").append(c.text)
                    } else {
                        body.append("【邻段补充】").append(c.text)
                    }
                    body.append('\n')
                }
            }

            val folderLabel = ev.folderPath.takeIf { it.isNotBlank() }?.let { "[$it] " }.orEmpty()
            val section = ev.section?.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
            val block = buildString {
                append("【证据 ").append(index + 1).append("】")
                append(folderLabel)
                append("《").append(ev.documentTitle).append("》")
                append(section)
                append(" · 第 ").append(ev.orderIndex + 1).append(" 段（相关度 ")
                append(String.format(Locale.US, "%.2f", ev.score))
                append("）\n")
                append(body.toString().trim())
                append("\n\n")
            }

            if (sb.length + block.length > MAX_CONTEXT_CHARS) break
            sb.append(block)
        }

        return sb.toString().trimEnd()
    }

    companion object {
        /** 默认取的块数。 */
        const val DEFAULT_TOP_K = 5

        /** 补上下文的半径：前后各 1 块。 */
        const val NEIGHBOUR_RADIUS = 1

        /** 拼进 prompt 的教材原文硬上限（字符）。超出部分丢弃。 */
        const val MAX_CONTEXT_CHARS = 6000

        /** 单次查倒排表的行数上限。 */
        const val GRAM_ROW_LIMIT = 6000

        /**
         * 组装给模型的 systemPrompt 片段。
         *
         * 同时兼容本地端侧 NPU 模型与云端大模型：
         * 强制模型在引用任何知识库切片时，必须以标准 Markdown 引用块头部
         * `> **【知识库原文引述 · 出处：《文档标题》· 第 X 段】**` 显式告知用户正在引述知识库原文，
         * 随后逐字列出原文，并在 `**【拓展解析】**` 下方展开讲解。
         */
        fun buildSystemPromptSection(result: RetrievalResult): String {
            if (result.isEmpty) return ""
            return buildString {
                append("【本地知识库检索原文】以下是本次提问从本地教材知识库中检索到的高相关度原文切片：\n\n")
                append(result.contextText)
                append("\n\n")
                append("【强制回答与原文引述规范（本地 NPU 模型与云端模型均须严格执行）】\n")
                append("1. 当你使用或参考上述任何教材切片回答问题时，必须在回答开头显式告知用户正在引述本地知识库原文，严格采用以下 Markdown 引用格式（请将《文档标题》与第 X 段替换为对应证据的真实标题与段落号）：\n")
                append("> **【知识库原文引述 · 出处：《文档标题》· 第 X 段】**\n")
                append("> 这里逐字摘录上述【核心命中段】中的教材原文内容，不得篡改或省略关键定义。\n\n")
                append("2. 在完成原文引述块之后，空一行，使用以下标题展开你的讲解与分析：\n")
                append("**【拓展解析】**\n")
                append("在此处基于所引述的教材原文，条理清晰地解答用户问题，并在关键结论后标注证据编号（如【证据 1】）。\n")
                append("3. 若检索到的多段原文均相关，可依次列出多个 `> **【知识库原文引述 · 出处：《文档标题》· 第 X 段】**` 引用块，再统一输出 `**【拓展解析】**`。\n")
                append("4. 严禁编造教材原文：若上述检索片段与用户提问完全无关，请明确说明“本地知识库中未检索到与该问题直接相关的教材原文”，然后再提供常规学术解答。")
            }
        }

        /** 供界面/日志使用的一行出处列表。 */
        fun citationsOf(result: RetrievalResult): List<String> =
            result.evidences.mapIndexed { i, ev ->
                val folderPrefix = ev.folderPath.takeIf { it.isNotBlank() }?.let { "[$it] " }.orEmpty()
                val section = ev.section?.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
                "【${i + 1}】$folderPrefix《${ev.documentTitle}》$section · 第 ${ev.orderIndex + 1} 段"
            }

        /** 空结果常量，便于调用方复用。 */
        val EMPTY = RetrievalResult(emptyList(), "", 0)

        /**
         * 在内存中对给定的（文档, 切片列表）集合执行与 [retrieve] 完全一致的
         * 2-gram + IDF + 关键术语精确子串加权 + BM25 长度归一化检索评分。
         *
         * 供知识库管理页的“检索命中测试”实时预览命中切片与得分。
         */
        fun rankChunksInMemory(
            query: String,
            documentsWithChunks: List<Pair<KnowledgeDocument, List<KnowledgeChunk>>>,
            topK: Int = DEFAULT_TOP_K
        ): RetrievalResult {
            val grams = Chunker.queryGrams(query)
            if (grams.isEmpty()) return EMPTY
            val queryGramSet = grams.toSet()

            val allChunks = ArrayList<Pair<KnowledgeDocument, KnowledgeChunk>>()
            documentsWithChunks.forEach { (doc, chunks) ->
                if (doc.enabled) {
                    chunks.forEach { chunk -> allChunks.add(doc to chunk) }
                }
            }
            if (allChunks.isEmpty()) return RetrievalResult(emptyList(), "", grams.size)

            val gramFreqByChunk = HashMap<String, List<Chunker.GramFreq>>(allChunks.size)
            val gramChunkCounts = HashMap<String, Int>(grams.size * 2)

            allChunks.forEach { (_, chunk) ->
                val matched = Chunker.bigrams(chunk.text).filter { it.gram in queryGramSet }
                if (matched.isNotEmpty()) {
                    gramFreqByChunk[chunk.id] = matched
                    matched.forEach { gf ->
                        gramChunkCounts[gf.gram] = (gramChunkCounts[gf.gram] ?: 0) + 1
                    }
                }
            }

            if (gramFreqByChunk.isEmpty()) {
                return RetrievalResult(emptyList(), "", grams.size)
            }

            val totalCandidateChunks = gramFreqByChunk.size.coerceAtLeast(1)
            val keyTerms = Chunker.extractKeyTerms(query)
            val subphrases = LinkedHashSet<String>().apply {
                for (term in keyTerms) {
                    val cleaned = term.filter { !it.isWhitespace() && !Chunker.isPunctuation(it) }
                    if (cleaned.length >= 4) {
                        for (i in 0..cleaned.length - 4) add(cleaned.substring(i, i + 4))
                    } else if (cleaned.length == 3) {
                        add(cleaned)
                    }
                }
            }.take(16)

            val scoredEvidences = ArrayList<Evidence>(gramFreqByChunk.size)
            for ((doc, chunk) in allChunks) {
                val matchedGrams = gramFreqByChunk[chunk.id] ?: continue
                var baseGramScore = 0.0
                for (gf in matchedGrams) {
                    val df = (gramChunkCounts[gf.gram] ?: 1).toDouble()
                    val idfBoost = 1.0 + 0.35 * ln(1.0 + totalCandidateChunks.toDouble() / df)
                    val tfSaturated = 1.0 + 0.25 * minOf(gf.freq, 5)
                    baseGramScore += tfSaturated * idfBoost
                }
                val gramCoverage = matchedGrams.size.toDouble() / grams.size.toDouble()

                val normalizedChunkText = chunk.text.lowercase()
                val cleanedChunkText = normalizedChunkText.filter { !it.isWhitespace() && !Chunker.isPunctuation(it) }
                val sectionText = chunk.section?.lowercase().orEmpty()

                var exactTermBoost = 0.0
                var matchedKeyTermCount = 0
                for (term in keyTerms) {
                    val cleanedTerm = term.filter { !it.isWhitespace() && !Chunker.isPunctuation(it) }
                    if (cleanedTerm.length < 2) continue
                    if (normalizedChunkText.contains(term) || cleanedChunkText.contains(cleanedTerm)) {
                        matchedKeyTermCount++
                        val lengthWeight = (cleanedTerm.length.coerceAtMost(8)).toDouble() * 0.45
                        exactTermBoost += 1.2 + lengthWeight
                    }
                    if (sectionText.isNotEmpty() && sectionText.contains(term)) {
                        exactTermBoost += 0.85
                    }
                }
                if (matchedKeyTermCount >= 2) {
                    exactTermBoost += (matchedKeyTermCount - 1) * 1.35
                }
                for (sub in subphrases) {
                    if (cleanedChunkText.contains(sub)) {
                        exactTermBoost += 0.45
                    }
                }

                val charLen = chunk.text.length.coerceAtLeast(1).toDouble()
                val isHeadingOnly = chunk.section != null && chunk.text == chunk.section
                val lengthNorm = if (isHeadingOnly) {
                    0.78
                } else {
                    val b = 0.32
                    val avgLen = 180.0
                    val normDenominator = 1.0 - b + b * (charLen / avgLen).coerceIn(0.45, 2.2)
                    1.0 / normDenominator
                }

                val finalScore = ((baseGramScore * lengthNorm) + exactTermBoost + (gramCoverage * 1.5)) / grams.size.toDouble()
                scoredEvidences.add(
                    Evidence(
                        chunkId = chunk.id,
                        documentId = chunk.documentId,
                        documentTitle = doc.title,
                        section = chunk.section,
                        orderIndex = chunk.orderIndex,
                        text = chunk.text,
                        score = finalScore,
                        folderPath = doc.folderPath
                    )
                )
            }

            val topEvidences = scoredEvidences
                .sortedByDescending { it.score }
                .take(topK)

            val previewContext = buildString {
                topEvidences.forEachIndexed { idx, ev ->
                    val folderLabel = ev.folderPath.takeIf { it.isNotBlank() }?.let { "[$it] " }.orEmpty()
                    val section = ev.section?.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
                    append("【证据 ").append(idx + 1).append("】")
                    append(folderLabel)
                    append("《").append(ev.documentTitle).append("》")
                    append(section)
                    append(" · 第 ").append(ev.orderIndex + 1).append(" 段（相关度 ")
                    append(String.format(Locale.US, "%.2f", ev.score))
                    append("）\n")
                    append(ev.text)
                    append("\n\n")
                }
            }.trimEnd()

            return RetrievalResult(topEvidences, previewContext, grams.size)
        }
    }
}
